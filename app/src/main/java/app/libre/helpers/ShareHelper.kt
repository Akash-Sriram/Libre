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
        val effectiveId = if (id.startsWith("/")) {
            LocalAudioMatcher.getVideoId(id) ?: id
        } else {
            id
        }

        val isJioSaavn = JioSaavnHelper.isJioSaavn(effectiveId, false)
        if (isJioSaavn) {
            val cleanId = effectiveId.removePrefix("jsa_song_").removePrefix("jsa_album_").removePrefix("jsa_playlist_").removePrefix("jsa:")
            val parts = cleanId.split("_")
            val token = parts.getOrNull(1) ?: parts[0]
            return when {
                effectiveId.startsWith("jsa_album_") -> "https://www.jiosaavn.com/album/album/$token"
                effectiveId.startsWith("jsa_playlist_") -> "https://www.jiosaavn.com/featured/playlist/$token"
                shareObjectType == ShareObjectType.CHANNEL -> "https://www.jiosaavn.com/artist/artist/$token"
                else -> "https://www.jiosaavn.com/song/track/$token"
            }
        }

        val cleanYtId = effectiveId.toID()
        if (cleanYtId.startsWith("/")) {
            // Strictly local file with no online mapping
            return ""
        }

        val isYtm = source == "ytm" ||
                effectiveId.contains("music.youtube.com") ||
                effectiveId.contains("/album/") ||
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
        val textToShare = shareUrl.ifEmpty {
            title ?: java.io.File(id).nameWithoutExtension
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, textToShare)
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
        val textToCopy = shareUrl.ifEmpty {
            java.io.File(id).nameWithoutExtension
        }
        ClipboardHelper.save(context = context, text = textToCopy, notify = true)
    }
}
