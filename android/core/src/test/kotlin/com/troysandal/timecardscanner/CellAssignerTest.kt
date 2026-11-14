package com.troysandal.timecardscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CellAssignerTest {
    // 2 rows, Ck column [0,100) then three value columns of 100 px each.
    private val grid = Grid(rowEdges = listOf(0f, 100f, 200f), colEdges = listOf(0f, 100f, 200f, 300f, 400f))

    private fun sym(t: String, cx: Float, cy: Float) =
        OcrSymbol(t, Box(cx - 5, cy - 10, cx + 5, cy + 10))

    @Test fun symbolsGoToTheCellTheirCenterIsIn() {
        val cells = CellAssigner.assign(
            listOf(sym("4", 120f, 50f), sym(":", 135f, 50f), sym("2", 150f, 50f), sym("7", 250f, 150f)),
            grid,
        )
        assertEquals("4:2", cells[0][0].text)
        assertEquals("7", cells[1][1].text)
        assertEquals("", cells[0][2].text)
    }

    @Test fun writingCrossingADividerIsSplitByInkPosition() {
        // "1732": 173 left of the divider at x=200, the 2 lands right of it.
        val cells = CellAssigner.assign(
            listOf(sym("1", 150f, 50f), sym("7", 165f, 50f), sym("3", 185f, 50f), sym("2", 215f, 50f)),
            grid,
        )
        assertEquals("173", cells[0][0].text)
        assertEquals("2", cells[0][1].text)
    }

    @Test fun readsLeftToRightRegardlessOfInputOrder() {
        val cells = CellAssigner.assign(listOf(sym("2", 160f, 50f), sym("1", 130f, 50f)), grid)
        assertEquals("12", cells[0][0].text)
    }

    @Test fun ckColumnAndOutsideAreIgnored() {
        val cells = CellAssigner.assign(listOf(sym("5", 50f, 50f), sym("9", 500f, 50f), sym("9", 150f, 250f)), grid)
        assertTrue(cells.all { row -> row.all { it.text.isEmpty() } })
    }

    @Test fun misreadsAreMappedAndFlagged() {
        val cells = CellAssigner.assign(listOf(sym("S", 150f, 50f)), grid)
        assertEquals("5", cells[0][0].text)
        assertTrue(cells[0][0].flagged)
    }
}
