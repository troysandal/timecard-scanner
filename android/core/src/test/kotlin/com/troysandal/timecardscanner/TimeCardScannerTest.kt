package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeCardScannerTest {
    private fun model(text: String, confidence: Float, peak: Float = 1f, suspect: Boolean = false, widest: Float = 0.8f) =
        GlyphRead(text, confidence, peak, suspect, widest)

    private val ink = 0.1 // clearly written

    @Test fun aPhotoWithNoNumeralsIsNoTable() {
        val scan = TimeCardScanner.scan(ByteArray(100 * 100) { 0xE0.toByte() }, 100, 100, emptyList())
        assertEquals(emptyList(), scan.rows)
        assertEquals(listOf(ScanFlag.Kind.NO_TABLE), scan.flags.map { it.kind })
    }

    @Test fun aConfidentReadIsTakenWithoutAFlag() {
        val (text, flag) = TimeCardScanner.decide(model("27", 0.99f), "", ink)
        assertEquals("27", text)
        assertNull(flag)
    }

    @Test fun anUnsureReadYieldsToTheRecognizerAndIsFlagged() {
        val (text, flag) = TimeCardScanner.decide(model("32", 0.67f), "37", ink)
        assertEquals("37", text)
        assertEquals(ScanFlag.Kind.UNSURE_VALUE, flag?.first)
    }

    @Test fun anUnsureReadIsKeptWhenTheRecognizerSawNothing() {
        val (text, flag) = TimeCardScanner.decide(model("65", 0.68f), "", ink)
        assertEquals("65", text)
        assertEquals(ScanFlag.Kind.UNSURE_VALUE, flag?.first)
    }

    @Test fun aReadBelowTheFloorIsDropped() {
        val (text, flag) = TimeCardScanner.decide(model("921", 0.3f), "", ink)
        assertEquals("", text)
        assertEquals(ScanFlag.Kind.INK_NOT_READ, flag?.first)
    }

    @Test fun aConfidentReadTheRecognizerContradictsIsFlagged() {
        val (text, flag) = TimeCardScanner.decide(model("1741", 1f), "17:41", ink)
        assertEquals("1741", text)
        assertEquals(ScanFlag.Kind.READERS_DISAGREE, flag?.first)
    }

    @Test fun agreementClearsTheUnsureFlag() {
        val (text, flag) = TimeCardScanner.decide(model("22", 0.82f), "22", ink)
        assertEquals("22", text)
        assertNull(flag)
    }

    @Test fun aSuspectShapeIsFlaggedHoweverConfident() {
        assertEquals(ScanFlag.Kind.SUSPECT_SHAPE, TimeCardScanner.decide(model("23", 1f, suspect = true), "", ink).second?.first)
        assertEquals(ScanFlag.Kind.SUSPECT_SHAPE, TimeCardScanner.decide(model("2", 1f, widest = 1.4f), "", ink).second?.first)
    }

    @Test fun nothingIsInventedWhereThereIsNoInk() {
        val (text, flag) = TimeCardScanner.decide(model("577", 0.6f), "5", 0.005)
        assertEquals("", text)
        assertNull(flag)
    }

    @Test fun aConfidentReadRemovedByTheInkGateLeavesAFlaggedBlank() {
        val (text, flag) = TimeCardScanner.decide(model("1", 1f), "", 0.005)
        assertEquals("", text)
        assertEquals(ScanFlag.Kind.INK_NOT_READ, flag?.first)
    }

    @Test fun grayMatchesTheEvaluationsConversion() {
        val gray = TimeCardScanner.grayFromArgb(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        assertEquals(listOf(0, 255, 76, 150, 29), gray.map { it.toInt() and 0xFF })
    }
}
