package com.fixmylife.rompacker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.min

object IconMaker {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Loads a picked image, downsampled and center-cropped to a square. */
    fun loadSquare(resolver: ContentResolver, uri: Uri, maxSize: Int = 768): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val size = min(side, maxSize)
        return Bitmap.createScaledBitmap(square, size, size, true)
    }

    /** Fallback icon when the user doesn't pick an image. */
    fun placeholder(title: String, system: GameSystem): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val (top, bottom) = when (system) {
            GameSystem.GB -> 0xFF8BAC0F.toInt() to 0xFF306230.toInt()
            GameSystem.GBC -> 0xFF7B2FF7.toInt() to 0xFF2A0A6B.toInt()
            GameSystem.GBA -> 0xFF3F51B5.toInt() to 0xFF111A4D.toInt()
        }
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)

        val initials = title.split(Regex("[\\s\\-:]+")).filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.34f
        }
        c.drawText(initials, size / 2f, size * 0.56f, text)
        text.textSize = size * 0.09f
        text.alpha = 200
        c.drawText(system.ext.uppercase(), size / 2f, size * 0.74f, text)
        return bmp
    }

    fun render(src: Bitmap, layer: ApkRepacker.IconLayer, px: Int): ByteArray {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val full = Rect(0, 0, src.width, src.height)
        when (layer) {
            ApkRepacker.IconLayer.LEGACY -> {
                // Rounded square filling the icon.
                val r = RectF(0f, 0f, px.toFloat(), px.toFloat())
                val clip = Path().apply { addRoundRect(r, px * 0.18f, px * 0.18f, Path.Direction.CW) }
                c.clipPath(clip)
                c.drawBitmap(src, full, r, paint)
            }
            ApkRepacker.IconLayer.FOREGROUND -> {
                // Adaptive icons show roughly the middle 72dp of a 108dp canvas.
                val inset = px * (18f / 108f)
                c.drawBitmap(src, full, RectF(inset, inset, px - inset, px - inset), paint)
            }
            ApkRepacker.IconLayer.BACKGROUND -> c.drawColor(averageColor(src))
        }
        return ByteArrayOutputStream().use { bos ->
            out.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
    }

    private fun averageColor(src: Bitmap): Int {
        val one = Bitmap.createScaledBitmap(src, 1, 1, true).getPixel(0, 0)
        // Darken a touch so the artwork pops against it.
        val f = 0.8f
        return Color.rgb(
            (Color.red(one) * f).toInt(),
            (Color.green(one) * f).toInt(),
            (Color.blue(one) * f).toInt(),
        )
    }
}
