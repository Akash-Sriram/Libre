package app.libre.recognition

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.libre.R
import app.libre.ui.activities.MainActivity
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground Service that performs Shazam music recognition in the background.
 *
 * This allows recognizing audio currently playing from a website (Chrome/Firefox),
 * social app (Instagram, TikTok, YouTube), or ambiently, WITHOUT navigating away
 * or stopping the background media playback.
 *
 * When complete, updates the notification with the track name, artist, and album art,
 * with a one-tap action to play or view the track in Libre.
 *
 * Ported from Metrolist for Libre.
 */
class RecognitionForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var recognitionJob: Job? = null
    private var statusJob: Job? = null
    private var isTerminal = false

    private val imageLoader by lazy { ImageLoader.Builder(this).build() }

    private var floatingOverlay: FloatingRecognitionOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (recognitionJob?.isActive != true && startInForeground()) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                if (floatingOverlay == null) {
                    floatingOverlay = FloatingRecognitionOverlay(this)
                }
                floatingOverlay?.show()
            }
            startRecognition()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingOverlay?.dismiss()
        floatingOverlay = null
        serviceScope.cancel()
    }

    private fun startInForeground(): Boolean {
        val initialNotification = buildNotification(
            title = getString(R.string.identify_music),
            content = getString(R.string.recognition_notification_listening),
            ongoing = true
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
            return true
        } catch (_: Exception) {
            stopSelf()
            return false
        }
    }

    private fun startRecognition() {
        if (recognitionJob?.isActive == true) return
        isTerminal = false
        MusicRecognitionService.reset()

        statusJob?.cancel()
        statusJob = serviceScope.launch {
            MusicRecognitionService.recognitionStatus.collect { status ->
                if (status !is RecognitionStatus.Ready) {
                    renderStatus(status)
                }
            }
        }

        recognitionJob = serviceScope.launch {
            val result = MusicRecognitionService.recognize(this@RecognitionForegroundService)
            if (result is RecognitionStatus.Error &&
                MusicRecognitionService.recognitionStatus.value !is RecognitionStatus.Error
            ) {
                renderStatus(result)
            }
        }
    }

    private fun renderStatus(status: RecognitionStatus) {
        when (status) {
            is RecognitionStatus.Listening -> {
                updateNotification(
                    title = getString(R.string.identify_music),
                    content = getString(R.string.recognition_notification_listening),
                    ongoing = true
                )
            }
            is RecognitionStatus.Processing -> {
                updateNotification(
                    title = getString(R.string.identify_music),
                    content = getString(R.string.recognition_notification_processing),
                    ongoing = true
                )
            }
            is RecognitionStatus.Success -> {
                handleSuccess(status.result)
            }
            is RecognitionStatus.NoMatch -> {
                finishWithMessage(getString(R.string.recognition_notification_no_match))
            }
            is RecognitionStatus.Error -> {
                finishWithMessage(status.message.ifBlank { getString(R.string.recognition_notification_failed) })
            }
            is RecognitionStatus.Ready -> {
                // Do nothing
            }
        }
    }

    private fun handleSuccess(result: RecognitionResult) {
        if (isTerminal) return
        isTerminal = true

        if (floatingOverlay != null) {
            // Floating overlay card is active on screen — remove notification completely to avoid repetitive UI!
            stopForeground(STOP_FOREGROUND_REMOVE)
            try {
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
        } else {
            val contentIntent = createResultPendingIntent(result)

            serviceScope.launch {
                val coverBitmap = (result.coverArtHqUrl ?: result.coverArtUrl)?.let { url ->
                    withTimeoutOrNull(2500L) { loadBitmap(url) }
                }

                updateNotification(
                    title = result.title,
                    content = result.artist + if (!result.album.isNullOrBlank()) " • ${result.album}" else "",
                    largeIcon = coverBitmap,
                    contentIntent = contentIntent,
                    ongoing = false,
                    autoCancel = true
                )

                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun finishWithMessage(message: String) {
        if (isTerminal) return
        isTerminal = true

        if (floatingOverlay != null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            try {
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
        } else {
            updateNotification(
                title = getString(R.string.identify_music),
                content = message,
                ongoing = false,
                autoCancel = true
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun updateNotification(
        title: String,
        content: String,
        largeIcon: Bitmap? = null,
        contentIntent: PendingIntent? = null,
        ongoing: Boolean = false,
        autoCancel: Boolean = false
    ) {
        val notification = buildNotification(title, content, largeIcon, contentIntent, ongoing, autoCancel)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun buildNotification(
        title: String,
        content: String,
        largeIcon: Bitmap? = null,
        contentIntent: PendingIntent? = null,
        ongoing: Boolean = false,
        autoCancel: Boolean = false
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle(title)
        .setContentText(content)
        .setStyle(NotificationCompat.BigTextStyle().bigText(content))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(ongoing)
        .setAutoCancel(autoCancel)
        .apply {
            if (largeIcon != null) setLargeIcon(largeIcon)
            if (contentIntent != null) {
                setContentIntent(contentIntent)
                addAction(
                    R.drawable.ic_play,
                    getString(R.string.play_track),
                    contentIntent
                )
            }
        }
        .build()

    private fun createResultPendingIntent(result: RecognitionResult): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_RECOGNITION_RESULT_TITLE, result.title)
            putExtra(EXTRA_RECOGNITION_RESULT_ARTIST, result.artist)
            putExtra(EXTRA_RECOGNITION_RESULT_COVER, result.coverArtHqUrl ?: result.coverArtUrl)
            putExtra(EXTRA_RECOGNITION_RESULT_ALBUM, result.album)
            putExtra(EXTRA_RECOGNITION_RESULT_LOCAL_PATH, result.localFilePath)
            putExtra(MainActivity.EXTRA_TRIGGER_RECOGNITION, true)
        }

        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(this@RecognitionForegroundService)
                .data(url)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            (result.image as? coil3.BitmapImage)?.bitmap
                ?: ((result.image as? BitmapDrawable)?.bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recognition_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.recognition_notification_channel)
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 4209
        const val CHANNEL_ID = "shazam_music_recognition"

        const val EXTRA_RECOGNITION_RESULT_TITLE = "rec_title"
        const val EXTRA_RECOGNITION_RESULT_ARTIST = "rec_artist"
        const val EXTRA_RECOGNITION_RESULT_COVER = "rec_cover"
        const val EXTRA_RECOGNITION_RESULT_ALBUM = "rec_album"
        const val EXTRA_RECOGNITION_RESULT_LOCAL_PATH = "rec_local_path"
    }
}
