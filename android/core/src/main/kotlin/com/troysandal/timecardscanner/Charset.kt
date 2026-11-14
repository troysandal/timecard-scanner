package com.troysandal.timecardscanner

/**
 * Maps recognizer output onto the card's real alphabet: digits and `:`.
 * Port of ios/Core/.../Charset.swift — keep the two tables in sync.
 * Non-digit characters are usually a predictable misread, so they are
 * mapped, not dropped; truly unmappable characters are dropped.
 */
object Charset {
    val allowed: Set<Char> = "0123456789:".toSet()

    private val map: Map<Char, Char> = buildMap {
        // looks like 1
        for (c in "|/\\lIi!][") put(c, '1')
        // looks like 0
        for (c in "OoDQU") put(c, '0')
        for (c in "Zz") put(c, '2')
        for (c in "Ss$") put(c, '5')
        for (c in "bG") put(c, '6')
        for (c in "T?") put(c, '7')
        for (c in "B&") put(c, '8')
        for (c in "gq") put(c, '9')
        put('A', '4')
        // colon-like ticks
        for (c in ".;,'’-‘`") put(c, ':')
    }

    data class Normalized(val text: String, val changed: Boolean)

    /** Maps then strips. Whitespace is removed. `changed` mirrors Swift: `out != raw`. */
    fun normalize(raw: String): Normalized {
        val out = StringBuilder()
        for (ch in raw) {
            if (ch.isWhitespace()) continue
            if (ch in allowed) out.append(ch) else map[ch]?.let { out.append(it) }
        }
        val text = out.toString()
        return Normalized(text, text != raw)
    }

    fun isValid(s: String): Boolean = s.all { it in allowed }
}
