package app.libre.ui.sheets

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.api.PlaylistsHelper
import app.libre.constants.IntentData
import app.libre.databinding.SheetPlaylistOptionsBinding
import app.libre.db.DatabaseHolder
import app.libre.enums.ImportFormat
import app.libre.enums.PlaylistType
import app.libre.enums.ShareObjectType
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.serializable
import app.libre.extensions.toID
import app.libre.extensions.toastFromMainDispatcher
import app.libre.helpers.BackgroundHelper
import app.libre.helpers.ContextHelper
import app.libre.helpers.DuplicateAudioMatcher
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.obj.ShareData
import app.libre.parcelable.PlayerData
import app.libre.ui.activities.MainActivity
import app.libre.ui.base.BaseActivity
import app.libre.ui.dialogs.CleanDuplicatesDialog
import app.libre.ui.dialogs.DeletePlaylistDialog
import app.libre.ui.dialogs.RenamePlaylistDialog
import app.libre.helpers.ShareHelper
import app.libre.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Redesigned Material 3 bottom sheet for Playlist Options matching the sleek design of VideoOptionsBottomSheet.
 */
class PlaylistOptionsBottomSheet : ExpandedBottomSheet(R.layout.sheet_playlist_options) {
    private lateinit var playlistName: String
    private lateinit var playlistId: String
    private lateinit var playlistType: PlaylistType

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            playlistName = it.getString(IntentData.playlistName).orEmpty()
            playlistId = it.getString(IntentData.playlistId).orEmpty()
            playlistType = it.serializable(IntentData.playlistType) ?: PlaylistType.PUBLIC
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = SheetPlaylistOptionsBinding.bind(view)
        val context = requireContext()
        val mFragmentManager = (context as BaseActivity).supportFragmentManager

        // 1. Header Information
        binding.sheetTitle.text = playlistName
        val isLocal = playlistType != PlaylistType.PUBLIC
        binding.sheetTypeBadge.text = if (isLocal) "Local Playlist" else "Public Playlist"
        binding.sheetThumbnail.setImageResource(R.drawable.ic_playlist)

        // Load thumbnail and count asynchronously
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val playlist = runCatching { PlaylistsHelper.getPlaylist(playlistId) }.getOrNull()
            val thumbUrl = playlist?.thumbnailUrl
            val count = playlist?.videos ?: playlist?.relatedStreams?.size ?: 0
            val formattedCount = if (count == 1) "1 song" else "${java.text.NumberFormat.getNumberInstance().format(count)} songs"

            val isBookmarked = DatabaseHolder.Database.playlistBookmarkDao().includes(playlistId)

