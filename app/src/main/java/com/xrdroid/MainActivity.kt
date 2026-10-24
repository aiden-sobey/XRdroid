package com.xrdroid

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

// XRdroid's phone screen. Remote: a trackpad for the screen being looked at, with Back and Home.
// Settings: what still needs setting up, and the screen layout. Opening it starts the spatial screens on
// the glasses, and again whenever they reconnect. Permission prompts are raised here, on the phone, because
// prompts shown in the glasses can't be tapped.
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "XRdroid"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PERMISSION_REQUEST = 7302
        private const val ACTION_USB_PERMISSION = "com.xrdroid.CONTROL_USB_PERMISSION"
        // Lets the system finish setting up a newly connected display first
        private const val LAUNCH_DELAY_MS = 750L
        // Each press of the size buttons grows or shrinks the screens by this much
        private const val SCALE_STEP = 1.1f
        // Longest side of the saved background photo, about the widest spatial screen
        private const val MAX_BACKGROUND_PX = 2560
        private const val THUMBNAIL_PX = 200
        // Preset pictures are 2560 wide, so this gives thumbnails a little over THUMBNAIL_PX
        private const val THUMBNAIL_SAMPLE = 8
        private const val SETUP_POLL_MS = 1500L
        // The glasses' display normally starts within a second or two of their USB connection
        private const val REPLUG_AFTER_MS = 4000L
    }

    // One row of the Setup card
    private class Check(view: View) {
        val icon: ImageView = view.findViewById(R.id.check_icon)
        val title: TextView = view.findViewById(R.id.check_title)
        val detail: TextView = view.findViewById(R.id.check_detail)
        val action: MaterialButton = view.findViewById(R.id.check_action)
        var ok = false
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var usbManager: UsbManager
    private lateinit var connection: TextView
    private lateinit var hint: TextView
    private lateinit var shizukuCheck: Check
    private lateinit var headTrackingCheck: Check
    private lateinit var setupCard: View
    private lateinit var setupDone: View
    private val layoutCards = mutableListOf<Pair<ScreenLayout, MaterialCardView>>()
    private val backgroundItems = mutableListOf<Pair<HomeBackground, View>>()
    private val backgroundThumbnails = mutableMapOf<HomeBackground, Bitmap?>()
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Null when the picker was closed, or couldn't fetch the photo itself
        Log.i(TAG, "Photo picker returned ${uri ?: "nothing"}")
        if (uri == null) return@registerForActivityResult
        thread(name = "HomeBackground") {
            val saved = savePhoto(uri)
            runOnUiThread {
                if (saved) chooseBackground(HomeBackground.PHOTO)
                else Toast.makeText(this, "Couldn't use that photo", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var launchedOnDisplay = -1
    // The layout the screens were last built with, to rebuild them when it's switched or edited
    private var appliedLayout = ""
    // When the glasses appeared on USB without their display, or 0
    private var displayMissingSince = 0L
    private var replugPrompt: AlertDialog? = null
    private var replugPromptDismissed = false
    private val helperListener: () -> Unit = {
        refreshSetup()
        scheduleStart()
    }

    private val startRunnable = Runnable { startOnGlasses() }
    private val setupPoll = object : Runnable {
        override fun run() {
            refreshSetup()
            handler.postDelayed(this, SETUP_POLL_MS)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleStart()
        override fun onDisplayRemoved(displayId: Int) = scheduleStart()
        override fun onDisplayChanged(displayId: Int) {}
    }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshSetup() } }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refreshSetup() } }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshSetup() } }
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        // Head tracking keeps retrying, so it picks up the new access by itself
        override fun onReceive(context: Context, intent: Intent) = refreshSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        displayManager = getSystemService(DisplayManager::class.java)
        usbManager = getSystemService(UsbManager::class.java)
        connection = findViewById(R.id.connection)

        val remotePage = findViewById<View>(R.id.page_remote)
        val settingsPage = findViewById<View>(R.id.page_settings)
        val navigation = findViewById<BottomNavigationView>(R.id.nav)
        navigation.setOnItemSelectedListener { item ->
            remotePage.isVisible = item.itemId == R.id.nav_remote
            settingsPage.isVisible = item.itemId == R.id.nav_settings
            refreshSetup()
            true
        }
        setUpRemote()
        setUpSettings()
        // Settings sits on the left, but the app opens on the Remote
        navigation.selectedItemId = R.id.nav_remote

        displayManager.registerDisplayListener(displayListener, handler)
        Helper.addListener(helperListener)
        Helper.start(this)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        ContextCompat.registerReceiver(this, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        // Coming back to this screen also brings the spatial screens back to the front of the glasses
        launchedOnDisplay = -1
        scheduleStart()
        // The layout editor may have added, changed, picked or deleted a layout
        showLayouts()
        applyLayoutIfChanged()
        handler.post(setupPoll)
    }

    override fun onPause() {
        handler.removeCallbacks(setupPoll)
        super.onPause()
    }

    override fun onDestroy() {
        Helper.removeListener(helperListener)
        displayManager.unregisterDisplayListener(displayListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        unregisterReceiver(usbPermissionReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setUpRemote() {
        hint = findViewById(R.id.trackpad_hint)
        findViewById<TrackpadView>(R.id.trackpad).onFirstTouch = { hint.animate().alpha(0f).setDuration(400).start() }
        findViewById<View>(R.id.view_mode).setOnClickListener { showViewModeMenu(it) }
        findViewById<View>(R.id.further).setOnClickListener { view -> changeScale(view, 1f / SCALE_STEP) }
        findViewById<View>(R.id.closer).setOnClickListener { view -> changeScale(view, SCALE_STEP) }
        findViewById<View>(R.id.recenter).setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Session.onRecenterRequested?.invoke()
        }
        findViewById<View>(R.id.back).setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val screen = Session.activeScreen ?: return@setOnClickListener
            Helper.service?.injectKey(KeyEvent.KEYCODE_BACK, screen.displayId)
        }
        findViewById<View>(R.id.home).setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val screen = Session.activeScreen ?: return@setOnClickListener
            Session.onHomeRequested?.invoke(screen.displayId)
        }
    }

    private fun setUpSettings() {
        val checks = findViewById<LinearLayout>(R.id.checks)
        fun addCheck(): Check {
            if (checks.childCount > 0) {
                checks.addView(MaterialDivider(this).apply {
                    dividerInsetStart = dp(60)
                    dividerInsetEnd = dp(20)
                })
            }
            val view = layoutInflater.inflate(R.layout.item_check, checks, false)
            checks.addView(view)
            return Check(view)
        }
        shizukuCheck = addCheck()
        headTrackingCheck = addCheck()
        setupCard = findViewById(R.id.setup_card)
        setupDone = findViewById(R.id.setup_done)

        findViewById<View>(R.id.new_layout).setOnClickListener { startActivity(Intent(this, LayoutEditorActivity::class.java)) }
        appliedLayout = ScreenLayout.saved(this).signature
        showLayouts()
        setUpBackgrounds()
    }

    // The built-in layouts and the wearer's own, which can be edited
    private fun showLayouts() {
        val layouts = findViewById<LinearLayout>(R.id.layouts)
        layouts.removeAllViews()
        layoutCards.clear()
        for (layout in ScreenLayout.all(this)) {
            val card = layoutInflater.inflate(R.layout.item_layout, layouts, false) as MaterialCardView
            card.findViewById<LayoutPreviewView>(R.id.layout_preview).layout = layout
            card.findViewById<TextView>(R.id.layout_title).text = layout.title
            card.findViewById<TextView>(R.id.layout_description).text = layout.description
            card.setOnClickListener { selectLayout(layout) }
            card.findViewById<View>(R.id.layout_edit).apply {
                isVisible = layout.custom
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, LayoutEditorActivity::class.java).putExtra(LayoutEditorActivity.EXTRA_LAYOUT_ID, layout.id))
                }
            }
            layouts.addView(card)
            layoutCards += layout to card
        }
        showSelectedLayout()
    }

    private fun setUpBackgrounds() {
        val row = findViewById<LinearLayout>(R.id.backgrounds)
        for (option in HomeBackground.entries) {
            val item = layoutInflater.inflate(R.layout.item_background, row, false)
            item.findViewById<TextView>(R.id.background_label).text = option.title
            item.findViewById<View>(R.id.background_card).setOnClickListener {
                if (option == HomeBackground.PHOTO) {
                    pickPhoto.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build())
                } else {
                    chooseBackground(option)
                }
            }
            row.addView(item)
            backgroundItems += option to item
        }
        showBackgrounds()
    }

    private fun chooseBackground(option: HomeBackground) {
        HomeBackground.save(this, option)
        showBackgrounds()
        Session.homes.values.mapNotNull { it.get() }.forEach { it.applyBackground() }
    }

    private fun showBackgrounds() {
        val selected = HomeBackground.saved(this)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val outline = ContextCompat.getColor(this, R.color.outline)
        for ((option, item) in backgroundItems) {
            val card = item.findViewById<MaterialCardView>(R.id.background_card)
            val preview = item.findViewById<ImageView>(R.id.background_preview)
            val icon = item.findViewById<ImageView>(R.id.background_icon)
            card.strokeColor = if (option == selected) accent else outline
            card.strokeWidth = dp(if (option == selected) 2 else 1)
            val photo = HomeBackground.photoFile(this)
            when {
                option == HomeBackground.NONE -> {
                    preview.setImageDrawable(null)
                    icon.setImageResource(R.drawable.ic_see_through)
                }
                option == HomeBackground.PHOTO && photo.exists() -> {
                    preview.setImageBitmap(decodeThumbnail(photo.path))
                    icon.setImageDrawable(null)
                }
                option == HomeBackground.PHOTO -> {
                    preview.setImageDrawable(null)
                    icon.setImageResource(R.drawable.ic_closer)
                }
                option.hasPicture -> {
                    // A small copy is quick to decode, and kept for later refreshes
                    preview.setImageBitmap(backgroundThumbnails.getOrPut(option) { option.picture(this, THUMBNAIL_SAMPLE) })
                    icon.setImageDrawable(null)
                }
                else -> {
                    preview.setImageDrawable(option.gradient())
                    icon.setImageDrawable(null)
                }
            }
        }
    }

    // Saves a resized copy of the chosen photo; runs off the main thread
    private fun savePhoto(uri: Uri): Boolean = try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_BACKGROUND_PX) {
                    val scale = MAX_BACKGROUND_PX.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                // A software bitmap, so it can be compressed
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        }
        HomeBackground.photoFile(this).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not save the background photo from $uri", e)
        false
    }

    private fun decodeThumbnail(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun selectLayout(layout: ScreenLayout) {
        if (layout == ScreenLayout.saved(this)) return
        ScreenLayout.save(this, layout)
        showSelectedLayout()
        applyLayoutIfChanged()
    }

    private fun showViewModeMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            val fixed = ScreenLayout.savedFixed(this@MainActivity)
            menu.add(0, 0, 0, "Floating: stays put in the room").isChecked = !fixed
            menu.add(0, 1, 1, "Fixed: moves with your head").isChecked = fixed
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                val choice = item.itemId == 1
                ScreenLayout.saveFixed(this@MainActivity, choice)
                Session.onFixedChanged?.invoke(choice)
                showSelectedLayout()
                true
            }
            show()
        }
    }

    private fun applyLayoutIfChanged() {
        val layout = ScreenLayout.saved(this).signature
        if (layout == appliedLayout) return
        appliedLayout = layout
        if (Session.running) rebuildScreens()
    }

    private fun showSelectedLayout() {
        val selected = ScreenLayout.saved(this)
        val fixed = ScreenLayout.savedFixed(this)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val outline = ContextCompat.getColor(this, R.color.outline)
        val text = ContextCompat.getColor(this, R.color.text)
        for ((layout, card) in layoutCards) {
            // Shown by the border, sketch and title rather than tinting the card, which would dim its text
            val isSelected = layout == selected
            card.isChecked = isSelected
            card.strokeColor = if (isSelected) accent else outline
            card.strokeWidth = dp(if (isSelected) 2 else 1)
            card.findViewById<LayoutPreviewView>(R.id.layout_preview).highlighted = isSelected
            card.findViewById<TextView>(R.id.layout_title).setTextColor(if (isSelected) accent else text)
        }
        // On the Remote tab, a single screen can stay put in the room or follow the head
        findViewById<MaterialButton>(R.id.view_mode).apply {
            isVisible = selected.screens.size == 1
            this.text = if (fixed) "Fixed" else "Floating"
        }
        // A fixed screen is always straight ahead, so there's nothing to recentre
        findViewById<View>(R.id.recenter).apply {
            isEnabled = !ScreenLayout.isFixed(this@MainActivity, selected)
            alpha = if (isEnabled) 1f else 0.4f
        }
    }

    private fun refreshSetup() {
        val glassesConnected = glassesDisplay() != null
        val glassesUsb = usbManager.deviceList.values.firstOrNull { it.vendorId == VitureImu.VITURE_VENDOR_ID }
        // Sometimes the glasses connect over USB but their display never starts, and only replugging them
        // brings it up
        val now = SystemClock.uptimeMillis()
        if (glassesUsb == null || glassesConnected) {
            displayMissingSince = 0L
            replugPromptDismissed = false
        } else if (displayMissingSince == 0L) {
            displayMissingSince = now
        }
        val needsReplug = displayMissingSince != 0L && now - displayMissingSince >= REPLUG_AFTER_MS
        showReplugPrompt(needsReplug)

        connection.text = when {
            glassesConnected -> "Glasses connected"
            needsReplug -> "Replug glasses"
            else -> "No glasses"
        }
        val dotColor = ContextCompat.getColor(this, when {
            glassesConnected -> R.color.good
            needsReplug -> R.color.attention
            else -> R.color.text_muted
        })
        connection.setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
            setSize(dp(7), dp(7))
        }, null, null, null)
        connection.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, R.color.surface_high))
            cornerRadius = dp(16).toFloat()
        }

        // Shizuku runs the helper that creates the screens and delivers the trackpad's touches to them
        when {
            !isInstalled(SHIZUKU_PACKAGE) -> show(shizukuCheck, "Screens and trackpad", "Needs Shizuku, which isn't installed", ok = false, "Install") {
                openStore(SHIZUKU_PACKAGE)
            }
            !Shizuku.pingBinder() -> show(shizukuCheck, "Screens and trackpad", "Shizuku isn't running. Start it in the Shizuku app.", ok = false, "Open") {
                packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let { startActivity(it) }
            }
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> show(shizukuCheck, "Screens and trackpad", "Shizuku needs to allow XRdroid", ok = false, "Allow") {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
            Helper.service == null -> show(shizukuCheck, "Screens and trackpad", "Starting the helper...", ok = false)
            else -> show(shizukuCheck, "Screens and trackpad", "Shizuku is running and allowed", ok = true)
        }
        hint.text = getString(when {
            Helper.service == null -> R.string.trackpad_hint_setup
            needsReplug -> R.string.trackpad_hint_replug
            else -> R.string.trackpad_hint
        })

        // Head tracking reads the glasses' motion sensor over USB. Android forgets access whenever they're
        // unplugged; the helper grants it again without a prompt.
        if (glassesUsb != null && !usbManager.hasPermission(glassesUsb)) Helper.allowUsb(glassesUsb)
        when {
            glassesUsb == null -> show(headTrackingCheck, "Head tracking", "Connect your VITURE glasses", ok = false)
            !usbManager.hasPermission(glassesUsb) -> show(headTrackingCheck, "Head tracking", "Allow access to the glasses' motion sensor", ok = false, "Allow") {
                requestUsbPermission(glassesUsb)
            }
            else -> show(headTrackingCheck, "Head tracking", "Reading the glasses' motion sensor", ok = true)
        }
        // Once everything is working, Setup shrinks to a tick
        val allOk = shizukuCheck.ok && headTrackingCheck.ok
        setupCard.isVisible = !allOk
        setupDone.isVisible = allOk
    }

    // Shown once each time it happens, and closed by itself once the display starts
    private fun showReplugPrompt(needed: Boolean) {
        if (!needed) {
            replugPrompt?.dismiss()
            replugPrompt = null
            return
        }
        if (replugPrompt != null || replugPromptDismissed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        replugPrompt = MaterialAlertDialogBuilder(this)
            .setTitle("Replug your glasses")
            .setMessage("Your glasses are connected, but their display hasn't started. Unplug them from the phone and plug them back in.")
            .setPositiveButton("OK") { _, _ -> replugPromptDismissed = true }
            .setOnCancelListener { replugPromptDismissed = true }
            .show()
    }

    private fun show(check: Check, title: String, detail: String, ok: Boolean, actionLabel: String? = null, action: (() -> Unit)? = null) {
        check.ok = ok
        check.title.text = title
        check.detail.text = detail
        check.icon.setImageResource(if (ok) R.drawable.ic_ok else R.drawable.ic_attention)
        check.icon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (ok) R.color.good else R.color.attention)
        )
        check.action.isVisible = !ok && actionLabel != null
        check.action.text = actionLabel
        check.action.setOnClickListener { action?.invoke() }
    }

    // Brings the screens closer (bigger) or pushes them back (smaller), within limits
    private fun changeScale(view: View, factor: Float) {
        val current = ScreenLayout.savedScale(this)
        val scale = (current * factor).coerceIn(ScreenLayout.MIN_SCALE, ScreenLayout.MAX_SCALE)
        // A longer buzz at the limit, so it's clear the button did nothing
        view.performHapticFeedback(if (scale == current) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CLOCK_TICK)
        if (scale == current) return
        ScreenLayout.saveScale(this, scale)
        Session.onScaleChanged?.invoke(scale)
    }

    private fun rebuildScreens() {
        val glasses = glassesDisplay() ?: return
        val intent = Intent(this, GlassesActivity::class.java)
            .setAction(GlassesActivity.ACTION_APPLY_LAYOUT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(glasses.displayId).toBundle())
    }

    private fun scheduleStart() {
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, LAUNCH_DELAY_MS)
    }

    // A real external display rather than one of the spatial screens. External displays are flagged for
    // presentations and the spatial screens aren't; fall back to any other public display just in case.
    private fun glassesDisplay(): Display? {
        val candidates = displayManager.displays.filter {
            it.displayId != Display.DEFAULT_DISPLAY &&
                (it.flags and Display.FLAG_PRIVATE) == 0 &&
                !Session.isSpatialDisplay(it.displayId)
        }
        return candidates.firstOrNull { (it.flags and Display.FLAG_PRESENTATION) != 0 } ?: candidates.firstOrNull()
    }

    private fun startOnGlasses() {
        refreshSetup()
        val glasses = glassesDisplay()
        if (glasses == null) {
            launchedOnDisplay = -1
            return
        }
        if (glasses.displayId == launchedOnDisplay) return
        // The screens need the helper, which also launches the view fullscreen on glasses in desktop mode
        // (it would open as a window otherwise). Setup on the Settings tab says what's missing.
        val helper = Helper.service ?: return
        launchedOnDisplay = glasses.displayId
        val component = ComponentName(this, GlassesActivity::class.java)
        val launched = runCatching { helper.launchFullscreen(component.flattenToString(), glasses.displayId) }.getOrDefault(false)
        if (!launched) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(glasses.displayId).toBundle())
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        // The system fills in the result extras, so the PendingIntent has to be mutable
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun isInstalled(packageName: String) = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun openStore(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
