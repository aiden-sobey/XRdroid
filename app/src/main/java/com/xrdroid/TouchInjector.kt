package com.xrdroid

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

// One virtual finger on a spatial screen, delivered through the shell helper with real timestamps, so
// apps track scrolls and flings as they would a real finger
class TouchInjector {

    companion object {
        // Long enough for apps that sample touches once per frame (games) to see a tap
        private const val TAP_MS = 50L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var displayId = -1
    private var downTime = 0L
    private var x = 0f
    private var y = 0f
    // Movement is held back until then, so the app registers a long press first
    private var holdUntil = 0L

    var isDown = false
        private set

    fun down(displayId: Int, x: Float, y: Float, holdMs: Long = 0) {
        if (isDown) cancel()
        this.displayId = displayId
        this.x = x
        this.y = y
        downTime = SystemClock.uptimeMillis()
        holdUntil = downTime + holdMs
        isDown = true
        send(MotionEvent.ACTION_DOWN)
    }

    fun move(x: Float, y: Float) {
        if (!isDown) return
        this.x = x
        this.y = y
        val wait = holdUntil - SystemClock.uptimeMillis()
        if (wait <= 0) {
            send(MotionEvent.ACTION_MOVE)
        } else {
            // Catch up to the latest position once the hold is over
            handler.removeCallbacks(catchUp)
            handler.postDelayed(catchUp, wait)
        }
    }

    // Lifts the finger, after the rest of any long-press hold
    fun up() {
        if (!isDown) return
        handler.removeCallbacks(catchUp)
        val wait = holdUntil - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(lift, wait) else lift.run()
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        if (isDown) send(MotionEvent.ACTION_CANCEL)
        isDown = false
    }

    fun tap(displayId: Int, x: Float, y: Float) {
        down(displayId, x, y)
        handler.postDelayed(lift, TAP_MS)
    }

    private val catchUp = Runnable { if (isDown) send(MotionEvent.ACTION_MOVE) }

    private val lift = Runnable {
        if (isDown) {
            send(MotionEvent.ACTION_MOVE)
            send(MotionEvent.ACTION_UP)
        }
        isDown = false
    }

    private fun send(action: Int) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = this@TouchInjector.x
            y = this@TouchInjector.y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        runCatching { Helper.service?.injectMotion(event, displayId) }
        event.recycle()
    }
}
