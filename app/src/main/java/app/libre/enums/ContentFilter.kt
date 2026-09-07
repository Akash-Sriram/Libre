package app.libre.enums

import app.libre.helpers.PreferenceHelper

private const val SELECTED_FEED_FILTERS = "filter_feed"

enum class ContentFilter {
    VIDEOS,
    SHORTS,
    LIVESTREAMS;

    var isEnabled
        get() = name in enabledFiltersSet
        set(enabled) {
            val newFilters = enabledFiltersSet
                .apply { if (enabled) add(name) else remove(name) }

            PreferenceHelper.putStringSet(SELECTED_FEED_FILTERS, newFilters)
        }

    companion object {
        private val enabledFiltersSet: MutableSet<String>
            get() = PreferenceHelper
                .getStringSet(SELECTED_FEED_FILTERS, entries.mapTo(mutableSetOf()) { it.name })
                .toMutableSet()
    }
}
