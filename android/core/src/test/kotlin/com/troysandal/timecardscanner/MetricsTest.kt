package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsTest {
    private val truth = listOf(
        listOf("4:21", "", ""),
        listOf("", "12", ""),
    )

    @Test fun perfectMatch() {
        val s = Metrics.score(truth, truth)
        assertTrue(s.rowCountMatches)
        assertEquals(6, s.cellsCorrect)
        assertEquals(2, s.nonBlankCorrect)
        assertEquals(2, s.nonBlankTotal)
        assertEquals(0.0, s.characterErrorRate)
    }

    @Test fun countsWrongAndMissingCells() {
        val got = listOf(listOf("4:2", "", ""), listOf("", "", ""))
        val s = Metrics.score(got, truth)
        assertEquals(4, s.cellsCorrect)
        assertEquals(0, s.nonBlankCorrect)
        assertEquals(0, s.rowsCorrect)
        assertEquals(3.0 / 6.0, s.characterErrorRate, 1e-9) // 1 + 2 edits over 6 chars
    }

    @Test fun contentPastTruthIsInvented() {
        val got = truth + listOf(listOf("1", "", "2"))
        val s = Metrics.score(got, truth)
        assertFalse(s.rowCountMatches)
        assertEquals(2, s.blankTailInvented)
        assertEquals(6, s.cellsTotal)
    }
}
