package com.xrdroid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

// A launchable app: its launcher activity, label and icon
class InstalledApp(val component: ComponentName, val label: String, val icon: Drawable)

object InstalledApps {
    // Every app with a launcher entry except this one, alphabetically. Slow-ish; call off the main thread.
    fun load(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                InstalledApp(
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    it.loadLabel(packageManager).toString(),
                    it.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}

// The apps pinned to the home screen dock, in order
object Dock {
    private const val PREFS = "settings"
    private const val KEY_DOCK = "dock"
    // Offered on first run when installed, so the dock isn't empty
    private val STARTER_PACKAGES = listOf(
        "com.google.android.youtube",
        "com.android.chrome",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.android.vending"
    )

    fun load(context: Context, apps: List<InstalledApp>): List<ComponentName> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_DOCK, null)
        if (saved == null) {
            val starters = STARTER_PACKAGES.mapNotNull { pkg -> apps.firstOrNull { it.component.packageName == pkg }?.component }
            save(context, starters)
            return starters
        }
        return saved.split(',').filter { it.isNotEmpty() }.mapNotNull { ComponentName.unflattenFromString(it) }
    }

    fun add(context: Context, apps: List<InstalledApp>, component: ComponentName) {
        val dock = load(context, apps)
        if (component !in dock) save(context, dock + component)
    }

    fun remove(context: Context, apps: List<InstalledApp>, component: ComponentName) {
        save(context, load(context, apps) - component)
    }

    private fun save(context: Context, dock: List<ComponentName>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DOCK, dock.joinToString(",") { it.flattenToString() })
            .apply()
    }
}
