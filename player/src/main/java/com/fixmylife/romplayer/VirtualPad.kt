package com.fixmylife.romplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Transparent overlay with a D-pad, A/B, Start/Select, L/R (GBA only) and
 * quick save/load. Each touch event recomputes the full pressed set from all
 * active pointers, so sliding a thumb from B onto A just works.
 */
@SuppressLint("ViewConstructor")
class VirtualPad(
    context: Context,
    private val showShoulders: Boolean,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onButton(keyCode: Int, pressed: Boolean)
        fun onDpad(x: Int, y: Int)
        fun onQuickSave()
        fun onQuickLoad()
    }

    private class Btn(val key: Int, val label: String, val round: Boolean) {
        val rect = RectF()
        fun hit(x: Float, y: Float): Boolean {
            val padX = rect.width() * 0.2f
            val padY = rect.height() * 0.2f
            return x >= rect.left - padX && x <= rect.right + padX && y >= rect.top - padY && y <= rect.bottom + padY
        }
    }

    private val keySave = -1
    private val keyLoad = -2

    private val buttons = buildList {
        add(Btn(KeyEvent.KEYCODE_BUTTON_A, "A", true))
        add(Btn(KeyEvent.KEYCODE_BUTTON_B, "B", true))
        add(Btn(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", false))
        add(Btn(KeyEvent.KEYCODE_BUTTON_START, "START", false))
        if (showShoulders) {
            add(Btn(KeyEvent.KEYCODE_BUTTON_L1, "L", false))
            add(Btn(KeyEvent.KEYCODE_BUTTON_R1, "R", false))
        }
        add(Btn(keySave, "SAVE", false))
        add(Btn(keyLoad, "LOAD", false))
    }

    private var dpadX = 0f
    private var dpadY = 0f
    private var dpadR = 0f

    private var pressed = emptySet<Int>()
    private var dir = 0 to 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val fillOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(150, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val landscape = w > h
        fun place(key: Int, cx: Float, cy: Float, bw: Float, bh: Float) {
            buttons.firstOrNull { it.key == key }?.rect?.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        }
        if (landscape) {
            dpadX = w * 0.13f; dpadY = h * 0.62f; dpadR = h * 0.18f
            val r = h * 0.085f
            place(KeyEvent.KEYCODE_BUTTON_A, w * 0.92f, h * 0.55f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_B, w * 0.81f, h * 0.70f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_SELECT, w * 0.40f, h * 0.93f, h * 0.2f, h * 0.08f)
            place(KeyEvent.KEYCODE_BUTTON_START, w * 0.60f, h * 0.93f, h * 0.2f, h * 0.08f)
            place(KeyEvent.KEYCODE_BUTTON_L1, w * 0.09f, h * 0.12f, h * 0.28f, h * 0.11f)
            place(KeyEvent.KEYCODE_BUTTON_R1, w * 0.91f, h * 0.12f, h * 0.28f, h * 0.11f)
            place(keySave, w * 0.42f, h * 0.06f, h * 0.16f, h * 0.07f)
            place(keyLoad, w * 0.58f, h * 0.06f, h * 0.16f, h * 0.07f)
        } else {
            dpadX = w * 0.24f; dpadY = h * 0.74f; dpadR = w * 0.18f
            val r = w * 0.09f
            place(KeyEvent.KEYCODE_BUTTON_A, w * 0.85f, h * 0.70f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_B, w * 0.65f, h * 0.77f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_SELECT, w * 0.38f, h * 0.91f, w * 0.2f, w * 0.08f)
            place(KeyEvent.KEYCODE_BUTTON_START, w * 0.62f, h * 0.91f, w * 0.2f, w * 0.08f)
            place(KeyEvent.KEYCODE_BUTTON_L1, w * 0.12f, h * 0.57f, w * 0.2f, w * 0.09f)
            place(KeyEvent.KEYCODE_BUTTON_R1, w * 0.88f, h * 0.57f, w * 0.2f, w * 0.09f)
            place(keySave, w * 0.42f, h * 0.57f, w * 0.15f, w * 0.07f)
            place(keyLoad, w * 0.58f, h * 0.57f, w * 0.15f, w * 0.07f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // D-pad: a cross with the active arm highlighted.
        val arm = dpadR * 0.36f
        val (dx, dy) = dir
        fun armRect(ax: Int, ay: Int) = RectF(
            dpadX + ax * dpadR * 0.5f - (if (ax == 0) arm else dpadR * 0.5f),
            dpadY + ay * dpadR * 0.5f - (if (ay == 0) arm else dpadR * 0.5f),
            dpadX + ax * dpadR * 0.5f + (if (ax == 0) arm else dpadR * 0.5f),
            dpadY + ay * dpadR * 0.5f + (if (ay == 0) arm else dpadR * 0.5f),
        )
        listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0).forEach { (ax, ay) ->
            val on = (ax != 0 && ax == dx) || (ay != 0 && ay == dy)
            val r = armRect(ax, ay)
            canvas.drawRoundRect(r, arm * 0.3f, arm * 0.3f, if (on) fillOn else fill)
            canvas.drawRoundRect(r, arm * 0.3f, arm * 0.3f, stroke)
        }

        buttons.forEach { b ->
            val on = b.key in pressed
            val p = if (on) fillOn else fill
            if (b.round) {
                canvas.drawOval(b.rect, p); canvas.drawOval(b.rect, stroke)
            } else {
                val rad = b.rect.height() / 2
                canvas.drawRoundRect(b.rect, rad, rad, p); canvas.drawRoundRect(b.rect, rad, rad, stroke)
            }
            text.textSize = b.rect.height() * if (b.round) 0.45f else 0.5f
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.ascent() + text.descent()) / 2, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = mutableSetOf<Int>()
        var nx = 0
        var ny = 0
        val action = e.actionMasked
        if (action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                val lifting = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
                    i == e.actionIndex
                if (lifting) continue
                val x = e.getX(i)
                val y = e.getY(i)
                val dist = hypot(x - dpadX, y - dpadY)
                if (dist < dpadR * 1.5f) {
                    if (dist > dpadR * 0.15f) {
                        // 8-way: split the circle into 45° sectors.
                        val sector = (atan2(y - dpadY, x - dpadX) / (Math.PI / 4)).roundToInt()
                        val d = SECTORS[(sector + 8) % 8]
                        nx = d.first; ny = d.second
                    }
                } else {
                    buttons.firstOrNull { it.hit(x, y) }?.let { now += it.key }
                }
            }
        }

        if (nx to ny != dir) {
            dir = nx to ny
            listener.onDpad(nx, ny)
            if (nx != 0 || ny != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        (now - pressed).forEach { key ->
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (key) {
                keySave -> listener.onQuickSave()
                keyLoad -> listener.onQuickLoad()
                else -> listener.onButton(key, true)
            }
        }
        (pressed - now).filter { it >= 0 }.forEach { listener.onButton(it, false) }
        invalidate()
        pressed = now
        return true
    }

    companion object {
        // index = sector (0 = right, going clockwise because screen Y points down)
        private val SECTORS = arrayOf(
            1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1,
        )
    }
}
