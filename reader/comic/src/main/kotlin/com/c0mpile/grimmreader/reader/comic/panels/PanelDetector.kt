package com.c0mpile.grimmreader.reader.comic.panels

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ARGB pixels, row-major. */
class Raster(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    /** Area-average downscale so that the longer side is at most [maxSide]. */
    fun downscaled(maxSide: Int): Raster {
        val f = max(width, height).toFloat() / maxSide
        if (f <= 1f) return this
        val nw = (width / f).roundToInt()
        val nh = (height / f).roundToInt()
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy0 = y * height / nh
            val sy1 = max(sy0 + 1, (y + 1) * height / nh)
            for (x in 0 until nw) {
                val sx0 = x * width / nw
                out[y * nw + x] = average(sx0, max(sx0 + 1, (x + 1) * width / nw), sy0, sy1)
            }
        }
        return Raster(nw, nh, out)
    }

    private fun average(
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
    ): Int {
        var r = 0
        var g = 0
        var b = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = pixels[y * width + x]
                r += red(p)
                g += green(p)
                b += blue(p)
            }
        }
        val n = (y1 - y0) * (x1 - x0)
        return argb(r / n, g / n, b / n)
    }
}

/** Axis-aligned box in raster pixels, end-exclusive. */
internal data class Box(
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    val area get() = w.toLong() * h
}

/**
 * Clean-room panel detector for guided view (Spike d, `docs/spikes/panels.md`): estimate the gutter colour
 * from the page border, then cut the page recursively along full-length gutter rows and columns (XY-cut).
 * Inside each region the gutter colour is estimated again (nested margins, coloured gutters), and black and
 * white are tried as well (thin border lines), but only for thin cuts that leave large parts. A region no
 * straight gutter splits is tried once more as connected components of non-gutter pixels (gutters that are
 * not straight or aligned). Panels come in reading order: top to bottom, left to right.
 *
 * When unsure (one panel, or panels covering under half the page) the result is the whole page without its
 * margins, so guided view never skips content it could not find.
 */
object PanelDetector {
    /** Bump when results change, so cached panels are detected again. */
    const val VERSION = 1

    fun detect(input: Raster): List<Rect> {
        val r = input.downscaled(MAX_SIDE)
        val page = Box(0, 0, r.width, r.height)
        val masks = MaskCache(r)
        val pageMask = masks.forColor(gutterColor(r, page).color)
        val trimmed = pageMask.trim(page)
        if (trimmed.w <= 2 || trimmed.h <= 2) return listOf(FULL_PAGE)
        val leaves = ArrayList<Box>()
        Cutter(r, masks, leaves).cut(trimmed, pageMask)
        val panels = leaves.filter { r.isPanelSized(it) }
        val coverage = panels.sumOf { it.area }.toFloat() / max(1L, trimmed.area)
        val boxes = if (panels.size <= 1 || coverage < MIN_COVERAGE) listOf(trimmed) else panels
        return boxes.map { b ->
            Rect(b.x0.toFloat() / r.width, b.y0.toFloat() / r.height, b.x1.toFloat() / r.width, b.y1.toFloat() / r.height)
        }
    }

    val FULL_PAGE = Rect(0f, 0f, 1f, 1f)
}

