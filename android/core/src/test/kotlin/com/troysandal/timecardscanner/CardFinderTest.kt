package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardFinderTest {
    /** A dark desk (40) with a light card (220) at the given pixel rectangle, with dark writing on the card. */
    private fun photo(w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int): ByteArray {
        val g = ByteArray(w * h) { 40 }
        for (y in top until bottom) for (x in left until right) g[y * w + x] = 220.toByte()
        for (y in top + 10 until top + 14) for (x in left + 10 until right - 10) g[y * w + x] = 30 // a line of writing
        return g
    }

    @Test fun aLightCardOnADarkDeskIsFound() {
        val r = assertNotNull(CardFinder.find(photo(1000, 1400, 200, 300, 800, 1200), 1000, 1400))
        assertTrue(r.x in 0.15..0.2 && r.y in 0.19..0.22, "starts near the card's corner: ${r.x},${r.y}")
        assertTrue(r.x + r.width in 0.8..0.85 && r.y + r.height in 0.85..0.89, "ends near the far corner")
    }

    @Test fun aCardFillingTheFrameIsNotFound() {
        assertNull(CardFinder.find(ByteArray(600 * 800) { 220.toByte() }, 600, 800))
    }

    @Test fun aBlankDarkPhotoIsNotFound() {
        assertNull(CardFinder.find(ByteArray(600 * 800) { 40 }, 600, 800))
    }

    @Test fun aTinyBrightSpeckIsNotACard() {
        assertNull(CardFinder.find(photo(1000, 1400, 480, 680, 520, 720), 1000, 1400))
    }
}
