package com.xrdroid

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.util.concurrent.TimeUnit

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

// The apps on the home screen dock, in order. Until the wearer adds or removes one, the dock follows
// their most-used apps; after that it keeps their choice.
object Dock {
    private const val PREFS = "settings"
    private const val KEY_DOCK = "dock"
    private const val SIZE = 6
    private const val USAGE_DAYS = 14L
    // Used when usage access isn't allowed, so the dock isn't empty
    private val STARTER_PACKAGES = listOf(
        "com.google.android.youtube",
        "com.android.chrome",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.android.vending"
    )

    // Reads app usage when the wearer hasn't chosen the dock; call off the main thread
    fun load(context: Context, apps: List<InstalledApp>): List<ComponentName> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DOCK, null)
        if (saved != null) return saved.split(',').filter { it.isNotEmpty() }.mapNotNull { ComponentName.unflattenFromString(it) }
        return mostUsed(context, apps).ifEmpty {
            STARTER_PACKAGES.mapNotNull { pkg -> apps.firstOrNull { it.component.packageName == pkg }?.component }
        }
    }

    fun save(context: Context, dock: List<ComponentName>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DOCK, dock.joinToString(",") { it.flattenToString() })
            .apply()
    }

    // Launchable apps by time on screen over the last two weeks; empty without usage access
    private fun mostUsed(context: Context, apps: List<InstalledApp>): List<ComponentName> {
        val end = System.currentTimeMillis()
        val usage = context.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(end - TimeUnit.DAYS.toMillis(USAGE_DAYS), end)
        val byPackage = apps.groupBy { it.component.packageName }
        return usage.values
            .filter { it.totalTimeVisible > 0 && it.packageName in byPackage }
            .sortedByDescending { it.totalTimeVisible }
            .take(SIZE)
            .map { byPackage.getValue(it.packageName).first().component }
    }
}
