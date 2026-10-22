package com.xrdroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface

// Creates the virtual displays behind the spatial screens through the shell helper. They are trusted
// displays, so any app can be launched onto them. If Shizuku isn't ready yet, they are created as soon as
// it is; if the helper stops, its displays go with it and are created again when it reconnects.
class VirtualScreens(private val context: Context, private val onStatus: (String) -> Unit) {

    class Spec(val name: String, val width: Int, val height: Int, val densityDpi: Int, val surface: Surface)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var specs: List<Spec> = emptyList()
    private var onCreated: ((List<Int>) -> Unit)? = null
    private val displayIds = mutableListOf<Int>()
    private var released = false

    private val helperListener: () -> Unit = { update() }

    // Calls back on the main thread with a display id per spec, each time the displays are (re)created
    fun create(specs: List<Spec>, onCreated: (displayIds: List<Int>) -> Unit) {
        this.specs = specs
        this.onCreated = onCreated
        Helper.addListener(helperListener)
        Helper.start(context)
        if (Helper.service == null) onStatus("waiting for the Shizuku helper")
        mainHandler.post { update() }
    }

    fun release() {
        released = true
        Helper.removeListener(helperListener)
        mainHandler.removeCallbacksAndMessages(null)
        val helper = Helper.service
        onStatus("releasing displays $displayIds (helper connected: ${helper != null})")
        displayIds.forEach { id ->
            runCatching { helper?.releaseDisplay(id) }.onFailure { onStatus("could not release display $id: $it") }
        }
        displayIds.clear()
    }

    private fun update() {
        if (released) return
        val helper = Helper.service
        if (helper == null) {
            if (displayIds.isNotEmpty()) onStatus("helper stopped, taking displays $displayIds with it")
            displayIds.clear()
            return
        }
        if (displayIds.isNotEmpty()) return
        try {
            // Recorded one at a time, so a failure part way still releases the ones that were made
            specs.forEach { displayIds += helper.createDisplay(it.name, it.width, it.height, it.densityDpi, it.surface) }
            onStatus("created displays $displayIds")
            onCreated?.invoke(displayIds.toList())
        } catch (e: Exception) {
            onStatus("could not create displays: $e")
        }
    }
}
