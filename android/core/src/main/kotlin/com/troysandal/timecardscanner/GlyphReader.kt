package com.troysandal.timecardscanner

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Result of reading one cell with the digit model. */
data class GlyphRead(
    /** Digits, plus ':' for two small stacked dots or a single dot between digits; "" when nothing handwritten was found. */
    val text: String,
    /** Lowest softmax confidence over the digit glyphs (1.0 when nothing was read; a colon does not lower it). */
    val confidence: Float,
    /** Ink strength of the strongest stroke (0..1), before the [GlyphReader.peakMin] gate. */
    val peak: Float,
    /**
     * The shape gives a reason to distrust the value however confident the digits are: a line was erased
     * through the middle of the writing (a struck-out value, or writing over a printed rule), or a blob was
     * cut into several digits. A review screen should flag the cell.
     */
    val suspect: Boolean = false,
    /** Width of the widest glyph relative to the tallest one in the cell; well above 1 may be touching digits. */
    val widest: Float = 0f,
)

/**
 * Reads a cell crop with [DigitNet]: ink map -> Otsu -> connected components
 * -> drop printed rules -> merge broken strokes -> split wide blobs -> centre
 * each glyph MNIST-style -> classify. Ported from training/evaluate.py; with
 * [pythonParity] settings it reproduces the Python run (the parity reference).
 * The defaults differ in two ways that the Python original lacks: printed rules
 * are erased, and wide single digits are no longer split.
 *
 * The thresholds (peakMin, rule rejection, glyph size gates) were tuned
 * in-sample on 18 physical cards; treat results as an upper bound.
 */
