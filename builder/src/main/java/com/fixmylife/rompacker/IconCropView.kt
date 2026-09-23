package com.fixmylife.rompacker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Square crop frame: the whole view is the crop. Drag to pan, pinch to zoom,
 * and the brightness / contrast / saturation values are applied live so the
 * exported icon looks exactly like the preview.
 */
class IconCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var source: Bitmap? = null

    // Named xform, not matrix: View already has getMatrix() and the JVM signatures would clash.
    private val xform = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(160, 255, 255, 255)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(60, 255, 255, 255)
    }

    var brightness = 0f
        set(value) { field = value; applyFilter() }
    var contrast = 1f
        set(value) { field = value; applyFilter() }
    var saturation = 1f
        set(value) { field = value; applyFilter() }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = currentScale() * detector.scaleFactor
            val f = target.coerceIn(minScale(), minScale() * 8f) / currentScale()
            xform.postScale(f, f, detector.focusX, detector.focusY)
            constrain()
            invalidate()
            return true
        }
    })

    private val panDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            xform.postTranslate(-dx, -dy)
            constrain()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            reset()
            return true
        }
    })

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
            .takeIf { it > 0 } ?: MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = reset()

    fun setBitmap(bitmap: Bitmap?) {
        source = bitmap
        reset()
    }

    fun rotate() {
        source = source?.let { src ->
            val m = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
        reset()
    }

    fun resetAdjustments() {
        brightness = 0f
        contrast = 1f
        saturation = 1f
    }

    /** Renders the cropped square at the requested pixel size. */
    fun export(size: Int): Bitmap? {
        val src = source ?: return null
        if (width == 0) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = size / width.toFloat()
        val m = Matrix(xform).apply { postScale(scale, scale) }
        canvas.drawBitmap(src, m, paint)
        return out
    }

    private fun reset() {
        val src = source ?: return
        if (width == 0) return
        xform.reset()
        val s = minScale()
        xform.postScale(s, s)
        xform.postTranslate((width - src.width * s) / 2f, (height - src.height * s) / 2f)
        invalidate()
    }

    /** Smallest scale that still covers the square frame. */
    private fun minScale(): Float {
        val src = source ?: return 1f
        return max(width.toFloat() / src.width, height.toFloat() / src.height)
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        xform.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    /** Keeps the image covering the frame so no empty edges appear. */
    private fun constrain() {
        val src = source ?: return
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        xform.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() >= width) {
            if (r.left > 0) dx = -r.left
            if (r.right < width) dx = width - r.right
        } else {
            dx = (width - r.width()) / 2f - r.left
        }
        if (r.height() >= height) {
            if (r.top > 0) dy = -r.top
            if (r.bottom < height) dy = height - r.bottom
        } else {
            dy = (height - r.height()) / 2f - r.top
        }
        xform.postTranslate(dx, dy)
    }

    private fun applyFilter() {
        val cm = ColorMatrix()
        cm.setSaturation(saturation)
        val c = contrast
        val offset = (1f - c) * 127.5f + brightness * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, offset,
                    0f, c, 0f, 0f, offset,
                    0f, 0f, c, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val src = source
        if (src == null) {
            canvas.drawColor(Color.argb(30, 128, 128, 128))
        } else {
            canvas.drawBitmap(src, xform, paint)
        }
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, frame)
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, grid)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, grid)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (source == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) panDetector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
