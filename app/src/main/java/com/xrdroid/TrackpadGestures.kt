package com.xrdroid

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

// Turns touches on the phone into cursor movement, clicks, two-finger scrolling and long-press drags on
// the spatial screen being looked at. The screen is fixed for the length of each gesture.
class TrackpadGestures(private val view: View) : View.OnTouchListener {

    companion object {
        private const val CURSOR_GAIN = 1.5f
        private const val SCROLL_GAIN = 2.5f
        private const val TAP_MAX_MS = 250L
        private const val LONG_PRESS_MS = 400L
        private const val MOVE_SLOP_PX = 10f
        private const val SCROLL_SLOP_DP = 6
        // Extra hold beyond the system long-press timeout before the app is sure to have registered it
        private const val LONG_PRESS_MARGIN_MS = 100L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val finger = TouchInjector()
    private val scrollSlopPx = SCROLL_SLOP_DP * view.resources.displayMetrics.density

    private var screen: Session.Screen? = null
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var isMultiTouch = false
    private var isDragging = false
    private var isScrolling = false
    private var twoFingerTravel = 0f
    private var centroidX = 0f
    private var centroidY = 0f
    private var scrollFingerX = 0f
    private var scrollFingerY = 0f

    // Press and hold at the cursor until the app registers a long press (haptic = picked up), then the
    // finger follows the cursor, dragging whatever was picked up
    private val longPressRunnable = Runnable {
        moved = true
        val target = screen ?: return@Runnable
        val holdMs = ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS
        finger.down(target.displayId, target.cursorX, target.cursorY, holdMs)
        isDragging = true
        handler.postDelayed(pickedUpRunnable, holdMs)
    }
    private val pickedUpRunnable = Runnable {
        if (isDragging) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                screen = Session.activeScreen
                Session.touchActive = true
                startX = x
                startY = y
                lastX = x
                lastY = y
                downTime = SystemClock.uptimeMillis()
                moved = false
                isMultiTouch = false
                isDragging = false
                isScrolling = false
                twoFingerTravel = 0f
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isMultiTouch = true
                moved = true
                handler.removeCallbacks(longPressRunnable)
                updateCentroid(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(x - startX, y - startY) > MOVE_SLOP_PX) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (isMultiTouch && event.pointerCount >= 2 && !isDragging) {
                    val previousX = centroidX
                    val previousY = centroidY
                    updateCentroid(event)
                    scroll(centroidX - previousX, centroidY - previousY)
                } else {
                    moveCursor(x - lastX, y - lastY)
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    // Back to one finger: release the scroll, letting its lift-off speed carry into a fling
                    if (isScrolling) {
                        finger.up()
                        isScrolling = false
                    }
                } else {
                    updateCentroid(event, event.actionIndex)
                }
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount > remainingIndex) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val target = screen
                if (!moved && SystemClock.uptimeMillis() - downTime < TAP_MAX_MS && target != null) {
                    finger.tap(target.displayId, target.cursorX, target.cursorY)
                } else if (isDragging || isScrolling) {
                    finger.up()
                }
                finishGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (isDragging || isScrolling) finger.cancel()
                finishGesture()
            }
        }
        return true
    }

    private fun finishGesture() {
        handler.removeCallbacks(pickedUpRunnable)
        isDragging = false
        isScrolling = false
        isMultiTouch = false
        screen = null
        Session.touchActive = false
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val target = screen ?: return
        target.cursorX = (target.cursorX + dx * CURSOR_GAIN).coerceIn(0f, target.width - 1f)
        target.cursorY = (target.cursorY + dy * CURSOR_GAIN).coerceIn(0f, target.height - 1f)
        if (isDragging) finger.move(target.cursorX, target.cursorY)
    }

    private fun scroll(dx: Float, dy: Float) {
        val target = screen ?: return
        // Ignore small wobble at the start, so resting two fingers doesn't nudge the content
        if (twoFingerTravel < scrollSlopPx) {
            twoFingerTravel += hypot(dx, dy)
            return
        }
        if (!isScrolling) {
            startScroll(target)
            return
        }
        val nextX = scrollFingerX + dx * SCROLL_GAIN
        val nextY = scrollFingerY + dy * SCROLL_GAIN
        if (nextX !in 0f..target.width - 1f || nextY !in 0f..target.height - 1f) {
            // Virtual finger reached the screen edge: put it down again near the cursor, like re-gripping a trackpad
            startScroll(target)
        } else {
            scrollFingerX = nextX
            scrollFingerY = nextY
            finger.move(scrollFingerX, scrollFingerY)
        }
    }

    private fun startScroll(target: Session.Screen) {
        // Press at the cursor but away from the edges, so there is room to scroll in every direction
        scrollFingerX = target.cursorX.coerceIn(target.width * 0.1f, target.width * 0.9f)
        scrollFingerY = target.cursorY.coerceIn(target.height * 0.1f, target.height * 0.9f)
        finger.down(target.displayId, scrollFingerX, scrollFingerY)
        isScrolling = true
    }

    // Midpoint of the fingers, optionally ignoring the pointer that is lifting
    private fun updateCentroid(event: MotionEvent, skipIndex: Int = -1) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count > 0) {
            centroidX = sumX / count
            centroidY = sumY / count
        }
    }
}
