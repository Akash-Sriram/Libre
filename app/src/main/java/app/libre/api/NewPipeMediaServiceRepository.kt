package app.libre.api

import android.util.Base64
import app.libre.api.obj.Channel
import app.libre.api.obj.ChannelTab
import app.libre.api.obj.ChannelTabResponse
import app.libre.api.obj.ChapterSegment
import app.libre.api.obj.Comment
import app.libre.api.obj.CommentsPage
import app.libre.api.obj.ContentItem
import app.libre.api.obj.MetaInfo
import app.libre.api.obj.MediaStream
import app.libre.api.obj.Playlist
import app.libre.api.obj.PreviewFrames
import app.libre.api.obj.SearchResult
import app.libre.api.obj.StreamItem
import app.libre.api.obj.StreamItem.Companion.TYPE_CHANNEL
import app.libre.api.obj.StreamItem.Companion.TYPE_PLAYLIST
import app.libre.api.obj.StreamItem.Companion.TYPE_STREAM
import app.libre.api.obj.Streams
import app.libre.api.obj.Subtitle
import app.libre.api.poToken.PoTokenGenerator
import app.libre.extensions.sha256Sum
import app.libre.extensions.toID
import app.libre.helpers.NewPipeExtractorInstance
import app.libre.helpers.PlayerHelper
import app.libre.ui.dialogs.ShareDialog.Companion.YOUTUBE_FRONTEND_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import kotlin.time.toKotlinInstant


private fun VideoStream.toMediaStream() = MediaStream(
    url = content,
    codec = codec,
    format = format?.toString(),
    height = height,
    width = width,
    quality = getResolution(),
    mimeType = format?.mimeType,
    bitrate = bitrate,
    initStart = initStart,
    initEnd = initEnd,
    indexStart = indexStart,
    indexEnd = indexEnd,
    fps = fps,
    durationMs = itagItem?.approxDurationMs,
    contentLength = itagItem?.contentLength ?: 0L,
    itag = itagItem?.id,
    lastModified = itagItem?.lastModified,
    xtags = itagItem?.xtags,
)

private fun AudioStream.toMediaStream() = MediaStream(
    url = content,
    format = format?.toString(),
    quality = "$averageBitrate bits",
    bitrate = bitrate,
    mimeType = format?.mimeType,
    initStart = initStart,
    initEnd = initEnd,
    indexStart = indexStart,
    indexEnd = indexEnd,
    durationMs = itagItem?.approxDurationMs,
    contentLength = itagItem?.contentLength ?: 0L,
    codec = codec,
    audioTrackId = audioTrackId,
    audioTrackName = audioTrackName,
    audioTrackLocale = audioLocale?.toLanguageTag(),
    audioTrackType = audioTrackType?.name,
    videoOnly = false,
    itag = itagItem?.id,
    lastModified = itagItem?.lastModified,
    isDrc = itagItem?.isDrc,
    xtags = itagItem?.xtags,
)

fun StreamInfoItem.toStreamItem(
    uploaderAvatarUrl: String? = null,
    feedInfo: StreamInfoItem? = null,
): StreamItem {
    val uploadDate = uploadDate ?: feedInfo?.uploadDate
    val textualUploadDate = textualUploadDate ?: feedInfo?.textualUploadDate

    return StreamItem(
        type = TYPE_STREAM,
        url = url.toID(),
        // if available prefer the RSS feed title, since it's untranslated
        title = feedInfo?.name ?: name,
        uploaded = uploadDate?.offsetDateTime()?.toEpochSecond()?.times(1000) ?: -1,
        uploadedDate = textualUploadDate ?: uploadDate?.offsetDateTime()?.toLocalDateTime()
            ?.toLocalDate()
            ?.toString(),
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl?.toID(),
        uploaderAvatar = uploaderAvatarUrl ?: uploaderAvatars.maxByOrNull { it.height }?.url,
        thumbnail = thumbnails.maxByOrNull { it.height }?.url,
        duration = duration,
        views = viewCount,
        uploaderVerified = isUploaderVerified,
        shortDescription = shortDescription,
        isShort = isShortFormContent
    )
}

