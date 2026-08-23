package app.libre.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.math.MathUtils.clamp
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import app.libre.R
import app.libre.api.JsonHelper
import app.libre.api.YtMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.libre.api.obj.ChapterSegment
import app.libre.api.obj.Streams
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.databinding.FragmentAudioPlayerBinding
import app.libre.enums.PlayerCommand
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.navigateVideo
import app.libre.extensions.normalize
import app.libre.extensions.seekBy
import app.libre.extensions.toID
import app.libre.extensions.togglePlayPauseState
import app.libre.extensions.updateIfChanged
import app.libre.helpers.AudioHelper
import app.libre.helpers.BackgroundHelper
import app.libre.helpers.ClipboardHelper
import app.libre.helpers.ImageHelper
import app.libre.helpers.JioSaavnHelper
import app.libre.helpers.MusicCategoryCache
import app.libre.helpers.NavigationHelper
import app.libre.helpers.PlayerHelper
import app.libre.helpers.ThemeHelper
import app.libre.parcelable.PlayerData
import app.libre.services.AbstractPlayerService
import app.libre.services.OnlinePlayerService
import app.libre.ui.base.BasePlayerFragment
import app.libre.ui.extensions.getSystemInsets
import app.libre.ui.models.ChaptersViewModel
import app.libre.ui.models.CommonPlayerViewModel
import app.libre.ui.models.PlaybackStatus
import app.libre.ui.dialogs.AddToPlaylistDialog
import app.libre.ui.sheets.BaseBottomSheet
import app.libre.ui.sheets.ChaptersBottomSheet
import app.libre.ui.sheets.PlaybackOptionsSheet
import app.libre.ui.sheets.PlayingQueueSheet
import app.libre.ui.sheets.SleepTimerSheet
import app.libre.ui.sheets.VideoOptionsBottomSheet
import app.libre.util.PlayingQueue
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@UnstableApi
class AudioPlayerFragment : BasePlayerFragment(R.layout.fragment_audio_player) {
    private var _binding: FragmentAudioPlayerBinding? = null
    val binding get() = _binding!!

    private lateinit var audioHelper: AudioHelper
    private val activity get() = baseActivity
    private val viewModel: CommonPlayerViewModel get() = commonPlayerViewModel
    private val chaptersModel: ChaptersViewModel by activityViewModels()

    // for the transition
    private var transitionStartId = 0
    private var transitionEndId = 0

    private var handler = Handler(Looper.getMainLooper())
    private var isPaused = !PlayerHelper.playAutomatically

    var isOffline: Boolean = false
        private set
    private var isLocalPlaylist: Boolean = false
    private var syncedLines = emptyList<SyncedLine>()
    private var syncedAdapter: SyncedLyricsAdapter? = null
    private var lyricsFetchedVideoId: String? = null
    // When true, the audio player will NOT auto-promote to the video player for non-music content.
    // Set when the user manually switches from the video player to the audio player.
    private var noAutoVideoSwitch: Boolean = false
    // Tracks whether the auto-switch decision has been made for the current track.
    // Reset each time metadata changes (new track) so queued videos are re-evaluated.
    private var autoSwitchChecked: Boolean = false
    private var playerController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioHelper = AudioHelper(requireContext())

        isOffline = requireArguments().getBoolean(IntentData.offlinePlayer)
        noAutoVideoSwitch = requireArguments().getBoolean(IntentData.noAutoVideoSwitch, false)
        isLocalPlaylist = requireArguments().getBoolean(IntentData.isLocalPlaylist, false)

