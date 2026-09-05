package app.libre.helpers

import android.content.Context
import android.content.Intent
import app.libre.R
import app.libre.enums.ShareObjectType
import app.libre.extensions.toID

object ShareHelper {
    const val YOUTUBE_FRONTEND_URL = "https://www.youtube.com"
    const val YOUTUBE_MUSIC_URL = "https://music.youtube.com"
    const val YOUTUBE_SHORT_URL = "https://youtu.be"

    /**
     * Resolves the authentic, source-aware share URL for a given ID and object type.
     */
    fun getShareUrl(
        id: String,
        source: String? = null,
        shareObjectType: ShareObjectType = ShareObjectType.VIDEO
    ): String {
        val isJioSaavn = JioSaavnHelper.isJioSaavn(id, false)
        if (isJioSaavn) {
            val cleanId = id.removePrefix("jsa_song_").removePrefix("jsa_album_").removePrefix("jsa_playlist_")
            val parts = cleanId.split("_")
            val token = parts.getOrNull(1) ?: parts[0]
            return when {
                id.startsWith("jsa_album_") -> "https://www.jiosaavn.com/album/album/$token"
                id.startsWith("jsa_playlist_") -> "https://www.jiosaavn.com/featured/playlist/$token"
                shareObjectType == ShareObjectType.CHANNEL -> "https://www.jiosaavn.com/artist/artist/$token"
                else -> "https://www.jiosaavn.com/song/track/$token"
            }
        }

        val cleanYtId = id.toID()
        val isYtm = source == "ytm" ||
                id.contains("music.youtube.com") ||
                id.contains("/album/") ||
                cleanYtId.startsWith("OLAK") ||
                cleanYtId.startsWith("MPRE")

        return when (shareObjectType) {
            ShareObjectType.CHANNEL -> "$YOUTUBE_FRONTEND_URL/channel/$cleanYtId"
            ShareObjectType.PLAYLIST -> {
                val host = if (isYtm) YOUTUBE_MUSIC_URL else YOUTUBE_FRONTEND_URL
                "$host/playlist?list=$cleanYtId"
            }
            ShareObjectType.VIDEO -> {
                if (isYtm) "$YOUTUBE_MUSIC_URL/watch?v=$cleanYtId"
                else "$YOUTUBE_SHORT_URL/$cleanYtId"
            }
        }
    }

    /**
     * Directly launches the native Android system share chooser with the resolved URL.
     */
    fun share(
        context: Context,
        id: String,
        title: String? = null,
        source: String? = null,
        shareObjectType: ShareObjectType = ShareObjectType.VIDEO
    ) {
        val shareUrl = getShareUrl(id, source, shareObjectType)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, shareUrl)
            if (!title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
            type = "text/plain"
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.shareTo)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * Directly copies the authentic URL to clipboard with user notification toast.
     */
    fun copyLink(
        context: Context,
        id: String,
        source: String? = null,
        shareObjectType: ShareObjectType = ShareObjectType.VIDEO
    ) {
        val shareUrl = getShareUrl(id, source, shareObjectType)
        ClipboardHelper.save(context = context, text = shareUrl, notify = true)
    }
}
