package app.libre.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.libre.R
import app.libre.api.JsonHelper
import app.libre.api.PlaylistsHelper
import app.libre.enums.ImportFormat
import app.libre.extensions.TAG
import app.libre.extensions.toID
import app.libre.extensions.toastFromMainDispatcher
import app.libre.obj.PipedImportPlaylist
import app.libre.obj.PipedPlaylistFile
import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_FRONTEND_URL
import app.libre.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

object ImportHelper {
    private const val VIDEO_ID_LENGTH = 11

    // format: playlistName-videos.csv, where "videos" could also be i18ned to a different language
    private val csvPlaylistNameRegex = Regex("""(.*)-(\w+)\.csv""")

    /**
     * Import Playlists
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importPlaylists(context: Context, uri: Uri, importFormat: ImportFormat) {
        val importPlaylists = mutableListOf<PipedImportPlaylist>()

        when (importFormat) {
            ImportFormat.PIPED -> {
                val playlistFile = context.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<PipedPlaylistFile>(it)
                }
                importPlaylists.addAll(playlistFile?.playlists.orEmpty())

                // convert the YouTube URLs to videoIds
                importPlaylists.forEach { playlist ->
                    playlist.videos = playlist.videos.map { it.takeLast(VIDEO_ID_LENGTH) }
                }
            }

            ImportFormat.YOUTUBECSV -> {
                val playlist = PipedImportPlaylist()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.bufferedReader().readLines()
                    // invalid playlist file, hence returning
                    if (lines.size < 2) return

                    val playlistName = lines[1].split(",").reversed().getOrNull(2)
                    // the playlist name can be undefined in some cases, e.g. watch later lists
                    playlist.name = playlistName ?: extractYTPlaylistName(context, uri)
                            ?: TextUtils.getFileSafeTimeStampNow()

                    // start directly at the beginning if header playlist info such as name is missing
                    val startIndex = if (playlistName == null) {
                        1
                    } else {
                        // seek to the first blank line
                        var splitIndex = lines.indexOfFirst { line -> line.isBlank() }
                        while (lines.getOrElse(splitIndex) { return }.isBlank()) splitIndex++
                        // skip the line containing the names of the columns
                        splitIndex + 2
                    }
                    for (line in lines.subList(startIndex, lines.size)) {
                        if (line.isBlank()) continue

                        val videoId = line.split(",")
                            .firstOrNull()
                            ?.takeIf { it.isNotBlank() }

                        if (videoId != null) {
                            playlist.videos += videoId.trim().takeLast(VIDEO_ID_LENGTH)
                        }
                    }
                    importPlaylists.add(playlist)
                }
            }

            ImportFormat.URLSORIDS -> {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val playlist = PipedImportPlaylist(name = TextUtils.getFileSafeTimeStampNow())

                    playlist.videos = inputStream.bufferedReader().readLines()
                        .flatMap { it.split(",") }
                        .mapNotNull { videoUrlOrId ->
                            if (videoUrlOrId.length == VIDEO_ID_LENGTH) {
                                videoUrlOrId
                            } else {
                                TextUtils.getVideoIdFromUri(videoUrlOrId.toUri())
                            }
                        }

                    if (playlist.videos.isNotEmpty()) {
                        importPlaylists.add(playlist)
                    }
                }
            }

        }

        if (importPlaylists.isEmpty()) {
            context.toastFromMainDispatcher(R.string.emptyList)
            return
        }

        try {
            PlaylistsHelper.importPlaylists(importPlaylists)
            context.toastFromMainDispatcher(R.string.success)
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            e.localizedMessage?.let {
                context.toastFromMainDispatcher(it)
            }
        }
    }

    /**
     * Export Playlists
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportPlaylists(
        context: Context,
        uri: Uri,
        importFormat: ImportFormat,
        selectedPlaylistIds: List<String>? = null
    ) {
        val playlists = PlaylistsHelper.getAllPlaylistsWithVideos(selectedPlaylistIds)

        when (importFormat) {
            ImportFormat.PIPED -> {
                val playlistFile = PipedPlaylistFile(playlists = playlists.map {
                    val videos = it.relatedStreams.map { item ->
                        val videoId = item.url!!.toID()
                        val isJioSaavn = app.libre.helpers.JioSaavnHelper.isJioSaavn(videoId, false)
                        if (isJioSaavn) {
                            val cleanId = videoId.removePrefix("jsa_song_")
                            val parts = cleanId.split("_")
                            val token = parts.getOrNull(1) ?: parts[0]
                            "https://www.jiosaavn.com/song/track/$token"
                        } else {
                            "$YOUTUBE_FRONTEND_URL/watch?v=$videoId"
                        }
                    }
                    PipedImportPlaylist(it.name, "playlist", "private", videos)
                })

                context.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(playlistFile, it)
                }
                context.toastFromMainDispatcher(R.string.exportsuccess)
            }



            ImportFormat.URLSORIDS -> {
                val urlListExport = playlists
                    .flatMap { it.relatedStreams }
                    .joinToString("\n") { 
                        val videoId = it.url!!.toID()
                        val isJioSaavn = app.libre.helpers.JioSaavnHelper.isJioSaavn(videoId, false)
                        if (isJioSaavn) {
                            val cleanId = videoId.removePrefix("jsa_song_")
                            val parts = cleanId.split("_")
                            val token = parts.getOrNull(1) ?: parts[0]
                            "https://www.jiosaavn.com/song/track/$token"
                        } else {
                            "$YOUTUBE_FRONTEND_URL/watch?v=$videoId"
                        }
                    }

                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(urlListExport.toByteArray())
                }
                context.toastFromMainDispatcher(R.string.exportsuccess)
            }

            else -> Unit
        }
    }

    private fun extractYTPlaylistName(context: Context, uri: Uri): String? {
        val fileName = DocumentFile.fromSingleUri(context, uri)?.name

        return csvPlaylistNameRegex.find(fileName.orEmpty())?.groupValues?.getOrNull(1)
            ?: fileName?.removeSuffix(".csv")
    }
}
