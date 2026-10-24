package com.xrdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The space in front of the wearer, unrolled flat: left to right is turning the head, up and down is
// looking up and down, and the dot in the middle is straight ahead after recentring. Screens are dragged
// around it; they snap to the centre lines and to each other, and keep a gap between them. Each screen is
// drawn as the box around its outline as the glasses will show it, and spaced by that box.
class LayoutEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        // How far screens may sit from straight ahead
        const val FIELD_YAW = 90f
        const val FIELD_PITCH = 55f
        // Kept between neighbouring screens, so their borders never touch
        const val GAP_DEGREES = 2f
        private const val SNAP_DEGREES = 3f
        private const val GRID_DEGREES = 15f
        // Roughly what the glasses show at once, here around the recentre point
        private const val VIEW_WIDTH_DEGREES = 43f
        private const val VIEW_HEIGHT_DEGREES = 25f
    }

    var screens: MutableList<CustomScreen> = mutableListOf()
        set(value) {
            field = value
            invalidate()
        }
    var selected = 0
        set(value) {
            field = value
            invalidate()
        }
    // Called when a screen is picked or moved
    var onChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val muted = ContextCompat.getColor(context, R.color.text_muted)
    private val text = ContextCompat.getColor(context, R.color.text)
    private val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface) }
    private val gridPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.outline)
        strokeWidth = density
    }
    private val viewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = muted
        pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
    }
    private val screenFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * density
        color = muted
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val rect = RectF()

    // The box around a screen's outline at some pitch; yaws are relative to the screen's own yaw
    private class Footprint(val left: Float, val right: Float, val bottom: Float, val top: Float)
    private val footprints = HashMap<CustomScreen, Footprint>()

    private fun footprint(screen: CustomScreen): Footprint = footprints.getOrPut(screen.copy(yawDegrees = 0f)) {
        val outline = ScreenGeometry.outline(screen.copy(yawDegrees = 0f).spec(""))
        var left = -Float.MAX_VALUE
        var right = Float.MAX_VALUE
        var bottom = Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        for (i in outline.indices step 2) {
            left = max(left, outline[i])
            right = min(right, outline[i])
            bottom = min(bottom, outline[i + 1])
            top = max(top, outline[i + 1])
        }
        Footprint(left, right, bottom, top)
    }

    private var dragging = -1
    private var startX = 0f
    private var startY = 0f
    private var startYaw = 0f
    private var startPitch = 0f

    private val pxPerDegree get() = width / (2 * FIELD_YAW)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * FIELD_PITCH / FIELD_YAW).roundToInt())
    }

    // Positive yaw is to the wearer's left, so it runs right to left
    private fun x(yaw: Float) = width / 2f - yaw * pxPerDegree
    private fun y(pitch: Float) = height / 2f - pitch * pxPerDegree

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val corner = 16 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner, corner, fieldPaint)

        var degrees = GRID_DEGREES
        while (degrees < FIELD_YAW) {
            for (sign in listOf(-1, 1)) {
                canvas.drawLine(x(sign * degrees), 0f, x(sign * degrees), height.toFloat(), gridPaint)
                if (degrees < FIELD_PITCH) canvas.drawLine(0f, y(sign * degrees), width.toFloat(), y(sign * degrees), gridPaint)
            }
            degrees += GRID_DEGREES
        }
        canvas.drawText("Left", 10 * density, 18 * density, captionPaint)
        captionPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Right", width - 10 * density, 18 * density, captionPaint)
        captionPaint.textAlign = Paint.Align.LEFT

        // What the glasses show when looking straight ahead
        rect.set(x(VIEW_WIDTH_DEGREES / 2), y(VIEW_HEIGHT_DEGREES / 2), x(-VIEW_WIDTH_DEGREES / 2), y(-VIEW_HEIGHT_DEGREES / 2))
        canvas.drawRoundRect(rect, 6 * density, 6 * density, viewPaint)

        labelPaint.textSize = 14 * density
        screens.forEachIndexed { i, screen ->
            val footprint = footprint(screen)
            rect.set(
                x(screen.yawDegrees + footprint.left), y(footprint.top),
                x(screen.yawDegrees + footprint.right), y(footprint.bottom)
            )
            val isSelected = i == selected
            screenFill.color = Color.argb(if (isSelected) 110 else 70, Color.red(accent), Color.green(accent), Color.blue(accent))
            screenStroke.color = if (isSelected) accent else muted
            screenStroke.strokeWidth = (if (isSelected) 2.5f else 1.2f) * density
            canvas.drawRoundRect(rect, 5 * density, 5 * density, screenFill)
            canvas.drawRoundRect(rect, 5 * density, 5 * density, screenStroke)
            labelPaint.color = text
            canvas.drawText("${i + 1}", rect.centerX(), rect.centerY() + 5 * density, labelPaint)
        }

        // The recentre point, drawn over the screens so it stays visible
        canvas.drawCircle(x(0f), y(0f), 4.5f * density, dotPaint)
        dotPaint.style = Paint.Style.STROKE
        dotPaint.strokeWidth = 1.5f * density
        canvas.drawCircle(x(0f), y(0f), 9 * density, dotPaint)
        dotPaint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = hitTest(event.x, event.y)
                if (dragging < 0) return false
                selected = dragging
                startX = event.x
                startY = event.y
                startYaw = screens[dragging].yawDegrees
                startPitch = screens[dragging].pitchDegrees
                // Keep the page from scrolling while a screen is dragged
                parent.requestDisallowInterceptTouchEvent(true)
                onChanged?.invoke()
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                moveTo(dragging, startYaw - (event.x - startX) / pxPerDegree, startPitch - (event.y - startY) / pxPerDegree)
                invalidate()
                onChanged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return true
    }

    // The selected screen first, since it's drawn last and so on top; a little slack around small screens
    private fun hitTest(px: Float, py: Float): Int {
        val slack = 6 * density / pxPerDegree
        val order = listOf(selected) + screens.indices.filter { it != selected }.reversed()
        val yaw = (width / 2f - px) / pxPerDegree
        val pitch = (height / 2f - py) / pxPerDegree
        return order.firstOrNull { i ->
            if (i !in screens.indices) return@firstOrNull false
            val footprint = footprint(screens[i])
            yaw - screens[i].yawDegrees in footprint.right - slack..footprint.left + slack &&
                pitch in footprint.bottom - slack..footprint.top + slack
        } ?: -1
    }

    // Moves a screen as close to where it was dragged as it can go without overlapping another
    private fun moveTo(index: Int, rawYaw: Float, rawPitch: Float) {
        val screen = screens[index]
        val footprint = footprint(screen)
        // How far the outline reaches above and below the screen's pitch
        val above = footprint.top - screen.pitchDegrees
        val below = screen.pitchDegrees - footprint.bottom
        val yawTargets = mutableListOf(0f)
        val pitchTargets = mutableListOf(0f)
        screens.forEachIndexed { i, other ->
            if (i == index) return@forEachIndexed
            val near = footprint(other)
            // Line up with a neighbour, or sit right beside, above or below it
            yawTargets += other.yawDegrees
            yawTargets += other.yawDegrees + near.left + GAP_DEGREES - footprint.right
            yawTargets += other.yawDegrees + near.right - GAP_DEGREES - footprint.left
            pitchTargets += other.pitchDegrees
            pitchTargets += near.top + GAP_DEGREES + below
            pitchTargets += near.bottom - GAP_DEGREES - above
        }
        val snappedYaw = snap(rawYaw, yawTargets).coerceIn(-FIELD_YAW - footprint.right, FIELD_YAW - footprint.left)
        val snappedPitch = snap(rawPitch, pitchTargets).coerceIn(-FIELD_PITCH + below, FIELD_PITCH - above)
        val roundYaw = rawYaw.roundToInt().toFloat()
        val roundPitch = rawPitch.roundToInt().toFloat()
        // A screen's outline changes a little with its pitch, so a snapped spot may not quite fit; then try
        // unsnapped, then sliding along whichever direction is still free
        val choice = listOf(
            snappedYaw to snappedPitch, roundYaw to roundPitch,
            snappedYaw to screen.pitchDegrees, roundYaw to screen.pitchDegrees,
            screen.yawDegrees to snappedPitch, screen.yawDegrees to roundPitch
        ).firstOrNull { (y, p) -> fits(index, screen, y, p) } ?: return
        screens[index] = screen.copy(yawDegrees = choice.first, pitchDegrees = choice.second)
    }

    private fun snap(value: Float, targets: List<Float>): Float {
        val nearest = targets.minByOrNull { abs(it - value) }
        return if (nearest != null && abs(nearest - value) <= SNAP_DEGREES) nearest else value.roundToInt().toFloat()
    }

    // Whether the screen fits at a position, inside the field and at least GAP_DEGREES from every other
    fun fits(index: Int, screen: CustomScreen, yaw: Float, pitch: Float): Boolean {
        val footprint = footprint(screen.copy(pitchDegrees = pitch))
        if (yaw + footprint.left > FIELD_YAW + 0.01f || yaw + footprint.right < -FIELD_YAW - 0.01f ||
            footprint.top > FIELD_PITCH + 0.01f || footprint.bottom < -FIELD_PITCH - 0.01f
        ) return false
        return screens.indices.none { i ->
            if (i == index) return@none false
            val other = screens[i]
            val near = footprint(other)
            yaw + footprint.right < other.yawDegrees + near.left + GAP_DEGREES - 0.01f &&
                other.yawDegrees + near.right < yaw + footprint.left + GAP_DEGREES - 0.01f &&
                footprint.bottom < near.top + GAP_DEGREES - 0.01f &&
                near.bottom < footprint.top + GAP_DEGREES - 0.01f
        }
    }

    // The free whole-degree position nearest to where a screen would like to be, or null if it can't fit
    fun nearestFree(index: Int, screen: CustomScreen, yaw: Float, pitch: Float): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        var bestDistance = Float.MAX_VALUE
        for (y in -FIELD_YAW.toInt()..FIELD_YAW.toInt()) {
            for (p in -FIELD_PITCH.toInt()..FIELD_PITCH.toInt()) {
                val distance = hypot(y - yaw, p - pitch)
                if (distance < bestDistance && fits(index, screen, y.toFloat(), p.toFloat())) {
                    best = y.toFloat() to p.toFloat()
                    bestDistance = distance
                }
            }
        }
        return best
    }
}
