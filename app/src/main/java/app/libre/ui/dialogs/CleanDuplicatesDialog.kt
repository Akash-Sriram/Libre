package app.libre.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.libre.R
import app.libre.databinding.ItemDuplicateGroupBinding
import app.libre.databinding.ItemDuplicateTrackEntryBinding
import app.libre.databinding.SheetCleanDuplicatesBinding
import app.libre.helpers.CanonicalTrack
import app.libre.helpers.DuplicateAudioMatcher
import app.libre.helpers.DuplicateGroup
import app.libre.helpers.ImageHelper
import app.libre.ui.extensions.setFormattedDuration
import app.libre.ui.sheets.ExpandedBottomSheet

class CleanDuplicatesDialog(
    private val duplicateGroups: List<DuplicateGroup>,
    private val onConfirmRemove: (indicesToRemove: List<Int>) -> Unit
) : ExpandedBottomSheet(R.layout.sheet_clean_duplicates) {

    private val selectedForRemoval = mutableSetOf<Int>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = SheetCleanDuplicatesBinding.bind(view)

        // Pre-select defaults for removal: keep the highest quality / local / JioSaavn or first item
        selectedForRemoval.clear()
        for (group in duplicateGroups) {
            val items = group.items
            if (items.size < 2) continue

            // Determine which item to keep (prefer local file > JioSaavn > first item)
            val keepItem = items.firstOrNull { it.localFilePath != null }
                ?: items.firstOrNull { it.isJioSaavn }
                ?: items.first()

            for (item in items) {
                if (item.originalIndex != keepItem.originalIndex) {
                    selectedForRemoval.add(item.originalIndex)
                }
            }
        }

        val totalDuplicates = selectedForRemoval.size
        binding.duplicateHeaderTitle.text = getString(R.string.duplicates_found_title, totalDuplicates)
        updateButtonText(binding)

        binding.duplicateRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.duplicateRecycler.adapter = DuplicateGroupsAdapter(duplicateGroups) {
            updateButtonText(binding)
        }

        binding.btnRemoveDuplicates.setOnClickListener {
            val toRemove = selectedForRemoval.toList().sortedDescending()
            dismiss()
            onConfirmRemove(toRemove)
        }
    }

    private fun updateButtonText(binding: SheetCleanDuplicatesBinding) {
        val count = selectedForRemoval.size
        binding.btnRemoveDuplicates.isEnabled = count > 0
        binding.btnRemoveDuplicates.text = getString(R.string.remove_duplicates_btn, count)
    }

    private inner class DuplicateGroupsAdapter(
        private val groups: List<DuplicateGroup>,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<DuplicateGroupsAdapter.GroupViewHolder>() {

        inner class GroupViewHolder(val binding: ItemDuplicateGroupBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemDuplicateGroupBinding.inflate(inflater, parent, false)
            return GroupViewHolder(binding)
        }

        override fun getItemCount(): Int = groups.size

        override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
            val group = groups[position]
            val albumText = if (group.primaryAlbum.isNotBlank()) " • ${group.primaryAlbum.replaceFirstChar { it.uppercase() }}" else ""
            holder.binding.groupTitle.text = "${group.primaryTitle}$albumText"

            holder.binding.entriesContainer.removeAllViews()
            val inflater = LayoutInflater.from(holder.itemView.context)

            for (track in group.items) {
                val entryBinding = ItemDuplicateTrackEntryBinding.inflate(inflater, holder.binding.entriesContainer, false)

                entryBinding.entryTitle.text = track.originalItem.title
                ImageHelper.loadImage(track.originalItem.thumbnail, entryBinding.entryThumbnail)
                entryBinding.entryDuration.setFormattedDuration(track.duration, false, 0L)

                if (track.isJioSaavn) {
                    entryBinding.entrySourceBadge.text = "JioSaavn"
                    entryBinding.entrySourceBadge.setTextColor(android.graphics.Color.parseColor("#00B368"))
                    entryBinding.entrySourceBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2200B368"))
                } else {
                    entryBinding.entrySourceBadge.text = "YouTube"
                    entryBinding.entrySourceBadge.setTextColor(android.graphics.Color.parseColor("#E53935"))
                    entryBinding.entrySourceBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#22E53935"))
                }

                fun updateStatus(isMarkedForRemoval: Boolean) {
                    if (isMarkedForRemoval) {
                        entryBinding.entryStatusTag.text = "Remove"
                        entryBinding.entryStatusTag.setTextColor(android.graphics.Color.parseColor("#E53935"))
                        entryBinding.root.alpha = 0.65f
                    } else {
                        entryBinding.entryStatusTag.text = "Keep"
                        entryBinding.entryStatusTag.setTextColor(android.graphics.Color.parseColor("#00B368"))
                        entryBinding.root.alpha = 1.0f
                    }
                }

                val isSelected = track.originalIndex in selectedForRemoval
                entryBinding.entryCheckbox.isChecked = isSelected
                updateStatus(isSelected)

                entryBinding.entryCheckbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedForRemoval.add(track.originalIndex)
                    } else {
                        selectedForRemoval.remove(track.originalIndex)
                    }
                    updateStatus(isChecked)
                    onSelectionChanged()
                }

                entryBinding.root.setOnClickListener {
                    entryBinding.entryCheckbox.toggle()
                }

                holder.binding.entriesContainer.addView(entryBinding.root)
            }
        }
    }
}
