package com.troysandal.timecardscanner

/** Pixel rect, top-left origin, same convention as the iOS cell rects. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** One recognized character (ML Kit `Symbol`) with its box. */
data class OcrSymbol(val text: String, val box: Box, val confidence: Float = 1f)

/**
 * Printed grid as edge coordinates. [colEdges] has 5 entries for the card
 * (Ck.# column + 3 value columns); [rowEdges] has rows + 1 entries, header
 * rows already excluded. Both ascending.
 */
data class Grid(val rowEdges: List<Float>, val colEdges: List<Float>) {
    val rows get() = rowEdges.size - 1
    val valueColumns get() = colEdges.size - 2
}

data class Cell(val text: String, val flagged: Boolean)

/**
 * Puts each character into the cell its center falls in, so writing that
 * crosses a printed divider is split by where the ink lands (how the corpus
 * labels are defined). Characters outside the grid are ignored. Text is read
 * left to right within a cell and mapped onto `[0-9:]` via [Charset].
 */
object CellAssigner {
    fun assign(symbols: List<OcrSymbol>, grid: Grid): List<List<Cell>> {
        val buckets = Array(grid.rows) { Array(grid.valueColumns) { mutableListOf<OcrSymbol>() } }
        for (s in symbols) {
            val r = bucket(grid.rowEdges, s.box.centerY)
            val c = bucket(grid.colEdges, s.box.centerX) - 1 // column 0 is Ck.#
            if (r < 0 || c < 0 || c >= grid.valueColumns) continue
            buckets[r][c].add(s)
        }
        return buckets.map { row ->
            row.map { syms ->
                val raw = syms.sortedBy { it.box.centerX }.joinToString("") { it.text }
                val n = Charset.normalize(raw)
                Cell(n.text, n.changed)
            }
        }
    }

    /** Index of the interval [edges[i], edges[i+1]) containing v, or -1. */
    internal fun bucket(edges: List<Float>, v: Float): Int {
        if (edges.size < 2 || v < edges.first() || v >= edges.last()) return -1
        for (i in 0 until edges.size - 1) if (v < edges[i + 1]) return i
        return -1
    }
}
