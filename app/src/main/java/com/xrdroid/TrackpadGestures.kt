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
// the spatial screen being looked at. The screen is fixed for the length of each gesture. While the phone
// pointer aims the cursor, one finger instead works as a touch at the cursor: tap to click, slide to drag,
// hold to long-press. Sliding drags what's under the cursor but leaves the cursor where it was aimed.
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
    // Aims the cursor while it's running
    var pointer: PhonePointer? = null
    // Whether the current gesture started while the pointer was aiming
    private var pointing = false
    // While pointing, where the touch has been dragged to; the cursor itself stays where it was aimed
    private var touchX = 0f
    private var touchY = 0f
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
    // While pointing, a finger held still has been a long press for the app by now
    private val heldRunnable = Runnable {
        if (!moved) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pointing = pointer?.running == true
        if (pointing) return onPointingTouch(event)
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

    // One finger is a touch that starts at the cursor and follows the finger, while the cursor stays put; the
    // phone's aim is frozen meanwhile. A second finger turns it into a two-finger scroll.
    private fun onPointingTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer?.freeze()
                screen = Session.activeScreen
                Session.touchActive = true
                startX = x
                startY = y
                lastX = x
                lastY = y
                moved = false
                isMultiTouch = false
                isScrolling = false
                twoFingerTravel = 0f
                screen?.let {
                    touchX = it.cursorX
                    touchY = it.cursorY
                    finger.down(it.displayId, touchX, touchY)
                }
                handler.postDelayed(heldRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(heldRunnable)
                if (!isMultiTouch) finger.cancel()
                isMultiTouch = true
                moved = true
                updateCentroid(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMultiTouch && event.pointerCount >= 2) {
                    val previousX = centroidX
                    val previousY = centroidY
                    updateCentroid(event)
                    scroll(centroidX - previousX, centroidY - previousY)
                } else if (!isMultiTouch) {
                    if (hypot(x - startX, y - startY) > MOVE_SLOP_PX) {
                        moved = true
                        handler.removeCallbacks(heldRunnable)
                    }
                    val target = screen
                    if (target != null) {
                        touchX = (touchX + (x - lastX) * CURSOR_GAIN).coerceIn(0f, target.width - 1f)
                        touchY = (touchY + (y - lastY) * CURSOR_GAIN).coerceIn(0f, target.height - 1f)
                        finger.move(touchX, touchY)
                    }
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2 && isScrolling) {
                    finger.up()
                    isScrolling = false
                } else if (event.pointerCount > 2) {
                    updateCentroid(event, event.actionIndex)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isMultiTouch || isScrolling) finger.up()
                finishGesture()
                pointer?.release()
            }
            MotionEvent.ACTION_CANCEL -> {
                finger.cancel()
                finishGesture()
                pointer?.release()
            }
        }
        return true
    }

    private fun finishGesture() {
        handler.removeCallbacks(heldRunnable)
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
