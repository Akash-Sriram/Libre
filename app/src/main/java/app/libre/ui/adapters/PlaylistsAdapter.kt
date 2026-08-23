package app.libre.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.ListAdapter
import app.libre.R
import app.libre.api.obj.Playlists
import app.libre.constants.IntentData
import app.libre.databinding.ItemLibraryPlaylistGridBinding
import app.libre.enums.PlaylistType
import app.libre.extensions.addSpringTouchFeedback
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.base.BaseActivity
import app.libre.ui.sheets.PlaylistOptionsBottomSheet
import app.libre.ui.sheets.PlaylistOptionsBottomSheet.Companion.PLAYLIST_OPTIONS_REQUEST_KEY
import app.libre.ui.viewholders.PlaylistsViewHolder

class PlaylistsAdapter(
    private val playlistType: PlaylistType
) : ListAdapter<Playlists, PlaylistsViewHolder>(
    DiffUtilItemCallback(areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id })
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistsViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ItemLibraryPlaylistGridBinding.inflate(layoutInflater, parent, false)
        return PlaylistsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistsViewHolder, position: Int) {
        val playlist = getItem(holder.bindingAdapterPosition)
        holder.binding.apply {
            // set imageview drawable as empty playlist if imageview empty
            if (playlist.thumbnail.isNullOrBlank()) {
                playlistThumbnail.setImageResource(R.drawable.ic_empty_playlist)
            } else {
                ImageHelper.loadImage(playlist.thumbnail, playlistThumbnail)
            }
            playlistTitle.text = playlist.name

            val count = playlist.videos
            val formattedCount = java.text.NumberFormat.getNumberInstance().format(count)
            videoCount.text = if (count == 1L) "1 song" else "$formattedCount songs"

            root.addSpringTouchFeedback(0.96f)
            root.setOnClickListener {
                NavigationHelper.navigatePlaylist(root.context, playlist.id, playlistType)
            }

            val fragmentManager = (root.context as BaseActivity).supportFragmentManager
            val showPlaylistOptions = {
                val playlistOptionsDialog = PlaylistOptionsBottomSheet()
                playlistOptionsDialog.arguments = android.os.Bundle().apply {
                    putString(IntentData.playlistId, playlist.id)
                    putString(IntentData.playlistName, playlist.name)
                    putSerializable(IntentData.playlistType, playlistType)
                }
                playlistOptionsDialog.show(
                    fragmentManager,
                    PlaylistOptionsBottomSheet::class.java.name
                )
            }

            root.setOnLongClickListener {
                showPlaylistOptions()
                true
            }
        }
    }

}
