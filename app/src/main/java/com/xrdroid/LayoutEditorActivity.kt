package com.xrdroid

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

// Makes or changes a custom screen layout: add screens, drag them around the recentre point, and pick
// each one's shape and size. Saving a layout also switches to it.
class LayoutEditorActivity : AppCompatActivity() {

    companion object {
        // The custom layout to edit; without it, a new one is started
        const val EXTRA_LAYOUT_ID = "com.xrdroid.LAYOUT_ID"
    }

    private lateinit var field: LayoutEditorView
    private lateinit var name: EditText
    private lateinit var count: TextView
    private lateinit var add: MaterialButton
    private lateinit var screenTitle: TextView
    private lateinit var removeScreen: MaterialButton
    private lateinit var shapes: ChipGroup
    private lateinit var sizes: ChipGroup
    private lateinit var curved: MaterialSwitch
    private var layoutId = ""
    // As it was opened, to tell whether closing would lose changes
    private lateinit var initial: CustomLayout
    // Set while the controls are being updated to match a screen, so they don't feed back as changes
    private var updatingControls = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_editor)
        field = findViewById(R.id.editor_field)
        name = findViewById(R.id.editor_name)
        count = findViewById(R.id.editor_count)
        add = findViewById(R.id.editor_add)
        screenTitle = findViewById(R.id.editor_screen_title)
        removeScreen = findViewById(R.id.editor_remove_screen)
        shapes = findViewById(R.id.editor_shapes)
        sizes = findViewById(R.id.editor_sizes)
        curved = findViewById(R.id.editor_curved)

        val existing = intent.getStringExtra(EXTRA_LAYOUT_ID)?.let { CustomLayouts.find(this, it) }
        layoutId = existing?.id ?: CustomLayouts.newId()
        name.setText(existing?.name ?: "My layout ${CustomLayouts.load(this).size + 1}")
        field.screens = (existing?.screens ?: listOf(CustomScreen())).toMutableList()
        field.onChanged = { showSelected() }
        initial = current()
        tidyOverlaps()

        findViewById<View>(R.id.editor_close).setOnClickListener { close() }
        onBackPressedDispatcher.addCallback(this) { close() }
        findViewById<View>(R.id.editor_save).setOnClickListener { save() }
        findViewById<View>(R.id.editor_delete).apply {
            isVisible = existing != null
            setOnClickListener { confirmDelete() }
        }
        add.setOnClickListener { addScreen() }
        removeScreen.setOnClickListener { removeScreen() }

        for (shape in ScreenShape.entries) shapes.addView(chip(shape.title, shape))
        for (size in ScreenSize.entries) sizes.addView(chip(size.title, size))
        shapes.setOnCheckedStateChangeListener { group, ids ->
            val shape = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as ScreenShape }
            if (!updatingControls && shape != null) change { it.copy(shape = shape, curved = shape == ScreenShape.ULTRAWIDE) }
        }
        sizes.setOnCheckedStateChangeListener { group, ids ->
            val size = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as ScreenSize }
            if (!updatingControls && size != null) change { it.copy(size = size) }
        }
        curved.setOnCheckedChangeListener { _, checked -> if (!updatingControls) change { it.copy(curved = checked) } }
        showSelected()
    }

    private fun chip(text: String, value: Any) = (layoutInflater.inflate(R.layout.item_chip, shapes, false) as Chip).apply {
        id = View.generateViewId()
        this.text = text
        tag = value
    }

    private fun current() = CustomLayout(layoutId, name.text.toString().trim().ifEmpty { "My layout" }, field.screens.toList())

    // Changes the selected screen, moving it to the nearest free spot if it no longer fits where it is
    private fun change(update: (CustomScreen) -> CustomScreen) {
        val index = field.selected
        val changed = update(field.screens[index])
        val spot = if (field.fits(index, changed, changed.yawDegrees, changed.pitchDegrees)) changed.yawDegrees to changed.pitchDegrees
        else field.nearestFree(index, changed, changed.yawDegrees, changed.pitchDegrees)
        if (spot == null) {
            Toast.makeText(this, "There's no room for a screen that big", Toast.LENGTH_SHORT).show()
        } else {
            field.screens[index] = changed.copy(yawDegrees = spot.first, pitchDegrees = spot.second)
            field.invalidate()
        }
        showSelected()
    }

    // Layouts saved by an older version may have screens closer than the editor now allows, as it learned
    // their exact outlines; move any such screen to the nearest free spot, ready to save
    private fun tidyOverlaps() {
        var moved = false
        field.screens.indices.forEach { i ->
            val screen = field.screens[i]
            if (field.fits(i, screen, screen.yawDegrees, screen.pitchDegrees)) return@forEach
            val spot = field.nearestFree(i, screen, screen.yawDegrees, screen.pitchDegrees) ?: return@forEach
            field.screens[i] = screen.copy(yawDegrees = spot.first, pitchDegrees = spot.second)
            moved = true
        }
        if (moved) Toast.makeText(this, "Moved screens that were too close together. Save to keep it.", Toast.LENGTH_LONG).show()
    }

    // A new screen goes as close to straight ahead as there's room for
    private fun addScreen() {
        if (field.screens.size >= CustomLayouts.MAX_SCREENS) return
        val screen = CustomScreen()
        val spot = field.nearestFree(-1, screen, 0f, 0f)
        if (spot == null) {
            Toast.makeText(this, "There's no room for another screen", Toast.LENGTH_SHORT).show()
            return
        }
        field.screens += screen.copy(yawDegrees = spot.first, pitchDegrees = spot.second)
        field.selected = field.screens.lastIndex
        showSelected()
    }

    private fun removeScreen() {
        if (field.screens.size <= 1) return
        field.screens.removeAt(field.selected)
        field.selected = field.selected.coerceAtMost(field.screens.lastIndex)
        showSelected()
    }

    // Matches the controls to the selected screen
    private fun showSelected() {
        updatingControls = true
        val screen = field.screens[field.selected]
        screenTitle.text = "Screen ${field.selected + 1}"
        shapes.children().firstOrNull { it.tag == screen.shape }?.let { shapes.check(it.id) }
        sizes.children().firstOrNull { it.tag == screen.size }?.let { sizes.check(it.id) }
        curved.isVisible = screen.shape.canCurve
        curved.isChecked = screen.curved
        count.text = "${field.screens.size} of ${CustomLayouts.MAX_SCREENS} screens"
        add.isEnabled = field.screens.size < CustomLayouts.MAX_SCREENS
        removeScreen.isEnabled = field.screens.size > 1
        updatingControls = false
    }

    private fun ChipGroup.children() = (0 until childCount).map { getChildAt(it) }

    private fun save() {
        val layout = current()
        CustomLayouts.save(this, layout)
        ScreenLayout.select(this, layout.id)
        finish()
    }

    private fun close() {
        if (current() == initial) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard changes?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${current().name}?")
            .setPositiveButton("Delete") { _, _ ->
                CustomLayouts.delete(this, layoutId)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
