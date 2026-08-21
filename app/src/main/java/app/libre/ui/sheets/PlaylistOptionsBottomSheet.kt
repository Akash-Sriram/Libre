package app.libre.ui.sheets

import android.os.Bundle
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.api.PlaylistsHelper
import app.libre.constants.IntentData
import app.libre.db.DatabaseHolder
import app.libre.enums.ImportFormat
import app.libre.enums.PlaylistType
import app.libre.enums.ShareObjectType
import app.libre.extensions.serializable
import app.libre.extensions.toID
import app.libre.extensions.toastFromMainDispatcher
import app.libre.helpers.BackgroundHelper
import app.libre.helpers.ContextHelper
import app.libre.obj.ShareData
import app.libre.parcelable.PlayerData
import app.libre.ui.activities.MainActivity
import app.libre.ui.base.BaseActivity
import app.libre.ui.dialogs.DeletePlaylistDialog
import app.libre.ui.dialogs.RenamePlaylistDialog
import app.libre.ui.dialogs.ShareDialog
import app.libre.ui.preferences.BackupRestoreSettings
import app.libre.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlaylistOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var playlistName: String
    private lateinit var playlistId: String
    private lateinit var playlistType: PlaylistType

    private var exportFormat: ImportFormat = ImportFormat.PIPED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            playlistName = it.getString(IntentData.playlistName)!!
            playlistId = it.getString(IntentData.playlistId)!!
            playlistType = it.serializable(IntentData.playlistType)!!
        }

        setTitle(playlistName)

        // options for the dialog
        val optionsList = mutableListOf<Int>()

        if (PlayingQueue.isNotEmpty()) optionsList.add(R.string.add_to_queue)

        val isBookmarked = runBlocking(Dispatchers.IO) {
            DatabaseHolder.Database.playlistBookmarkDao().includes(playlistId)
        }

        if (playlistType == PlaylistType.PUBLIC) {
            optionsList.add(R.string.share)
            optionsList.add(R.string.clonePlaylist)

            // only add the bookmark option to the playlist if public
            optionsList.add(
                if (isBookmarked) R.string.remove_bookmark else R.string.add_to_bookmarks
            )
        } else {
            optionsList.add(R.string.renamePlaylist)
            optionsList.add(R.string.export_playlist)
            optionsList.add(R.string.deletePlaylist)
        }

        setSimpleItems(optionsList.map { getString(it) }) { which ->
            val mFragmentManager = (context as BaseActivity).supportFragmentManager

            when (optionsList[which]) {
                // play the playlist in the background
                R.string.playOnBackground -> {
                    val playlist = withContext(Dispatchers.IO) {
                        runCatching { PlaylistsHelper.getPlaylist(playlistId) }
                    }.getOrElse {
                        context?.toastFromMainDispatcher(R.string.error)
                        return@setSimpleItems
                    }

                    playlist.relatedStreams.firstOrNull()?.let {
                        BackgroundHelper.playOnBackground(
                            requireContext(),
                            PlayerData(it.url!!.toID(), playlistId = playlistId)
                        )
                    }
                }

                R.string.add_to_queue -> {
                    PlayingQueue.insertPlaylist(playlistId, null)
                }
                // Clone the playlist to the users Piped account
                R.string.clonePlaylist -> {
                    val context = requireContext()
                    val playlistId = withContext(Dispatchers.IO) {
                        runCatching {
                            PlaylistsHelper.clonePlaylist(playlistId)
                        }.getOrNull()
                    }
                    context.toastFromMainDispatcher(
                        if (playlistId != null) R.string.playlistCloned else R.string.server_error
                    )
                }
                // share the playlist
                R.string.share -> {
                    val newShareDialog = ShareDialog()
                    newShareDialog.arguments = android.os.Bundle().apply {
                        putString(IntentData.id, playlistId)
                        putSerializable(IntentData.shareObjectType, ShareObjectType.PLAYLIST)
                        putParcelable(IntentData.shareData, ShareData(currentPlaylist = playlistName))
                    }
                    // using parentFragmentManager, childFragmentManager doesn't work here
                    newShareDialog.show(parentFragmentManager, ShareDialog::class.java.name)
                }

                R.string.deletePlaylist -> {
                    val newDeletePlaylistDialog = DeletePlaylistDialog()
                    newDeletePlaylistDialog.arguments = android.os.Bundle().apply {
                        putString(IntentData.playlistId, playlistId)
                    }
                    newDeletePlaylistDialog.show(mFragmentManager, null)
                }

                R.string.renamePlaylist -> {
                    val newRenamePlaylistDialog = RenamePlaylistDialog()
                    newRenamePlaylistDialog.arguments = android.os.Bundle().apply {
                        putString(IntentData.playlistId, playlistId)
                        putString(IntentData.playlistName, playlistName)
                    }
                    newRenamePlaylistDialog.show(mFragmentManager, null)
                }



                R.string.export_playlist -> {
                    val context = requireContext()

                    BackupRestoreSettings.createImportFormatDialog(
                        context,
                        R.string.export_playlist,
                        BackupRestoreSettings.exportPlaylistFormatList + listOf(ImportFormat.URLSORIDS)
                    ) { format, includeTimestamp ->
                        exportFormat = format
                        ContextHelper.unwrapActivity<MainActivity>(context)
                            .startPlaylistExport(playlistId, playlistName, exportFormat, includeTimestamp)
                    }
                }

                else -> {
                    withContext(Dispatchers.IO) {
                        if (isBookmarked) {
                            DatabaseHolder.Database.playlistBookmarkDao().deleteById(playlistId)
                        } else {
                            val bookmark = try {
                                MediaServiceRepository.instance.getPlaylist(playlistId)
                            } catch (e: Exception) {
                                return@withContext
                            }.toPlaylistBookmark(playlistId)
                            DatabaseHolder.Database.playlistBookmarkDao().insert(bookmark)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val PLAYLIST_OPTIONS_REQUEST_KEY = "playlist_options_request_key"
    }
}
