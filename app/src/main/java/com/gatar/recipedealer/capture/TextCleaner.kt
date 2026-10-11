package com.gatar.recipedealer.capture

object TextCleaner {
    private val ZERO_WIDTH = Regex("[​‌‍⁠﻿]")
    private val NBSP = Regex("[   ]")
    private val TRAILING_SPACE = Regex("[ \t]+\n")
    private val BLANK_RUNS = Regex("\n{3,}")

    /** Zero-width characters (incl. a leading U+200B), NBSP, CRLF, trailing spaces, runs of blank lines. */
    fun clean(text: String): String = text
        .replace(ZERO_WIDTH, "")
        .replace(NBSP, " ")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(TRAILING_SPACE, "\n")
        .replace(BLANK_RUNS, "\n\n")
        .trim()
}
