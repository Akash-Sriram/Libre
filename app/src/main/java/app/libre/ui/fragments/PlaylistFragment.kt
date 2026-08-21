package app.libre.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.core.text.parseAsHtml
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.api.PlaylistsHelper
import app.libre.api.obj.Playlist
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.constants.PreferenceKeys
import app.libre.databinding.FragmentPlaylistBinding
import app.libre.db.DatabaseHolder
import app.libre.enums.PlaylistType
import app.libre.extensions.TAG
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.ceilHalf
import app.libre.extensions.dpToPx
import app.libre.extensions.setOnDismissListener
import app.libre.extensions.toID
import app.libre.extensions.toastFromMainDispatcher
import app.libre.extensions.toastFromMainThread
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.helpers.PreferenceHelper
import app.libre.helpers.ThemeHelper
import app.libre.parcelable.PlayerData
import app.libre.ui.adapters.PlaylistAdapter
import app.libre.ui.adapters.PlaylistItem
import app.libre.ui.base.BaseActivity
import app.libre.ui.base.DynamicLayoutManagerFragment
import app.libre.ui.extensions.addOnBottomReachedListener
import app.libre.ui.models.CommonPlayerViewModel
import app.libre.ui.models.PlaylistViewModel
import app.libre.ui.sheets.BaseBottomSheet
import app.libre.ui.sheets.PlaylistOptionsBottomSheet
import app.libre.util.PlayingQueue
import app.libre.util.TextUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.timeago.patterns.it

class PlaylistFragment : DynamicLayoutManagerFragment(R.layout.fragment_playlist) {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<PlaylistFragmentArgs>()

    // general playlist information
    private lateinit var playlistId: String
    private var playlistName: String? = null
    private var playlistType = PlaylistType.PUBLIC

    // runtime variables
    private var playlistFeed = mutableListOf<StreamItem>()
    private var playlistAdapter: PlaylistAdapter? = null
    private var nextPage: String? = null
    private var isLoading = true
    private var isBookmarked = false
    private var headerTopColor: Int? = null
    private var headerBgColor: Int? = null

