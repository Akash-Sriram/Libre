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
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.parcelable.PlayerData
import app.libre.ui.dialogs.AddToPlaylistDialog
import app.libre.ui.dialogs.ShareDialog
import app.libre.util.PlayingQueue

/**
 * Modern Material 3 bottom sheet with rich track header and styled options for a selected video.
 */
class VideoOptionsBottomSheet : ExpandedBottomSheet(R.layout.sheet_video_options) {
    private lateinit var streamItem: StreamItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamItem = arguments?.parcelable(IntentData.streamItem)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = SheetVideoOptionsBinding.bind(view)
        val videoId = streamItem.url?.toID() ?: return

        // 1. Resolve local clean metadata
        val localTitle = app.libre.helpers.LocalAudioMatcher.getTitleFromFile(videoId, streamItem.title)
        val baseTitle = (localTitle ?: streamItem.title).orEmpty()
        val trackNumber = app.libre.helpers.LocalAudioMatcher.getTrackNumberFromFile(videoId, streamItem.title)
        val displayTitle = app.libre.helpers.LocalAudioMatcher.formatTitleWithTrackNumber(baseTitle, trackNumber)

        val localArtist = app.libre.helpers.LocalAudioMatcher.getArtistFromFile(videoId, streamItem.title)
        val localAlbum = app.libre.helpers.LocalAudioMatcher.getAlbumFromFile(videoId, streamItem.title)
        val localYear = app.libre.helpers.LocalAudioMatcher.getYearFromFile(videoId, streamItem.title)

        val album = localAlbum ?: streamItem.albumName.orEmpty().trim()
        val year = localYear
        val rawArtist = (localArtist ?: streamItem.uploaderName.orEmpty()).replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
        val artist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist) ?: rawArtist

        val subtitleText = when {
            artist.isNotEmpty() && album.isNotEmpty() && !year.isNullOrBlank() -> "$artist • $album ($year)"
            artist.isNotEmpty() && album.isNotEmpty() -> "$artist • $album"
            artist.isNotEmpty() && !year.isNullOrBlank() -> "$artist • $year"
            artist.isNotEmpty() -> artist
            album.isNotEmpty() -> album
            else -> ""
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
        val isCurrentPlaying = PlayingQueue.getCurrent()?.url?.toID() == videoId
        binding.actionPlayNext.isGone = isCurrentPlaying
        binding.actionAddToQueue.isGone = isCurrentPlaying
        binding.actionChannel.isGone = streamItem.uploaderUrl.isNullOrEmpty()

        // 6. Action Clicks
        binding.actionPlayNext.setOnClickListener {
            dismiss()
            PlayingQueue.addAsNext(streamItem)
        }

        binding.actionAddToQueue.setOnClickListener {
            dismiss()
            PlayingQueue.add(streamItem)
        }

        binding.actionPlayBackground.setOnClickListener {
            dismiss()
            NavigationHelper.navigateVideo(
                requireContext(),
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
            val url = if (videoId.startsWith("jsa_")) {
                "https://www.jiosaavn.com/song/$videoId"
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
    }

    companion object {
        const val VIDEO_OPTIONS_SHEET_REQUEST_KEY = "video_options_sheet_request_key"
    }
}
