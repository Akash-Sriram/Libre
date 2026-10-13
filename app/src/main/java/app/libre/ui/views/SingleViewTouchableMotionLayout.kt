package app.libre.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
    private var touchInitialX = 0f
    private var touchInitialY = 0f
    private var isTouchDownInsideHitArea = false
    private var shouldInterceptTouchEvent = false
    private var isDraggingMiniPlayer = false
    private var isDismissing = false

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
            val nextButton = findViewById<View>(R.id.miniPlayerNext)
            val interactiveButtons = listOfNotNull(pauseButton, nextButton)
            val location = IntArray(2)
            getLocationOnScreen(location)
            val globalX = e.x.toInt() + location[0]
            val globalY = e.y.toInt() + location[1]
            for (button in interactiveButtons) {
                if (button.visibility == View.VISIBLE) {
                    val rect = Rect()
                    button.getGlobalVisibleRect(rect)
                    if (rect.contains(globalX, globalY)) {
                        return false
                    }
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
            if (startedMinimized && !isDismissing) {
                val startX = e1?.x ?: 0f
                val endX = e2.x
                val diffX = endX - startX
                val diffY = e2.y - (e1?.y ?: 0f)
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY) && kotlin.math.abs(diffX) > 60 && kotlin.math.abs(velocityX) > 250) {
                    animateDismiss(if (diffX > 0) 1 else -1)
                    return true
                } else if (diffY > 60 && velocityY > 250) {
                    animateDismissDown()
                    return true
                } else if (diffY < -60 && velocityY < -250) {
                    setTransitionDuration(350)
                    transitionToStart()
                    return true
                }
                return false
            } else if (!startedMinimized) {
                val diffY = e2.y - (e1?.y ?: 0f)
                val diffX = e2.x - (e1?.x ?: 0f)
                if (diffY < -80 && velocityY < -250 && kotlin.math.abs(diffY) > kotlin.math.abs(diffX)) {
                    swipeUpListener.forEach { it.invoke() }
                    return true
                }
                return false
            }
            return false
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
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
        isDismissing = true

        controls?.animate()
            ?.translationX(targetTranslationX)
            ?.alpha(0f)
            ?.setDuration(200)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.start()

        container.animate()
            .translationX(targetTranslationX)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
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
        val targetTranslationY = (container.height.takeIf { it > 0 } ?: 200).toFloat() * 1.5f
        isDismissing = true

        controls?.animate()
            ?.translationY(targetTranslationY)
            ?.alpha(0f)
            ?.setDuration(200)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.start()

        container.animate()
            .translationY(targetTranslationY)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
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
                isDraggingMiniPlayer = false
                startedMinimized = progress >= 0.95f || currentState == R.id.end

                viewToDetectTouch?.getHitRect(viewRect)
                isTouchDownInsideHitArea = viewRect.contains(event.x.toInt(), event.y.toInt())
                touchInitialX = event.x
                touchInitialY = event.y
                isStrictlyDownSwipe = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (startedMinimized && isTouchDownInsideHitArea && !isDismissing) {
                    val dx = event.x - touchInitialX
                    val dy = event.y - touchInitialY

                    if (!isDraggingMiniPlayer) {
                        if (kotlin.math.abs(dx) > scaledTouchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            isDraggingMiniPlayer = true
                            shouldInterceptTouchEvent = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (dy > scaledTouchSlop && dy > kotlin.math.abs(dx)) {
                            isDraggingMiniPlayer = true
                            shouldInterceptTouchEvent = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                } else if (!startedMinimized && !shouldInterceptTouchEvent && isTouchDownInsideHitArea) {
                    val deltaY = event.y - touchInitialY

                    // swipe down detected
                    if (deltaY > scaledTouchSlop) {
                        shouldInterceptTouchEvent = true

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
        val isMinimized = progress >= 0.95f || currentState == R.id.end
        if (isMinimized) {
            val touchView = viewToDetectTouch
            if (touchView != null) {
                touchView.getHitRect(viewRect)
                if (event.action == MotionEvent.ACTION_DOWN) {
                    isTouchDownInsideHitArea = viewRect.contains(event.x.toInt(), event.y.toInt())
                }
                if (!isTouchDownInsideHitArea) {
                    // Touch occurred outside mini player (e.g. on playlist/album tracks).
                    // Do not intercept or consume touches so underlying fragments can scroll freely without expanding player.
                    return false
                }
            }
            gestureDetector.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isMinimized = progress >= 0.95f || currentState == R.id.end
        if (isMinimized && !isTouchDownInsideHitArea) return false

        if (startedMinimized && !isDismissing) {
            val container = findViewById<View>(R.id.audio_player_container) ?: viewToDetectTouch
            val controls = findViewById<View>(R.id.miniPlayerControls)

            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchInitialX
                    val dy = event.y - touchInitialY

                    if (!isDraggingMiniPlayer) {
                        if (kotlin.math.abs(dx) > scaledTouchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            isDraggingMiniPlayer = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (dy > scaledTouchSlop && dy > kotlin.math.abs(dx)) {
                            isDraggingMiniPlayer = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }

                    if (isDraggingMiniPlayer && container != null) {
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            // 1:1 Horizontal drag tracking
                            container.translationX = dx
                            container.translationY = 0f
                            controls?.translationX = dx
                            controls?.translationY = 0f

                            val width = (container.width.takeIf { it > 0 } ?: 1000).toFloat()
                            val progressFraction = (kotlin.math.abs(dx) / width).coerceIn(0f, 1f)
                            val alpha = 1.0f - (progressFraction * 0.7f)
                            container.alpha = alpha
                            controls?.alpha = alpha
                        } else if (dy > 0) {
                            // 1:1 Downward drag tracking
                            container.translationY = dy
                            container.translationX = 0f
                            controls?.translationY = dy
                            controls?.translationX = 0f

                            val height = (container.height.takeIf { it > 0 } ?: 200).toFloat()
                            val progressFraction = (dy / height).coerceIn(0f, 1f)
                            val alpha = 1.0f - (progressFraction * 0.7f)
                            container.alpha = alpha
                            controls?.alpha = alpha
                        }
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingMiniPlayer && container != null) {
                        isDraggingMiniPlayer = false
                        val width = (container.width.takeIf { it > 0 } ?: 1000).toFloat()
                        val height = (container.height.takeIf { it > 0 } ?: 200).toFloat()

                        if (kotlin.math.abs(container.translationX) > 0f) {
                            if (kotlin.math.abs(container.translationX) > width * 0.35f) {
                                val direction = if (container.translationX > 0f) 1 else -1
                                animateDismiss(direction)
                            } else {
                                container.animate()
                                    .translationX(0f)
                                    .alpha(1f)
                                    .setDuration(220)
                                    .setInterpolator(DecelerateInterpolator())
                                    .start()
                                controls?.animate()
                                    ?.translationX(0f)
                                    ?.alpha(1f)
                                    ?.setDuration(220)
                                    ?.setInterpolator(DecelerateInterpolator())
                                    ?.start()
                            }
                            return true
                        } else if (container.translationY > 0f) {
                            if (container.translationY > height * 0.35f) {
                                animateDismissDown()
                            } else {
                                container.animate()
                                    .translationY(0f)
                                    .alpha(1f)
                                    .setDuration(220)
                                    .setInterpolator(DecelerateInterpolator())
                                    .start()
                                controls?.animate()
                                    ?.translationY(0f)
                                    ?.alpha(1f)
                                    ?.setDuration(220)
                                    ?.setInterpolator(DecelerateInterpolator())
                                    ?.start()
                            }
                            return true
                        }
                    }
                }
            }
        }

        return super.onTouchEvent(event)
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
