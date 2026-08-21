package app.libre.api

import androidx.annotation.StringRes
import app.libre.R
import app.libre.api.obj.Channel
import app.libre.api.obj.ChannelTabResponse
import app.libre.api.obj.CommentsPage
import app.libre.api.obj.Playlist
import app.libre.api.obj.SearchResult
import app.libre.api.obj.StreamItem
import app.libre.api.obj.Streams
import app.libre.helpers.PlayerHelper

interface MediaServiceRepository {
    fun getTrendingCategories(): List<TrendingCategory>

    suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem>
    suspend fun getStreams(videoId: String): Streams
    suspend fun getComments(videoId: String): CommentsPage

    suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage
    suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult
    suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String
    ): SearchResult

    suspend fun getSuggestions(query: String): List<String>
    suspend fun getChannel(channelId: String): Channel
    suspend fun getChannelTab(data: String, nextPage: String? = null): ChannelTabResponse
    suspend fun getChannelByName(channelName: String): Channel
    suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel
    suspend fun getPlaylist(playlistId: String): Playlist
    suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist

    companion object {
        private val jioSaavnRepo = JioSaavnMediaServiceRepository()
        private val youtubeRepo: MediaServiceRepository = NewPipeMediaServiceRepository()

        val instance: MediaServiceRepository = object : MediaServiceRepository {
            override fun getTrendingCategories(): List<TrendingCategory> =
                youtubeRepo.getTrendingCategories()

            override suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem> =
                youtubeRepo.getTrending(region, category)

            private val streamsLruCache = androidx.collection.LruCache<String, Streams>(40)

            override suspend fun getStreams(videoId: String): Streams {
                streamsLruCache.get(videoId)?.let { return it }
                val fetched = if (app.libre.helpers.JioSaavnHelper.isJioSaavn(videoId, false)) {
                    jioSaavnRepo.getStreams(videoId)
                } else {
                    youtubeRepo.getStreams(videoId)
                }
                streamsLruCache.put(videoId, fetched)
                return fetched
            }

            override suspend fun getComments(videoId: String): CommentsPage {
                return if (app.libre.helpers.JioSaavnHelper.isJioSaavn(videoId, false)) {
                    jioSaavnRepo.getComments(videoId)
                } else {
                    youtubeRepo.getComments(videoId)
                }
            }

            override suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage {
                return if (app.libre.helpers.JioSaavnHelper.isJioSaavn(videoId, false)) {
                    jioSaavnRepo.getCommentsNextPage(videoId, nextPage)
                } else {
                    youtubeRepo.getCommentsNextPage(videoId, nextPage)
                }
            }

            private fun isJioSaavnFilter(filter: String): Boolean {
                return filter.contains("jiosaavn")
            }

            override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult {
                return if (isJioSaavnFilter(filter)) {
                    jioSaavnRepo.getSearchResults(searchQuery, filter)
                } else {
                    youtubeRepo.getSearchResults(searchQuery, filter)
                }
            }

            override suspend fun getSearchResultsNextPage(
                searchQuery: String,
                filter: String,
                nextPage: String
            ): SearchResult {
                return if (isJioSaavnFilter(filter)) {
                    jioSaavnRepo.getSearchResultsNextPage(searchQuery, filter, nextPage)
                } else {
                    youtubeRepo.getSearchResultsNextPage(searchQuery, filter, nextPage)
                }
            }

            override suspend fun getSuggestions(query: String): List<String> =
                youtubeRepo.getSuggestions(query)

            override suspend fun getChannel(channelId: String): Channel {
                return if (channelId.length <= 15) {
                    jioSaavnRepo.getChannel(channelId)
                } else {
                    youtubeRepo.getChannel(channelId)
                }
            }

            override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse =
                youtubeRepo.getChannelTab(data, nextPage)

            override suspend fun getChannelByName(channelName: String): Channel =
                youtubeRepo.getChannelByName(channelName)

            override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel =
                youtubeRepo.getChannelNextPage(channelId, nextPage)

            override suspend fun getPlaylist(playlistId: String): Playlist {
                return if (playlistId.startsWith("jsa_")) {
                    jioSaavnRepo.getPlaylist(playlistId)
                } else {
                    youtubeRepo.getPlaylist(playlistId)
                }
            }

            override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist {
                return if (playlistId.startsWith("jsa_")) {
                    jioSaavnRepo.getPlaylistNextPage(playlistId, nextPage)
                } else {
                    youtubeRepo.getPlaylistNextPage(playlistId, nextPage)
                }
            }
        }
    }
}

enum class TrendingCategory(@StringRes val titleRes: Int) {
    GAMING(R.string.gaming),
    TRAILERS(R.string.trailers),
    PODCASTS(R.string.podcasts),
    MUSIC(R.string.music),
    LIVE(R.string.live)
}