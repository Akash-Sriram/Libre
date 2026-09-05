package app.libre.ui.sheets

import android.os.Bundle
import android.util.Log
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.constants.IntentData
import app.libre.enums.ShareObjectType
import app.libre.extensions.TAG
import app.libre.extensions.toID
import app.libre.helpers.BackgroundHelper
import app.libre.helpers.NavigationHelper
import app.libre.obj.ShareData
import app.libre.parcelable.PlayerData
import app.libre.helpers.ShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dialog with different options for a selected video.
 *
 * Needs the [channelId] to load the content from the right video.
 */
class ChannelOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var channelId: String
    private var channelName: String? = null
    private var subscribed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channelId = arguments?.getString(IntentData.channelId)!!
        channelName = arguments?.getString(IntentData.channelName)
        subscribed = arguments?.getBoolean(IntentData.isSubscribed, false) ?: false

        setTitle(channelName)

        // List that stores the different menu options. In the future could be add more options here.
        val optionsList = mutableListOf(
            R.string.share,
            R.string.play_latest_videos,
            R.string.playOnBackground
        )

        setSimpleItems(optionsList.map { getString(it) }) { which ->
            when (optionsList[which]) {
                R.string.share -> {
                    ShareHelper.share(
                        context = requireContext(),
                        id = channelId,
                        title = channelName,
                        shareObjectType = ShareObjectType.CHANNEL
                    )
                }

                R.string.play_latest_videos -> {
                    try {
                        val channel = withContext(Dispatchers.IO) {
                            MediaServiceRepository.instance.getChannel(channelId)
                        }
                        channel.relatedStreams.firstOrNull()?.url?.toID()?.let {
                            NavigationHelper.navigateVideo(
                                requireContext(),
                                PlayerData(
                                    it,
                                    channelId = channelId
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG(), e.toString())
                    }
                }

                R.string.playOnBackground -> {
                    try {
                        val channel = withContext(Dispatchers.IO) {
                            MediaServiceRepository.instance.getChannel(channelId)
                        }
                        channel.relatedStreams.firstOrNull()?.url?.toID()?.let {
                            BackgroundHelper.playOnBackground(
                                requireContext(),
                                PlayerData(
                                    videoId = it,
                                    channelId = channelId
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG(), e.toString())
                    }
                }
            }
        }
    }
}
