package app.libre.util

import androidx.media3.common.Player
import app.libre.api.MediaServiceRepository
import app.libre.api.PlaylistsHelper
import app.libre.api.obj.StreamItem
import app.libre.extensions.move
import app.libre.extensions.runCatchingIO
import app.libre.extensions.toID
import app.libre.helpers.PlayerHelper
import app.libre.util.PlayingQueue.queueMode
import kotlinx.coroutines.Job
import java.util.Collections

object PlayingQueue {
    // queue is a synchronized list to be safely accessible from different coroutine threads
    private val queue = Collections.synchronizedList(mutableListOf<StreamItem>())
    private var currentStream: StreamItem? = null

    private val queueJobs = mutableListOf<Job>()

    /**
     * Current use case of the queue. Do NOT add any offline videos while the [queueMode] is online
     * or vice versa.
     */
    var queueMode: PlayingQueueMode = PlayingQueueMode.ONLINE

    private var repeatModeOverride: Int? = null

    // wrapper around PlayerHelper#repeatMode for compatibility
    var repeatMode: Int
        get() = repeatModeOverride ?: try {
            PlayerHelper.repeatMode
        } catch (_: Throwable) {
            Player.REPEAT_MODE_OFF
        }
        set(value) {
            repeatModeOverride = value
            try {
                PlayerHelper.repeatMode = value
            } catch (_: Throwable) {
                // Ignore if preference store is not initialized in unit tests
            }
        }

    private fun clearJobs() {
        queueJobs.forEach {
            it.cancel()
        }
        queueJobs.clear()
    }

    fun clear() {
        clearJobs()
        queue.clear()
        currentStream = null
    }

    /**
     * Remove all items after the current [StreamItem] from the queue
     *
     * I.e., the current and all previous streams are kept
     */
    fun clearAfterCurrent() {
        clearJobs()
        synchronized(queue) {
            val newQueue = queue.filterIndexed { index, item -> index <= currentIndex() }
            setStreams(newQueue)
        }
    }

    /**
     * @param skipExisting Whether to skip the [streamItem] if it's already part of the queue
     */
    fun add(vararg streamItem: StreamItem, skipExisting: Boolean = false) = synchronized(queue) {
        for (stream in streamItem) {
            if ((skipExisting && contains(stream)) || stream.title.isNullOrBlank()) continue

            queue.remove(stream)
            queue.add(stream)
        }
    }

    fun addAsNext(streamItem: StreamItem) {
        synchronized(queue) {
            if (currentStream == streamItem) return
            if (queue.contains(streamItem)) queue.remove(streamItem)
            queue.add(currentIndex() + 1, streamItem)
        }
    }

    // return the next item, or if repeating enabled and no video left, the first one of the queue
    fun getNext(): String? = synchronized(queue) {
        val nextItem = queue.getOrNull(currentIndex() + 1)
        if (nextItem != null) return nextItem.url?.toID()

        if (repeatMode == Player.REPEAT_MODE_ALL) return queue.firstOrNull()?.url?.toID()

        return null
    }

    // return the previous item, or if repeating enabled and no video left, the last one of the queue
    fun getPrev(): String? = synchronized(queue) {
        val prevItem = queue.getOrNull(currentIndex() - 1)
        if (prevItem != null) return prevItem.url?.toID()

        if (repeatMode == Player.REPEAT_MODE_ALL) return queue.lastOrNull()?.url?.toID()

        return null
    }

    fun hasPrev() = getPrev() != null

    fun hasNext() = getNext() != null

    fun updateCurrent(streamItem: StreamItem) = synchronized(queue) {
        currentStream = streamItem

        if (!contains(streamItem)) add(streamItem)
    }

    fun isNotEmpty() = queue.isNotEmpty()

    fun isEmpty() = queue.isEmpty()

    fun size() = queue.size

    fun isLast() = currentIndex() == size() - 1

    fun currentIndex(): Int = synchronized(queue) {
        return queue.indexOfFirst {
            it.url?.toID() == currentStream?.url?.toID()
        }.takeIf { it >= 0 } ?: 0
    }

