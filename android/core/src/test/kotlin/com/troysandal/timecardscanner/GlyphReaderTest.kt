package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlyphReaderTest {
    private val reader = GlyphReader(DigitNet.load())
    private val w = 160
    private val h = 100

    private fun paper(level: Float = 225f) = FloatArray(w * h) { level }

    private fun FloatArray.fill(x0: Int, y0: Int, x1: Int, y1: Int, v: Float = 20f) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * w + x] = v
    }

    @Test fun blankCellReadsNothing() {
        val r = reader.read(paper(), w, h)
        assertEquals("", r.text)
        assertEquals(1f, r.confidence)
    }

    @Test fun aThickVerticalStrokeIsAOne() {
        val img = paper().also { it.fill(76, 15, 84, 85) }
        val r = reader.read(img, w, h)
        assertEquals("1", r.text)
        assertTrue(r.confidence > 0.5f, "confidence was ${r.confidence}")
    }

    @Test fun twoStrokesReadAsTwoDigitsLeftToRight() {
        val img = paper().also { it.fill(50, 15, 58, 85); it.fill(100, 15, 108, 85) }
        assertEquals("11", reader.read(img, w, h).text)
    }

    @Test fun printedRulesAreNotWriting() {
        val img = paper().also {
            it.fill(0, 0, 4, h)        // vertical rule on the left edge
            it.fill(0, h - 4, w, h)    // horizontal rule along the bottom
        }
        assertEquals("", reader.read(img, w, h).text)
    }

    @Test fun aThickSlantedRuleThroughTheCropIsErased() {
        // the row grid is only approximate, so the printed rule often lies inside the crop, not on its border
        val img = paper().also {
            it.fill(76, 10, 84, 65)
            for (x in 0 until w) it.fill(x, 78 + x * 8 / w, x + 1, 88 + x * 8 / w)
        }
        assertEquals("1", reader.read(img, w, h).text)
        assertTrue(GlyphReader.pythonParity(DigitNet.load()).read(img, w, h).text.length > 1, "the Python original reads the rule as writing")
    }

    @Test fun aSliverOfTheDividerAtTheCropEdgeIsNotAOne() {
        val img = paper().also { it.fill(76, 15, 84, 85); it.fill(w - 2, 5, w, 45) }
        assertEquals("1", reader.read(img, w, h).text)
    }

    @Test fun aWideDigitIsNotCutInTwo() {
        val img = paper().also { it.fill(50, 22, 112, 78); it.fill(58, 30, 104, 70, v = 225f) } // a ring wider than tall
        assertEquals("0", reader.read(img, w, h).text)
    }

    @Test fun twoStackedDotsBetweenDigitsAreAColon() {
        val img = paper().also {
            it.fill(40, 15, 48, 85)
            it.fill(76, 32, 84, 40); it.fill(76, 60, 84, 68)
            it.fill(112, 15, 120, 85)
        }
        assertEquals("1:1", reader.read(img, w, h).text)
    }

    @Test fun aSlantedColonWhoseDotsDoNotOverlapIsStillAColon() {
        val img = paper().also {
            it.fill(40, 15, 48, 85)
            it.fill(80, 32, 88, 40); it.fill(72, 60, 80, 68) // upper dot entirely right of the lower one
            it.fill(112, 15, 120, 85)
        }
        assertEquals("1:1", reader.read(img, w, h).text)
    }

    @Test fun aLeadingAndATrailingColonAreKept() {
        val img = paper().also {
            it.fill(20, 32, 28, 40); it.fill(18, 60, 26, 68)
            it.fill(76, 15, 84, 85)
            it.fill(130, 32, 138, 40); it.fill(128, 60, 136, 68)
        }
        assertEquals(":1:", reader.read(img, w, h).text)
    }

    @Test fun aSpeckAtTheCropEdgeDoesNotJoinAColon() {
        val img = paper().also {
            it.fill(40, 15, 48, 85)
            it.fill(76, 32, 84, 40); it.fill(76, 60, 84, 68)
            it.fill(78, h - 1, 80, h) // what shows of a rule below the colon
            it.fill(112, 15, 120, 85)
        }
        assertEquals("1:1", reader.read(img, w, h).text)
    }

    @Test fun aSingleDotBetweenDigitsIsAColon() {
        val img = paper().also { it.fill(40, 15, 48, 85); it.fill(76, 70, 84, 78); it.fill(112, 15, 120, 85) }
        assertEquals("1:1", reader.read(img, w, h).text)
    }

    @Test fun aSingleDotBeforeOrAfterTheDigitsIsNotAColon() {
        val img = paper().also { it.fill(20, 70, 28, 78); it.fill(60, 15, 68, 85); it.fill(100, 15, 108, 85); it.fill(135, 70, 143, 78) }
        assertEquals("11", reader.read(img, w, h).text)
    }

    @Test fun dotsAloneAreNotAColon() {
        val img = paper().also { it.fill(76, 22, 84, 30); it.fill(76, 60, 84, 68) }
        assertTrue(':' !in reader.read(img, w, h).text)
    }

    @Test fun aSliverAtTheLeftEdgeIsDroppedToo() {
        val img = paper().also { it.fill(76, 15, 84, 85); it.fill(0, 30, 2, 95) }
        assertEquals("1", reader.read(img, w, h).text)
    }

    @Test fun aTallOneIsNotErasedAsARule() {
        val img = paper().also { it.fill(76, 9, 84, 91) } // 82% of the crop height
        assertEquals("1", reader.read(img, w, h).text)
    }

    @Test fun theSameCellReadsTheSameAtOtherResolutions() {
        val img = paper().also {
            it.fill(40, 15, 48, 85)
            it.fill(76, 32, 84, 40); it.fill(76, 60, 84, 68)
            it.fill(112, 15, 120, 85)
            it.fill(0, h - 5, w, h) // a printed rule along the bottom
        }
        for (scale in listOf(0.5, 1.0, 2.0, 3.0)) {
            val sw = (w * scale).toInt()
            val sh = (h * scale).toInt()
            assertEquals("1:1", reader.read(reader.resizeGray(img, w, h, sw, sh), sw, sh).text, "at ${scale}x")
        }
    }

    @Test fun aStruckThroughValueIsSuspect() {
        val img = paper().also { it.fill(50, 22, 112, 78); it.fill(58, 30, 104, 70, v = 225f) }
        assertEquals(false, reader.read(img, w, h).suspect)
        img.fill(20, 47, 140, 52) // a pen line through the middle of the digit
        assertEquals(true, reader.read(img, w, h).suspect)
    }

    @Test fun aRuleBesideTheWritingIsNotSuspect() {
        val img = paper().also { it.fill(76, 15, 84, 80); it.fill(0, 86, w, 92) }
        val r = reader.read(img, w, h)
        assertEquals("1", r.text)
        assertEquals(false, r.suspect)
    }

    @Test fun aBlobTooWideForOneDigitIsCutAndSuspect() {
        // two rings joined side by side: the even cut is a guess, so the read must say so
        val img = paper().also {
            it.fill(45, 30, 115, 70) // 70 wide, 40 tall: short enough not to look like a printed rule
            it.fill(52, 37, 76, 63, v = 225f); it.fill(84, 37, 108, 63, v = 225f)
        }
        val r = reader.read(img, w, h)
        assertTrue(r.text.length >= 2, "read '${r.text}'")
        assertEquals(true, r.suspect)
    }

    @Test fun aStrokeBrokenInTwoIsNotAColon() {
        val img = paper().also {
            it.fill(40, 15, 48, 85)
            it.fill(100, 12, 108, 46); it.fill(100, 54, 108, 88) // a 1 with a gap, pieces too long to be dots
        }
        assertEquals("11", reader.read(img, w, h).text)
    }

    @Test fun faintMarksBelowThePeakGateAreIgnored() {
        val img = paper().also { it.fill(76, 15, 84, 85, v = 200f) } // barely darker than the paper
        assertEquals("", reader.read(img, w, h).text)
    }

    @Test fun otsuSplitsTwoLevels() {
        val a = FloatArray(1000) { if (it < 800) 0.05f else 0.9f }
        val t = reader.otsu(a)
        assertTrue(t > 0.05f && t < 0.9f, "otsu was $t")
    }
}
