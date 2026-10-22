package com.xrdroid

import android.content.Context
import android.graphics.drawable.GradientDrawable
import java.io.File

// The home screen background: see-through (black, which the glasses don't light up), a preset gradient,
// or the wearer's own photo. Presets are muted, since colour on the glasses is opaque and bright.
enum class HomeBackground(val title: String, private val colors: IntArray?) {
    NONE("See-through", null),
    AURORA("Aurora", intArrayOf(0xFF1E1650.toInt(), 0xFF0C3F4D.toInt())),
    DUSK("Dusk", intArrayOf(0xFF2E1846.toInt(), 0xFF7A2F4C.toInt())),
    OCEAN("Ocean", intArrayOf(0xFF081C36.toInt(), 0xFF164B78.toInt())),
    FOREST("Forest", intArrayOf(0xFF0B1F16.toInt(), 0xFF24553F.toInt())),
    PHOTO("Photo", null);

    fun gradient(): GradientDrawable? = colors?.let { GradientDrawable(GradientDrawable.Orientation.TL_BR, it) }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_BACKGROUND = "background"

        fun saved(context: Context): HomeBackground {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND, null)
            val background = entries.firstOrNull { it.name == name } ?: NONE
            // Fall back to see-through if the photo has gone missing
            return if (background == PHOTO && !photoFile(context).exists()) NONE else background
        }

        fun save(context: Context, background: HomeBackground) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BACKGROUND, background.name).apply()
        }

        // A resized copy of the chosen photo, kept in the app's own storage
        fun photoFile(context: Context) = File(context.filesDir, "background.jpg")
    }
}