private class Cutter(
    private val r: Raster,
    private val masks: MaskCache,
    private val out: MutableList<Box>,
) {
    private val minGapY = max(2, (MIN_GAP * r.height).roundToInt())
    private val minGapX = max(2, (MIN_GAP * r.width).roundToInt())

    fun cut(
        region: Box,
        mask: Mask,
    ) {
        val t = mask.trim(region)
        if (t.w <= 2 || t.h <= 2) return
        val local = gutterColor(r, t)
        val own = linkedSetOf(mask)
        if (local.share >= LOCAL_SHARE) own += masks.forColor(local.color)
        val guarded = listOf(masks.forColor(BLACK), masks.forColor(WHITE)).filter { it !in own }
        val split =
            own.firstNotNullOfOrNull { split(t, it, strict = false) }
                ?: guarded.firstNotNullOfOrNull { split(t, it, strict = true) }
        if (split != null) {
            split.second.forEach { cut(it, split.first) }
        } else {
            out += components(t, mask) ?: listOf(t)
        }
    }

    /** Rows first, then columns; [strict] cuts (a colour that is not the region's own gutter) must be [plausible]. */
    private fun split(
        t: Box,
        m: Mask,
        strict: Boolean,
    ): Pair<Mask, List<Box>>? {
        val inner = m.trim(t)
        if (inner.w <= 2 || inner.h <= 2) return null
        for (horizontal in listOf(true, false)) {
            val parts = m.split(inner, horizontal, if (horizontal) minGapY else minGapX)
            if (parts.size > 1 && (!strict || plausible(parts, inner, horizontal))) return m to parts
        }
        return null
    }

    private fun plausible(
        parts: List<Box>,
        b: Box,
        horizontal: Boolean,
    ): Boolean {
        val extent = if (horizontal) b.h else b.w
        if (parts.any { (if (horizontal) it.h else it.w) < MIN_STRICT_PART * extent }) return false
        return parts.zipWithNext().all { (a, c) -> (if (horizontal) c.y0 - a.y1 else c.x0 - a.x1) <= MAX_STRICT_GAP * extent }
    }

    /** Bounding boxes of the non-gutter regions in [b], if they look like separate panels. */
    private fun components(
        b: Box,
        m: Mask,
    ): List<Box>? {
        if (b.area < 2 * MIN_PANEL_AREA * r.width * r.height) return null
        val boxes = Components(b, m).boxes().filter { r.isPanelSized(it) }
        if (boxes.size < 2 || boxes.sumOf { it.area } < COMPONENT_COVERAGE * b.area) return null
        val overlapping =
            boxes.indices.any { i ->
                (i + 1 until boxes.size).any { j -> overlap(boxes[i], boxes[j]) > MAX_OVERLAP * min(boxes[i].area, boxes[j].area) }
            }
        return if (overlapping) null else readingOrder(boxes)
    }
}

/** 4-connected regions of non-gutter pixels inside a box, by flood fill. */
private class Components(
    private val b: Box,
    private val m: Mask,
) {
    private val seen = BooleanArray(b.w * b.h)
    private val stack = IntArray(b.w * b.h)

    fun boxes(): List<Box> {
        val out = ArrayList<Box>()
        for (start in seen.indices) {
            if (!seen[start] && ink(start)) out += fill(start)
        }
        return out
    }

    private fun ink(i: Int) = m.isInk(b.x0 + i % b.w, b.y0 + i / b.w)

    private fun fill(start: Int): Box {
        var sp = push(start, 0)
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % b.w
            val y = i / b.w
            x0 = min(x0, x)
            x1 = max(x1, x)
            y0 = min(y0, y)
            y1 = max(y1, y)
            if (x > 0) sp = push(i - 1, sp)
            if (x < b.w - 1) sp = push(i + 1, sp)
            if (y > 0) sp = push(i - b.w, sp)
            if (y < b.h - 1) sp = push(i + b.w, sp)
        }
        return Box(b.x0 + x0, b.y0 + y0, b.x0 + x1 + 1, b.y0 + y1 + 1)
    }

    private fun push(
        i: Int,
        sp: Int,
    ): Int {
        if (seen[i] || !ink(i)) return sp
        seen[i] = true
        stack[sp] = i
        return sp + 1
    }
}

