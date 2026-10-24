package com.xrdroid

import kotlin.math.PI
import kotlin.math.abs

// Smooths a noisy signal with little lag: heavily while it's nearly still (hiding hand tremor), lightly
// while it moves fast (keeping up with deliberate movement). Casiez, Roussel and Vogel's "1€ filter".
class OneEuroFilter(private val minCutoffHz: Float, private val beta: Float, private val derivativeCutoffHz: Float = 1f) {

    private var value = Float.NaN
    private var derivative = 0f

    fun reset() {
        value = Float.NaN
        derivative = 0f
    }

    fun filter(raw: Float, dtSeconds: Float): Float {
        if (value.isNaN() || dtSeconds <= 0f) {
            value = raw
            return raw
        }
        derivative += smoothing(derivativeCutoffHz, dtSeconds) * ((raw - value) / dtSeconds - derivative)
        val cutoff = minCutoffHz + beta * abs(derivative)
        value += smoothing(cutoff, dtSeconds) * (raw - value)
        return value
    }

    private fun smoothing(cutoffHz: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dtSeconds)
    }
}