    // view models
    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val playlistViewModel: PlaylistViewModel by activityViewModels()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.PLAYLIST_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.PLAYLIST_SORT_ORDER, value)
            field = value
        }
    private val sortOptions by lazy { resources.getStringArray(R.array.playlistSortOptions) }
    private var recyclerViewState: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = args.playlistId
        playlistType = args.playlistType

        sharedElementEnterTransition = com.google.android.material.transition.MaterialContainerTransform().apply {
            duration = 320L
            interpolator = androidx.core.view.animation.PathInterpolatorCompat.create(0.1f, 0.9f, 0.2f, 1.0f)
            fadeMode = com.google.android.material.transition.MaterialContainerTransform.FADE_MODE_THROUGH
            fitMode = com.google.android.material.transition.MaterialContainerTransform.FIT_MODE_AUTO
            scrimColor = android.graphics.Color.TRANSPARENT
        }
        sharedElementReturnTransition = com.google.android.material.transition.MaterialContainerTransform().apply {
            duration = 280L
            interpolator = androidx.core.view.animation.PathInterpolatorCompat.create(0.2f, 0.0f, 0.0f, 1.0f)
            fadeMode = com.google.android.material.transition.MaterialContainerTransform.FADE_MODE_THROUGH
            fitMode = com.google.android.material.transition.MaterialContainerTransform.FIT_MODE_AUTO
            scrimColor = android.graphics.Color.TRANSPARENT
        }
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.playlistRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlaylistBinding.bind(view)
        binding.thumbnailCard.transitionName = "playlist_transition_$playlistId"
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        binding.playlistProgress.isVisible = true

        isBookmarked = runBlocking(Dispatchers.IO) {
            DatabaseHolder.Database.playlistBookmarkDao().includes(playlistId)
        }
        updateBookmarkRes()

        binding.playAll.addSpringTouchFeedback()
        binding.shuffleBTN.addSpringTouchFeedback()
        binding.bookmark.addSpringTouchFeedback()
        binding.sortBTN.addSpringTouchFeedback()
        binding.optionsMenu.addSpringTouchFeedback()

        binding.playlistAppBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val topC = headerTopColor ?: return@addOnOffsetChangedListener
            val bgC = headerBgColor ?: return@addOnOffsetChangedListener
            val totalRange = appBarLayout.totalScrollRange.toFloat()
            if (totalRange <= 0f) return@addOnOffsetChangedListener
            val ratio = (Math.abs(verticalOffset) / totalRange).coerceIn(0f, 1f)

            // Smoothly blend toolbar color from topColor (expanded) to bgColor (collapsed)
            val currentColor = androidx.core.graphics.ColorUtils.blendARGB(topC, bgC, ratio)
            val mainAct = activity as? app.libre.ui.activities.MainActivity
            mainAct?.findViewById<View>(R.id.appBarLayout)?.setBackgroundColor(currentColor)
            mainAct?.findViewById<View>(R.id.toolbar)?.setBackgroundColor(currentColor)

            // Show collapsed playlist title when scrolled up
            if (ratio > 0.7f) {
                mainAct?.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)?.title = playlistName.orEmpty()
            } else {
                mainAct?.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)?.title = ""
            }
        }

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            binding.playlistRecView.updatePadding(bottom = if (it) 78f.dpToPx() else 0)
        }

        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        fetchPlaylist()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val mainAct = activity as? app.libre.ui.activities.MainActivity
        mainAct?.findViewById<View>(R.id.appBarLayout)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        mainAct?.findViewById<View>(R.id.toolbar)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        mainAct?.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)?.title = ""
        _binding = null
    }

    private fun updateBookmarkRes() {
        binding.bookmark.setIconResource(
            if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
        )
    }

    private fun fetchPlaylist() {
        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    PlaylistsHelper.getPlaylist(playlistId)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                val currentBinding = _binding ?: return@launch
                currentBinding.playlistProgress.isGone = true
                currentBinding.nothingHere.isVisible = true
                context?.let { ctx ->
                    ctx.toastFromMainThread(R.string.unknown_error)
                }
                return@launch
            }
            val binding = _binding ?: return@launch

            playlistFeed = response.relatedStreams.toMutableList()
            nextPage = response.nextpage
            playlistName = response.name
            isLoading = false

            setPlaylistThumbnail(response.thumbnailUrl)

            binding.playlistProgress.isGone = true
            binding.playlistRecView.isVisible = true
            binding.playlistName.text = response.name

            binding.playlistInfo.text = getChannelAndVideoString(response, response.videos)
            binding.playlistInfo.setOnClickListener {
                NavigationHelper.navigateChannel(requireContext(), response.uploaderUrl)
            }

            playlistAdapter = PlaylistAdapter(playlistId, playlistType == PlaylistType.LOCAL) { streamItem ->
                startVideoItemPlayback(streamItem)
            }
            binding.playlistRecView.adapter = playlistAdapter

            // listen for playlist items to become deleted
            playlistAdapter!!.registerAdapterDataObserver(object :
                RecyclerView.AdapterDataObserver() {
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    if (positionStart == 0) {
                        ImageHelper.loadImage(
                            playlistFeed.firstOrNull()?.thumbnail.orEmpty(),
                            binding.thumbnail
                        )
                    }

                    binding.playlistInfo.text =
                        getChannelAndVideoString(response, playlistFeed.size)
                }
            })

            binding.playlistRecView.addOnBottomReachedListener {
                if (isLoading) return@addOnBottomReachedListener

                // append more playlists to the recycler view
                if (playlistType == PlaylistType.PUBLIC) {
                    fetchNextPage()
                }
            }

            // For dynamic Mixes or public playlists, continuously prefetch radio pages in background as much as possible
            if (playlistType == PlaylistType.PUBLIC && nextPage != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    var backgroundFetches = 0
                    // Prefetch continuously in the background up to 100 pages (~2,000 songs)
                    while (nextPage != null && backgroundFetches < 100 && _binding != null) {
                        kotlinx.coroutines.delay(600)
                        if (!isLoading && _binding != null && nextPage != null) {
                            withContext(Dispatchers.Main) {
                                if (!isLoading && _binding != null && nextPage != null) {
                                    fetchNextPage()
                                }
                            }
                            backgroundFetches++
                        }
                    }
                }
            }

            // listener for swiping to the left or right
            if (playlistType != PlaylistType.PUBLIC) {
                binding.playlistRecView.setOnDismissListener { position ->
                    removeFromPlaylist(position)
                }
            }

            showPlaylistVideos()

            playlistViewModel.searchQuery.observe(viewLifecycleOwner) {
                showPlaylistVideos()
            }

            // show playlist options
            binding.optionsMenu.setOnClickListener {
                val sheet = PlaylistOptionsBottomSheet()
                sheet.arguments = android.os.Bundle().apply {
                    putString(IntentData.playlistId, playlistId)
                    putString(IntentData.playlistName, playlistName.orEmpty())
                    putSerializable(IntentData.playlistType, playlistType)
                }

                val fragmentManager = (context as BaseActivity).supportFragmentManager
                fragmentManager.setFragmentResultListener(
                    PlaylistOptionsBottomSheet.PLAYLIST_OPTIONS_REQUEST_KEY,
                    (context as BaseActivity)
                ) { _, resultBundle ->
                    val newPlaylistName =
                        resultBundle.getString(IntentData.playlistName)
                    val isPlaylistToBeDeleted =
                        resultBundle.getBoolean(IntentData.playlistTask)

                    newPlaylistName?.let {
                        binding.playlistName.text = it
                        playlistName = it
                    }

                    if (isPlaylistToBeDeleted) {
                        findNavController().popBackStack()
                        return@setFragmentResultListener
                    }
                }

                sheet.show(fragmentManager)
            }

            if (playlistFeed.isEmpty()) {
                binding.nothingHere.isVisible = true
                binding.playAll.isGone = true
                binding.shuffleBTN.isGone = true
                binding.bookmark.isGone = true
            } else {
                binding.playAll.isVisible = true
                binding.shuffleBTN.isVisible = true
                binding.playAll.setOnClickListener {
                    startVideoItemPlayback(getSortedVideos().first().item)
                }
                binding.shuffleBTN.setOnClickListener {
                    val queue = playlistFeed.shuffled()
                    PlayingQueue.setStreams(queue)
                    navigateVideo(queue.firstOrNull() ?: return@setOnClickListener)
                }
            }

            if (playlistType == PlaylistType.PUBLIC) {
                binding.bookmark.isVisible = true
                binding.bookmark.setOnClickListener {
                    isBookmarked = !isBookmarked
                    updateBookmarkRes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!isBookmarked) {
                            DatabaseHolder.Database.playlistBookmarkDao()
                                .deleteById(playlistId)
                        } else {
                            val liveCount = playlistFeed.size
                            val currentBookmark = response.copy(
                                name = playlistName ?: response.name,
                                videos = if (liveCount > 0) liveCount else response.videos
                            ).toPlaylistBookmark(playlistId)
                            DatabaseHolder.Database.playlistBookmarkDao()
                                .insert(currentBookmark)
                        }
                    }
                }
            } else {
                binding.bookmark.isGone = true

                if (playlistFeed.isEmpty()) {
                    binding.sortBTN.isGone = true
                } else {
                    binding.sortBTN.isVisible = true
                    binding.sortBTN.setOnClickListener {
                        BaseBottomSheet().apply {
                            setSimpleItems(sortOptions.toList()) { index ->
                                selectedSortOrder = index
                                binding.sortBTN.tooltipText = sortOptions[index]
                                showPlaylistVideos()
                            }
                        }.show(childFragmentManager)
                    }
                }

                binding.sortBTN.tooltipText = sortOptions[selectedSortOrder]

            }

            updatePlaylistBookmark(response)
        }
    }

    private fun navigateVideo(streamItem: StreamItem) {
        NavigationHelper.navigateVideo(
            requireContext(),
            playerData = PlayerData(
                streamItem.url!!.toID(),
                playlistId = playlistId,
                keepQueue = true
            )
        )
    }

    private fun startVideoItemPlayback(streamItem: StreamItem) {
        if (playlistFeed.isEmpty()) return

        val sortedStreams = getSortedVideos()
        PlayingQueue.setStreams(sortedStreams.map { it.item })

        navigateVideo(streamItem)
    }

    /**
     * If the playlist is bookmarked, update its content if modified by the uploader
     */
    private suspend fun updatePlaylistBookmark(playlist: Playlist) {
        if (!isBookmarked) return
        withContext(Dispatchers.IO) {
            // update the playlist thumbnail and title if bookmarked
            val playlistBookmark =
                DatabaseHolder.Database.playlistBookmarkDao().findById(playlistId)
                    ?: return@withContext
            if (playlistBookmark.thumbnailUrl != playlist.thumbnailUrl ||
                playlistBookmark.playlistName != playlist.name ||
                playlistBookmark.videos != playlist.videos
            ) {
                DatabaseHolder.Database.playlistBookmarkDao()
                    .update(playlist.toPlaylistBookmark(playlistBookmark.playlistId))
            }
        }
    }

    private fun getSortedVideos(): List<PlaylistItem> {
        // in addition to sorting, we need to make sure that the original index of the item
        // is still known. We solve this by wrapping the StreamItems into PlaylistItems that contain
        // an additional index attribute.
        val items = playlistFeed.mapIndexed { index, item -> PlaylistItem(item, index) }

        return when {
            selectedSortOrder in listOf(0, 1) || playlistType == PlaylistType.PUBLIC -> {
                items
            }

            selectedSortOrder in listOf(2, 3) -> {
                items.sortedBy { it.item.duration }
            }

            selectedSortOrder in listOf(4, 5) -> {
                items.sortedBy { it.item.title }
            }

            else -> throw IllegalArgumentException()
        }.let {
            if (selectedSortOrder % 2 == 0) it else it.reversed()
        }
    }

    private fun showPlaylistVideos() {
        var videos = getSortedVideos()

        val rawQuery = playlistViewModel.searchQuery.value?.trim()
        if (!rawQuery.isNullOrEmpty()) {
            val terms = rawQuery.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            videos = videos.filter { item ->
                val combinedText = "${item.item.title.orEmpty()} ${item.item.uploaderName.orEmpty()} ${item.item.albumName.orEmpty()}"
                terms.all { term -> combinedText.contains(term, ignoreCase = true) }
            }
        }

        playlistAdapter?.submitList(videos)

        updatePlaylistDuration(videos)
    }

    @SuppressLint("StringFormatInvalid")
    private fun removeFromPlaylist(sortedFeedPosition: Int) {
        val playlistAdapter = playlistAdapter ?: return
        if (sortedFeedPosition !in playlistAdapter.currentList.indices) return

        val (video, originalPlaylistPosition) = playlistAdapter.currentList[sortedFeedPosition]

        val updatedList = playlistAdapter.currentList.toMutableList()
        updatedList.removeAt(sortedFeedPosition)
        val fixedList = fixItemIndices(updatedList, originalPlaylistPosition, -1)
        playlistAdapter.submitList(fixedList)

        // Keep master in-memory cache synchronized
        if (originalPlaylistPosition in playlistFeed.indices) {
            playlistFeed.removeAt(originalPlaylistPosition)
        }

        // try to remove the video from the playlist and show an undo snackbar if successful
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.removeFromPlaylist(playlistId, originalPlaylistPosition)

                val shortTitle = TextUtils.limitTextToLength(video.title.orEmpty(), 50)
                val snackBarText = getString(
                    R.string.successfully_removed_from_playlist,
                    shortTitle
                )

                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, snackBarText, Snackbar.LENGTH_LONG)
                        .setTextMaxLines(3)
                        .setAction(R.string.undo) {
                            reAddToPlaylist(
                                video,
                                sortedFeedPosition,
                                originalPlaylistPosition
                            )
                        }
                        .show()
                    updateInfo(updatedList)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    private fun updateInfo(updatedList: List<PlaylistItem>) {
        val playlistCount = updatedList.size
        binding.playlistInfo.text = getChannelAndVideoString(
            Playlist(name = playlistName, videos = playlistCount),
            playlistCount
        )
        updatePlaylistDuration(updatedList)
        setPlaylistThumbnail(updatedList.firstOrNull()?.item?.thumbnail)
    }

    private fun reAddToPlaylist(
        streamItem: StreamItem,
        sortedFeedPosition: Int,
        originalFeedPosition: Int
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.addToPlaylist(playlistId, streamItem)

                // Keep in-memory cache synchronized
                if (originalFeedPosition <= playlistFeed.size) {
                    playlistFeed.add(originalFeedPosition, streamItem)
                } else {
                    playlistFeed.add(streamItem)
                }

                val playlistAdapter = playlistAdapter ?: return@launch
                val updatedList = playlistAdapter.currentList.toMutableList()
                val targetIndex = sortedFeedPosition.coerceAtMost(updatedList.size)
                updatedList.add(targetIndex, PlaylistItem(streamItem, originalFeedPosition))
                val fixedList = fixItemIndices(updatedList, originalFeedPosition, +1)

                withContext(Dispatchers.Main) {
                    playlistAdapter.submitList(fixedList)
                    updateInfo(fixedList)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    /**
     * After removing or adding a video to a playlist, the original positions of all videos
     * after the removed/added one change by one.
     *
     * E.g., if you remove the video at index 7, all videos after it move one to the left (8 -> 7, 9 -> 8, ...).
     * In this example, the offset would be 1.
     *
     * I.e., this method adds the given offset to all videos with an originalPlaylistIndex > modifiedPosition.
     */
    private fun fixItemIndices(
        items: List<PlaylistItem>,
        modifiedPosition: Int,
        offset: Int
    ): List<PlaylistItem> {
        return items.map {
            if (it.originalPlaylistIndex > modifiedPosition) {
                it.copy(originalPlaylistIndex = it.originalPlaylistIndex + offset)
            } else {
                it
            }
        }
    }

    private fun fetchNextPage() {
        if (nextPage == null || isLoading) return
        isLoading = true

        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getPlaylistNextPage(playlistId, nextPage!!)
                }
            } catch (e: Exception) {
                context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
                Log.e(TAG(), e.toString())
                return@launch
            }

            nextPage = response.nextpage
            playlistFeed.addAll(response.relatedStreams)
            val currentList = playlistAdapter?.currentList.orEmpty()
            val newList = currentList + response.relatedStreams.mapIndexed { index, item ->
                PlaylistItem(item, currentList.size + index)
            }
            playlistAdapter?.submitList(newList)
            updateInfo(newList)
            isLoading = false
        }
    }

    private fun formatCleanDuration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return ""
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "${hours} hr ${minutes} min"
            hours > 0 -> "${hours} hr"
            minutes > 0 -> "${minutes} min"
            else -> "${totalSeconds} sec"
        }
    }

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    private fun getChannelAndVideoString(playlist: Playlist, count: Int, totalDuration: Long = 0): String {
        val countFormatted = java.text.NumberFormat.getIntegerInstance().format(count.coerceAtLeast(0))
        val countStr = if (count == 1) "1 song" else "$countFormatted songs"
        val durationStr = formatCleanDuration(totalDuration)

        val metaList = mutableListOf<String>()
        if (!playlist.uploader.isNullOrEmpty()) {
            metaList.add(playlist.uploader)
        }
        metaList.add(countStr)
        if (durationStr.isNotEmpty()) {
            metaList.add(durationStr)
        }
        return metaList.joinToString(" • ")
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlaylistDuration(updatedList: List<PlaylistItem>) {
        val totalDuration = updatedList.sumOf { it.item.duration ?: 0 }
        val playlistCount = updatedList.size
        binding.playlistInfo.text = getChannelAndVideoString(
            Playlist(name = playlistName, videos = playlistCount),
            playlistCount,
            totalDuration
        )
    }

    // Update the Cover/Thumbnail of the playlist if the first video was removed
    private fun setPlaylistThumbnail(thumbnailUrl: String?) {
        if (!thumbnailUrl.isNullOrEmpty()) {
            ImageHelper.loadImage(thumbnailUrl, binding.thumbnail)
            lifecycleScope.launch {
                val bitmap = ImageHelper.getImage(requireContext(), thumbnailUrl)
                if (bitmap != null) {
                    withContext(Dispatchers.Default) {
                        val softBitmap = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        } else bitmap

                        val palette = runCatching {
                            androidx.palette.graphics.Palette.from(softBitmap).generate()
                        }.getOrNull() ?: return@withContext

                        val dominantColor = palette.getDominantColor(android.graphics.Color.TRANSPARENT)
                        val darkVibrant = palette.getDarkVibrantColor(dominantColor)
                        val bgColor = ThemeHelper.getThemeColor(requireContext(), android.R.attr.colorBackground)

                        val topColor = androidx.core.graphics.ColorUtils.blendARGB(darkVibrant, bgColor, 0.45f)
                        val gradient = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(topColor, bgColor)
                        )

                        withContext(Dispatchers.Main) {
                            headerTopColor = topColor
                            headerBgColor = bgColor
                            _binding?.playlistCollapsingTb?.background = gradient
                            val mainAct = activity as? app.libre.ui.activities.MainActivity
                            mainAct?.findViewById<View>(R.id.appBarLayout)?.setBackgroundColor(topColor)
                            mainAct?.findViewById<View>(R.id.toolbar)?.setBackgroundColor(topColor)
                        }
                    }
                }
            }
        } else {
            binding.thumbnail.setImageResource(R.drawable.ic_empty_playlist)
            binding.thumbnail.setPadding(64f.dpToPx())
            binding.thumbnail.setBackgroundColor(com.google.android.material.R.attr.colorSurface)
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }
}
