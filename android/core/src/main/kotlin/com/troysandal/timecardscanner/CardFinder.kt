package com.troysandal.timecardscanner

import kotlin.math.max
import kotlin.math.min

/**
 * Where a card lies in a photo of it, as a starting box for the user's crop: the bounding box of the largest bright
 * region (a card is lighter than the desk it lies on), plus a small margin. Cheap and approximate on purpose, the
 * user always adjusts it. `null` when nothing card-like is found; the caller then starts from a default inset.
 */
object CardFinder {
    private const val WORK_SIDE = 512
    private const val MIN_AREA = 0.15
    private const val MAX_AREA = 0.98
    private const val MARGIN = 0.02

    /** A box inset from the photo's edges, for when no card is found. */
    val FALLBACK: ScanRegion = ScanRegion.of(0.05, 0.05, 0.90, 0.90)!!

    fun find(gray: ByteArray, width: Int, height: Int): ScanRegion? {
        if (width <= 0 || height <= 0 || gray.size < width * height) return null
        val step = max(1, max(width, height) / WORK_SIDE)
        val w = width / step
        val h = height / step
        if (w < 8 || h < 8) return null
        val small = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until step) for (dx in 0 until step) sum += gray[(y * step + dy) * width + x * step + dx].toInt() and 0xFF
            small[y * w + x] = sum / (step * step)
        }
        val threshold = otsu(small)
        val bright = BooleanArray(w * h) { small[it] > threshold }
        // largest 4-connected bright region
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var best = 0
        var bx0 = 0; var by0 = 0; var bx1 = 0; var by1 = 0
        for (start in bright.indices) {
            if (!bright[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                area++
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && bright[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && bright[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && bright[p - w] && !seen[p - w]) { seen[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && bright[p + w] && !seen[p + w]) { seen[p + w] = true; stack[sp++] = p + w }
            }
            if (area > best) { best = area; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1 }
        }
        val fraction = best.toDouble() / (w * h)
        if (fraction < MIN_AREA || fraction > MAX_AREA) return null
        val x = max(0.0, bx0.toDouble() / w - MARGIN)
        val y = max(0.0, by0.toDouble() / h - MARGIN)
        val right = min(1.0, (bx1 + 1).toDouble() / w + MARGIN)
        val bottom = min(1.0, (by1 + 1).toDouble() / h + MARGIN)
        return ScanRegion.of(x, y, right - x, bottom - y)
    }

    /** Otsu's threshold for 0..255 values. */
    private fun otsu(values: IntArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v.coerceIn(0, 255)]++
        val total = values.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i * hist[i].toDouble()
        var wB = 0.0; var sumB = 0.0; var bestVar = -1.0; var threshold = 128
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > bestVar) { bestVar = between; threshold = t }
        }
        return threshold
    }
}
