package app.libre.helpers

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Log
import app.libre.api.MediaServiceRepository
import app.libre.db.DatabaseHolder
import app.libre.db.obj.LocalAudioMetadataCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object LocalAudioMatcher {
    private const val TAG = "LocalAudioMatcher"

    /** videoId -> absolute local file path (restored from DB or built by scan) */
    private val localAudioMap = ConcurrentHashMap<String, String>()

    /**
     * Normalized title -> absolute local file path.
     * Populated during scan for audio files that have NO YouTube ID in filename/tags.
     * Used for JioSaavn and other non-YouTube songs where we match by title.
     */
    private val titleToPathMap = ConcurrentHashMap<String, String>()

    /** videoId -> embedded album art bytes (lazy-extracted on first use) */
    private val embeddedArtCache = ConcurrentHashMap<String, ByteArray?>()

    private var isIndexing = false

    /**
     * Completes once the DB restore phase finishes.
     * Any caller that needs the map before the scan fully completes should await this.
     */
    private var dbRestoreReady = CompletableDeferred<Unit>()

    /**
     * Entry point called on app start.
     *
     * Phase 1 (fast): Load previously scanned paths from DB → signals [dbRestoreReady].
     * Phase 2 (slow): Scan filesystem for new files not yet in DB.
     * Phase 3 (online): Background-prefetch metadata for newly discovered songs.
     */
    fun startAutoScan(context: Context) {
        if (isIndexing) return
        isIndexing = true
        // Reset the deferred for this launch
        dbRestoreReady = CompletableDeferred()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Yield to let the main UI thread finish initial layout and first-frame draw
                kotlinx.coroutines.delay(3000L)
                val dao = DatabaseHolder.Database.localAudioMetadataCacheDao()

                // ── Phase 1: Restore from DB (fast, ~50ms) ────────────────────────────
                val dbPaths = dao.getAllLocalPaths()
                val restoredIds = mutableSetOf<String>()
                for (entry in dbPaths) {
                    if (entry.localFilePath.isNotEmpty() && File(entry.localFilePath).exists()) {
                        localAudioMap[entry.videoId] = entry.localFilePath
                        restoredIds.add(entry.videoId)
                    } else if (entry.localFilePath.isNotEmpty()) {
                        Log.i(TAG, "Stale entry removed: ${entry.videoId}")
                        dao.deleteByVideoId(entry.videoId)
                    }
                }
                Log.i(TAG, "Restored ${restoredIds.size} tracks from DB cache (no scan needed).")

                // Signal: map is ready for playback lookups
                dbRestoreReady.complete(Unit)

                if (restoredIds.isNotEmpty()) {
                    isIndexing = false
                    return@launch
                }
                val newlyFound = mutableMapOf<String, String>()
                val musicDirs = linkedSetOf<File>()
                val standard = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (standard.exists()) musicDirs.add(standard)
                val alt = File("/storage/emulated/0/Music")
                if (alt.exists() && alt.canonicalPath != standard.canonicalPath) musicDirs.add(alt)

                val offlineUriStr = PreferenceHelper.getString(app.libre.constants.PreferenceKeys.OFFLINE_SONGS_FOLDER_URI, "")
                if (offlineUriStr.isNotEmpty()) {
                    try {
                        val uriString = android.net.Uri.parse(offlineUriStr).toString()
                        if (uriString.startsWith("content://com.android.externalstorage.documents/tree/primary%3A")) {
                            val relativePath = android.net.Uri.decode(uriString.substringAfter("primary%3A"))
                            val customDir = File(Environment.getExternalStorageDirectory(), relativePath)
                            if (customDir.exists()) {
                                musicDirs.add(customDir)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse custom offline songs folder", e)
                    }
                }

                for (dir in musicDirs) {
                    scanDirectory(dir, restoredIds, newlyFound)
                }

                // Persist newly found paths to DB
                for ((videoId, path) in newlyFound) {
                    localAudioMap[videoId] = path
                    dao.upsertPath(videoId, path)
                }

                val total = localAudioMap.size
                Log.i(TAG, "Scan complete. Total: $total tracks (${restoredIds.size} from DB, ${newlyFound.size} new).")

                // ── Phase 3: Background-prefetch metadata when online ─────────────────
                if (NetworkHelper.isNetworkAvailable(context)) {
                    prefetchMetadataForAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during local audio scan", e)
                dbRestoreReady.complete(Unit) // always complete so callers don't hang
            } finally {
                isIndexing = false
            }
        }
    }

    private fun scanDirectory(
        dir: File,
        alreadyKnown: Set<String>,
        newlyFound: MutableMap<String, String>
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, alreadyKnown, newlyFound)
            } else if (file.isFile) {
                val name = file.name.lowercase(Locale.US)
                if (name.endsWith(".mp3") || name.endsWith(".m4a") ||
                    name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".wav")
                ) {
                    val videoId = extractVideoId(file)
                    if (videoId != null) {
                        if (videoId !in alreadyKnown && videoId !in newlyFound) {
                            newlyFound[videoId] = file.absolutePath
                            Log.i(TAG, "New: ${file.name} -> $videoId")
                        }
                    } else {
                        // No ID found — index by title as last resort for fuzzy matching
                        val normalizedTitle = normalizeTitle(file.nameWithoutExtension)
                        if (normalizedTitle.isNotEmpty()) {
                            titleToPathMap[normalizedTitle] = file.absolutePath
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts a matchable ID from the file using multiple strategies:
     *
     * 1. **Filename suffix (11-char YouTube ID)** — yt-dlp format: `Title - [videoId].ext`
     * 2. **Binary head scan** (first 128KB): YouTube ID near COMM frame, or JioSaavn URL.
     * 3. **Binary tail scan** (last 64KB, M4A only): handles tail-moov M4A where the
     *    `©cmt` atom containing the JioSaavn URL is stored after the media data.
     * 4. **Any YouTube URL** fallback in head.
     *
     * JioSaavn tokens are returned as `"jsa:TOKEN"` to avoid collision with YouTube IDs.
     */
    private fun extractVideoId(file: File): String? {
        // Strategy 1: filename YouTube ID (no disk I/O)
        val nameWithoutExt = file.nameWithoutExtension
        val filenameMatch = Regex("""\[([a-zA-Z0-9_-]{11})\]$""").find(nameWithoutExt)
        if (filenameMatch != null) return filenameMatch.groupValues[1]

        if (!file.exists() || file.length() < 10) return null

        return try {
            // Strategy 2: head scan (first 128KB)
            val headSize = minOf(file.length(), 128 * 1024).toInt()
            val headBuffer = ByteArray(headSize)
            FileInputStream(file).use { it.read(headBuffer) }
            val head = String(headBuffer, charset("ISO-8859-1"))

            // 2a: YouTube ID near COMM frame
            val commIdx = head.indexOf("COMM")
            if (commIdx != -1) {
                val area = head.substring(commIdx, minOf(commIdx + 500, head.length))
                val m = Regex("""(?:v=|/v/|embed/|youtu\.be/|\b)([a-zA-Z0-9_-]{11})\b""").find(area)
                if (m != null) return m.groupValues[1]
            }

            // 2b: JioSaavn URL in head (head-moov M4A or ID3 COMM)
            val jsaHead = Regex("""jiosaavn\.com/song/[^/\s]+/([A-Za-z0-9_-]{6,20})""").find(head)
            if (jsaHead != null) return "jsa:${jsaHead.groupValues[1]}"

            // 2c: any YouTube URL in head
            val ytHead = Regex("""(?:youtube\.com/watch\?v=|youtu\.be/)([a-zA-Z0-9_-]{11})""").find(head)
            if (ytHead != null) return ytHead.groupValues[1]

            // Strategy 3: tail scan for M4A (tail-moov layout — ©cmt atom after mdat)
            if (file.name.lowercase(Locale.US).endsWith(".m4a") && file.length() > headSize + 1024) {
                val tailSize = minOf(file.length() - headSize, 64 * 1024).toInt()
                val tailBuffer = ByteArray(tailSize)
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - tailSize)
                    raf.read(tailBuffer)
                }
                val tail = String(tailBuffer, charset("ISO-8859-1"))
                val jsaTail = Regex("""jiosaavn\.com/song/[^/\s]+/([A-Za-z0-9_-]{6,20})""").find(tail)
                if (jsaTail != null) return "jsa:${jsaTail.groupValues[1]}"
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    /** Prefetch and cache online metadata for all locally matched songs with missing/empty metadata. */
    private suspend fun prefetchMetadataForAll() {
        val dao = DatabaseHolder.Database.localAudioMetadataCacheDao()
        val cachedWithMeta = dao.getAllCachedIds().toSet()
        val toFetch = localAudioMap.entries.filterNot { it.key in cachedWithMeta }

        if (toFetch.isEmpty()) {
            Log.i(TAG, "All local tracks already have cached metadata.")
            return
        }

        Log.i(TAG, "Prefetching metadata for ${toFetch.size} tracks...")
        for ((videoId, path) in toFetch) {
            try {
                val streams = withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getStreams(videoId)
                }
                dao.insert(
                    LocalAudioMetadataCache(
                        videoId = videoId,
                        localFilePath = path,
                        title = streams.title,
                        uploader = streams.uploader,
                        thumbnailUrl = streams.thumbnailUrl,
                        duration = streams.duration
                    )
                )
                Log.i(TAG, "Cached metadata: $videoId -> ${streams.title}")
            } catch (e: Exception) {
                Log.w(TAG, "Metadata fetch failed for $videoId: ${e.message}")
            }
        }
        Log.i(TAG, "Metadata prefetch complete.")
    }

    /**
     * Returns the local file path for [videoId], waiting for the DB restore to finish
     * if it hasn't yet (prevents race condition on first tap after cold start).
     *
     * For JioSaavn videoIds (full URLs like `https://www.jiosaavn.com/song/track/TOKEN`),
     * extracts the token and looks up `jsa:TOKEN` in the map.
     */
    suspend fun getLocalPathAsync(videoId: String): String? {
        dbRestoreReady.await()
        val path = localAudioMap[videoId] ?: toJsaKey(videoId)?.let { localAudioMap[it] }
        Log.i(TAG, "getLocalPathAsync: asked for '$videoId', found: $path")
        return path
    }

    /** Sync version — returns null if map not yet loaded. Use [getLocalPathAsync] in coroutines. */
    fun getLocalPath(videoId: String): String? =
        localAudioMap[videoId] ?: toJsaKey(videoId)?.let { localAudioMap[it] }

    /**
     * Extracts the JioSaavn token from a variety of videoId formats:
     * - Full URL: `https://www.jiosaavn.com/song/track/TOKEN`
     * - Internal ID: `jsa_song_PID_TOKEN` or `jsa_song_TOKEN`
     * Returns the `jsa:TOKEN` key used internally in [localAudioMap], or null if not a JioSaavn ID.
     */
    private fun toJsaKey(videoId: String): String? {
        if (videoId.startsWith("jsa_song_")) {
            val parts = videoId.split("_")
            return "jsa:${parts.last()}"
        }
        if (!videoId.contains("jiosaavn.com")) return null
        val token = videoId.substringAfterLast("/").substringBefore("?")
        return if (token.isNotEmpty()) "jsa:$token" else null
    }

    /**
     * Called by OnlinePlayerService when a title-based match is confirmed for a non-YouTube
     * song (e.g. JioSaavn). Registers the path under [videoId] so [getLocalPath] finds it
     * immediately for the subsequent [setStreamSource] call.
     */
    fun registerTitleMatch(videoId: String, localPath: String) {
        localAudioMap[videoId] = localPath
        Log.i(TAG, "Title match registered: $videoId -> $localPath")
    }

    /**
     * Tries to find a local file matching [title] (and optionally [artist]) for songs
     * that have no YouTube ID (e.g. JioSaavn songs). Uses normalized string comparison.
     * Returns the local file path if found, null otherwise.
     */
    fun getLocalPathByTitle(title: String, artist: String = ""): String? {
        if (titleToPathMap.isEmpty()) return null
        val normalizedQuery = normalizeTitle(title)
        if (normalizedQuery.isEmpty()) return null

        // Exact normalized title match first
        titleToPathMap[normalizedQuery]?.let { return it }

        // Fuzzy: check if the normalized filename contains the query title
        val artistNorm = normalizeTitle(artist)
        return titleToPathMap.entries.firstOrNull { (key, _) ->
            key.contains(normalizedQuery) ||
            (artistNorm.isNotEmpty() && key.contains(artistNorm) && key.contains(normalizedQuery.take(8)))
        }?.value
    }

    /**
     * Normalizes a title/filename for comparison:
     * - Lowercase
     * - Remove content in brackets: [year], (feat. X), etc.
     * - Remove special characters, keep alphanumerics and spaces
     * - Collapse whitespace
     */
    private fun normalizeTitle(raw: String): String {
        return raw
            .lowercase(Locale.US)
            .replace(Regex("""\[.*?\]"""), "")         // remove [year], [id], etc.
            .replace(Regex("""\(feat\.?.*?\)"""), "")   // remove (feat. X)
            .replace(Regex("""\(ft\.?.*?\)"""), "")
            .replace(Regex("""[^a-z0-9 ]"""), " ")      // keep only alphanumeric + space
            .replace(Regex("""\s+"""), " ")
            .trim()
    }


    /** Returns cached DB metadata for a videoId (fast, no network). */
    suspend fun getCachedMetadata(videoId: String): LocalAudioMetadataCache? {
        return try {
            val dao = DatabaseHolder.Database.localAudioMetadataCacheDao()
            val cached = dao.getByVideoId(videoId) ?: toJsaKey(videoId)?.let { dao.getByVideoId(it) }
            Log.i(TAG, "getCachedMetadata: asked for '$videoId', found: ${cached?.videoId}")
            cached
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the embedded album art from the audio file using MediaMetadataRetriever.
     * Returns raw JPEG/PNG bytes or null if no art is embedded.
     */
    fun getEmbeddedArt(videoId: String): ByteArray? {
        if (embeddedArtCache.containsKey(videoId)) return embeddedArtCache[videoId]

        val path = localAudioMap[videoId] ?: toJsaKey(videoId)?.let { localAudioMap[it] } ?: return null
        val art = try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path)
                retriever.embeddedPicture
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract embedded art for $videoId: ${e.message}")
            null
        }
        embeddedArtCache[videoId] = art
        return art
    }

    /**
     * Returns a file:// URI pointing to cached extracted artwork to avoid large Binder transactions.
     */
    fun getEmbeddedArtUri(context: android.content.Context, videoId: String): String? {
        val artBytes = getEmbeddedArt(videoId) ?: return null
        return try {
            val artFile = java.io.File(context.cacheDir, "art_$videoId.jpg")
            if (!artFile.exists() || artFile.length() == 0L) {
                artFile.writeBytes(artBytes)
            }
            artFile.toURI().toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the ARTIST tag embedded in the local audio file.
     * Uses MediaMetadataRetriever which handles both MP3 (ID3 ARTIST frame)
     * and M4A (©ART atom) correctly regardless of moov atom position.
     * Returns null if no artist tag is present or the file isn't indexed.
     */
    fun getArtistFromFile(videoId: String): String? {
        val path = localAudioMap[videoId]
            ?: toJsaKey(videoId)?.let { localAudioMap[it] }
            ?: return null
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
                    ?.split("\\", ",") // Handle both backslash and comma separated artists
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(", ")
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the TITLE tag embedded in the local audio file.
     * Returns null if no title tag is present or the file isn't indexed.
     */
    fun getTitleFromFile(videoId: String): String? {
        val path = localAudioMap[videoId]
            ?: toJsaKey(videoId)?.let { localAudioMap[it] }
            ?: return null
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
