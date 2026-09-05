package app.libre.ui.models.sources

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.libre.api.MediaServiceRepository
import app.libre.api.obj.ContentItem
import app.libre.extensions.toID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchPagingSource(
    private val searchQuery: String,
    private val searchFilter: String,
    private val onSearchSuggestion: (Pair<String, Boolean>?) -> Unit
) : PagingSource<String, ContentItem>() {
    override fun getRefreshKey(state: PagingState<String, ContentItem>) = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, ContentItem> {
        return try {
            val result = withContext(Dispatchers.IO) {
                params.key?.let {
                    MediaServiceRepository.instance.getSearchResultsNextPage(
                        searchQuery, searchFilter, it
                    )
                } ?: MediaServiceRepository.instance.getSearchResults(searchQuery, searchFilter)
                    .also {
                        if (it.suggestion.isNullOrEmpty()) onSearchSuggestion(null)
                        else onSearchSuggestion(it.suggestion to it.corrected)
                    }
            }

            val items = result.items.toMutableList()
            val isSongFilter = searchFilter in listOf("music_songs", "songs")
            if (params.key == null && searchQuery.isNotBlank() && isSongFilter) {
                val studioMaster = withContext(Dispatchers.IO) {
                    try {
                        app.libre.api.YtMusicApi.resolveStudioMaster(searchQuery)
                    } catch (_: Exception) { null }
                }
                if (studioMaster != null) {
                    val cleanId = studioMaster.url?.toID().orEmpty()
                    if (cleanId.isNotEmpty()) {
                        items.removeAll { it.url.toID() == cleanId }
                        items.add(0, studioMaster.toContentItem())
                    }
                }
            }

            LoadResult.Page(items, null, result.nextpage)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
