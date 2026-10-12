package app.libre.ui.extensions

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.updatePadding
import app.libre.R

/**
 * This function animates a view in a downward direction by a specified amount.
 *
 * @param duration The duration of the animation in milliseconds.
 * @param dy The distance to move the view along the Y-axis.
 * @param onEnd An optional lambda function that is invoked when the animation ends.
 */
fun View.animateDown(
    duration: Long,
    dy: Float,
    onEnd: () -> Unit = { }
) {
    this
        .animate()
        .withEndAction { onEnd.invoke() }
        .translationY(dy)
        .alpha(0f)
        .setDuration(duration)
        .start()
}

/**
 * Smoothly animates the bottom padding of a view to a target value.
 */
fun View.animatePaddingBottom(
    targetPadding: Int,
    duration: Long = 200L
) {
    val initialPadding = paddingBottom
    if (initialPadding == targetPadding) return

    (getTag(R.id.tag_padding_animator) as? ValueAnimator)?.cancel()

    ValueAnimator.ofInt(initialPadding, targetPadding).apply {
        this.duration = duration
        interpolator = DecelerateInterpolator()
        addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            this@animatePaddingBottom.updatePadding(bottom = value)
        }
        setTag(R.id.tag_padding_animator, this)
        start()
    }
}
