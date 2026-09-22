import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Draws the launcher PNGs (legacy + adaptive foreground/background) at build time,
 * so the repo stays text-only. The player's copies are placeholders that
 * ROM Packer overwrites per game.
 */
abstract class GenerateLauncherIcons : DefaultTask() {
    @get:Input abstract val background: Property<Int>
    @get:Input abstract val body: Property<Int>
    @get:Input abstract val accent: Property<Int>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        LauncherIcons.write(outputDir.get().asFile, background.get(), body.get(), accent.get())
    }
}

object LauncherIcons {
    private val densities = mapOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)

    fun write(root: File, bg: Int, body: Int, accent: Int) {
        densities.forEach { (name, scale) ->
            val dir = File(root, "mipmap-$name").apply { mkdirs() }
            val legacy = (48 * scale).roundToInt()
            val adaptive = (108 * scale).roundToInt()
            save(cartridge(legacy, bg, body, accent, fillBackground = true, inset = 0f), File(dir, "ic_launcher.png"))
            save(cartridge(adaptive, bg, body, accent, fillBackground = false, inset = 18f / 108f), File(dir, "ic_launcher_foreground.png"))
            save(solid(adaptive, bg), File(dir, "ic_launcher_background.png"))
        }
    }

    private fun save(img: BufferedImage, file: File) {
        check(ImageIO.write(img, "png", file)) { "PNG writer unavailable" }
    }

    private fun solid(size: Int, color: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(color, true)
        g.fillRect(0, 0, size, size)
        g.dispose()
        return img
    }

    private fun cartridge(size: Int, bg: Int, body: Int, accent: Int, fillBackground: Boolean, inset: Float): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val s = size.toFloat()
        if (fillBackground) {
            g.color = Color(bg, true)
            g.fill(RoundRectangle2D.Float(0f, 0f, s, s, s * 0.36f, s * 0.36f))
        }
        val o = s * inset
        val w = s - 2 * o
        val x0 = o + w * 0.26f
        val y0 = o + w * 0.18f
        val x1 = o + w * 0.74f
        val y1 = o + w * 0.84f
        g.color = Color(body, true)
        g.fill(RoundRectangle2D.Float(x0, y0, x1 - x0, y1 - y0, w * 0.1f, w * 0.1f))
        g.color = Color(accent, true)
        g.fill(Rectangle2D.Float(x0 + w * 0.06f, y0 + w * 0.1f, (x1 - x0) - w * 0.12f, w * 0.28f))
        for (i in 0 until 5) {
            val xx = x0 + w * 0.07f + i * w * 0.075f
            g.fill(Rectangle2D.Float(xx, y1 - w * 0.08f, w * 0.04f, w * 0.06f))
        }
        g.dispose()
        return img
    }
}
