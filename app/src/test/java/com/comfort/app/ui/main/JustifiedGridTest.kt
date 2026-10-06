package com.comfort.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JustifiedGridTest {
    private val width = 400f
    private val target = 200f
    private val gap = 2f

    private fun rows(cells: List<JustifiedCell>): List<List<JustifiedCell>> {
        val rows = mutableListOf<MutableList<JustifiedCell>>()
        var used = JUSTIFIED_COLUMNS
        for (c in cells) {
            if (used + c.span > JUSTIFIED_COLUMNS) { rows += mutableListOf<JustifiedCell>(); used = 0 }
            rows.last() += c
            used += c.span
        }
        return rows
    }

    @Test
    fun everyFullRowFillsAllColumnsAndSharesOneHeight() {
        val aspects = listOf(0.56f, 0.56f, 1.78f, 1f, 0.75f, 1.33f, 0.56f, 2.4f, 1f, 1f, 0.8f)
        val cells = justifiedCells(aspects, width, target, gap)
        assertEquals(aspects.size, cells.size)
        val all = rows(cells)
        all.dropLast(1).forEach { row ->
            assertEquals(JUSTIFIED_COLUMNS, row.sumOf { it.span })
            assertEquals(1, row.map { it.height }.distinct().size)
            assertTrue(row.first().height <= target)
        }
    }

    @Test
    fun widerMediaGetsMoreColumnsInItsRow() {
        val cells = justifiedCells(listOf(0.75f, 1.5f), width, 150f, gap)
        assertTrue(cells[1].span > cells[0].span)
    }

    @Test
    fun aLoneLastItemKeepsTheTargetHeightInsteadOfBlowingUp() {
        // Three portraits fill a row; a fourth portrait alone would be 400 dp tall stretched.
        val cells = justifiedCells(listOf(0.75f, 0.75f, 0.75f, 0.75f), width, target, gap)
        val last = cells.last()
        assertEquals(target, last.height, 0.01f)
        assertTrue(last.span < JUSTIFIED_COLUMNS)
    }

    @Test
    fun noTileIsSqueezedBelowTheMinimumWidth() {
        // Two portraits then a landscape: the landscape starts a new row instead of squeezing them.
        val aspects = listOf(0.6f, 0.6f, 1.5f, 0.6f, 0.6f, 0.6f, 1.5f, 1f)
        val cells = justifiedCells(aspects, width, 250f, gap, minAspect = 0.6f, maxAspect = 1.78f, minTileWidth = width * 0.3f, maxHeight = width * 0.6f)
        val colWidth = width / JUSTIFIED_COLUMNS
        rows(cells).dropLast(1).forEach { row ->
            row.forEach { assertTrue("tile ${it.span * colWidth}dp", it.span * colWidth >= width * 0.3f - 2f * gap) }
            assertTrue(row.first().height <= width * 0.6f + 0.01f)
        }
    }

    @Test
    fun extremeShapesAreClamped() {
        val cells = justifiedCells(listOf(10f, 0.1f), width, target, gap)
        cells.forEach { assertTrue(it.span >= 1) }
        assertTrue(cells.all { it.height > 0f })
    }
}
