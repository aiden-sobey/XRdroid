package com.xrdroid

import android.content.Context
import kotlin.math.atan
import kotlin.math.tan

// A screen in a layout: where it sits around the wearer (positive yaw is to the left, positive pitch is up),
// how wide it appears, its resolution, and whether it wraps around the wearer or is a flat panel facing them.
// Sizes keep roughly one screen pixel per glasses pixel (about 42 per degree).
data class ScreenSpec(
    val name: String,
    val yawDegrees: Float,
    val widthDegrees: Float,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val curved: Boolean = false,
    val pitchDegrees: Float = 0f
) {
    // How tall the screen appears, from its width and pixel shape, as the renderer builds it
    val heightDegrees: Float
        get() {
            val halfWidth = Math.toRadians(widthDegrees / 2.0)
            val width = if (curved) 2 * halfWidth else 2 * tan(halfWidth)
            return Math.toDegrees(2 * atan(width * heightPx / widthPx / 2)).toFloat()
        }
}

// A set of screens: one of the built-in layouts, or one the wearer made in the layout editor
class ScreenLayout(val id: String, val title: String, val description: String, val screens: List<ScreenSpec>, val custom: Boolean = false) {

    override fun equals(other: Any?) = other is ScreenLayout && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = id

    // Changes whenever the screens would be built differently
    val signature get() = id + screens

    companion object {
        private const val PREFS = "settings"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_SCALE = "scale"
        private const val KEY_FIXED = "fixed"
        // How far the screens can be brought closer (bigger) or pushed back (smaller)
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.6f

        val SINGLE = ScreenLayout(
            "SINGLE", "Single screen", "One 16:9 screen straight ahead",
            listOf(ScreenSpec("Screen", 0f, 44f, 1920, 1080, 240))
        )
        val DUAL = ScreenLayout(
            "DUAL", "Dual screens", "Two screens side by side",
            listOf(
                ScreenSpec("Left screen", 22f, 40f, 1600, 900, 200),
                ScreenSpec("Right screen", -22f, 40f, 1600, 900, 200)
            )
        )
        val ULTRAWIDE = ScreenLayout(
            "ULTRAWIDE", "Ultrawide", "One curved 21:9 screen",
            listOf(ScreenSpec("Ultrawide screen", 0f, 62f, 2560, 1080, 240, curved = true))
        )
        val BUILT_IN = listOf(SINGLE, DUAL, ULTRAWIDE)

        fun all(context: Context) = BUILT_IN + CustomLayouts.load(context).map { it.toScreenLayout() }

        fun saved(context: Context): ScreenLayout {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
            return all(context).firstOrNull { it.id == id } ?: DUAL
        }

        fun save(context: Context, layout: ScreenLayout) = select(context, layout.id)

        fun select(context: Context, id: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAYOUT, id).apply()
        }

        // A single screen can follow the head instead of staying put in the room
        fun savedFixed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FIXED, false)

        fun saveFixed(context: Context, fixed: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FIXED, fixed).apply()
        }

        fun isFixed(context: Context, layout: ScreenLayout) = layout.screens.size == 1 && savedFixed(context)

        fun savedScale(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)

        fun saveScale(context: Context, scale: Float) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SCALE, scale).apply()
        }
    }
}
