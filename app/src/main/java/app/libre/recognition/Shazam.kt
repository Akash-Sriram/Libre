package app.libre.recognition

import app.libre.api.RetrofitInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object Shazam {
    private const val TAG = "ShazamApi"
    private const val MIN_REQUEST_INTERVAL_MS = 1000L
    private const val MAX_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 2000L

    private var lastRequestTime = 0L
    private val requestMutex = Mutex()
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val userAgents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
        "Dalvik/1.6.0 (Linux; U; Android 4.4.2; SM-T210 Build/KOT49H)",
        "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
        "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)"
    )

    private val timezones = listOf(
        "Europe/Paris",
        "Europe/London",
        "America/New_York",
        "America/Los_Angeles",
        "Asia/Tokyo",
        "Asia/Dubai",
        "Asia/Kolkata"
    )

    suspend fun recognize(
        signature: String,
        sampleDurationMs: Long
    ): Result<RecognitionResult> = withContext(Dispatchers.IO) {
        val cacheKey = signature.hashCode().toString()
        val cached = resultCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < 300_000L) {
            return@withContext Result.success(cached.result)
        }

        try {
            val res = requestMutex.withLock {
                val secondCheck = resultCache[cacheKey]
                if (secondCheck != null && (System.currentTimeMillis() - secondCheck.timestamp) < 300_000L) {
                    secondCheck.result
                } else {
                    val fresh = executeRequest(signature, sampleDurationMs)
                    resultCache[cacheKey] = CachedResult(System.currentTimeMillis(), fresh)
                    fresh
                }
            }
            Result.success(res)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Result.failure(exception)
        }
    }

    private suspend fun executeRequest(
        signature: String,
        sampleDurationMs: Long
    ): RecognitionResult {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                enforceRateLimit()
                return performRecognition(signature, sampleDurationMs)
            } catch (e: Exception) {
                lastException = e
                if (e.message?.contains("429") == true || e.message?.contains("Too many requests", ignoreCase = true) == true) {
                    if (attempt < MAX_RETRIES - 1) {
                        val delayTime = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                        delay(delayTime)
                        continue
                    }
                } else {
                    throw e
                }
            }
        }

        throw lastException ?: Exception("Recognition failed after  attempts")
    }

    private fun performRecognition(
        signature: String,
        sampleDurationMs: Long
    ): RecognitionResult {
        val timestamp = System.currentTimeMillis() / 1000
        val uuid1 = UUID.randomUUID().toString().uppercase()
        val uuid2 = UUID.randomUUID().toString()

        val requestObj = ShazamRequestJson(
            geolocation = ShazamRequestJson.Geolocation(
                altitude = Random.nextDouble() * 400 + 100,
                latitude = Random.nextDouble() * 180 - 90,
                longitude = Random.nextDouble() * 360 - 180
            ),
            signature = ShazamRequestJson.Signature(
                samplems = sampleDurationMs,
                timestamp = timestamp,
                uri = signature
            ),
            timestamp = timestamp,
            timezone = timezones.random()
        )

        val jsonBody = json.encodeToString(ShazamRequestJson.serializer(), requestObj)
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$uuid1/$uuid2?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", userAgents.random())
            .header("Content-Language", "en_US")
            .build()

        val response = RetrofitInstance.httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            when (code) {
                429 -> throw Exception("Too many requests")
                404 -> throw Exception("No match found")
                in 500..599 -> throw Exception("Shazam service temporarily unavailable")
                else -> throw Exception("Recognition failed (error $code)")
            }
        }

        val bodyString = response.body.string()
        val parsed = json.decodeFromString(ShazamResponseJson.serializer(), bodyString)
        return parsed.toRecognitionResult() ?: throw Exception("No matches found")
    }

    private suspend fun enforceRateLimit() {
        val now = System.currentTimeMillis()
        val diff = now - lastRequestTime
        if (diff < MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - diff)
        }
        lastRequestTime = System.currentTimeMillis()
    }

    private fun ShazamResponseJson.toRecognitionResult(): RecognitionResult? {
        val t = this.track ?: return null

        val songSection = t.sections?.find { it?.type == "SONG" }
        val metadata = songSection?.metadata
        val album = metadata?.find { it?.title == "Album" }?.text
        val label = metadata?.find { it?.title == "Label" }?.text
        val releaseDate = metadata?.find { it?.title == "Released" }?.text
        val lyricsSection = t.sections?.find { it?.type == "LYRICS" }
        val lyrics = lyricsSection?.text

        return RecognitionResult(
            trackId = t.key ?: tagid ?: "",
            title = t.title ?: "",
            artist = t.subtitle ?: "",
            album = album,
            coverArtUrl = t.images?.coverart,
            coverArtHqUrl = t.images?.coverarthq,
            genre = t.genres?.primary,
            releaseDate = releaseDate,
            label = label,
            lyrics = lyrics,
            shazamUrl = t.url,
            appleMusicUrl = null,
            isrc = t.isrc,
            localFilePath = null
        )
    }

    private data class CachedResult(
        val timestamp: Long,
        val result: RecognitionResult
    )
}
