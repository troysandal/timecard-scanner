package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColumnFinderTest {
    private val w = 1000
    private val h = 400

    private fun blank() = ByteArray(w * h) { 0xFF.toByte() }

    private fun ByteArray.vLine(x: Int, from: Int = 0, to: Int = h) {
        for (y in from until to) for (t in 0 until 3) this[y * w + x + t] = 0
    }

    @Test fun picksTheFourDividersAndIgnoresSpuriousStrokes() {
        // left border 60, Ck|Arrived 170, 480, 740, right border 950; plus handwriting-like strokes
        val img = blank().apply {
            for (x in listOf(60, 170, 480, 740, 950)) vLine(x)
            vLine(300, 100, 300) // tall stroke inside a column, ~50% of the height
            vLine(620, 150, 350)
        }
        val c = ColumnFinder.find(img, w, h, 0, h, numeralBandRight = 120f)
        assertTrue(c.fromRules)
        assertEquals(5, c.edges.size)
        listOf(171f, 481f, 741f, 951f).zip(c.edges.drop(1)).forEach { (want, got) -> assertEquals(want, got, 8f) }
    }

    @Test fun strictRulesAreReportedAsRules() {
        val img = blank().apply { for (x in listOf(60, 170, 480, 740, 950)) vLine(x) }
        assertEquals(ColumnEdges.Source.RULES, ColumnFinder.find(img, w, h, 0, h, 120f).source)
    }

    @Test fun faintRulesAreFoundOnTheRelaxedPass() {
        // rules only 20% of the table height: below the strict 0.25 bar, above the relaxed 0.15
        val img = blank().apply { for (x in listOf(170, 480, 740, 950)) vLine(x, 0, 80) }
        val c = ColumnFinder.find(img, w, h, 0, h, 120f)
        assertEquals(ColumnEdges.Source.RELAXED, c.source)
        assertTrue(c.fromRules)
    }

    @Test fun missingRightBorderIsInferredAndCappedAtTheMargin() {
        // three dividers, the third column narrower than the second, no right border drawn
        val img = blank().apply { for (x in listOf(170, 480, 790)) vLine(x) }
        val c = ColumnFinder.find(img, w, h, 0, h, 120f)
        assertEquals(ColumnEdges.Source.INFERRED, c.source)
        assertFalse(c.fromRules)
        assertTrue(c.edges.last() <= w * 0.97f + 1, "right edge ${c.edges.last()} past the margin")
    }

    @Test fun onlyOuterRulesAreSplitEvenly() {
        val img = blank().apply { vLine(170); vLine(900) }
        val c = ColumnFinder.find(img, w, h, 0, h, 120f)
        assertEquals(ColumnEdges.Source.INFERRED, c.source)
        val widths = c.edges.drop(1).zipWithNext { a, b -> b - a }
        assertEquals(widths[0], widths[1], 0.5f)
        assertEquals(widths[1], widths[2], 0.5f)
    }

    @Test fun fallsBackToEqualColumnsWhenThereAreNoRules() {
        val c = ColumnFinder.find(blank(), w, h, 0, h, numeralBandRight = 120f)
        assertEquals(ColumnEdges.Source.EQUAL_THIRDS, c.source)
        assertFalse(c.fromRules)
        assertEquals(5, c.edges.size)
        val widths = c.edges.drop(1).zipWithNext { a, b -> b - a }
        assertEquals(widths[0], widths[1], 0.01f)
        assertEquals(widths[1], widths[2], 0.01f)
    }
}
