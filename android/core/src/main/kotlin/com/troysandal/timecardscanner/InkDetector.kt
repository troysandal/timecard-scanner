package com.troysandal.timecardscanner

/**
 * "Is there handwriting in this cell?" from pixels alone, no OCR and no model.
 * Used to flag cells that hold ink but that the reader returned blank, so a
 * review screen can ask the user to fill them in.
 *
 * Ink = pixels clearly darker than the cell's own paper level (its median), so
 * shadows and uneven lighting across the card do not matter. Printed rules
 * that leak into the crop (tilt, slightly wrong grid) are removed: long
 * straight runs of ink first, then any pixel row or column that is mostly ink. Thresholds are first guesses to
 * be swept on the corpus.
 */
object InkDetector {
    /**
     * Fraction (0..1) of the inset cell that is ink after rule removal.
     * [delta] is how much darker than the paper a pixel must be to count.
     */
    fun score(
        gray: ByteArray,
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        inset: Int = 10,
        delta: Int = 45,
    ): Double {
        val x0 = (left + inset).toInt().coerceIn(0, width)
        val x1 = (right - inset).toInt().coerceIn(x0, width)
        val y0 = (top + inset).toInt().coerceIn(0, height)
        val y1 = (bottom - inset).toInt().coerceIn(y0, height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 4 || h < 4) return 0.0

        val hist = IntArray(256)
        for (y in y0 until y1) for (x in x0 until x1) hist[gray[y * width + x].toInt() and 0xFF]++
        var acc = 0
        var paper = 255
        for (v in 0..255) { acc += hist[v]; if (acc * 2 >= w * h) { paper = v; break } }
        val limit = paper - delta

        val ink = BooleanArray(w * h)
        val rowInk = IntArray(h)
        val colInk = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            if ((gray[(y0 + y) * width + x0 + x].toInt() and 0xFF) < limit) {
                ink[y * w + x] = true
                rowInk[y]++
                colInk[x]++
            }
        }
        eraseRuleRuns(ink, w, h)
        var count = 0
        for (y in 0 until h) {
            if (rowInk[y] > w * 0.7) continue // a horizontal rule, not writing
            for (x in 0 until w) if (ink[y * w + x] && colInk[x] <= h * 0.7) count++
        }
        return count.toDouble() / (w * h)
    }

    /**
     * Clears long straight runs of ink, which are printed rules and not writing. The row/column test in
     * [score] only catches a rule that stays in the same pixel rows across the whole cell; in a photo taken
     * at an angle the rules slant, so each pixel row holds only part of one. A run counts as a rule when it
     * is long (45% of the cell's width, 90% of its height), or shorter (30% / 50%) but starting at the
     * cell's edge, as a slanted rule's pieces do, or a vertical piece hugging the cell's side (a divider
     * leaning in). Its soft edges go with it.
     */
    private fun eraseRuleRuns(ink: BooleanArray, w: Int, h: Int) {
        val kill = BooleanArray(w * h)
        val margin = maxOf(2, Math.round(h * 0.03f))
        val edge = margin
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!ink[y * w + x]) { x++; continue }
                var e = x
                while (e < w && ink[y * w + e]) e++
                val atEdge = x <= edge || e >= w - edge
                if (e - x >= 0.45 * w || (atEdge && e - x >= 0.30 * w)) {
                    for (yy in maxOf(0, y - margin)..minOf(h - 1, y + margin)) for (xx in x until e) kill[yy * w + xx] = true
                }
                x = e
            }
        }
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!ink[y * w + x]) { y++; continue }
                var e = y
                while (e < h && ink[e * w + x]) e++
                val atEdge = y <= edge || e >= h - edge
                // a column divider that leans into the cell stays close to the cell's side
                val bySide = x <= 0.04 * w || x >= 0.96 * w
                if (e - y >= 0.90 * h || (atEdge && e - y >= 0.50 * h) || (bySide && e - y >= 0.30 * h)) {
                    for (xx in maxOf(0, x - margin)..minOf(w - 1, x + margin)) for (yy in y until e) kill[yy * w + xx] = true
                }
                y = e
            }
        }
        for (i in kill.indices) if (kill[i]) ink[i] = false
    }
}
