package app.libre.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.libre.databinding.QueueRowBinding
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.toID
import app.libre.helpers.ImageHelper
import app.libre.helpers.ThemeHelper
import app.libre.ui.viewholders.PlayingQueueViewHolder
import app.libre.util.PlayingQueue

class PlayingQueueAdapter(
    private val onQueueItemSelected: (String) -> Unit
) : RecyclerView.Adapter<PlayingQueueViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayingQueueViewHolder {
        val binding = QueueRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlayingQueueViewHolder(binding)
    }

    override fun getItemCount() = PlayingQueue.size()

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: PlayingQueueViewHolder, position: Int) {
        val streamItem = PlayingQueue.get(position) ?: return
        val currentIndex = PlayingQueue.currentIndex()
        val isCurrent = currentIndex == position

        holder.binding.apply {
            ImageHelper.loadImage(streamItem.thumbnail, thumbnail)
            val videoId = streamItem.url.orEmpty().toID()
            val localArtist = app.libre.helpers.LocalAudioMatcher.getArtistFromFile(videoId, streamItem.title)
            val localAlbum = app.libre.helpers.LocalAudioMatcher.getAlbumFromFile(videoId, streamItem.title)

            val rawArtist = localArtist
                ?: streamItem.uploaderName.orEmpty().replace(Regex("""\s*-\s*Topic\b""", RegexOption.IGNORE_CASE), "").trim()
            val displayArtist = app.libre.helpers.LocalAudioMatcher.normalizeArtistString(rawArtist).orEmpty()
            val displayAlbum = localAlbum ?: streamItem.albumName.orEmpty().trim()

            videoInfo.text = when {
                displayArtist.isNotEmpty() && displayAlbum.isNotEmpty() && !displayArtist.equals(displayAlbum, ignoreCase = true) -> "$displayArtist • $displayAlbum"
                displayArtist.isNotEmpty() -> displayArtist
                displayAlbum.isNotEmpty() -> displayAlbum
                else -> ""
            }
            duration.text = DateUtils.formatElapsedTime(streamItem.duration ?: 0)

            val localTitle = app.libre.helpers.LocalAudioMatcher.getTitleFromFile(videoId, streamItem.title)
            val baseTitle = (localTitle ?: streamItem.title).orEmpty()
            val trackNumber = app.libre.helpers.LocalAudioMatcher.getTrackNumberFromFile(videoId, streamItem.title)
            val displayTitle = app.libre.helpers.LocalAudioMatcher.formatTitleWithTrackNumber(baseTitle, trackNumber)

            if (isCurrent) {
                val primaryColor = ThemeHelper.getThemeColor(root.context, androidx.appcompat.R.attr.colorPrimary)
                title.setTextColor(primaryColor)
                title.text = "▶  " + displayTitle
            } else {
                val defaultTextColor = ThemeHelper.getThemeColor(root.context, android.R.attr.textColorPrimary)
                title.setTextColor(defaultTextColor)
                title.text = displayTitle
            }

            root.addSpringTouchFeedback(0.96f)

            root.setOnClickListener {
                val newVideoId = streamItem.url?.toID() ?: return@setOnClickListener

                val oldPosition = PlayingQueue.currentIndex()
                val newPosition = position
                PlayingQueue.updateCurrent(streamItem)

                // select the new item in the queue and update the selected item in the UI
                onQueueItemSelected(newVideoId)
                notifyItemChanged(oldPosition)
                notifyItemChanged(newPosition)
            }
        }
    }
}
