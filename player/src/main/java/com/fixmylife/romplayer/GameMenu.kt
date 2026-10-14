package com.fixmylife.romplayer

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The in-game menu, behind the single hamburger button. One list of actions,
 * with the slot picker as the only screen below it.
 */
class GameMenu(
    private val context: Context,
    private val saves: SaveManager,
    private val actions: Actions,
) {

    interface Actions {
        fun quickSave()
        fun quickLoad()
        fun saveToSlot(slot: SaveManager.Slot)
        fun loadFromSlot(slot: SaveManager.Slot)
        fun importBatterySave()
        fun exportBatterySave()
        fun importState()
        fun exportState(slot: SaveManager.Slot)
        fun resetGame()
        fun setFastForward(speed: Int)
        fun setShader(index: Int)
        fun setMuted(muted: Boolean)
        fun setRotation(mode: Int)
        fun currentRotation(): Int
        fun checkForUpdate()
    }

    private var fastForward = 1
    private var shaderIndex = 0
    private var muted = false

    fun show() {
        val items = listOf(
            "Quick save",
            "Quick load",
            "Save state to a slot",
            "Load state from a slot",
            "Export a save state",
            "Import a save state",
            "Import battery save (.sav)",
            "Export battery save (.sav)",
            "Speed: ${speedLabel()}",
            "Filter: ${SHADERS[shaderIndex]}",
            "Rotation: ${ROTATIONS[actions.currentRotation()]}",
            if (muted) "Sound: off" else "Sound: on",
            "Check for updates",
            "Reset game",
        )
        AlertDialog.Builder(context)
            .setTitle("Menu")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> actions.quickSave()
                    1 -> actions.quickLoad()
                    2 -> pickSlot("Save to which slot?", allowEmpty = true) { actions.saveToSlot(it) }
                    3 -> pickSlot("Load which slot?", allowEmpty = false) { actions.loadFromSlot(it) }
                    4 -> pickSlot("Export which slot?", allowEmpty = false) { actions.exportState(it) }
                    5 -> actions.importState()
                    6 -> actions.importBatterySave()
                    7 -> actions.exportBatterySave()
                    8 -> cycleSpeed()
                    9 -> cycleShader()
                    10 -> pickRotation()
                    11 -> {
                        muted = !muted
                        actions.setMuted(muted)
                    }
                    12 -> actions.checkForUpdate()
                    13 -> confirmReset()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickRotation() {
        AlertDialog.Builder(context)
            .setTitle("Rotation")
            .setSingleChoiceItems(ROTATIONS, actions.currentRotation()) { dialog, which ->
                actions.setRotation(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun speedLabel() = if (fastForward == 1) "normal" else "${fastForward}x"

    private fun cycleSpeed() {
        fastForward = when (fastForward) {
            1 -> 2
            2 -> 3
            else -> 1
        }
        actions.setFastForward(fastForward)
    }

    private fun cycleShader() {
        shaderIndex = (shaderIndex + 1) % SHADERS.size
        actions.setShader(shaderIndex)
    }

    private fun confirmReset() {
        AlertDialog.Builder(context)
            .setTitle("Reset game?")
            .setMessage("This restarts the game. Battery saves are kept; anything since your last in-game save is lost.")
            .setPositiveButton("Reset") { _, _ -> actions.resetGame() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Slot list with thumbnail, slot number and when it was written. */
    fun pickSlot(title: String, allowEmpty: Boolean, onPick: (SaveManager.Slot) -> Unit) {
        val slots = saves.slots().filter { allowEmpty || it.exists }
        if (slots.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No save states yet")
                .setMessage("Use Quick save, or \"Save state to a slot\", first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val adapter = object : ArrayAdapter<SaveManager.Slot>(context, 0, slots) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val slot = slots[position]
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                val thumb = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).also { it.rightMargin = dp(14) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.argb(40, 128, 128, 128))
                    if (slot.thumb.isFile) {
                        setImageBitmap(BitmapFactory.decodeFile(slot.thumb.absolutePath))
                    }
                }
                val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(
                    TextView(context).apply {
                        text = "Slot ${slot.index}"
                        textSize = 16f
                    }
                )
                labels.addView(
                    TextView(context).apply {
                        text = saves.describe(slot)
                        textSize = 12f
                        alpha = 0.7f
                    }
                )
                row.addView(thumb)
                row.addView(labels)
                return row
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> onPick(slots[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        val SHADERS = arrayOf("Sharp", "Smooth", "LCD", "CRT")

        /** Index order matches GameActivity's rotation handling. */
        val ROTATIONS = arrayOf("Auto", "Portrait", "Landscape")
    }
}
