package app.libre.ui.adapters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import app.libre.api.PlaylistsHelper
import app.libre.constants.IntentData
import app.libre.databinding.CarouselPlaylistThumbnailBinding
import app.libre.helpers.ImageHelper
import app.libre.helpers.NavigationHelper
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.base.BaseActivity
import app.libre.ui.sheets.PlaylistOptionsBottomSheet
import app.libre.ui.viewholders.CarouselPlaylistViewHolder

data class CarouselPlaylist(
    val id: String,
    val title: String?,
    val thumbnail: String?
)

class CarouselPlaylistAdapter : ListAdapter<CarouselPlaylist, CarouselPlaylistViewHolder>(
    DiffUtilItemCallback()
) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): CarouselPlaylistViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return CarouselPlaylistViewHolder(CarouselPlaylistThumbnailBinding.inflate(layoutInflater))
    }

    override fun onBindViewHolder(
        holder: CarouselPlaylistViewHolder,
        position: Int
    ) {
        val item = getItem(position)!!

        with(holder.binding) {
            playlistName.text = item.title
            ImageHelper.loadImage(item.thumbnail, thumbnail)

            val type = PlaylistsHelper.getPlaylistType(item.id)
            root.transitionName = "playlist_transition_${item.id}"
            root.setOnClickListener {
                NavigationHelper.navigatePlaylist(root.context, item.id, type, root)
            }

            root.setOnLongClickListener {
                val playlistOptionsDialog = PlaylistOptionsBottomSheet()
                playlistOptionsDialog.arguments = Bundle().apply {
                    putString(IntentData.playlistId, item.id)
                    putString(IntentData.playlistName, item.title)
                    putSerializable(IntentData.playlistType, type)
                }
                playlistOptionsDialog.show((root.context as BaseActivity).supportFragmentManager)

                true
            }
        }
    }
}