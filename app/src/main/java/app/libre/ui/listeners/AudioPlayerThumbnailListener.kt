package app.libre.ui.listeners

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.os.postDelayed
import app.libre.ui.interfaces.AudioPlayerOptions
import kotlin.math.abs

class AudioPlayerThumbnailListener(context: Context, private val listener: AudioPlayerOptions) :
    View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private val gestureDetector = GestureDetector(context, GestureListener(), handler)
    private var isMoving = false
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && isMoving) {
            listener.onSwipeEnd()
            isMoving = false
            return false
        }

        if (event.action == MotionEvent.ACTION_DOWN) {
            isMoving = false
            downY = event.y
        }

        // When a clear downward swipe is detected, release the event so
        // SingleViewTouchableMotionLayout can intercept it and drive the
        // collapse (start → end) MotionLayout transition.
        if (event.action == MotionEvent.ACTION_MOVE) {
            val deltaY = event.y - downY
            val deltaX = event.x - (event.historySize.let { if (it > 0) event.getHistoricalX(0) else event.x })
            if (deltaY > SWIPE_DOWN_THRESHOLD && Math.abs(deltaY) > Math.abs(deltaX) * 1.5f) {
                isMoving = false
                return false  // hand off to MotionLayout for collapse
            }
        }

        runCatching {
            gestureDetector.onTouchEvent(event)
        }

        return true
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            handler.postDelayed(ACTION_INTERVAL, SINGLE_PRESS_TOKEN) {
                if (!isMoving) listener.onSingleTap()
            }

            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val insideThreshHold = abs(e2.y - e1!!.y) <= MOVEMENT_THRESHOLD

            // If the movement is inside threshold or scroll is horizontal then return false
            if (!isMoving && (insideThreshHold || abs(distanceX) > abs(distanceY))) {
                return false
            }

            isMoving = true

            listener.onSwipe(distanceY)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // remove to single press action from the queue
            handler.removeCallbacksAndMessages(SINGLE_PRESS_TOKEN)

            listener.onLongTap()
        }
    }

    companion object {
        private const val MOVEMENT_THRESHOLD = 10
        private val ACTION_INTERVAL = ViewConfiguration.getLongPressTimeout().toLong()
        private const val SINGLE_PRESS_TOKEN = "singlePress"
        // Minimum downward movement (px) to hand off the gesture to MotionLayout for player collapse
        private const val SWIPE_DOWN_THRESHOLD = 20f
    }
}
