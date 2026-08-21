package app.libre.ui.base

import androidx.activity.OnBackPressedCallback
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import app.libre.ui.activities.AbstractPlayerHostActivity
import app.libre.ui.models.CommonPlayerViewModel
import app.libre.ui.models.PlayerExpansionState

/**
 * Base fragment contract for media playback fragments (AudioPlayerFragment & PlayerFragment).
 * Standardizes top-priority predictive back handling and expansion state synchronization.
 */
abstract class BasePlayerFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    val commonPlayerViewModel by activityViewModels<CommonPlayerViewModel>()

    val baseActivity: AbstractPlayerHostActivity
        get() = activity as AbstractPlayerHostActivity

    private var activeBackCallback: OnBackPressedCallback? = null

    abstract fun isPlayerExpanded(): Boolean

    abstract fun collapsePlayerToMini()

    /**
     * Dynamically registers a top-priority OnBackPressedCallback whenever the player is expanded.
     * Ensures back gestures collapse the player before any background Fragment or NavHostFragment.
     */
    fun updateBackCallbackPriority() {
        activeBackCallback?.remove()
        if (isPlayerExpanded()) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isPlayerExpanded()) {
                        collapsePlayerToMini()
                        remove()
                        activeBackCallback = null
                    } else {
                        remove()
                        activeBackCallback = null
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
            activeBackCallback = callback
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        }
    }

    /**
     * Clears the back callback when the player is minimized.
     */
    fun clearBackCallbackPriority() {
        activeBackCallback?.remove()
        activeBackCallback = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearBackCallbackPriority()
    }
}