class GlyphReader(
    private val net: DigitNet,
    /** Minimum ink strength of the strongest stroke. 0.88 (the Python value) rejects good reads once a crop is resampled. */
    val peakMin: Float = 0.7f,
    /**
     * Erase printed rules that run through the crop (long straight runs of ink) before looking for glyphs.
     * Not in the Python original: turn it off to compare against tools/parity.
     */
    val stripRules: Boolean = true,
    /**
     * A blob is taken for touching digits when it is wider than this many times the tallest glyph in the
     * cell; it is then cut evenly into pieces about [splitPitch] of that height wide. The Python original
     * used 0.95 and 0.62 of the blob's own height ([splitByCellHeight] off), which cut wide single digits
     * (0, 2, 5, 7, 8) in two. The corpus has almost no touching digits: any [splitAspect] from 1.35 up
     * scores the same, so the split itself is unproven.
     */
    val splitAspect: Double = 1.5,
    val splitPitch: Double = 0.8,
    val splitByCellHeight: Boolean = true,
    /** Report a colon-shaped glyph as ':' instead of classifying it as a digit (the Python original had no colon). */
    val readColons: Boolean = true,
    /** Drop stray specks before strokes are merged (not in the Python original): one merged into a glyph stretches its box. */
    val dropSpecks: Boolean = true,
    /**
     * Every crop is resampled to this height (keeping its shape) before anything else, so the pixel-sized
     * steps below (15 px background filter, rule margins, size gates) see the same scale whatever the
     * camera or scanner resolution. 0 = use the crop as it is (the Python original).
     */
    val normHeight: Int = 140,
    /**
     * Lowest ink level (0..1, how much darker than the paper) a pixel may have and still count as writing: the
     * threshold is the larger of Otsu's and this. 0.18 suits dark ink. On faint ink (peak ~0.3) it cuts the thin
     * parts of strokes, which fall apart into pieces that can pair up as stray colons, so a faint card lowers it.
     */
    val inkFloor: Float = 0.18f,
) {

    /** [gray] is a [h] x [w] crop, row-major, 0 = black .. 255 = white. */
    fun read(gray: FloatArray, w: Int, h: Int): GlyphRead {
        if (normHeight > 0 && h != normHeight && h > 0 && w > 0) {
            val nw = max(1, Math.round(w.toDouble() * normHeight / h).toInt())
            return readAtScale(resizeGray(gray, w, h, nw, normHeight), nw, normHeight)
        }
        return readAtScale(gray, w, h)
    }

    /** Resamples a 0..255 grayscale crop (anti-aliased when shrinking, bilinear when enlarging). */
    fun resizeGray(gray: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val out = resizeBilinear(IntArray(w * h) { gray[it].toInt().coerceIn(0, 255) }, w, h, nw, nh)
        return FloatArray(nw * nh) { out[it].toFloat() }
    }

    private fun readAtScale(gray: FloatArray, w: Int, h: Int): GlyphRead {
        val ink = inkMap(gray, w, h)
        val ruleRuns = ArrayList<IntArray>()
        val glyphs = segment(ink, w, h, ruleRuns)
        if (glyphs.isEmpty()) return GlyphRead("", 1f, 0f)
        var peak = 0f
        for (g in glyphs) for (yy in 0 until g.h) for (xx in 0 until g.w) if (g.mask[yy * g.w + xx]) {
            peak = max(peak, ink[(g.y0 + yy) * w + g.x0 + xx])
        }
        if (peak < peakMin) return GlyphRead("", 1f, peak)

        val out = StringBuilder()
        var conf = 1f
        val tallest = glyphs.maxOf { it.h }
        var suspect = false
        var widest = 0f
        for (g in glyphs.sortedBy { it.x0 }) {
            if (g.colon) { out.append(':'); continue }
            widest = max(widest, g.w.toFloat() / tallest)
            // an erased line through the middle half of this glyph: a strike-through, not a rule beside it
            if (ruleRuns.any { (y, rx0, rx1) -> y > g.y0 + 0.25 * g.h && y < g.y1 - 0.25 * g.h && min(rx1, g.x1) - max(rx0, g.x0) >= 0.5 * g.w }) suspect = true
            val gh = g.h
            val gw = g.w
            val ref = if (splitByCellHeight) tallest else gh
            val k = if (gw > splitAspect * ref) max(1, pyRound(gw / (splitPitch * ref))) else 1 // touching digits: split evenly
            if (k > 1) suspect = true // an even cut is a guess
            for (j in 0 until k) {
                val xs = pyRound(j.toDouble() * gw / k)
                val xe = pyRound((j + 1).toDouble() * gw / k)
                val pw = xe - xs
                if (pw <= 0) continue
                val patch = FloatArray(gh * pw)
                for (yy in 0 until gh) for (xx in 0 until pw) {
                    val inside = g.mask[yy * gw + xs + xx]
                    patch[yy * pw + xx] = if (inside) ink[(g.y0 + yy) * w + g.x0 + xs + xx] else 0f
                }
                val img = glyphTo28(patch, pw, gh) ?: continue
                val p = net.probabilities(img)
                var best = 0
                for (c in 1 until 10) if (p[c] > p[best]) best = c
                out.append(best)
                conf = min(conf, p[best])
            }
        }
        return GlyphRead(out.toString(), conf, peak, suspect, widest)
    }

    /** For diagnosis: the glyph boxes [x0, y0, x1, y1] found in a crop, left to right. */
    fun glyphBoxes(gray: FloatArray, w: Int, h: Int): List<IntArray> =
        segment(inkMap(gray, w, h), w, h).sortedBy { it.x0 }.map { intArrayOf(it.x0, it.y0, it.x1, it.y1) }

    /** For diagnosis: what the segmentation saw in a crop, after the same resampling as [read]. */
    class Debug(val w: Int, val h: Int, val gray: FloatArray, val pieces: List<IntArray>, val glyphs: List<IntArray>, val glyphParts: List<List<IntArray>>, val colon: List<Boolean>)

    fun debug(gray0: FloatArray, w0: Int, h0: Int): Debug {
        var gray = gray0; var w = w0; val h = if (normHeight > 0) normHeight else h0
        if (h != h0) { w = max(1, Math.round(w0.toDouble() * h / h0).toInt()); gray = resizeGray(gray0, w0, h0, w, h) }
        val pieces = ArrayList<IntArray>()
        val glyphs = segment(inkMap(gray, w, h), w, h, null, pieces).sortedBy { it.x0 }
        return Debug(w, h, gray, pieces, glyphs.map { intArrayOf(it.x0, it.y0, it.x1, it.y1) }, glyphs.map { it.parts }, glyphs.map { it.colon })
    }

    // ---------------------------------------------------------------- ink map

    /** Dark strokes on any paper tint, in 0..1: (background - pixel) / background. */
    internal fun inkMap(g: FloatArray, w: Int, h: Int): FloatArray {
        val closed = erode(dilate(g, w, h, 15), w, h, 15)
        val bg = gaussian(closed, w, h, max(w, h) / 10.0)
        return FloatArray(w * h) { i -> ((bg[i] - g[i]) / max(bg[i], 1f)).coerceIn(0f, 1f) }
    }

    private fun refl(i: Int, n: Int): Int { // scipy 'reflect': d c b a | a b c d | d c b a
        if (n == 1) return 0
        val period = 2 * n
        val j = ((i % period) + period) % period
        return if (j < n) j else period - 1 - j
    }

    private fun dilate(a: FloatArray, w: Int, h: Int, size: Int): FloatArray = rankFilter(a, w, h, size, true)
    private fun erode(a: FloatArray, w: Int, h: Int, size: Int): FloatArray = rankFilter(a, w, h, size, false)

    /** Separable box max/min filter with reflected borders (== a size x size square footprint). */
    private fun rankFilter(a: FloatArray, w: Int, h: Int, size: Int, isMax: Boolean): FloatArray {
        val r = size / 2
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = a[y * w + refl(x - r, w)]
            for (k in -r + 1..r) { val c = a[y * w + refl(x + k, w)]; v = if (isMax) max(v, c) else min(v, c) }
            tmp[y * w + x] = v
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = tmp[refl(y - r, h) * w + x]
            for (k in -r + 1..r) { val c = tmp[refl(y + k, h) * w + x]; v = if (isMax) max(v, c) else min(v, c) }
            out[y * w + x] = v
        }
        return out
    }

    /**
     * scipy.ndimage.gaussian_filter: truncate 4.0, reflected borders. Each row/column is
     * copied once into a reflected buffer so the inner loop is a plain multiply-accumulate
     * (the radius is ~sigma*4, hundreds of taps on a large crop).
     */
    private fun gaussian(a: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val radius = (4.0 * sigma + 0.5).toInt()
        val kernel = DoubleArray(2 * radius + 1)
        var total = 0.0
        for (i in kernel.indices) { val v = exp(-0.5 * (i - radius) * (i - radius) / (sigma * sigma)); kernel[i] = v; total += v }
        for (i in kernel.indices) kernel[i] /= total

        val tmp = FloatArray(w * h)
        val row = FloatArray(w + 2 * radius)
        for (y in 0 until h) {
            for (i in row.indices) row[i] = a[y * w + refl(i - radius, w)]
            for (x in 0 until w) {
                var s = 0.0 // scipy accumulates in double; intermediate rows are stored as float32
                for (k in kernel.indices) s += kernel[k] * row[x + k]
                tmp[y * w + x] = s.toFloat()
            }
        }
        val out = FloatArray(w * h)
        val col = FloatArray(h + 2 * radius)
        for (x in 0 until w) {
            for (i in col.indices) col[i] = tmp[refl(i - radius, h) * w + x]
            for (y in 0 until h) {
                var s = 0.0
                for (k in kernel.indices) s += kernel[k] * col[y + k]
                out[y * w + x] = s.toFloat()
            }
        }
        return out
    }

    // ---------------------------------------------------------------- threshold

    /** Otsu over a 256-bin histogram, as in training/common.py. */
    internal fun otsu(a: FloatArray): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in a) { lo = min(lo, v); hi = max(hi, v) }
        if (hi <= lo) return hi
        val bins = 256
        val hist = DoubleArray(bins)
        val step = (hi - lo).toDouble() / bins
        for (v in a) hist[min(bins - 1, ((v - lo) / step).toInt())]++
        val mids = DoubleArray(bins) { lo + step * (it + 0.5) }
        val w0 = DoubleArray(bins)
        val cum = DoubleArray(bins)
        var s0 = 0.0
        var sm = 0.0
        for (i in 0 until bins) { s0 += hist[i]; sm += hist[i] * mids[i]; w0[i] = s0; cum[i] = sm }
        val total = w0[bins - 1]
        val m1 = DoubleArray(bins)
        var rs = 0.0
        var rw = 0.0
        for (i in bins - 1 downTo 0) { rs += hist[i] * mids[i]; rw += hist[i]; m1[i] = rs / max(rw, 1.0) }
        var best = -1.0
        var bestI = 0
        for (i in 0 until bins - 1) {
            val m0 = cum[i] / max(w0[i], 1.0)
            val v = w0[i] * (total - w0[i]) * (m0 - m1[i + 1]) * (m0 - m1[i + 1])
            if (v > best) { best = v; bestI = i }
        }
        return mids[bestI].toFloat()
    }

    // ---------------------------------------------------------- segmentation

    private class Comp(
        var x0: Int, var x1: Int, var y0: Int, var y1: Int, var area: Int, var mask: BooleanArray,
        /** Boxes [x0, y0, x1, y1] of the connected pieces this glyph was merged from. */
        val parts: List<IntArray> = listOf(intArrayOf(x0, y0, x1, y1)),
        /** Two dots paired by [pairColons]. */
        val colon: Boolean = false,
    ) {
        val w get() = x1 - x0
        val h get() = y1 - y0
    }

    /**
     * Pairs up colons before strokes are merged: two small dots, one above the other with a clear gap,
     * beside at least one real digit. Size is judged against the tallest piece in the cell, so a cell
     * holding only dots has no colon. Handwritten colons slant, so the dots need not overlap sideways
     * (the stroke merge below would miss those and the size gate would then drop both dots).
     */
    private fun pairColons(comps: MutableList<Comp>) {
        val tall = comps.maxOfOrNull { it.h } ?: return
        fun dot(c: Comp) = !c.colon && c.h <= COLON_DOT_MAX_HEIGHT * tall && c.w <= COLON_DOT_MAX_WIDTH * tall
        while (true) {
            var best: Pair<Comp, Comp>? = null
            var bestDx = COLON_MAX_SLANT * tall
            for (a in comps) for (b in comps) {
                if (a === b || !dot(a) || !dot(b)) continue
                val gap = b.y0 - a.y1 // a is the upper dot
                if (gap < COLON_MIN_GAP * tall || b.y1 - a.y0 > tall) continue
                val dx = abs((a.x0 + a.x1) - (b.x0 + b.x1)) / 2.0
                if (dx <= bestDx) { bestDx = dx; best = a to b }
            }
            val (a, b) = best ?: break
            val x0 = min(a.x0, b.x0); val x1 = max(a.x1, b.x1)
            val mw = x1 - x0
            val m = BooleanArray(mw * (b.y1 - a.y0))
            for (c in listOf(a, b)) for (yy in 0 until c.h) for (xx in 0 until c.w) {
                if (c.mask[yy * c.w + xx]) m[(c.y0 - a.y0 + yy) * mw + (c.x0 - x0 + xx)] = true
            }
            comps[comps.indexOf(a)] = Comp(x0, x1, a.y0, b.y1, a.area + b.area, m, a.parts + b.parts, colon = true)
            comps.remove(b)
        }
        // A single dot standing between two digits is a colon too (written "9.26"; decided 2026-11-14: a dot
        // means ':'). It must be clear of both neighbours, or it is a fragment the stroke merge should take.
        for (i in comps.indices) {
            val d = comps[i]
            if (!dot(d) || d.area < SINGLE_DOT_MIN_AREA * tall * tall) continue
            fun digit(c: Comp) = !c.colon && !dot(c) && c.h >= 0.5 * tall
            if (comps.any { it !== d && min(it.x1, d.x1) > max(it.x0, d.x0) }) continue
            if (comps.none { digit(it) && it.x1 <= d.x0 } || comps.none { digit(it) && it.x0 >= d.x1 }) continue
            comps[i] = Comp(d.x0, d.x1, d.y0, d.y1, d.area, d.mask, d.parts, colon = true)
        }
    }

    private fun segment(ink: FloatArray, w: Int, h: Int, ruleRuns: MutableList<IntArray>? = null, pieces: MutableList<IntArray>? = null): List<Comp> {
        val thr = max(otsu(ink), inkFloor)
        val on = BooleanArray(w * h) { ink[it] > thr }
        val nearRule = if (stripRules) eraseRules(on, w, h, ruleRuns) else BooleanArray(h)
        val labels = IntArray(w * h)
        val comps = mutableListOf<Comp>()
        var next = 0
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!on[start] || labels[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
            val pixels = ArrayList<Int>()
            while (sp > 0) {
                val p = stack[--sp]
                pixels.add(p)
                val px = p % w
                val py = p / w
                x0 = min(x0, px); x1 = max(x1, px + 1); y0 = min(y0, py); y1 = max(y1, py + 1)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (on[q] && labels[q] == 0) { labels[q] = next; stack[sp++] = q }
                }
            }
            val cw = x1 - x0
            val ch = y1 - y0
            val m = BooleanArray(cw * ch)
            for (p in pixels) m[(p / w - y0) * cw + (p % w - x0)] = true
            // printed rules are not handwriting
            if (ch >= 0.85 * h && cw <= 0.12 * w) continue
            if (cw >= 0.6 * w && ch <= 0.15 * h) continue
            if ((x0 == 0 || x1 == w) && cw <= 0.08 * w && ch >= 0.5 * h) continue
            // the edge of a column divider just inside the crop: a sliver a few pixels wide, of any height
            if (stripRules && (x0 == 0 || x1 == w) && cw <= max(4.0, 0.02 * w)) continue
            if (y0 == 0 && y1 == h) continue
            // what is left of an erased rule: a flat sliver lying on it
            if (ch <= 0.08 * h && cw >= 2 * ch && (y0 until y1).all { nearRule[it] }) continue
            // specks: stray pixels, and what shows of a rule lying along the crop's top or bottom edge
            if (dropSpecks && (pixels.size < SPECK_AREA * h * h || ((y0 == 0 || y1 == h) && ch <= SPECK_EDGE_HEIGHT * h))) continue
            comps.add(Comp(x0, x1, y0, y1, pixels.size, m))
        }
        comps.sortBy { it.x0 } // stable, like Python's sort
        pieces?.addAll(comps.map { intArrayOf(it.x0, it.y0, it.x1, it.y1, it.area) })

        if (readColons) pairColons(comps)

        // merge parts of one glyph (an open 4, a broken 5): strong horizontal overlap
        var merged = true
        while (merged) {
            merged = false
            loop@ for (a in comps.indices) for (b in a + 1 until comps.size) {
                val A = comps[a]
                val B = comps[b]
                if (A.colon || B.colon) continue
                val ov = min(A.x1, B.x1) - max(A.x0, B.x0)
                if (ov > 0.5 * min(A.w, B.w)) {
                    val x0 = min(A.x0, B.x0); val x1 = max(A.x1, B.x1)
                    val y0 = min(A.y0, B.y0); val y1 = max(A.y1, B.y1)
                    val mw = x1 - x0
                    val m = BooleanArray(mw * (y1 - y0))
                    for (c in listOf(A, B)) for (yy in 0 until c.h) for (xx in 0 until c.w) {
                        if (c.mask[yy * c.w + xx]) m[(c.y0 - y0 + yy) * mw + (c.x0 - x0 + xx)] = true
                    }
                    comps[a] = Comp(x0, x1, y0, y1, A.area + B.area, m, A.parts + B.parts)
                    comps.removeAt(b)
                    merged = true
                    break@loop
                }
            }
        }
        return comps.filter { it.colon || (it.h >= 0.22 * h && it.area >= 0.004 * h * h) }
    }

    /**
     * Clears printed rules from the binary ink mask: horizontal runs of at least 45% of the crop width (well above
     * the widest stroke of a digit), or 30% when the run starts at the crop's side as a slanted rule's does, and
     * vertical runs of at least 90% of its height, plus a small margin for their soft edges. A run is one
     * pixel row/column, so a slightly slanted or thick rule goes too, while a digit that touches the rule
     * keeps everything but the crossing. Returns the rows that lie on or beside an erased horizontal rule.
     */
    private fun eraseRules(on: BooleanArray, w: Int, h: Int, runs: MutableList<IntArray>?): BooleanArray {
        val kill = BooleanArray(w * h)
        val nearRule = BooleanArray(h)
        val minH = max(8, (RULE_MIN_WIDTH * w).toInt())
        val minEdgeH = max(8, (RULE_MIN_WIDTH_AT_EDGE * w).toInt())
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!on[y * w + x]) { x++; continue }
                var e = x
                while (e < w && on[y * w + e]) e++
                val reachesEdge = x <= RULE_MARGIN || e >= w - RULE_MARGIN
                if (e - x >= minH || (reachesEdge && e - x >= minEdgeH)) {
                    runs?.add(intArrayOf(y, x, e))
                    for (yy in max(0, y - RULE_MARGIN)..min(h - 1, y + RULE_MARGIN)) {
                        nearRule[yy] = true
                        for (xx in x until e) kill[yy * w + xx] = true
                    }
                    for (yy in max(0, y - 2 * RULE_MARGIN)..min(h - 1, y + 2 * RULE_MARGIN)) nearRule[yy] = true
                }
                x = e
            }
        }
        val minV = max(8, (RULE_MIN_HEIGHT * h).toInt())
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!on[y * w + x]) { y++; continue }
                var e = y
                while (e < h && on[e * w + x]) e++
                if (e - y >= minV) {
                    for (xx in max(0, x - RULE_MARGIN)..min(w - 1, x + RULE_MARGIN)) for (yy in y until e) kill[yy * w + xx] = true
                }
                y = e
            }
        }
        for (i in kill.indices) if (kill[i]) on[i] = false
        return nearRule
    }

    // ------------------------------------------------------------ glyph -> 28x28

    /** Python's round(): half to even. */
    private fun pyRound(v: Double): Int = Math.rint(v).toInt()

    /** MNIST-style: longest side to 20 px (PIL-style bilinear), centred by centre of mass in 28x28. */
    internal fun glyphTo28(patch: FloatArray, w: Int, h: Int): FloatArray? {
        var mx = 0f
        for (v in patch) mx = max(mx, v)
        if (mx <= 0f) return null
        val u8 = IntArray(patch.size) { ((patch[it] / mx) * 255f).toInt().coerceIn(0, 255) } // astype(uint8) truncates
        val s = 20.0 / max(h, w)
        val nw = max(1, pyRound(w * s))
        val nh = max(1, pyRound(h * s))
        val a = resizeBilinear(u8, w, h, nw, nh)
        var sum = 0.0
        var cy = 0.0
        var cx = 0.0
        for (y in 0 until nh) for (x in 0 until nw) { val v = a[y * nw + x] / 255.0; sum += v; cy += v * y; cx += v * x }
        if (sum > 0) { cy /= sum; cx /= sum } else { cy = nh / 2.0; cx = nw / 2.0 }
        var oy = pyRound(14 - cy)
        var ox = pyRound(14 - cx)
        oy = max(0, min(28 - nh, oy))
        ox = max(0, min(28 - nw, ox))
        val canvas = FloatArray(784)
        for (y in 0 until nh) for (x in 0 until nw) canvas[(oy + y) * 28 + ox + x] = a[y * nw + x] / 255f
        return canvas
    }

    /**
     * PIL Image.resize(BILINEAR) for 8-bit data: triangle filter widened when shrinking (anti-aliased),
     * horizontal pass then vertical, with PIL's 22-bit fixed-point coefficients and round-half-up,
     * so the result matches Pillow exactly rather than approximately.
     */
    private fun resizeBilinear(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val hx = coefficients(w, nw)
        val tmp = IntArray(nw * h)
        for (y in 0 until h) for (ox in 0 until nw) {
            val c = hx[ox]
            var acc = 1L shl (PRECISION - 1)
            for (i in c.k.indices) acc += c.k[i].toLong() * src[y * w + c.start + i]
            tmp[y * nw + ox] = clip8(acc shr PRECISION)
        }
        val vy = coefficients(h, nh)
        val out = IntArray(nw * nh)
        for (oy in 0 until nh) for (x in 0 until nw) {
            val c = vy[oy]
            var acc = 1L shl (PRECISION - 1)
            for (i in c.k.indices) acc += c.k[i].toLong() * tmp[(c.start + i) * nw + x]
            out[oy * nw + x] = clip8(acc shr PRECISION)
        }
        return out
    }

    private class Coeff(val start: Int, val k: IntArray)

    private fun clip8(v: Long): Int = if (v < 0) 0 else if (v > 255) 255 else v.toInt()

    /** Pillow's precompute_coeffs + normalize_coeffs_8bpc for the bilinear filter. */
    private fun coefficients(inSize: Int, outSize: Int): List<Coeff> {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 1.0 * filterScale
        val ss = 1.0 / filterScale
        return List(outSize) { xx ->
            val center = (xx + 0.5) * scale
            val xmin = max((center - support + 0.5).toInt(), 0)
            val xmax = min((center + support + 0.5).toInt(), inSize)
            val w = DoubleArray(xmax - xmin) { x -> val t = abs((x + xmin - center + 0.5) * ss); if (t < 1.0) 1.0 - t else 0.0 }
            val sum = w.sum()
            val k = IntArray(w.size) { i ->
                val n = if (sum != 0.0) w[i] / sum else w[i]
                (if (n < 0) -0.5 + n * (1 shl PRECISION) else 0.5 + n * (1 shl PRECISION)).toInt()
            }
            Coeff(xmin, k)
        }
    }

    companion object {
        /** The reader exactly as training/evaluate.py behaves, for tools/parity. */
        fun pythonParity(net: DigitNet, peakMin: Float = 0.88f) =
            GlyphReader(net, peakMin, stripRules = false, splitAspect = 0.95, splitPitch = 0.62, splitByCellHeight = false, readColons = false, dropSpecks = false, normHeight = 0, inkFloor = 0.18f)

        private const val PRECISION = 22
        private const val RULE_MIN_WIDTH = 0.45
        private const val RULE_MIN_WIDTH_AT_EDGE = 0.30
        private const val RULE_MIN_HEIGHT = 0.90
        private const val RULE_MARGIN = 4
        // all relative to the tallest piece in the cell
        private const val COLON_DOT_MAX_HEIGHT = 0.40
        private const val COLON_DOT_MAX_WIDTH = 0.30
        private const val COLON_MAX_SLANT = 0.20
        private const val COLON_MIN_GAP = 0.04
        private const val SINGLE_DOT_MIN_AREA = 0.006
        // relative to the crop height
        private const val SPECK_AREA = 0.001
        private const val SPECK_EDGE_HEIGHT = 0.03
    }
}
