package app.libre.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.libre.db.dao.LocalAudioMetadataCacheDao
import app.libre.db.dao.LocalPlaylistsDao
import app.libre.db.dao.PlaylistBookmarkDao
import app.libre.db.dao.SearchHistoryDao
import app.libre.db.obj.LocalAudioMetadataCache
import app.libre.db.obj.LocalPlaylist
import app.libre.db.obj.LocalPlaylistItem
import app.libre.db.obj.PlaylistBookmark
import app.libre.db.obj.SearchHistoryItem

@Database(
    entities = [
        SearchHistoryItem::class,
        PlaylistBookmark::class,
        LocalPlaylist::class,
        LocalPlaylistItem::class,
        LocalAudioMetadataCache::class
    ],
    version = 32,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Search History
     */
    abstract fun searchHistoryDao(): SearchHistoryDao

    /**
     * Bookmarked Playlists
     */
    abstract fun playlistBookmarkDao(): PlaylistBookmarkDao

    /**
     * Local playlists
     */
    abstract fun localPlaylistsDao(): LocalPlaylistsDao

    /**
     * Local audio metadata cache (thumbnails, titles, etc. for Syncthing-matched tracks)
     */
    abstract fun localAudioMetadataCacheDao(): LocalAudioMetadataCacheDao
}
