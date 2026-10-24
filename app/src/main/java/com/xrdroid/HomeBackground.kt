package com.xrdroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import androidx.annotation.DrawableRes
import java.io.File

// The home screen background: see-through (black, which the glasses don't light up), the wearer's own
// photo, or a preset gradient or picture. Presets are dark and muted, since colour on the glasses is
// opaque and bright.
enum class HomeBackground(val title: String, private val colors: IntArray? = null, @DrawableRes private val picture: Int = 0) {
    NONE("See-through"),
    PHOTO("Photo"),
    DUSK("Dusk", colors = intArrayOf(0xFF2E1846.toInt(), 0xFF7A2F4C.toInt())),
    AURORA("Aurora", picture = R.drawable.bg_aurora),
    PEAKS("Peaks", picture = R.drawable.bg_peaks),
    NEBULA("Nebula", picture = R.drawable.bg_nebula);

    val hasPicture get() = picture != 0

    fun gradient(): GradientDrawable? = colors?.let { GradientDrawable(GradientDrawable.Orientation.TL_BR, it) }

    // The preset picture at 1/sample of its size; full size takes a moment, so decode it off the main thread
    fun picture(context: Context, sample: Int = 1): Bitmap? =
        if (picture == 0) null
        else BitmapFactory.decodeResource(context.resources, picture, BitmapFactory.Options().apply { inSampleSize = sample })

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
