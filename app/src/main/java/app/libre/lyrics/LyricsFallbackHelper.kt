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
     * Fetch synced lyrics from Apple Music (via BetterLyrics TTML), Binimum (Apple/Spotify TTML),
     * KuGou (regional/indie), or YouTube video subtitles/captions.
     * Returns a map with "synced" and "plain" keys if found, or null.
     */
    suspend fun fetchFallbackLyrics(
        title: String,
        artist: String,
        durationSeconds: Long = 0L,
        album: String? = null,
        subtitleUrl: String? = null
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanArtistName(artist)

        // 1. Try Apple Music TTML (BetterLyrics proxy)
        if (cleanTitle.isNotBlank()) {
            fetchFromAppleMusicTTML(cleanTitle, cleanArtist, durationSeconds, album)?.let { return@withContext it }
        }

        // 2. Try LyricsPlus / Binimum API (Word/Line-synced TTML)
        if (cleanTitle.isNotBlank()) {
            fetchFromBinimum(cleanTitle, cleanArtist, durationSeconds, album)?.let { return@withContext it }
        }

        // 3. Try KuGou (great for regional, indie, and Asian tracks)
        if (cleanTitle.isNotBlank()) {
            fetchFromKuGou(cleanTitle, cleanArtist, durationSeconds, album)?.let { return@withContext it }
        }

        // 4. Try YouTube Video Subtitle / Caption stream (ultimate fallback for unindexed tracks)
        if (!subtitleUrl.isNullOrBlank()) {
            fetchFromSubtitleUrl(subtitleUrl)?.let { return@withContext it }
        }

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

    private fun fetchFromBinimum(
        title: String,
        artist: String,
        durationSeconds: Long,
        album: String?
    ): Map<String, String>? {
        return try {
            val urlBuilder = "https://lyrics-api.binimum.org/".toHttpUrlOrNull()!!.newBuilder()
            urlBuilder.addQueryParameter("track", title)
            if (artist.isNotBlank()) {
                urlBuilder.addQueryParameter("artist", artist)
            }
            if (durationSeconds > 0) {
                urlBuilder.addQueryParameter("duration", durationSeconds.toString())
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
                val results = json.optJSONArray("results") ?: return null
                if (results.length() == 0) return null

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val lyricsUrl = item.optString("lyricsUrl")
                    if (lyricsUrl.isNotBlank()) {
                        val ttmlReq = Request.Builder().url(lyricsUrl).build()
                        RetrofitInstance.httpClient.newCall(ttmlReq).execute().use { ttmlRes ->
                            if (ttmlRes.isSuccessful) {
                                val ttmlBody = ttmlRes.body.string()
                                val syncedLrc = TTMLParser.ttmlToLrc(ttmlBody)
                                if (!syncedLrc.isNullOrBlank()) {
                                    val plain = stripLrcTimestamps(syncedLrc)
                                    return mapOf(
                                        "synced" to syncedLrc,
                                        "plain" to plain
                                    )
                                }
                            }
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchFromSubtitleUrl(subtitleUrl: String): Map<String, String>? {
        return try {
            val request = Request.Builder()
                .url(subtitleUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            RetrofitInstance.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                if (body.isBlank()) return null
                val lrc = parseSubtitlesToLrc(body)
                if (!lrc.isNullOrBlank()) {
                    val plain = stripLrcTimestamps(lrc)
                    mapOf("synced" to lrc, "plain" to plain)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseSubtitlesToLrc(content: String): String? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        val lines = mutableListOf<Pair<Long, String>>()

        if (trimmed.startsWith("WEBVTT") || trimmed.contains("-->")) {
            val vttRegex = Regex("""(?:(\d{2}):)?(\d{2}):(\d{2})\.(\d{2,3})\s*-->\s*(?:(\d{2}):)?(\d{2}):(\d{2})\.(\d{2,3})""")
            val cueBlocks = trimmed.split(Regex("""\r?\n\r?\n"""))
            for (block in cueBlocks) {
                val match = vttRegex.find(block) ?: continue
                val text = block.substring(match.range.last + 1)
                    .replace(Regex("""<[^>]+>"""), "")
                    .replace(Regex("""\r?\n"""), " ")
                    .trim()
                if (text.isBlank()) continue

                val hours = match.groupValues[1].toLongOrNull() ?: 0L
                val minutes = match.groupValues[2].toLongOrNull() ?: 0L
                val seconds = match.groupValues[3].toLongOrNull() ?: 0L
                val msStr = match.groupValues[4].padEnd(3, '0').take(3)
                val ms = msStr.toLongOrNull() ?: 0L
                val totalMs = hours * 3600000L + minutes * 60000L + seconds * 1000L + ms
                lines.add(Pair(totalMs, text))
            }
        } else if (trimmed.contains("<text") || trimmed.contains("<p")) {
            val textRegex = Regex("""<text\s+start="([\d.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
            for (match in textRegex.findAll(trimmed)) {
                val startSec = match.groupValues[1].toDoubleOrNull() ?: continue
                val text = unescapeXml(match.groupValues[2].trim())
                if (text.isNotBlank()) {
                    lines.add(Pair((startSec * 1000).toLong(), text))
                }
            }

            if (lines.isEmpty()) {
                val pRegex = Regex("""<p\s+t="(\d+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                for (match in pRegex.findAll(trimmed)) {
                    val tMs = match.groupValues[1].toLongOrNull() ?: continue
                    val text = unescapeXml(match.groupValues[2].trim())
                    if (text.isNotBlank()) {
                        lines.add(Pair(tMs, text))
                    }
                }
            }
        }

        if (lines.isEmpty()) return null

        return lines.sortedBy { it.first }.joinToString("\n") { (timeMs, text) ->
            val m = timeMs / 60000
            val s = (timeMs % 60000) / 1000
            val c = (timeMs % 1000) / 10
            String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s", m, s, c, text)
        }
    }

    private fun unescapeXml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace(Regex("""&#(\d+);""")) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null) code.toChar().toString() else match.value
            }
    }
}
