package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharsetTest {
    @Test fun validTextIsUnchanged() {
        val n = Charset.normalize("9:33:31")
        assertEquals("9:33:31", n.text)
        assertFalse(n.changed)
    }

    @Test fun leadingAndTrailingColonsAreKept() {
        assertEquals(":05", Charset.normalize(":05").text)
        assertEquals("05:", Charset.normalize("05:").text)
    }

    @Test fun misreadsAreMappedNotDropped() {
        val n = Charset.normalize("|S")
        assertEquals("15", n.text)
        assertTrue(n.changed)
        assertEquals("4:21", Charset.normalize("4'21").text)
    }

    @Test fun whitespaceIsRemovedAndUnmappableDropped() {
        assertEquals("12", Charset.normalize("1 2").text)
        assertEquals("12", Charset.normalize("1~2").text)
    }

    @Test fun isValid() {
        assertTrue(Charset.isValid("1:23"))
        assertFalse(Charset.isValid("1a"))
    }
}
