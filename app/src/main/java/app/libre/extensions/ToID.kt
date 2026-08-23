package app.libre.extensions

import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_FRONTEND_URL
import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_MUSIC_URL
import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_SHORT_URL

/**
 * Formats a full YouTube / JioSaavn URL or path to a clean video/channel/playlist ID.
 * Handles youtube.com, youtu.be, music.youtube.com, query params (?si=, &t=), and bare IDs.
 */
fun String.toID(): String {
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return ""

    // 1. YouTube Playlist ID from URL (e.g. ?list=PL..., &list=OLAK5uy_..., /playlist?list=...)
    val playlistMatch = Regex("""[?&]list=([a-zA-Z0-9_-]+)""").find(trimmed)
    if (playlistMatch != null) {
        return playlistMatch.groupValues[1]
    }

    // 2. YouTube 11-character regex from any YouTube URL format (watch?v=, youtu.be/, shorts/)
    val ytMatch = Regex("""(?:(?:https?://)?(?:www\.|music\.|m\.)?youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|v/|embed/)|(?:https?://)?youtu\.be/)([a-zA-Z0-9_-]{11})""").find(trimmed)
    if (ytMatch != null) {
        return ytMatch.groupValues[1]
    }

    // 3. JioSaavn Track, Album or Playlist URL
    val jsaSongMatch = Regex("""jiosaavn\.com/song/[^/\s]+/([A-Za-z0-9_-]+)""").find(trimmed)
    if (jsaSongMatch != null) {
        return "jsa:${jsaSongMatch.groupValues[1]}"
    }

    val jsaAlbumMatch = Regex("""jiosaavn\.com/album/[^/\s]+/([A-Za-z0-9_-]+)""").find(trimmed)
    if (jsaAlbumMatch != null) {
        return "jsa_album_${jsaAlbumMatch.groupValues[1]}"
    }

    val jsaPlaylistMatch = Regex("""jiosaavn\.com/featured/[^/\s]+/([A-Za-z0-9_-]+)""").find(trimmed)
    if (jsaPlaylistMatch != null) {
        return "jsa_playlist_${jsaPlaylistMatch.groupValues[1]}"
    }

    // 4. Prefix cleanup for YTM browse/playlist prefixes (VL, etc.)
    if (trimmed.startsWith("VL") && trimmed.length > 2) {
        return trimmed.removePrefix("VL")
    }

    // 5. Fallback cleanup for channels, paths, local paths
    return trimmed
        .removePrefix("https://www.youtube.com")
        .removePrefix("https://youtube.com")
        .removePrefix("http://www.youtube.com")
        .removePrefix("http://youtube.com")
        .removePrefix("https://music.youtube.com")
        .removePrefix("http://music.youtube.com")
        .removePrefix("https://youtu.be")
        .removePrefix("http://youtu.be")
        .removePrefix("/")
        .replace("/watch?v=", "")
        .replace("watch?v=", "")
        .replace("/channel/", "")
        .replace("/playlist?list=", "")
        .replace("/album/", "")
        .replace("/watch/", "")
        .removeSuffix("/shorts")
        .removeSuffix("/streams")
        .removeSuffix("/videos")
        .removePrefix("/")
}
