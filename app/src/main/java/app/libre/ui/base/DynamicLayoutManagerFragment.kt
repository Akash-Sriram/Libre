package app.libre.ui.base

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import app.libre.R

abstract class DynamicLayoutManagerFragment(@LayoutRes layoutResId: Int) : Fragment(layoutResId) {
    abstract fun setLayoutManagers(gridItems: Int)

    private fun getGridItemsCount(orientation: Int): Int {
        val resId = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            R.integer.grid_items
        } else {
            R.integer.grid_items_landscape
        }
        return resources.getInteger(resId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        setLayoutManagers(getGridItemsCount(newConfig.orientation))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setLayoutManagers(getGridItemsCount(resources.configuration.orientation))
    }
}
