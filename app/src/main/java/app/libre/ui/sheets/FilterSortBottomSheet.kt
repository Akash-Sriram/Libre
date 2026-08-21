package app.libre.ui.sheets

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.fragment.app.setFragmentResult
import app.libre.R
import app.libre.constants.IntentData
import app.libre.databinding.FilterSortSheetBinding
import app.libre.enums.ContentFilter
import app.libre.extensions.parcelableArrayList
import app.libre.obj.SelectableOption

class FilterSortBottomSheet : ExpandedBottomSheet(R.layout.filter_sort_sheet) {
    private var _binding: FilterSortSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var sortOptions: List<SelectableOption>

    private var selectedIndex = 0
    private var showUpcoming = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val arguments = requireArguments()
        sortOptions = arguments.parcelableArrayList(IntentData.sortOptions)!!
        showUpcoming = arguments.getBoolean(IntentData.showUpcoming)
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FilterSortSheetBinding.bind(view)
        addSortOptions()
        setInitialFiltersState()

        observeSortChanges()
        observeCheckboxFilters()
        observeFiltersChanges()
    }

    private fun addSortOptions() {
        sortOptions.forEachIndexed { i, option ->
            val rb = createRadioButton(i, option.name)

            binding.sortRadioGroup.addView(rb)

            if (option.isSelected) {
                selectedIndex = i
                binding.sortRadioGroup.check(rb.id)
            }
        }
    }

    private fun createRadioButton(index: Int, name: String): RadioButton {
        return RadioButton(context).apply {
            tag = index
            text = name
            layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
    }


    private fun setInitialFiltersState() {
        binding.filterVideos.isChecked = ContentFilter.VIDEOS.isEnabled
        binding.filterShorts.isChecked = ContentFilter.SHORTS.isEnabled
        binding.filterLivestreams.isChecked = ContentFilter.LIVESTREAMS.isEnabled
        binding.showUpcomingCheckbox.isChecked = showUpcoming
    }

    private fun observeSortChanges() {
        binding.sortRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            val index = group.findViewById<RadioButton>(checkedId).tag as Int
            selectedIndex = index
            notifyChange()
        }
    }

    private fun observeCheckboxFilters() {
        binding.showUpcomingCheckbox.setOnCheckedChangeListener { _, checked ->
            showUpcoming = checked
            notifyChange()
        }
    }

    private fun observeFiltersChanges() {
        binding.filters.setOnCheckedStateChangeListener { _, _ ->
            ContentFilter.VIDEOS.isEnabled = binding.filterVideos.isChecked
            ContentFilter.SHORTS.isEnabled = binding.filterShorts.isChecked
            ContentFilter.LIVESTREAMS.isEnabled = binding.filterLivestreams.isChecked
            notifyChange()
        }
    }

    private fun notifyChange() {
        setFragmentResult(
            requestKey = FILTER_SORT_REQUEST_KEY,
            result = Bundle().apply {
                putInt(IntentData.sortOptions, selectedIndex)
                putBoolean(IntentData.showUpcoming, showUpcoming)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FILTER_SORT_REQUEST_KEY = "filter_sort_request_key"
    }
}
