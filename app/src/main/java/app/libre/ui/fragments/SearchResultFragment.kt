package app.libre.ui.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.libre.R
import app.libre.databinding.FragmentSearchResultBinding
import app.libre.extensions.ceilHalf
import app.libre.ui.activities.MainActivity
import app.libre.ui.adapters.SearchResultsAdapter
import app.libre.ui.base.DynamicLayoutManagerFragment
import app.libre.ui.extensions.setOnBackPressed
import app.libre.ui.models.SearchResultViewModel
import app.libre.util.TextUtils.toTimeInSeconds
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SearchResultFragment : DynamicLayoutManagerFragment(R.layout.fragment_search_result) {
    private var _binding: FragmentSearchResultBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<SearchResultFragmentArgs>()
    private val viewModel by viewModels<SearchResultViewModel>()

    private val mainActivity get() = activity as MainActivity
    private var recyclerViewState: Parcelable? = null

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.searchRecycler?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSearchResultBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // fixes a bug that the search query will stay the old one when searching for multiple
        // different queries in a row and navigating to the previous ones through back presses
        mainActivity.setQuerySilent(args.query)
        mainActivity.clearSearchViewFocus()

        val timeStamp = args.query.toHttpUrlOrNull()?.queryParameter("t")?.toTimeInSeconds()
        val searchResultsAdapter = SearchResultsAdapter(timeStamp ?: 0)
        binding.searchRecycler.adapter = searchResultsAdapter

        var isUpdatingChips = false

        // Dynamic Contextual Type Chips & Filter Logic
        val updateTypeChipVisibility = {
            isUpdatingChips = true
            val sourceId = binding.sourceChipGroup.checkedChipId
            binding.apply {
                when (sourceId) {
                    R.id.chip_source_ytm -> {
                        chipTypeSongs.isVisible = true
                        chipTypeAlbums.isVisible = true
                        chipTypePlaylists.isVisible = true
                        chipTypeVideos.isVisible = true
                        chipTypeVideos.text = "Music Videos"
                        chipTypeChannels.isVisible = false
                        if (typeChipGroup.checkedChipId !in listOf(R.id.chip_type_songs, R.id.chip_type_albums, R.id.chip_type_playlists, R.id.chip_type_videos)) {
                            chipTypeSongs.isChecked = true
                        }
                    }
                    R.id.chip_source_jiosaavn -> {
                        chipTypeSongs.isVisible = true
                        chipTypeAlbums.isVisible = true
                        chipTypePlaylists.isVisible = true
                        chipTypeVideos.isVisible = false
                        chipTypeChannels.isVisible = false
                        if (typeChipGroup.checkedChipId !in listOf(R.id.chip_type_songs, R.id.chip_type_albums, R.id.chip_type_playlists)) {
                            chipTypeSongs.isChecked = true
                        }
                    }
                    else -> { // YouTube (Default)
                        chipTypeVideos.isVisible = true
                        chipTypeVideos.text = "Videos"
                        chipTypeChannels.isVisible = true
                        chipTypePlaylists.isVisible = true
                        chipTypeSongs.isVisible = false
                        chipTypeAlbums.isVisible = false
                        if (typeChipGroup.checkedChipId !in listOf(R.id.chip_type_videos, R.id.chip_type_channels, R.id.chip_type_playlists)) {
                            chipTypeVideos.isChecked = true
                        }
                    }
                }
            }
            isUpdatingChips = false
        }

        val applyFilters = {
            val sourceId = binding.sourceChipGroup.checkedChipId
            val typeId = binding.typeChipGroup.checkedChipId

            val filterString = when (sourceId) {
                R.id.chip_source_ytm -> {
                    when (typeId) {
                        R.id.chip_type_albums -> "music_albums"
                        R.id.chip_type_playlists -> "music_playlists"
                        R.id.chip_type_videos -> "music_videos"
                        R.id.chip_type_songs -> "music_songs"
                        else -> "music_songs"
                    }
                }
                R.id.chip_source_jiosaavn -> {
                    when (typeId) {
                        R.id.chip_type_albums -> "jiosaavn_albums"
                        R.id.chip_type_playlists -> "jiosaavn_playlists"
                        R.id.chip_type_songs -> "jiosaavn"
                        else -> "jiosaavn"
                    }
                }
                else -> { // YouTube
                    when (typeId) {
                        R.id.chip_type_channels -> "channels"
                        R.id.chip_type_playlists -> "playlists"
                        R.id.chip_type_videos -> "videos"
                        else -> "videos"
                    }
                }
            }

            viewModel.setFilter(filterString)
        }

        updateTypeChipVisibility()

        binding.sourceChipGroup.setOnCheckedStateChangeListener { _, _ ->
            if (!isUpdatingChips) {
                updateTypeChipVisibility()
                applyFilters()
            }
        }
        binding.typeChipGroup.setOnCheckedStateChangeListener { _, _ ->
            if (!isUpdatingChips) {
                applyFilters()
            }
        }

        binding.searchRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchResultsAdapter.loadStateFlow.collectLatest { loadStates ->
                    val refreshState = loadStates.refresh
                    val isLoading = refreshState is LoadState.Loading
                    binding.progress.isVisible = isLoading && searchResultsAdapter.itemCount == 0
                    if (!isLoading) {
                        binding.searchRecycler.isVisible = true
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchResultsFlow.collectLatest {
                    searchResultsAdapter.submitData(it)
                }
            }
        }

        viewModel.searchSuggestion.observe(viewLifecycleOwner) { suggestion ->
            binding.searchSuggestionContainer.isVisible = suggestion != null
            binding.searchSuggestionContainer.setOnClickListener(null)
            if (suggestion == null) return@observe

            val (suggestion, corrected) = suggestion
            binding.searchSuggestion.text = suggestion
            binding.searchSuggestionLabel.text = if (corrected) {
                getString(R.string.showing_results_for)
            } else {
                binding.searchSuggestionContainer.setOnClickListener {
                    mainActivity.setQuery(suggestion, true)
                }
                getString(R.string.did_you_mean)
            }
        }

    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.searchRecycler.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
