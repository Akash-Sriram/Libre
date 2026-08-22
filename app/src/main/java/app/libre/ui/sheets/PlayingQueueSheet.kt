package app.libre.ui.sheets

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import app.libre.R
import app.libre.constants.IntentData
import app.libre.databinding.QueueBottomSheetBinding
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.setActionListener
import app.libre.extensions.toID
import app.libre.ui.adapters.PlayingQueueAdapter
import app.libre.util.PlayingQueue
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PlayingQueueSheet : ExpandedBottomSheet(R.layout.queue_bottom_sheet) {
    private var _binding: QueueBottomSheetBinding? = null
    private val binding get() = _binding!!

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = QueueBottomSheetBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.shuffle.addSpringTouchFeedback()
        binding.sort.addSpringTouchFeedback()
        binding.clearQueue.addSpringTouchFeedback()

        binding.optionsRecycler.layoutManager = LinearLayoutManager(context)
        binding.optionsRecycler.setHasFixedSize(true)
        binding.optionsRecycler.setItemViewCacheSize(20)
        val adapter = PlayingQueueAdapter { videoId ->
            setFragmentResult(
                PLAYING_QUEUE_REQUEST_KEY,
                Bundle().apply { putString(IntentData.videoId, videoId) }
            )
            updateQueueHeader()
        }
        binding.optionsRecycler.adapter = adapter

        updateQueueHeader()

        // scroll to the currently playing video in the queue
        val currentPlayingIndex = PlayingQueue.currentIndex()
        if (currentPlayingIndex != -1) binding.optionsRecycler.scrollToPosition(currentPlayingIndex)

        binding.shuffle.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val currentIndex = PlayingQueue.currentIndex()
            val start = currentIndex + 1
            val count = PlayingQueue.size() - start
            if (count > 0) {
                PlayingQueue.shuffleUpcoming()
                adapter.notifyItemRangeChanged(start, count)
                updateQueueHeader()
            }
        }

        binding.clearQueue.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.tooltip_clear_queue)
                .setPositiveButton(R.string.okay) { _, _ ->
                    val currentIndex = PlayingQueue.currentIndex()
                    PlayingQueue.setStreams(
                        PlayingQueue.getStreams()
                            .filterIndexed { index, _ -> index == currentIndex }
                    )
                    adapter.notifyDataSetChanged()
                    updateQueueHeader()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.sort.setOnClickListener {
            showSortDialog(adapter)
        }

        binding.optionsRecycler.setActionListener(
            allowSwipe = true,
            allowDrag = true,
            onDismissedListener = { position ->
                if (position == PlayingQueue.currentIndex()) {
                    adapter.notifyItemChanged(position)
                    return@setActionListener
                }
                PlayingQueue.remove(position)
                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(position, adapter.itemCount)
                updateQueueHeader()
            },
            onDragListener = { from, to ->
                PlayingQueue.move(from, to)
                adapter.notifyItemMoved(from, to)
            }
        )
    }

    private fun updateQueueHeader() {
        val binding = _binding ?: return
        val count = PlayingQueue.size()
        val totalDurationSeconds = PlayingQueue.getStreams().sumOf { it.duration ?: 0L }
        
        val countStr = if (count == 1) "1 song" else "$count songs"
        val durationStr = formatCleanDuration(totalDurationSeconds)

        binding.queueInfo.text = if (durationStr.isNotEmpty()) "$countStr • $durationStr" else countStr
    }

    private fun formatCleanDuration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return ""
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "${hours} hr ${minutes} min"
            hours > 0 -> "${hours} hr"
            minutes > 0 -> "${minutes} min"
            else -> "${totalSeconds} sec"
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showSortDialog(adapter: PlayingQueueAdapter) {
        val sortOptions = listOf(
            R.string.title,
            R.string.uploader_name,
            R.string.duration,
            R.string.tooltip_reverse
        )
            .map { requireContext().getString(it) }
            .toTypedArray()

        BaseBottomSheet().apply {
            setTitle(this@PlayingQueueSheet.getString(R.string.sort_by))
            setSimpleItems(sortOptions.toList()) { index ->
                val streams = PlayingQueue.getStreams()
                val currentIndex = PlayingQueue.currentIndex()
                val currentTrack = streams.getOrNull(currentIndex)

                val newQueue = when (index) {
                    0 -> streams.sortedBy { it.title.orEmpty().lowercase() }
                    1 -> streams.sortedBy { it.uploaderName.orEmpty().lowercase() }
                    2 -> streams.sortedBy { it.duration ?: 0L }
                    3 -> streams.reversed()
                    else -> streams
                }
                PlayingQueue.setStreams(newQueue)
                if (currentTrack != null) {
                    PlayingQueue.updateCurrent(currentTrack)
                }
                adapter.notifyDataSetChanged()
                updateQueueHeader()
            }
        }.show(childFragmentManager)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PLAYING_QUEUE_REQUEST_KEY = "playing_queue_request_key"
    }
}
