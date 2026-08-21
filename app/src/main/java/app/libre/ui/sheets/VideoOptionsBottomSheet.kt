package app.libre.ui.sheets

import android.os.Bundle
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.NavHostFragment
import app.libre.R
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.constants.PreferenceKeys
import app.libre.db.DatabaseHolder
import app.libre.enums.ShareObjectType
import app.libre.extensions.parcelable
import app.libre.extensions.toID
import app.libre.helpers.NavigationHelper
import app.libre.helpers.PlayerHelper
import app.libre.helpers.PreferenceHelper
import app.libre.obj.ShareData
import app.libre.parcelable.PlayerData
import app.libre.ui.activities.MainActivity
import app.libre.ui.dialogs.AddToPlaylistDialog
import app.libre.ui.dialogs.ShareDialog
import app.libre.util.PlayingQueue
import app.libre.util.PlayingQueueMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Dialog with different options for a selected video.
 *
 * Needs the [streamItem] to load the content from the right video.
 */
class VideoOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var streamItem: StreamItem

    override fun onCreate(savedInstanceState: Bundle?) {
        streamItem = arguments?.parcelable(IntentData.streamItem)!!
        val playlistId = arguments?.getString(IntentData.playlistId)

        val videoId = streamItem.url?.toID() ?: return

        setTitle(streamItem.title)

        val optionsList = mutableListOf<Int>()
        // these options are only available for other videos than the currently playing one
        if (PlayingQueue.getCurrent()?.url?.toID() != videoId) {
            optionsList += getOptionsForNotActivePlayback(videoId)
        }

        optionsList += listOf(
            R.string.addToPlaylist,
            R.string.share,
            R.string.copy_link
        )

        if (!streamItem.uploaderUrl.isNullOrEmpty()) {
            optionsList += R.string.channels
        }

        setSimpleItems(optionsList.map { getString(it) }) { which ->
            when (optionsList[which]) {
                // Start the background mode
                R.string.playOnBackground -> {
                    NavigationHelper.navigateVideo(
                        requireContext(),
                        playerData = PlayerData(
                            videoId = videoId,
                            timestamp = 0L
                        ),
                        audioOnlyPlayerRequested = true
                    )
                }
                // Add Video to Playlist Dialog
                R.string.addToPlaylist -> {
                    AddToPlaylistDialog().apply {
                        arguments = Bundle().apply { putParcelable(IntentData.videoInfo, streamItem) }
                    }.show(
                        parentFragmentManager,
                        AddToPlaylistDialog::class.java.name
                    )
                }

                R.string.play_next -> {
                    PlayingQueue.addAsNext(streamItem)
                }

                R.string.add_to_queue -> {
                    PlayingQueue.add(streamItem)
                }

                R.string.share -> {
                    val bundle = Bundle().apply {
                        putString(IntentData.id, videoId)
                        putSerializable(IntentData.shareObjectType, app.libre.enums.ShareObjectType.VIDEO)
                        putParcelable(IntentData.shareData, app.libre.obj.ShareData(currentVideo = streamItem.title))
                    }
                    ShareDialog().apply {
                        arguments = bundle
                    }.show(parentFragmentManager, ShareDialog::class.java.name)
                }

                R.string.copy_link -> {
                    val url = if (videoId.startsWith("jsa_")) {
                        "https://www.jiosaavn.com/song/$videoId"
                    } else {
                        "https://youtu.be/$videoId"
                    }
                    app.libre.helpers.ClipboardHelper.save(context = requireContext(), text = url, notify = true)
                }

                R.string.channels -> {
                    streamItem.uploaderUrl?.let {
                        NavigationHelper.navigateChannel(requireContext(), it)
                    }
                }
            }
        }

        super.onCreate(savedInstanceState)
    }

    private fun getOptionsForNotActivePlayback(videoId: String): List<Int> {
        // List that stores the different menu options.
        val optionsList = mutableListOf(R.string.playOnBackground)

        // Check whether the player is running and add queue options
        if (PlayingQueue.isNotEmpty() && PlayingQueue.queueMode == PlayingQueueMode.ONLINE) {
            optionsList += R.string.play_next
            optionsList += R.string.add_to_queue
        }

        return optionsList
    }

    companion object {
        const val VIDEO_OPTIONS_SHEET_REQUEST_KEY = "video_options_sheet_request_key"
    }
}
