package com.xrdroid

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

// Where a screen's surface lies around the wearer, shared by the renderer and the layout editor so the
// editor shows what the glasses will. Points are in the screen's own frame, before it's turned to its yaw:
// the wearer at the origin, -Z ahead, +X to the right and +Y up.
object ScreenGeometry {
    // Radius of the sphere the screens sit on, in world units
    const val DISTANCE = 2f

    // Width along the surface: an arc for curved screens, the plane width for flat ones
    fun width(curved: Boolean, widthDegrees: Float): Float {
        val halfAngle = Math.toRadians(widthDegrees / 2.0)
        return (if (curved) DISTANCE * 2 * halfAngle else DISTANCE * 2 * tan(halfAngle)).toFloat()
    }

    // A point on the screen, u from its left edge (0) to its right (1) and v from its bottom (0) to its top
    // (1), grown by margin on every side, and tilted up by tiltDegrees about the wearer's sideways axis so
    // the whole screen faces a wearer looking up at it, as it does at eye level. A tilted curved screen's
    // ends sit a little nearer the horizon than its middle.
    fun point(curved: Boolean, widthDegrees: Float, width: Float, height: Float, margin: Float, tiltDegrees: Float, u: Float, v: Float, out: FloatArray) {
        val y = (v - 0.5f) * (height + 2 * margin)
        val x: Double
        val z: Double
        if (curved) {
            val span = widthDegrees + 2 * Math.toDegrees((margin / DISTANCE).toDouble()).toFloat()
            // Positive angles are to the left
            val angle = Math.toRadians((span / 2f - u * span).toDouble())
            x = -DISTANCE * sin(angle)
            z = -DISTANCE * cos(angle)
        } else {
            x = ((u - 0.5f) * (width + 2 * margin)).toDouble()
            z = -DISTANCE.toDouble()
        }
        val t = Math.toRadians(tiltDegrees.toDouble())
        out[0] = x.toFloat()
        out[1] = (y * cos(t) - z * sin(t)).toFloat()
        out[2] = (y * sin(t) + z * cos(t)).toFloat()
    }

    // A screen's outline as the wearer sees it, going round its edges: pairs of yaw (positive to the left,
    // relative to the screen's own yaw) and pitch in degrees. Screens pitched up or down come out slightly
    // narrower at the end nearer the zenith or nadir, and curved ones dip at the ends.
    fun outline(spec: ScreenSpec, stepsPerEdge: Int = 8): FloatArray {
        val width = width(spec.curved, spec.widthDegrees)
        val height = width * spec.heightPx / spec.widthPx
        val corners = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        val out = FloatArray(4 * stepsPerEdge * 2)
        val point = FloatArray(3)
        var i = 0
        for (edge in 0 until 4) {
            val (u0, v0) = corners[edge]
            val (u1, v1) = corners[(edge + 1) % 4]
            for (step in 0 until stepsPerEdge) {
                val f = step.toFloat() / stepsPerEdge
                point(spec.curved, spec.widthDegrees, width, height, 0f, spec.pitchDegrees, u0 + (u1 - u0) * f, v0 + (v1 - v0) * f, point)
                out[i++] = Math.toDegrees(atan2(-point[0].toDouble(), -point[2].toDouble())).toFloat()
                out[i++] = Math.toDegrees(atan2(point[1].toDouble(), hypot(point[0].toDouble(), point[2].toDouble()))).toFloat()
            }
        }
        return out
    }
}
