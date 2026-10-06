package com.c0mpile.grimmreader.spike.panels

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Axis-aligned box in raster pixels, end-exclusive. */
data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    val area get() = w.toLong() * h
}

/** ARGB pixels, row-major. */
class Raster(val w: Int, val h: Int, val px: IntArray) {
    /** Area-average downscale so that the longer side is at most [maxSide]. */
    fun downscaled(maxSide: Int): Raster {
        val f = max(w, h).toFloat() / maxSide
        if (f <= 1f) return this
        val nw = (w / f).roundToInt()
        val nh = (h / f).roundToInt()
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy0 = y * h / nh
            val sy1 = max(sy0 + 1, (y + 1) * h / nh)
            for (x in 0 until nw) {
                val sx0 = x * w / nw
                val sx1 = max(sx0 + 1, (x + 1) * w / nw)
                var r = 0
                var g = 0
                var b = 0
                for (sy in sy0 until sy1) {
                    val row = sy * w
                    for (sx in sx0 until sx1) {
                        val p = px[row + sx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val n = (sy1 - sy0) * (sx1 - sx0)
                out[y * nw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return Raster(nw, nh, out)
    }
}

data class Params(
    /** Working resolution (longer side). */
    val maxSide: Int = 1280,
    /** Max per-channel distance from the gutter colour. */
    val colorTol: Int = 40,
    /** Share of non-gutter pixels a gutter line may contain (specks, stray lines). */
    val lineTolerance: Float = 0.03f,
    /** Minimum gutter thickness as a share of the region's extent. */
    val minGapFrac: Float = 0.004f,
    val minPanelAreaFrac: Float = 0.012f,
    val minSideFrac: Float = 0.08f,
    /** Below this panel coverage of the trimmed page the result is not trusted. */
    val minCoverage: Float = 0.5f,
)

data class Detection(val panels: List<Box>, val fallback: Boolean, val gutterColor: Int, val raster: Raster)

/**
 * Clean-room panel detector: estimate the gutter colour from the page border, then cut the page
 * recursively along full-length gutter rows/columns (XY-cut). Inside each region the gutter colour is
 * re-estimated (nested margins, coloured gutters) and black/white are tried as well (thin border
 * lines). A leaf that no straight gutter splits is tried once more as connected components of
 * non-gutter pixels (gutters that are not perfectly straight or aligned). Leaves are panels, in reading
 * order (top to bottom, then left to right, or right to left for [rtl]).
 */
object GutterDetector {
    const val VERSION = 3

    fun detect(input: Raster, rtl: Boolean = false, p: Params = Params()): Detection {
        val r = input.downscaled(p.maxSide)
        val bg = gutterColor(r, Box(0, 0, r.w, r.h)).first
        val masks = HashMap<Int, Mask>()
        val maskFor = { c: Int -> masks.getOrPut(c and 0xF0F0F0) { Mask(r, c, p.colorTol) } }
        val pageMask = maskFor(bg)
        val trimmedPage = pageMask.trim(Box(0, 0, r.w, r.h), p.lineTolerance)
        val leaves = ArrayList<Box>()
        Cutter(r, p, rtl, maskFor, leaves).cut(trimmedPage, pageMask)
        val minArea = p.minPanelAreaFrac * r.w * r.h
        val panels = leaves.filter { it.area >= minArea && it.w >= p.minSideFrac * r.w && it.h >= p.minSideFrac * r.h }
        val coverage = panels.sumOf { it.area }.toFloat() / max(1L, trimmedPage.area)
        return if (panels.size <= 1 || coverage < p.minCoverage) {
            Detection(listOf(trimmedPage), fallback = true, gutterColor = bg, raster = r)
        } else {
            Detection(panels, fallback = false, gutterColor = bg, raster = r)
        }
    }

    private class Cutter(
        val r: Raster,
        val p: Params,
        val rtl: Boolean,
        val maskFor: (Int) -> Mask,
        val out: MutableList<Box>,
    ) {
        private val minGapY = max(2, (p.minGapFrac * r.h).roundToInt())
        private val minGapX = max(2, (p.minGapFrac * r.w).roundToInt())

        fun cut(region: Box, mask: Mask) {
            val t = mask.trim(region, p.lineTolerance)
            if (t.w <= 2 || t.h <= 2) return
            val (local, share) = gutterColor(r, t)
            val candidates = linkedSetOf(mask)
            if (share >= 0.4f) candidates += maskFor(local)
            val guarded = listOf(maskFor(BLACK), maskFor(WHITE)).filter { it !in candidates }
            for (m in candidates + guarded) {
                val inner = m.trim(t, p.lineTolerance)
                if (inner.w <= 2 || inner.h <= 2) continue
                val strict = m in guarded
                val rows = m.split(inner, horizontal = true, p.lineTolerance, minGapY)
                if (rows.size > 1 && (!strict || plausible(rows, inner, horizontal = true))) {
                    rows.forEach { cut(it, m) }
                    return
                }
                val cols = m.split(inner, horizontal = false, p.lineTolerance, minGapX)
                if (cols.size > 1 && (!strict || plausible(cols, inner, horizontal = false))) {
                    (if (rtl) cols.reversed() else cols).forEach { cut(it, m) }
                    return
                }
            }
            out += components(t, mask) ?: listOf(t)
        }

        /** A cut along a colour that is not the region's own gutter must be thin and leave substantial parts. */
        private fun plausible(parts: List<Box>, b: Box, horizontal: Boolean): Boolean {
            val extent = if (horizontal) b.h else b.w
            if (parts.any { (if (horizontal) it.h else it.w) < 0.15f * extent }) return false
            return parts.zipWithNext().all { (a, c) -> (if (horizontal) c.y0 - a.y1 else c.x0 - a.x1) <= 0.02f * extent }
        }

        /** Bounding boxes of 4-connected non-gutter regions in [b], if they look like separate panels. */
        private fun components(b: Box, m: Mask): List<Box>? {
            val minArea = p.minPanelAreaFrac * r.w * r.h
            if (b.area < 2 * minArea) return null
            val bw = b.w
            val label = IntArray(b.w * b.h)
            val stack = IntArray(b.w * b.h)
            val boxes = ArrayList<Box>()
            var next = 0
            for (start in label.indices) {
                if (label[start] != 0 || !m.isInk(b.x0 + start % bw, b.y0 + start / bw)) continue
                next++
                var sp = 0
                stack[sp++] = start
                label[start] = next
                var x0 = Int.MAX_VALUE
                var y0 = Int.MAX_VALUE
                var x1 = -1
                var y1 = -1
                while (sp > 0) {
                    val i = stack[--sp]
                    val x = i % bw
                    val y = i / bw
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                    if (x > 0) sp = visit(i - 1, b, m, label, stack, sp, next)
                    if (x < bw - 1) sp = visit(i + 1, b, m, label, stack, sp, next)
                    if (y > 0) sp = visit(i - bw, b, m, label, stack, sp, next)
                    if (y < b.h - 1) sp = visit(i + bw, b, m, label, stack, sp, next)
                }
                val c = Box(b.x0 + x0, b.y0 + y0, b.x0 + x1 + 1, b.y0 + y1 + 1)
                if (c.area >= minArea && c.w >= p.minSideFrac * r.w && c.h >= p.minSideFrac * r.h) boxes += c
            }
            if (boxes.size < 2 || boxes.sumOf { it.area } < 0.6f * b.area) return null
            for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) {
                    if (overlap(boxes[i], boxes[j]) > 0.15f * minOf(boxes[i].area, boxes[j].area)) return null
                }
            }
            return readingOrder(boxes)
        }

        private fun visit(i: Int, b: Box, m: Mask, label: IntArray, stack: IntArray, sp: Int, id: Int): Int {
            if (label[i] != 0 || !m.isInk(b.x0 + i % b.w, b.y0 + i / b.w)) return sp
            label[i] = id
            stack[sp] = i
            return sp + 1
        }

        /** Row bands (a box joins a band when it overlaps half its height), then by x within a band. */
        private fun readingOrder(boxes: List<Box>): List<Box> {
            val bands = ArrayList<MutableList<Box>>()
            for (bx in boxes.sortedBy { it.y0 }) {
                val band = bands.lastOrNull()
                val top = band?.minOf { it.y0 } ?: 0
                val bottom = band?.maxOf { it.y1 } ?: 0
                if (band != null && minOf(bottom, bx.y1) - maxOf(top, bx.y0) >= bx.h / 2) band += bx else bands += mutableListOf(bx)
            }
            return bands.flatMap { band -> if (rtl) band.sortedByDescending { it.x1 } else band.sortedBy { it.x0 } }
        }

        private fun overlap(a: Box, c: Box): Long {
            val w = minOf(a.x1, c.x1) - maxOf(a.x0, c.x0)
            val h = minOf(a.y1, c.y1) - maxOf(a.y0, c.y0)
            return if (w > 0 && h > 0) w.toLong() * h else 0L
        }
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Most common colour (4 bits per channel) in a 1.5 % band inside [b]'s edges, averaged within its bin, and its share. */
    private fun gutterColor(r: Raster, b: Box): Pair<Int, Float> {
        val band = max(2, (0.015f * minOf(b.w, b.h)).roundToInt())
        val counts = IntArray(4096)
        val sums = LongArray(4096 * 3)
        fun add(p: Int) {
            val rr = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val bb = p and 0xFF
            val bin = ((rr shr 4) shl 8) or ((gg shr 4) shl 4) or (bb shr 4)
            counts[bin]++
            sums[bin * 3] += rr.toLong()
            sums[bin * 3 + 1] += gg.toLong()
            sums[bin * 3 + 2] += bb.toLong()
        }
        for (y in b.y0 until b.y1) {
            val inBandY = y < b.y0 + band || y >= b.y1 - band
            for (x in b.x0 until b.x1) {
                if (inBandY || x < b.x0 + band || x >= b.x1 - band) add(r.px[y * r.w + x])
            }
        }
        val best = counts.indices.maxBy { counts[it] }
        val n = counts[best].toLong()
        val color = (0xFF shl 24) or ((sums[best * 3] / n).toInt() shl 16) or ((sums[best * 3 + 1] / n).toInt() shl 8) or (sums[best * 3 + 2] / n).toInt()
        return color to n.toFloat() / counts.sum()
    }

    /** Non-gutter pixel mask with per-row and per-column prefix sums for O(1) line counts. */
    private class Mask(r: Raster, bg: Int, tol: Int) {
        private val w = r.w
        private val h = r.h
        private val rowPrefix = IntArray((w + 1) * h)
        private val colPrefix = IntArray((h + 1) * w)
        private val ink = BooleanArray(w * h)

        init {
            val br = (bg shr 16) and 0xFF
            val bgG = (bg shr 8) and 0xFF
            val bb = bg and 0xFF
            for (y in 0 until h) {
                var acc = 0
                for (x in 0 until w) {
                    val p = r.px[y * w + x]
                    val ink = abs(((p shr 16) and 0xFF) - br) > tol || abs(((p shr 8) and 0xFF) - bgG) > tol || abs((p and 0xFF) - bb) > tol
                    if (ink) acc++
                    this.ink[y * w + x] = ink
                    rowPrefix[y * (w + 1) + x + 1] = acc
                    colPrefix[x * (h + 1) + y + 1] = colPrefix[x * (h + 1) + y] + if (ink) 1 else 0
                }
            }
        }

        fun isInk(x: Int, y: Int) = ink[y * w + x]

        fun rowInk(y: Int, x0: Int, x1: Int) = rowPrefix[y * (w + 1) + x1] - rowPrefix[y * (w + 1) + x0]

        fun colInk(x: Int, y0: Int, y1: Int) = colPrefix[x * (h + 1) + y1] - colPrefix[x * (h + 1) + y0]

        fun rowIsGutter(y: Int, b: Box, tol: Float) = rowInk(y, b.x0, b.x1) <= tol * b.w

        fun colIsGutter(x: Int, b: Box, tol: Float) = colInk(x, b.y0, b.y1) <= tol * b.h

        fun trim(b: Box, tol: Float): Box {
            var (x0, y0, x1, y1) = b
            while (y0 < y1 && rowIsGutter(y0, Box(x0, y0, x1, y1), tol)) y0++
            while (y1 > y0 && rowIsGutter(y1 - 1, Box(x0, y0, x1, y1), tol)) y1--
            while (x0 < x1 && colIsGutter(x0, Box(x0, y0, x1, y1), tol)) x0++
            while (x1 > x0 && colIsGutter(x1 - 1, Box(x0, y0, x1, y1), tol)) x1--
            return Box(x0, y0, x1, y1)
        }

        /** Splits [b] at every gutter run of at least [minGap] lines. */
        fun split(b: Box, horizontal: Boolean, tol: Float, minGap: Int): List<Box> {
            val start = if (horizontal) b.y0 else b.x0
            val end = if (horizontal) b.y1 else b.x1
            val parts = ArrayList<Box>()
            var segStart = start
            var i = start
            while (i < end) {
                val gutter = if (horizontal) rowIsGutter(i, b, tol) else colIsGutter(i, b, tol)
                if (!gutter) {
                    i++
                    continue
                }
                var j = i
                while (j < end && (if (horizontal) rowIsGutter(j, b, tol) else colIsGutter(j, b, tol))) j++
                if (j - i >= minGap && i > segStart) {
                    parts += if (horizontal) Box(b.x0, segStart, b.x1, i) else Box(segStart, b.y0, i, b.y1)
                    segStart = j
                }
                i = j
            }
            if (segStart < end) parts += if (horizontal) Box(b.x0, segStart, b.x1, end) else Box(segStart, b.y0, end, b.y1)
            return parts
        }
    }
}
