package com.comfort.app.ui.main

// The Library's gallery-style grid: rows that each fill the full width, with every tile as wide as
// its media's shape needs at that row's height (like Samsung Gallery / Google Photos), instead of a
// grid of identical squares.

/** Columns the grid is split into; a tile's width is a whole number of them, which is fine enough
 * that rounding a tile's share of its row is invisible. */
const val JUSTIFIED_COLUMNS = 60

/** One tile's place: [span] of [JUSTIFIED_COLUMNS] and the height of its row (dp). */
data class JustifiedCell(val span: Int, val height: Float)

/** Lays [aspects] (width/height, one per item, in order) out in rows [width] dp wide with [gap] dp
 * between tiles. Items join a row until it would get shorter than [targetHeight], at which point
 * the row is stretched to fill the width exactly. Aspects are clamped to [minAspect]..[maxAspect]
 * (the tile crops the rest), so a very tall or very wide file can't make a sliver or a giant. The
 * last row is stretched too unless that would make it much taller than the others, in which case
 * it keeps the target height and leaves the end of the row empty. */
fun justifiedCells(
    aspects: List<Float>,
    width: Float,
    targetHeight: Float,
    gap: Float,
    maxPerRow: Int = 4,
    minAspect: Float = 0.75f,
    maxAspect: Float = 2f,
): List<JustifiedCell> {
    val clamped = aspects.map { it.coerceIn(minAspect, maxAspect) }
    val out = ArrayList<JustifiedCell>(aspects.size)
    var row = ArrayList<Float>()

    fun fillHeight(r: List<Float>) = (width - gap * (r.size - 1)) / r.sum()

    fun emit(r: List<Float>, height: Float, fullWidth: Boolean) {
        val shares = r.map { it * height }
        val total = if (fullWidth) shares.sum() else width - gap * (r.size - 1)
        val spans = shares.map { (it / total * JUSTIFIED_COLUMNS).toInt().coerceAtLeast(1) }.toMutableList()
        if (fullWidth) {
            // Hand the columns lost to rounding down to the widest tiles, so the row ends flush.
            var missing = JUSTIFIED_COLUMNS - spans.sum()
            val byWidth = shares.indices.sortedByDescending { shares[it] }
            var k = 0
            while (missing > 0) { spans[byWidth[k % byWidth.size]]++; missing--; k++ }
            while (missing < 0) { val i = byWidth[k % byWidth.size]; if (spans[i] > 1) { spans[i]--; missing++ }; k++ }
        }
        spans.forEach { out += JustifiedCell(it, height) }
    }

    for (aspect in clamped) {
        row += aspect
        val h = fillHeight(row)
        if (h <= targetHeight || row.size >= maxPerRow) {
            emit(row, h, fullWidth = true)
            row = ArrayList()
        }
    }
    if (row.isNotEmpty()) {
        val h = fillHeight(row)
        if (h <= targetHeight * 1.3f) emit(row, h, fullWidth = true) else emit(row, targetHeight, fullWidth = false)
    }
    return out
}
