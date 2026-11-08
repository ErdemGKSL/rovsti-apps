import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Tiles the golden screenshots whose file name matches [include] (and not [exclude]) into one captioned PNG
 * (ORIGINAL; `javax.imageio` only). Each tile is box-filtered down to [tileWidth] pixels so the sheet stays small enough
 * to open and judge as a whole. Run: `./gradlew :catalog:contactSheet`.
 */
abstract class ContactSheetTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val screenshots: DirectoryProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:Input
    abstract val include: Property<String>

    @get:Input
    abstract val exclude: Property<String>

    @get:Input
    abstract val columns: Property<Int>

    @get:Input
    abstract val tileWidth: Property<Int>

    @TaskAction
    fun render() {
        System.setProperty("java.awt.headless", "true")
        val inc = Regex(include.get())
        val exc = Regex(exclude.get())
        val files =
            screenshots
                .get()
                .asFile
                .listFiles { f -> f.extension == "png" && inc.containsMatchIn(f.name) && !exc.containsMatchIn(f.name) }
                .orEmpty()
                .sortedWith(compareBy({ naturalKey(it.name) }, { it.name }))
        if (files.isEmpty()) {
            throw GradleException(
                "No screenshots match ${include.get()} in ${screenshots.get().asFile}",
            )
        }

        val cols = columns.get()
        val tw = tileWidth.get()
        val gutter = 12
        val caption = 22
        val first = ImageIO.read(files.first())
        val th = (tw.toDouble() * first.height / first.width).toInt()
        val rows = (files.size + cols - 1) / cols
        val cellW = tw + gutter
        val cellH = th + caption + gutter
        val sheet = BufferedImage(gutter + cols * cellW, gutter + rows * cellH, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = Color(0x1c, 0x1c, 0x1e)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        files.forEachIndexed { i, file ->
            val x = gutter + (i % cols) * cellW
            val y = gutter + (i / cols) * cellH
            g.drawImage(downscale(ImageIO.read(file), tw, th), x, y, null)
            g.color = Color(0xd0, 0xd0, 0xd4)
            val label = file.nameWithoutExtension.removeSuffix("-light")
            g.drawString(if (label.length > 30) label.take(29) + "~" else label, x, y + th + 14)
        }
        g.dispose()
        val out = output.get().asFile
        out.parentFile.mkdirs()
        ImageIO.write(sheet, "png", out)
        logger.lifecycle("contactSheet: ${files.size} tiles -> $out (${out.length() / 1024} KB)")
    }

    /** Halves with a box filter until close, then one bilinear step: keeps thin text legible. */
    private fun downscale(
        src: BufferedImage,
        w: Int,
        h: Int,
    ): BufferedImage {
        var img = src
        while (img.width / 2 >= w) {
            val next = BufferedImage(img.width / 2, img.height / 2, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until next.height) {
                for (x in 0 until next.width) {
                    var r = 0
                    var gr = 0
                    var b = 0
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            val p = img.getRGB(x * 2 + dx, y * 2 + dy)
                            r += (p shr 16) and 255
                            gr += (p shr 8) and 255
                            b += p and 255
                        }
                    }
                    next.setRGB(x, y, ((r / 4) shl 16) or ((gr / 4) shl 8) or (b / 4))
                }
            }
            img = next
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    /** "e1.10-menu" sorts after "e1.9-x": compare the story number numerically. */
    private fun naturalKey(name: String): Int =
        Regex("""^e1\.(\d+)""")
            .find(name)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 999
}