    fun getCurrent(): StreamItem? = currentStream

    fun contains(streamItem: StreamItem) = synchronized(queue) {
        queue.any { it.url?.toID() == streamItem.url?.toID() }
    }

    // direct indexed access without creating defensive list copies
    fun get(index: Int): StreamItem? = synchronized(queue) {
        queue.getOrNull(index)
    }

    // only returns a copy of the queue, no write access
    fun getStreams() = queue.toList()

    fun setStreams(streams: List<StreamItem>) = synchronized(queue) {
        queue.clear()

        queue.addAll(streams)
    }

    fun remove(index: Int) = synchronized(queue) {
        queue.removeAt(index)
        return@synchronized
    }

    fun move(from: Int, to: Int) = synchronized(queue) {
        queue.move(from, to)
    }

    /**
     * Adds a list of videos to the current queue while updating the position of the current stream
     * @param isMainList whether the videos are part of the list that initially has been used to
     * start the queue, either from a channel or playlist. If it's false, the current stream won't
     * be touched, since it's an independent list.
     */
    private fun addToQueueAsync(
        streams: List<StreamItem>, currentStreamItem: StreamItem? = null, isMainList: Boolean = true
    ) {
        synchronized(queue) {
            if (!isMainList) {
                add(*streams.toTypedArray())
                return
            }
            val currentStream = currentStreamItem ?: this.currentStream
            // if the stream already got added to the queue earlier, although it's not yet
            // been found in the playlist, remove it and re-add it later
            var reAddStream = true
            if (currentStream != null && streams.any { it.url?.toID() == currentStream.url?.toID() }) {
                queue.removeAll { it.url?.toID() == currentStream.url?.toID() }
                reAddStream = false
            }
            // add all new stream items to the queue
            add(*streams.toTypedArray())

            if (currentStream != null && reAddStream) {
                // re-add the stream to the end of the queue
                updateCurrent(currentStream)
            }
        }
    }

    private suspend fun fetchMoreFromPlaylist(
        playlistId: String,
        nextPage: String?,
        isMainList: Boolean
    ) {
        var playlistNextPage = nextPage
        while (playlistNextPage != null) {
            MediaServiceRepository.instance.getPlaylistNextPage(playlistId, playlistNextPage).run {
                addToQueueAsync(relatedStreams, isMainList = isMainList)
                playlistNextPage = this.nextpage
            }
        }
    }

