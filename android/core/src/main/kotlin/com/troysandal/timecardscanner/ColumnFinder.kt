package com.troysandal.timecardscanner

/**
 * Finds the printed vertical dividers inside the table's row range and picks
 * the four that bound the card's `Ck.#` column and three value columns.
 *
 * Candidates are columns of the (tilt-tolerant) darkness profile. The card
 * layout is the only prior: three value columns of similar width (within 30%,
 * then 40% on the relaxed pass), all right of the printed numerals. No text is
 * involved. When rules are faint the grid degrades in steps (relaxed, inferred
 * from three or two rules, equal thirds); [ColumnEdges.source] says which, so a
 * guessed grid can be flagged.
 *
 * Result edges: `[ckLeft, d1, d2, d3, d4]`, ready for [Grid.colEdges]
 * (`ckLeft` is the image edge or the table's left border, whichever is known).
 */
data class ColumnEdges(val edges: List<Float>, val source: Source) {
    /** How the grid was found, best to worst. Anything but [Source.RULES] deserves a warning flag. */
    enum class Source { RULES, RELAXED, INFERRED, EQUAL_THIRDS }

    /** True when all four dividers were found as printed rules (strict or relaxed). */
    val fromRules get() = source == Source.RULES || source == Source.RELAXED
}

object ColumnFinder {
    /** Just the provenance of a grid, for evaluation tooling that already holds the edges. */
    class Result(val source: ColumnEdges.Source)

    fun find(
        gray: ByteArray,
        width: Int,
        height: Int,
        y0: Int,
        y1: Int,
        numeralBandRight: Float,
        minStrength: Double = 0.25,
    ): ColumnEdges {
        val strict = debugPeaks(gray, width, height, y0, y1, minStrength)
        best(strict, width, numeralBandRight)?.let { return ColumnEdges(listOf(0f) + it, ColumnEdges.Source.RULES) }
        // Faint or partly occluded rules: lower the strength bar and widen the width tolerance.
        val weak = debugPeaks(gray, width, height, y0, y1, minStrength = 0.15)
        best(weak, width, numeralBandRight, tolerance = 0.4f)?.let { return ColumnEdges(listOf(0f) + it, ColumnEdges.Source.RELAXED) }
        // Three dividers found but the right border missed: infer it from the column width.
        best3(weak, width, numeralBandRight)?.let { return ColumnEdges(listOf(0f) + it, ColumnEdges.Source.INFERRED) }
        // Only the outer rules are visible: split evenly between them.
        anchored(weak, width, numeralBandRight)?.let { return ColumnEdges(listOf(0f) + it, ColumnEdges.Source.INFERRED) }
        return fallback(width, numeralBandRight)
    }

    /** Candidate divider columns (x, strength) in the table's row range, strongest 10, left to right. */
    fun debugPeaks(gray: ByteArray, width: Int, height: Int, y0: Int, y1: Int, minStrength: Double = 0.25): List<Pair<Float, Double>> {
        val top = y0.coerceIn(0, height - 1)
        val bottom = y1.coerceIn(top + 1, height)

        var sum = 0L
        for (v in gray) sum += v.toInt() and 0xFF
        val dark = (sum / gray.size * 0.7).toInt()

        val count = IntArray(width)
        for (y in top until bottom) {
            val base = y * width
            for (x in 0 until width) if ((gray[base + x].toInt() and 0xFF) < dark) count[x]++
        }
        // Max over a small window absorbs slight tilt; strength = share of table height.
        val win = maxOf(2, width / 250)
        val strength = DoubleArray(width) { x ->
            var m = 0
            for (k in maxOf(0, x - win)..minOf(width - 1, x + win)) if (count[k] > m) m = count[k]
            m.toDouble() / (bottom - top)
        }
        return (1 until width - 1)
            .filter { strength[it] >= strength[it - 1] && strength[it] > strength[it + 1] && strength[it] > minStrength }
            .sortedByDescending { strength[it] }.take(10)
            .sorted().map { it.toFloat() to strength[it] }
    }

    private fun fallback(width: Int, numeralBandRight: Float): ColumnEdges {
        // Fallback: no usable rules (faint pencil, glare). Split what is right of the numeral
        // band into three equal columns out to the right margin; callers should flag it.
        val d1 = numeralBandRight + width * 0.04f
        val right = width * 0.96f
        val step = (right - d1) / 3
        return ColumnEdges(listOf(0f, d1, d1 + step, d1 + 2 * step, right), ColumnEdges.Source.EQUAL_THIRDS)
    }

    /** Best 4 dividers `d1<d2<d3<d4`: strong, right of the numerals, with similar value-column widths. */
    internal fun best(peaks: List<Pair<Float, Double>>, width: Int, bandRight: Float, tolerance: Float = 0.3f): List<Float>? {
        var bestScore = 0.0
        var bestPick: List<Float>? = null
        val n = peaks.size
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            val x = listOf(peaks[a].first, peaks[b].first, peaks[c].first, peaks[d].first)
            if (x[0] < bandRight - width * 0.005f) continue // Ck|Arrived divider must be right of the numerals
            val w = listOf(x[1] - x[0], x[2] - x[1], x[3] - x[2])
            val mean = w.average().toFloat()
            if (mean < width * 0.12f) continue
            if (w.any { it < mean * (1 - tolerance) || it > mean * (1 + tolerance) }) continue
            val score = peaks[a].second + peaks[b].second + peaks[c].second + peaks[d].second
            if (score > bestScore) { bestScore = score; bestPick = x }
        }
        return bestPick
    }

    /** `d1<d2<d3` with two similar value-column widths; the right border is inferred as `d3 + (d3 - d2)`. */
    internal fun best3(peaks: List<Pair<Float, Double>>, width: Int, bandRight: Float): List<Float>? {
        var bestScore = 0.0
        var bestPick: List<Float>? = null
        for (a in peaks.indices) for (b in a + 1 until peaks.size) for (c in b + 1 until peaks.size) {
            val x = listOf(peaks[a].first, peaks[b].first, peaks[c].first)
            if (x[0] < bandRight - width * 0.005f) continue
            val w1 = x[1] - x[0]
            val w2 = x[2] - x[1]
            if (w1 < width * 0.12f || w2 < width * 0.12f) continue
            if (w2 < w1 * 0.7f || w2 > w1 * 1.3f) continue
            // The third column is often narrower, so cap at the right margin instead of rejecting.
            val d4 = minOf(x[2] + w2, width * 0.97f)
            if (d4 - x[2] < w2 * 0.5f) continue
            val score = peaks[a].second + peaks[b].second + peaks[c].second
            if (score > bestScore) { bestScore = score; bestPick = x + d4 }
        }
        return bestPick
    }

    /** The first rule right of the numerals and the last rule near the right margin, with the middle split evenly. */
    internal fun anchored(peaks: List<Pair<Float, Double>>, width: Int, bandRight: Float): List<Float>? {
        val d1 = peaks.firstOrNull { it.first >= bandRight - width * 0.005f }?.first ?: return null
        val d4 = peaks.lastOrNull { it.first >= width * 0.85f }?.first ?: return null
        if (d4 - d1 < width * 0.5f) return null
        val step = (d4 - d1) / 3
        return listOf(d1, d1 + step, d1 + 2 * step, d4)
    }
}
