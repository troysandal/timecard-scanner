package com.troysandal.timecardscanner

/**
 * Scores a table (rows x 3 strings) against labeled truth. Port of
 * DevTools/Sources/ScanMetrics/Metrics.swift: same counting rules so Android
 * numbers are directly comparable to S1's 803/867 cells, 294/357 non-blank.
 * Rows past the truth's length are the "blank tail" and must be empty.
 */
data class Score(
    val rowCountMatches: Boolean,
    val cellsCorrect: Int,
    val cellsTotal: Int,
    val nonBlankCorrect: Int,
    val nonBlankTotal: Int,
    val rowsCorrect: Int,
    val rowsTotal: Int,
    val characterErrorRate: Double,
    val blankTailInvented: Int,
)

object Metrics {
    fun score(table: List<List<String>>, truth: List<List<String>>): Score {
        var cellsCorrect = 0
        var cellsTotal = 0
        var nonBlankCorrect = 0
        var nonBlankTotal = 0
        var rowsCorrect = 0
        var rowsTotal = 0
        var invented = 0
        var editSum = 0
        var charSum = 0
        val blank = listOf("", "", "")

        for (r in 0 until maxOf(table.size, truth.size)) {
            val got = table.getOrElse(r) { blank }
            if (r >= truth.size) {
                invented += got.count { it.isNotEmpty() }
                continue
            }
            var rowOk = true
            for (c in 0 until 3) {
                val want = truth[r].getOrElse(c) { "" }
                val have = got.getOrElse(c) { "" }
                cellsTotal++
                if (want == have) cellsCorrect++ else rowOk = false
                if (want.isNotEmpty()) {
                    nonBlankTotal++
                    if (want == have) nonBlankCorrect++
                }
                editSum += editDistance(want, have)
                charSum += want.length
            }
            rowsTotal++
            if (rowOk) rowsCorrect++
        }
        return Score(
            rowCountMatches = table.size == truth.size,
            cellsCorrect = cellsCorrect,
            cellsTotal = cellsTotal,
            nonBlankCorrect = nonBlankCorrect,
            nonBlankTotal = nonBlankTotal,
            rowsCorrect = rowsCorrect,
            rowsTotal = rowsTotal,
            characterErrorRate = if (charSum == 0) 0.0 else editSum.toDouble() / charSum,
            blankTailInvented = invented,
        )
    }

    internal fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            prev = cur
        }
        return prev[b.length]
    }
}
