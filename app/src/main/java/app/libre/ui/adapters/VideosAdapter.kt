package app.libre.ui.adapters

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ListAdapter
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.databinding.PlaylistTrackRowBinding
import app.libre.extensions.dpToPx
import app.libre.extensions.toID
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.parcelable.PlayerData
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.base.BaseActivity
import app.libre.ui.extensions.setFormattedDuration
import app.libre.ui.extensions.setWatchProgressLength
import app.libre.ui.sheets.VideoOptionsBottomSheet
import app.libre.ui.viewholders.VideosViewHolder
import app.libre.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideosAdapter(
    private val showChannelInfo: Boolean = true
) : ListAdapter<StreamItem, VideosViewHolder>(DiffUtilItemCallback()) {

    fun insertItems(newItems: List<StreamItem>) {
        val updatedList = currentList.toMutableList().also {
            it.addAll(newItems)
        }

        submitList(updatedList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideosViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = PlaylistTrackRowBinding.inflate(layoutInflater, parent, false)
        return VideosViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VideosViewHolder, position: Int) {
        val video = getItem(holder.bindingAdapterPosition)
        val videoId = video.url.orEmpty().toID()

        val context = holder.binding.root.context
        val activity = (context as BaseActivity)
        val fragmentManager = activity.supportFragmentManager

        with(holder.binding) {
            val isMusicTrack = video.url?.startsWith("jsa_") == true || 
                               video.url?.contains("music.youtube.com") == true || 
                               video.url?.contains("/album/") == true ||
                               video.isShort

            // Option 2 (High-Clarity Showcase): 72x72dp for music tracks, 110x62dp (16:9) for standard videos
            val layoutParams = thumbnailCard.layoutParams
            if (isMusicTrack) {
                layoutParams.width = 72f.dpToPx()
                layoutParams.height = 72f.dpToPx()
            } else {
                layoutParams.width = 110f.dpToPx()
                layoutParams.height = 62f.dpToPx()
            }
            thumbnailCard.layoutParams = layoutParams

            videoTitle.text = video.title

            val metaText = TextUtils.formatViewsString(
                context = root.context,
                views = video.views ?: -1,
                uploaded = video.uploaded,
                uploader = if (showChannelInfo) video.uploaderName else null
            )
            channelName.text = metaText.ifBlank { if (showChannelInfo) video.uploaderName.orEmpty() else "" }

            video.duration?.let { thumbnailDuration.setFormattedDuration(it, video.isShort, video.uploaded) }
            watchProgress.setWatchProgressLength(videoId, video.duration ?: 0L)
            ImageHelper.loadImage(video.thumbnail, thumbnail)

            root.setOnClickListener {
                NavigationHelper.navigateVideo(root.context, PlayerData(videoId))
            }

            val openOptions: () -> Unit = {
                fragmentManager.setFragmentResultListener(
                    VideoOptionsBottomSheet.VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
                val sheet = VideoOptionsBottomSheet()
                sheet.arguments = Bundle().apply { putParcelable(IntentData.streamItem, video) }
                sheet.show(fragmentManager, VideosAdapter::class.java.name)
            }

            optionsMenu.setOnClickListener { openOptions() }
            root.setOnLongClickListener {
                openOptions()
                true
            }

            val currentVideoId = videoId
            root.tag = currentVideoId
            activity.lifecycleScope.launch {
                val isInPlaylist = withContext(Dispatchers.IO) {
                    app.libre.db.DatabaseHolder.Database.localPlaylistsDao().isVideoInAnyPlaylist(currentVideoId)
                }
                if (root.tag == currentVideoId) {
                    if (isInPlaylist) {
                        downloadBadge.setImageResource(app.libre.R.drawable.ic_bookmark)
                        downloadBadge.setColorFilter(app.libre.helpers.ThemeHelper.getThemeColor(activity, androidx.appcompat.R.attr.colorPrimary))
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
