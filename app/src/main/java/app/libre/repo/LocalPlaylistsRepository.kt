package app.libre.repo

import app.libre.api.MediaServiceRepository
import app.libre.api.PlaylistsHelper
import app.libre.api.PlaylistsHelper.MAX_CONCURRENT_IMPORT_CALLS
import app.libre.api.obj.Playlist
import app.libre.api.obj.Playlists
import app.libre.api.obj.StreamItem
import app.libre.db.DatabaseHolder
import app.libre.db.obj.LocalPlaylist
import app.libre.extensions.parallelMap
import app.libre.obj.PipedImportPlaylist

class LocalPlaylistsRepository: PlaylistRepository {
    override suspend fun getPlaylist(playlistId: String): Playlist {
        val idLong = playlistId.toLongOrNull()
        val relation = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong) else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }
            ?: throw NoSuchElementException("Playlist with id $playlistId not found")

        val lastVideoThumbnail = relation.videos.lastOrNull()?.thumbnailUrl

        return Playlist(
            name = relation.playlist.name,
            description = relation.playlist.description,
            thumbnailUrl = lastVideoThumbnail ?: relation.playlist.thumbnailUrl,
            videos = relation.videos.size,
            relatedStreams = relation.videos.map { it.toStreamItem() }
        )
    }

    override suspend fun getPlaylists(): List<Playlists> {
        return DatabaseHolder.Database.localPlaylistsDao().getAll()
            .map {
                val lastVideoThumbnail = it.videos.lastOrNull()?.thumbnailUrl
                Playlists(
                    id = it.playlist.id.toString(),
                    name = it.playlist.name,
                    shortDescription = it.playlist.description,
                    thumbnail = lastVideoThumbnail ?: it.playlist.thumbnailUrl,
                    videos = it.videos.size.toLong()
                )
            }
    }

    override suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem): Boolean {
        val idLong = playlistId.toLongOrNull()
        val localPlaylist = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong) else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }
            ?: return false

        for (video in videos) {
            val localPlaylistItem = video.toLocalPlaylistItem(playlistId)

            val existingVideo = DatabaseHolder.Database.localPlaylistsDao()
                .getPlaylistVideo(playlistId, localPlaylistItem.videoId)
            if (existingVideo != null) {
                // update existing video metadata
                localPlaylistItem.id = existingVideo.id
                DatabaseHolder.Database.localPlaylistsDao().updatePlaylistVideo(localPlaylistItem)
                continue
            }

            // add the new video to the database
            DatabaseHolder.Database.localPlaylistsDao().addPlaylistVideo(localPlaylistItem)

            val playlist = localPlaylist.playlist
            if (playlist.thumbnailUrl.isEmpty()) {
                // set the new playlist thumbnail URL
                localPlaylistItem.thumbnailUrl?.let {
                    playlist.thumbnailUrl = it
                    DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)
                }
            }
        }

        return true
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Boolean {
        val idLong = playlistId.toLongOrNull()
        val playlist = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong)?.playlist else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }?.playlist
            ?: return false
        playlist.name = newName
        DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)

        return true
    }

    override suspend fun changePlaylistDescription(playlistId: String, newDescription: String): Boolean {
        val idLong = playlistId.toLongOrNull()
        val playlist = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong)?.playlist else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }?.playlist
            ?: return false
        playlist.description = newDescription
        DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)

        return true
    }

    override suspend fun clonePlaylist(playlistId: String): String {
        val playlist = MediaServiceRepository.instance.getPlaylist(playlistId)
        val newPlaylist = createPlaylist(playlist.name ?: "Unknown name")

        PlaylistsHelper.addToPlaylist(newPlaylist, *playlist.relatedStreams.toTypedArray())

        var nextPage = playlist.nextpage
        while (nextPage != null) {
            val pageToFetch = nextPage
            nextPage = runCatching {
                MediaServiceRepository.instance.getPlaylistNextPage(playlistId, pageToFetch).apply {
                    PlaylistsHelper.addToPlaylist(newPlaylist, *relatedStreams.toTypedArray())
                }.nextpage
            }.getOrNull()
        }

        return playlistId
    }

    override suspend fun removeFromPlaylist(playlistId: String, index: Int): Boolean {
        val idLong = playlistId.toLongOrNull()
        val transaction = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong) else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }
            ?: return false

        val videoToRemove = transaction.videos.getOrNull(index) ?: return false
        DatabaseHolder.Database.localPlaylistsDao().removePlaylistVideo(videoToRemove)

        // set a new playlist thumbnail if the first video got removed
        if (index == 0) {
            transaction.playlist.thumbnailUrl =
                transaction.videos.getOrNull(1)?.thumbnailUrl.orEmpty()
        }
        DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(transaction.playlist)

        return true
    }

    override suspend fun importPlaylists(playlists: List<PipedImportPlaylist>) {
        val currentPlaylists = DatabaseHolder.Database.localPlaylistsDao().getAll()
        for (playlist in playlists) {
            val existing = currentPlaylists.find { it.playlist.name == playlist.name }
            val playlistId = existing?.playlist?.id?.toString() ?: createPlaylist(playlist.name!!)

            val existingVideoIds = existing?.videos?.map { it.videoId }?.toSet().orEmpty()
            val newVideos = playlist.videos.filter { it !in existingVideoIds }

            if (newVideos.isNotEmpty()) {
                // if not logged in, all video information needs to become fetched manually
                // Only do so with `MAX_CONCURRENT_IMPORT_CALLS` videos at once to prevent performance issues
                for (videoIdList in newVideos.chunked(MAX_CONCURRENT_IMPORT_CALLS)) {
                    val streams = videoIdList.parallelMap {
                        runCatching { MediaServiceRepository.instance.getStreams(it) }
                            .getOrNull()
                            ?.toStreamItem(it)
                    }.filterNotNull()

                    PlaylistsHelper.addToPlaylist(playlistId, *streams.toTypedArray())
                }
            }
        }
    }

    override suspend fun createPlaylist(playlistName: String): String {
        val playlist = LocalPlaylist(name = playlistName, thumbnailUrl = "")
        return DatabaseHolder.Database.localPlaylistsDao().createPlaylist(playlist).toString()
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean {
        DatabaseHolder.Database.localPlaylistsDao().deletePlaylistById(playlistId)
        DatabaseHolder.Database.localPlaylistsDao().deletePlaylistItemsByPlaylistId(playlistId)

        return true
    }
}