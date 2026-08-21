package app.libre.obj

import app.libre.db.obj.LocalPlaylistWithVideos
import app.libre.db.obj.PlaylistBookmark
import app.libre.db.obj.SearchHistoryItem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class BackupFile(
    //
    // some stuff for compatibility with Piped imports
    //
    val format: String = "Piped",
    val version: Int = 1,

    //
    // only compatible with LibreTube itself, database objects
    //
    var searchHistory: List<SearchHistoryItem>? = null,
    var playlistBookmarks: List<PlaylistBookmark>? = null,

    //
    // Preferences, stored as a key value map
    //
    var preferences: List<PreferenceItem>? = null,

    var localPlaylists: List<LocalPlaylistWithVideos>? = null,
    var playlists: List<PipedImportPlaylist>? = null
)
