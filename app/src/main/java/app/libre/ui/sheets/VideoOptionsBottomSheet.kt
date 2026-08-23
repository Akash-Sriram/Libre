package app.libre.ui.sheets

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import app.libre.R
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.databinding.SheetVideoOptionsBinding
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.parcelable
import app.libre.extensions.toID
import app.libre.extensions.toastFromMainDispatcher
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.parcelable.PlayerData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.libre.ui.dialogs.AddToPlaylistDialog
import app.libre.ui.dialogs.ShareDialog
import app.libre.util.PlayingQueue

/**
 * Modern Material 3 bottom sheet with rich track header and styled options for a selected video.
 */
class VideoOptionsBottomSheet : ExpandedBottomSheet(R.layout.sheet_video_options) {
    private lateinit var streamItem: StreamItem

    var onPlaybackSpeedClick: (() -> Unit)? = null
    var onSleepTimerClick: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamItem = arguments?.parcelable(IntentData.streamItem)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = SheetVideoOptionsBinding.bind(view)
        val videoId = streamItem.url?.toID() ?: return

        // 1. Resolve metadata (sync with local tags ONLY for local playlists)
        val isLocalPlaylist = arguments?.getBoolean("is_local_playlist", false) ?: false
        val isFromPlayer = arguments?.getBoolean("is_from_player", false) ?: false
        val displayTitle: String
        val subtitleText: String

        if (isLocalPlaylist || isFromPlayer) {
            val localTitle = app.libre.helpers.LocalAudioMatcher.getTitleFromFile(videoId, streamItem.title)
            val baseTitle = (localTitle ?: streamItem.title).orEmpty()
            val trackNumber = app.libre.helpers.LocalAudioMatcher.getTrackNumberFromFile(videoId, streamItem.title)
            displayTitle = app.libre.helpers.LocalAudioMatcher.formatTitleWithTrackNumber(baseTitle, trackNumber)

            val localArtist = app.libre.helpers.LocalAudioMatcher.getArtistFromFile(videoId, streamItem.title)
            val localAlbum = app.libre.helpers.LocalAudioMatcher.getAlbumFromFile(videoId, streamItem.title)
            val localYear = app.libre.helpers.LocalAudioMatcher.getYearFromFile(videoId, streamItem.title)

            val album = localAlbum ?: streamItem.albumName.orEmpty().trim()
            val year = localYear
            val rawArtist = (localArtist ?: streamItem.uploaderName.orEmpty()).replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
            val artist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist) ?: rawArtist

