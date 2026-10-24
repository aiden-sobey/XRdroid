package com.xrdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

// The trackpad surface: a faint dot grid with a soft glow under each finger. Touches are handed to
// TrackpadGestures, which drives the spatial screen being looked at.
class TrackpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Called on the first touch, e.g. to fade out the hint
    var onFirstTouch: (() -> Unit)? = null

    val gestures = TrackpadGestures(this)
    private val density = resources.displayMetrics.density
    private val gridSpacing = 22 * density
    private val glowRadius = 44 * density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(18, 255, 255, 255) }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fingers = mutableMapOf<Int, PointF>()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onFirstTouch?.invoke()
            onFirstTouch = null
        }
        gestures.onTouch(this, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingers.clear()
            MotionEvent.ACTION_POINTER_UP -> fingers.remove(event.getPointerId(event.actionIndex))
            else -> for (i in 0 until event.pointerCount) {
                fingers.getOrPut(event.getPointerId(i)) { PointF() }.set(event.getX(i), event.getY(i))
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = gridSpacing
        var y = inset
        while (y < height - inset / 2) {
            var x = inset
            while (x < width - inset / 2) {
                canvas.drawCircle(x, y, 1.2f * density, gridPaint)
                x += gridSpacing
            }
            y += gridSpacing
        }
        for (finger in fingers.values) {
            glowPaint.shader = RadialGradient(
                finger.x, finger.y, glowRadius,
                intArrayOf(Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(finger.x, finger.y, glowRadius, glowPaint)
        }
    }
}
