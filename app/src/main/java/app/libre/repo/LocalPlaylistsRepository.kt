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
import app.libre.extensions.toID
import app.libre.obj.PipedImportPlaylist

class LocalPlaylistsRepository: PlaylistRepository {
    override suspend fun getPlaylist(playlistId: String): Playlist {
        val idLong = playlistId.toLongOrNull()
        val relation = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong) else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }
            ?: throw NoSuchElementException("Playlist with id $playlistId not found")

        val latestSongThumb = relation.videos.lastOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
        val bestThumb = latestSongThumb ?: relation.playlist.thumbnailUrl.orEmpty()
        return Playlist(
            name = relation.playlist.name,
            description = relation.playlist.description,
            thumbnailUrl = bestThumb,
            videos = relation.videos.size,
            relatedStreams = relation.videos.map { it.toStreamItem() }
        )
    }

    override suspend fun getPlaylists(): List<Playlists> {
        return DatabaseHolder.Database.localPlaylistsDao().getAll()
            .map {
                val latestSongThumb = it.videos.lastOrNull { v -> !v.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                val thumb = latestSongThumb ?: it.playlist.thumbnailUrl.orEmpty()
                Playlists(
                    id = it.playlist.id.toString(),
                    name = it.playlist.name,
                    shortDescription = it.playlist.description,
                    thumbnail = thumb,
                    videos = it.videos.size.toLong()
                )
            }
    }

    override suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem): Boolean {
        val idLong = playlistId.toLongOrNull()
        val localPlaylist = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong) else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }
            ?: return false

        val existingCanonical = localPlaylist.videos.map {
            app.libre.helpers.DuplicateAudioMatcher.resolveCanonicalTrackSync(it.toStreamItem())
        }.toMutableList()

        for (video in videos) {
            val videoId = video.url?.toID().orEmpty()
            var targetVideo = video
            if (videoId.length == 11) {
                val titleLower = video.title?.lowercase().orEmpty()
                val isLikelyVideo = titleLower.contains("video") || titleLower.contains("promo") ||
                        titleLower.contains("official") || titleLower.contains("4k")
                if (isLikelyVideo) {
                    val rawArtist = (video.uploaderName ?: "").replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
                    val artist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist) ?: rawArtist
                    val master = app.libre.api.YtMusicApi.resolveStudioMaster(video.title.orEmpty(), artist)
                    if (master != null) {
                        targetVideo = master
                    }
                }
            }

            val localPlaylistItem = targetVideo.toLocalPlaylistItem(playlistId)
            val candidateCanonical = app.libre.helpers.DuplicateAudioMatcher.resolveCanonicalTrackSync(targetVideo)

            val existingMatch = existingCanonical.firstOrNull {
                app.libre.helpers.DuplicateAudioMatcher.isDuplicate(it, candidateCanonical)
            }

            if (existingMatch != null) {
                // If duplicate exists across YouTube/JioSaavn, skip inserting duplicate
                val existingVideo = DatabaseHolder.Database.localPlaylistsDao()
                    .getPlaylistVideo(playlistId, existingMatch.videoId)
                if (existingVideo != null && localPlaylistItem.videoId == existingVideo.videoId) {
                    localPlaylistItem.id = existingVideo.id
                    DatabaseHolder.Database.localPlaylistsDao().updatePlaylistVideo(localPlaylistItem)
                }
                continue
            }

            // add the new video to the database
            DatabaseHolder.Database.localPlaylistsDao().addPlaylistVideo(localPlaylistItem)
            existingCanonical.add(candidateCanonical)

            val playlist = localPlaylist.playlist
            localPlaylistItem.thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                playlist.thumbnailUrl = it
                DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)
            }
        }

        app.libre.helpers.LocalPlaylistsCache.reload()
        return true
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Boolean {
        val idLong = playlistId.toLongOrNull()
        val playlist = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong)?.playlist else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }?.playlist
            ?: return false
        playlist.name = newName
        DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)
        app.libre.helpers.LocalPlaylistsCache.reload()

        return true
    }

    override suspend fun changePlaylistDescription(playlistId: String, newDescription: String): Boolean {
        val idLong = playlistId.toLongOrNull()
        val playlist = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong)?.playlist else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }?.playlist
            ?: return false
        playlist.description = newDescription
        DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)
        app.libre.helpers.LocalPlaylistsCache.reload()

        return true
    }

    override suspend fun clonePlaylist(playlistId: String): String {
        val playlist = PlaylistsHelper.getPlaylist(playlistId)
        val playlistName = playlist.name ?: "Unknown name"
        val newPlaylist = createPlaylist(playlistName)

        val streams = playlist.relatedStreams
        if (streams.isNotEmpty()) {
            PlaylistsHelper.addToPlaylist(newPlaylist, *streams.toTypedArray())
        }

        if (!playlist.thumbnailUrl.isNullOrEmpty()) {
            val idLong = newPlaylist.toLongOrNull()
            val localPlaylist = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong)?.playlist else null)
            if (localPlaylist != null) {
                localPlaylist.thumbnailUrl = playlist.thumbnailUrl!!
                DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(localPlaylist)
            }
        }

        var nextPage = playlist.nextpage
        while (nextPage != null) {
            val pageToFetch = nextPage
            nextPage = runCatching {
                MediaServiceRepository.instance.getPlaylistNextPage(playlistId, pageToFetch).apply {
                    if (relatedStreams.isNotEmpty()) {
                        PlaylistsHelper.addToPlaylist(newPlaylist, *relatedStreams.toTypedArray())
                    }
                }.nextpage
            }.getOrNull()
        }

        app.libre.helpers.LocalPlaylistsCache.reload()
        return newPlaylist
    }

    override suspend fun removeFromPlaylist(playlistId: String, index: Int): Boolean {
        val idLong = playlistId.toLongOrNull()
        val transaction = (if (idLong != null) DatabaseHolder.Database.localPlaylistsDao().getById(idLong) else null)
            ?: DatabaseHolder.Database.localPlaylistsDao().getAll().firstOrNull { it.playlist.id.toString() == playlistId }
            ?: return false

        val videoToRemove = transaction.videos.getOrNull(index) ?: return false
        DatabaseHolder.Database.localPlaylistsDao().removePlaylistVideo(videoToRemove)

        // Recalculate and set the new playlist thumbnail from the remaining tracks (last added track)
        val remainingVideos = transaction.videos.filter { it.id != videoToRemove.id }
        val newThumb = remainingVideos.lastOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl.orEmpty()
        transaction.playlist.thumbnailUrl = newThumb
        DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(transaction.playlist)
        app.libre.helpers.LocalPlaylistsCache.reload()

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
        app.libre.helpers.LocalPlaylistsCache.reload()
    }

    override suspend fun createPlaylist(playlistName: String): String {
        val playlist = LocalPlaylist(name = playlistName, thumbnailUrl = "")
        val result = DatabaseHolder.Database.localPlaylistsDao().createPlaylist(playlist).toString()
        app.libre.helpers.LocalPlaylistsCache.reload()
        return result
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean {
        DatabaseHolder.Database.localPlaylistsDao().deletePlaylistById(playlistId)
        DatabaseHolder.Database.localPlaylistsDao().deletePlaylistItemsByPlaylistId(playlistId)
        app.libre.helpers.LocalPlaylistsCache.reload()

        return true
    }
}