package com.xrdroid

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

// What the phone screen and the glasses share while XRdroid is running: the spatial screens, which one
// the wearer is looking at (and so gets the trackpad), and hooks for the phone's buttons
object Session {
    // A spatial screen as the trackpad sees it: its display, size in pixels and cursor position
    class Screen(val displayId: Int, val width: Int, val height: Int, val name: String) {
        @Volatile var cursorX = width / 2f
        @Volatile var cursorY = height / 2f
    }

    @Volatile var running = false
    @Volatile var screens: List<Screen> = emptyList()
    // The screen being looked at, or null before the screens exist
    @Volatile var activeScreen: Screen? = null
    // True while a finger is on the trackpad, so the active screen can't change mid-gesture
    @Volatile var touchActive = false

    @Volatile var onHomeRequested: ((Int) -> Unit)? = null
    @Volatile var onRecenterRequested: (() -> Unit)? = null
    // Called with the new size when the screens are brought closer or pushed back
    @Volatile var onScaleChanged: ((Float) -> Unit)? = null
    // Called when a single screen switches between staying put in the room and following the head
    @Volatile var onFixedChanged: ((Boolean) -> Unit)? = null

    // The home screen on each spatial screen, by display id
    val homes = ConcurrentHashMap<Int, WeakReference<HomeActivity>>()

    fun isSpatialDisplay(displayId: Int) = screens.any { it.displayId == displayId }
}
