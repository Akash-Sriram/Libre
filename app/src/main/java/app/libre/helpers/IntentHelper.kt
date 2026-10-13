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

        // Custom libre scheme or text search: libre://search?q=...
        if (uri.scheme == "libre" || host == "search") {
            val query = uri.getQueryParameter("q") ?: uri.getQueryParameter("query")
            if (!query.isNullOrBlank()) {
                putExtra(IntentData.query, query)
            }
            return@with this
        }

        // Google Search / Sound Search results URL redirection (e.g. https://www.google.com/search?q=...)
        if (host.contains("google.") || host.contains("googlequicksearchbox") || host == "g.co") {
            val query = uri.getQueryParameter("q") ?: uri.getQueryParameter("query")
            if (!query.isNullOrBlank()) {
                putExtra(IntentData.query, query)
                return@with this
            }
        }

        if (host.contains("jiosaavn.com")) {
            val type = pathSegments.firstOrNull { it == "album" || it == "featured" || it == "playlist" || it == "song" }
            val id = pathSegments.lastOrNull { it.isNotBlank() }
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
        val listParam = uri.getQueryParameter("list")

        // 1. Explicit playlist or album query parameter (?list=OLAK..., ?list=PL..., ?list=MPRE...)
        if (!listParam.isNullOrBlank()) {
            if (listParam.startsWith("RD")) {
                val baseVideoId = listParam.removePrefix("RD").takeIf { it.length == 11 }
                putExtra(IntentData.videoId, baseVideoId)
                putExtra(IntentData.playlistId, listParam)
            } else {
                putExtra(IntentData.playlistId, listParam)
            }
            return@with this
        }

        // 2. YouTube Music album browse links (/browse/MPREb_..., /album/MPREb_..., /browse/OLAK...)
        if (secondLastSegment == "browse" || secondLastSegment == "album" ||
            lastSegment?.startsWith("MPREb_") == true || lastSegment?.startsWith("OLAK") == true || lastSegment?.startsWith("VLOLAK") == true) {
            val albumId = lastSegment
            if (!albumId.isNullOrBlank()) {
                putExtra(IntentData.playlistId, albumId)
                return@with this
            }
        }

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
            lastSegment == "watch_videos" -> {
                putExtra(IntentData.playlistName, uri.getQueryParameter("title"))
                val videoIds = uri.getQueryParameter("video_ids")?.split(",")
                putExtra(IntentData.videoIds, videoIds?.toTypedArray())
            }
            else -> {
                val id = if (lastSegment == "watch") uri.getQueryParameter("v") else lastSegment
                putExtra(IntentData.videoId, id)
                putExtra(IntentData.timeStamp, uri.getQueryParameter("t")?.toTimeInSeconds())
            }
        }
        this
    }
}
