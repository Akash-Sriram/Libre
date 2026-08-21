package app.libre.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.libre.db.obj.LocalAudioMetadataCache

@Dao
interface LocalAudioMetadataCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LocalAudioMetadataCache)

    @Query("SELECT * FROM localAudioMetadataCache WHERE videoId = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: String): LocalAudioMetadataCache?

    @Query("SELECT videoId FROM localAudioMetadataCache")
    suspend fun getAllCachedIds(): List<String>

    /** Returns all entries that have a known local file path (used to restore the in-memory map on launch). */
    @Query("SELECT videoId, localFilePath FROM localAudioMetadataCache WHERE localFilePath != ''")
    suspend fun getAllLocalPaths(): List<VideoIdPath>

    /** Upserts just the videoId + localFilePath, leaving existing metadata untouched. */
    @Query("INSERT OR REPLACE INTO localAudioMetadataCache (videoId, localFilePath, title, uploader, thumbnailUrl, duration, cachedAtMs) " +
           "VALUES (:videoId, :localFilePath, " +
           "COALESCE((SELECT title FROM localAudioMetadataCache WHERE videoId = :videoId), ''), " +
           "COALESCE((SELECT uploader FROM localAudioMetadataCache WHERE videoId = :videoId), ''), " +
           "COALESCE((SELECT thumbnailUrl FROM localAudioMetadataCache WHERE videoId = :videoId), ''), " +
           "COALESCE((SELECT duration FROM localAudioMetadataCache WHERE videoId = :videoId), 0), " +
           "COALESCE((SELECT cachedAtMs FROM localAudioMetadataCache WHERE videoId = :videoId), strftime('%s','now')*1000))")
    suspend fun upsertPath(videoId: String, localFilePath: String)

    @Query("DELETE FROM localAudioMetadataCache WHERE videoId = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    /** Removes entries whose stored file path no longer exists (stale after file deletion). */
    @Query("SELECT videoId, localFilePath FROM localAudioMetadataCache WHERE localFilePath != ''")
    suspend fun getAllForStalenessCheck(): List<VideoIdPath>

    @Query("DELETE FROM localAudioMetadataCache WHERE videoId = :videoId")
    suspend fun deleteStale(videoId: String)
}

/** Lightweight projection used to restore the in-memory path map without loading full metadata. */
data class VideoIdPath(val videoId: String, val localFilePath: String)

