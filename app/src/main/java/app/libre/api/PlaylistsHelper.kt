package app.libre.api

import androidx.core.text.isDigitsOnly
import app.libre.api.obj.Playlist
import app.libre.api.obj.Playlists
import app.libre.api.obj.StreamItem
import app.libre.constants.PreferenceKeys
import app.libre.enums.PlaylistType
import app.libre.helpers.PreferenceHelper
import app.libre.obj.PipedImportPlaylist
import app.libre.repo.LocalPlaylistsRepository
import app.libre.repo.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

object PlaylistsHelper {
    const val MAX_CONCURRENT_IMPORT_CALLS = 5

    val loggedIn: Boolean get() = false
    private val playlistsRepository: PlaylistRepository
        get() = LocalPlaylistsRepository()

    suspend fun getPlaylists(): List<Playlists> = withContext(Dispatchers.IO) {
        val playlists = playlistsRepository.getPlaylists()
        sortPlaylists(playlists)
    }

    private fun sortPlaylists(playlists: List<Playlists>): List<Playlists> {
        return when (
            PreferenceHelper.getString(PreferenceKeys.PLAYLISTS_ORDER, "creation_date")
        ) {
            "creation_date" -> playlists
            "creation_date_reversed" -> playlists.reversed()
            "alphabetic" -> playlists.sortedBy { it.name?.lowercase() }
            "alphabetic_reversed" -> playlists.sortedBy { it.name?.lowercase() }
                .reversed()

            else -> playlists
        }
    }

    suspend fun getPlaylist(playlistId: String): Playlist {
        // JioSaavn search results prefix album IDs with "jsa_" to distinguish them from local playlists
        if (playlistId.startsWith("jsa_")) {
            return JioSaavnMediaServiceRepository().getPlaylist(playlistId)
        }
        // load locally stored playlists with the auth api
        val type = getPlaylistType(playlistId)
        if (type != PlaylistType.PUBLIC) {
            return playlistsRepository.getPlaylist(playlistId)
        }
        return MediaServiceRepository.instance.getPlaylist(playlistId)
    }

    suspend fun getAllPlaylistsWithVideos(playlistIds: List<String>? = null): List<Playlist> {
        return withContext(Dispatchers.IO) {
            (playlistIds ?: getPlaylists().map { it.id!! })
                .map { async { getPlaylist(it) } }
                .awaitAll()
        }
    }

    suspend fun createPlaylist(playlistName: String) =
        playlistsRepository.createPlaylist(playlistName)

    suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem) =
        withContext(Dispatchers.IO) {
            playlistsRepository.addToPlaylist(playlistId, *videos)
        }

    suspend fun renamePlaylist(playlistId: String, newName: String) =
        playlistsRepository.renamePlaylist(playlistId, newName)

    suspend fun changePlaylistDescription(playlistId: String, newDescription: String) =
        playlistsRepository.changePlaylistDescription(playlistId, newDescription)

    suspend fun removeFromPlaylist(playlistId: String, index: Int) =
        playlistsRepository.removeFromPlaylist(playlistId, index)

    suspend fun importPlaylists(playlists: List<PipedImportPlaylist>) =
        playlistsRepository.importPlaylists(playlists)

    suspend fun clonePlaylist(playlistId: String) = playlistsRepository.clonePlaylist(playlistId)
    suspend fun deletePlaylist(playlistId: String) = playlistsRepository.deletePlaylist(playlistId)

    fun getPrivatePlaylistType(): PlaylistType {
        return if (loggedIn) PlaylistType.PRIVATE else PlaylistType.LOCAL
    }

    fun getPlaylistType(playlistId: String): PlaylistType {
        // JioSaavn search results prefix album IDs with "jsa_" - always treat as PUBLIC
        if (playlistId.startsWith("jsa_")) return PlaylistType.PUBLIC
        return if (playlistId.isDigitsOnly()) {
            PlaylistType.LOCAL
        } else {
            PlaylistType.PUBLIC
        }
    }
}
