package app.libre.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ListAdapter
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.databinding.PlaylistTrackRowBinding
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.toID
import app.libre.helpers.ImageHelper
import app.libre.helpers.ThemeHelper
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.base.BaseActivity
import app.libre.ui.extensions.setFormattedDuration
import app.libre.ui.extensions.setWatchProgressLength
import app.libre.ui.sheets.VideoOptionsBottomSheet
import app.libre.ui.sheets.VideoOptionsBottomSheet.Companion.VIDEO_OPTIONS_SHEET_REQUEST_KEY
import app.libre.ui.viewholders.PlaylistViewHolder
import app.libre.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistItem(
    val item: StreamItem,
    /**
     * The original index of the playlist item before sorting the feed.
     */
    val originalPlaylistIndex: Int,
)

class PlaylistAdapter(
    private val playlistId: String,
    private val isLocalPlaylist: Boolean = true,
    private val onVideoClick: (StreamItem) -> Unit
) : ListAdapter<PlaylistItem, PlaylistViewHolder>(DiffUtilItemCallback(
    areItemsTheSame = { old, new -> old.item.url == new.item.url },
    areContentsTheSame = { old, new -> old.item == new.item }
)) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = PlaylistTrackRowBinding.inflate(layoutInflater, parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlistItem = getItem(holder.bindingAdapterPosition)
        val streamItem = playlistItem.item
        val videoId = streamItem.url.orEmpty().toID()

        val context = holder.binding.root.context
        val activity = (context as BaseActivity)
        val fragmentManager = activity.supportFragmentManager

        with(holder.binding) {
            val isCurrent = PlayingQueue.getCurrent()?.url?.toID() == videoId
            if (isCurrent) {
                val primaryColor = ThemeHelper.getThemeColor(context, androidx.appcompat.R.attr.colorPrimary)
                videoTitle.setTextColor(primaryColor)
                videoTitle.text = "▶  " + streamItem.title
            } else {
                val defaultTextColor = ThemeHelper.getThemeColor(context, android.R.attr.textColorPrimary)
                videoTitle.setTextColor(defaultTextColor)
                videoTitle.text = streamItem.title
            }
            channelName.text = streamItem.uploaderName.orEmpty().ifEmpty { streamItem.albumName.orEmpty() }

            streamItem.duration?.let {
                thumbnailDuration.setFormattedDuration(it, streamItem.isShort, streamItem.uploaded)
            }
            ImageHelper.loadImage(streamItem.thumbnail, thumbnail)

            root.addSpringTouchFeedback(0.96f)

            root.setOnClickListener {
                onVideoClick(streamItem)
            }

            fun showTrackOptions() {
                fragmentManager.setFragmentResultListener(
                    VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
                VideoOptionsBottomSheet().apply {
                    arguments = android.os.Bundle().apply {
                        putParcelable(IntentData.streamItem, streamItem)
                        putString(IntentData.playlistId, playlistId)
                    }
                }.show(fragmentManager, VideoOptionsBottomSheet::class.java.name)
            }

            optionsMenu.setOnClickListener {
                showTrackOptions()
            }

            root.setOnLongClickListener {
                showTrackOptions()
                true
            }

            streamItem.duration?.let { watchProgress.setWatchProgressLength(videoId, it) }

            if (isLocalPlaylist) {
                // Song is already in this local playlist — no need to show badge
                downloadBadge.clearColorFilter()
                downloadBadge.isGone = true
            } else {
                // For album/public playlists, show badge if song is in any local playlist
                val currentVideoId = videoId
                root.tag = currentVideoId
                activity.lifecycleScope.launch {
                    val isInPlaylist = withContext(Dispatchers.IO) {
                        app.libre.db.DatabaseHolder.Database.localPlaylistsDao()
                            .isVideoInAnyPlaylist(currentVideoId)
                    }
                    if (root.tag == currentVideoId) {
                        if (isInPlaylist) {
                            downloadBadge.setImageResource(app.libre.R.drawable.ic_bookmark)
                            downloadBadge.setColorFilter(
                                app.libre.helpers.ThemeHelper.getThemeColor(
                                    activity, androidx.appcompat.R.attr.colorPrimary
                                )
                            )
                            downloadBadge.isVisible = true
                        } else {
                            downloadBadge.clearColorFilter()
                            downloadBadge.isGone = true
                        }
                    }
                }
            }
        }
    }
}
