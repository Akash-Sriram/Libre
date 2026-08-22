package app.libre.db

import app.libre.api.obj.StreamItem
import app.libre.constants.PreferenceKeys
import app.libre.db.DatabaseHolder.Database
import app.libre.db.obj.SearchHistoryItem
import app.libre.enums.ContentFilter
import app.libre.extensions.toID
import app.libre.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object DatabaseHelper {
    private const val MAX_SEARCH_HISTORY_SIZE = 20

    suspend fun addToSearchHistory(searchHistoryItem: SearchHistoryItem) {
        Database.searchHistoryDao().insert(searchHistoryItem)

        // delete the first search history entry if the limit is reached
        val searchHistory = Database.searchHistoryDao().getAll().toMutableList()

        while (searchHistory.size > MAX_SEARCH_HISTORY_SIZE) {
            Database.searchHistoryDao().delete(searchHistory.first())
            searchHistory.removeAt(0)
        }
    }

    suspend fun filterByStreamType(
        streams: List<StreamItem>,
        showUpcoming: Boolean
    ): List<StreamItem> {
        val streamItems = streams.filter {
            if (!showUpcoming && it.isUpcoming) return@filter false

            val isVideo = !it.isShort && !it.isLive
            return@filter when {
                !ContentFilter.SHORTS.isEnabled && it.isShort -> false
                !ContentFilter.VIDEOS.isEnabled && isVideo -> false
                !ContentFilter.LIVESTREAMS.isEnabled && it.isLive -> false
                else -> true
            }
        }
        return streamItems
    }
}
