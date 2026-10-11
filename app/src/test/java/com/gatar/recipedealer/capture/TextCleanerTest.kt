package com.gatar.recipedealer.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {
    @Test fun cleansSharedText() {
        val raw = "​Szarlotka\r\n\r\n\r\n\r\nSkładniki :\r\n1 kg jabłek  \r\n‍2 jajka\n\n\n"
        assertEquals("Szarlotka\n\nSkładniki :\n1 kg jabłek\n2 jajka", TextCleaner.clean(raw))
    }

    @Test fun keepsSingleBlankLines() = assertEquals("a\n\nb\nc", TextCleaner.clean("a\n\nb\nc"))
}
