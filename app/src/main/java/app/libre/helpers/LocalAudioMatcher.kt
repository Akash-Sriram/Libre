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

                // Signal: map is ready for playback lookups immediately
                dbRestoreReady.complete(Unit)

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

                // Scan MediaStore for fast permission-free indexing of all audio files
                scanMediaStore(context, restoredIds, newlyFound)

                val knownPaths = HashSet<String>(localAudioMap.values)
                knownPaths.addAll(newlyFound.values)

                for (dir in musicDirs) {
                    scanDirectory(dir, restoredIds, knownPaths, newlyFound)
                }

                // Persist newly found paths to DB
                for ((videoId, path) in newlyFound) {
                    localAudioMap[videoId] = path
                    dao.upsertPath(videoId, path)
                }

                val total = localAudioMap.size
                Log.i(TAG, "Scan complete. Total: $total tracks (${restoredIds.size} from DB, ${newlyFound.size} newly discovered).")
            } catch (e: Exception) {
                Log.e(TAG, "Error during local audio scan", e)
                dbRestoreReady.complete(Unit) // always complete so callers don't hang
            } finally {
                isIndexing = false
            }
        }
    }

    private fun scanMediaStore(
        context: Context,
        alreadyKnown: Set<String>,
        newlyFound: MutableMap<String, String>
    ) {
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media.DATA,
            android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM,
            android.provider.MediaStore.Audio.Media.YEAR,
            android.provider.MediaStore.Audio.Media.TRACK
        )
        try {
            val cursor = context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            ) ?: return

            cursor.use {
                val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                val titleCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.ALBUM)
                val yearCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.YEAR)
                val trackCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.TRACK)
                val albumArtistCol = cursor.getColumnIndex("album_artist").takeIf { it >= 0 } ?: cursor.getColumnIndex("albumartist")
                val genreCol = cursor.getColumnIndex("genre_name").takeIf { it >= 0 } ?: cursor.getColumnIndex("genre")

                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(dataCol) ?: continue
                    val file = File(filePath)
                    if (!file.exists()) continue

                    val videoId = extractVideoId(file)
                    val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) else null
                    val artist = if (artistCol >= 0) normalizeArtistString(cursor.getString(artistCol)) else null
                    val album = if (albumCol >= 0) cursor.getString(albumCol)?.trim() else null
                    val year = if (yearCol >= 0) cursor.getString(yearCol)?.takeIf { it != "0" } else null
                    val rawTrack = if (trackCol >= 0) cursor.getInt(trackCol) else 0
                    val trackNumber = if (rawTrack > 0) rawTrack % 1000 else null
                    val albumArtist = if (albumArtistCol >= 0) normalizeArtistString(cursor.getString(albumArtistCol)) else null
                    val genre = (if (genreCol >= 0) cursor.getString(genreCol)?.trim() else null)
                        ?.takeIf { it.isNotBlank() } ?: extractGenreFromBinary(file.absolutePath)

                    // Index for fuzzy title search
                    val normalizedTitle = normalizeTitle(rawTitle ?: file.nameWithoutExtension)
                    if (normalizedTitle.isNotEmpty()) {
                        titleToPathMap[normalizedTitle] = file.absolutePath
                    }
                    val normalizedFileName = normalizeTitle(file.nameWithoutExtension)
                    if (normalizedFileName.isNotEmpty() && normalizedFileName != normalizedTitle) {
                        titleToPathMap[normalizedFileName] = file.absolutePath
                    }

                    val title = rawTitle?.trim()?.takeIf { it.isNotBlank() }
                    val tags = LocalAudioTags(artist, album, year, trackNumber, albumArtist, genre, title)
                    tagCache[file.absolutePath] = tags
                    tagCache[file.name] = tags

                    if (videoId != null) {
                        if (videoId !in alreadyKnown && videoId !in newlyFound) {
                            newlyFound[videoId] = file.absolutePath
                            Log.i(TAG, "MediaStore Match: ${file.name} -> $videoId")
                        }
                        tagCache[videoId] = tags
                    }
                    if (normalizedTitle.isNotEmpty()) {
                        tagCache[normalizedTitle] = tags
                    }
                    if (normalizedFileName.isNotEmpty() && normalizedFileName != normalizedTitle) {
                        tagCache[normalizedFileName] = tags
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore scan error", e)
        }
    }

    private fun scanDirectory(
        dir: File,
        alreadyKnownIds: Set<String>,
        knownPaths: Set<String>,
        newlyFound: MutableMap<String, String>
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, alreadyKnownIds, knownPaths, newlyFound)
            } else if (file.isFile) {
                val path = file.absolutePath
                if (path in knownPaths) continue

                val name = file.name.lowercase(Locale.US)
                if (name.endsWith(".mp3") || name.endsWith(".m4a") ||
                    name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".wav")
                ) {
                    val videoId = extractVideoId(file)
                    if (videoId != null) {
                        if (videoId !in alreadyKnownIds && videoId !in newlyFound) {
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
     * 2. **Binary head scan** (first 256KB): extracts URL / video ID from ID3 COMM, WXXX, WOAS,
     *    and MP4 ©cmt atoms across ASCII, UTF-8, and UTF-16 encodings.
     * 3. **Binary tail scan** (last 128KB, M4A & ID3v1): handles tail-moov and footer tags.
     * 4. **Exact YouTube & JioSaavn URL matches**.
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
            // Strategy 2: head scan (first 256KB)
            val headSize = minOf(file.length(), 256 * 1024).toInt()
            val headBuffer = ByteArray(headSize)
            FileInputStream(file).use { it.read(headBuffer) }
            val rawHead = String(headBuffer, charset("ISO-8859-1"))
            // Remove null bytes so UTF-16LE / UTF-16BE encoded ID3 frames become readable ASCII
            val head = rawHead.replace("\u0000", "")

            // 2a: Any full YouTube URL in comment / frames
            val ytUrlMatch = Regex("""(?:youtube\.com/(?:watch\?v=|shorts/|v/|embed/)|youtu\.be/)([a-zA-Z0-9_-]{11})""").find(head)
            if (ytUrlMatch != null) return ytUrlMatch.groupValues[1]

            // 2b: YouTube ID near COMM frame or description tags
            val commIdx = head.indexOf("COMM")
            if (commIdx != -1) {
                val area = head.substring(commIdx, minOf(commIdx + 500, head.length))
                val m = Regex("""(?:v=|/v/|embed/|youtu\.be/|\b)([a-zA-Z0-9_-]{11})\b""").find(area)
                if (m != null) return m.groupValues[1]
            }

            // 2c: JioSaavn URL in head (head-moov M4A or ID3 COMM)
            val jsaHead = Regex("""jiosaavn\.com/song/[^/\s]+/([A-Za-z0-9_-]{6,20})""").find(head)
            if (jsaHead != null) return "jsa:${jsaHead.groupValues[1]}"

            // Strategy 3: tail scan for M4A (tail-moov layout — ©cmt atom after mdat) or MP3 ID3v1
            if (file.length() > headSize + 1024) {
                val tailSize = minOf(file.length() - headSize, 128 * 1024).toInt()
                val tailBuffer = ByteArray(tailSize)
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - tailSize)
                    raf.read(tailBuffer)
                }
                val rawTail = String(tailBuffer, charset("ISO-8859-1"))
                val tail = rawTail.replace("\u0000", "")

                val ytTailUrl = Regex("""(?:youtube\.com/(?:watch\?v=|shorts/|v/|embed/)|youtu\.be/)([a-zA-Z0-9_-]{11})""").find(tail)
                if (ytTailUrl != null) return ytTailUrl.groupValues[1]

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
    /**
     * Tries to find a local file matching [title] (and optionally [artist]) for songs
     * that have no YouTube ID (e.g. JioSaavn songs, local songs). Uses normalized string comparison.
     * Returns the local file path if found, null otherwise.
     */
    fun getLocalPathByTitle(title: String, artist: String = ""): String? {
        if (titleToPathMap.isEmpty()) return null
        val normalizedQuery = normalizeTitle(title)
        if (normalizedQuery.length < 3) return null

        // 1. Exact normalized title match
        titleToPathMap[normalizedQuery]?.let { return it }

        // 2. Exact match with artist if provided
        val artistNorm = normalizeTitle(artist)
        if (artistNorm.isNotEmpty()) {
            val combined = "$artistNorm $normalizedQuery"
            titleToPathMap[combined]?.let { return it }
        }

        return null
    }

    /**
     * Normalizes a title/filename for comparison:
     * - Lowercase
     * - Remove track numbers: "01 - ", "02. "
     * - Remove bracket tags: [year], (feat. X), [320kbps], etc.
     * - Strip music site tags (MassTamilan, StarMusiq, etc.)
     * - Remove special characters, keep alphanumerics and spaces
     * - Collapse whitespace
     */
    fun normalizeTitle(raw: String): String {
        return raw
            .lowercase(Locale.US)
            .replace(Regex("""^\d+[\s._-]+"""), "")     // remove leading track numbers "01 - ", "02. "
            .replace(Regex("""\[.*?\]"""), "")         // remove [year], [id], [320kbps]
            .replace(Regex("""\(feat\.?.*?\)"""), "")   // remove (feat. X)
            .replace(Regex("""\(ft\.?.*?\)"""), "")
            .replace(Regex("""\b(masstamilan|starmusiq|sensongs|isaimini|tamiltunes|128kbps|320kbps|official|audio|video|song|lyric|lyrics)\b"""), " ")
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

    data class LocalAudioTags(
        val artist: String?,
        val album: String?,
        val year: String?,
        val trackNumber: Int? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val title: String? = null
    )

    val tagCache = ConcurrentHashMap<String, LocalAudioTags>()

    fun normalizeArtistString(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .split(Regex("""\\+|\s*,\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Formats a song title with a 2-digit track number prefix (e.g. "02. Song Title").
     */
    fun formatTitleWithTrackNumber(title: String, trackNumber: Int?): String {
        if (trackNumber == null || trackNumber <= 0) return title
        val cleanTitle = title.trim()
        if (Regex("""^\d{1,3}[\s.\-_]""").containsMatchIn(cleanTitle)) {
            return cleanTitle
        }
        val prefix = String.format(Locale.US, "%02d. ", trackNumber)
        return "$prefix$cleanTitle"
    }

    private fun extractTagsForPath(cacheKey: String, path: String): LocalAudioTags {
        val cached = tagCache[cacheKey]
        if (cached != null && cached.genre != null && cached.artist != null && cached.title != null) return cached

        val tags = try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path)
                val artist = normalizeArtistString(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
                    ?.takeIf { it.isNotBlank() }
                val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                val year = rawDate?.let {
                    Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value ?: it.take(4)
                }?.takeIf { it.isNotBlank() }
                val rawTrackStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                val trackNumber = rawTrackStr?.substringBefore("/")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                val albumArtist = normalizeArtistString(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                var genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim()
                    ?.takeIf { it.isNotBlank() }
                if (genre.isNullOrBlank()) {
                    genre = extractGenreFromBinary(path)
                }
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                    ?.takeIf { it.isNotBlank() }

                LocalAudioTags(artist, album, year, trackNumber, albumArtist, genre, title)
            }
        } catch (_: Exception) {
            val binaryGenre = extractGenreFromBinary(path)
            LocalAudioTags(null, null, null, null, null, binaryGenre, null)
        }
        tagCache[cacheKey] = tags
        return tags
    }

    private fun extractGenreFromBinary(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() < 32) return null
            val size = minOf(file.length(), 256 * 1024).toInt()
            val buffer = ByteArray(size)
            FileInputStream(file).use { it.read(buffer) }
            val raw = String(buffer, charset("ISO-8859-1"))

            // 1. M4A / MP4 atom \xa9gen
            val genIdx = raw.indexOf("\u00a9gen")
            if (genIdx != -1) {
                val dataIdx = raw.indexOf("data", genIdx)
                if (dataIdx != -1 && dataIdx - genIdx <= 20) {
                    val textStart = dataIdx + 12
                    val textLen = minOf(64, raw.length - textStart)
                    val genreRaw = raw.substring(textStart, textStart + textLen)
                    val clean = genreRaw.takeWhile { it >= ' ' && it != '\u0000' && it.code != 127 }.trim()
                    if (clean.isNotEmpty()) return clean
                }
            }

            // 2. MP3 ID3v2 TCON frame
            val tconIdx = raw.indexOf("TCON")
            if (tconIdx != -1) {
                val textStart = tconIdx + 10 // TCON (4) + size (4) + flags (2)
                if (textStart < raw.length) {
                    val textLen = minOf(64, raw.length - textStart)
                    val genreRaw = raw.substring(textStart, textStart + textLen).replace("\u0000", "")
                    val clean = genreRaw.takeWhile { it >= ' ' && it.code != 127 }.trim()
                    if (clean.isNotEmpty()) return clean
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePath(videoId: String, title: String? = null): String? {
        if (videoId.isNotEmpty()) {
            val direct = localAudioMap[videoId] ?: toJsaKey(videoId)?.let { localAudioMap[it] }
            if (direct != null) return direct
        }
        if (!title.isNullOrBlank()) {
            val titleMatch = getLocalPathByTitle(title)
            if (titleMatch != null) {
                return titleMatch
            }
        }
        return null
    }

    fun getArtistFromFile(videoId: String, title: String? = null): String? {
        val key = videoId.ifEmpty { title.orEmpty() }
        val cached = tagCache[key]
        if (!cached?.artist.isNullOrBlank()) return cached.artist
        val path = resolvePath(videoId, title) ?: return null
        return extractTagsForPath(key, path).artist
    }

    /**
     * Reads the TITLE tag embedded in the local audio file.
     * Returns null if no title tag is present or the file isn't indexed.
     */
    fun getTitleFromFile(videoId: String, title: String? = null): String? {
        val key = videoId.ifEmpty { title.orEmpty() }
        val cached = tagCache[key]
        if (!cached?.title.isNullOrBlank()) return cached.title
        val path = resolvePath(videoId, title) ?: return null
        return extractTagsForPath(key, path).title
    }

    /**
     * Reads the ALBUM tag embedded in the local audio file.
     * Returns null if no album tag is present or the file isn't indexed.
     */
    fun getAlbumFromFile(videoId: String, title: String? = null): String? {
        val key = videoId.ifEmpty { title.orEmpty() }
        val cached = tagCache[key]
        if (!cached?.album.isNullOrBlank()) return cached.album
        val path = resolvePath(videoId, title) ?: return null
        return extractTagsForPath(key, path).album
    }

    /**
     * Reads the YEAR/DATE tag embedded in the local audio file.
     * Returns null if no year tag is present or the file isn't indexed.
     */
    fun getYearFromFile(videoId: String, title: String? = null): String? {
        val key = videoId.ifEmpty { title.orEmpty() }
        val cached = tagCache[key]
        if (!cached?.year.isNullOrBlank()) return cached.year
        val path = resolvePath(videoId, title) ?: return null
        return extractTagsForPath(key, path).year
    }

    /**
     * Reads the TRACK NUMBER tag embedded in the local audio file.
     */
    fun getTrackNumberFromFile(videoId: String, title: String? = null): Int? {
        val key = videoId.ifEmpty { title.orEmpty() }
        val cached = tagCache[key]
        if (cached?.trackNumber != null) return cached.trackNumber
        val path = resolvePath(videoId, title) ?: return null
        return extractTagsForPath(key, path).trackNumber
    }

    /**
     * Reads the ALBUM ARTIST tag embedded in the local audio file.
     */
    fun getAlbumArtistFromFile(videoId: String, title: String? = null): String? {
        val key = videoId.ifEmpty { title.orEmpty() }
        val cached = tagCache[key]
        if (!cached?.albumArtist.isNullOrBlank()) return cached.albumArtist
        val path = resolvePath(videoId, title) ?: return null
        return extractTagsForPath(key, path).albumArtist
    }

    /**
     * Reads the GENRE tag embedded in the local audio file.
     */
    fun getGenreFromFile(videoId: String, title: String? = null): String? {
        val key = videoId.ifEmpty { title.orEmpty() }
        val cached = tagCache[key]
        if (!cached?.genre.isNullOrBlank()) return cached.genre
        val path = resolvePath(videoId, title) ?: return null
        return extractTagsForPath(key, path).genre
    }
}
