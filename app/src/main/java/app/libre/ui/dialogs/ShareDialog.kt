package app.libre.ui.dialogs

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import app.libre.R
import app.libre.constants.IntentData
import app.libre.databinding.DialogShareBinding
import app.libre.enums.ShareObjectType
import app.libre.extensions.parcelable
import app.libre.extensions.serializable
import app.libre.extensions.toID
import app.libre.helpers.ClipboardHelper
import app.libre.helpers.PreferenceHelper
import app.libre.obj.ShareData
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ShareDialog : DialogFragment() {
    private lateinit var id: String
    private lateinit var shareObjectType: ShareObjectType
    private lateinit var shareData: ShareData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            id = it.getString(IntentData.id)!!
            shareObjectType = it.serializable(IntentData.shareObjectType)!!
            shareData = it.parcelable(IntentData.shareData)!!
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val shareableTitle = shareData.currentChannel
            ?: shareData.currentVideo
            ?: shareData.currentPlaylist.orEmpty()

        val binding = DialogShareBinding.inflate(layoutInflater)
        
        val isYouTubeVideo = shareObjectType == ShareObjectType.VIDEO && !app.libre.helpers.JioSaavnHelper.isJioSaavn(id, false)
        if (isYouTubeVideo) {
            binding.shareHostGroup.isVisible = true
            val savedHost = PreferenceHelper.getString("share_link_host", "music")
            if (savedHost == "youtube") {
                binding.radioYoutube.isChecked = true
            } else {
                binding.radioYtMusic.isChecked = true
            }

            binding.shareHostGroup.setOnCheckedChangeListener { _, checkedId ->
                val newHost = if (checkedId == R.id.radio_youtube) "youtube" else "music"
                PreferenceHelper.putString("share_link_host", newHost)
                binding.linkPreview.text = generateLinkText(binding)
            }
        } else {
            binding.shareHostGroup.isVisible = false
        }

        if (shareObjectType == ShareObjectType.VIDEO) {
            binding.timeStampSwitchLayout.isVisible = true
            binding.timeCodeSwitch.isChecked = PreferenceHelper.getBoolean(
                "share_with_time_code",
                false
            )
            binding.timeCodeSwitch.setOnCheckedChangeListener { _, isChecked ->
                binding.timeStampInputLayout.isVisible = isChecked
                PreferenceHelper.putBoolean("share_with_time_code", isChecked)
                binding.linkPreview.text = generateLinkText(binding)
            }
            binding.timeStamp.addTextChangedListener {
                binding.linkPreview.text = generateLinkText(binding)
            }
            val timeStamp = shareData.currentPosition ?: 0L
            binding.timeStamp.setText((timeStamp).toString())
            if (binding.timeCodeSwitch.isChecked) {
                binding.timeStampInputLayout.isVisible = true
            }
        }

        binding.copyLink.setOnClickListener {
            ClipboardHelper.save(requireContext(), text = binding.linkPreview.text.toString())
        }

        binding.linkPreview.text = generateLinkText(binding)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.share))
            .setView(binding.root)
            .setPositiveButton(R.string.share) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, binding.linkPreview.text.toString())
                    .putExtra(Intent.EXTRA_SUBJECT, shareableTitle)
                    .setType("text/plain")
                val shareIntent = Intent.createChooser(intent, getString(R.string.shareTo))
                requireContext().startActivity(shareIntent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generateLinkText(
        binding: DialogShareBinding
    ): String {
        val cleanId = id.removePrefix("jsa_song_").removePrefix("jsa_album_").removePrefix("jsa_playlist_")
        val isJioSaavn = app.libre.helpers.JioSaavnHelper.isJioSaavn(id, false)
        
        if (isJioSaavn) {
            val parts = cleanId.split("_")
            val token = parts.getOrNull(1) ?: parts[0]
            if (id.startsWith("jsa_album_")) {
                return "https://www.jiosaavn.com/album/album/$token"
            } else if (id.startsWith("jsa_playlist_")) {
                return "https://www.jiosaavn.com/featured/playlist/$token"
            } else if (shareObjectType == ShareObjectType.CHANNEL) {
                return "https://www.jiosaavn.com/artist/artist/$token"
            } else {
                return "https://www.jiosaavn.com/song/track/$token"
            }
        }

        val cleanYtId = id.toID()
        val isMusicPlaylist = cleanYtId.startsWith("OLAK") || cleanYtId.startsWith("MPRE")
        val isMusicTrack = binding.radioYtMusic.isChecked
        val host = if (isMusicPlaylist || isMusicTrack) YOUTUBE_MUSIC_URL else YOUTUBE_FRONTEND_URL
        val url = when (shareObjectType) {
            ShareObjectType.VIDEO -> {
                val queryParams = mutableListOf<String>()
                if (binding.timeCodeSwitch.isChecked) {
                    queryParams += "t=${binding.timeStamp.text}"
                }
                val baseUrl = if (isMusicTrack) "$YOUTUBE_MUSIC_URL/watch?v=$cleanYtId" else "$YOUTUBE_SHORT_URL/$cleanYtId"

                if (queryParams.isEmpty()) baseUrl
                else baseUrl + (if (baseUrl.contains("?")) "&" else "?") + queryParams.joinToString("&")
            }

            ShareObjectType.PLAYLIST -> "$host/playlist?list=$cleanYtId"
            else -> "$host/channel/$cleanYtId"
        }

        return url
    }

    companion object {
        const val YOUTUBE_FRONTEND_URL = "https://www.youtube.com"
        const val YOUTUBE_MUSIC_URL = "https://music.youtube.com"
        const val YOUTUBE_SHORT_URL = "https://youtu.be"
    }
}
