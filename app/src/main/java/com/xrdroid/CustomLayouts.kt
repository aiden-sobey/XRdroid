package com.xrdroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

// Shapes offered for custom screens, and whether each may curve around the wearer
enum class ScreenShape(val title: String, val aspectWidth: Int, val aspectHeight: Int, val canCurve: Boolean) {
    WIDE("16:9", 16, 9, true),
    ULTRAWIDE("21:9", 21, 9, true),
    CLASSIC("4:3", 4, 3, false),
    SQUARE("1:1", 1, 1, false),
    PORTRAIT("9:16", 9, 16, false)
}

// Sizes by how far the screen's diagonal spans, so every shape at one size looks about as big
enum class ScreenSize(val title: String, val diagonalDegrees: Float) {
    SMALL("Small", 34f),
    MEDIUM("Medium", 46f),
    LARGE("Large", 60f)
}

// One screen in a custom layout, positioned around the recentre point (positive yaw is to the left)
data class CustomScreen(
    val shape: ScreenShape = ScreenShape.WIDE,
    val size: ScreenSize = ScreenSize.MEDIUM,
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val curved: Boolean = false
) {
    companion object {
        // Matches the built-in layouts: about one screen pixel per glasses pixel, and apps laid out at a
        // comfortable size for the distance
        private const val PX_PER_DEGREE = 42f
        private const val DENSITY_DPI = 230
        private const val MAX_PX = 2560
    }

    fun spec(name: String): ScreenSpec {
        val widthDegrees = size.diagonalDegrees * shape.aspectWidth / hypot(shape.aspectWidth.toFloat(), shape.aspectHeight.toFloat())
        // Keep the longer side within what a virtual display handles comfortably
        val longest = widthDegrees * PX_PER_DEGREE * maxOf(1f, shape.aspectHeight.toFloat() / shape.aspectWidth)
        val scale = minOf(1f, MAX_PX / longest)
        val widthPx = roundTo8(widthDegrees * PX_PER_DEGREE * scale)
        val heightPx = roundTo8(widthPx.toFloat() * shape.aspectHeight / shape.aspectWidth)
        return ScreenSpec(name, yawDegrees, widthDegrees, widthPx, heightPx, DENSITY_DPI, curved && shape.canCurve, pitchDegrees)
    }

    private fun roundTo8(value: Float) = (value / 8f).roundToInt() * 8
}

data class CustomLayout(val id: String, val name: String, val screens: List<CustomScreen>) {

    fun toScreenLayout() = ScreenLayout(
        id, name,
        if (screens.size == 1) "1 screen" else "${screens.size} screens",
        screens.mapIndexed { i, screen -> screen.spec("Screen ${i + 1}") },
        custom = true
    )
}

// The wearer's own layouts, kept as JSON alongside the other settings
object CustomLayouts {
    // Each screen is a virtual display the phone renders and composites every frame, with its own apps
    const val MAX_SCREENS = 4

    private const val PREFS = "settings"
    private const val KEY_LAYOUTS = "custom_layouts"

    fun load(context: Context): List<CustomLayout> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val layout = array.getJSONObject(i)
                val screens = layout.getJSONArray("screens")
                CustomLayout(layout.getString("id"), layout.getString("name"), (0 until screens.length()).map { j ->
                    val screen = screens.getJSONObject(j)
                    CustomScreen(
                        ScreenShape.valueOf(screen.getString("shape")),
                        ScreenSize.valueOf(screen.getString("size")),
                        screen.getDouble("yaw").toFloat(),
                        screen.getDouble("pitch").toFloat(),
                        screen.optBoolean("curved")
                    )
                })
            }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: String) = load(context).firstOrNull { it.id == id }

    // Adds the layout, or replaces the one with the same id
    fun save(context: Context, layout: CustomLayout) {
        val layouts = load(context)
        write(context, if (layouts.any { it.id == layout.id }) layouts.map { if (it.id == layout.id) layout else it } else layouts + layout)
    }

    fun delete(context: Context, id: String) = write(context, load(context).filter { it.id != id })

    fun newId() = "custom-${System.currentTimeMillis()}"

    private fun write(context: Context, layouts: List<CustomLayout>) {
        val array = JSONArray()
        for (layout in layouts) {
            val screens = JSONArray()
            for (screen in layout.screens) {
                screens.put(JSONObject()
                    .put("shape", screen.shape.name)
                    .put("size", screen.size.name)
                    .put("yaw", screen.yawDegrees.toDouble())
                    .put("pitch", screen.pitchDegrees.toDouble())
                    .put("curved", screen.curved))
            }
            array.put(JSONObject().put("id", layout.id).put("name", layout.name).put("screens", screens))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAYOUTS, array.toString()).apply()
    }
}
