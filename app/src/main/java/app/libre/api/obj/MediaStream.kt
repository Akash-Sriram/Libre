package app.libre.api.obj

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class MediaStream(
    var url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val videoOnly: Boolean? = null,
    val bitrate: Int? = null,
    val initStart: Int? = null,
    val initEnd: Int? = null,
    val indexStart: Int? = null,
    val indexEnd: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val durationMs: Long? = null,
    val audioTrackName: String? = null,
    val audioTrackId: String? = null,
    val contentLength: Long = -1,
    val audioTrackType: String? = null,
    val audioTrackLocale: String? = null,
    val itag: Int? = null,
    val lastModified: Long? = null,
    val isDrc: Boolean? = null,
    val xtags: String? = null,
): Parcelable

