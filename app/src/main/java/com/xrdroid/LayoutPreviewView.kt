package com.xrdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Top-down sketch of a screen layout: the wearer at the bottom, their field of view as a faint wedge,
// and each screen as an arc around them
class LayoutPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        // Roughly the glasses' horizontal field of view
        private const val FIELD_OF_VIEW_DEGREES = 46f
        // Trimmed off each end of a screen in multi-screen sketches so they read as separate screens
        private const val GAP_DEGREES = 5f
    }

    var layout: ScreenLayout = ScreenLayout.DUAL
        set(value) {
            field = value
            invalidate()
        }
    var highlighted = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val muted = ContextCompat.getColor(context, R.color.text_muted)
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wearerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBounds = RectF()
    private val fieldPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height - 6 * density
        val radius = min(width / 2f - 6 * density, height - 14 * density)
        arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val color = if (highlighted) accent else muted

        // Straight ahead is up (-90 degrees on the canvas); positive yaw turns left (anticlockwise)
        fieldPaint.color = Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))
        fieldPath.reset()
        fieldPath.moveTo(centerX, centerY)
        fieldPath.arcTo(arcBounds, -90f - FIELD_OF_VIEW_DEGREES / 2f, FIELD_OF_VIEW_DEGREES)
        fieldPath.close()
        canvas.drawPath(fieldPath, fieldPaint)

        // Flat screens are drawn as straight lines and curved ones as arcs, with a clear gap between neighbours
        screenPaint.color = color
        val inset = if (layout.screens.size > 1) GAP_DEGREES else 0f
        for (screen in layout.screens) {
            val left = screen.yawDegrees + screen.widthDegrees / 2f - inset
            val right = screen.yawDegrees - screen.widthDegrees / 2f + inset
            if (screen.curved) {
                canvas.drawArc(arcBounds, -90f - left, left - right, false, screenPaint)
            } else {
                canvas.drawLine(pointX(centerX, radius, left), pointY(centerY, radius, left),
                    pointX(centerX, radius, right), pointY(centerY, radius, right), screenPaint)
            }
        }

        wearerPaint.color = color
        canvas.drawCircle(centerX, centerY, 3.5f * density, wearerPaint)
    }

    // A point on the circle at the given yaw (positive is to the left of straight up)
    private fun pointX(centerX: Float, radius: Float, yawDegrees: Float) =
        centerX + radius * cos(Math.toRadians((-90f - yawDegrees).toDouble())).toFloat()

    private fun pointY(centerY: Float, radius: Float, yawDegrees: Float) =
        centerY + radius * sin(Math.toRadians((-90f - yawDegrees).toDouble())).toFloat()
}