fun InfoItem.toContentItem(source: String = "youtube") = when (this) {
    is StreamInfoItem -> if (contentAvailability in arrayOf(
            ContentAvailability.AVAILABLE,
            ContentAvailability.UPCOMING,
            ContentAvailability.UNKNOWN
        )
    ) ContentItem(
        url = url.toID(),
        type = TYPE_STREAM,
        thumbnail = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
        title = name,
        uploaderAvatar = uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty(),
        uploaderUrl = uploaderUrl.toID(),
        uploaderName = uploaderName,
        uploaded = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: -1,
        isShort = isShortFormContent,
        views = viewCount,
        shortDescription = shortDescription,
        verified = isUploaderVerified,
        duration = duration,
        source = source
    ) else null

    is ChannelInfoItem -> ContentItem(
        url = url.toID(),
        name = name,
        type = TYPE_CHANNEL,
        thumbnail = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
        subscribers = subscriberCount,
        videos = streamCount,
        source = source
    )

    is PlaylistInfoItem -> ContentItem(
        url = url.toID(),
        type = TYPE_PLAYLIST,
        title = name,
        name = name,
        shortDescription = description.content,
        thumbnail = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
        videos = streamCount,
        uploaderVerified = isUploaderVerified,
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl?.toID(),
        source = source
    )

    else -> null
}

fun ChannelInfo.toChannel() = Channel(
    id = id,
    name = name,
    description = description,
    verified = isVerified,
    avatarUrl = avatars.maxByOrNull { it.height }?.url,
    bannerUrl = banners.maxByOrNull { it.height }?.url,
    tabs = tabs.filterNot { it.contentFilters.contains(ChannelTabs.VIDEOS) }
        .map { ChannelTab(it.contentFilters.first().lowercase(), it.toTabDataString()) },
    subscriberCount = subscriberCount
)

fun PlaylistInfo.toPlaylist() = Playlist(
    name = name,
    description = description?.content,
    thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
    uploaderUrl = uploaderUrl.toID(),
    bannerUrl = banners.maxByOrNull { it.height }?.url,
    uploader = uploaderName,
    uploaderAvatar = uploaderAvatars.maxByOrNull { it.height }?.url,
    videos = streamCount.toInt(),
    relatedStreams = relatedItems.map { it.toStreamItem() },
    nextpage = nextPage?.toNextPageString()
)

fun CommentsInfoItem.toComment() = Comment(
    author = uploaderName,
    commentId = commentId,
    commentText = commentText.content,
    commentedTime = textualUploadDate,
    commentedTimeMillis = uploadDate?.offsetDateTime()?.toEpochSecond()?.times(1000),
    commentorUrl = uploaderUrl.toID(),
    hearted = isHeartedByUploader,
    creatorReplied = hasCreatorReply(),
    likeCount = likeCount.toLong(),
    pinned = isPinned,
    verified = isUploaderVerified,
    replyCount = replyCount.toLong(),
    repliesPage = replies?.toNextPageString(),
    thumbnail = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
    channelOwner = isChannelOwner
)

// the following classes are necessary because kotlinx can't deserialize
// classes from external libraries as they're not annotated
@Serializable
private data class NextPage(
    val url: String? = null,
    val id: String? = null,
    val ids: List<String>? = null,
    val cookies: Map<String, String>? = null,
    val body: String? = null
)

fun Page.toNextPageString() = JsonHelper.json.encodeToString(
    NextPage(url, id, ids, cookies, body?.let { Base64.encodeToString(it, Base64.DEFAULT) })
)

fun String.toPage(): Page = with(JsonHelper.json.decodeFromString<NextPage>(this)) {
    return Page(url, id, ids, cookies, body?.let { Base64.decode(it, Base64.DEFAULT) })
}

@Serializable
private data class TabData(
    val originalUrl: String? = null,
    val url: String? = null,
    val id: String? = null,
    val contentFilters: List<String>? = null,
    val sortFilter: String? = null,
)

fun ListLinkHandler.toTabDataString() = JsonHelper.json.encodeToString(
    TabData(originalUrl, url, id, contentFilters, sortFilter)
)

fun String.toListLinkHandler() = with(JsonHelper.json.decodeFromString<TabData>(this)) {
    ListLinkHandler(originalUrl, url, id, contentFilters, sortFilter)
}

class NewPipeMediaServiceRepository : MediaServiceRepository {
    init {
        YoutubeStreamExtractor.setPoTokenProvider(PoTokenGenerator());
    }

    // see https://github.com/TeamNewPipe/NewPipeExtractor/tree/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/kiosk
    private val trendingCategories = TrendingCategory.entries.associate {
        when (it) {
            TrendingCategory.GAMING -> it to "trending_gaming"
            TrendingCategory.TRAILERS -> it to "trending_movies_and_shows"
            TrendingCategory.PODCASTS -> it to "trending_podcasts_episodes"
            TrendingCategory.MUSIC -> it to "trending_music"
            TrendingCategory.LIVE -> it to "live"
        }
    }

