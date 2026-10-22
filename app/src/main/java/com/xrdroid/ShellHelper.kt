package com.xrdroid

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

// Runs inside Shizuku as the shell user, which may create trusted virtual displays and inject input.
// Unlike displays an ordinary app creates, trusted ones accept any app, keep their own power state (so
// they stay on while the phone screen is off) and their own input focus.
class ShellHelper(context: Context) : IShellHelper.Stub() {

    companion object {
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_UID = 2000
        // Hidden DisplayManager flags, available to the shell user
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_OWN_FOCUS = 1 shl 14
        // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC: don't wait for the app to handle the event
        private const val INJECT_MODE_ASYNC = 0
    }

    // DisplayManager checks that the calling package belongs to the calling uid, so present as the shell
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE

        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    }

    // The Context constructor is hidden; Shizuku's process runs without hidden API restrictions
    private val displayManager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        .newInstance(ShellContext(context))
    private val displays = mutableMapOf<Int, VirtualDisplay>()
    private val appPackage = context.packageName
    private val usbManager = context.getSystemService(UsbManager::class.java)

    // Input injection is hidden API: InputManagerGlobal.injectInputEvent, and InputEvent.setDisplayId to
    // aim each event at a spatial screen. The shell user holds INJECT_EVENTS.
    private val inputManager = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    private val injectInputEvent = inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    override fun createDisplay(name: String, width: Int, height: Int, densityDpi: Int, surface: Surface): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or
            FLAG_TRUSTED or
            FLAG_OWN_DISPLAY_GROUP or
            FLAG_ALWAYS_UNLOCKED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) flags = flags or FLAG_OWN_FOCUS
        val display = displayManager.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
        synchronized(displays) { displays[display.display.displayId] = display }
        return display.display.displayId
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    // An app can only switch itself out of a desktop-mode window while it has focus, which the spatial
    // view doesn't (the trackpad on the phone does), so the shell user launches it fullscreen instead
    override fun launchFullscreen(component: String, displayId: Int): Boolean {
        val command = listOf("am", "start", "--display", displayId.toString(), "--windowingMode", "1", "-n", component)
        return ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0
    }

    override fun allowUsageAccess(): Boolean {
        val command = listOf("appops", "set", appPackage, "GET_USAGE_STATS", "allow")
        return ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0
    }

    // UsbManager.grantPermission(device, uid) is hidden; the shell user holds MANAGE_USB
    override fun allowUsbDevice(deviceName: String): Boolean {
        val device = usbManager.deviceList[deviceName] ?: return false
        val callerUid = Binder.getCallingUid()
        UsbManager::class.java.getMethod("grantPermission", UsbDevice::class.java, Int::class.javaPrimitiveType)
            .invoke(usbManager, device, callerUid)
        return true
    }

    override fun injectMotion(event: MotionEvent, displayId: Int) {
        inject(event, displayId)
    }

    override fun injectKey(keyCode: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId)
        }
    }

    private fun inject(event: InputEvent, displayId: Int) {
        setDisplayId.invoke(event, displayId)
        injectInputEvent.invoke(inputManager, event, INJECT_MODE_ASYNC)
    }

    override fun destroy() {
        synchronized(displays) {
            displays.values.forEach { it.release() }
            displays.clear()
        }
        exitProcess(0)
    }
}
