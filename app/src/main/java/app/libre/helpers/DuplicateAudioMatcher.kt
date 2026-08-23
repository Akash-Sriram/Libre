package app.libre.helpers

import android.util.Log
import app.libre.api.obj.StreamItem
import app.libre.extensions.toID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class CanonicalTrack(
    val videoId: String,
    val originalItem: StreamItem,
    val originalIndex: Int,
    val cleanTitle: String,
    val cleanAlbum: String,
    val cleanArtist: String,
    val duration: Long,
    val localFilePath: String? = null,
    val isJioSaavn: Boolean = false
)

data class DuplicateGroup(
    val primaryTitle: String,
    val primaryAlbum: String,
    val items: List<CanonicalTrack>
)

object DuplicateAudioMatcher {

    private const val TAG = "DuplicateAudioMatcher"

    private val TITLE_JUNK_REGEX = Regex(
        """(?i)[\(\[\{]\s*(?:official\s*(?:music\s*)?(?:video|audio|lyric|hd|4k|remastered|track)?|lyric(?:s)?\s*video|full\s*(?:video|audio|song|track)|video\s*song|4k\s*uhd|remastered|hd|hq|audio|from\s+["'].*?["']|with\s+lyrics)\s*[\)\]\}]"""
    )

    private val METADATA_SPLIT_REGEX = Regex("""(?i)\s+(?:ft\.|feat\.|with|prod\.)\s+.*$""")
    private val NOISE_CHARS_REGEX = Regex("""[^a-zA-Z0-9\s]""")

    fun cleanString(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var cleaned = text.replace(Regex("""^\d{1,3}[\.\s\-_]+"""), " ")
        cleaned = cleaned.replace(TITLE_JUNK_REGEX, " ")
        cleaned = cleaned.replace(METADATA_SPLIT_REGEX, " ")
        cleaned = cleaned.replace(NOISE_CHARS_REGEX, " ")
        return cleaned.lowercase(Locale.US).trim().replace(Regex("""\s+"""), " ")
    }

    /**
     * Instant local-first resolution of canonical metadata for StreamItem.
     */
    fun resolveCanonicalTrackSync(
        item: StreamItem,
        originalIndex: Int = 0
    ): CanonicalTrack {
        val videoId = item.url?.toID().orEmpty()
        val isJio = JioSaavnHelper.isJioSaavn(videoId)
        val localPath = if (videoId.isNotEmpty()) LocalAudioMatcher.getLocalPath(videoId) else null

        val cachedTags = if (videoId.isNotEmpty()) LocalAudioMatcher.tagCache[videoId] else null

        var rawTitle = cachedTags?.title ?: item.title.orEmpty()
        var rawAlbum = cachedTags?.album ?: item.albumName.orEmpty()
        var rawArtist = cachedTags?.artist ?: item.uploaderName.orEmpty()

        val rawDuration = item.duration ?: 0L

        var cleanTitle = cleanString(rawTitle)
        val cleanAlbum = cleanString(rawAlbum)
        val cleanArtist = cleanString(rawArtist.replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), ""))

        if (cleanAlbum.isNotEmpty() && cleanTitle.startsWith(cleanAlbum) && cleanTitle.length > cleanAlbum.length) {
            cleanTitle = cleanTitle.removePrefix(cleanAlbum).trim()
        }

        return CanonicalTrack(
            videoId = videoId,
            originalItem = item,
            originalIndex = originalIndex,
            cleanTitle = cleanTitle,
            cleanAlbum = cleanAlbum,
            cleanArtist = cleanArtist,
            duration = rawDuration,
            localFilePath = localPath,
            isJioSaavn = isJio
        )
    }

    /**
     * Determines whether two canonical tracks represent the same song.
     * Strict matching hierarchy:
     * 1. Exact URL / Video ID or local file path match (primary ground truth).
     * 2. Full track title match + Artist/Album/Duration verification.
     */
    fun isDuplicate(a: CanonicalTrack, b: CanonicalTrack): Boolean {
        // 1. Exact Video ID / URL match (Top Priority Truth)
        if (a.videoId.isNotEmpty() && a.videoId == b.videoId) return true

        // 2. Same physical local file
        if (!a.localFilePath.isNullOrEmpty() && !b.localFilePath.isNullOrEmpty() && a.localFilePath == b.localFilePath) {
            return true
        }

        // 3. Full Track Title Match
        val titleA = a.cleanTitle
        val titleB = b.cleanTitle
        if (titleA.isEmpty() || titleB.isEmpty()) return false

        // Full clean title must match exactly
        if (titleA != titleB) return false

        // 4. Album Conflict Check (if both have specified album names, they must match)
        val albumA = a.cleanAlbum
        val albumB = b.cleanAlbum
        val hasAlbums = albumA.isNotEmpty() && albumB.isNotEmpty()
        if (hasAlbums && albumA != albumB && !albumA.contains(albumB) && !albumB.contains(albumA)) {
            return false
        }

        // 5. Artist Conflict Check (if both have specified artists, they must match)
        val artistA = a.cleanArtist
        val artistB = b.cleanArtist
        val hasArtists = artistA.isNotEmpty() && artistB.isNotEmpty()
        if (hasArtists && artistA != artistB && !artistA.contains(artistB) && !artistB.contains(artistA)) {
            return false
        }

        // 6. Duration Check (if both have durations, must be within 15 seconds)
        if (a.duration > 0 && b.duration > 0) {
            val durationDiff = abs(a.duration - b.duration)
            if (durationDiff > 15) return false
        }

        return true
    }

    /**
     * Scans an entire list of StreamItems and groups detected duplicates together in < 5ms.
     */
    suspend fun findDuplicates(items: List<StreamItem>): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val canonicalList = items.mapIndexed { index, streamItem ->
            resolveCanonicalTrackSync(streamItem, index)
        }

        val processed = BooleanArray(canonicalList.size)
        val duplicateGroups = mutableListOf<DuplicateGroup>()

        for (i in canonicalList.indices) {
            if (processed[i]) continue
            val current = canonicalList[i]
            val groupItems = mutableListOf(current)

            for (j in (i + 1) until canonicalList.size) {
                if (processed[j]) continue
                val candidate = canonicalList[j]
                if (isDuplicate(current, candidate)) {
                    groupItems.add(candidate)
                    processed[j] = true
                }
            }

            if (groupItems.size > 1) {
                processed[i] = true
                val displayTitle = groupItems.firstOrNull { it.isJioSaavn || it.localFilePath != null }?.originalItem?.title
                    ?: current.originalItem.title.orEmpty()
                val displayAlbum = groupItems.firstOrNull { it.cleanAlbum.isNotEmpty() }?.cleanAlbum
                    ?: ""

                duplicateGroups.add(
                    DuplicateGroup(
                        primaryTitle = displayTitle,
                        primaryAlbum = displayAlbum,
                        items = groupItems
                    )
                )
            }
        }

        Log.i(TAG, "Scanned ${items.size} tracks, found ${duplicateGroups.size} duplicate group(s).")
        duplicateGroups
    }
}
