package com.troysandal.timecardscanner

/**
 * Row geometry recovered from the printed `Ck.#` numerals (1..N), which are
 * the leftmost digits on the card. Digits only, so it is language independent
 * and the row count is discovered, never assumed.
 *
 * [rowCenters] has one y per numeral 1..[rowCount]; numerals the OCR missed or
 * misread are interpolated from the pitch. [found] is how many of them were
 * actually read: low `found / rowCount` means the geometry is partly guessed.
 */
data class RowAnchors(
    val rowCenters: List<Float>,
    val pitch: Float,
    val bandLeft: Float,
    val bandRight: Float,
    val found: Int,
) {
    val rowCount get() = rowCenters.size

    /** Row edges at midpoints between centers, half a pitch beyond the ends. */
    val rowEdges: List<Float>
        get() = listOf(rowCenters.first() - pitch / 2) +
            rowCenters.zipWithNext { a, b -> (a + b) / 2 } +
            listOf(rowCenters.last() + pitch / 2)
}

object RowAnchorFinder {
    /** Printed variants top out at 18; generous headroom, but a stray "99" must not become 99 rows. */
    const val MAX_ROWS = 30

    /** The numeral band spans at most this fraction of the image width (two digits are about 0.05). */
    const val MAX_BAND_WIDTH = 0.10f

    private data class Numeral(val value: Int, val cy: Float, val left: Float, val right: Float)

    /** [imageWidth] scales the gap that separates the numeral band from the writing. */
    fun find(symbols: List<OcrSymbol>, imageWidth: Int): RowAnchors? {
        val digits = symbols.mapNotNull { s ->
            val t = Charset.normalize(s.text).text
            if (s.text.length == 1 && t.length == 1 && t[0].isDigit()) s to t[0] else null
        }
        if (digits.size < 2) return null

        // 1. The numeral band: the leftmost cluster of digit x-positions.
        val byX = digits.sortedBy { it.first.box.centerX }
        val gap = imageWidth * 0.035f
        // ...but never wider than a numeral column can be: on a whole card, a handwritten rider number or the
        // writing itself can bridge small gaps all the way across the table and swallow the band. A lone stray
        // digit left of the column (a hole punch read as `1`) makes a cluster of one: start again from the next.
        var start = 0
        var end = 1
        while (start < byX.size - 1) {
            val reach = byX[start].first.box.centerX + imageWidth * MAX_BAND_WIDTH
            end = start + 1
            while (end < byX.size && byX[end].first.box.centerX - byX[end - 1].first.box.centerX <= gap && byX[end].first.box.centerX <= reach) end++
            if (end - start >= 2) break
            start++
        }
        val band = byX.subList(start, end)
        if (band.size < 2) return null

        // 2. Group the band into numerals by y (the two digits of "10" share a row).
        val heights = band.map { it.first.box.bottom - it.first.box.top }.sorted()
        val tol = heights[heights.size / 2] * 0.6f
        val numerals = mutableListOf<Numeral>()
        var group = mutableListOf<Pair<OcrSymbol, Char>>()
        fun flush() {
            if (group.isEmpty()) return
            val ordered = group.sortedBy { it.first.box.centerX }
            // A mapped misread (`l` for 1) is fine, but a printed border read as `|` and
            // mapped to 1 turns "12" into "112": if that is implausible, trust only real digits.
            val value = ordered.map { it.second }.joinToString("").toIntOrNull()?.takeIf { it in 1..MAX_ROWS }
                ?: ordered.filter { it.first.text.single().isDigit() }.map { it.second }.joinToString("")
                    .toIntOrNull()?.takeIf { it in 1..MAX_ROWS }
            if (value != null) numerals.add(Numeral(value, group.map { it.first.box.centerY }.average().toFloat(), group.minOf { it.first.box.left }, group.maxOf { it.first.box.right }))
            group = mutableListOf()
        }
        var lastCy = Float.NEGATIVE_INFINITY
        for (d in band.sortedBy { it.first.box.centerY }) {
            if (group.isNotEmpty() && d.first.box.centerY - lastCy > tol) flush()
            group.add(d)
            lastCy = d.first.box.centerY
        }
        flush()
        if (numerals.size < 2) return null

        // 3. Longest chain with strictly increasing value and y (LIS), so stray digits drop out.
        val n = numerals.size
        val best = IntArray(n) { 1 }
        val prev = IntArray(n) { -1 }
        for (j in 0 until n) for (i in 0 until j) {
            if (numerals[i].value < numerals[j].value && numerals[i].cy < numerals[j].cy && best[i] + 1 > best[j]) {
                best[j] = best[i] + 1
                prev[j] = i
            }
        }
        var tail = best.indices.maxByOrNull { best[it] }!!
        val chain = ArrayDeque<Numeral>()
        while (tail >= 0) { chain.addFirst(numerals[tail]); tail = prev[tail] }
        if (chain.size < 2) return null

        // 4. Pitch from consecutive chain members; interpolate every value 1..max.
        var pitches = chain.zipWithNext { a, b -> (b.cy - a.cy) / (b.value - a.value) }.sorted()
        var pitch = pitches[pitches.size / 2]
        if (pitch <= 0f) return null
        // Numerals sit on one line (value x pitch + offset). A digit group from the card's footer or header (an
        // address, a phone number) that happens to be larger and lower than the real ones would extend the chain
        // past the last row: drop members that are off the line by more than half a row (footer digits are whole rows away).
        val offsets = chain.map { it.cy - it.value * pitch }.sorted()
        val offset = offsets[offsets.size / 2]
        val online = chain.filter { kotlin.math.abs(it.cy - (offset + it.value * pitch)) <= 0.5f * pitch }
        if (online.size != chain.size) {
            if (online.size < 2) return null
            chain.clear()
            chain.addAll(online)
            pitches = chain.zipWithNext { a, b -> (b.cy - a.cy) / (b.value - a.value) }.sorted()
            pitch = pitches[pitches.size / 2]
            if (pitch <= 0f) return null
        }
        val maxValue = chain.last().value
        val centers = (1..maxValue).map { v ->
            val anchor = chain.minByOrNull { kotlin.math.abs(it.value - v) }!!
            anchor.cy + (v - anchor.value) * pitch
        }
        return RowAnchors(
            rowCenters = centers,
            pitch = pitch,
            // Measured from the numerals that made the 1,2,3.. chain, not from every digit near the
            // band: handwriting that starts close to the column would otherwise push the edge
            // past the real divider.
            // ...and by the median over rows, so one row whose handwriting touches its numeral
            // (grouped with it by y) cannot stretch the band either.
            bandLeft = chain.map { it.left }.sorted()[chain.size / 2],
            bandRight = chain.map { it.right }.sorted()[chain.size / 2],
            found = chain.size,
        )
    }
}