/** Row bands (a box joins a band when it overlaps half its height), left to right within a band. */
internal fun readingOrder(boxes: List<Box>): List<Box> {
    val bands = ArrayList<MutableList<Box>>()
    for (box in boxes.sortedBy { it.y0 }) {
        val band = bands.lastOrNull()
        if (band != null && min(band.maxOf { it.y1 }, box.y1) - max(band.minOf { it.y0 }, box.y0) >= box.h / 2) {
            band += box
        } else {
            bands += mutableListOf(box)
        }
    }
    return bands.flatMap { band -> band.sortedBy { it.x0 } }
}

private fun overlap(
    a: Box,
    c: Box,
): Long {
    val w = min(a.x1, c.x1) - max(a.x0, c.x0)
    val h = min(a.y1, c.y1) - max(a.y0, c.y0)
    return if (w > 0 && h > 0) w.toLong() * h else 0L
}

private fun Raster.isPanelSized(b: Box) = b.area >= MIN_PANEL_AREA * width * height && b.w >= MIN_SIDE * width && b.h >= MIN_SIDE * height

private class GutterColor(
    val color: Int,
    val share: Float,
)

/** Most common colour (4 bits per channel) in a thin band inside [b]'s edges, averaged within its bin. */
private fun gutterColor(
    r: Raster,
    b: Box,
): GutterColor {
    val band = max(2, (BORDER_BAND * min(b.w, b.h)).roundToInt())
    val counts = IntArray(BINS)
    val sums = LongArray(BINS * CHANNELS)
    for (y in b.y0 until b.y1) {
        val inBandY = y < b.y0 + band || y >= b.y1 - band
        for (x in b.x0 until b.x1) {
            if (!inBandY && x >= b.x0 + band && x < b.x1 - band) continue
            val p = r.pixels[y * r.width + x]
            val bin = bin(p)
            counts[bin]++
            sums[bin * CHANNELS] += red(p).toLong()
            sums[bin * CHANNELS + 1] += green(p).toLong()
            sums[bin * CHANNELS + 2] += blue(p).toLong()
        }
    }
    val best = counts.indices.maxBy { counts[it] }
    val n = counts[best].toLong()
    val at = best * CHANNELS
    val color = argb((sums[at] / n).toInt(), (sums[at + 1] / n).toInt(), (sums[at + 2] / n).toInt())
    return GutterColor(color, n.toFloat() / counts.sum())
}

/** One mask per (coarse) gutter colour, built on first use. */
private class MaskCache(
    private val r: Raster,
) {
    private val masks = HashMap<Int, Mask>()

    fun forColor(color: Int): Mask = masks.getOrPut(color and COARSE) { Mask(r, color) }
}

