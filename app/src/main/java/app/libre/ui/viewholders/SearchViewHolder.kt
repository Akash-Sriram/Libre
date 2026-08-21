package app.libre.ui.viewholders

import androidx.recyclerview.widget.RecyclerView
import app.libre.databinding.ChannelRowBinding
import app.libre.databinding.PlaylistTrackRowBinding
import app.libre.databinding.PlaylistsRowBinding
import app.libre.databinding.VideoRowBinding

class SearchViewHolder : RecyclerView.ViewHolder {
    var videoRowBinding: VideoRowBinding? = null
    var playlistTrackRowBinding: PlaylistTrackRowBinding? = null
    var channelRowBinding: ChannelRowBinding? = null
    var playlistRowBinding: PlaylistsRowBinding? = null

    constructor(binding: PlaylistTrackRowBinding) : super(binding.root) {
        playlistTrackRowBinding = binding
    }

    constructor(binding: VideoRowBinding) : super(binding.root) {
        videoRowBinding = binding
    }

    constructor(binding: ChannelRowBinding) : super(binding.root) {
        channelRowBinding = binding
    }

    constructor(binding: PlaylistsRowBinding) : super(binding.root) {
        playlistRowBinding = binding
    }
}
