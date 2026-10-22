package com.xrdroid

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

// The home screen on a spatial screen: a clock, a search bar that opens the app drawer, and a dock of
// favourite apps. Apps open on the same screen. Back closes the drawer but never the home screen.
class HomeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "XRdroid"
        private const val DOCK_ICON_DP = 56
        private const val DRAWER_CELL_DP = 132
    }

    private lateinit var homeContent: View
    private lateinit var drawer: View
    private lateinit var query: EditText
    private lateinit var dock: LinearLayout
    private lateinit var background: ImageView
    private lateinit var scrim: View
    private val adapter = AppAdapter()
    private var apps: List<InstalledApp> = emptyList()

    private val displayIdCompat: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!!.displayId else windowManager.defaultDisplay.displayId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        homeContent = findViewById(R.id.home_content)
        drawer = findViewById(R.id.home_drawer)
        query = findViewById(R.id.home_query)
        dock = findViewById(R.id.home_dock)
        background = findViewById(R.id.home_background)
        scrim = findViewById(R.id.home_scrim)
        applyBackground()

        findViewById<View>(R.id.home_search).setOnClickListener { openDrawer() }
        query.doAfterTextChanged { filter() }
        // Enter opens the top match
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) adapter.items.firstOrNull()?.let { launch(it) }
            true
        }
        findViewById<RecyclerView>(R.id.home_grid).apply {
            val columns = (resources.displayMetrics.widthPixels / (DRAWER_CELL_DP * resources.displayMetrics.density)).toInt().coerceAtLeast(4)
            layoutManager = GridLayoutManager(this@HomeActivity, columns)
            adapter = this@HomeActivity.adapter
        }
        onBackPressedDispatcher.addCallback(this) {
            if (drawer.isVisible) closeDrawer()
        }

        Session.homes[displayIdCompat] = WeakReference(this)
        thread(name = "InstalledApps") {
            val loaded = InstalledApps.load(this)
            runOnUiThread {
                apps = loaded
                refreshDock()
                filter()
            }
        }
    }

    override fun onDestroy() {
        if (Session.homes[displayIdCompat]?.get() === this) Session.homes.remove(displayIdCompat)
        super.onDestroy()
    }

    // Back to the home screen itself, e.g. when Home is pressed on the phone
    fun showHome() {
        closeDrawer()
    }

    // Shows the background chosen in Settings
    fun applyBackground() {
        val chosen = HomeBackground.saved(this)
        scrim.isVisible = chosen == HomeBackground.PHOTO
        if (chosen != HomeBackground.PHOTO) {
            background.setImageDrawable(chosen.gradient())
            return
        }
        val file = HomeBackground.photoFile(this)
        thread(name = "HomeBackground") {
            val bitmap = BitmapFactory.decodeFile(file.path)
            runOnUiThread { if (!isDestroyed) background.setImageBitmap(bitmap) }
        }
    }

    // The keyboard can't appear on a spatial screen, so Android shows it on the phone instead
    private fun openDrawer() {
        query.text.clear()
        drawer.isVisible = true
        homeContent.isVisible = false
        query.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeDrawer() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        query.text.clear()
        drawer.isVisible = false
        homeContent.isVisible = true
    }

    private fun filter() {
        val text = query.text.toString().trim()
        adapter.items = if (text.isEmpty()) apps else apps.filter { it.label.contains(text, ignoreCase = true) }
    }

    private fun launch(app: InstalledApp) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayIdCompat).toBundle())
            closeDrawer()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open ${app.label} on display $displayIdCompat", e)
        }
    }

    // Rebuilds the dock from the saved favourites, followed by the All apps button
    private fun refreshDock() {
        dock.removeAllViews()
        val size = dp(DOCK_ICON_DP)
        for (component in Dock.load(this, apps)) {
            val app = apps.firstOrNull { it.component == component } ?: continue
            dock.addView(dockButton(app.label) {
                // Its own copy, since the drawer shows the same icon
                setImageDrawable(app.icon.constantState?.newDrawable(resources)?.mutate() ?: app.icon)
            }.apply {
                setOnClickListener { launch(app) }
                setOnLongClickListener { view ->
                    showDockMenu(view, app)
                    true
                }
            }, LinearLayout.LayoutParams(size + dp(16), size + dp(16)))
        }
        if (dock.childCount > 0) {
            dock.addView(View(this).apply { setBackgroundColor(getColor(R.color.outline)) },
                LinearLayout.LayoutParams(dp(1), dp(40)).apply { setMargins(dp(10), 0, dp(10), 0) })
        }
        dock.addView(dockButton("All apps") {
            setImageResource(R.drawable.ic_apps)
            setColorFilter(getColor(R.color.text))
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }.apply { setOnClickListener { openDrawer() } }, LinearLayout.LayoutParams(size + dp(16), size + dp(16)))
    }

    private fun dockButton(description: String, setUp: ImageView.() -> Unit) = ImageView(this).apply {
        contentDescription = description
        background = getDrawable(R.drawable.app_ripple)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true
        isFocusable = true
        setUp()
    }

    private fun showDockMenu(anchor: View, app: InstalledApp) {
        PopupMenu(this, anchor).apply {
            menu.add("Remove from dock").setOnMenuItemClickListener {
                Dock.remove(this@HomeActivity, apps, app.component)
                refreshAllDocks()
                true
            }
            show()
        }
    }

    private fun showDrawerMenu(anchor: View, app: InstalledApp) {
        val inDock = app.component in Dock.load(this, apps)
        PopupMenu(this, anchor).apply {
            menu.add(if (inDock) "Remove from dock" else "Add to dock").setOnMenuItemClickListener {
                if (inDock) Dock.remove(this@HomeActivity, apps, app.component)
                else Dock.add(this@HomeActivity, apps, app.component)
                refreshAllDocks()
                true
            }
            show()
        }
    }

    // Every spatial screen shows the same dock
    private fun refreshAllDocks() {
        Session.homes.values.mapNotNull { it.get() }.forEach { it.refreshDock() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {

        var items: List<InstalledApp> = emptyList()
            set(value) {
                field = value
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            holder.itemView.setOnClickListener { launch(app) }
            holder.itemView.setOnLongClickListener { view ->
                showDrawerMenu(view, app)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
