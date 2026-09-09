package app.libre.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import app.libre.helpers.LocalAudioMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

object MusicRecognitionService {
    private const val RECORDING_SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val RECORDING_DURATION_MS = 12000L
    private const val TARGET_SAMPLE_RATE = 16000

    private val _recognitionStatus = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val recognitionStatus: StateFlow<RecognitionStatus> = _recognitionStatus.asStateFlow()

    fun reset() {
        _recognitionStatus.value = RecognitionStatus.Ready
    }

    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun recognize(context: Context): RecognitionStatus = withContext(Dispatchers.IO) {
        if (!hasRecordPermission(context)) {
            val err = RecognitionStatus.Error("Microphone permission not granted")
            _recognitionStatus.value = err
            return@withContext err
        }

        _recognitionStatus.value = RecognitionStatus.Listening

        try {
            // Step 1: Record PCM from microphone
            val audioData = recordAudio()
            if (audioData.isEmpty()) {
                val err = RecognitionStatus.Error("Failed to record audio from microphone")
                _recognitionStatus.value = err
                return@withContext err
            }

            _recognitionStatus.value = RecognitionStatus.Processing

            // Step 2: Resample 44.1kHz -> 16kHz
            val decoded = DecodedAudio(
                data = audioData,
                channelCount = 1,
                sampleRate = RECORDING_SAMPLE_RATE,
                pcmEncoding = AUDIO_FORMAT
            )
            val resampled = AudioResampler.resample(decoded, TARGET_SAMPLE_RATE).getOrElse { error ->
                val err = RecognitionStatus.Error("Audio resampling failed: ")
                _recognitionStatus.value = err
                return@withContext err
            }

            // Step 3: Compute Shazam FFT signature
            val signature = ShazamSignatureGenerator.fromI16(resampled.data)
            val sampleDurationMs = (resampled.data.size / 2) * 1000L / TARGET_SAMPLE_RATE

            // Step 4: Dispatch to Shazam API
            val result = Shazam.recognize(signature, sampleDurationMs)

            result.fold(
                onSuccess = { recResult ->
                    // Check local-first offline library!
                    val localMatch = LocalAudioMatcher.getLocalPathByTitle(recResult.title, recResult.artist)
                    val enrichedResult = recResult.copy(localFilePath = localMatch)

                    val success = RecognitionStatus.Success(enrichedResult)
                    _recognitionStatus.value = success
                    success
                },
                onFailure = { error ->
                    val msg = error.message.orEmpty()
                    val status = if (msg.contains("No match", ignoreCase = true)) {
                        RecognitionStatus.NoMatch()
                    } else if (msg.contains("Unable to resolve host", ignoreCase = true) || msg.contains("No address associated", ignoreCase = true) || msg.contains("Network is unreachable", ignoreCase = true)) {
                        RecognitionStatus.Error("No internet connection. Please connect to Wi-Fi or mobile data to query Shazam.")
                    } else {
                        RecognitionStatus.Error(msg.ifBlank { "Recognition failed. Please try again." })
                    }
                    _recognitionStatus.value = status
                    status
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val err = RecognitionStatus.Error(e.message ?: "Recognition failed")
            _recognitionStatus.value = err
            err
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): ByteArray = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(
            RECORDING_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(4096)

        val audioRecord = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(RECORDING_SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RECORDING_SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
        }

        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)

        try {
            audioRecord.startRecording()
            val startTime = System.currentTimeMillis()

            while (isActive && (System.currentTimeMillis() - startTime) < RECORDING_DURATION_MS) {
                val bytesRead = audioRecord.read(buffer, 0, bufferSize)
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }

            audioRecord.stop()
            audioRecord.release()
        } catch (e: Exception) {
            try { audioRecord.release() } catch (_: Exception) {}
        }

        outputStream.toByteArray()
    }
}
