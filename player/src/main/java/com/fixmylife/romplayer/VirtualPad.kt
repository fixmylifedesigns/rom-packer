package com.fixmylife.romplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Transparent control overlay: D-pad, face buttons, Start/Select, L/R on GBA,
 * and one hamburger button that opens the menu. Everything else lives in there.
 *
 * The D-pad is one filled plus shape (two rounded bars unioned into a single
 * path) rather than four overlapping rectangles, so the arms don't stack their
 * translucency into bright squares at the centre.
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
        fun onMenu()
    }

    private class Btn(val key: Int, val label: String, val round: Boolean) {
        val rect = RectF()
        fun hit(x: Float, y: Float): Boolean {
            val padX = rect.width() * 0.18f
            val padY = rect.height() * 0.25f
            return x >= rect.left - padX && x <= rect.right + padX &&
                y >= rect.top - padY && y <= rect.bottom + padY
        }
    }

    private val buttons = buildList {
        add(Btn(KeyEvent.KEYCODE_BUTTON_A, "A", true))
        add(Btn(KeyEvent.KEYCODE_BUTTON_B, "B", true))
        add(Btn(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", false))
        add(Btn(KeyEvent.KEYCODE_BUTTON_START, "START", false))
        if (showShoulders) {
            add(Btn(KeyEvent.KEYCODE_BUTTON_L1, "L", false))
            add(Btn(KeyEvent.KEYCODE_BUTTON_R1, "R", false))
        }
        add(Btn(KEY_MENU, "", false))
    }

    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadR = 0f
    private val dpadPath = Path()

    private var pressed = emptySet<Int>()
    private var dir = 0 to 0

    // Frosted-glass look from the redesign: a soft translucent fill, a hairline
    // edge, and a violet wash on whatever is being pressed.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 240, 238, 250) }
    private val fillOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 139, 92, 246) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(70, 255, 255, 255)
    }
    private val pivot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }

    // A wide, faint second stroke fakes the soft outer glow in the concept.
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.argb(40, 190, 165, 255)
    }
    private val glowOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.argb(110, 167, 129, 255)
    }
    private val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(225, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val landscape = w > h
        fun place(key: Int, cx: Float, cy: Float, bw: Float, bh: Float) {
            buttons.firstOrNull { it.key == key }?.rect?.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        }

        if (landscape) {
            dpadCx = w * 0.12f; dpadCy = h * 0.66f; dpadR = h * 0.21f
            val r = h * 0.09f
            // Face buttons sit on a diagonal, like the real hardware.
            place(KeyEvent.KEYCODE_BUTTON_A, w * 0.92f, h * 0.58f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_B, w * 0.80f, h * 0.74f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_SELECT, w * 0.42f, h * 0.92f, h * 0.20f, h * 0.085f)
            place(KeyEvent.KEYCODE_BUTTON_START, w * 0.58f, h * 0.92f, h * 0.20f, h * 0.085f)
            place(KeyEvent.KEYCODE_BUTTON_L1, w * 0.09f, h * 0.10f, h * 0.24f, h * 0.10f)
            place(KeyEvent.KEYCODE_BUTTON_R1, w * 0.91f, h * 0.10f, h * 0.24f, h * 0.10f)
            place(KEY_MENU, w * 0.50f, h * 0.08f, h * 0.11f, h * 0.085f)
        } else {
            dpadCx = w * 0.25f; dpadCy = h * 0.745f; dpadR = w * 0.20f
            val r = w * 0.095f
            place(KeyEvent.KEYCODE_BUTTON_A, w * 0.855f, h * 0.705f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_B, w * 0.655f, h * 0.775f, r * 2, r * 2)
            place(KeyEvent.KEYCODE_BUTTON_SELECT, w * 0.35f, h * 0.905f, w * 0.24f, w * 0.095f)
            place(KeyEvent.KEYCODE_BUTTON_START, w * 0.65f, h * 0.905f, w * 0.24f, w * 0.095f)
            place(KeyEvent.KEYCODE_BUTTON_L1, w * 0.14f, h * 0.575f, w * 0.20f, w * 0.09f)
            place(KeyEvent.KEYCODE_BUTTON_R1, w * 0.86f, h * 0.575f, w * 0.20f, w * 0.09f)
            // Centred between L and R, below the screen rather than on top of it.
            place(KEY_MENU, w * 0.50f, h * 0.575f, w * 0.14f, w * 0.105f)
        }
        buildDpadPath()
    }

    /** One plus shape: a horizontal bar unioned with a vertical bar. */
    private fun buildDpadPath() {
        val arm = dpadR * 0.36f
        val corner = arm * 0.32f
        val horizontal = RectF(dpadCx - dpadR, dpadCy - arm, dpadCx + dpadR, dpadCy + arm)
        val vertical = RectF(dpadCx - arm, dpadCy - dpadR, dpadCx + arm, dpadCy + dpadR)
        dpadPath.reset()
        dpadPath.addRoundRect(horizontal, corner, corner, Path.Direction.CW)
        val v = Path().apply { addRoundRect(vertical, corner, corner, Path.Direction.CW) }
        dpadPath.op(v, Path.Op.UNION)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(dpadPath, if (dir == 0 to 0) glow else glowOn)
        canvas.drawPath(dpadPath, fill)

        val (dx, dy) = dir
        if (dx != 0 || dy != 0) {
            canvas.save()
            canvas.clipPath(dpadPath)
            val arm = dpadR * 0.36f
            if (dx != 0) {
                val left = if (dx > 0) dpadCx + arm * 0.1f else dpadCx - dpadR
                val right = if (dx > 0) dpadCx + dpadR else dpadCx - arm * 0.1f
                canvas.drawRect(left, dpadCy - arm, right, dpadCy + arm, fillOn)
            }
            if (dy != 0) {
                val top = if (dy > 0) dpadCy + arm * 0.1f else dpadCy - dpadR
                val bottom = if (dy > 0) dpadCy + dpadR else dpadCy - arm * 0.1f
                canvas.drawRect(dpadCx - arm, top, dpadCx + arm, bottom, fillOn)
            }
            canvas.restore()
        }
        canvas.drawPath(dpadPath, stroke)
        canvas.drawCircle(dpadCx, dpadCy, dpadR * 0.11f, pivot)

        buttons.forEach { b ->
            val on = b.key in pressed
            val paint = if (on) fillOn else fill
            if (b.round) {
                canvas.drawOval(b.rect, if (on) glowOn else glow)
                canvas.drawOval(b.rect, paint)
                canvas.drawOval(b.rect, stroke)
            } else {
                val radius = b.rect.height() / 2
                canvas.drawRoundRect(b.rect, radius, radius, if (on) glowOn else glow)
                canvas.drawRoundRect(b.rect, radius, radius, paint)
                canvas.drawRoundRect(b.rect, radius, radius, stroke)
            }
            if (b.key == KEY_MENU) {
                drawHamburger(canvas, b.rect)
            } else {
                text.textSize = if (b.round) b.rect.height() * 0.44f else b.rect.height() * 0.46f
                canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }

    /** Three bars, centred in the button. */
    private fun drawHamburger(canvas: Canvas, rect: RectF) {
        val width = rect.width() * 0.44f
        val gap = rect.height() * 0.22f
        bars.strokeWidth = rect.height() * 0.09f
        val cx = rect.centerX()
        val cy = rect.centerY()
        for (i in -1..1) {
            val y = cy + i * gap
            canvas.drawLine(cx - width / 2, y, cx + width / 2, y, bars)
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

                if (inDpadArea(x, y)) {
                    val ox = x - dpadCx
                    val oy = y - dpadCy
                    if (hypot(ox, oy) > dpadR * 0.18f) {
                        // A direction counts when its axis carries at least ~40% of
                        // the other, which gives generous diagonals without jitter.
                        if (abs(ox) > abs(oy) * 0.4f) nx = if (ox > 0) 1 else -1
                        if (abs(oy) > abs(ox) * 0.4f) ny = if (oy > 0) 1 else -1
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
                KEY_MENU -> listener.onMenu()
                else -> listener.onButton(key, true)
            }
        }
        (pressed - now).filter { it >= 0 }.forEach { listener.onButton(it, false) }
        pressed = now
        invalidate()
        return true
    }

    /** Square catch area around the cross, so thumbs near the tips still register. */
    private fun inDpadArea(x: Float, y: Float): Boolean {
        val reach = dpadR * 1.25f
        return x > dpadCx - reach && x < dpadCx + reach && y > dpadCy - reach && y < dpadCy + reach
    }

    companion object {
        private const val KEY_MENU = -1
    }
}
