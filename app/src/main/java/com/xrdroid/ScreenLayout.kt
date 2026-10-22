package com.xrdroid

import android.content.Context

// A screen in a layout: where it sits around the wearer (positive yaw is to the left), how wide it
// appears, its resolution, and whether it wraps around the wearer or is a flat panel facing them.
// Sizes keep roughly one screen pixel per glasses pixel (about 42 per degree).
data class ScreenSpec(
    val name: String,
    val yawDegrees: Float,
    val widthDegrees: Float,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val curved: Boolean = false
)

enum class ScreenLayout(val title: String, val description: String, val screens: List<ScreenSpec>) {
    SINGLE(
        "Single screen",
        "One 16:9 screen straight ahead",
        listOf(ScreenSpec("Screen", 0f, 44f, 1920, 1080, 240))
    ),
    DUAL(
        "Dual screens",
        "Two screens side by side. Look left or right to switch between them.",
        listOf(
            ScreenSpec("Left screen", 22f, 40f, 1600, 900, 200),
            ScreenSpec("Right screen", -22f, 40f, 1600, 900, 200)
        )
    ),
    ULTRAWIDE(
        "Ultrawide",
        "One curved 21:9 screen that wraps around you",
        listOf(ScreenSpec("Ultrawide screen", 0f, 62f, 2560, 1080, 240, curved = true))
    );

    companion object {
        private const val PREFS = "settings"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_SCALE = "scale"
        // How far the screens can be brought closer (bigger) or pushed back (smaller)
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.6f

        fun saved(context: Context): ScreenLayout {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
            return entries.firstOrNull { it.name == name } ?: DUAL
        }

        fun save(context: Context, layout: ScreenLayout) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAYOUT, layout.name).apply()
        }

        fun savedScale(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)

        fun saveScale(context: Context, scale: Float) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SCALE, scale).apply()
        }
    }
}
