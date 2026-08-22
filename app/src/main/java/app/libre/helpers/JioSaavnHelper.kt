package app.libre.helpers

import android.widget.ImageView
import androidx.core.view.isVisible
import app.libre.api.obj.Streams
import app.libre.constants.PreferenceKeys
import app.libre.databinding.CustomExoPlayerViewTemplateBinding
import app.libre.extensions.toID

object JioSaavnHelper {
    
    fun isJioSaavn(videoId: String?, isOffline: Boolean = false): Boolean {
        if (videoId == null || isOffline) return false
        val trimmed = videoId.trim()
        return trimmed.startsWith("jsa_") || trimmed.startsWith("jsa:") || trimmed.contains("jiosaavn.com")
    }

    fun setupAudioOnlyThumbnail(playerBackgroundBinding: CustomExoPlayerViewTemplateBinding, streams: Streams) {
        if (streams.videoStreams.isEmpty()) {
            playerBackgroundBinding.exoArtwork.scaleType = ImageView.ScaleType.CENTER_CROP
            playerBackgroundBinding.exoArtwork.isVisible = true
            playerBackgroundBinding.exoShutter.isVisible = false
            ImageHelper.loadImage(streams.thumbnailUrl, playerBackgroundBinding.exoArtwork)
        } else {
            playerBackgroundBinding.exoArtwork.isVisible = false
            playerBackgroundBinding.exoShutter.isVisible = true
        }
    }

    fun resetPlayerDefaults(playerBackgroundBinding: CustomExoPlayerViewTemplateBinding) {
        playerBackgroundBinding.exoArtwork.isVisible = false
        playerBackgroundBinding.exoShutter.isVisible = true
    }

}
