package app.libre.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import app.libre.R
import app.libre.constants.IntentData
import app.libre.extensions.toastFromMainThread
import app.libre.ui.sheets.IntentChooserSheet
import app.libre.util.TextUtils.toTimeInSeconds

object IntentHelper {
    private fun getResolveIntent(link: String) = Intent(Intent.ACTION_VIEW)
        .setData(link.toUri())
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun getResolveInfo(context: Context, link: String): List<ResolveInfo> {
        val intent = getResolveIntent(link)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager
                .queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
        } else {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
    }

    fun openLinkFromHref(context: Context, fragmentManager: FragmentManager, link: String, forceDefaultOpen: Boolean = false) {
        val resolveInfoList = getResolveInfo(context, link)

        if (resolveInfoList.isEmpty() || forceDefaultOpen) {
            try {
                context.startActivity(getResolveIntent(link))
            } catch (e: Exception) {
                context.toastFromMainThread(R.string.error)
            }
        } else {
            IntentChooserSheet()
                .apply { arguments = Bundle().apply { putString(IntentData.url, link) } }
                .show(fragmentManager)
        }
    }

    fun resolveType(uri: Uri) = resolveType(Intent(), uri)

    /**
     * Resolve the uri and return a bundle with the arguments
     */
    fun resolveType(intent: Intent, uri: Uri) = with(intent) {
        val host = uri.host.orEmpty()
        val pathSegments = uri.pathSegments
        val lastSegment = uri.lastPathSegment

        if (host.contains("jiosaavn.com")) {
            // Examples:
            // https://www.jiosaavn.com/album/kanchana/TODtx4wo8yU_
            // https://www.jiosaavn.com/song/some-song-name/someId
            // https://www.jiosaavn.com/featured/some-playlist-name/someId
            val type = pathSegments.getOrNull(0)
            val id = lastSegment
            if (id != null) {
                when (type) {
                    "album" -> putExtra(IntentData.playlistId, "jsa_album_$id")
                    "featured", "playlist" -> putExtra(IntentData.playlistId, "jsa_playlist_$id")
                    "song" -> putExtra(IntentData.videoId, "jsa_song_$id")
                }
            }
            return@with this
        }

        val secondLastSegment = pathSegments.getOrNull(pathSegments.size - 2)
        when {
            lastSegment == "results" -> {
                putExtra(IntentData.query, uri.getQueryParameter("search_query"))
            }
            secondLastSegment == "channel" -> {
                putExtra(IntentData.channelId, lastSegment)
            }
            secondLastSegment == "c" || secondLastSegment == "user" -> {
                putExtra(IntentData.channelName, lastSegment)
            }
            lastSegment == "playlist" -> {
                val listId = uri.getQueryParameter("list")
                if (listId?.startsWith("RD") == true) {
                    val baseVideoId = listId.removePrefix("RD").takeIf { it.length == 11 }
                    putExtra(IntentData.videoId, baseVideoId)
                    putExtra(IntentData.playlistId, listId)
                } else {
                    putExtra(IntentData.playlistId, listId)
                }
            }
            lastSegment == "watch_videos" -> {
                putExtra(IntentData.playlistName, uri.getQueryParameter("title"))
                val videoIds = uri.getQueryParameter("video_ids")?.split(",")
                putExtra(IntentData.videoIds, videoIds?.toTypedArray())
            }
            else -> {
                val id = if (lastSegment == "watch") uri.getQueryParameter("v") else lastSegment
                val listId = uri.getQueryParameter("list")
                putExtra(IntentData.videoId, id)
                if (listId != null) {
                    putExtra(IntentData.playlistId, listId)
                }
                putExtra(IntentData.timeStamp, uri.getQueryParameter("t")?.toTimeInSeconds())
            }
        }
        this
    }
}
