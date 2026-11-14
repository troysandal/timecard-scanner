package com.troysandal.timecardscanner.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureGateTest {
    @Test
    fun secondCaptureIsBusyUntilTheFirstIsTaken() {
        val gate = CaptureGate<String>()
        assertTrue(gate.begin("a"))
        assertFalse(gate.begin("b"))
        assertEquals("a", gate.take())
        assertTrue(gate.begin("c"), "free again once the first capture was taken")
    }

    @Test
    fun takeReturnsAPendingCaptureExactlyOnce() {
        val gate = CaptureGate<String>()
        gate.begin("a")
        assertEquals("a", gate.take())
        assertNull(gate.take(), "a second settle (late error callback after destroy) must find nothing")
    }

    @Test
    fun takeOnAnEmptyGateIsNull() {
        assertNull(CaptureGate<String>().take())
    }

    @Test
    fun failedLaunchFreesTheGateSoTheNextCaptureIsNotBusy() {
        val gate = CaptureGate<String>()
        gate.begin("a")
        // what the plugin does when launching the scanner throws: take the capture and reject it
        val rejected = mutableListOf<String>()
        gate.settle { rejected += it }
        assertEquals(listOf("a"), rejected)
        assertTrue(gate.begin("b"))
    }

    @Test
    fun settleRunsOnlyIfSomethingIsPending() {
        val gate = CaptureGate<String>()
        var calls = 0
        gate.settle { calls++ }
        gate.begin("a")
        gate.settle { calls++ }
        gate.settle { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun aLateCallbackForAnOldCaptureCannotSettleANewerOne() {
        val gate = CaptureGate<String>()
        val a = "a"
        gate.begin(a)
        gate.take() // a is settled by another path
        val b = "b"
        gate.begin(b)
        var rejected = 0
        gate.settle(a) { rejected++ } // a's delayed error callback
        assertEquals(0, rejected, "b must not be rejected by a's callback")
        assertTrue(gate.holds(b))
        gate.settle(b) { rejected++ }
        assertEquals(1, rejected)
    }

    @Test
    fun holdsIsFalseOnceTheCaptureWasSettled() {
        val gate = CaptureGate<String>()
        val a = "a"
        gate.begin(a)
        assertTrue(gate.holds(a))
        gate.take()
        assertFalse(gate.holds(a), "onReady must not launch the scanner for a settled capture")
    }
}