    fun insertPlaylist(playlistId: String, newCurrentStream: StreamItem?) = runCatchingIO {
        val cleanId = playlistId.toID()
        if (cleanId.startsWith("RD")) {
            val targetVideoId = newCurrentStream?.url?.toID() ?: cleanId.removePrefix("RD").takeIf { it.length == 11 }
            if (targetVideoId != null) {
                val streams = MediaServiceRepository.instance.getStreams(targetVideoId)
                if (newCurrentStream == null) {
                    updateCurrent(streams.toStreamItem(targetVideoId))
                }
                addToQueueAsync(streams.relatedStreams, isMainList = true)

                // Background radio chaining: continuously fetch up to 4 more batches so radio never runs dry on shuffle or playback
                var currentSeedId = streams.relatedStreams.firstOrNull { !it.isLive && it.url?.toID() != targetVideoId }?.url?.toID()
                var radioBatches = 0
                while (currentSeedId != null && radioBatches < 4) {
                    try {
                        val nextBatch = MediaServiceRepository.instance.getStreams(currentSeedId)
                        val newTracks = nextBatch.relatedStreams.filter { !it.isLive }
                        if (newTracks.isNotEmpty()) {
                            addToQueueAsync(newTracks, isMainList = false)
                        }
                        currentSeedId = newTracks.lastOrNull { it.url?.toID() != currentSeedId }?.url?.toID()
                        radioBatches++
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            return@runCatchingIO
        }
        val playlist = PlaylistsHelper.getPlaylist(playlistId)
        val isMainList = newCurrentStream != null
        addToQueueAsync(playlist.relatedStreams, newCurrentStream, isMainList)
        if (playlist.nextpage == null) return@runCatchingIO
        fetchMoreFromPlaylist(playlistId, playlist.nextpage, isMainList)
    }.let { queueJobs.add(it) }

    private suspend fun fetchMoreFromChannel(channelId: String, nextPage: String?) {
        var channelNextPage = nextPage
        var pageIndex = 1
        while (channelNextPage != null && pageIndex < 10) {
            MediaServiceRepository.instance.getChannelNextPage(channelId, channelNextPage).run {
                addToQueueAsync(relatedStreams)
                channelNextPage = this.nextpage
                pageIndex++
            }
        }
    }

    private fun insertChannel(channelId: String, newCurrentStream: StreamItem) = runCatchingIO {
        val channel = MediaServiceRepository.instance.getChannel(channelId)
        addToQueueAsync(channel.relatedStreams, newCurrentStream)
        if (channel.nextpage == null) return@runCatchingIO
        fetchMoreFromChannel(channelId, channel.nextpage)
    }.let { queueJobs.add(it) }

    fun insertByVideoId(videoId: String) = runCatchingIO {
        val streams = MediaServiceRepository.instance.getStreams(videoId.toID())
        add(streams.toStreamItem(videoId))
    }

    fun updateQueue(
        streamItem: StreamItem,
        playlistId: String?,
        channelId: String?,
        relatedStreams: List<StreamItem> = emptyList()
    ) {
        updateCurrent(streamItem)

        if (playlistId != null) {
            insertPlaylist(playlistId, streamItem)
        } else if (channelId != null) {
            insertChannel(channelId, streamItem)
        } else if (relatedStreams.isNotEmpty()) {
            insertRelatedStreams(relatedStreams)
        }
    }

    fun insertRelatedStreams(streams: List<StreamItem>) {
        if (!PlayerHelper.autoInsertRelatedVideos) return

        // don't add new videos to the queue if the user chose to repeat only the current queue
        if (isLast() && repeatMode == Player.REPEAT_MODE_ALL) return

        add(*streams.filter { !it.isLive }.toTypedArray(), skipExisting = true)
    }

    /**
     * Smart artist-spaced shuffle: shuffles items such that tracks by the same artist/uploader
     * are distributed evenly across the queue rather than clustered sequentially.
     */
    fun smartShuffleList(items: List<StreamItem>): List<StreamItem> {
        if (items.size <= 2) return items.shuffled()

        val artistBuckets = items.groupBy { (it.uploaderName ?: "").trim().lowercase() }
            .values
            .map { it.shuffled().toMutableList() }
            .sortedByDescending { it.size }
            .toMutableList()

        val result = mutableListOf<StreamItem>()
        var lastArtist = ""

        while (artistBuckets.isNotEmpty()) {
            val candidateIndex = artistBuckets.indexOfFirst {
                (it.firstOrNull()?.uploaderName ?: "").trim().lowercase() != lastArtist
            }

            val chosenBucketIndex = if (candidateIndex != -1) candidateIndex else 0
            val chosenBucket = artistBuckets[chosenBucketIndex]
            val item = chosenBucket.removeAt(0)
            result.add(item)
            lastArtist = (item.uploaderName ?: "").trim().lowercase()

            if (chosenBucket.isEmpty()) {
                artistBuckets.removeAt(chosenBucketIndex)
            } else {
                artistBuckets.sortByDescending { it.size }
            }
        }
        return result
    }

    /**
     * Shuffles all upcoming songs after the currently playing song using smart artist-spacing.
     */
    fun shuffleUpcoming(): List<StreamItem> = synchronized(queue) {
        val currentIndex = currentIndex()
        val currentAndPast = queue.filterIndexed { index, _ -> index <= currentIndex }
        val upcoming = queue.filterIndexed { index, _ -> index > currentIndex }
        if (upcoming.isEmpty()) return queue.toList()

        val shuffledUpcoming = smartShuffleList(upcoming)
        val newQueue = currentAndPast + shuffledUpcoming
        setStreams(newQueue)
        return newQueue
    }
}

enum class PlayingQueueMode {
    ONLINE,
    OFFLINE
}