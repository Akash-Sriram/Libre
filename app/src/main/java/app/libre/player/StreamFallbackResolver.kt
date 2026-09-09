package app.libre.player

import android.util.Log
import app.libre.api.RetrofitInstance
import app.libre.api.obj.MediaStream
import app.libre.api.obj.Streams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Secondary YouTube stream resolver providing resilience against breaking YouTube cipher
 * rotations, bandwidth throttling, and HTTP 403 Forbidden errors.
 *
 * Modeled after Faraday and InnerTubeX stream resolution paradigms.
 * Fetches authenticated iOS / UMP stream definitions and formats to ensure uninterrupted playback.
 */
object StreamFallbackResolver {
    private const val TAG = "StreamFallbackResolver"
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    private data class CachedStream(
        val streams: Streams,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CachedStream>()

    /**
     * Invalidate cached stream for the given video ID, forcing fresh resolution on next call.
     */
    fun invalidateCache(videoId: String) {
        cache.remove(videoId)
    }

    /**
     * Resolve YouTube streams via fallback InnerTube client identity.
     * Returns a valid [Streams] object if successful, or null if resolution fails.
     */
    suspend fun resolveStream(videoId: String): Streams? = withContext(Dispatchers.IO) {
        if (videoId.length != 11) return@withContext null

        // 1. Check in-memory cache
        val cached = cache[videoId]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
            Log.d(TAG, "Serving fallback stream for $videoId from cache")
            return@withContext cached.streams
        }

        try {
            // 2. Build iOS client player payload
            val jsonPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "21.26.4")
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iPhone")
                        put("osVersion", "18.3.2.22D82")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }.toString()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonPayload.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(PLAYER_URL)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
                .header("X-YouTube-Client-Name", "5")
                .header("X-YouTube-Client-Version", "21.26.4")
                .build()

            RetrofitInstance.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Fallback player request failed with HTTP ${response.code}")
                    return@withContext null
                }

                val bodyStr = response.body.string()
                if (bodyStr.isBlank()) return@withContext null

                val root = JSONObject(bodyStr)
                val playabilityStatus = root.optJSONObject("playabilityStatus")
                val status = playabilityStatus?.optString("status")
                if (!status.equals("OK", ignoreCase = true)) {
                    Log.w(TAG, "Fallback video playability status not OK: $status (${playabilityStatus?.optString("reason")})")
                    return@withContext null
                }

                val videoDetails = root.optJSONObject("videoDetails")
                val title = videoDetails?.optString("title") ?: "Unknown Title"
                val author = videoDetails?.optString("author") ?: "Unknown Artist"
                val durationSec = videoDetails?.optLong("lengthSeconds") ?: 0L
                val thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                val streamingData = root.optJSONObject("streamingData") ?: return@withContext null
                val serverAbrUrl = streamingData.optString("serverAbrStreamingUrl").takeIf { it.isNotBlank() }

                val playerConfig = root.optJSONObject("playerConfig")
                val mediaCommon = playerConfig?.optJSONObject("mediaCommonConfig")
                val mediaUstreamer = mediaCommon?.optJSONObject("mediaUstreamerRequestConfig")
                val ustreamerConfig = mediaUstreamer?.optString("videoPlaybackUstreamerConfig")?.takeIf { it.isNotBlank() }

                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                val audioStreams = mutableListOf<MediaStream>()
                val videoStreams = mutableListOf<MediaStream>()

                if (adaptiveFormats != null) {
                    for (i in 0 until adaptiveFormats.length()) {
                        val fmt = adaptiveFormats.getJSONObject(i)
                        val mimeType = fmt.optString("mimeType")
                        val itag = fmt.optInt("itag")
                        val bitrate = fmt.optInt("bitrate")
                        val contentLength = fmt.optLong("contentLength")
                        val approxDurationMs = fmt.optLong("approxDurationMs")
                        val directUrl = fmt.optString("url").takeIf { it.isNotBlank() }

                        val initRange = fmt.optJSONObject("initRange")
                        val initStart = initRange?.optInt("start")
                        val initEnd = initRange?.optInt("end")

                        val indexRange = fmt.optJSONObject("indexRange")
                        val indexStart = indexRange?.optInt("start")
                        val indexEnd = indexRange?.optInt("end")

                        val codec = extractCodec(mimeType)
                        val containerMime = mimeType.substringBefore(";").trim()

                        if (mimeType.startsWith("audio/")) {
                            audioStreams.add(
                                MediaStream(
                                    url = directUrl,
                                    format = containerMime,
                                    quality = "${bitrate / 1000} kbps",
                                    mimeType = containerMime,
                                    codec = codec,
                                    videoOnly = false,
                                    bitrate = bitrate,
                                    initStart = initStart,
                                    initEnd = initEnd,
                                    indexStart = indexStart,
                                    indexEnd = indexEnd,
                                    durationMs = approxDurationMs,
                                    contentLength = contentLength,
                                    itag = itag,
                                    lastModified = fmt.optLong("lastModified")
                                )
                            )
                        } else if (mimeType.startsWith("video/")) {
                            videoStreams.add(
                                MediaStream(
                                    url = directUrl,
                                    format = containerMime,
                                    quality = fmt.optString("qualityLabel"),
                                    mimeType = containerMime,
                                    codec = codec,
                                    videoOnly = true,
                                    bitrate = bitrate,
                                    width = fmt.optInt("width"),
                                    height = fmt.optInt("height"),
                                    fps = fmt.optInt("fps"),
                                    initStart = initStart,
                                    initEnd = initEnd,
                                    indexStart = indexStart,
                                    indexEnd = indexEnd,
                                    durationMs = approxDurationMs,
                                    contentLength = contentLength,
                                    itag = itag
                                )
                            )
                        }
                    }
                }

                if (audioStreams.isEmpty() && serverAbrUrl == null) {
                    Log.w(TAG, "Fallback resolver found no usable audio or SABR streams for $videoId")
                    return@withContext null
                }

                val resolved = Streams(
                    title = title,
                    uploader = author,
                    thumbnailUrl = thumbnailUrl,
                    duration = durationSec,
                    audioStreams = audioStreams,
                    videoStreams = videoStreams,
                    serverAbrStreamingUrl = serverAbrUrl,
                    videoPlaybackUstreamerConfig = ustreamerConfig
                )

                cache[videoId] = CachedStream(resolved, System.currentTimeMillis())
                Log.i(TAG, "Successfully resolved fallback streams for $videoId (audioCount=${audioStreams.size}, hasSABR=${serverAbrUrl != null})")
                return@withContext resolved
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving fallback streams for $videoId", e)
            null
        }
    }

    private fun extractCodec(mimeType: String): String {
        val codecMatch = Regex("""codecs="([^"]+)"""").find(mimeType)
            ?: Regex("""codecs=([a-zA-Z0-9._-]+)""").find(mimeType)
        return codecMatch?.groupValues?.get(1) ?: when {
            mimeType.contains("mp4a") -> "mp4a.40.2"
            mimeType.contains("opus") -> "opus"
            mimeType.contains("vp09") -> "vp09.00.50.08"
            mimeType.contains("avc1") -> "avc1.640028"
            else -> ""
        }
    }
}
