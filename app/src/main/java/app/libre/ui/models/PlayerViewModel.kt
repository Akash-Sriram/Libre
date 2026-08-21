package app.libre.ui.models

import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
class PlayerViewModel : ViewModel() {

    // this is only used to restore the subtitle after leaving PiP, the actual caption state
    // should always be read from the player's selected tracks!
    var currentCaptionId: String? = null

    /**
     * Whether an orientation change is in progress, so that the current player should be continued to use
     *
     * Set to true if the activity will be recreated due to an orientation change
     */
    var isOrientationChangeInProgress = false
}