    override fun getTrendingCategories(): List<TrendingCategory> =
        trendingCategories.keys.toList()

    override suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem> {
        val kioskList = NewPipeExtractorInstance.extractor.kioskList
        kioskList.forceContentCountry(ContentCountry(region))

        val kioskId = trendingCategories[category]
        val extractor = kioskList.getExtractorById(kioskId, null)
        extractor.fetchPage()

        val info = KioskInfo.getInfo(extractor)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toStreamItem() }
    }

    override suspend fun getStreams(videoId: String): Streams = withContext(Dispatchers.IO) {
        if (videoId.length != 11) {
            return@withContext JioSaavnMediaServiceRepository().getStreams(videoId)
        }
        val respAsync = async {
            StreamInfo.getInfo("$YOUTUBE_FRONTEND_URL/watch?v=$videoId")
        }
        val resp = respAsync.await()
        val dislikes = -1L

        Streams(
            title = resp.name,
            description = resp.description.content,
            uploader = resp.uploaderName,
            uploaderAvatar = resp.uploaderAvatars.maxBy { it.height }.url,
            uploaderUrl = resp.uploaderUrl.toID(),
            uploaderVerified = resp.isUploaderVerified,
            uploaderSubscriberCount = resp.uploaderSubscriberCount,
            category = resp.category,
            views = resp.viewCount,
            likes = resp.likeCount,
            dislikes = dislikes,
            license = resp.licence,
            hls = resp.hlsUrl,
            dash = resp.dashMpdUrl,
            tags = resp.tags,
            metaInfo = resp.metaInfo.map {
                MetaInfo(
                    it.title,
                    it.content.content,
                    it.urls.map { url -> url.toString() },
                    it.urlTexts
                )
            },
            visibility = resp.privacy.name.lowercase(),
            duration = resp.duration,
            uploadTimestamp = resp.uploadDate.offsetDateTime().toInstant().toKotlinInstant(),
            uploaded = resp.uploadDate.offsetDateTime().toEpochSecond() * 1000,
            thumbnailUrl = resp.thumbnails.maxBy { it.height }.url,
            relatedStreams = resp.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .map { item -> item.toStreamItem() },
            chapters = resp.streamSegments.map {
                ChapterSegment(
                    title = it.title,
                    image = it.previewUrl.orEmpty(),
                    start = it.startTimeSeconds.toLong()
                )
            },
            audioStreams = resp.audioStreams.map { it.toMediaStream() },
            videoStreams = resp.videoOnlyStreams.map { it.toMediaStream().copy(videoOnly = true,) },
            previewFrames = resp.previewFrames.map {
                PreviewFrames(
                    it.urls,
                    it.frameWidth,
                    it.frameHeight,
                    it.totalCount,
                    it.durationPerFrame.toLong(),
                    it.framesPerPageX,
                    it.framesPerPageY
                )
            },
            subtitles = resp.subtitles.map {
                Subtitle(
                    it.content,
                    it.format?.mimeType,
                    it.displayLanguageName,
                    it.languageTag,
                    it.isAutoGenerated
                )
            },
            // currently, isShortFormContent always seems to return false
            isShort = resp.isShortFormContent || (resp.videoStreams + resp.videoOnlyStreams)
                .firstOrNull()?.let { it.height > it.width } ?: false,
            hasVideo = resp.videoStreams.isNotEmpty() || resp.videoOnlyStreams.isNotEmpty(),
            serverAbrStreamingUrl = resp.serverAbrStreamingUrl,
            videoPlaybackUstreamerConfig = resp.ustreamerConfig,
        )
    }



    override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult {
        if (filter.startsWith("jiosaavn")) {
            return JioSaavnMediaServiceRepository().getSearchResults(searchQuery, filter)
        }

        if (filter.startsWith("music_")) {
            val ytmResults = try {
                YtMusicApi.search(searchQuery, filter)
            } catch (e: Exception) {
                emptyList()
            }
            return SearchResult(
                items = ytmResults,
                nextpage = null,
                suggestion = null,
                corrected = false
            )
        }

        val queryHandler = NewPipeExtractorInstance.extractor.searchQHFactory.fromQuery(
            searchQuery,
            listOf(filter),
            null
        )
        val searchInfo = SearchInfo.getInfo(NewPipeExtractorInstance.extractor, queryHandler)

        return SearchResult(
            items = searchInfo.relatedItems.mapNotNull { it.toContentItem("youtube") },
            nextpage = searchInfo.nextPage?.toNextPageString(),
            suggestion = searchInfo.searchSuggestion,
            corrected = searchInfo.isCorrectedSearch
        )
    }

    override suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String
    ): SearchResult {
        if (filter.startsWith("jiosaavn")) {
            return JioSaavnMediaServiceRepository().getSearchResultsNextPage(searchQuery, filter, nextPage)
        }

        if (filter.startsWith("music_")) {
            return SearchResult(
                items = emptyList(),
                nextpage = null
            )
        }

        val queryHandler = NewPipeExtractorInstance.extractor.searchQHFactory.fromQuery(
            searchQuery,
            listOf(filter),
            null
        )
        val searchInfo = SearchInfo.getMoreItems(
            NewPipeExtractorInstance.extractor,
            queryHandler,
            nextPage.toPage()
        )

        return SearchResult(
            items = searchInfo.items.mapNotNull { it.toContentItem("youtube") },
            nextpage = searchInfo.nextPage?.toNextPageString()
        )
    }

    override suspend fun getSuggestions(query: String): List<String> {
        return NewPipeExtractorInstance.extractor.suggestionExtractor.suggestionList(query)
    }

    private suspend fun getLatestVideos(channelInfo: ChannelInfo): Pair<List<StreamItem>, String?> {
        val relatedTab = channelInfo.tabs.find { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: return emptyList<StreamItem>() to null

        val relatedStreamsResp = getChannelTab(relatedTab.toTabDataString())
        return relatedStreamsResp.content.map { it.toStreamItem() } to relatedStreamsResp.nextpage
    }

    override suspend fun getChannel(channelId: String): Channel {
        val channelUrl = "$YOUTUBE_FRONTEND_URL/channel/${channelId}"
        val channelInfo = ChannelInfo.getInfo(NewPipeExtractorInstance.extractor, channelUrl)

        val channel = channelInfo.toChannel()

        val relatedVideos = getLatestVideos(channelInfo)
        channel.relatedStreams = relatedVideos.first
        channel.nextpage = relatedVideos.second

        return channel
    }

    override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse {
        val linkListHandler = data.toListLinkHandler()

        val (items, newNextPage) = if (nextPage == null) {
            val resp = ChannelTabInfo.getInfo(NewPipeExtractorInstance.extractor, linkListHandler)
            resp.relatedItems to resp.nextPage
        } else {
            val resp = ChannelTabInfo.getMoreItems(
                NewPipeExtractorInstance.extractor,
                linkListHandler,
                nextPage.toPage()
            )
            resp.items to resp.nextPage
        }

        return ChannelTabResponse(
            content = items.mapNotNull { it.toContentItem() },
            nextpage = newNextPage?.toNextPageString()
        )
    }

    override suspend fun getChannelByName(channelName: String): Channel {
        val channelUrl = "$YOUTUBE_FRONTEND_URL/c/${channelName}"
        val channelInfo = ChannelInfo.getInfo(NewPipeExtractorInstance.extractor, channelUrl)

        val channel = channelInfo.toChannel()

        val relatedVideos = getLatestVideos(channelInfo)
        channel.relatedStreams = relatedVideos.first
        channel.nextpage = relatedVideos.second

        return channel
    }

    override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel {
        val url = "${YOUTUBE_FRONTEND_URL}/channel/${channelId}/videos"
        val listLinkHandler = ListLinkHandler(url, url, channelId, listOf("videos"), "")
        val tab = getChannelTab(listLinkHandler.toTabDataString(), nextPage)
        return Channel(
            relatedStreams = tab.content.map { it.toStreamItem() },
            nextpage = tab.nextpage
        )
    }

    override suspend fun getPlaylist(playlistId: String): Playlist {
        val cleanPlaylistId = playlistId.toID()
        
        if (cleanPlaylistId.startsWith("MPREb_") || cleanPlaylistId.startsWith("FEmusic_") || cleanPlaylistId.startsWith("OLAK") || cleanPlaylistId.startsWith("VLOLAK")) {
            val browseId = if (cleanPlaylistId.startsWith("OLAK") && !cleanPlaylistId.startsWith("VL")) "VL$cleanPlaylistId" else cleanPlaylistId
            val ytmAlbum = YtMusicApi.fetchAlbum(browseId)
            if (ytmAlbum != null && ytmAlbum.relatedStreams.isNotEmpty()) return ytmAlbum
        }

        // Helper to synthesize dynamic radio playlist from a seed track
        suspend fun synthesizeMixPlaylist(seedId: String): Playlist {
            val streamInfo = getStreams(seedId)
            val seedStreamItem = streamInfo.toStreamItem(seedId)
            val allTracks = mutableListOf(seedStreamItem).apply {
                addAll(streamInfo.relatedStreams.filter { !it.isLive })
            }
            // Chain to the next seed video for endless radio scrolling
            val nextSeedId = streamInfo.relatedStreams.firstOrNull { !it.isLive && it.url?.toID() != seedId }?.url?.toID()
            val nextPageToken = nextSeedId?.let { "mix_radio_$it" }

            return Playlist(
                name = "Mix - ${streamInfo.title.orEmpty()}",
                thumbnailUrl = streamInfo.thumbnailUrl,
                uploader = streamInfo.uploader,
                uploaderAvatar = streamInfo.uploaderAvatar,
                uploaderUrl = streamInfo.uploaderUrl,
                videos = allTracks.size,
                relatedStreams = allTracks,
                nextpage = nextPageToken
            )
        }

        if (cleanPlaylistId.startsWith("RD") || cleanPlaylistId.contains("RD")) {
            val seedVideoId = when {
                cleanPlaylistId.startsWith("RDAMVM") -> cleanPlaylistId.removePrefix("RDAMVM").take(11)
                cleanPlaylistId.startsWith("RDEM") -> cleanPlaylistId.removePrefix("RDEM").take(11)
                cleanPlaylistId.startsWith("RDCLAK") -> null
                cleanPlaylistId.startsWith("RD") -> cleanPlaylistId.removePrefix("RD").take(11)
                else -> null
            }?.takeIf { it.length == 11 }

            if (seedVideoId != null) {
                return try {
                    synthesizeMixPlaylist(seedVideoId)
                } catch (e: Exception) {
                    val playlistUrl = "${YOUTUBE_FRONTEND_URL}/playlist?list=${cleanPlaylistId}"
                    PlaylistInfo.getInfo(playlistUrl).toPlaylist()
                }
            }
        }

        val playlistUrl = "${YOUTUBE_FRONTEND_URL}/playlist?list=${cleanPlaylistId}"
        return try {
            val playlistInfo = PlaylistInfo.getInfo(playlistUrl)
            playlistInfo.toPlaylist()
        } catch (e: org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) {
            val fallbackVideoId = cleanPlaylistId.replace("RD", "").replace("AMVM", "").replace("EM", "").take(11)
            if (fallbackVideoId.length == 11) {
                synthesizeMixPlaylist(fallbackVideoId)
            } else {
                throw e
            }
        }
    }

    override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist {
        if (nextPage.startsWith("mix_radio_")) {
            val nextSeedId = nextPage.removePrefix("mix_radio_").takeIf { it.length == 11 }
            if (nextSeedId != null) {
                val streamInfo = getStreams(nextSeedId)
                val newTracks = streamInfo.relatedStreams.filter { !it.isLive }
                val subsequentSeedId = newTracks.lastOrNull { it.url?.toID() != nextSeedId }?.url?.toID()
                val subsequentToken = subsequentSeedId?.let { "mix_radio_$it" }
                return Playlist(
                    relatedStreams = newTracks,
                    nextpage = subsequentToken
                )
            }
        }

        val playlistUrl = "${YOUTUBE_FRONTEND_URL}/playlist?list=${playlistId}"
        val playlistInfo = PlaylistInfo.getMoreItems(
            NewPipeExtractorInstance.extractor,
            playlistUrl,
            nextPage.toPage()
        )

        return Playlist(
            relatedStreams = playlistInfo.items.map { it.toStreamItem() },
            nextpage = playlistInfo.nextPage?.toNextPageString()
        )
    }

    override suspend fun getComments(videoId: String): CommentsPage {
        val url = "${YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
        val commentsInfo = CommentsInfo.getInfo(url)

        return CommentsPage(
            nextpage = commentsInfo.nextPage?.toNextPageString(),
            disabled = commentsInfo.isCommentsDisabled,
            commentCount = commentsInfo.commentsCount.toLong(),
            comments = commentsInfo.relatedItems.map { it.toComment() }
        )
    }

    override suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage {
        val url = "${YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
        val commentsInfo = CommentsInfo.getMoreItems(
            NewPipeExtractorInstance.extractor,
            url,
            nextPage.toPage()
        )

        return CommentsPage(
            nextpage = commentsInfo.nextPage?.toNextPageString(),
            comments = commentsInfo.items.map { it.toComment() }
        )
    }

    companion object {
        private const val DEARROW_THUMBNAIL_URL =
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
    }
}
