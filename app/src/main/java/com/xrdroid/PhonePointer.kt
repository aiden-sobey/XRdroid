package com.xrdroid

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

// Aims the cursor with the phone, like a laser pointer: turning the phone moves the cursor across the
// screen being looked at, a little further than the phone turns. The screen is still picked by gaze; when it changes (or the
// pointer starts, or the screens are recentred) the cursor starts from where the wearer is looking.
// Only the phone's turning is used, so its heading never needs lining up with the glasses', and the
// cursor stops at the screen's edges. A finger on the trackpad freezes the aim.
class PhonePointer(context: Context) : SensorEventListener {

    companion object {
        private const val PREFS = "settings"
        private const val KEY_POINTER = "pointer"
        private const val SAMPLE_PERIOD_US = 5000
        // A degree of turning moves the cursor a little further, so small wrist movements cover the screen
        private const val GAIN = 1.275f
        // Hand tremor is smoothed away while nearly still; fast movement is barely delayed
        private const val FILTER_MIN_CUTOFF_HZ = 1f
        private const val FILTER_BETA = 0.03f
        // Held this still for a while, the phone is resting: the cursor ignores the sensor's slow drift
        // until it's deliberately moved again
        private const val STILL_DEGREES_PER_SECOND = 1.5f
        private const val MOVING_DEGREES_PER_SECOND = 4f
        private const val STILL_AFTER_NANOS = 500_000_000L
        // Pressing the trackpad jolts the phone; the cursor goes back to where it was just before
        private const val REWIND_NANOS = 50_000_000L
        private const val HISTORY_NANOS = 200_000_000L

        fun saved(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_POINTER, false)

        fun save(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_POINTER, on).apply()
        }
    }

    private class Position(val nanos: Long, val x: Float, val y: Float)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val lock = Any()
    private val rotation = FloatArray(9)
    private val yawFilter = OneEuroFilter(FILTER_MIN_CUTOFF_HZ, FILTER_BETA)
    private val pitchFilter = OneEuroFilter(FILTER_MIN_CUTOFF_HZ, FILTER_BETA)
    private val history = ArrayDeque<Position>()
    private var thread: HandlerThread? = null

    // The screen the cursor was last moved on; a different one starts again from the gaze
    private var screen: Session.Screen? = null
    // The phone's yaw counted continuously past ±180°, so smoothing never sees it jump
    private var lastRawYaw = Float.NaN
    private var unwrappedYaw = 0f
    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private var lastNanos = 0L
    private var stillSince = 0L
    private var resting = false
    private var frozen = false

    val available get() = sensor != null
    @Volatile var running = false
        private set

    fun start() {
        if (running || sensor == null) return
        val handlerThread = HandlerThread("PhonePointer").apply { start() }
        thread = handlerThread
        synchronized(lock) { restart() }
        running = true
        sensorManager.registerListener(this, sensor, SAMPLE_PERIOD_US, 0, Handler(handlerThread.looper))
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    // Puts the cursor back where the wearer is looking, e.g. after recentring
    fun reset() = synchronized(lock) { restart() }

    // A finger went down on the trackpad: undo the jolt of pressing and hold the cursor still
    fun freeze() = synchronized(lock) {
        frozen = true
        val target = screen ?: return@synchronized
        if (target !== Session.activeScreen) return@synchronized
        val before = SystemClock.elapsedRealtimeNanos() - REWIND_NANOS
        history.lastOrNull { it.nanos <= before }?.let {
            target.cursorX = it.x
            target.cursorY = it.y
        }
    }

    // The finger lifted: aim again from wherever the phone now points, so the cursor doesn't jump
    fun release() = synchronized(lock) {
        frozen = false
        lastYaw = Float.NaN
        history.clear()
    }

    private fun restart() {
        screen = null
        lastYaw = Float.NaN
        lastRawYaw = Float.NaN
        lastNanos = 0L
        stillSince = 0L
        resting = false
        history.clear()
        yawFilter.reset()
        pitchFilter.reset()
    }

    override fun onSensorChanged(event: SensorEvent) = synchronized(lock) {
        // Where the top of the phone points: yaw (positive to the left) and pitch, in degrees
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val dt = if (lastNanos == 0L) 0f else (event.timestamp - lastNanos) / 1e9f
        lastNanos = event.timestamp
        val rawYaw = Math.toDegrees(atan2(-rotation[1].toDouble(), rotation[4].toDouble())).toFloat()
        val rawPitch = Math.toDegrees(asin(rotation[7].coerceIn(-1f, 1f).toDouble())).toFloat()
        unwrappedYaw = if (lastRawYaw.isNaN()) rawYaw else unwrappedYaw + wrap(rawYaw - lastRawYaw)
        lastRawYaw = rawYaw
        move(yawFilter.filter(unwrappedYaw, dt), pitchFilter.filter(rawPitch, dt), dt, event.timestamp)
    }

    private fun move(yaw: Float, pitch: Float, dt: Float, nanos: Long) {
        val active = Session.activeScreen ?: return
        if (active !== screen) {
            // Newly looked at, or just started: begin where the wearer is looking
            screen = active
            if (!Session.touchActive) {
                active.cursorX = active.gazeX
                active.cursorY = active.gazeY
            }
            lastYaw = yaw
            lastPitch = pitch
            history.clear()
            return
        }
        if (frozen || lastYaw.isNaN()) {
            lastYaw = yaw
            lastPitch = pitch
            return
        }
        val dYaw = yaw - lastYaw
        val dPitch = pitch - lastPitch
        lastYaw = yaw
        lastPitch = pitch
        // Resting phones drift slowly; ignore that until the phone clearly moves again
        val speed = if (dt > 0f) hypot(dYaw, dPitch) / dt else 0f
        if (resting) {
            if (speed < MOVING_DEGREES_PER_SECOND) return
            resting = false
        }
        if (speed < STILL_DEGREES_PER_SECOND) {
            if (stillSince == 0L) stillSince = nanos
            if (nanos - stillSince > STILL_AFTER_NANOS) {
                resting = true
                return
            }
        } else {
            stillSince = 0L
        }
        // Turning left moves the cursor left, tipping up moves it up; it stops at the edges
        active.cursorX = (active.cursorX - dYaw * active.pxPerDegreeX * GAIN).coerceIn(0f, active.width - 1f)
        active.cursorY = (active.cursorY - dPitch * active.pxPerDegreeY * GAIN).coerceIn(0f, active.height - 1f)
        val now = SystemClock.elapsedRealtimeNanos()
        history.addLast(Position(now, active.cursorX, active.cursorY))
        while (history.isNotEmpty() && now - history.first().nanos > HISTORY_NANOS) history.removeFirst()
    }

    private fun wrap(degrees: Float) = ((degrees + 540f) % 360f) - 180f

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