            subtitleText = when {
                artist.isNotEmpty() && album.isNotEmpty() && !year.isNullOrBlank() -> "$artist • $album ($year)"
                artist.isNotEmpty() && album.isNotEmpty() -> "$artist • $album"
                artist.isNotEmpty() && !year.isNullOrBlank() -> "$artist • $year"
                artist.isNotEmpty() -> artist
                album.isNotEmpty() -> album
                else -> ""
            }
        } else {
            // Online search results, albums, public playlists: direct authentic online metadata
            displayTitle = streamItem.title.orEmpty()
            val rawArtist = streamItem.uploaderName.orEmpty().replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
            val artist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist) ?: rawArtist
            val album = streamItem.albumName.orEmpty().trim()

            subtitleText = when {
                artist.isNotEmpty() && album.isNotEmpty() -> "$artist • $album"
                album.isNotEmpty() -> album
                artist.isNotEmpty() -> artist
                else -> ""
            }
        }

        binding.sheetTitle.text = displayTitle
        binding.sheetSubtitle.text = subtitleText
        binding.sheetSubtitle.isVisible = subtitleText.isNotBlank()

        // 2. Source badge
        val isJioSaavn = app.libre.helpers.JioSaavnHelper.isJioSaavn(videoId)
        binding.sheetSourceBadge.text = if (isJioSaavn) "JioSaavn" else "YouTube"
        val badgeColor = if (isJioSaavn) android.graphics.Color.parseColor("#00B368") else android.graphics.Color.parseColor("#E53935")
        val badgeBg = if (isJioSaavn) android.graphics.Color.parseColor("#2200B368") else android.graphics.Color.parseColor("#22E53935")
        binding.sheetSourceBadge.setTextColor(badgeColor)
        binding.sheetSourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(badgeBg)

        // 3. Thumbnail
        ImageHelper.loadImage(streamItem.thumbnail, binding.sheetThumbnail)

        // 4. Spring feedback
        binding.actionPlayNext.addSpringTouchFeedback()
        binding.actionAddToQueue.addSpringTouchFeedback()
        binding.actionPlayBackground.addSpringTouchFeedback()
        binding.actionAddToPlaylist.addSpringTouchFeedback()
        binding.actionShare.addSpringTouchFeedback()
        binding.actionCopyLink.addSpringTouchFeedback()
        binding.actionChannel.addSpringTouchFeedback()

        // 5. Actions visibility
        val isCurrentPlaying = isFromPlayer || PlayingQueue.getCurrent()?.url?.toID() == videoId
        binding.actionPlayNext.isGone = isCurrentPlaying
        binding.actionAddToQueue.isGone = isCurrentPlaying
        binding.actionPlayBackground.isGone = isFromPlayer
        binding.actionChannel.isGone = streamItem.uploaderUrl.isNullOrEmpty()

        binding.actionPlaybackSpeed.isVisible = isFromPlayer
        binding.actionSleepTimer.isVisible = isFromPlayer
        if (isFromPlayer) {
            binding.actionPlaybackSpeed.addSpringTouchFeedback()
            binding.actionSleepTimer.addSpringTouchFeedback()
            binding.actionPlaybackSpeed.setOnClickListener {
                val cb = onPlaybackSpeedClick
                dismiss()
                cb?.invoke()
            }
            binding.actionSleepTimer.setOnClickListener {
                val cb = onSleepTimerClick
                dismiss()
                cb?.invoke()
            }
        }

        // 6. Action Clicks
        binding.actionPlayNext.setOnClickListener {
            dismiss()
            PlayingQueue.addAsNext(streamItem)
        }

        binding.actionAddToQueue.setOnClickListener {
            dismiss()
            val context = requireContext()
            if (PlayingQueue.getCurrent() == null) {
                PlayingQueue.setStreams(listOf(streamItem))
                NavigationHelper.navigateVideo(
                    context,
                    playerData = PlayerData(
                        videoId = videoId,
                        timestamp = 0L
                    ),
                    audioOnlyPlayerRequested = true
                )
            } else {
                PlayingQueue.add(streamItem)
            }
            android.widget.Toast.makeText(context, R.string.added_to_queue, android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.actionPlayBackground.setOnClickListener {
            dismiss()
            val context = requireContext()
            PlayingQueue.setStreams(listOf(streamItem))
            NavigationHelper.navigateVideo(
                context,
                playerData = PlayerData(
                    videoId = videoId,
                    timestamp = 0L
                ),
                audioOnlyPlayerRequested = true
            )
        }

        binding.actionAddToPlaylist.setOnClickListener {
            dismiss()
            AddToPlaylistDialog().apply {
                arguments = Bundle().apply { putParcelable(IntentData.videoInfo, streamItem) }
            }.show(parentFragmentManager, AddToPlaylistDialog::class.java.name)
        }

        binding.actionShare.setOnClickListener {
            dismiss()
            val bundle = Bundle().apply {
                putString(IntentData.id, videoId)
                putSerializable(IntentData.shareObjectType, app.libre.enums.ShareObjectType.VIDEO)
                putParcelable(IntentData.shareData, app.libre.obj.ShareData(currentVideo = streamItem.title))
            }
            ShareDialog().apply {
                arguments = bundle
            }.show(parentFragmentManager, ShareDialog::class.java.name)
        }

        binding.actionCopyLink.setOnClickListener {
            dismiss()
            val shareHost = app.libre.helpers.PreferenceHelper.getString("share_link_host", "music")
            val url = if (videoId.startsWith("jsa_")) {
                "https://www.jiosaavn.com/song/$videoId"
            } else if (shareHost == "music") {
                "https://music.youtube.com/watch?v=$videoId"
            } else {
                "https://youtu.be/$videoId"
            }
            app.libre.helpers.ClipboardHelper.save(context = requireContext(), text = url, notify = true)
        }

        binding.actionChannel.setOnClickListener {
            dismiss()
            streamItem.uploaderUrl?.let {
                NavigationHelper.navigateChannel(requireContext(), it)
            }
        }

        // 7. Song <-> Video Switcher
        val isYouTube = videoId.length == 11
        if (isYouTube) {
            val titleLower = streamItem.title?.lowercase().orEmpty()
            val isLikelyVideoVersion = titleLower.contains("video") || titleLower.contains("4k") || titleLower.contains("hd") || titleLower.contains("uhd")
            binding.switchVersionText.text = if (isLikelyVideoVersion) "Switch to studio audio" else "Switch to official music video"
            binding.switchVersionIcon.setImageResource(if (isLikelyVideoVersion) R.drawable.ic_audio else R.drawable.ic_video)
            binding.actionSwitchVersion.isVisible = true
            binding.actionSwitchVersion.addSpringTouchFeedback()

            binding.actionSwitchVersion.setOnClickListener {
                dismiss()
                val context = requireContext()
                val act = activity as? app.libre.ui.activities.MainActivity
                act?.lifecycleScope?.launch(Dispatchers.IO) {
                    val rawArtist = (streamItem.uploaderName ?: "").replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
                    val artist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist) ?: rawArtist
                    val target = if (isLikelyVideoVersion) {
                        app.libre.api.YtMusicApi.resolveStudioMaster(streamItem.title.orEmpty(), artist)
                    } else {
                        app.libre.api.YtMusicApi.resolveOfficialVideo(streamItem.title.orEmpty(), artist)
                    }
                    withContext(Dispatchers.Main) {
                        if (target != null) {
                            val newId = target.url.orEmpty().toID()
                            if (newId.isNotEmpty() && newId != videoId) {
                                NavigationHelper.navigateVideo(
                                    context,
                                    playerData = PlayerData(
                                        videoId = newId,
                                        keepQueue = true
                                    ),
                                    audioOnlyPlayerRequested = isLikelyVideoVersion
                                )
                            } else {
                                android.widget.Toast.makeText(context, "Already playing best version", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            android.widget.Toast.makeText(context, R.string.error, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            binding.actionSwitchVersion.isGone = true
        }

        // 8. Go to album
        val isInAlbum = arguments?.getBoolean("is_in_album", false) ?: false
        val candidateAlbumId = streamItem.albumId
        val albumName = streamItem.albumName?.takeIf { it.isNotBlank() }
            ?: if (isLocalPlaylist || isFromPlayer) app.libre.helpers.LocalAudioMatcher.getAlbumFromFile(videoId, streamItem.title) else null
        val hasAlbum = !isInAlbum && (isYouTube || !candidateAlbumId.isNullOrBlank() || !albumName.isNullOrBlank())
        binding.actionAlbum.isVisible = hasAlbum
        if (hasAlbum) {
            binding.actionAlbum.addSpringTouchFeedback()
            binding.actionAlbumText.text = "Go to album"
            binding.actionAlbum.setOnClickListener {
                dismiss()
                val context = requireContext()
                if (!candidateAlbumId.isNullOrBlank() && (candidateAlbumId.startsWith("MPRE") || candidateAlbumId.startsWith("OLAK") || candidateAlbumId.startsWith("VL") || candidateAlbumId.startsWith("PL") || candidateAlbumId.startsWith("jsa_album_"))) {
                    NavigationHelper.navigatePlaylist(context, candidateAlbumId, app.libre.enums.PlaylistType.PUBLIC)
                } else {
                    val rawArtist = (streamItem.uploaderName ?: "").replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
                    val artist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist) ?: rawArtist
                    val act = activity as? app.libre.ui.activities.MainActivity
                    act?.lifecycleScope?.launch(Dispatchers.IO) {
                        var targetAlbum = albumName
                        if (targetAlbum.isNullOrBlank()) {
                            val master = app.libre.api.YtMusicApi.resolveStudioMaster(streamItem.title.orEmpty(), artist)
                            targetAlbum = master?.albumName?.takeIf { it.isNotBlank() }
                        }
                        val resolvedId = if (!targetAlbum.isNullOrBlank()) {
                            app.libre.api.YtMusicApi.resolveAlbumId(targetAlbum, artist)
                        } else null

                        withContext(Dispatchers.Main) {
                            if (!resolvedId.isNullOrBlank()) {
                                NavigationHelper.navigatePlaylist(context, resolvedId, app.libre.enums.PlaylistType.PUBLIC)
                            } else {
                                android.widget.Toast.makeText(context, R.string.error, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val VIDEO_OPTIONS_SHEET_REQUEST_KEY = "video_options_sheet_request_key"
    }
}
