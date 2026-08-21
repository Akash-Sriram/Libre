package app.libre.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached metadata for locally matched audio files (matched by video ID in comment tags).
 * - [localFilePath] persists the matched file path so a filesystem re-scan is not needed on every launch.
 * - Metadata fields (title, uploader, thumbnailUrl, duration) are populated online and used offline.
 */
@Entity(tableName = "localAudioMetadataCache")
data class LocalAudioMetadataCache(
    @PrimaryKey val videoId: String,
    /** Absolute path to the matched local audio file. Non-null once the file scan has run. */
    val localFilePath: String = "",
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val duration: Long,
    val cachedAtMs: Long = System.currentTimeMillis()
)

