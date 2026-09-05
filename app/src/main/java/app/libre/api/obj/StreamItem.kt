package app.libre.api.obj

import android.os.Parcelable
import app.libre.db.obj.LocalPlaylistItem
import app.libre.extensions.toID
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class StreamItem(
    val url: String? = null,
    val type: String? = null,
    var title: String? = null,
    var thumbnail: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val uploadedDate: String? = null,
    val duration: Long? = null,
    val views: Long? = null,
    val uploaderVerified: Boolean? = null,
    val uploaded: Long = 0,
    val shortDescription: String? = null,
    val isShort: Boolean = false,
    val albumName: String? = null,
    val albumId: String? = null,
    val source: String? = null
) : Parcelable {
    val isLive get() = !isShort && ((duration == null) || (duration <= 0L))
    val isUpcoming get() = uploaded > System.currentTimeMillis()

    fun toContentItem() = ContentItem(
        url = url.orEmpty(),
        type = type ?: TYPE_STREAM,
        thumbnail = thumbnail.orEmpty(),
        title = title,
        name = title,
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl,
        uploaderAvatar = uploaderAvatar,
        duration = duration ?: -1L,
        views = views ?: -1L,
        isShort = isShort,
        uploaderVerified = uploaderVerified,
        uploaded = uploaded,
        shortDescription = shortDescription,
        albumName = albumName,
        albumId = albumId,
        source = source ?: "youtube"
    )

    fun toLocalPlaylistItem(playlistId: String): LocalPlaylistItem {
        val cleanVideoId = (url ?: "").toID().ifEmpty { url.orEmpty() }
        return LocalPlaylistItem(
            playlistId = playlistId.toIntOrNull() ?: 0,
            videoId = cleanVideoId,
            title = title,
            uploader = uploaderName,
            thumbnailUrl = thumbnail,
            duration = duration,
            albumName = albumName
        )
    }

    companion object {
        const val TYPE_STREAM = "stream"
        const val TYPE_CHANNEL = "channel"
        const val TYPE_PLAYLIST = "playlist"
    }
}
