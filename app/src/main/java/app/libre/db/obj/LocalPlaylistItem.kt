package app.libre.db.obj

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.libre.api.obj.StreamItem
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["videoId"])
    ]
)
data class LocalPlaylistItem(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    @ColumnInfo var playlistId: Int = 0,
    @ColumnInfo val videoId: String = "",
    @ColumnInfo val title: String? = null,
    @ColumnInfo val uploader: String? = null,
    @ColumnInfo val thumbnailUrl: String? = null,
    @ColumnInfo val duration: Long? = null,
    @ColumnInfo val albumName: String? = null
) {
    fun toStreamItem(): StreamItem {
        return StreamItem(
            url = videoId,
            title = title,
            thumbnail = thumbnailUrl,
            uploaderName = uploader,
            duration = duration,
            albumName = albumName
        )
    }
}
