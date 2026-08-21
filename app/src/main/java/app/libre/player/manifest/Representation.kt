package app.libre.player.manifest

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import app.libre.api.obj.MediaStream
import misc.Common.FormatId

/** A Sabr representation.  */
@UnstableApi
data class Representation(
    /** The format of the representation.  */
    val format: Format,
    /** Metadata about the stream.  */
    val stream: MediaStream,
) {
    fun formatId(): FormatId = FormatId.newBuilder()
        .setItag(stream.itag!!)
        .setLastModified(stream.lastModified!!)
        .setXtags(stream.xtags ?: "")
        .build()
}
