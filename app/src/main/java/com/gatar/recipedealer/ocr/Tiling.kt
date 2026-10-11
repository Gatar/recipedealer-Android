package com.gatar.recipedealer.ocr

import com.gatar.recipedealer.model.OcrLine

/** A recognized line in global coordinates of its page / image. */
data class TextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY get() = (top + bottom) / 2
}

/** A horizontal band of a tall image: decoded `[top, bottom)`, owns lines whose center is in `[ownTop, ownBottom)`. */
data class Tile(val top: Int, val bottom: Int, val ownTop: Int, val ownBottom: Int)

/**
 * Tall images (long screenshots, e.g. 1080×11513) are decoded in tiles of 4096 px with 512 px overlap. A line
 * belongs to the tile that holds its vertical center (own ranges split each overlap in half) – nothing lost,
 * nothing duplicated.
 */
object Tiling {
    const val TILE = 4096
    const val OVERLAP = 512

    /** Tiles covering `[0, height)`; [truncated] when [maxTiles] cut the image short. */
    fun tiles(height: Int, maxTiles: Int, tile: Int = TILE, overlap: Int = OVERLAP): Pair<List<Tile>, Boolean> {
        if (height <= tile) return listOf(Tile(0, height, 0, height)) to false
        val step = tile - overlap
        val tops = generateSequence(0) { it + step }.takeWhile { it == 0 || it + overlap < height }.toList()
        val used = tops.take(maxTiles)
        val tiles = used.mapIndexed { i, top ->
            val bottom = minOf(top + tile, height)
            val ownTop = if (i == 0) 0 else top + overlap / 2
            val ownBottom = if (i == tops.lastIndex) height else bottom - overlap / 2
            Tile(top, bottom, ownTop, ownBottom)
        }
        return tiles to (used.size < tops.size)
    }

    /** Lines found in [tile] (tile coordinates) → the ones it owns, in global coordinates. */
    fun own(tile: Tile, lines: List<TextLine>): List<TextLine> =
        lines.map { it.copy(top = it.top + tile.top, bottom = it.bottom + tile.top) }
            .filter { it.centerY >= tile.ownTop && it.centerY < tile.ownBottom }

    /**
     * Top-to-bottom, then left-to-right: lines whose vertical centers fall inside the current row's band form one row
     * (a label and its value on one visual line), each row sorted by `left`.
     */
    fun readingOrder(lines: List<TextLine>): List<TextLine> {
        val rows = mutableListOf<MutableList<TextLine>>()
        for (l in lines.sortedWith(compareBy({ it.top }, { it.left }))) {
            val row = rows.lastOrNull()
            if (row != null && l.centerY in row.minOf { it.top }..row.maxOf { it.bottom } &&
                row.none { it.centerY !in l.top..l.bottom }) row += l else rows += mutableListOf(l)
        }
        return rows.flatMap { r -> r.sortedBy { it.left } }
    }

    fun toOcrLines(lines: List<TextLine>, page: Int) = lines.map { OcrLine(it.text, page, listOf(it.left, it.top, it.right, it.bottom)) }
}
