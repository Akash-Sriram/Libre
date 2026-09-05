package app.libre.extensions

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.libre.api.JsonHelper
import app.libre.api.obj.Streams
import app.libre.constants.IntentData

@OptIn(UnstableApi::class)
fun MediaItem.Builder.setMetadata(streams: Streams, videoId: String) = apply {
    // Avoid reaching the max parcelable size of 1MB for binder transactions.
    val clearedStreams = streams.copy(
        audioStreams = emptyList(),
        videoStreams = emptyList(),
        hasVideo = streams.hasVideo || streams.videoStreams.isNotEmpty()
    )
    val extras = Bundle().apply {
        putString(MediaMetadataCompat.METADATA_KEY_TITLE, streams.title)
        putString(MediaMetadataCompat.METADATA_KEY_ARTIST, streams.artist ?: streams.uploader)
        putString(IntentData.videoId, videoId)
        // JSON-encode as work-around for https://github.com/androidx/media/issues/564
        putString(IntentData.streams, JsonHelper.json.encodeToString(clearedStreams))
        putString(IntentData.chapters, JsonHelper.json.encodeToString(streams.chapters))
    }
    setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(streams.title)
            .setArtist(streams.artist ?: streams.uploader)
            .setDurationMs(streams.duration.times(1000))
            .setArtworkUri(streams.thumbnailUrl.toUri())
            .setComposer(streams.uploaderUrl.orEmpty().toID())
            .setExtras(extras)
            // send a unique timestamp to notify that the metadata changed, even if playing the same video twice
            .setTrackNumber(System.currentTimeMillis().mod(Int.MAX_VALUE))
            .build()
    )
}
