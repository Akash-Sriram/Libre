package app.libre.services

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.api.obj.Streams
import app.libre.constants.IntentData
import app.libre.constants.PreferenceKeys
import app.libre.db.DatabaseHelper
import app.libre.extensions.TAG
import app.libre.extensions.parcelable
import app.libre.extensions.setMetadata
import app.libre.extensions.toID
import app.libre.extensions.toastFromMainDispatcher
import app.libre.extensions.toastFromMainThread
import app.libre.extensions.updateParameters
import app.libre.helpers.PlayerHelper
import app.libre.helpers.PlayerHelper.getSubtitleRoleFlags
import app.libre.helpers.PreferenceHelper
import app.libre.parcelable.PlayerData
import app.libre.player.SabrMediaSource
import app.libre.player.manifest.SabrManifest
import app.libre.util.PlayingQueue
import app.libre.util.YoutubeHlsPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the selected videos audio in background mode with a notification area.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class OnlinePlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = false

    // PlaylistId/ChannelId for autoplay
    private var playlistId: String? = null
    private var channelId: String? = null

    /**
     * The response that gets when called the Api.
     */
    private var streams: Streams? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /*
    Current job that's loading a new video (the value is null if no video is loading at the moment).
     */
    private var fetchVideoInfoJob: Job? = null
    private var prefetchJob: Job? = null

    private fun prefetchNextTrack() {
        prefetchJob?.cancel()
        val nextVideoId = PlayingQueue.getNext() ?: return

        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Local Track Fast-Path Pre-Check
                val localPath = if (isAudioOnlyPlayer) app.libre.helpers.LocalAudioMatcher.getLocalPathAsync(nextVideoId) else null
                if (localPath != null) {
                    app.libre.helpers.LocalAudioMatcher.getCachedMetadata(nextVideoId)
                    app.libre.helpers.LocalAudioMatcher.getEmbeddedArtUri(this@OnlinePlayerService, nextVideoId)
                    return@launch
                }

                // 2. Online Track Pre-fetch into LRU Cache
                if (app.libre.helpers.NetworkHelper.isNetworkAvailable(this@OnlinePlayerService)) {
                    MediaServiceRepository.instance.getStreams(nextVideoId)
                }
            } catch (_: Exception) {}
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    if (!isTransitioning) playNextVideo()
                }

                Player.STATE_IDLE -> {
                    onDestroy()
                }

                Player.STATE_BUFFERING -> {}
                Player.STATE_READY -> {
                    prefetchNextTrack()
                }
            }
        }
    }

    override suspend fun onServiceCreated(args: Bundle) {
        val playerData = args.parcelable<PlayerData>(IntentData.playerData)
        if (playerData == null) {
            stopSelf()
            return
        }
        isAudioOnlyPlayer = args.getBoolean(IntentData.audioOnly)

        // get the intent arguments
        // call toID() to strip any URL path prefix (e.g. /watch?v=, /watch/)
        // since the adapter may pass the full item.url instead of a bare videoId
        videoId = playerData.videoId!!.toID()
        playlistId = playerData.playlistId
        channelId = playerData.channelId
        startTimestampSeconds = playerData.timestamp

        if (!playerData.keepQueue) PlayingQueue.clear()

        exoPlayer?.addListener(playerListener)
        trackSelector?.updateParameters {
            setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
        }
    }

    override suspend fun startPlayback() {
        super.startPlayback()

        val timestampMs = startTimestampSeconds?.times(1000) ?: 0L
        startTimestampSeconds = null

        // stop any previous task for loading video info
        fetchVideoInfoJob?.cancelAndJoin()

        // start loading the video info while keeping a reference to the job
        // so that it can be canceled once a different video is loaded
        fetchVideoInfoJob = scope.launch {
            val currentQueueItem = PlayingQueue.getItem(videoId) ?: PlayingQueue.getCurrent()
            if (currentQueueItem != null && currentQueueItem.url?.toID() == videoId.toID()) {
                PlayingQueue.updateCurrent(currentQueueItem)
            }
            val isJio = app.libre.helpers.JioSaavnHelper.isJioSaavn(videoId)

            // Only use local audio fast-path if this is an audio-only player session
            val localPath = if (isAudioOnlyPlayer) {
                app.libre.helpers.LocalAudioMatcher.getLocalPathAsync(videoId)
                    ?: if (isJio) currentQueueItem?.title?.let { app.libre.helpers.LocalAudioMatcher.getLocalPathByTitle(it, currentQueueItem.uploaderName.orEmpty()) } else null
            } else null

            if (localPath != null) {
                // LOCAL FILE EXISTS — play instantly with cached/local metadata with ZERO network calls!
                app.libre.helpers.LocalAudioMatcher.registerTitleMatch(videoId, localPath)

                // Build streams immediately from cache or embedded file info
                val cached = app.libre.helpers.LocalAudioMatcher.getCachedMetadata(videoId)
                val playlistItem = if (cached == null) {
                    withContext(Dispatchers.IO) {
                        try {
                            app.libre.db.DatabaseHolder.Database.localPlaylistsDao().getPlaylistItemByVideoId(videoId)
                        } catch (e: Exception) { null }
                    }
                } else null
                
                val streamItem = currentQueueItem ?: playlistItem?.toStreamItem()
                val localArt = app.libre.helpers.LocalAudioMatcher.getEmbeddedArtUri(this@OnlinePlayerService, videoId)
                    ?: app.libre.helpers.LocalAudioMatcher.getEmbeddedArtUri(this@OnlinePlayerService, localPath)
                val thumbnailUrl = localArt
                    ?: streamItem?.thumbnail?.takeIf { it.isNotBlank() }
                    ?: cached?.thumbnailUrl.orEmpty()
                
                streams = Streams(
                    title = app.libre.helpers.LocalAudioMatcher.getTitleFromFile(videoId, streamItem?.title) ?: cached?.title?.takeIf { it.isNotBlank() } ?: streamItem?.title ?: "Local Audio",
                    uploader = cached?.uploader?.takeIf { it.isNotBlank() } ?: streamItem?.uploaderName ?: "Local Artist",
                    artist = app.libre.helpers.LocalAudioMatcher.getArtistFromFile(videoId, streamItem?.title),
                    thumbnailUrl = thumbnailUrl,
                    duration = cached?.duration?.takeIf { it > 0 } ?: streamItem?.duration ?: 0L
                )

                streams?.toStreamItem(videoId)?.let {
                    PlayingQueue.updateCurrent(it)
                    if (!PlayingQueue.hasNext()) {
                        PlayingQueue.updateQueue(it, playlistId, channelId, streams!!.relatedStreams)
                    }
                }

                withContext(Dispatchers.Main) {
                    setStreamSource()   // setStreamSource sees localPath and routes to local file
                    configurePlayer(timestampMs)
                }
                return@launch
            }

            // NO LOCAL FILE — stream normally from online
            streams = withContext(Dispatchers.IO) {
                try {
                    MediaServiceRepository.instance.getStreams(videoId)
                } catch (e: Exception) {
                    Log.e(TAG(), e.stackTraceToString())
                    toastFromMainDispatcher(e.localizedMessage.orEmpty())
                    return@withContext null
                }
            } ?: return@launch

            // In Audio Player mode, auto-upgrade music videos to their official Studio Master
            var actualVideoId = videoId
            val isFromAlbum = !currentQueueItem?.albumName.isNullOrBlank() || playlistId?.startsWith("OLAK") == true || playlistId?.startsWith("MPRE") == true
            if (isAudioOnlyPlayer && videoId.length == 11 && !isFromAlbum) {
                val currentTitle = streams?.title.orEmpty()
                val currentUploader = streams?.uploader.orEmpty()
                val isAlreadyStudioMaster = currentUploader.endsWith("- Topic", ignoreCase = true)
                val titleLower = currentTitle.lowercase()
                val isExplicitMusicVideo = !isAlreadyStudioMaster && (
                    titleLower.contains("video song") || titleLower.contains("official video") ||
                    titleLower.contains("music video") || titleLower.contains("promo") ||
                    titleLower.contains("full video") || titleLower.contains("4k video") ||
                    titleLower.contains("lyric video")
                )

                if (isExplicitMusicVideo) {
                    val rawArtist = currentUploader.replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
                    val artist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist) ?: rawArtist
                    val master = withContext(Dispatchers.IO) {
                        app.libre.api.YtMusicApi.resolveStudioMaster(currentTitle, artist)
                    }
                    if (master != null) {
                        val masterId = master.url.orEmpty().toID()
                        if (masterId.isNotEmpty() && masterId != videoId) {
                            val masterStreams = withContext(Dispatchers.IO) {
                                try {
                                    MediaServiceRepository.instance.getStreams(masterId)
                                } catch (e: Exception) { null }
                            }
                            if (masterStreams != null) {
                                actualVideoId = masterId
                                streams = masterStreams.copy(
                                    thumbnailUrl = master.thumbnail?.takeIf { it.isNotBlank() } ?: masterStreams.thumbnailUrl
                                )
                            }
                        }
                    }
                }
            }

            // Only preserve album artwork if this is an official album playlist
            val queueThumb = currentQueueItem?.thumbnail
            if (isFromAlbum && !queueThumb.isNullOrBlank() && streams != null && streams!!.thumbnailUrl.isNullOrBlank()) {
                streams = streams!!.copy(thumbnailUrl = queueThumb)
            }

            videoId = actualVideoId

            streams?.toStreamItem(videoId)?.let {
                PlayingQueue.updateCurrent(it)
                if (!PlayingQueue.hasNext()) {
                    PlayingQueue.updateQueue(it, playlistId, channelId, streams!!.relatedStreams)
                }
            }

            withContext(Dispatchers.Main) {
                setStreamSource()
                configurePlayer(timestampMs)
            }

        }

        fetchVideoInfoJob?.join()
        fetchVideoInfoJob = null
    }

    private fun configurePlayer(seekToPositionMs: Long) {
        // seek to the previous position if available
        if (seekToPositionMs != 0L) {
            exoPlayer?.seekTo(seekToPositionMs)
        }

        exoPlayer?.apply {
            // automatically start playback when using the audio player
            playWhenReady = PlayerHelper.playAutomatically || isAudioOnlyPlayer
            prepare()
        }
    }

    /**
     * Plays the next video from the queue
     */
    private fun playNextVideo(nextId: String? = null) {
        if (nextId == null) {
            if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
                exoPlayer?.seekTo(0)
                return
            }

            // In audio-only mode always advance — the user is listening to a playlist.
            // In video mode respect the autoplay and shouldHandleAutoplay guards.
            val canAutoPlay = isAudioOnlyPlayer ||
                (PlayerHelper.isAutoPlayEnabled(playlistId != null) && shouldHandleAutoplay)
            if (!canAutoPlay) return
        }

        val nextVideo = nextId ?: PlayingQueue.getNext() ?: return

        // play new video on background
        navigateVideo(nextVideo)
    }



    override fun navigateVideo(videoId: String) {
        this.streams = null

        super.navigateVideo(videoId)
    }

    /**
     * Sets the [MediaItem] with the [streams] into the [exoPlayer]
     */
    private fun setStreamSource() {
        val streams = streams ?: return

        // Intercept and use local audio files only in audio-only player mode
        val localPath = if (isAudioOnlyPlayer) app.libre.helpers.LocalAudioMatcher.getLocalPath(videoId) else null
        if (localPath != null) {
            android.util.Log.i("OnlinePlayerService", "Local audio match found: $localPath. Playing locally instead of streaming!")
            val localUri = android.net.Uri.fromFile(java.io.File(localPath))
            // Detect MIME type from file extension so ExoPlayer parses the container correctly.
            // Hardcoding AUDIO_MPEG caused M4A/FLAC/OGG files to fail 3-4 times before playing.
            val mimeType = when (localPath.substringAfterLast('.').lowercase()) {
                "m4a"  -> androidx.media3.common.MimeTypes.AUDIO_MP4
                "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                "ogg"  -> androidx.media3.common.MimeTypes.AUDIO_OGG
                "wav"  -> androidx.media3.common.MimeTypes.AUDIO_WAV
                else   -> androidx.media3.common.MimeTypes.AUDIO_MPEG // mp3 default
            }
            val mediaItem = createMediaItem(localUri, mimeType, streams)
            exoPlayer?.setMediaItem(mediaItem)
            return
        }

        when {
            // SABR
            // skip SABR for livestreams, as the player impl has no support for it
            !streams.isLive && streams.serverAbrStreamingUrl != null && streams.videoPlaybackUstreamerConfig != null -> {
                val sabrMediaSourceFactory = SabrMediaSource.Factory(
                    SabrManifest(videoId, streams)
                )
                val mediaItem = createMediaItem(
                    streams.serverAbrStreamingUrl.toUri(),
                    "application/vnd.yt-ump",
                    streams
                )
                val mediaSource = sabrMediaSourceFactory.createMediaSource(mediaItem)
                val mediaSources = listOf<MediaSource>(mediaSource) + streams.subtitles.map {
                    val format = Format.Builder()
                        .setSampleMimeType(it.mimeType)
                        .setLanguage(it.code)
                        .setRoleFlags(getSubtitleRoleFlags(it))
                        .build()
                    val subtitleParserFactory = DefaultSubtitleParserFactory()
                    val extractorsFactory = ExtractorsFactory {
                        arrayOf(
                            SubtitleExtractor(
                                subtitleParserFactory.create(format), format
                            )
                        )
                    }
                    val progressiveMediaSourceFactory = ProgressiveMediaSource.Factory(
                        DefaultDataSource.Factory(this), extractorsFactory
                    ).setLoadOnlySelectedTracks(true)
                    try {
                        // `enableLazyLoadingWithSingleTrack` is private
                        val method =
                            ProgressiveMediaSource.Factory::class.java.getDeclaredMethod(
                                "enableLazyLoadingWithSingleTrack",
                                Int::class.java,
                                Format::class.java
                            )
                        method.isAccessible = true
                        method.invoke(
                            progressiveMediaSourceFactory, SubtitleExtractor.TRACK_ID,
                            format
                                .buildUpon()
                                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                                .setCodecs(format.sampleMimeType)
                                .setCueReplacementBehavior( subtitleParserFactory.getCueReplacementBehavior(format))
                                .build()
                        )
                    } catch (e: Exception) {
                        Log.w(this::class.simpleName, "failed to set subtitle lazy-loading: ${e.stackTrace}")
                    }
                    progressiveMediaSourceFactory.createMediaSource(MediaItem.fromUri(it.url!!))
                }.toList()

                exoPlayer?.setMediaSource(MergingMediaSource(*mediaSources.toTypedArray()))
                return
            }
            // DASH
            streams.videoStreams.isNotEmpty() -> {
                // only use the dash manifest generated by YT if either it's a livestream or no other source is available
                val dashUri =
                    if (streams.isLive && streams.dash != null) {
                        streams.dash.toUri()
                    } else {
                        PlayerHelper.createDashSource(streams, this)
                    }

                val mediaItem = createMediaItem(dashUri, MimeTypes.APPLICATION_MPD, streams)
                exoPlayer?.setMediaItem(mediaItem)
            }
            // HLS as last fallback
            streams.hls != null -> {
                val hlsMediaSourceFactory = HlsMediaSource.Factory(DefaultDataSource.Factory(this))
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())

                val mediaItem = createMediaItem(
                    streams.hls.toUri(),
                    MimeTypes.APPLICATION_M3U8,
                    streams
                )
                val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

                exoPlayer?.setMediaSource(mediaSource)
                return
            }
            // Progressive audio fallback (e.g. JioSaavn progressive audio streams)
            streams.audioStreams.isNotEmpty() -> {
                val audioStream = streams.audioStreams.first()
                val audioUri = audioStream.url.orEmpty().toUri()
                val mediaItem = createMediaItem(
                    audioUri,
                    audioStream.mimeType ?: MimeTypes.AUDIO_UNKNOWN,
                    streams
                )
                exoPlayer?.setMediaItem(mediaItem)
            }
            // NO STREAM FOUND
            else -> {
                toastFromMainThread(R.string.unknown_error)
                return
            }
        }
    }

    private fun getSubtitleConfigs(): List<SubtitleConfiguration> = streams?.subtitles?.map {
        val roleFlags = getSubtitleRoleFlags(it)
        SubtitleConfiguration.Builder(it.url!!.toUri())
            .setRoleFlags(roleFlags)
            .setLanguage(it.code)
            .setMimeType(it.mimeType).build()
    }.orEmpty()

    private fun createMediaItem(uri: Uri, mimeType: String, streams: Streams) =
        MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setSubtitleConfigurations(getSubtitleConfigs())
            .setMetadata(streams, videoId)
            .build()
}
