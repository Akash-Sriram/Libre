package app.libre.api

import app.libre.extensions.toID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object YtMusicApi {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val API_URL = "https://music.youtube.com/youtubei/v1/next"
    private const val TAG = "YtMusicApi"

    suspend fun fetchAlbumName(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230508.00.00")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url(API_URL)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .build()

            RetrofitInstance.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body.string()
                
                // Innertube JSON is heavily nested. We use a regex to reliably find the album name
                // associated with the track in the musicQueueRenderer.
                // Example: "album":{"name":"The Album Name"
                var albumName: String? = null
                val target = "\"MUSIC_PAGE_TYPE_ALBUM\""
                val index = body.indexOf(target)
                if (index != -1) {
                    val textKey = "\"text\":\""
                    val textKeyAlt = "\"text\": \""
                    var textIndex = body.lastIndexOf(textKey, index)
                    if (textIndex == -1) textIndex = body.lastIndexOf(textKeyAlt, index)
                    
                    if (textIndex != -1) {
                        val keyLength = if (body.substring(textIndex, textIndex + textKey.length) == textKey) textKey.length else textKeyAlt.length
                        val startQuote = textIndex + keyLength - 1
                        val endQuote = body.indexOf("\"", startQuote + 1)
                        if (endQuote != -1) {
                            albumName = body.substring(startQuote + 1, endQuote)
                        }
                    }
                } else {
                    // If there's no network error but the track simply doesn't have an album,
                    // return an empty string so the worker knows it was checked.
                    albumName = ""
                }
                
                android.util.Log.d(TAG, "Fetched album for $videoId: $albumName")
                return@withContext albumName
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch album for $videoId", e)
            // Throw exception so worker knows it was a network failure and doesn't mark it as empty
            throw e
        }
    }

    suspend fun fetchLyrics(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Call v1/next to get watch playlist and find the lyrics browseId (starts with MPLYt_)
            val nextPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230508.00.00")
                    })
                })
                put("videoId", videoId)
            }

            val nextRequest = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next")
                .post(nextPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .build()

            val browseId = RetrofitInstance.httpClient.newCall(nextRequest).execute().use { nextResponse ->
                if (!nextResponse.isSuccessful) return@withContext null
                val nextBody = nextResponse.body.string()
                val matchResult = Regex("""MPLYt_[a-zA-Z0-9_-]+""").find(nextBody)
                matchResult?.value
            } ?: return@withContext null

            // Step 2: Call v1/browse with the lyrics browseId to get the lyrics text
            val browsePayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230508.00.00")
                    })
                })
                put("browseId", browseId)
            }

            val browseRequest = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .post(browsePayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .build()

            RetrofitInstance.httpClient.newCall(browseRequest).execute().use { browseResponse ->
                if (!browseResponse.isSuccessful) return@withContext null

                val browseBody = browseResponse.body.string()
                val json = JSONObject(browseBody)
                val contents = json.optJSONObject("contents")
                val sectionList = contents?.optJSONObject("sectionListRenderer")
                val sectionContents = sectionList?.optJSONArray("contents")
                val firstContent = sectionContents?.optJSONObject(0)
                val musicDescriptionShelf = firstContent?.optJSONObject("musicDescriptionShelfRenderer")
                val description = musicDescriptionShelf?.optJSONObject("description")
                val runs = description?.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val lyricsText = runs.getJSONObject(0).optString("text")
                    return@withContext lyricsText
                }

                return@withContext null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch lyrics for $videoId", e)
            return@withContext null
        }
    }

    suspend fun fetchLrcLyrics(
        videoId: String,
        durationSeconds: Long
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val nextPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230508.00.00")
                    })
                })
                put("videoId", videoId)
            }

            val nextRequest = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next")
                .post(nextPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .build()

            val nextJson = RetrofitInstance.httpClient.newCall(nextRequest).execute().use { nextResponse ->
                if (!nextResponse.isSuccessful) return@withContext null
                val nextBody = nextResponse.body.string()
                JSONObject(nextBody)
            }

            var trackName = ""
            var artistName = ""
            var albumName = ""
            try {
                val contents = nextJson.getJSONObject("contents")
                val watchNext = contents.getJSONObject("singleColumnMusicWatchNextResultsRenderer")
                val tabbed = watchNext.getJSONObject("tabbedRenderer")
                val watchNextTabbed = tabbed.getJSONObject("watchNextTabbedResultsRenderer")
                val tabs = watchNextTabbed.getJSONArray("tabs")
                val tab0 = tabs.getJSONObject(0).getJSONObject("tabRenderer")
                val tabContent = tab0.getJSONObject("content")
                val musicQueue = tabContent.getJSONObject("musicQueueRenderer")
                val queueContent = musicQueue.getJSONObject("content")
                val playlistPanel = queueContent.getJSONObject("playlistPanelRenderer")
                val playlistContents = playlistPanel.getJSONArray("contents")
                if (playlistContents.length() > 0) {
                    val firstItem = playlistContents.getJSONObject(0).getJSONObject("playlistPanelVideoRenderer")
                    trackName = firstItem.getJSONObject("title").getJSONArray("runs").getJSONObject(0).getString("text")
                    
                    val longByline = firstItem.optJSONObject("longBylineText")
                    val runs = longByline?.optJSONArray("runs")
                    if (runs != null) {
                        val artists = mutableListOf<String>()
                        var foundBullet = false
                        for (i in 0 until runs.length()) {
                            val runText = runs.getJSONObject(i).getString("text")
                            if (runText.contains("•") || runText.contains("·")) {
                                foundBullet = true
                                if (i + 1 < runs.length()) {
                                    albumName = runs.getJSONObject(i + 1).getString("text").trim()
                                }
                                break
                            }
                            artists.add(runText)
                        }
                        artistName = artists.joinToString("").trim()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to parse next metadata", e)
            }

            if (trackName.isBlank()) return@withContext null

            val cleanTitle = trackName
                .replace(Regex("""\s*[\[(](?:Official|Lyric|Official Video|Video|HD|HQ|Audio|Visualizer|Clean Version|From .*|feat\..*|ft\..*)[\])]""", RegexOption.IGNORE_CASE), "")
                .trim()

            var cleanArtist = artistName
            if (cleanArtist.endsWith(" - Topic", ignoreCase = true)) {
                cleanArtist = cleanArtist.substring(0, cleanArtist.length - 8)
            }
            if (cleanArtist.equals("Release - Topic", ignoreCase = true) || 
                cleanArtist.equals("Release", ignoreCase = true) ||
                cleanArtist.equals("Various Artists - Topic", ignoreCase = true) ||
                cleanArtist.equals("Various Artists", ignoreCase = true)) {
                cleanArtist = ""
            }
            cleanArtist = cleanArtist.trim()

            val builder = "https://lrclib.net/api/get".toHttpUrlOrNull()!!.newBuilder()
            builder.addQueryParameter("track_name", cleanTitle)
            builder.addQueryParameter("artist_name", cleanArtist)
            if (durationSeconds > 0) {
                builder.addQueryParameter("duration", durationSeconds.toString())
            }

            val request = Request.Builder()
                .url(builder.build())
                .header("User-Agent", "Libre/1.0 (https://github.com/Akash-Sriram/Libre)")
                .build()

            var lyricsResult: Map<String, String>? = null

            if (cleanArtist.isNotEmpty()) {
                lyricsResult = runCatching {
                    RetrofitInstance.httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body.string()
                            val json = JSONObject(body)
                            val plain = json.optString("plainLyrics").takeIf { !it.isNullOrBlank() }
                            val synced = json.optString("syncedLyrics").takeIf { !it.isNullOrBlank() }
                            if (plain != null || synced != null) {
                                mapOf(
                                    "plain" to (plain ?: ""),
                                    "synced" to (synced ?: ""),
                                    "title" to trackName,
                                    "artist" to artistName,
                                    "album" to albumName
                                )
                            } else null
                        } else null
                    }
                }.getOrNull()
            }

            if (lyricsResult != null) return@withContext lyricsResult

            val searchBuilder = "https://lrclib.net/api/search".toHttpUrlOrNull()!!.newBuilder()
            searchBuilder.addQueryParameter("track_name", cleanTitle)
            if (cleanArtist.isNotEmpty()) {
                searchBuilder.addQueryParameter("artist_name", cleanArtist)
            }
            val searchRequest = Request.Builder()
                .url(searchBuilder.build())
                .header("User-Agent", "Libre/1.0 (https://github.com/Akash-Sriram/Libre)")
                .build()

            RetrofitInstance.httpClient.newCall(searchRequest).execute().use { searchResponse ->
                if (!searchResponse.isSuccessful) return@withContext null

                val searchBody = searchResponse.body.string()
                val jsonArray = org.json.JSONArray(searchBody)
                if (jsonArray.length() > 0) {
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val plain = item.optString("plainLyrics").takeIf { !it.isNullOrBlank() }
                        val synced = item.optString("syncedLyrics").takeIf { !it.isNullOrBlank() }
                        if (synced != null || plain != null) {
                            return@withContext mapOf(
                                "plain" to (plain ?: ""),
                                "synced" to (synced ?: ""),
                                "title" to trackName,
                                "artist" to artistName,
                                "album" to albumName
                            )
                        }
                    }
                }
                return@withContext null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch LRC lyrics for $videoId", e)
            return@withContext null
        }
    }

    suspend fun fetchJioSaavnLyrics(jioSaavnId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&lyrics_id=$jioSaavnId&_format=json&api_version=4&ctx=web6s"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36")
                .build()

            RetrofitInstance.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body.string()
                val json = JSONObject(body)
                return@withContext json.optString("lyrics").takeIf { !it.isNullOrBlank() }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch JioSaavn lyrics for $jioSaavnId", e)
            return@withContext null
        }
    }

    object LyricsCache {
        private val memoryCache = android.util.LruCache<String, Map<String, String>>(50)

        fun get(context: android.content.Context, videoId: String): Map<String, String>? {
            memoryCache.get(videoId)?.let { return it }
            try {
                val cacheFile = android.content.ContextWrapper(context).cacheDir.resolve("lyrics_$videoId.json")
                if (cacheFile.exists()) {
                    val jsonStr = cacheFile.readText()
                    val json = org.json.JSONObject(jsonStr)
                    val map = mapOf(
                        "plain" to json.optString("plain"),
                        "synced" to json.optString("synced"),
                        "title" to json.optString("title"),
                        "artist" to json.optString("artist"),
                        "album" to json.optString("album")
                    )
                    memoryCache.put(videoId, map)
                    return map
                }
            } catch (e: Exception) {
                android.util.Log.e("LyricsCache", "Failed to read cache for $videoId", e)
            }
            return null
        }

        fun put(context: android.content.Context, videoId: String, lyrics: Map<String, String>) {
            memoryCache.put(videoId, lyrics)
            try {
                val cacheFile = android.content.ContextWrapper(context).cacheDir.resolve("lyrics_$videoId.json")
                val json = org.json.JSONObject().apply {
                    put("plain", lyrics["plain"] ?: "")
                    put("synced", lyrics["synced"] ?: "")
                    put("title", lyrics["title"] ?: "")
                    put("artist", lyrics["artist"] ?: "")
                    put("album", lyrics["album"] ?: "")
                }
                cacheFile.writeText(json.toString())
            } catch (e: Exception) {
                android.util.Log.e("LyricsCache", "Failed to write cache for $videoId", e)
            }
        }
    }

    suspend fun fetchAlbum(browseId: String): app.libre.api.obj.Playlist? = withContext(Dispatchers.IO) {
        try {
            val formattedBrowseId = if (browseId.startsWith("OLAK") && !browseId.startsWith("VL")) "VL$browseId" else browseId
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20231211.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", formattedBrowseId)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = RetrofitInstance.httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body.string()
            val json = JSONObject(body)

            // If this is an OLAK playlist without header, check if there is an MPRE album browseId in the tracks to get the rich album master
            if (formattedBrowseId.contains("OLAK")) {
                val secList = json.optJSONObject("contents")
                    ?.optJSONObject("twoColumnBrowseResultsRenderer")
                    ?.optJSONObject("secondaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                val shelf = secList?.optJSONObject(0)?.optJSONObject("musicPlaylistShelfRenderer")
                val firstItem = shelf?.optJSONArray("contents")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemRenderer")
                val menuItems = firstItem?.optJSONObject("menu")?.optJSONObject("menuRenderer")?.optJSONArray("items")
                if (menuItems != null) {
                    for (mIdx in 0 until menuItems.length()) {
                        val mItem = menuItems.optJSONObject(mIdx)?.optJSONObject("menuNavigationItemRenderer")
                        val bId = mItem?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                        if (!bId.isNullOrBlank() && bId.startsWith("MPRE")) {
                            return@withContext fetchAlbum(bId)
                        }
                    }
                }
            }

            val microformat = json.optJSONObject("microformat")?.optJSONObject("microformatDataRenderer")
            var albumTitle = microformat?.optString("title").orEmpty()
            val microDesc = microformat?.optString("description").orEmpty()
            var uploader = if (microDesc.contains("•")) microDesc.substringAfter("•").trim() else ""
            val microThumbs = microformat?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            var thumbUrl = microThumbs?.optJSONObject(microThumbs.length() - 1)?.optString("url")

            val respHeader = json.optJSONObject("contents")
                ?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: json.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                ?: json.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")

            if (albumTitle.isBlank()) {
                albumTitle = respHeader?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            }
            if (uploader.isBlank()) {
                uploader = respHeader?.optJSONObject("subtitle")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            }
            if (thumbUrl.isNullOrBlank()) {
                val thumbs = respHeader?.optJSONObject("thumbnail")?.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ?: respHeader?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                thumbUrl = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
            }

            val tracks = mutableListOf<app.libre.api.obj.StreamItem>()

            // For MPRE albums, fetch the companion OLAK studio audio playlist to get pure master audio tracks
            var masterTrackJson = json
            if (formattedBrowseId.startsWith("MPRE")) {
                val olakMatch = Regex("""OLAK5uy_[a-zA-Z0-9_-]+""").find(body)?.value
                if (olakMatch != null) {
                    try {
                        val masterPayload = JSONObject().apply {
                            put("context", JSONObject().apply {
                                put("client", JSONObject().apply {
                                    put("clientName", "WEB_REMIX")
                                    put("clientVersion", "1.20240101.01.00")
                                    put("hl", "en")
                                    put("gl", "IN")
                                })
                            })
                            put("browseId", "VL$olakMatch")
                        }
                        val masterReq = Request.Builder()
                            .url("https://music.youtube.com/youtubei/v1/browse")
                            .post(masterPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Origin", "https://music.youtube.com")
                            .header("Referer", "https://music.youtube.com/")
                            .build()
                        val masterResp = RetrofitInstance.httpClient.newCall(masterReq).execute()
                        if (masterResp.isSuccessful) {
                            masterTrackJson = JSONObject(masterResp.body.string())
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to fetch master OLAK tracks", e)
                    }
                }
            }

            // 1. Check singleColumnBrowseResultsRenderer
            var sectionContents = masterTrackJson.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?.optJSONObject(0)
                ?.optJSONObject("musicShelfRenderer")
                ?.optJSONArray("contents")

            // 2. Check twoColumnBrowseResultsRenderer (musicPlaylistShelfRenderer or musicShelfRenderer)
            if (sectionContents == null) {
                val twoColSec = masterTrackJson.optJSONObject("contents")
                    ?.optJSONObject("twoColumnBrowseResultsRenderer")
                    ?.optJSONObject("secondaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?.optJSONObject(0)

                sectionContents = twoColSec?.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")
                    ?: twoColSec?.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
            }

            if (sectionContents != null) {
                for (i in 0 until sectionContents.length()) {
                    val itemObj = sectionContents.optJSONObject(i) ?: continue
                    val renderer = itemObj.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    val playEndpoint = renderer.optJSONObject("overlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                        ?.optJSONObject("playNavigationEndpoint")
                    val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
                        ?.takeIf { it.isNotBlank() }
                        ?: playEndpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
                        ?: renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
                        ?: continue

                    val flexCols = renderer.optJSONArray("flexColumns") ?: continue
                    val title = flexCols.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text").orEmpty()

                    val artistCol = flexCols.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                    val rawArtist = artistCol?.optJSONObject(0)?.optString("text")
                    val trackArtist = if (!rawArtist.isNullOrBlank() && !rawArtist.contains("plays", ignoreCase = true)) rawArtist else uploader

                    val trackThumbs = renderer.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val trackThumbUrl = trackThumbs?.optJSONObject(trackThumbs.length() - 1)?.optString("url")

                    tracks.add(
                        app.libre.api.obj.StreamItem(
                            url = "https://www.youtube.com/watch?v=$videoId",
                            title = title,
                            uploaderName = trackArtist,
                            uploaderUrl = "",
                            thumbnail = thumbUrl?.takeIf { it.isNotBlank() } ?: trackThumbUrl.orEmpty(),
                            albumName = albumTitle,
                            type = app.libre.api.obj.StreamItem.TYPE_STREAM
                        )
                    )
                }
            }

            if (tracks.isNotEmpty()) {
                app.libre.api.obj.Playlist(
                    name = albumTitle.ifBlank { "Album" },
                    thumbnailUrl = thumbUrl.orEmpty(),
                    uploader = uploader,
                    videos = tracks.size,
                    relatedStreams = tracks
                )
            } else null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "fetchAlbum error for $browseId", e)
            null
        }
    }

    suspend fun resolveAlbumForVideo(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.length != 11) return@withContext null
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("videoId", videoId)
            }
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = RetrofitInstance.httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body.string()
            val mpreMatch = Regex(""""browseId":\s*"(MPREb_[a-zA-Z0-9_-]+)"""").find(body)?.groupValues?.getOrNull(1)
            if (!mpreMatch.isNullOrBlank()) return@withContext mpreMatch

            val olakMatch = Regex(""""(?:playlistId|browseId)":\s*"(OLAK5uy_[a-zA-Z0-9_-]+)"""").find(body)?.groupValues?.getOrNull(1)
            if (!olakMatch.isNullOrBlank()) return@withContext "VL$olakMatch"
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resolveAlbumForVideo error for $videoId", e)
        }
        return@withContext null
    }

    suspend fun resolveAlbumId(albumName: String, artistName: String? = null): String? = withContext(Dispatchers.IO) {
        if (albumName.isBlank()) return@withContext null
        try {
            val q = if (!artistName.isNullOrBlank()) "$albumName $artistName" else albumName
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", q)
                put("params", "EgWKAQIYAWoOEAQQAxAJEAUQChAQEBU=") // Albums filter
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = RetrofitInstance.httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body.string()
            val json = JSONObject(body)
            val sec = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (sec != null) {
                for (i in 0 until sec.length()) {
                    val s = sec.optJSONObject(i) ?: continue
                    val card = s.optJSONObject("musicCardShelfRenderer")
                    if (card != null) {
                        val runs = card.optJSONObject("title")?.optJSONArray("runs")
                        val bId = runs?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                        if (!bId.isNullOrBlank() && (bId.startsWith("MPRE") || bId.startsWith("OLAK") || bId.startsWith("VL"))) {
                            return@withContext bId
                        }
                    }
                    val shelf = s.optJSONObject("musicShelfRenderer")
                    if (shelf != null) {
                        val contents = shelf.optJSONArray("contents") ?: continue
                        for (j in 0 until contents.length()) {
                            val mr = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val menuItems = mr.optJSONObject("menu")?.optJSONObject("menuRenderer")?.optJSONArray("items")
                            if (menuItems != null) {
                                for (mIdx in 0 until menuItems.length()) {
                                    val toggle = menuItems.optJSONObject(mIdx)?.optJSONObject("toggleMenuServiceItemRenderer")
                                    val plId = toggle?.optJSONObject("toggledServiceEndpoint")?.optJSONObject("likeEndpoint")?.optJSONObject("target")?.optString("playlistId").orEmpty()
                                    if (plId.startsWith("OLAK")) {
                                        return@withContext "VL$plId"
                                    }
                                }
                            }
                            val flexCols = mr.optJSONArray("flexColumns")
                            val runs = flexCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                            val bId = runs?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                                ?: mr.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                            if (!bId.isNullOrBlank() && (bId.startsWith("MPRE") || bId.startsWith("OLAK") || bId.startsWith("VL"))) {
                                return@withContext bId
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resolveAlbumId error for $albumName", e)
        }
        return@withContext null
    }

    suspend fun search(
        query: String,
        filter: String = "music_songs"
    ): List<app.libre.api.obj.ContentItem> = withContext(Dispatchers.IO) {
        try {
            val params = when (filter) {
                "music_songs" -> "EgWKAQIIAWoOEAQQAxAJEAUQChAQEBU="
                "music_videos" -> "EgWKAQIYAWoMEAMQBBAJEA4QChAF"
                "music_albums" -> "EgWKAQIYAWoOEAQQAxAJEAUQChAQEBU="
                "music_playlists" -> "EgWKAQIQAWoMEAMQBBAJEA4QChAF"
                else -> null
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230508.00.00")
                        put("hl", "en")
                    })
                })
                put("query", query)
                if (params != null) put("params", params)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = RetrofitInstance.httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body.string()
            val json = JSONObject(body)
            val results = mutableListOf<app.libre.api.obj.ContentItem>()
            val seenIds = mutableSetOf<String>()

            // For Albums search, first check if default search Top Card points to the official primary album
            if (filter == "music_albums") {
                try {
                    val topPayload = JSONObject().apply {
                        put("context", JSONObject().apply {
                            put("client", JSONObject().apply {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", "1.20240101.01.00")
                                put("hl", "en")
                                put("gl", "IN")
                            })
                        })
                        put("query", query)
                    }
                    val topReq = Request.Builder()
                        .url("https://music.youtube.com/youtubei/v1/search")
                        .post(topPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Origin", "https://music.youtube.com")
                        .header("Referer", "https://music.youtube.com/")
                        .build()
                    val topResp = RetrofitInstance.httpClient.newCall(topReq).execute()
                    if (topResp.isSuccessful) {
                        val tBody = topResp.body.string()
                        val tJson = JSONObject(tBody)
                        val tSec = tJson.optJSONObject("contents")?.optJSONObject("tabbedSearchResultsRenderer")
                            ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                            ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                        if (tSec != null) {
                            for (ti in 0 until tSec.length()) {
                                val tCard = tSec.optJSONObject(ti)?.optJSONObject("musicCardShelfRenderer") ?: continue
                                val menuItems = tCard.optJSONObject("menu")?.optJSONObject("menuRenderer")?.optJSONArray("items")
                                var albumBrowseId: String? = null
                                if (menuItems != null) {
                                    for (mi in 0 until menuItems.length()) {
                                        val bId = menuItems.optJSONObject(mi)?.optJSONObject("menuNavigationItemRenderer")
                                            ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                                        if (!bId.isNullOrBlank() && (bId.startsWith("MPREb_") || bId.startsWith("OLAK5uy_"))) {
                                            albumBrowseId = bId
                                            break
                                        }
                                    }
                                }
                                if (albumBrowseId == null) {
                                    val cTitleRuns = tCard.optJSONObject("title")?.optJSONArray("runs")
                                    val bId = cTitleRuns?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                                    if (!bId.isNullOrBlank() && (bId.startsWith("MPREb_") || bId.startsWith("OLAK5uy_"))) {
                                        albumBrowseId = bId
                                    }
                                }
                                if (albumBrowseId != null) {
                                    val albumObj = fetchAlbum(albumBrowseId)
                                    if (albumObj != null) {
                                        val cleanId = albumBrowseId
                                        seenIds.add(cleanId)
                                        results.add(
                                            app.libre.api.obj.ContentItem(
                                                url = albumBrowseId,
                                                type = app.libre.api.obj.StreamItem.TYPE_PLAYLIST,
                                                thumbnail = albumObj.thumbnailUrl.orEmpty(),
                                                title = albumObj.name,
                                                name = albumObj.name,
                                                uploaderName = albumObj.uploader,
                                                videos = albumObj.videos.toLong(),
                                                source = "ytm"
                                            )
                                        )
                                        break
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "music_albums top card extraction failed", e)
                }
            }

            val contents = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (contents != null) {
                for (i in 0 until contents.length()) {
                    val section = contents.optJSONObject(i) ?: continue
                    val musicShelf = section.optJSONObject("musicShelfRenderer")
                    val shelfContents = musicShelf?.optJSONArray("contents") ?: continue

                    for (j in 0 until shelfContents.length()) {
                        val itemObj = shelfContents.optJSONObject(j) ?: continue
                        val renderer = itemObj.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        val flexCols = renderer.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        val title = col0?.optJSONObject(0)?.optString("text").orEmpty()
                        if (title.isBlank()) continue

                        val overlay = renderer.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")

                        val playNav = overlay?.optJSONObject("playNavigationEndpoint")
                        val playWatch = playNav?.optJSONObject("watchEndpoint")
                        val col0Watch = col0?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                        val navEndpoint = renderer.optJSONObject("navigationEndpoint")
                        val navWatch = navEndpoint?.optJSONObject("watchEndpoint")

                        val videoId = playWatch?.optString("videoId")
                            ?: col0Watch?.optString("videoId")
                            ?: navWatch?.optString("videoId")

                        val playPlaylistId = playNav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")
                            ?: playWatch?.optString("playlistId")
                        val browseNav = navEndpoint?.optJSONObject("browseEndpoint")
                        val watchPlaylistNav = navEndpoint?.optJSONObject("watchPlaylistEndpoint")

                        val playlistId = playPlaylistId
                            ?: watchPlaylistNav?.optString("playlistId")
                            ?: browseNav?.optString("browseId")

                        val col1 = flexCols.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")

                        var artistName = ""
                        var albumName = ""
                        var albumId: String? = null
                        var durationSec = 0L
                        if (col1 != null) {
                            val nonSepRuns = mutableListOf<org.json.JSONObject>()
                            for (k in 0 until col1.length()) {
                                val run = col1.optJSONObject(k) ?: continue
                                val t = run.optString("text").trim()
                                if (t.isEmpty() || t == "•" || t == "·") continue
                                if (t.matches(Regex("""\d+:\d+(?::\d+)?"""))) {
                                    val parts = t.split(":")
                                    durationSec = if (parts.size == 2) {
                                        (parts[0].toLongOrNull() ?: 0L) * 60 + (parts[1].toLongOrNull() ?: 0L)
                                    } else if (parts.size == 3) {
                                        (parts[0].toLongOrNull() ?: 0L) * 3600 + (parts[1].toLongOrNull() ?: 0L) * 60 + (parts[2].toLongOrNull() ?: 0L)
                                    } else 0L
                                } else {
                                    nonSepRuns.add(run)
                                }
                            }
                            if (nonSepRuns.isNotEmpty()) {
                                artistName = nonSepRuns[0].optString("text")
                                if (nonSepRuns.size > 1) {
                                    albumName = nonSepRuns[1].optString("text")
                                    val bId = nonSepRuns[1].optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                                    if (!bId.isNullOrBlank() && (bId.startsWith("MPRE") || bId.startsWith("OLAK") || bId.startsWith("VL"))) {
                                        albumId = bId
                                    }
                                }
                            }
                        }

                        val thumbs = renderer.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val bestThumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()

                        val isExplicitPlaylistOrAlbum = filter == "music_albums" || filter == "music_playlists"
                        val isPlaylistOrAlbum = isExplicitPlaylistOrAlbum || 
                            (videoId.isNullOrBlank() && !playlistId.isNullOrBlank()) ||
                            (videoId.isNullOrBlank() && (browseNav?.optString("browseId").orEmpty().startsWith("MPRE") || browseNav?.optString("browseId").orEmpty().startsWith("VL")))

                        val finalId = if (!isPlaylistOrAlbum && !videoId.isNullOrBlank()) {
                            videoId
                        } else {
                            playlistId ?: browseNav?.optString("browseId") ?: videoId ?: continue
                        }

                        if (seenIds.contains(finalId)) continue
                        seenIds.add(finalId)

                        results.add(
                            app.libre.api.obj.ContentItem(
                                url = finalId,
                                type = if (isPlaylistOrAlbum) app.libre.api.obj.StreamItem.TYPE_PLAYLIST else app.libre.api.obj.StreamItem.TYPE_STREAM,
                                thumbnail = bestThumb,
                                title = title,
                                name = title,
                                uploaderName = artistName,
                                albumName = albumName,
                                albumId = albumId,
                                duration = durationSec,
                                source = "ytm"
                            )
                        )
                    }
                }
            }

            return@withContext results
        } catch (e: Exception) {
            android.util.Log.e(TAG, "YtMusicApi.search error", e)
            return@withContext emptyList()
        }
    }

    val companionPairMap = java.util.concurrent.ConcurrentHashMap<String, app.libre.api.obj.StreamItem>()

    fun registerCompanionPair(audioItem: app.libre.api.obj.StreamItem, videoItem: app.libre.api.obj.StreamItem) {
        val aId = audioItem.url?.toID().orEmpty()
        val vId = videoItem.url?.toID().orEmpty()
        if (aId.isNotEmpty()) companionPairMap[vId] = audioItem
        if (vId.isNotEmpty()) companionPairMap[aId] = videoItem
    }

    private val studioMasterCache = java.util.concurrent.ConcurrentHashMap<String, app.libre.api.obj.StreamItem>()

    suspend fun resolveStudioMaster(title: String, artist: String? = null): app.libre.api.obj.StreamItem? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val cacheKey = "${title.trim().lowercase()}::${artist?.trim()?.lowercase().orEmpty()}"
        studioMasterCache[cacheKey]?.let { return@withContext it }

        // Check if queue has a matching topic stream from the album first
        val queueMatch = app.libre.util.PlayingQueue.getStreams().firstOrNull {
            it.uploaderName?.endsWith("- Topic", ignoreCase = true) == true &&
                !it.title.isNullOrBlank() &&
                (title.contains(it.title.orEmpty(), ignoreCase = true) || it.title.orEmpty().contains(title, ignoreCase = true))
        }
        if (queueMatch != null) {
            studioMasterCache[cacheKey] = queueMatch
            return@withContext queueMatch
        }

        try {
            var cleanTitle = title
                .replace(Regex("""(?i)[\(\[\{]\s*(?:official\s*(?:music\s*)?(?:video|audio|lyric|hd|4k|remastered|track)?|lyric(?:s)?\s*video|full\s*(?:video|audio|song|track)|video\s*song|4k\s*uhd|remastered|hd|hq|audio|from\s+["'].*?["']|with\s+lyrics)\s*[\)\]\}]"""), " ")
                .replace(Regex("""(?i)\b(video song|official video|full video|lyric video|audio song|video)\b"""), " ")
                .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            val cleanArtist = artist?.replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "")?.trim().orEmpty()
            val query = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle

            // 2. Search Albums first to locate official full album track (e.g. TCU49lzQa8Y) with White Album Cover
            val albumPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", query)
                put("params", "EgWKAQIYAWoOEAQQAxAJEAUQChAQEBU=") // Albums filter
            }

            val albumRequest = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(albumPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val albumResponse = RetrofitInstance.httpClient.newCall(albumRequest).execute()
            if (albumResponse.isSuccessful) {
                val aBody = albumResponse.body.string()
                val aJson = JSONObject(aBody)
                val aSec = aJson.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")

                val aShelf = aSec?.optJSONObject(0)?.optJSONObject("musicShelfRenderer")
                val aContents = aShelf?.optJSONArray("contents")
                if (aContents != null) {
                    for (ai in 0 until minOf(aContents.length(), 2)) {
                        val itm = aContents.optJSONObject(ai)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        var targetBrowse = itm.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                        val menuItems = itm.optJSONObject("menu")?.optJSONObject("menuRenderer")?.optJSONArray("items")
                        if (menuItems != null) {
                            for (mi in 0 until menuItems.length()) {
                                val toggle = menuItems.optJSONObject(mi)?.optJSONObject("toggleMenuServiceItemRenderer")
                                val plId = toggle?.optJSONObject("toggledServiceEndpoint")?.optJSONObject("likeEndpoint")?.optJSONObject("target")?.optString("playlistId").orEmpty()
                                if (plId.isNotEmpty()) {
                                    targetBrowse = "VL$plId"
                                    break
                                }
                            }
                        }

                        if (targetBrowse.isNotEmpty()) {
                            val bPayload = JSONObject().apply {
                                put("context", JSONObject().apply {
                                    put("client", JSONObject().apply {
                                        put("clientName", "WEB_REMIX")
                                        put("clientVersion", "1.20240101.01.00")
                                        put("hl", "en")
                                        put("gl", "IN")
                                    })
                                })
                                put("browseId", targetBrowse)
                            }
                            val bReq = Request.Builder()
                                .url("https://music.youtube.com/youtubei/v1/browse")
                                .post(bPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                .header("Origin", "https://music.youtube.com")
                                .header("Referer", "https://music.youtube.com/")
                                .build()
                            val bResp = RetrofitInstance.httpClient.newCall(bReq).execute()
                            if (bResp.isSuccessful) {
                                val bBody = bResp.body.string()
                                val bJson = JSONObject(bBody)
                                val header = bJson.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                                    ?: bJson.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                                val aTitle = header?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                                val aThumbs = header?.optJSONObject("thumbnail")?.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                val aCover = aThumbs?.optJSONObject(aThumbs.length() - 1)?.optString("url").orEmpty()

                                val bSec = bJson.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                                    ?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                                    ?: bJson.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
                                        ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                                        ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")

                                val bShelf = bSec?.optJSONObject(0)?.optJSONObject("musicShelfRenderer")
                                    ?: bSec?.optJSONObject(0)?.optJSONObject("musicPlaylistShelfRenderer")
                                val bItems = bShelf?.optJSONArray("contents")
                                if (bItems != null) {
                                    for (bi in 0 until bItems.length()) {
                                        val trackRenderer = bItems.optJSONObject(bi)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                        val tFlex = trackRenderer.optJSONArray("flexColumns")?.optJSONObject(0)
                                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                                        val tTitle = tFlex?.optJSONObject(0)?.optString("text").orEmpty()
                                        val tVid = trackRenderer.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                                        val cleanSearch = cleanTitle.filter { it.isLetterOrDigit() }.lowercase()
                                        val cleanTrack = tTitle.filter { it.isLetterOrDigit() }.lowercase()
                                        if (tVid.isNotEmpty() && (cleanSearch in cleanTrack || cleanTrack in cleanSearch)) {
                                            val item = app.libre.api.obj.StreamItem(
                                                url = "https://www.youtube.com/watch?v=$tVid",
                                                title = tTitle,
                                                uploaderName = cleanArtist.ifBlank { aTitle },
                                                uploaderUrl = "",
                                                thumbnail = aCover,
                                                albumName = aTitle,
                                                albumId = targetBrowse,
                                                type = app.libre.api.obj.StreamItem.TYPE_STREAM
                                            )
                                            studioMasterCache[cacheKey] = item
                                            return@withContext item
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Fallback: Search Songs
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", query)
                put("params", "EgWKAQIIAWoOEAQQAxAJEAUQChAQEBU=") // Songs filter
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = RetrofitInstance.httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body.string()
            val json = JSONObject(body)
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
            val sec = tabs?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (sec != null) {
                for (i in 0 until sec.length()) {
                    val s = sec.optJSONObject(i) ?: continue
                    val shelf = s.optJSONObject("musicShelfRenderer") ?: continue
                    val contents = shelf.optJSONArray("contents") ?: continue
                    if (contents.length() > 0) {
                        val renderer = contents.optJSONObject(0)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        val flexCols = renderer.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        val songTitle = col0?.optJSONObject(0)?.optString("text").orEmpty()
                        val songVid = col0?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchEndpoint")?.optString("videoId") ?: continue

                        val col1 = flexCols.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        val songArtist = col1?.optJSONObject(0)?.optString("text").orEmpty()

                        var songAlbum = ""
                        if (flexCols.length() > 2) {
                            val col2 = flexCols.optJSONObject(2)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                            songAlbum = col2?.optJSONObject(0)?.optString("text").orEmpty()
                        }

                        val fixedCols = renderer.optJSONArray("fixedColumns")
                        val durRun = fixedCols?.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)?.optString("text")
                        val durSec = if (!durRun.isNullOrBlank() && durRun.contains(":")) {
                            val parts = durRun.split(":")
                            if (parts.size == 2) {
                                (parts[0].toLongOrNull() ?: 0L) * 60 + (parts[1].toLongOrNull() ?: 0L)
                            } else if (parts.size == 3) {
                                (parts[0].toLongOrNull() ?: 0L) * 3600 + (parts[1].toLongOrNull() ?: 0L) * 60 + (parts[2].toLongOrNull() ?: 0L)
                            } else 0L
                        } else 0L

                        val thumbs = renderer.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val bestThumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
                            ?.replace(Regex("""=w\d+-h\d+.*"""), "=w544-h544-l90-rj")
                            .orEmpty()

                        val result = app.libre.api.obj.StreamItem(
                            url = "https://www.youtube.com/watch?v=$songVid",
                            title = songTitle,
                            uploaderName = songArtist.ifBlank { cleanArtist },
                            thumbnail = bestThumb,
                            albumName = songAlbum,
                            duration = durSec,
                            type = app.libre.api.obj.StreamItem.TYPE_STREAM
                        )
                        studioMasterCache[cacheKey] = result
                        return@withContext result
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resolveStudioMaster error for $title", e)
            null
        }
    }

    private val officialVideoCache = java.util.concurrent.ConcurrentHashMap<String, app.libre.api.obj.StreamItem>()

    suspend fun resolveOfficialVideo(title: String, artist: String? = null): app.libre.api.obj.StreamItem? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val cacheKey = "${title.trim().lowercase()}::${artist?.trim()?.lowercase().orEmpty()}"
        officialVideoCache[cacheKey]?.let { return@withContext it }

        try {
            var cleanTitle = title
                .replace(Regex("""(?i)[\(\[\{]\s*(?:official\s*(?:music\s*)?(?:video|audio|lyric|hd|4k|remastered|track)?|lyric(?:s)?\s*video|full\s*(?:video|audio|song|track)|video\s*song|4k\s*uhd|remastered|hd|hq|audio|from\s+["'].*?["']|with\s+lyrics)\s*[\)\]\}]"""), " ")
                .replace(Regex("""(?i)\b(video song|official video|full video|lyric video|audio song|video)\b"""), " ")
                .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            val cleanArtist = artist?.replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "")?.trim().orEmpty()
            val query = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", query)
                put("params", "EgWKAQIQAWoOEAQQAxAJEAUQChAQEBU=") // Videos filter
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = RetrofitInstance.httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body.string()
            val json = JSONObject(body)
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
            val sec = tabs?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (sec != null) {
                for (i in 0 until sec.length()) {
                    val s = sec.optJSONObject(i) ?: continue
                    val shelf = s.optJSONObject("musicShelfRenderer") ?: continue
                    val contents = shelf.optJSONArray("contents") ?: continue
                    if (contents.length() > 0) {
                        val renderer = contents.optJSONObject(0)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        val flexCols = renderer.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        val songTitle = col0?.optJSONObject(0)?.optString("text").orEmpty()
                        val songVid = col0?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchEndpoint")?.optString("videoId") ?: continue

                        val col1 = flexCols.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        val songArtist = col1?.optJSONObject(0)?.optString("text").orEmpty()

                        val thumbs = renderer.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val bestThumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()

                        val result = app.libre.api.obj.StreamItem(
                            url = "https://www.youtube.com/watch?v=$songVid",
                            title = songTitle,
                            uploaderName = songArtist.ifBlank { cleanArtist },
                            thumbnail = bestThumb,
                            type = app.libre.api.obj.StreamItem.TYPE_STREAM
                        )
                        officialVideoCache[cacheKey] = result
                        return@withContext result
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resolveOfficialVideo error for $title", e)
            null
        }
    }
}
