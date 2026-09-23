import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Writes the launcher PNGs (legacy + adaptive foreground/background) at build time,
 * so the repo stays text-only. The builder supplies real artwork as a base64 PNG;
 * the player falls back to a drawn placeholder that ROM Packer overwrites per game.
 */
abstract class GenerateLauncherIcons : DefaultTask() {
    @get:Input abstract val background: Property<Int>
    @get:Input @get:Optional abstract val backgroundEnd: Property<Int>
    @get:Input abstract val body: Property<Int>
    @get:Input abstract val accent: Property<Int>

    /** Optional artwork (base64-encoded PNG) to use instead of the drawn cartridge. */
    @get:InputFile @get:Optional abstract val sourceImage: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val artwork = sourceImage.orNull?.asFile?.let { file ->
            ImageIO.read(Base64.getDecoder().decode(file.readText().trim()).inputStream())
        }
        LauncherIcons.write(
            outputDir.get().asFile,
            background.get(),
            backgroundEnd.getOrElse(background.get()),
            body.get(),
            accent.get(),
            artwork,
        )
    }
}

object LauncherIcons {
    private val densities = mapOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)

    fun write(root: File, bg: Int, bgEnd: Int, body: Int, accent: Int, artwork: BufferedImage? = null) {
        // With artwork, the transparent-corner version becomes the adaptive foreground
        // and the gradient behind it matches the artwork's own field, so the tile's
        // rounded edge disappears under whatever mask the launcher applies.
        val cutout = artwork?.let(::dropFlatBackground)
        densities.forEach { (name, scale) ->
            val dir = File(root, "mipmap-$name").apply { mkdirs() }
            val legacy = (48 * scale).roundToInt()
            val adaptive = (108 * scale).roundToInt()
            if (artwork != null && cutout != null) {
                save(scaled(artwork, legacy), File(dir, "ic_launcher.png"))
                // The cartridge fills ~68% of the artwork, so 84/108 keeps it inside the
                // 72dp safe zone with only its corners grazing a strict circular mask.
                save(centred(cutout, adaptive, 84f / 108f), File(dir, "ic_launcher_foreground.png"))
            } else {
                save(cartridge(legacy, bg, bgEnd, body, accent, fillBackground = true, inset = 0f), File(dir, "ic_launcher.png"))
                save(cartridge(adaptive, bg, bgEnd, body, accent, fillBackground = false, inset = 18f / 108f), File(dir, "ic_launcher_foreground.png"))
            }
            save(gradient(adaptive, bg, bgEnd), File(dir, "ic_launcher_background.png"))
        }
    }

    /** Clears the dark artboard surrounding the artwork's rounded tile. */
    private fun dropFlatBackground(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val rgb = src.getRGB(x, y)
                val sum = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)
                out.setRGB(x, y, if (sum > 180) rgb or (0xFF shl 24) else 0)
            }
        }
        return out
    }

    private fun scaled(src: BufferedImage, size: Int): BufferedImage {
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, size, size, null)
        g.dispose()
        return out
    }

    private fun centred(src: BufferedImage, size: Int, fraction: Float): BufferedImage {
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        val inner = (size * fraction).roundToInt()
        val offset = (size - inner) / 2
        g.drawImage(src, offset, offset, inner, inner, null)
        g.dispose()
        return out
    }

    private fun save(img: BufferedImage, file: File) {
        check(ImageIO.write(img, "png", file)) { "PNG writer unavailable" }
    }

    /** Diagonal violet-to-indigo wash, sampled from the artwork so the two match. */
    private fun gradient(size: Int, from: Int, to: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(from, true), size.toFloat(), size.toFloat(), Color(to, true))
        g.fillRect(0, 0, size, size)
        g.dispose()
        return img
    }

    /**
     * Fallback artwork: a cartridge silhouette on the wash, with a pixel-grid label
     * panel and connector teeth. Used by the player's placeholder icons.
     */
    private fun cartridge(
        size: Int,
        bg: Int,
        bgEnd: Int,
        body: Int,
        accent: Int,
        fillBackground: Boolean,
        inset: Float,
    ): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val s = size.toFloat()
        if (fillBackground) {
            g.paint = GradientPaint(0f, 0f, Color(bg, true), s, s, Color(bgEnd, true))
            g.fill(RoundRectangle2D.Float(0f, 0f, s, s, s * 0.32f, s * 0.32f))
        }

        val o = s * inset
        val w = s - 2 * o
        val x0 = o + w * 0.28f
        val y0 = o + w * 0.16f
        val x1 = o + w * 0.72f
        val y1 = o + w * 0.84f
        val cw = x1 - x0

        g.color = Color(body, true)
        g.fill(RoundRectangle2D.Float(x0, y0, cw, y1 - y0, w * 0.09f, w * 0.09f))

        if (fillBackground) {
            g.paint = GradientPaint(0f, 0f, Color(bg, true), s, s, Color(bgEnd, true))
        } else {
            g.composite = AlphaComposite.Clear
        }
        val notch = cw * 0.26f
        val corner = Path2D.Float().apply {
            moveTo((x1 - notch).toDouble(), y0.toDouble())
            lineTo(x1.toDouble(), y0.toDouble())
            lineTo(x1.toDouble(), (y0 + notch).toDouble())
            closePath()
        }
        g.fill(corner)
        g.composite = AlphaComposite.SrcOver

        g.color = Color(accent, true)
        g.fill(
            RoundRectangle2D.Float(
                x0 + cw * 0.12f, y0 + cw * 0.14f,
                cw * 0.76f, cw * 0.62f,
                w * 0.04f, w * 0.04f,
            )
        )

        g.color = Color(body, true)
        val cell = cw * 0.1f
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                if ((row + col) % 2 != 0) continue
                g.fill(
                    Rectangle2D.Float(
                        x0 + cw * 0.17f + col * cell,
                        y0 + cw * 0.22f + row * cell,
                        cell * 0.62f, cell * 0.62f,
                    )
                )
            }
        }

        g.color = Color(accent, true)
        for (i in 0 until 5) {
            g.fill(
                Rectangle2D.Float(
                    x0 + cw * 0.14f + i * cw * 0.16f,
                    y1 - w * 0.055f,
                    cw * 0.09f, w * 0.045f,
                )
            )
        }
        g.dispose()
        return img
    }
}
