package com.c0mpile.grimmreader.spike.panels.bench

import com.c0mpile.grimmreader.spike.panels.GutterDetector
import com.c0mpile.grimmreader.spike.panels.Raster
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * usage: bench <pages dir> <out dir> [rtl-pages comma list]
 * Writes `detections.json` (normalised boxes in reading order) and `overlay/<page>.png`.
 */
fun main(args: Array<String>) {
    val pages = File(args[0]).listFiles { f -> f.name.endsWith(".img") }!!.sortedBy { it.name }
    val out = File(args[1]).apply { mkdirs() }
    val overlays = File(out, "overlay").apply { mkdirs() }
    val rtl = args.getOrNull(2)?.split(',')?.toSet().orEmpty()
    val json = StringBuilder("[\n")
    pages.forEachIndexed { i, f ->
        val id = f.name.removeSuffix(".img")
        val img = ImageIO.read(f)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val raster = Raster(img.width, img.height, px)
        repeat(3) { GutterDetector.detect(raster, id in rtl) } // warm-up
        val t0 = System.nanoTime()
        val d = GutterDetector.detect(raster, id in rtl)
        val ms = (System.nanoTime() - t0) / 1e6
        val r = d.raster
        val boxes = d.panels.joinToString(",") { b ->
            "[%.4f,%.4f,%.4f,%.4f]".format(b.x0.toFloat() / r.w, b.y0.toFloat() / r.h, b.x1.toFloat() / r.w, b.y1.toFloat() / r.h)
        }
        json.append("""  {"page":"$id","w":${r.w},"h":${r.h},"fallback":${d.fallback},"ms":${"%.1f".format(ms)},"gutter":"#%06X","panels":[$boxes]}""".format(d.gutterColor and 0xFFFFFF))
        json.append(if (i < pages.size - 1) ",\n" else "\n")
        overlay(r, d.panels, File(overlays, "$id.png"))
    }
    json.append("]\n")
    File(out, "detections.json").writeText(json.toString())
}

private fun overlay(r: Raster, boxes: List<com.c0mpile.grimmreader.spike.panels.Box>, file: File) {
    val img = BufferedImage(r.w, r.h, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, r.w, r.h, r.px, 0, r.w)
    val g = img.createGraphics()
    g.color = Color(0, 0, 255, 110)
    g.stroke = BasicStroke(1f)
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    for (i in 1..9) {
        val x = r.w * i / 10
        val y = r.h * i / 10
        g.drawLine(x, 0, x, r.h)
        g.drawLine(0, y, r.w, y)
        g.drawString("$i", x + 2, 12)
        g.drawString("$i", 2, y - 2)
    }
    g.stroke = BasicStroke(4f)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
    boxes.forEachIndexed { i, b ->
        g.color = Color(255, 0, 255)
        g.drawRect(b.x0 + 2, b.y0 + 2, b.w - 4, b.h - 4)
        g.color = Color.YELLOW
        g.fillRect(b.x0 + 6, b.y0 + 6, 30, 32)
        g.color = Color.BLACK
        g.drawString("${i + 1}", b.x0 + 10, b.y0 + 33)
    }
    g.dispose()
    ImageIO.write(img, "png", file)
}
