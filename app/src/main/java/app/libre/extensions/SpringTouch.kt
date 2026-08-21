package app.libre.extensions

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Adds smooth tactile spring elasticity touch feedback to any View.
 * Scales down to 0.94x on press and spring-bounces back on release/cancel.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.addSpringTouchFeedback(
    scaleDownTo: Float = 0.94f,
    haptic: Boolean = true
) {
    val scaleXAnim = SpringAnimation(this, DynamicAnimation.SCALE_X).apply {
        spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            stiffness = SpringForce.STIFFNESS_LOW
        }
    }
    val scaleYAnim = SpringAnimation(this, DynamicAnimation.SCALE_Y).apply {
        spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            stiffness = SpringForce.STIFFNESS_LOW
        }
    }

    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (haptic) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                scaleXAnim.animateToFinalPosition(scaleDownTo)
                scaleYAnim.animateToFinalPosition(scaleDownTo)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scaleXAnim.animateToFinalPosition(1.0f)
                scaleYAnim.animateToFinalPosition(1.0f)
            }
        }
        false
    }
}
