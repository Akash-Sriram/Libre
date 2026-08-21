package app.libre.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import app.libre.R

class SingleViewTouchableMotionLayout(context: Context, attributeSet: AttributeSet? = null) :
    MotionLayout(context, attributeSet) {

    private val viewToDetectTouch: View?
        get() = findViewById<View>(R.id.main_container) ?: findViewById(R.id.audio_player_container)

    private val isAudioPlayer: Boolean
        get() = findViewById<View>(R.id.audio_player_container) != null

    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val viewRect = Rect()
    private val transitionListenerList = mutableListOf<TransitionListener?>()
    private val swipeDownListener = mutableListOf<() -> Unit>()
    private val gestureDetector = GestureDetector(context, Listener())

    private var startedMinimized = false
    private var isStrictlyDownSwipe = false
    private var touchInitialY = 0f
    private var isTouchDownInsideHitArea = false
    private var shouldInterceptTouchEvent = false

    init {
        super.setTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(p0: MotionLayout?, p1: Int, p2: Int, p3: Float) {
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionChange(p0, p1, p2, p3) }
            }

            override fun onTransitionCompleted(p0: MotionLayout?, p1: Int) {
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionCompleted(p0, p1) }
            }
        })
    }

    override fun setTransitionListener(listener: TransitionListener?) {
        addTransitionListener(listener)
    }

    override fun addTransitionListener(listener: TransitionListener?) {
        transitionListenerList += listener
    }

    private val swipeDismissListener = mutableListOf<() -> Unit>()

    private val swipeUpListener = mutableListOf<() -> Unit>()

    private inner class Listener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val pauseButton = findViewById<View>(R.id.miniPlayerPause) ?: findViewById<View>(R.id.play_imageView)
            if (pauseButton != null && pauseButton.visibility == View.VISIBLE) {
                val rect = Rect()
                pauseButton.getGlobalVisibleRect(rect)
                val location = IntArray(2)
                getLocationOnScreen(location)
                val globalX = e.x.toInt() + location[0]
                val globalY = e.y.toInt() + location[1]
                if (rect.contains(globalX, globalY)) {
                    return false
                }
            }
            setTransitionDuration(350)
            transitionToStart()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (startedMinimized) {
                val startX = e1?.x ?: 0f
                val endX = e2.x
                val diffX = endX - startX
                val diffY = e2.y - (e1?.y ?: 0f)
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY) && kotlin.math.abs(diffX) > 80 && kotlin.math.abs(velocityX) > 250) {
                    animateDismiss(if (diffX > 0) 1 else -1)
                    return true
                } else if (diffY > 80 && velocityY > 250) {
                    animateDismissDown()
                    return true
                }
                return false
            } else {
                val diffY = e2.y - (e1?.y ?: 0f)
                val diffX = e2.x - (e1?.x ?: 0f)
                if (diffY < -80 && velocityY < -250 && kotlin.math.abs(diffY) > kotlin.math.abs(diffX)) {
                    swipeUpListener.forEach { it.invoke() }
                    return true
                }
                return false
            }
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (startedMinimized) {
                if (isStrictlyDownSwipe && distanceY > 0) {
                    isStrictlyDownSwipe = false
                }

                if (isStrictlyDownSwipe && distanceY < -15F) {
                    animateDismissDown()
                    return true
                }
            }

            return false
        }
    }

    fun addSwipeUpListener(listener: () -> Unit) = apply {
        swipeUpListener.add(listener)
    }

    /**
     * Animate horizontal dismissal of mini player
     */
    fun animateDismiss(direction: Int) {
        val container = findViewById<View>(R.id.audio_player_container) ?: viewToDetectTouch ?: return
        val controls = findViewById<View>(R.id.miniPlayerControls)
        val targetTranslationX = (container.width.takeIf { it > 0 } ?: 1000) * direction.toFloat()

        controls?.animate()?.translationX(targetTranslationX)?.alpha(0f)?.setDuration(220)?.start()
        container.animate()
            .translationX(targetTranslationX)
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                container.translationX = 0f
                container.alpha = 1f
                controls?.translationX = 0f
                controls?.alpha = 1f
                swipeDismissListener.forEach { it.invoke() }
                swipeDownListener.forEach { it.invoke() }
            }
            .start()
    }

    /**
     * Animate downward dismissal of mini player
     */
    fun animateDismissDown() {
        val container = findViewById<View>(R.id.audio_player_container) ?: viewToDetectTouch ?: return
        val controls = findViewById<View>(R.id.miniPlayerControls)
        val targetTranslationY = (container.height.takeIf { it > 0 } ?: 200).toFloat()

        controls?.animate()?.translationY(targetTranslationY)?.alpha(0f)?.setDuration(200)?.start()
        container.animate()
            .translationY(targetTranslationY)
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                container.translationY = 0f
                container.alpha = 1f
                controls?.translationY = 0f
                controls?.alpha = 1f
                swipeDismissListener.forEach { it.invoke() }
                swipeDownListener.forEach { it.invoke() }
            }
            .start()
    }

    /**
     * Add a listener when the mini player is swiped horizontally or vertically to dismiss
     */
    fun addSwipeDismissListener(listener: () -> Unit) = apply {
        swipeDismissListener.add(listener)
    }

    /**
     * Add a listener when the view is swiped down while the current transition's state is in
     * end state (minimized state)
     */
    fun addSwipeDownListener(listener: () -> Unit) = apply {
        swipeDownListener.add(listener)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                shouldInterceptTouchEvent = false
                isTouchDownInsideHitArea = false
                startedMinimized = progress == 1F

                viewToDetectTouch?.getHitRect(viewRect)
                isTouchDownInsideHitArea = viewRect.contains(event.x.toInt(), event.y.toInt())
                touchInitialY = event.y
                isStrictlyDownSwipe = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!startedMinimized && !shouldInterceptTouchEvent && isTouchDownInsideHitArea) {
                    val deltaY = event.y - touchInitialY

                    // swipe down detected
                    if (deltaY > scaledTouchSlop) {
                        // start intercepting and consume the event ourselves in onTouchEvent()
                        shouldInterceptTouchEvent = true

                        // inject down MotionEvent from current position to properly trigger
                        // motion scene's swipe action
                        MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_DOWN
                            setLocation(event.x, event.y)
                        }.also { downEvent ->
                            onTouchEvent(downEvent)
                            downEvent.recycle()
                        }
                    }
                }
            }
        }

        return shouldInterceptTouchEvent
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (progress >= 0.95f) {
            viewToDetectTouch?.getHitRect(viewRect)
            if (viewRect.contains(event.x.toInt(), event.y.toInt())) {
                gestureDetector.onTouchEvent(event)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTouchDownInsideHitArea && startedMinimized) {
            // detect gesture only when the player is in minimized state
            gestureDetector.onTouchEvent(event)
        }

        return isTouchDownInsideHitArea && super.onTouchEvent(event)
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        // Prevent child scrollviews (comments, descriptions) from driving MotionLayout miniplayer transitions
        return false
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // Do nothing
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        // Do nothing
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) {
        // Do nothing
    }
}
