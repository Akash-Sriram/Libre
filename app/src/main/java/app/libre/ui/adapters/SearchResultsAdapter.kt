package app.libre.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import app.libre.R
import app.libre.api.JsonHelper
import app.libre.api.obj.ContentItem
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.databinding.ChannelRowBinding
import app.libre.databinding.PlaylistTrackRowBinding
import app.libre.databinding.PlaylistsRowBinding
import app.libre.databinding.VideoRowBinding
import app.libre.enums.PlaylistType
import app.libre.extensions.dpToPx
import app.libre.extensions.formatShort
import app.libre.extensions.toID
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.parcelable.PlayerData
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.base.BaseActivity
import app.libre.ui.extensions.setFormattedDuration
import app.libre.ui.extensions.setWatchProgressLength
import app.libre.ui.viewholders.SearchViewHolder
import app.libre.ui.sheets.ChannelOptionsBottomSheet
import app.libre.ui.sheets.PlaylistOptionsBottomSheet
import app.libre.ui.sheets.VideoOptionsBottomSheet
import app.libre.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class SearchResultsAdapter(
    private val timeStamp: Long = 0
) : PagingDataAdapter<ContentItem, SearchViewHolder>(
    DiffUtilItemCallback(
        areItemsTheSame = { oldItem, newItem -> oldItem.url == newItem.url },
        areContentsTheSame = { oldItem, newItem -> oldItem == newItem },
    )
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            0 -> SearchViewHolder(
                PlaylistTrackRowBinding.inflate(layoutInflater, parent, false)
            )

            1 -> SearchViewHolder(
                ChannelRowBinding.inflate(layoutInflater, parent, false)
            )

            2 -> SearchViewHolder(
                PlaylistsRowBinding.inflate(layoutInflater, parent, false)
            )

            else -> throw IllegalArgumentException("Invalid type")
        }
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        val searchItem = getItem(position)!!

        val playlistTrackRowBinding = holder.playlistTrackRowBinding
        val videoRowBinding = holder.videoRowBinding
        val channelRowBinding = holder.channelRowBinding
        val playlistRowBinding = holder.playlistRowBinding

        if (playlistTrackRowBinding != null) {
            bindTrack(searchItem, playlistTrackRowBinding, position)
        } else if (videoRowBinding != null) {
            bindVideo(searchItem, videoRowBinding, position)
        } else if (channelRowBinding != null) {
            bindChannel(searchItem, channelRowBinding)
        } else if (playlistRowBinding != null) {
            bindPlaylist(searchItem, playlistRowBinding)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)?.type) {
            StreamItem.TYPE_STREAM -> 0
            StreamItem.TYPE_CHANNEL -> 1
            StreamItem.TYPE_PLAYLIST -> 2
            else -> 3
        }
    }

    private fun bindTrack(item: ContentItem, binding: PlaylistTrackRowBinding, position: Int) {
        binding.apply {
            val isMusicTrack = item.url.startsWith("jsa_") || 
                               item.url.contains("music.youtube.com") || 
                               item.url.contains("/album/") ||
                               item.isShort == true

            // Uniform 56x56dp Squircle Layout for all rows (Spotify / Apple Music design standard)
            val layoutParams = thumbnailCard.layoutParams
            layoutParams.width = 56f.dpToPx()
            layoutParams.height = 56f.dpToPx()
            thumbnailCard.layoutParams = layoutParams

            ImageHelper.loadImage(item.thumbnail, thumbnail)

            thumbnailDuration.setFormattedDuration(item.duration, item.isShort, item.uploaded)
            videoTitle.text = item.title

            val isJioSaavn = item.source == "jiosaavn" || item.url.startsWith("jsa_")
            val isYtMusic = item.source == "ytm" || (!isJioSaavn && (item.url.contains("music.youtube.com") || item.url.contains("/album/")))
            val sourceText = if (isJioSaavn) "JioSaavn" else if (isYtMusic) "YT Music" else "YouTube"
            sourceBadge.text = sourceText
            val (badgeColor, badgeBg) = when {
                isJioSaavn -> android.graphics.Color.parseColor("#00B368") to android.graphics.Color.parseColor("#2200B368")
                isYtMusic -> android.graphics.Color.parseColor("#FF4055") to android.graphics.Color.parseColor("#22FF4055")
                else -> android.graphics.Color.parseColor("#E53935") to android.graphics.Color.parseColor("#22E53935")
            }
            sourceBadge.setTextColor(badgeColor)
            sourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(badgeBg)
            sourceBadge.isVisible = true

            val metaText = if (!item.albumName.isNullOrBlank() && !item.uploaderName.isNullOrBlank()) {
                "${item.uploaderName} • ${item.albumName}"
            } else {
                TextUtils.formatViewsString(
                    context = root.context,
                    views = item.views,
                    uploaded = item.uploaded,
                    uploader = item.uploaderName
                ).ifBlank { item.uploaderName.orEmpty() }
            }
            channelName.text = metaText

            root.setOnClickListener {
                NavigationHelper.navigateVideo(root.context, PlayerData(item.url, timestamp = timeStamp))
            }

            val videoId = item.url.toID()
            val activity = (root.context as BaseActivity)
            val fragmentManager = activity.supportFragmentManager

            val openOptions: () -> Unit = {
                fragmentManager.setFragmentResultListener(
                    VideoOptionsBottomSheet.VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(position)
                }
                val sheet = VideoOptionsBottomSheet()
                val contentItemString = JsonHelper.json.encodeToString(item)
                val streamItem: StreamItem = JsonHelper.json.decodeFromString(contentItemString)
                sheet.arguments = android.os.Bundle().apply {
                    putParcelable(IntentData.streamItem, streamItem)
                }
                sheet.show(fragmentManager, SearchResultsAdapter::class.java.name)
            }

            optionsMenu.setOnClickListener { openOptions() }
            root.setOnLongClickListener {
                openOptions()
                true
            }

            watchProgress.setWatchProgressLength(videoId, item.duration)

            val isInPlaylist = app.libre.helpers.LocalPlaylistsCache.isSongInAnyPlaylist(item.toStreamItem())
            if (isInPlaylist) {
                downloadBadge.setImageResource(R.drawable.ic_bookmark)
                downloadBadge.setColorFilter(app.libre.helpers.ThemeHelper.getThemeColor(activity, androidx.appcompat.R.attr.colorPrimary))
                downloadBadge.isVisible = true
            } else {
                downloadBadge.clearColorFilter()
                downloadBadge.isGone = true
            }
        }
    }

    private fun bindVideo(item: ContentItem, binding: VideoRowBinding, position: Int) {
        binding.apply {
            ImageHelper.loadImage(item.thumbnail, thumbnail)

            thumbnailDuration.setFormattedDuration(item.duration, item.isShort, item.uploaded)
            videoTitle.text = item.title

            videoInfo.text = TextUtils.formatViewsString(root.context, item.views, item.uploaded)
            videoInfo.isVisible = !videoInfo.text.isNullOrEmpty()

            channelContainer.isGone = item.uploaderAvatar.isNullOrEmpty()
            channelName.text = item.uploaderName
            ImageHelper.loadImage(item.uploaderAvatar, channelImage, true)

            root.setOnClickListener {
                NavigationHelper.navigateVideo(root.context, PlayerData(item.url, timestamp = timeStamp))
            }

            val videoId = item.url.toID()
            val activity = (root.context as BaseActivity)
            val fragmentManager = activity.supportFragmentManager
            root.setOnLongClickListener {
                fragmentManager.setFragmentResultListener(
                    VideoOptionsBottomSheet.VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(position)
                }
                val sheet = VideoOptionsBottomSheet()
                val contentItemString = JsonHelper.json.encodeToString(item)
                val streamItem: StreamItem = JsonHelper.json.decodeFromString(contentItemString)
                sheet.arguments = android.os.Bundle().apply {
                    putParcelable(IntentData.streamItem, streamItem)
                }
                sheet.show(fragmentManager, SearchResultsAdapter::class.java.name)
                true
            }
            channelContainer.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, item.uploaderUrl)
            }
            watchProgress.setWatchProgressLength(videoId, item.duration)

            val currentVideoId = videoId
            root.tag = currentVideoId
            activity.lifecycleScope.launch {
                val isInPlaylist = withContext(Dispatchers.IO) {
                    app.libre.db.DatabaseHolder.Database.localPlaylistsDao().isVideoInAnyPlaylist(currentVideoId)
                }
                if (root.tag == currentVideoId) {
                    if (isInPlaylist) {
                        downloadBadge.setImageResource(R.drawable.ic_bookmark)
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

    private fun bindChannel(item: ContentItem, binding: ChannelRowBinding) {
        binding.apply {
            ImageHelper.loadImage(item.thumbnail, searchChannelImage, true)
            searchChannelName.text = item.name

            val subscribers = item.subscribers.formatShort()
            searchViews.text = if (item.subscribers >= 0 && item.videos >= 0) {
                root.context.getString(R.string.subscriberAndVideoCounts, subscribers, item.videos)
            } else if (item.subscribers >= 0) {
                root.context.getString(R.string.subscribers, subscribers)
            } else if (item.videos >= 0) {
                root.context.getString(R.string.videoCount, item.videos)
            } else {
                ""
            }

            root.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, item.url)
            }

            binding.searchSubButton.isGone = true

            root.setOnLongClickListener {
                val channelOptionsSheet = ChannelOptionsBottomSheet()
                channelOptionsSheet.arguments = android.os.Bundle().apply {
                    putString(IntentData.channelId, item.url.toID())
                    putString(IntentData.channelName, item.name)
                    putBoolean(IntentData.isSubscribed, false)
                }
                channelOptionsSheet.show((root.context as BaseActivity).supportFragmentManager)
                true
            }
        }
    }

    private fun bindPlaylist(item: ContentItem, binding: PlaylistsRowBinding) {
        binding.apply {
            ImageHelper.loadImage(item.thumbnail, playlistThumbnail)
            playlistTitle.text = item.name
            playlistTitle.maxLines = 2

            // Rich online metadata for Playlists & Albums
            val subtitleParts = mutableListOf<String>()
            val uploader = item.uploaderName
            if (!uploader.isNullOrBlank()) {
                subtitleParts.add(uploader)
            }

            val isMix = item.url.toID().startsWith("RD") || item.name.orEmpty().startsWith("Mix -")
            if (isMix) {
                subtitleParts.add("Dynamic Mix")
            } else if (item.videos > 0) {
                val formattedCount = java.text.NumberFormat.getNumberInstance().format(item.videos)
                subtitleParts.add(if (item.videos == 1L) "1 song" else "$formattedCount songs")
            } else if (item.url.startsWith("jsa_album_")) {
                if (item.uploaded > 0) {
                    subtitleParts.add(item.uploaded.toString())
                }
                subtitleParts.add("Album")
            } else if (item.url.startsWith("jsa_playlist_")) {
                subtitleParts.add("Playlist")
            } else if (!item.shortDescription.isNullOrBlank()) {
                subtitleParts.add(item.shortDescription.orEmpty())
            } else {
                subtitleParts.add("Playlist")
            }

            val isJio = item.source == "jiosaavn" || item.url.startsWith("jsa_")
            val isYtm = item.source == "ytm" || item.url.contains("music.youtube.com") || item.url.contains("/album/") || item.url.toID().startsWith("RD") || item.url.toID().startsWith("MPRE") || item.url.toID().startsWith("OLAK")
            val sourceText = if (isJio) "JioSaavn" else if (isYtm) "YT Music" else "YouTube"
            sourceBadge.text = sourceText
            val (badgeColor, badgeBg) = when {
                isJio -> android.graphics.Color.parseColor("#00B368") to android.graphics.Color.parseColor("#2200B368")
                isYtm -> android.graphics.Color.parseColor("#FF4055") to android.graphics.Color.parseColor("#22FF4055")
                else -> android.graphics.Color.parseColor("#E53935") to android.graphics.Color.parseColor("#22E53935")
            }
            sourceBadge.setTextColor(badgeColor)
            sourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(badgeBg)
            sourceBadge.isVisible = true

            videoCount.text = subtitleParts.joinToString(" • ")
            root.setOnClickListener {
                val playlistId = item.url.toID()
                if (playlistId.startsWith("RD")) {
                    val baseVideoId = playlistId.removePrefix("RD").takeIf { it.length == 11 }
                    NavigationHelper.navigateVideo(
                        root.context,
                        playerData = PlayerData(
                            videoId = baseVideoId,
                            playlistId = playlistId,
                            timestamp = 0L
                        ),
                        audioOnlyPlayerRequested = true
                    )
                } else {
                    NavigationHelper.navigatePlaylist(root.context, item.url, PlaylistType.PUBLIC)
                }
            }

            val showOptions = {
                val sheet = PlaylistOptionsBottomSheet()
                sheet.arguments = android.os.Bundle().apply {
                    putString(IntentData.playlistId, item.url.toID())
                    putString(IntentData.playlistName, item.name.orEmpty())
                    putSerializable(IntentData.playlistType, PlaylistType.PUBLIC)
                }
                sheet.show(
                    (root.context as BaseActivity).supportFragmentManager,
                    PlaylistOptionsBottomSheet::class.java.name
                )
            }

            optionsMenu.setOnClickListener {
                showOptions()
            }

            root.setOnLongClickListener {
                showOptions()
                true
            }
        }
    }
}
