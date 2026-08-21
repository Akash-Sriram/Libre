package app.libre.ui.models

import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.libre.ui.dialogs.ShareDialog
import app.libre.ui.fragments.SearchResultFragmentArgs
import app.libre.ui.models.sources.SearchPagingSource
import app.libre.util.TextUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

class SearchResultViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val args = SearchResultFragmentArgs.fromSavedStateHandle(savedStateHandle)

    // parse search URLs from YouTube or JioSaavn entered in the search bar
    private val searchQuery = run {
        val uri = args.query.toUri()
        val host = uri.host.orEmpty()
        if (host.contains("jiosaavn.com")) {
            val lastSegment = uri.lastPathSegment
            val type = uri.pathSegments.getOrNull(0)
            if (lastSegment != null && (type == "album" || type == "featured")) {
                // Return just the ID so the playlist repository handles it as a JioSaavn query
                return@run lastSegment
            }
        }
        TextUtils.getVideoIdFromUri(uri)?.let { videoId ->
            "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
        } ?: args.query
    }

    private val filterMutableData = MutableStateFlow("combined_all")

    val searchSuggestion = MutableLiveData<Pair<String, Boolean>?>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResultsFlow = filterMutableData.flatMapLatest {
        Pager(
            PagingConfig(pageSize = 20, enablePlaceholders = false),
            pagingSourceFactory = {
                SearchPagingSource(searchQuery, it) { suggestion ->
                    searchSuggestion.postValue(suggestion)
                }
            }
        ).flow
    }
        .cachedIn(viewModelScope)

    fun setFilter(filter: String) {
        if (filterMutableData.value != filter) {
            filterMutableData.value = filter
        }
    }
}
