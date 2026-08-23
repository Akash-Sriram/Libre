package app.libre.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.libre.R
import app.libre.api.PlaylistsHelper
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.databinding.ItemAddToPlaylistEntryBinding
import app.libre.databinding.SheetAddToPlaylistBinding
import app.libre.db.DatabaseHolder
import app.libre.db.obj.LocalPlaylist
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.parcelable
import app.libre.helpers.DuplicateAudioMatcher
import app.libre.helpers.ImageHelper
import app.libre.helpers.LocalPlaylistsCache
import app.libre.ui.sheets.ExpandedBottomSheet
import app.libre.util.PlayingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistDisplayItem(
    val playlist: LocalPlaylist,
    val songCount: Int,
    var isAlreadyInPlaylist: Boolean = false
)

class AddToPlaylistDialog : ExpandedBottomSheet(R.layout.sheet_add_to_playlist) {

    private var videoInfo: StreamItem? = null
    private var binding: SheetAddToPlaylistBinding? = null
    private var adapter: AddToPlaylistAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoInfo = arguments?.parcelable(IntentData.videoInfo)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = SheetAddToPlaylistBinding.bind(view)
        binding = b

        val targetStreams = videoInfo?.let { listOf(it) } ?: PlayingQueue.getStreams()
        if (targetStreams.isEmpty()) {
            dismiss()
            return
        }

        b.playlistsRecycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = AddToPlaylistAdapter(targetStreams)
        b.playlistsRecycler.adapter = adapter

        b.btnCreateNewPlaylist.addSpringTouchFeedback()
        b.btnCreateNewPlaylist.setOnClickListener {
            CreatePlaylistDialog().show(childFragmentManager, CreatePlaylistDialog::class.java.name)
        }

        childFragmentManager.setFragmentResultListener(
            CreatePlaylistDialog.CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
            this
        ) { _, resultBundle ->
            val isPlaylistCreated = resultBundle.getBoolean(IntentData.playlistTask)
            if (isPlaylistCreated) {
                lifecycleScope.launch(Dispatchers.IO) {
                    LocalPlaylistsCache.reload()
                    loadPlaylists()
                }
            }
        }

        loadPlaylists()
    }

    private fun loadPlaylists() {
        val targetStreams = videoInfo?.let { listOf(it) } ?: PlayingQueue.getStreams()
        if (targetStreams.isEmpty()) return

        // 1. Instant 0ms load directly from RAM cache
        val inMemoryItems = LocalPlaylistsCache.getDisplayItemsSync(targetStreams)
        if (inMemoryItems.isNotEmpty()) {
            adapter?.setItems(inMemoryItems)
            return
        }

        // 2. Fallback if cache not warmed yet
        lifecycleScope.launch(Dispatchers.IO) {
            LocalPlaylistsCache.reload()
            val freshItems = LocalPlaylistsCache.getDisplayItemsSync(targetStreams)
            withContext(Dispatchers.Main) {
                adapter?.setItems(freshItems)
            }
        }
    }

    private inner class AddToPlaylistAdapter(
        private val targetStreams: List<StreamItem>
    ) : RecyclerView.Adapter<AddToPlaylistAdapter.ViewHolder>() {

        private val items = ArrayList<PlaylistDisplayItem>()

        fun setItems(newItems: List<PlaylistDisplayItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemAddToPlaylistEntryBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemBinding = ItemAddToPlaylistEntryBinding.inflate(inflater, parent, false)
            return ViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val playlist = item.playlist

            holder.binding.playlistTitle.text = playlist.name
            val count = item.songCount
            val formattedCount = java.text.NumberFormat.getNumberInstance().format(count)
            holder.binding.playlistSongCount.text = if (count == 1) "1 song" else "$formattedCount songs"

            if (playlist.thumbnailUrl.isNotEmpty()) {
                ImageHelper.loadImage(playlist.thumbnailUrl, holder.binding.playlistThumbnail)
            } else {
                holder.binding.playlistThumbnail.setImageResource(R.drawable.ic_playlist)
            }

            holder.binding.alreadyAddedBadge.isVisible = item.isAlreadyInPlaylist
            holder.binding.addIcon.isVisible = !item.isAlreadyInPlaylist

            holder.binding.root.addSpringTouchFeedback()
            holder.binding.root.setOnClickListener {
                val context = requireContext().applicationContext
                val playlistName = playlist.name.orEmpty()
                if (item.isAlreadyInPlaylist) {
                    val songTitle = targetStreams.firstOrNull()?.title ?: "Song"
                    Toast.makeText(
                        context,
                        "\"$songTitle\" is already in $playlistName",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val message = try {
                        context.getString(R.string.added_to_playlist, playlistName)
                    } catch (e: Exception) {
                        "Added to playlist $playlistName"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    dismiss()

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            PlaylistsHelper.addToPlaylist(playlist.id.toString(), *targetStreams.toTypedArray())
                            LocalPlaylistsCache.reload()
                        } catch (e: Exception) {
                            android.util.Log.e("AddToPlaylistDialog", "Error adding to playlist", e)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        adapter = null
    }

    companion object {
        const val ADD_TO_PLAYLIST_DIALOG_DISMISSED_KEY = "add_to_playlist_dialog_dismissed"
    }
}
