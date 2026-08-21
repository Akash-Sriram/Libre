package app.libre.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.libre.R
import app.libre.api.PlaylistsHelper
import app.libre.api.obj.Playlists
import app.libre.constants.IntentData
import app.libre.constants.PreferenceKeys
import app.libre.databinding.FragmentLibraryBinding
import app.libre.db.DatabaseHolder
import app.libre.extensions.TAG
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.ceilHalf
import app.libre.extensions.dpToPx
import app.libre.helpers.PreferenceHelper
import app.libre.ui.adapters.PlaylistBookmarkAdapter
import app.libre.ui.adapters.PlaylistsAdapter
import app.libre.ui.base.DynamicLayoutManagerFragment
import app.libre.ui.dialogs.CreatePlaylistDialog
import app.libre.ui.dialogs.CreatePlaylistDialog.Companion.CREATE_PLAYLIST_DIALOG_REQUEST_KEY
import app.libre.ui.models.CommonPlayerViewModel
import app.libre.ui.activities.MainActivity
import app.libre.ui.sheets.BaseBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : DynamicLayoutManagerFragment(R.layout.fragment_library) {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()

    private val playlistsAdapter = PlaylistsAdapter(PlaylistsHelper.getPrivatePlaylistType())
    private val playlistBookmarkAdapter = PlaylistBookmarkAdapter()

    override fun setLayoutManagers(gridItems: Int) {
        val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        _binding?.bookmarksRecView?.layoutManager = GridLayoutManager(context, spanCount)
        _binding?.playlistRecView?.layoutManager = GridLayoutManager(context, spanCount)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLibraryBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        binding.playlistRecView.layoutManager = GridLayoutManager(context, spanCount)
        binding.bookmarksRecView.layoutManager = GridLayoutManager(context, spanCount)

        binding.bookmarksRecView.adapter = playlistBookmarkAdapter
        binding.playlistRecView.adapter = playlistsAdapter

        // Apply tactile spring feedback to FAB and create buttons
        binding.btnCreatePlaylist.addSpringTouchFeedback()
        binding.searchFab.addSpringTouchFeedback()

        // listen for playlists to become deleted
        playlistsAdapter.registerAdapterDataObserver(object :
            RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                _binding?.nothingHere?.isVisible = playlistsAdapter.itemCount == 0
                _binding?.sortTV?.isVisible = playlistsAdapter.itemCount > 0
                super.onItemRangeRemoved(positionStart, itemCount)
            }
        })

        fetchPlaylists()
        initBookmarks()

        binding.btnCreatePlaylist.setOnClickListener {
            CreatePlaylistDialog().show(childFragmentManager, CreatePlaylistDialog::class.java.name)
        }

        childFragmentManager.setFragmentResultListener(
            CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
            this
        ) { _, resultBundle ->
            val isPlaylistCreated = resultBundle.getBoolean(IntentData.playlistTask)
            if (isPlaylistCreated) {
                fetchPlaylists()
            }
        }
        binding.searchFab.setOnClickListener {
            findNavController().navigate(R.id.openSearch)
        }

        val sortOptions = resources.getStringArray(R.array.playlistSortingOptions)
        val sortOptionValues = resources.getStringArray(R.array.playlistSortingOptionsValues)
        val order = PreferenceHelper.getString(
            PreferenceKeys.PLAYLISTS_ORDER,
            sortOptionValues.first()
        )
        val orderIndex = sortOptionValues.indexOf(order)
        binding.sortTV.text = sortOptions.getOrNull(orderIndex)

        binding.sortTV.setOnClickListener {
            BaseBottomSheet().apply {
                setSimpleItems(sortOptions.toList()) { index ->
                    binding.sortTV.text = sortOptions[index]
                    val value = sortOptionValues[index]
                    PreferenceHelper.putString(PreferenceKeys.PLAYLISTS_ORDER, value)
                    fetchPlaylists()
                }
            }.show(childFragmentManager)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        fetchPlaylists()
        initBookmarks()
    }

    private fun initBookmarks() {
        viewLifecycleOwner.lifecycleScope.launch {
            val bookmarks = withContext(Dispatchers.IO) {
                DatabaseHolder.Database.playlistBookmarkDao().getAll()
            }

            val binding = _binding ?: return@launch

            binding.bookmarksContainer.isVisible = bookmarks.isNotEmpty()
            if (bookmarks.isNotEmpty()) {
                playlistBookmarkAdapter.submitList(bookmarks)
            }
        }
    }

    private fun fetchPlaylists() {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = try {
                withContext(Dispatchers.IO) {
                    PlaylistsHelper.getPlaylists()
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val binding = _binding ?: return@launch

            // also update playlists recycler when the playlists are empty in order to remove
            // playlists that were removed by the user
            showPlaylists(playlists)
            if (playlists.isEmpty()) {
                binding.sortTV.isVisible = false
                binding.nothingHere.isVisible = true
            }
        }
    }

    private fun showPlaylists(playlists: List<Playlists>) {
        val binding = _binding ?: return

        binding.nothingHere.isGone = true
        binding.sortTV.isVisible = true
        playlistsAdapter.submitList(playlists)
    }
}
