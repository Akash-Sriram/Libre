package app.libre.lyrics

import android.util.Base64
import app.libre.api.RetrofitInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Multi-Source Lyrics Fallback Provider.
 * Queries:
 * 1. Apple Music TTML via BetterLyrics API (`https://lyrics-api.boidu.dev/getLyrics`)
 * 2. KuGou Synced Lyrics API (`https://lyrics.kugou.com`)
 *
 * Ported from Metrolist for Libre.
 */
object LyricsFallbackHelper {
    private const val TAG = "LyricsFallbackHelper"

    /**
     * Fetch synced lyrics from Apple Music (via BetterLyrics TTML) or KuGou.
     * Returns a map with "synced" and "plain" keys if found, or null.
     */
    suspend fun fetchFallbackLyrics(
        title: String,
        artist: String,
        durationSeconds: Long = 0L,
        album: String? = null
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanArtistName(artist)

        if (cleanTitle.isBlank()) return@withContext null

        // 1. Try Apple Music TTML (BetterLyrics)
        fetchFromAppleMusicTTML(cleanTitle, cleanArtist, durationSeconds, album)?.let { return@withContext it }

        // 2. Try KuGou (great for regional, indie, and Asian tracks)
        fetchFromKuGou(cleanTitle, cleanArtist, durationSeconds, album)?.let { return@withContext it }

        null
    }

    private fun fetchFromAppleMusicTTML(
        title: String,
        artist: String,
        durationSeconds: Long,
        album: String?
    ): Map<String, String>? {
        return try {
            val urlBuilder = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrlOrNull()!!.newBuilder()
            urlBuilder.addQueryParameter("s", title)
            if (artist.isNotBlank()) {
                urlBuilder.addQueryParameter("a", artist)
            }
            if (durationSeconds > 0) {
                urlBuilder.addQueryParameter("d", durationSeconds.toString())
            }
            if (!album.isNullOrBlank()) {
                urlBuilder.addQueryParameter("album", album)
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .build()

            RetrofitInstance.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body.string()
                if (bodyStr.isBlank()) return null

                val json = JSONObject(bodyStr)
                val ttml = json.optString("ttml")
                if (ttml.isNotBlank()) {
                    val syncedLrc = TTMLParser.ttmlToLrc(ttml)
                    if (!syncedLrc.isNullOrBlank()) {
                        val plain = stripLrcTimestamps(syncedLrc)
                        return mapOf(
                            "synced" to syncedLrc,
                            "plain" to plain
                        )
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromKuGou(
        title: String,
        artist: String,
        durationSeconds: Long,
        album: String?
    ): Map<String, String>? {
        return try {
            // Step A: Search for lyric candidates
            val query = if (artist.isNotBlank()) "$title - $artist" else title
            val searchUrl = "https://lyrics.kugou.com/search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("ver", "1")
                .addQueryParameter("man", "yes")
                .addQueryParameter("client", "pc")
                .addQueryParameter("keyword", query)
                .apply {
                    if (durationSeconds > 0) {
                        addQueryParameter("duration", (durationSeconds * 1000).toString())
                    }
                }
                .build()

            val searchReq = Request.Builder().url(searchUrl).build()
            val (candidateId, accessKey) = RetrofitInstance.httpClient.newCall(searchReq).execute().use { res ->
                if (!res.isSuccessful) return null
                val root = JSONObject(res.body.string())
                val candidates = root.optJSONArray("candidates") ?: return null
                if (candidates.length() == 0) return null

                val first = candidates.getJSONObject(0)
                Pair(first.optLong("id"), first.optString("accesskey"))
            }

            if (candidateId <= 0 || accessKey.isBlank()) return null

            // Step B: Download and base64-decode LRC
            val downloadUrl = "https://lyrics.kugou.com/download".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("ver", "1")
                .addQueryParameter("client", "pc")
                .addQueryParameter("fmt", "lrc")
                .addQueryParameter("charset", "utf8")
                .addQueryParameter("id", candidateId.toString())
                .addQueryParameter("accesskey", accessKey)
                .build()

            val downloadReq = Request.Builder().url(downloadUrl).build()
            RetrofitInstance.httpClient.newCall(downloadReq).execute().use { res ->
                if (!res.isSuccessful) return null
                val root = JSONObject(res.body.string())
                val encodedContent = root.optString("content")
                if (encodedContent.isBlank()) return null

                val decodedBytes = Base64.decode(encodedContent, Base64.DEFAULT)
                val lrcText = String(decodedBytes, Charsets.UTF_8).trim()
                if (lrcText.isNotBlank()) {
                    val plain = stripLrcTimestamps(lrcText)
                    mapOf(
                        "synced" to lrcText,
                        "plain" to plain
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripLrcTimestamps(lrc: String): String {
        return lrc.lines()
            .map { it.replace(Regex("""\[\d{2}:\d{2}(?:\.\d{1,3})?]"""), "").trim() }
            .filter { it.isNotBlank() && !it.startsWith("[ar:") && !it.startsWith("[ti:") && !it.startsWith("[al:") }
            .joinToString("\n")
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("""\s*[\[(](?:Official|Lyric|Official Video|Video|HD|HQ|Audio|Visualizer|Clean Version|From .*|feat\..*|ft\..*)[\])]""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        var clean = artist
        if (clean.endsWith(" - Topic", ignoreCase = true)) {
            clean = clean.substring(0, clean.length - 8)
        }
        if (clean.equals("Release - Topic", ignoreCase = true) ||
            clean.equals("Release", ignoreCase = true) ||
            clean.equals("Various Artists - Topic", ignoreCase = true) ||
            clean.equals("Various Artists", ignoreCase = true)) {
            clean = ""
        }
        return clean.trim()
    }
}
