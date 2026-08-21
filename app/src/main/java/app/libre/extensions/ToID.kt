package app.libre.extensions

import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_FRONTEND_URL
import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_MUSIC_URL
import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_SHORT_URL

/**
 * format a full YouTube url or a path to a video/channel/playlist ID
 */
fun String.toID(): String {
    return this
        // remove any youtube origins from urls
        .removePrefix(YOUTUBE_FRONTEND_URL)
        .removePrefix(YOUTUBE_MUSIC_URL)
        .removePrefix(YOUTUBE_SHORT_URL)
        .replace("/watch?v=", "") // videos
        .replace("/channel/", "") // channels
        .replace("/playlist?list=", "") // playlists
        .replace("/album/", "") // albums
        .replace("/watch/", "") // JioSaavn watch/song paths
        // channel urls for different categories than the main one
        .removeSuffix("/shorts")
        .removeSuffix("/streams")
        .removeSuffix("/videos")
}
