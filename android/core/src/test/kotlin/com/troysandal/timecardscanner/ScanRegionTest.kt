package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanRegionTest {
    @Test fun aBoxInsideThePhotoIsAccepted() {
        val r = assertNotNull(ScanRegion.of(0.1, 0.2, 0.8, 0.6))
        assertEquals(ScanRegion.Pixels(100, 600, 800, 1800), r.pixels(1000, 3000))
    }

    @Test fun rubbishIsRejected() {
        assertNull(ScanRegion.of(Double.NaN, 0.0, 1.0, 1.0))
        assertNull(ScanRegion.of(-0.2, 0.0, 0.5, 0.5))
        assertNull(ScanRegion.of(0.6, 0.0, 0.6, 0.5)) // leaves the photo
        assertNull(ScanRegion.of(0.0, 0.0, 0.02, 0.5)) // too thin
        assertNull(ScanRegion.of(0.0, 0.0, 0.0, 0.0))
    }

    @Test fun roundingNoiseAtTheEdgesIsClamped() {
        val r = assertNotNull(ScanRegion.of(-0.0004, 0.5, 1.0008, 0.5004))
        assertEquals(ScanRegion.Pixels(0, 400, 400, 400), r.pixels(400, 800))
    }

    @Test fun fractionsRoundOutwardSoNothingIsCutOff() {
        val p = assertNotNull(ScanRegion.of(0.333, 0.333, 0.334, 0.334)).pixels(100, 100)
        assertEquals(33, p.left)
        assertEquals(67, p.left + p.width)
    }

    @Test fun theStartingBoxCoversTheTablePlusItsNumeralColumnAndCaptions() {
        val cells = (0 until 2).map { r -> (0 until 3).map { c -> Box(300f + c * 200, 1000f + r * 100, 500f + c * 200, 1100f + r * 100) } }
        val box = assertNotNull(ScanRegion.enclosing(cells, 1000, 3000))
        val px = box.pixels(1000, 3000)
        assertTrue(px.left < 300 - 110)
        assertTrue(px.left + px.width >= 900)
        assertTrue(px.top < 1000 - 100)
        assertTrue(px.top + px.height >= 1200)
    }

    @Test fun theStartingBoxStaysInsideThePhotoAndNeedsGeometry() {
        val box = assertNotNull(ScanRegion.enclosing(listOf(listOf(Box(0f, 0f, 1000f, 3000f))), 1000, 3000))
        assertEquals(ScanRegion.Pixels(0, 0, 1000, 3000), box.pixels(1000, 3000))
        assertNull(ScanRegion.enclosing(listOf(listOf(Box(0f, 0f, 0f, 0f))), 1000, 3000))
    }

    @Test fun movingTheBoxStopsAtThePhotosEdge() {
        val start = BoxDrag.Frac(0.1, 0.1, 0.5, 0.5)
        assertEquals(BoxDrag.Frac(0.5, 0.5, 0.5, 0.5), BoxDrag.move(start, 0.9, 0.9))
        assertEquals(BoxDrag.Frac(0.0, 0.0, 0.5, 0.5), BoxDrag.move(start, -0.9, -0.9))
    }

    @Test fun resizingACornerKeepsTheOppositeCornerAndAMinimumSide() {
        val start = BoxDrag.Frac(0.2, 0.2, 0.4, 0.4)
        val tl = BoxDrag.resize(start, 0, -0.1, -0.1)
        assertEquals(0.1, tl.x, 1e-9); assertEquals(0.6, tl.right, 1e-9); assertEquals(0.6, tl.bottom, 1e-9)
        val br = BoxDrag.resize(start, 2, 0.1, 0.1)
        assertEquals(0.2, br.x, 1e-9); assertEquals(0.7, br.right, 1e-9)
        val squeezed = BoxDrag.resize(start, 2, -0.9, -0.9)
        assertEquals(ScanRegion.MIN_SIDE, squeezed.width, 1e-9)
        assertEquals(ScanRegion.MIN_SIDE, squeezed.height, 1e-9)
        val past = BoxDrag.resize(start, 1, 0.9, 0.0)
        assertEquals(1.0, past.right, 1e-9)
    }
}
