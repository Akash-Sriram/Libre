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
            title.text = streamItem.title
            videoInfo.text = streamItem.uploaderName.orEmpty().ifEmpty { streamItem.albumName.orEmpty() }
            duration.text = DateUtils.formatElapsedTime(streamItem.duration ?: 0)

            if (isCurrent) {
                val primaryColor = ThemeHelper.getThemeColor(root.context, androidx.appcompat.R.attr.colorPrimary)
                title.setTextColor(primaryColor)
                title.text = "▶  " + streamItem.title
            } else {
                val defaultTextColor = ThemeHelper.getThemeColor(root.context, android.R.attr.textColorPrimary)
                title.setTextColor(defaultTextColor)
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
