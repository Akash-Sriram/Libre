package app.libre.repo

import app.libre.api.obj.Playlist
import app.libre.api.obj.Playlists
import app.libre.api.obj.StreamItem
import app.libre.obj.PipedImportPlaylist

interface PlaylistRepository {
    suspend fun getPlaylist(playlistId: String): Playlist
    suspend fun getPlaylists(): List<Playlists>
    suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem): Boolean
    suspend fun renamePlaylist(playlistId: String, newName: String): Boolean
    suspend fun changePlaylistDescription(playlistId: String, newDescription: String): Boolean
    suspend fun clonePlaylist(playlistId: String): String?
    suspend fun removeFromPlaylist(playlistId: String, index: Int): Boolean
    suspend fun importPlaylists(playlists: List<PipedImportPlaylist>)
    suspend fun createPlaylist(playlistName: String): String?
    suspend fun deletePlaylist(playlistId: String): Boolean
}