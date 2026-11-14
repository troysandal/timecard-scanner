package com.troysandal.timecardscanner

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DigitNetTest {
    private val net = DigitNet.load()

    @Test fun matchesPyTorchLogits() {
        val cases = JSONArray(javaClass.getResource("/golden.json")!!.readText())
        assertTrue(cases.length() >= 5)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val input = FloatArray(784) { c.getJSONArray("input").getDouble(it).toFloat() }
            val want = c.getJSONArray("logits")
            val got = net.logits(input)
            for (k in 0 until 10) assertEquals(want.getDouble(k).toFloat(), got[k], 1e-3f, "case $i logit $k")
        }
    }

    @Test fun probabilitiesSumToOne() {
        val p = net.probabilities(FloatArray(784) { if (it % 29 < 3) 1f else 0f })
        assertEquals(1f, p.sum(), 1e-4f)
    }
}
