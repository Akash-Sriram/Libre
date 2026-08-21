package app.libre.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

interface ExternalApi {
    @Headers(
        "User-Agent: $USER_AGENT",
        "Accept: application/json",
        "Content-Type: application/json+protobuf",
        "x-goog-api-key: $GOOGLE_API_KEY",
        "x-user-agent: grpc-web-javascript/0.1",
    )
    @POST
    suspend fun botguardRequest(
        @Url url: String,
        @Body jsonPayload: List<String>
    ): JsonElement
}
