package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RowAnchorsTest {
    private fun digit(t: String, cx: Float, cy: Float) = OcrSymbol(t, Box(cx - 6, cy - 14, cx + 6, cy + 14))

    /** Numerals 1..n stacked at [pitch], band near x=100 on a 1000 px wide image. */
    private fun numerals(n: Int, pitch: Float = 80f, skip: Set<Int> = emptySet()): List<OcrSymbol> =
        (1..n).filter { it !in skip }.flatMap { v ->
            val cy = 200f + (v - 1) * pitch
            if (v < 10) listOf(digit("$v", 100f, cy))
            else listOf(digit("${v / 10}", 94f, cy), digit("${v % 10}", 108f, cy))
        }

    @Test fun discoversRowCountFromTheNumerals() {
        val a = assertNotNull(RowAnchorFinder.find(numerals(15), 1000))
        assertEquals(15, a.rowCount)
        assertEquals(15, a.found)
        assertEquals(80f, a.pitch, 0.01f)
        assertEquals(200f + 14 * 80f, a.rowCenters.last(), 0.01f)
        assertEquals(16, a.rowEdges.size)
        assertEquals(160f, a.rowEdges.first(), 0.01f)
    }

    @Test fun twoDigitNumeralsAreOneRow() {
        assertEquals(18, RowAnchorFinder.find(numerals(18), 1000)!!.rowCount)
    }

    @Test fun missedNumeralsAreInterpolated() {
        val a = assertNotNull(RowAnchorFinder.find(numerals(12, skip = setOf(5, 6)), 1000))
        assertEquals(12, a.rowCount)
        assertEquals(10, a.found)
        assertEquals(200f + 4 * 80f, a.rowCenters[4], 0.5f) // numeral 5, never read
    }

    @Test fun handwritingDigitsOutsideTheBandAreIgnored() {
        val ink = listOf(digit("4", 300f, 200f), digit("7", 320f, 280f), digit("9", 700f, 360f))
        assertEquals(10, RowAnchorFinder.find(numerals(10) + ink, 1000)!!.rowCount)
    }

    @Test fun aMisreadNumeralThatBreaksTheOrderIsDroppedFromTheChain() {
        // "3" read as "S" (-> 5) sits out of order; the longest increasing chain 1,2,4,5,6 survives
        val syms = numerals(6).map { if (it.text == "3") it.copy(text = "S") else it }
        val a = assertNotNull(RowAnchorFinder.find(syms, 1000))
        assertEquals(6, a.rowCount)
        assertEquals(5, a.found)
        assertEquals(200f + 2 * 80f, a.rowCenters[2], 0.5f) // numeral 3 is interpolated, not trusted
    }

    @Test fun handwritingBesideANumeralDoesNotStretchTheBand() {
        // row 4's handwriting starts right next to its numeral and shares its y
        val near = digit("7", 140f, 200f + 3 * 80f)
        val a = RowAnchorFinder.find(numerals(10) + near, 1000)!!
        assertEquals(10, a.rowCount)
        assertTrue(a.bandRight < 120f, "band right ${a.bandRight} should stay at the numerals")
    }

    @Test fun implausibleNumeralIsNotARowCount() {
        // a stray "99" in the band at the bottom must not stretch the table to 99 rows
        val stray = listOf(digit("9", 94f, 200f + 10 * 80f), digit("9", 108f, 200f + 10 * 80f))
        assertEquals(10, RowAnchorFinder.find(numerals(10) + stray, 1000)!!.rowCount)
    }

    @Test fun printedBorderReadAsBarDoesNotCorruptTheNumeral() {
        // the left border read as "|" beside "12" must not become "112"
        val border = digit("|", 82f, 200f + 11 * 80f)
        val a = RowAnchorFinder.find(numerals(12) + border, 1000)!!
        assertEquals(12, a.rowCount)
        assertEquals(12, a.found)
    }

    @Test fun noDigitsGivesNull() {
        assertNull(RowAnchorFinder.find(listOf(OcrSymbol("A", Box(0f, 0f, 10f, 10f))), 1000))
    }

    /** A whole card: a handwritten rider number above the table bridges small x gaps all the way into the writing. */
    @Test fun writingBridgingTheBandDoesNotSwallowIt() {
        val bridge = listOf(digit("6", 135f, 60f), digit("6", 165f, 60f), digit("3", 195f, 60f), digit("4", 225f, 60f), digit("5", 255f, 60f), digit("7", 283f, 60f))
        val writing = (0 until 12).flatMap { r -> (0 until 6).map { c -> digit("${(r + c) % 10}", 300f + c * 30f, 200f + r * 80f) } }
        val a = assertNotNull(RowAnchorFinder.find(numerals(12) + bridge + writing, 1000))
        assertEquals(12, a.rowCount)
        assertEquals(12, a.found)
    }

    /** A stray digit well left of the numeral column must not leave the band a cluster of one. */
    @Test fun aStrayDigitLeftOfTheBandDoesNotHideIt() {
        val a = assertNotNull(RowAnchorFinder.find(numerals(12) + digit("1", 20f, 500f), 1000))
        assertEquals(12, a.rowCount)
    }

    /** A whole card's footer digits (an address) below the table must not extend the row count. */
    @Test fun footerDigitsOffTheNumeralLineAreNotRows() {
        val footer = listOf(digit("1", 100f, 200f + 10 * 80f + 400f), digit("5", 114f, 200f + 10 * 80f + 400f))
        val a = assertNotNull(RowAnchorFinder.find(numerals(10) + footer, 1000))
        assertEquals(10, a.rowCount)
        assertEquals(10, a.found)
    }
}
