package com.gatar.recipedealer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilingTest {
    @Test fun shortImageIsOneTile() {
        val (tiles, truncated) = Tiling.tiles(2400, maxTiles = 12)
        assertEquals(listOf(Tile(0, 2400, 0, 2400)), tiles)
        assertFalse(truncated)
    }

    @Test fun longScreenshotTilesCoverEverythingOnce() {
        val (tiles, truncated) = Tiling.tiles(11513, maxTiles = 12)
        assertFalse(truncated)
        assertEquals(listOf(0, 3584, 7168, 10752), tiles.map { it.top })
        assertEquals(11513, tiles.last().bottom)
        assertTrue(tiles.all { it.bottom - it.top <= Tiling.TILE })
        // own ranges partition [0, height)
        assertEquals(0, tiles.first().ownTop)
        tiles.zipWithNext().forEach { (a, b) -> assertEquals(a.ownBottom, b.ownTop) }
        assertEquals(11513, tiles.last().ownBottom)
    }

    @Test fun capTruncates() {
        val (tiles, truncated) = Tiling.tiles(40000, maxTiles = 3)
        assertEquals(3, tiles.size)
        assertTrue(truncated)
    }

    @Test fun lineCutByATileEdgeIsKeptOnceFromTheNeighbour() {
        val (tiles, _) = Tiling.tiles(8000, maxTiles = 12)
        val (a, b) = tiles
        // a line spanning 4070..4120 globally: tile a sees it cut (4070..4096), tile b sees it whole
        val inA = listOf(TextLine("cut", 0, 4070 - a.top, 500, 4096 - a.top))
        val inB = listOf(TextLine("whole", 0, 4070 - b.top, 500, 4120 - b.top))
        val kept = Tiling.own(a, inA) + Tiling.own(b, inB)
        assertEquals(listOf("whole"), kept.map { it.text })
        // a line inside the overlap but well above the split is owned by tile a only
        val l = 3700
        val kept2 = Tiling.own(a, listOf(TextLine("x", 0, l - a.top, 10, l + 40 - a.top))) +
            Tiling.own(b, listOf(TextLine("x", 0, l - b.top, 10, l + 40 - b.top)))
        assertEquals(1, kept2.size)
    }

    @Test fun readingOrderRowsThenLeftToRight() {
        val lines = listOf(
            TextLine("value", 600, 100, 900, 140),
            TextLine("label", 50, 102, 300, 142),
            TextLine("next", 50, 200, 300, 240),
        )
        assertEquals(listOf("label", "value", "next"), Tiling.readingOrder(lines).map { it.text })
    }
}
