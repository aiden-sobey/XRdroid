package com.xrdroid

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// The view on the glasses: app screens fixed in space around the wearer, turned with head tracking from
// VITURE glasses. The phone screen launches it on the glasses display. For tuning over adb:
//   adb shell am broadcast -a com.xrdroid.RECENTER
//   adb shell am broadcast -a com.xrdroid.TUNE --ef fov 26 --ef lookahead 0.03
class GlassesActivity : Activity() {

    companion object {
        private const val TAG = "XRdroid"
        private const val ACTION_RECENTER = "com.xrdroid.RECENTER"
        private const val ACTION_TUNE = "com.xrdroid.TUNE"
        // Sent by the phone screen when the layout changes; the screens are rebuilt to match
        const val ACTION_APPLY_LAYOUT = "com.xrdroid.APPLY_LAYOUT"
        private const val CURSOR_BITMAP_PX = 64
    }

    private lateinit var imu: VitureImu
    private lateinit var renderer: SceneRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var displays: VirtualScreens
    private var displaysRequested = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_RECENTER -> renderer.recenterRequested = true
                ACTION_TUNE -> {
                    if (intent.hasExtra("fov")) renderer.verticalFovDegrees = intent.getFloatExtra("fov", renderer.verticalFovDegrees)
                    if (intent.hasExtra("lookahead")) renderer.lookaheadSeconds = intent.getFloatExtra("lookahead", renderer.lookaheadSeconds)
                }
            }
            Log.i(TAG, "${intent.action}: fov=${renderer.verticalFovDegrees} lookahead=${renderer.lookaheadSeconds}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.i(TAG, "Created ${hashCode()} (${ScreenLayout.saved(this)})")
        Session.running = true
        imu = VitureImu(this)
        displays = VirtualScreens(this) { status -> Log.i(TAG, "Displays: $status") }
        val cursor = ContextCompat.getDrawable(this, R.drawable.cursor)!!.toBitmap(CURSOR_BITMAP_PX, CURSOR_BITMAP_PX)
        renderer = SceneRenderer(imu, cursor, ScreenLayout.saved(this), ScreenLayout.savedScale(this)) { surfaces ->
            runOnUiThread { createScreens(surfaces) }
        }
        Session.onRecenterRequested = { renderer.recenterRequested = true }
        Session.onScaleChanged = { scale -> glView.queueEvent { renderer.setScale(scale) } }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
        }
        setContentView(glView)
        WindowInsetsControllerCompat(window, glView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val filter = IntentFilter().apply {
            addAction(ACTION_RECENTER)
            addAction(ACTION_TUNE)
        }
        // Exported so adb can send recentre and tuning commands
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        imu.start { status -> Log.i(TAG, "Head tracking: $status") }
    }

    private fun createScreens(surfaces: List<Surface>) {
        if (displaysRequested) return
        displaysRequested = true
        val screenSpecs = renderer.screens.map { it.spec }
        val specs = surfaces.mapIndexed { i, surface ->
            val spec = screenSpecs[i]
            VirtualScreens.Spec("XRdroid screen ${'A' + i}", spec.widthPx, spec.heightPx, spec.densityDpi, surface)
        }
        // Called again if the helper restarts, since its displays go with it
        displays.create(specs) { displayIds ->
            displayIds.forEachIndexed { i, id -> Log.i(TAG, "${specs[i].name}: display $id") }
            // Hand the screens to the trackpad; the renderer picks which one is being looked at
            Session.screens = displayIds.mapIndexed { i, id ->
                Session.Screen(id, screenSpecs[i].widthPx, screenSpecs[i].heightPx, screenSpecs[i].name)
            }
            Session.onHomeRequested = { displayId -> runOnUiThread { showHome(displayId) } }
            displayIds.forEach { showHome(it) }
        }
    }

    // Brings a screen's home screen to the front (closing its app drawer), or opens one if it has none
    private fun showHome(displayId: Int) {
        val home = Session.homes[displayId]?.get()
        if (home != null && !home.isFinishing) {
            getSystemService(ActivityManager::class.java).appTasks
                .firstOrNull { it.taskInfo.taskId == home.taskId }
                ?.moveToFront()
            home.showHome()
            return
        }
        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "Could not show the home screen on display $displayId", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Rebuilding the activity releases the old screens (closing their apps) and creates the new layout
        Log.i(TAG, "New intent ${intent.action} for ${hashCode()}")
        if (intent.action == ACTION_APPLY_LAYOUT) recreate()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        // Glasses in desktop mode open activities as windows, but the spatial view has to fill the display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && isInMultiWindowMode) {
            requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, object : OutcomeReceiver<Void, Throwable> {
                override fun onResult(result: Void?) {}
                override fun onError(error: Throwable) {
                    Log.w(TAG, "Could not go fullscreen on the glasses", error)
                }
            })
        }
    }

    override fun onPause() {
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroyed ${hashCode()}")
        unregisterReceiver(controlReceiver)
        imu.stop()
        Session.running = false
        Session.onRecenterRequested = null
        Session.onScaleChanged = null
        Session.onHomeRequested = null
        Session.activeScreen = null
        Session.screens = emptyList()
        displays.release()
        renderer.screens.forEach { screen ->
            if (screen.textureId != 0) {
                screen.surface.release()
                screen.surfaceTexture.release()
            }
        }
        super.onDestroy()
    }
}
