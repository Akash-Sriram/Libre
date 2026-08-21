package app.libre.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class JioSaavnOfficialMoreInfo(
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String? = null,
    val album: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    val duration: String? = null,
    val music: String? = null
)

@Serializable
data class JioSaavnOfficialSong(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val image: String? = null,
    val year: String? = null,
    val album: String? = null,
    val albumid: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    val music: String? = null,
    @SerialName("more_info") val moreInfo: JioSaavnOfficialMoreInfo? = null,
    val perma_url: String? = null
)

@Serializable
data class JioSaavnOfficialSearchResponse(
    val results: List<JioSaavnOfficialSong>? = null
)

@Serializable
data class JioSaavnOfficialAlbumItem(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val image: String? = null,
    val music: String? = null,
    val artist: String? = null,
    @SerialName("primary_artists") val primaryArtists: String? = null,
    val year: String? = null,
    val perma_url: String? = null
)

@Serializable
data class JioSaavnOfficialSearchAlbumsResponse(
    val results: List<JioSaavnOfficialAlbumItem>? = null
)

@Serializable
data class JioSaavnOfficialPlaylistItem(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val image: String? = null,
    val firstname: String? = null,
    val followerCount: String? = null,
    val songCount: String? = null,
    val perma_url: String? = null
)

@Serializable
data class JioSaavnOfficialSearchPlaylistsResponse(
    val results: List<JioSaavnOfficialPlaylistItem>? = null
)

@Serializable
data class JioSaavnOfficialArtistItem(
    val id: String = "",
    val name: String? = null,
    val title: String? = null,
    val image: String? = null,
    val role: String? = null,
    val followerCount: String? = null,
    val perma_url: String? = null
)

@Serializable
data class JioSaavnOfficialSearchArtistsResponse(
    val results: List<JioSaavnOfficialArtistItem>? = null
)

@Serializable
data class JioSaavnOfficialAlbumResponse(
    val id: String? = null,
    val title: String? = null,
    val name: String? = null,
    val image: String? = null,
    val year: String? = null,
    val artist: String? = null,
    @SerialName("primary_artists") val primaryArtists: String? = null,
    @SerialName("list") val songs: List<JioSaavnOfficialSong>? = null
)

@Serializable
data class JioSaavnOfficialPlaylistResponse(
    val id: String? = null,
    val title: String? = null,
    val name: String? = null,
    val image: String? = null,
    val list: List<JioSaavnOfficialSong>? = null
)

interface JioSaavnApi {
    @GET("api.php")
    suspend fun searchSongs(
        @Query("__call") call: String = "search.getResults",
        @Query("q") query: String,
        @Query("n") limit: Int = 30,
        @Query("p") page: Int = 1,
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialSearchResponse

    @GET("api.php")
    suspend fun searchAlbums(
        @Query("__call") call: String = "search.getAlbumResults",
        @Query("q") query: String,
        @Query("n") limit: Int = 30,
        @Query("p") page: Int = 1,
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialSearchAlbumsResponse

    @GET("api.php")
    suspend fun searchPlaylists(
        @Query("__call") call: String = "search.getPlaylistResults",
        @Query("q") query: String,
        @Query("n") limit: Int = 30,
        @Query("p") page: Int = 1,
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialSearchPlaylistsResponse

    @GET("api.php")
    suspend fun searchArtists(
        @Query("__call") call: String = "search.getArtistResults",
        @Query("q") query: String,
        @Query("n") limit: Int = 30,
        @Query("p") page: Int = 1,
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialSearchArtistsResponse

    @GET("api.php")
    suspend fun getSongDetails(
        @Query("__call") call: String = "song.getDetails",
        @Query("pids") pids: String,
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): Map<String, JioSaavnOfficialSong>

    @GET("api.php")
    suspend fun getAlbumDetails(
        @Query("__call") call: String = "content.getAlbumDetails",
        @Query("albumid") albumId: String,
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialAlbumResponse

    @GET("api.php")
    suspend fun getPlaylistDetails(
        @Query("__call") call: String = "playlist.getDetails",
        @Query("listid") listId: String,
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialPlaylistResponse

    @GET("api.php")
    suspend fun getAlbumDetailsByToken(
        @Query("__call") call: String = "webapi.get",
        @Query("token") token: String,
        @Query("type") type: String = "album",
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialAlbumResponse

    @GET("api.php")
    suspend fun getPlaylistDetailsByToken(
        @Query("__call") call: String = "webapi.get",
        @Query("token") token: String,
        @Query("type") type: String = "playlist",
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): JioSaavnOfficialPlaylistResponse

    @GET("api.php")
    suspend fun getSongDetailsByToken(
        @Query("__call") call: String = "webapi.get",
        @Query("token") token: String,
        @Query("type") type: String = "song",
        @Query("_format") format: String = "json",
        @Query("_marker") marker: String = "0",
        @Query("api_version") apiVersion: String = "4",
        @Query("ctx") ctx: String = "web6s"
    ): Map<String, JioSaavnOfficialSong>
}