            withContext(Dispatchers.Main) {
                binding.sheetSubtitle.text = formattedCount
                if (!thumbUrl.isNullOrEmpty()) {
                    ImageHelper.loadImage(thumbUrl, binding.sheetThumbnail)
                }

                if (isBookmarked) {
                    binding.iconBookmark.setImageResource(R.drawable.ic_bookmark_outlined)
                    binding.textBookmark.setText(R.string.remove_bookmark)
                } else {
                    binding.iconBookmark.setImageResource(R.drawable.ic_bookmark)
                    binding.textBookmark.setText(R.string.add_to_bookmarks)
                }
            }
        }

        // 2. Action Visibility
        binding.actionCleanDuplicates.isVisible = isLocal
        binding.actionRename.isVisible = isLocal
        binding.actionExport.isVisible = isLocal
        binding.actionDelete.isVisible = isLocal

        binding.actionShare.isVisible = !isLocal
        binding.actionClone.isVisible = !isLocal
        binding.actionBookmark.isVisible = !isLocal

        // 3. Touch Feedback
        binding.actionAddToQueue.addSpringTouchFeedback()
        binding.actionPlayBackground.addSpringTouchFeedback()
        binding.actionCleanDuplicates.addSpringTouchFeedback()
        binding.actionRename.addSpringTouchFeedback()
        binding.actionExport.addSpringTouchFeedback()
        binding.actionDelete.addSpringTouchFeedback()
        binding.actionShare.addSpringTouchFeedback()
        binding.actionClone.addSpringTouchFeedback()
        binding.actionBookmark.addSpringTouchFeedback()

        // 4. Action Click Listeners

        val actScope = (context as BaseActivity).lifecycleScope

        // Add to Queue
        binding.actionAddToQueue.setOnClickListener {
            dismiss()
            actScope.launch(Dispatchers.IO) {
                val playlist = runCatching { PlaylistsHelper.getPlaylist(playlistId) }.getOrNull()
                val streams = playlist?.relatedStreams.orEmpty()
                if (streams.isNotEmpty()) {
                    val firstStream = streams.first()
                    val firstVideoId = firstStream.url?.toID().orEmpty()

                    withContext(Dispatchers.Main) {
                        if (PlayingQueue.getCurrent() == null) {
                            PlayingQueue.setStreams(streams)
                            NavigationHelper.navigateVideo(
                                context,
                                playerData = PlayerData(
                                    videoId = firstVideoId,
                                    playlistId = playlistId,
                                    keepQueue = true
                                )
                            )
                        } else {
                            PlayingQueue.insertPlaylist(playlistId, null)
                        }
                        Toast.makeText(context, R.string.added_to_queue, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Play in Background
        binding.actionPlayBackground.setOnClickListener {
            dismiss()
            actScope.launch(Dispatchers.IO) {
                val playlist = runCatching { PlaylistsHelper.getPlaylist(playlistId) }.getOrNull()
                val firstStream = playlist?.relatedStreams?.firstOrNull()
                if (firstStream != null) {
                    withContext(Dispatchers.Main) {
                        BackgroundHelper.playOnBackground(
                            context,
                            PlayerData(firstStream.url!!.toID(), playlistId = playlistId)
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Clean Duplicates
        binding.actionCleanDuplicates.setOnClickListener {
            dismiss()
            actScope.launch(Dispatchers.IO) {
                val playlist = runCatching { PlaylistsHelper.getPlaylist(playlistId) }.getOrNull() ?: return@launch
                val duplicateGroups = DuplicateAudioMatcher.findDuplicates(playlist.relatedStreams)
                withContext(Dispatchers.Main) {
                    if (duplicateGroups.isEmpty()) {
                        Toast.makeText(context, R.string.no_duplicates_found, Toast.LENGTH_SHORT).show()
                    } else {
                        CleanDuplicatesDialog(duplicateGroups) { indicesToRemove ->
                            actScope.launch(Dispatchers.IO) {
                                for (idx in indicesToRemove) {
                                    PlaylistsHelper.removeFromPlaylist(playlistId, idx)
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.duplicates_removed_success, indicesToRemove.size),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    mFragmentManager.setFragmentResult("playlist_reload_key", Bundle.EMPTY)
                                }
                            }
                        }.show(mFragmentManager, CleanDuplicatesDialog::class.java.name)
                    }
                }
            }
        }

        // Rename Playlist
        binding.actionRename.setOnClickListener {
            dismiss()
            val renameDialog = RenamePlaylistDialog()
            renameDialog.arguments = Bundle().apply {
                putString(IntentData.playlistId, playlistId)
                putString(IntentData.playlistName, playlistName)
            }
            renameDialog.show(mFragmentManager, null)
        }

        // Export Playlist
        binding.actionExport.setOnClickListener {
            dismiss()
            val formats = arrayOf("JSON (.json)", "URLs / IDs (.txt)")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.export_playlist)
                .setItems(formats) { _, which ->
                    val format = if (which == 0) ImportFormat.PIPED else ImportFormat.URLSORIDS
                    ContextHelper.unwrapActivity<MainActivity>(context)
                        .startPlaylistExport(playlistId, playlistName, format, false)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Delete Playlist
        binding.actionDelete.setOnClickListener {
            dismiss()
            val deleteDialog = DeletePlaylistDialog()
            deleteDialog.arguments = Bundle().apply {
                putString(IntentData.playlistId, playlistId)
            }
            deleteDialog.show(mFragmentManager, null)
        }

        // Share Playlist
        binding.actionShare.setOnClickListener {
            dismiss()
            ShareHelper.share(
                context = requireContext(),
                id = playlistId,
                title = playlistName,
                shareObjectType = ShareObjectType.PLAYLIST
            )
        }

        // Clone Playlist
        binding.actionClone.setOnClickListener {
            dismiss()
            Toast.makeText(context, "Cloning playlist...", Toast.LENGTH_SHORT).show()
            actScope.launch(Dispatchers.IO) {
                val clonedId = runCatching { PlaylistsHelper.clonePlaylist(playlistId) }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (clonedId != null) {
                        Toast.makeText(context, R.string.playlistCloned, Toast.LENGTH_SHORT).show()
                        mFragmentManager.setFragmentResult("playlist_reload_key", Bundle.EMPTY)
                    } else {
                        Toast.makeText(context, R.string.server_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Bookmark Toggle
        binding.actionBookmark.setOnClickListener {
            dismiss()
            actScope.launch(Dispatchers.IO) {
                val isBookmarked = DatabaseHolder.Database.playlistBookmarkDao().includes(playlistId)
                if (isBookmarked) {
                    DatabaseHolder.Database.playlistBookmarkDao().deleteById(playlistId)
                } else {
                    val bookmark = try {
                        PlaylistsHelper.getPlaylist(playlistId)
                    } catch (e: Exception) {
                        return@launch
                    }.toPlaylistBookmark(playlistId)
                    DatabaseHolder.Database.playlistBookmarkDao().insert(bookmark)
                }
                withContext(Dispatchers.Main) {
                    mFragmentManager.setFragmentResult("playlist_reload_key", Bundle.EMPTY)
                }
            }
        }
    }

    companion object {
        const val PLAYLIST_OPTIONS_REQUEST_KEY = "playlist_options_request_key"
    }
}