        BackgroundHelper.startMediaService(
            requireContext(),
            OnlinePlayerService::class.java,
        ) {
            if (_binding == null) {
                it.sendCustomCommand(AbstractPlayerService.stopServiceCommand, Bundle.EMPTY)
                it.release()
                return@startMediaService
            }

            playerController = it
            handleServiceConnection()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentAudioPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // manually apply additional padding for edge-to-edge compatibility
        activity.getSystemInsets()?.let { systemBars ->
            with(binding.audioPlayerMain) {
                setPadding(
                    paddingLeft,
                    paddingTop + systemBars.top,
                    paddingRight,
                    paddingBottom + systemBars.bottom
                )
            }
        }

        initializeTransitionLayout()

        // select the title TV in order for it to automatically scroll
        binding.title.isSelected = true
        binding.uploader.isSelected = true

        binding.title.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = binding.title.text.toString())
            true
        }

        PlayerHelper.autoPlayEnabled = true

        binding.prev.setOnClickListener {
            playerController?.navigateVideo(PlayingQueue.getPrev() ?: return@setOnClickListener)
        }

        binding.next.setOnClickListener {
            playerController?.navigateVideo(PlayingQueue.getNext() ?: return@setOnClickListener)
        }

        binding.rewindBTN.setOnClickListener {
            playerController?.seekBy(-PlayerHelper.seekIncrement)
        }
        binding.forwardBTN.setOnClickListener {
            playerController?.seekBy(PlayerHelper.seekIncrement)
        }

        childFragmentManager.setFragmentResultListener(
            PlayingQueueSheet.PLAYING_QUEUE_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, args ->
            playerController?.navigateVideo(
                args.getString(IntentData.videoId) ?: return@setFragmentResultListener
            )
        }
        binding.openQueue.setOnClickListener {
            PlayingQueueSheet().show(childFragmentManager)
        }

        binding.openLyrics.setOnClickListener {
            toggleLyrics()
        }

        binding.openVideo.setOnClickListener {
            val currentId = PlayingQueue.getCurrent()?.url?.toID()
            switchToVideoMode(currentId ?: return@setOnClickListener)
        }

        childFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            playerController?.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        binding.playerMotionLayout.addSwipeDismissListener {
            killFragment(true)
        }

        binding.playerMotionLayout.addSwipeUpListener {
            if (isAdded && childFragmentManager.findFragmentByTag(PlayingQueueSheet::class.java.name) == null) {
                PlayingQueueSheet().show(childFragmentManager, PlayingQueueSheet::class.java.name)
            }
        }

        binding.miniPlayerClose.setOnClickListener {
            killFragment(true)
        }

        // Apply spring elasticity feedback to transport & action controls
        binding.playPause.addSpringTouchFeedback()
        binding.rewindBTN.addSpringTouchFeedback()
        binding.prev.addSpringTouchFeedback()
        binding.next.addSpringTouchFeedback()
        binding.forwardBTN.addSpringTouchFeedback()
        binding.openQueue.addSpringTouchFeedback()
        binding.openLyrics.addSpringTouchFeedback()
        binding.openVideo.addSpringTouchFeedback()
        binding.miniPlayerPause.addSpringTouchFeedback()

        binding.thumbnail.setOnLongClickListener {
            showMoreOptionsSheet()
            true
        }

        binding.playPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.miniPlayerPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.lyricsContainer.setOnClickListener {
            toggleLyrics()
        }

        // update the currently shown volume
        binding.volumeProgressBar.let { bar ->
            bar.progress = audioHelper.getVolumeWithScale(bar.max)
        }

        if (!PlayerHelper.playAutomatically) updatePlayPauseButton()

        updateChapterIndex()
    }

    fun switchToVideoMode(videoId: String) {
        playerController?.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putBoolean(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name, false)
            }
        )

        killFragment(false)

        NavigationHelper.openVideoPlayerFragment(
            context = requireContext(),
            playerData = PlayerData(
                videoId = videoId,
                isOffline = isOffline,
                forceVideo = true
            ),
            alreadyStarted = true
        )
    }

    private fun killFragment(stopPlayer: Boolean) {
        viewModel.isMiniPlayerVisible.value = false

        if (stopPlayer) playerController?.sendCustomCommand(
            AbstractPlayerService.stopServiceCommand,
            Bundle.EMPTY
        )
        playerController?.release()
        playerController = null

        viewModel.isFullscreen.value = false
        _binding?.playerMotionLayout?.transitionToEnd()

        // Guard against fragment being detached before the swipe animation callback fires.
        if (!isAdded || isRemoving || isStateSaved) return
        requireActivity().supportFragmentManager.commit {
            remove(this@AudioPlayerFragment)
        }
    }

    fun playNextVideo(videoId: String) {
        playerController?.navigateVideo(videoId)
    }

    override fun isPlayerExpanded(): Boolean {
        val b = _binding ?: return false
        return (b.playerMotionLayout.currentState == R.id.start || b.playerMotionLayout.progress < 0.95f) && b.playerMotionLayout.currentState != R.id.end
    }

    override fun collapsePlayerToMini() {
        val b = _binding ?: return
        b.audioPlayerContainer.isClickable = false
        b.playerMotionLayout.setTransition(R.id.start, R.id.end)
        b.playerMotionLayout.setTransitionDuration(350)
        b.playerMotionLayout.transitionToEnd()
        commonPlayerViewModel.updateExpansionState(app.libre.ui.models.PlayerExpansionState.Collapsed(1.0f))
        baseActivity.minimizePlayerContainerLayout()
        baseActivity.requestOrientationChange()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        transitionStartId = R.id.start
        transitionEndId = R.id.end

        activity.setPlayerContainerProgress(0f)

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                activity.setPlayerContainerProgress(progress.absoluteValue)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (_binding == null) return

                if (currentId == transitionEndId) {
                    commonPlayerViewModel.updateExpansionState(app.libre.ui.models.PlayerExpansionState.Collapsed(1.0f))
                    clearBackCallbackPriority()
                    activity.setPlayerContainerProgress(1f)
                    activity.minimizePlayerContainerLayout()
                    activity.requestOrientationChange()
                } else if (currentId == transitionStartId) {
                    commonPlayerViewModel.updateExpansionState(app.libre.ui.models.PlayerExpansionState.Expanded)
                    updateBackCallbackPriority()
                    activity.setPlayerContainerProgress(0f)
                    activity.maximizePlayerContainerLayout()
                    activity.clearSearchViewFocus()
                }
            }
        })

        if (arguments?.getBoolean(IntentData.minimizeByDefault, false) != true) {
            commonPlayerViewModel.updateExpansionState(app.libre.ui.models.PlayerExpansionState.Expanded)
            binding.playerMotionLayout.progress = 0f
            binding.playerMotionLayout.transitionToStart()
            activity.maximizePlayerContainerLayout()
            updateBackCallbackPriority()
        } else {
            commonPlayerViewModel.updateExpansionState(app.libre.ui.models.PlayerExpansionState.Collapsed(1.0f))
            binding.playerMotionLayout.progress = 1f
            binding.playerMotionLayout.transitionToEnd()
            activity.minimizePlayerContainerLayout()
        }
    }

    /**
     * Load the information from a new stream into the UI.
     * Also handles the auto-promote-to-video logic with a two-level cache:
     *   1. MusicCategoryCache (instant, in-memory) — used on 2nd+ play of any video
     *   2. Stream metadata extras — used on 1st play (with STATE_READY fallback)
     */
    private fun updateStreamInfo(metadata: MediaMetadata) {
        val binding = _binding ?: return

        val currentStream = PlayingQueue.getCurrent()
        val currentVideoId = currentStream?.url.orEmpty().toID()
        val streamTitle = currentStream?.title ?: metadata.title?.toString()

        val localArtist = app.libre.helpers.LocalAudioMatcher.getArtistFromFile(currentVideoId, streamTitle)
        val localAlbum = app.libre.helpers.LocalAudioMatcher.getAlbumFromFile(currentVideoId, streamTitle)
        val localYear = app.libre.helpers.LocalAudioMatcher.getYearFromFile(currentVideoId, streamTitle)

        val rawArtist = localArtist
            ?: metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: currentStream?.uploaderName.orEmpty()
        val cleanArtist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist.replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "")) ?: ""

        val albumName = localAlbum
            ?: metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: currentStream?.albumName.orEmpty().trim()
        val year = localYear
            ?: metadata.recordingYear?.toString()?.takeIf { it.isNotBlank() }

        val albumYearText = when {
            albumName.isNotBlank() && !year.isNullOrBlank() -> "$albumName • $year"
            albumName.isNotBlank() -> albumName
            !year.isNullOrBlank() -> year
            else -> ""
        }

        val localTitle = app.libre.helpers.LocalAudioMatcher.getTitleFromFile(currentVideoId, streamTitle)
        val trackNumber = app.libre.helpers.LocalAudioMatcher.getTrackNumberFromFile(currentVideoId, streamTitle)
        val baseTitle = (localTitle ?: metadata.title ?: streamTitle).toString()
        val formattedTitle = app.libre.helpers.LocalAudioMatcher.formatTitleWithTrackNumber(baseTitle, trackNumber)

        binding.title.text = formattedTitle
        binding.miniPlayerTitle.text = formattedTitle
        binding.miniPlayerArtist.text = cleanArtist
        binding.miniPlayerArtist.visibility = if (cleanArtist.isNotBlank()) View.VISIBLE else View.GONE

        binding.uploader.text = cleanArtist
        binding.uploader.setOnClickListener {
            val uploaderId = metadata.composer?.toString() ?: return@setOnClickListener
            NavigationHelper.navigateChannel(requireContext(), uploaderId)
        }

        binding.albumAndYear.text = albumYearText
        binding.albumAndYear.isVisible = albumYearText.isNotBlank()

        metadata.artworkUri?.let { updateThumbnailAsync(it) }

        if (noAutoVideoSwitch || isOffline) {
            autoSwitchChecked = true  // no auto-switching in these modes — suppress STATE_READY retries
        } else if (!autoSwitchChecked) {
            val currentId = PlayingQueue.getCurrent()?.url?.toID()
            val isJioSaavn = JioSaavnHelper.isJioSaavn(currentId, isOffline)

            if (isJioSaavn) {
                autoSwitchChecked = true  // JioSaavn always stays in audio
            } else if (currentId != null) {
                // --- Fast path: check in-memory cache first (populated on previous plays or scan) ---
                val cached = MusicCategoryCache.get(requireContext(), currentId)
                if (cached != null) {
                    autoSwitchChecked = true
                    if (!cached) {
                        // Previously identified as video content → switch immediately, no stream wait
                        switchToVideoMode(currentId)
                        return
                    }
                    // else cached as "stay in audio" — fall through to render audio UI
                } else {
                    // --- Slow path: decode from stream metadata (1st play of this video) ---
                    val streams: Streams? = metadata.extras?.getString(IntentData.streams)?.let {
                        JsonHelper.json.decodeFromString(it)
                    }
                    if (streams != null) {
                        autoSwitchChecked = true
                        val isMusic = streams.category == Streams.CATEGORY_MUSIC
                        val hasVideo = streams.videoStreams.isNotEmpty()
                        // Store for instant routing on all future plays
                        val stayInAudio = isMusic || !hasVideo
                        MusicCategoryCache.put(requireContext(), currentId, stayInAudio)
                        if (!stayInAudio) {
                            switchToVideoMode(currentId)
                            return
                        }
                    }
                    // If streams still null here, STATE_READY hook will retry
                }
            }
        }

        initializeSeekBar()
    }

    private fun updateThumbnailAsync(thumbnailUri: Uri) {
        binding.progress.isVisible = true
        binding.thumbnail.isGone = true
        binding.thumbnail.setColorFilter(Color.TRANSPARENT)

        lifecycleScope.launch {
            val binding = _binding ?: return@launch
            val bitmap = ImageHelper.getImage(requireContext(), thumbnailUri)
            binding.thumbnail.setImageBitmap(bitmap)
            binding.miniPlayerThumbnail.setImageBitmap(bitmap)
            binding.thumbnail.isVisible = true
            binding.progress.isGone = true

            if (bitmap != null) {
                withContext(Dispatchers.Default) {
                    // Palette requires a software bitmap — hardware bitmaps (GPU-allocated by Coil)
                    // crash with "pixel access is not supported on Config#HARDWARE bitmaps".
                    val softBitmap = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                        bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    } else {
                        bitmap
                    }
                    val palette = runCatching {
                        androidx.palette.graphics.Palette.from(softBitmap).generate()
                    }.getOrNull() ?: return@withContext

                    val dominantColor = palette.getDominantColor(android.graphics.Color.DKGRAY)
                    val darkVibrant = palette.getDarkVibrantColor(dominantColor)
                    val bgColor = ThemeHelper.getThemeColor(requireContext(), android.R.attr.colorBackground)

                    val topColor = androidx.core.graphics.ColorUtils.blendARGB(darkVibrant, bgColor, 0.45f)
                    val midColor = androidx.core.graphics.ColorUtils.blendARGB(dominantColor, bgColor, 0.75f)

                    val gradient = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(topColor, midColor, bgColor)
                    )

                    withContext(Dispatchers.Main) {
                        _binding?.audioPlayerMain?.background = gradient
                    }
                }
            }
        }
    }

    private fun initializeSeekBar() {
        binding.timeBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) playerController?.seekTo(value.toLong() * 1000)
        }
        updateSeekBar()
    }

    /**
     * Update the position, duration and text views belonging to the seek bar
     */
    private fun updateSeekBar() {
        val binding = _binding ?: return
        val duration = playerController?.duration?.takeIf { it > 0 } ?: let {
            // if there's no duration available, clear everything
            binding.timeBar.value = 0f
            binding.duration.text = ""
            binding.currentPosition.text = ""
            handler.postDelayed(this::updateSeekBar, 100)
            return
        }
        val currentPosition = playerController?.currentPosition?.toFloat() ?: 0f

        // set the text for the indicators
        binding.duration.text = DateUtils.formatElapsedTime(duration / 1000)
        binding.currentPosition.text = DateUtils.formatElapsedTime(
            (currentPosition / 1000).toLong()
        )

        // update the time bar current value and maximum value
        binding.timeBar.valueTo = (duration / 1000).toFloat()
        binding.timeBar.value = clamp(
            currentPosition / 1000,
            binding.timeBar.valueFrom,
            binding.timeBar.valueTo
        )

        updateActiveLyricsLine(playerController?.currentPosition ?: 0L)

        handler.postDelayed(this::updateSeekBar, 200)
    }

    private fun updatePlayPauseButton() {
        playerController?.let {
            val binding = _binding ?: return

            val iconRes = PlayerHelper.getPlayPauseActionIcon(it)
            binding.playPause.setIconResource(iconRes)
            binding.miniPlayerPause.setIconResource(iconRes)
        }
    }

    private fun handleServiceConnection() {
        playerController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                updatePlayPauseButton()
                isPaused = !isPlaying
                viewModel.updatePlaybackStatus(if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                // New track → reset the switch decision so we re-evaluate for this video
                autoSwitchChecked = false
                updateStreamInfo(mediaMetadata)
                // JSON-encode as work-around for https://github.com/androidx/media/issues/564
                val chapters: List<ChapterSegment>? =
                    mediaMetadata.extras?.getString(IntentData.chapters)?.let {
                        JsonHelper.json.decodeFromString(it)
                    }
                chaptersModel.chaptersLiveData.value = chapters

                val currentVideo = PlayingQueue.getCurrent()
                val videoId = currentVideo?.url?.toID()
                viewModel.updateCurrentTrack(currentVideo, isAudioOnly = true)

                val binding = _binding
                if (binding != null && binding.lyricsContainer.visibility == View.VISIBLE && currentVideo != null && videoId != null) {
                    loadLyrics(currentVideo, videoId)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                when (playbackState) {
                    Player.STATE_BUFFERING -> viewModel.updatePlaybackStatus(PlaybackStatus.Buffering)
                    Player.STATE_ENDED -> viewModel.updatePlaybackStatus(PlaybackStatus.Ended)
                    Player.STATE_IDLE -> viewModel.updatePlaybackStatus(PlaybackStatus.Idle)
                    Player.STATE_READY -> viewModel.updatePlaybackStatus(if (playerController?.isPlaying == true) PlaybackStatus.Playing else PlaybackStatus.Paused)
                }
                // STATE_READY is a reliable fallback: streams are always loaded by now.
                // If the first metadata check had null streams, this ensures we retry.
                if (playbackState == Player.STATE_READY && !autoSwitchChecked) {
                    playerController?.mediaMetadata?.let { updateStreamInfo(it) }
                }
            }
        })
        playerController?.mediaMetadata?.let { updateStreamInfo(it) }
        // JSON-encode as work-around for https://github.com/androidx/media/issues/564
        chaptersModel.chaptersLiveData.value =
            playerController?.mediaMetadata?.extras?.getString(IntentData.chapters)?.let {
                JsonHelper.json.decodeFromString(it)
            }

        updatePlayPauseButton()
        initializeSeekBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showMoreOptionsSheet() {
        val current = PlayingQueue.getCurrent() ?: return
        val targetFm = requireActivity().supportFragmentManager
        VideoOptionsBottomSheet().apply {
            arguments = Bundle().apply {
                putParcelable(IntentData.streamItem, current)
                putBoolean("is_from_player", true)
                putBoolean("is_local_playlist", true)
            }
            onPlaybackSpeedClick = {
                playerController?.let { PlaybackOptionsSheet(it).show(targetFm) }
            }
            onSleepTimerClick = {
                SleepTimerSheet().show(targetFm)
            }
        }.show(targetFm, VideoOptionsBottomSheet::class.java.name)
    }

    private fun updateChapterIndex() {
        if (_binding == null) return
        handler.postDelayed(this::updateChapterIndex, 100)

        val currentIndex =
            PlayerHelper.getCurrentChapterIndex(
                playerController?.currentPosition ?: return,
                chaptersModel.chapters
            )
        chaptersModel.currentChapterIndex.updateIfChanged(currentIndex ?: return)
    }

    private fun toggleLyrics() {
        val binding = _binding ?: return
        val currentVideo = PlayingQueue.getCurrent() ?: return
        val videoId = currentVideo.url?.toID() ?: return

        val isShowingLyrics = binding.lyricsContainer.visibility == android.view.View.VISIBLE

        if (isShowingLyrics) {
            // Switch back to thumbnail
            binding.lyricsContainer.visibility = android.view.View.GONE
            binding.thumbnail.parent.let { if (it is android.view.View) it.visibility = android.view.View.VISIBLE }
        } else {
            // Switch to lyrics
            binding.thumbnail.parent.let { if (it is android.view.View) it.visibility = android.view.View.GONE }
            binding.lyricsContainer.visibility = android.view.View.VISIBLE

            // Check if we already have the lyrics for this song loaded
            if (lyricsFetchedVideoId != videoId) {
                loadLyrics(currentVideo, videoId)
            }
        }
    }

    private fun loadLyrics(currentVideo: StreamItem, videoId: String) {
        val binding = _binding ?: return
        binding.progress.visibility = android.view.View.VISIBLE
        binding.lyricsRecycler.visibility = android.view.View.GONE
        binding.plainLyricsScroll.visibility = android.view.View.GONE

        lifecycleScope.launch {
            var syncedLrc: String? = null
            var plainText: String? = null

            var cleanTitle: String? = null
            var cleanArtist: String? = null
            var cleanAlbum: String? = null

            // Check persistent cache first
            val cached = YtMusicApi.LyricsCache.get(requireContext(), videoId)
            if (cached != null) {
                syncedLrc = cached["synced"]?.takeIf { it.isNotBlank() }
                plainText = cached["plain"]?.takeIf { it.isNotBlank() }
                cleanTitle = cached["title"]?.takeIf { it.isNotBlank() }
                cleanArtist = cached["artist"]?.takeIf { it.isNotBlank() }
                cleanAlbum = cached["album"]?.takeIf { it.isNotBlank() }
            } else {
                // 1. Try fetching from LRCLIB first to get synced lyrics!
                val durationSec = playerController?.duration?.div(1000) ?: 0L

                val lrcMap = YtMusicApi.fetchLrcLyrics(videoId, durationSec)
                if (lrcMap != null) {
                    syncedLrc = lrcMap["synced"]?.takeIf { it.isNotBlank() }
                    plainText = lrcMap["plain"]?.takeIf { it.isNotBlank() }
                    cleanTitle = lrcMap["title"]?.takeIf { it.isNotBlank() }
                    cleanArtist = lrcMap["artist"]?.takeIf { it.isNotBlank() }
                    cleanAlbum = lrcMap["album"]?.takeIf { it.isNotBlank() }
                }

                // 2. If LRCLIB failed or returned nothing, check by provider
                if (syncedLrc == null && plainText == null) {
                    if (JioSaavnHelper.isJioSaavn(videoId, isOffline)) {
                        plainText = YtMusicApi.fetchJioSaavnLyrics(videoId.removePrefix("jsa_"))
                    } else {
                        plainText = YtMusicApi.fetchLyrics(videoId)
                    }
                }

                // Write back to persistent cache
                if (syncedLrc != null || plainText != null || !cleanTitle.isNullOrBlank()) {
                    val mapToCache = mapOf(
                        "synced" to (syncedLrc ?: ""),
                        "plain" to (plainText ?: ""),
                        "title" to (cleanTitle ?: ""),
                        "artist" to (cleanArtist ?: ""),
                        "album" to (cleanAlbum ?: "")
                    )
                    YtMusicApi.LyricsCache.put(requireContext(), videoId, mapToCache)
                }
            }

            // Silently update local playlist database with clean YouTube Music metadata!
            if (!cleanTitle.isNullOrBlank()) {
                try {
                    app.libre.db.DatabaseHolder.Database.localPlaylistsDao().updateTrackMetadata(
                        videoId,
                        cleanTitle,
                        cleanArtist,
                        cleanAlbum
                    )
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayerLyrics", "Failed to update track metadata in database", e)
                }
            }

            withContext(Dispatchers.Main) {
                binding.progress.visibility = android.view.View.GONE
                lyricsFetchedVideoId = videoId

                if (syncedLrc != null) {
                    syncedLines = parsedLyricsMemoryCache.get(videoId) ?: parseLrc(syncedLrc).also {
                        parsedLyricsMemoryCache.put(videoId, it)
                    }
                    syncedAdapter = SyncedLyricsAdapter(syncedLines) { line ->
                        playerController?.seekTo(line.timeMs)
                    }
                    binding.lyricsRecycler.itemAnimator = null
                    binding.lyricsRecycler.clipToPadding = false
                    binding.lyricsRecycler.setPadding(0, 320, 0, 320)
                    binding.lyricsRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
                    binding.lyricsRecycler.adapter = syncedAdapter
                    binding.lyricsRecycler.visibility = android.view.View.VISIBLE
                    binding.plainLyricsScroll.visibility = android.view.View.GONE
                } else if (plainText != null) {
                    binding.plainLyricsText.text = plainText
                    binding.plainLyricsScroll.visibility = android.view.View.VISIBLE
                    binding.lyricsRecycler.visibility = android.view.View.GONE
                    syncedLines = emptyList()
                    syncedAdapter = null
                } else {
                    binding.plainLyricsText.text = "Lyrics not available for this track"
                    binding.plainLyricsScroll.visibility = android.view.View.VISIBLE
                    binding.lyricsRecycler.visibility = android.view.View.GONE
                    syncedLines = emptyList()
                    syncedAdapter = null
                }
            }
        }
    }

    private fun parseLrc(lrcText: String): List<SyncedLine> {
        val lines = mutableListOf<SyncedLine>()
        if (lrcText.contains("start_ms:") || lrcText.contains("start_ms :")) {
            var currentText = ""
            var currentStartMs: Long? = null
            lrcText.split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("- text:") || trimmed.startsWith("text:")) {
                    val textVal = trimmed.substringAfter("text:").trim().removeSurrounding("\"").removeSurrounding("'")
                    currentText = textVal
                } else if (trimmed.startsWith("start_ms:") || trimmed.startsWith("start_ms :")) {
                    val msVal = trimmed.substringAfter("start_ms:").trim().toLongOrNull()
                    currentStartMs = msVal
                }
                val startMs = currentStartMs
                if (startMs != null) {
                    lines.add(SyncedLine(startMs, currentText))
                    currentText = ""
                    currentStartMs = null
                }
            }
        } else {
            val regex = Regex("""\[(\d+):(\d+)(?:\.(\d+))?\](.*)""")
            lrcText.split("\n").forEach { line ->
                val match = regex.find(line.trim())
                if (match != null) {
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val sec = match.groupValues[2].toLongOrNull() ?: 0L
                    val msStr = match.groupValues[3]
                    var ms = msStr.toLongOrNull() ?: 0L
                    if (msStr.length == 2) ms *= 10 // Convert .34 to 340ms
                    val timeMs = (min * 60 + sec) * 1000 + ms
                    val text = match.groupValues[4].trim()
                    lines.add(SyncedLine(timeMs, text))
                }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun updateActiveLyricsLine(positionMs: Long) {
        val adapter = syncedAdapter ?: return
        if (binding.lyricsContainer.visibility != View.VISIBLE) return
        var activeIndex = -1
        for (i in syncedLines.indices) {
            if (positionMs >= syncedLines[i].timeMs) {
                activeIndex = i
            } else {
                break
            }
        }
        android.util.Log.d("AudioPlayerLyrics", "positionMs: $positionMs, activeIndex: $activeIndex, currentPosition: ${adapter.currentPosition}")
        if (activeIndex != -1 && adapter.currentPosition != activeIndex) {
            adapter.currentPosition = activeIndex
            val smoothScroller = CenterSmoothScroller(binding.lyricsRecycler.context).apply {
                targetPosition = activeIndex
            }
            binding.lyricsRecycler.layoutManager?.startSmoothScroll(smoothScroller)
        }
    }

    class CenterSmoothScroller(context: android.content.Context) : androidx.recyclerview.widget.LinearSmoothScroller(context) {
        override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
            return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
        }

        override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
            return 120f / displayMetrics.densityDpi
        }
    }

    data class SyncedLine(val timeMs: Long, val text: String)

    class SyncedLyricsAdapter(
        private val lines: List<SyncedLine>,
        private val onLineClick: (SyncedLine) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SyncedLyricsAdapter.ViewHolder>() {

        var currentPosition: Int = -1
            set(value) {
                if (field != value) {
                    val old = field
                    field = value
                    if (old in lines.indices) notifyItemChanged(old)
                    if (value in lines.indices) notifyItemChanged(value)
                }
            }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val textView = android.widget.TextView(parent.context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(32, 16, 32, 16)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            return ViewHolder(textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val line = lines[position]
            holder.textView.text = line.text

            val context = holder.textView.context
            val primaryColor = ThemeHelper.getThemeColor(context, android.R.attr.textColorPrimary)
            val inactiveColor = androidx.core.graphics.ColorUtils.setAlphaComponent(primaryColor, (0.35f * 255).toInt())

            val isCurrent = (position == currentPosition)
            holder.textView.alpha = 1.0f
            holder.textView.scaleX = 1.0f
            holder.textView.scaleY = 1.0f

            if (isCurrent) {
                holder.textView.textSize = 22f
                holder.textView.setTextColor(primaryColor)
                holder.textView.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                holder.textView.textSize = 19f
                holder.textView.setTextColor(inactiveColor)
                holder.textView.typeface = android.graphics.Typeface.DEFAULT
            }

            holder.textView.setOnClickListener {
                holder.textView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onLineClick(line)
            }
        }

        override fun getItemCount(): Int = lines.size

        class ViewHolder(val textView: android.widget.TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(textView)
    }

    companion object {
        private val parsedLyricsMemoryCache = android.util.LruCache<String, List<SyncedLine>>(100)
    }
}
