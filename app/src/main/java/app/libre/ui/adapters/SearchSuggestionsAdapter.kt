package app.libre.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import app.libre.databinding.SuggestionRowBinding
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.viewholders.SuggestionsViewHolder
import app.libre.R
import app.libre.db.obj.SearchHistoryItem
import app.libre.enums.SearchDataType
import app.libre.obj.SearchDataItem
import kotlin.collections.plus

class SearchSuggestionsAdapter(
    private val onRootClickListener: (String) -> Unit,
    private val onArrowClickListener: (String) -> Unit,
    private val onSearchHistoryItemDeleted: (SearchHistoryItem) -> Unit,
) : ListAdapter<SearchDataItem, SuggestionsViewHolder>(DiffUtilItemCallback<SearchDataItem>()) {

    /**
     *  Allow submit list partially, either [historyList] only or [suggestionList] only, without
     *  updating the whole list.
     */
    fun submitSearchSuggestions(
        historyList: List<SearchDataItem>?,
        suggestionList: List<SearchDataItem>?,
        commitCallback: Runnable? = null,
    ) {
        if (historyList == null && suggestionList == null) return

        val oldList = currentList.toList()
        val histories = historyList ?: oldList.filter { it.type == SearchDataType.HISTORY }
        val suggestions = suggestionList ?: oldList.filter { it.type == SearchDataType.SUGGESTION }
        val newList = (histories + suggestions).distinctBy { it.query }

        super.submitList(newList, commitCallback)
    }

    /**
     * @see [submitSearchSuggestions]
     */
    @Deprecated("Use `submitSearchSuggestions()` instead.")
    override fun submitList(list: List<SearchDataItem>?) {}

    /**
     * @see [submitSearchSuggestions]
     */
    @Deprecated("Use `submitSearchSuggestions()` instead.")
    override fun submitList(list: List<SearchDataItem>?, commitCallback: Runnable?) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionsViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = SuggestionRowBinding.inflate(layoutInflater, parent, false)
        return SuggestionsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionsViewHolder, position: Int) {
        val item = getItem(holder.bindingAdapterPosition)
        val suggestion = item.query

        holder.binding.apply {
            when (item.type) {
                SearchDataType.HISTORY -> {
                    leadingIcon.setImageResource(R.drawable.ic_history)
                    deleteHistory.isVisible = true
                    arrow.isVisible = false
                    deleteHistory.setOnClickListener {
                        onSearchHistoryItemDeleted(SearchHistoryItem(suggestion))
                    }
                }

                SearchDataType.SUGGESTION -> {
                    leadingIcon.setImageResource(R.drawable.ic_search)
                    deleteHistory.isVisible = false
                    arrow.isVisible = true
                    arrow.setOnClickListener {
                        onArrowClickListener(suggestion)
                    }
                }
            }
            suggestionText.text = suggestion
            root.setOnClickListener {
                onRootClickListener(suggestion)
            }
        }
    }
}
