package app.libre.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import app.libre.R
import app.libre.constants.IntentData
import app.libre.databinding.PlaylistsRowBinding
import app.libre.db.DatabaseHolder
import app.libre.db.obj.PlaylistBookmark
import app.libre.enums.PlaylistType
import app.libre.extensions.addSpringTouchFeedback
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.base.BaseActivity
import app.libre.ui.sheets.PlaylistOptionsBottomSheet
import app.libre.ui.viewholders.PlaylistBookmarkViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaylistBookmarkAdapter: ListAdapter<PlaylistBookmark, PlaylistBookmarkViewHolder>(
    DiffUtilItemCallback(
        areItemsTheSame = { oldItem, newItem -> oldItem.playlistId == newItem.playlistId }
    )
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistBookmarkViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return PlaylistBookmarkViewHolder(
            PlaylistsRowBinding.inflate(layoutInflater, parent, false)
        )
    }

    private fun showPlaylistOptions(context: Context, bookmark: PlaylistBookmark) {
        val sheet = PlaylistOptionsBottomSheet()
        sheet.arguments = android.os.Bundle().apply {
            putString(IntentData.playlistId, bookmark.playlistId)
            putString(IntentData.playlistName, bookmark.playlistName)
            putSerializable(IntentData.playlistType, PlaylistType.PUBLIC)
        }
        sheet.show(
            (context as BaseActivity).supportFragmentManager
        )
    }

    override fun onBindViewHolder(holder: PlaylistBookmarkViewHolder, position: Int) {
        val bookmark = getItem(holder.bindingAdapterPosition)

        with(holder.binding) {
            ImageHelper.loadImage(bookmark.thumbnailUrl, playlistThumbnail)
            playlistTitle.text = bookmark.playlistName
            playlistTitle.maxLines = 2

            // Rich 2-line structure with online metadata
            val uploaderName = bookmark.uploader
            val playlistName = bookmark.playlistName.orEmpty()
            val playlistId = bookmark.playlistId
            val subtitleParts = mutableListOf<String>()

            if (!uploaderName.isNullOrEmpty()) {
                subtitleParts.add(uploaderName)
            }
            if (playlistId.startsWith("RD") || playlistName.startsWith("Mix -")) {
                subtitleParts.add("Dynamic Mix")
            } else if (bookmark.videos > 0) {
                val formattedCount = java.text.NumberFormat.getNumberInstance().format(bookmark.videos)
                subtitleParts.add(if (bookmark.videos == 1) "1 song" else "$formattedCount songs")
            } else {
                subtitleParts.add("Playlist")
            }

            videoCount.text = subtitleParts.joinToString(" • ")

            root.addSpringTouchFeedback(0.96f)

            optionsMenu.setOnClickListener {
                showPlaylistOptions(root.context, bookmark)
            }

            root.setOnClickListener {
                NavigationHelper.navigatePlaylist(
                    root.context,
                    bookmark.playlistId,
                    PlaylistType.PUBLIC
                )
            }

            root.setOnLongClickListener {
                showPlaylistOptions(root.context, bookmark)
                true
            }
        }
    }
}
