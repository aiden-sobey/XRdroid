package com.xrdroid

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Quaternions as [w, x, y, z]. A head orientation maps the glasses' frame (X right, Y up,
// -Z forward) into a gravity-aligned world frame, matching github.com/elasticjava/viture-v2.
internal object Quat {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f)

    fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
    )

    fun conj(q: FloatArray) = floatArrayOf(q[0], -q[1], -q[2], -q[3])

    // Where an orientation is looking, in radians: heading (positive turns left) and pitch (positive looks up)
    fun lookAngles(q: FloatArray): Pair<Float, Float> {
        val m = FloatArray(16)
        toMatrix(q, m)
        // Forward is -Z, so the gaze direction is minus the rotation's third column
        return atan2(m[8], m[10]) to asin((-m[9]).coerceIn(-1f, 1f))
    }

    // A turn of the given angle (radians) about the vertical axis
    fun aboutY(angle: Float) = floatArrayOf(cos(angle / 2f), 0f, sin(angle / 2f), 0f)

    // Body-frame angular rate in rad/s between two orientations dt seconds apart
    fun angularRate(a: FloatArray, b: FloatArray, dt: Float): FloatArray {
        val d = mul(conj(a), b)
        // q and -q are the same orientation; take the shorter arc
        val sign = if (d[0] < 0f) -1f else 1f
        val k = 2f * sign / dt
        return floatArrayOf(d[1] * k, d[2] * k, d[3] * k)
    }

    // Extrapolates q forward by dt seconds at body-frame angular rate omega
    fun integrate(q: FloatArray, omega: FloatArray, dt: Float): FloatArray {
        val rate = sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2])
        if (rate * dt < 1e-6f) return q
        val half = rate * dt / 2f
        val s = sin(half) / rate
        val out = mul(q, floatArrayOf(cos(half), omega[0] * s, omega[1] * s, omega[2] * s))
        val n = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        return floatArrayOf(out[0] / n, out[1] / n, out[2] / n, out[3] / n)
    }

    // Column-major rotation matrix, as android.opengl.Matrix expects
    fun toMatrix(q: FloatArray, out: FloatArray) {
        val (w, x, y, z) = q
        out[0] = 1f - 2f * (y * y + z * z); out[1] = 2f * (x * y + w * z); out[2] = 2f * (x * z - w * y); out[3] = 0f
        out[4] = 2f * (x * y - w * z); out[5] = 1f - 2f * (x * x + z * z); out[6] = 2f * (y * z + w * x); out[7] = 0f
        out[8] = 2f * (x * z + w * y); out[9] = 2f * (y * z - w * x); out[10] = 1f - 2f * (x * x + y * y); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    }
}
