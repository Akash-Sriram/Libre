package app.libre.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.libre.db.obj.LocalPlaylist
import app.libre.db.obj.LocalPlaylistItem
import app.libre.db.obj.LocalPlaylistWithVideos

@Dao
interface LocalPlaylistsDao {
    @Transaction
    @Query("SELECT * FROM LocalPlaylist")
    suspend fun getAll(): List<LocalPlaylistWithVideos>

    @Transaction
    @Query("SELECT * FROM LocalPlaylist WHERE id = :playlistId")
    suspend fun getById(playlistId: Long): LocalPlaylistWithVideos?

    @Query("SELECT EXISTS(SELECT 1 FROM localPlaylistItem WHERE videoId = :videoId LIMIT 1)")
    suspend fun isVideoInAnyPlaylist(videoId: String): Boolean

    @Insert
    suspend fun createPlaylist(playlist: LocalPlaylist): Long

    @Update
    suspend fun updatePlaylist(playlist: LocalPlaylist)

    @Query("DELETE FROM localPlaylist WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: String)

    @Insert
    suspend fun addPlaylistVideo(playlistVideo: LocalPlaylistItem)

    @Update
    suspend fun updatePlaylistVideo(playlistVideo: LocalPlaylistItem)

    @Delete
    suspend fun removePlaylistVideo(playlistVideo: LocalPlaylistItem)

    @Query("DELETE FROM localPlaylistItem WHERE playlistId = :playlistId")
    suspend fun deletePlaylistItemsByPlaylistId(playlistId: String)

    @Query("DELETE FROM localPlaylistItem WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun deletePlaylistItemsByVideoId(playlistId: String, videoId: String)

    @Query("SELECT * FROM localPlaylistItem WHERE playlistId = :playlistId AND videoId = :videoId LIMIT 1")
    suspend fun getPlaylistVideo(playlistId: String, videoId: String): LocalPlaylistItem?

    @Query("SELECT * FROM localPlaylistItem WHERE videoId = :videoId LIMIT 1")
    suspend fun getPlaylistItemByVideoId(videoId: String): LocalPlaylistItem?

    @Query("UPDATE localPlaylistItem SET title = :title, uploader = :uploader, albumName = :albumName WHERE videoId = :videoId")
    suspend fun updateTrackMetadata(videoId: String, title: String?, uploader: String?, albumName: String?)

    @Query("SELECT DISTINCT videoId FROM localPlaylistItem")
    suspend fun getAllDistinctVideoIds(): List<String>

    @Query("SELECT DISTINCT videoId FROM localPlaylistItem WHERE albumName IS NULL")
    suspend fun getUnscannedMetadataVideoIds(): List<String>

    @Query("SELECT duration FROM localPlaylistItem WHERE videoId = :videoId LIMIT 1")
    suspend fun getVideoDuration(videoId: String): Long?
}
