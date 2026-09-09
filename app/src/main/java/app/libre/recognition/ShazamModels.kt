package app.libre.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShazamRequestJson(
    @SerialName("geolocation")
    val geolocation: Geolocation,
    @SerialName("signature")
    val signature: Signature,
    @SerialName("timestamp")
    val timestamp: Long,
    @SerialName("timezone")
    val timezone: String
) {
    @Serializable
    data class Geolocation(
        @SerialName("altitude")
        val altitude: Double,
        @SerialName("latitude")
        val latitude: Double,
        @SerialName("longitude")
        val longitude: Double
    )

    @Serializable
    data class Signature(
        @SerialName("samplems")
        val samplems: Long,
        @SerialName("timestamp")
        val timestamp: Long,
        @SerialName("uri")
        val uri: String
    )
}

@Serializable
data class ShazamResponseJson(
    @SerialName("matches")
    val matches: List<Match?>? = null,
    @SerialName("track")
    val track: Track? = null,
    @SerialName("tagid")
    val tagid: String? = null
) {
    @Serializable
    data class Match(
        @SerialName("id")
        val id: String? = null,
        @SerialName("offset")
        val offset: Double? = null,
        @SerialName("timeskew")
        val timeskew: Double? = null,
        @SerialName("frequencyskew")
        val frequencyskew: Double? = null
    )

    @Serializable
    data class Track(
        @SerialName("key")
        val key: String? = null,
        @SerialName("title")
        val title: String? = null,
        @SerialName("subtitle")
        val subtitle: String? = null,
        @SerialName("images")
        val images: Images? = null,
        @SerialName("share")
        val share: Share? = null,
        @SerialName("url")
        val url: String? = null,
        @SerialName("isrc")
        val isrc: String? = null,
        @SerialName("genres")
        val genres: Genres? = null,
        @SerialName("sections")
        val sections: List<Section?>? = null
    ) {
        @Serializable
        data class Images(
            @SerialName("background")
            val background: String? = null,
            @SerialName("coverart")
            val coverart: String? = null,
            @SerialName("coverarthq")
            val coverarthq: String? = null
        )

        @Serializable
        data class Share(
            @SerialName("subject")
            val subject: String? = null,
            @SerialName("text")
            val text: String? = null,
            @SerialName("href")
            val href: String? = null,
            @SerialName("image")
            val image: String? = null
        )

        @Serializable
        data class Genres(
            @SerialName("primary")
            val primary: String? = null
        )

        @Serializable
        data class Section(
            @SerialName("type")
            val type: String? = null,
            @SerialName("tabname")
            val tabname: String? = null,
            @SerialName("metadata")
            val metadata: List<Metadata?>? = null,
            @SerialName("text")
            val text: List<String>? = null
        ) {
            @Serializable
            data class Metadata(
                @SerialName("title")
                val title: String? = null,
                @SerialName("text")
                val text: String? = null
            )
        }
    }
}

data class RecognitionResult(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtUrl: String?,
    val coverArtHqUrl: String?,
    val genre: String?,
    val releaseDate: String?,
    val label: String?,
    val lyrics: List<String>?,
    val shazamUrl: String?,
    val appleMusicUrl: String?,
    val isrc: String?,
    val localFilePath: String? = null
)

sealed class RecognitionStatus {
    data object Ready : RecognitionStatus()
    data object Listening : RecognitionStatus()
    data object Processing : RecognitionStatus()
    data class Success(val result: RecognitionResult) : RecognitionStatus()
    data class NoMatch(val message: String = "No matches found. Try again with clearer audio.") : RecognitionStatus()
    data class Error(val message: String) : RecognitionStatus()
}