/** Non-gutter ("ink") pixels, with per-row and per-column prefix sums for O(1) line counts. */
private class Mask(
    r: Raster,
    gutter: Int,
) {
    private val w = r.width
    private val h = r.height
    private val ink = BooleanArray(w * h)
    private val rowPrefix = IntArray((w + 1) * h)
    private val colPrefix = IntArray((h + 1) * w)

    init {
        for (y in 0 until h) {
            var acc = 0
            for (x in 0 until w) {
                val isInk = !near(r.pixels[y * w + x], gutter)
                if (isInk) acc++
                ink[y * w + x] = isInk
                rowPrefix[y * (w + 1) + x + 1] = acc
                colPrefix[x * (h + 1) + y + 1] = colPrefix[x * (h + 1) + y] + if (isInk) 1 else 0
            }
        }
    }

    fun isInk(
        x: Int,
        y: Int,
    ) = ink[y * w + x]

    private fun rowIsGutter(
        y: Int,
        b: Box,
    ) = rowPrefix[y * (w + 1) + b.x1] - rowPrefix[y * (w + 1) + b.x0] <= LINE_TOLERANCE * b.w

    private fun colIsGutter(
        x: Int,
        b: Box,
    ) = colPrefix[x * (h + 1) + b.y1] - colPrefix[x * (h + 1) + b.y0] <= LINE_TOLERANCE * b.h

    private fun isGutter(
        i: Int,
        b: Box,
        horizontal: Boolean,
    ) = if (horizontal) rowIsGutter(i, b) else colIsGutter(i, b)

    /** Shrinks [b] past gutter lines on all four sides. */
    fun trim(b: Box): Box {
        var x0 = b.x0
        var y0 = b.y0
        var x1 = b.x1
        var y1 = b.y1
        while (y0 < y1 && rowIsGutter(y0, Box(x0, y0, x1, y1))) y0++
        while (y1 > y0 && rowIsGutter(y1 - 1, Box(x0, y0, x1, y1))) y1--
        while (x0 < x1 && colIsGutter(x0, Box(x0, y0, x1, y1))) x0++
        while (x1 > x0 && colIsGutter(x1 - 1, Box(x0, y0, x1, y1))) x1--
        return Box(x0, y0, x1, y1)
    }

    /** Splits [b] at every gutter run of at least [minGap] lines. */
    fun split(
        b: Box,
        horizontal: Boolean,
        minGap: Int,
    ): List<Box> {
        val start = if (horizontal) b.y0 else b.x0
        val end = if (horizontal) b.y1 else b.x1
        val part = { from: Int, to: Int -> if (horizontal) Box(b.x0, from, b.x1, to) else Box(from, b.y0, to, b.y1) }
        val parts = ArrayList<Box>()
        var segStart = start
        var i = start
        while (i < end) {
            var j = i
            while (j < end && isGutter(j, b, horizontal)) j++
            if (j == i) {
                i++
            } else {
                if (j - i >= minGap && i > segStart) {
                    parts += part(segStart, i)
                    segStart = j
                }
                i = j
            }
        }
        if (segStart < end) parts += part(segStart, end)
        return parts
    }
}

private fun near(
    p: Int,
    c: Int,
) = abs(red(p) - red(c)) <= COLOR_TOLERANCE && abs(green(p) - green(c)) <= COLOR_TOLERANCE && abs(blue(p) - blue(c)) <= COLOR_TOLERANCE

private fun red(p: Int) = (p shr RED_SHIFT) and CHANNEL

private fun green(p: Int) = (p shr GREEN_SHIFT) and CHANNEL

private fun blue(p: Int) = p and CHANNEL

private fun argb(
    r: Int,
    g: Int,
    b: Int,
) = OPAQUE or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b

/** Colour histogram bin: the top [BIN_BITS] bits of each channel. */
private fun bin(p: Int): Int {
    val drop = Byte.SIZE_BITS - BIN_BITS
    return ((red(p) shr drop) shl (2 * BIN_BITS)) or ((green(p) shr drop) shl BIN_BITS) or (blue(p) shr drop)
}

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 0xFF
private const val CHANNELS = 3
private const val OPAQUE = 0xFF shl 24
private const val BIN_BITS = 4
private const val BINS = 1 shl (CHANNELS * BIN_BITS)

/** Working resolution (longer side). */
private const val MAX_SIDE = 1280

/** Largest per-channel distance from the gutter colour. */
private const val COLOR_TOLERANCE = 40

/** Share of non-gutter pixels a gutter line may contain (specks, stray lines). */
private const val LINE_TOLERANCE = 0.03f

/** Shortest gutter run, as a share of the page's extent. */
private const val MIN_GAP = 0.004f
private const val MIN_PANEL_AREA = 0.012f
private const val MIN_SIDE = 0.08f

/** Below this panel coverage of the trimmed page the result is not trusted. */
private const val MIN_COVERAGE = 0.5f

/** A region's own border colour counts as a gutter colour when it fills this share of the border band. */
private const val LOCAL_SHARE = 0.4f
private const val MIN_STRICT_PART = 0.15f
private const val MAX_STRICT_GAP = 0.02f
private const val COMPONENT_COVERAGE = 0.6f
private const val MAX_OVERLAP = 0.15f
private const val BORDER_BAND = 0.015f
private const val COARSE = 0xF0F0F0
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
