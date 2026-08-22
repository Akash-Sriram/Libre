package app.libre.util

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/**
 * [DefaultTrackSelector] that automatically chooses the audio quality based on
 * the current preference set in [PreferenceHelper]
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DefaultTrackSelectorWithAudioQualitySupport
    (context: Context) :
    DefaultTrackSelector(context) {
    override fun selectAudioTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<out Array<out IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters
    ): android.util.Pair<ExoTrackSelection.Definition, Int>? {
        return super.selectAudioTrack(
            mappedTrackInfo,
            rendererFormatSupports,
            rendererMixedMimeTypeAdaptationSupports,
            params
        )
    }
}
