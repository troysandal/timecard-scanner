package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkDetectorTest {
    private val w = 300
    private val h = 200

    private fun paper(level: Int = 230) = ByteArray(w * h) { level.toByte() }

    private fun score(img: ByteArray, delta: Int = 45) = InkDetector.score(img, w, h, 0f, 0f, w.toFloat(), h.toFloat(), inset = 10, delta = delta)

    @Test fun blankPaperHasNoInk() = assertEquals(0.0, score(paper()))

    @Test fun uniformShadowIsNotInk() = assertEquals(0.0, score(paper(120)))

    @Test fun handwritingStrokeIsInk() {
        val img = paper()
        for (y in 60 until 140) for (x in 100 until 106) img[y * w + x] = 20 // a vertical-ish stroke
        for (x in 100 until 180) for (y in 100 until 105) img[y * w + x] = 20
        assertTrue(score(img) > 0.01, "stroke should register")
    }

    @Test fun faintPencilNeedsALowerDelta() {
        val img = paper()
        for (y in 60 until 140) for (x in 100 until 106) img[y * w + x] = 190.toByte() // only 40 darker
        assertEquals(0.0, score(img, delta = 45))
        assertTrue(score(img, delta = 25) > 0.0)
    }

    @Test fun aPrintedRuleAlongTheEdgeIsIgnored() {
        val img = paper()
        for (x in 0 until w) for (y in 12 until 16) img[y * w + x] = 20 // horizontal rule inside the inset
        for (y in 0 until h) for (x in 284 until 288) img[y * w + x] = 20 // vertical rule
        assertEquals(0.0, score(img))
    }

    @Test fun slantedRulesAreIgnoredToo() {
        // A photo taken at an angle: the rules cross the cell on a slope, so no pixel row or column is mostly ink.
        val img = paper()
        for (x in 0 until w) { val y0 = 150 + x * 9 / w; for (y in y0 until y0 + 5) img[y * w + x] = 20 }   // 3% slope
        for (y in 0 until h) { val x0 = 281 + y * 8 / h; for (x in x0 until x0 + 5) img[y * w + x] = 20 }   // leaning in at the right
        assertTrue(score(img) < TimeCardScanner.INK_MIN / 2, "slanted rules scored ${score(img)}")
    }

    @Test fun aLoneOneStillCountsAsInk() {
        val img = paper()
        for (y in 50 until 150) for (x in 140 until 148) img[y * w + x] = 20
        assertTrue(score(img) >= TimeCardScanner.INK_MIN, "a 1 scored ${score(img)}")
    }
}
