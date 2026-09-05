package app.libre.api.obj

import android.os.Parcelable
import app.libre.enums.FileType
import app.libre.extensions.toLocalDate
import app.libre.json.SafeInstantSerializer
import kotlinx.datetime.Instant
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class Streams(
    var title: String,
    val description: String = "",

    @Serializable(SafeInstantSerializer::class)
    @SerialName("uploadDate")
    @IgnoredOnParcel
    val uploadTimestamp: Instant? = null,
    val uploaded: Long? = null,

    val uploader: String = "",
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    /** Real artist name (from local file tags or YT Music API), if different from [uploader]. */
    val artist: String? = null,
    var thumbnailUrl: String = "",
    val category: String = "",
    val license: String = "YouTube licence",
    val visibility: String = "public",
    val tags: List<String> = emptyList(),
    val metaInfo: List<MetaInfo> = emptyList(),
    val hls: String? = null,
    val dash: String? = null,
    val uploaderVerified: Boolean = false,
    val duration: Long = 0,
    val views: Long = 0,
    val likes: Long = 0,
    val dislikes: Long = 0,
    val audioStreams: List<MediaStream> = emptyList(),
    val videoStreams: List<MediaStream> = emptyList(),
    var relatedStreams: List<StreamItem> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val livestream: Boolean = false,
    val proxyUrl: String? = null,
    val chapters: List<ChapterSegment> = emptyList(),
    val uploaderSubscriberCount: Long = 0,
    val previewFrames: List<PreviewFrames> = emptyList(),
    var isShort: Boolean = false,
    val hasVideo: Boolean = false,
    val serverAbrStreamingUrl: String? = null,
    val videoPlaybackUstreamerConfig: String? = null
): Parcelable {
    @IgnoredOnParcel
    val isLive = livestream || duration <= 0



    fun toStreamItem(videoId: String): StreamItem {
        return StreamItem(
            url = videoId,
            title = title,
            thumbnail = thumbnailUrl,
            uploaderName = uploader,
            uploaderUrl = uploaderUrl,
            uploaderAvatar = uploaderAvatar,
            uploadedDate = uploadTimestamp?.toLocalDate()?.toString(),
            uploaded = uploaded ?: uploadTimestamp?.toEpochMilliseconds() ?: 0,
            duration = duration,
            views = views,
            uploaderVerified = uploaderVerified,
            shortDescription = description
        )
    }

    companion object {
        const val CATEGORY_MUSIC = "Music"
    }
}
