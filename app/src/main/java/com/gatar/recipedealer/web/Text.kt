package com.gatar.recipedealer.web

/** Text normalization for values coming from pages (JSON-LD often carries entities and stray tags). */
object Text {
    private val TAG = Regex("<[^>]{1,200}>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z][a-zA-Z0-9]*);")
    private val SPACE = Regex("[\\s  ​]+")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–",
        "mdash" to "—", "hellip" to "…", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "deg" to "°",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bdquo" to "„", "times" to "×",
    )

    fun decodeEntities(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e]
        } ?: m.value
    }

    /** Strips tags, decodes entities, collapses whitespace. */
    fun norm(s: String): String = SPACE.replace(decodeEntities(if ('<' in s) TAG.replace(s, " ") else s), " ").trim()
}

/** Cooking times. Never computed from each other. */
object Durations {
    private val ISO = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$",
        RegexOption.IGNORE_CASE)
    private val HOURS = Regex("(\\d+(?:[.,]\\d+)?)\\s*(h|hrs?|hours?|godz\\w*|godzin\\w*)\\b", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("(\\d+)\\s*(m|mins?|minut\\w*|minutes?)\\b", RegexOption.IGNORE_CASE)

    /** ISO 8601 duration (`PT1H30M`) → minutes; 0 or unparseable → null. */
    fun iso(s: String?): Int? {
        val m = ISO.matchEntire(s?.trim() ?: return null) ?: return null
        val (d, h, min, sec) = m.destructured
        val total = (d.toDoubleOrNull() ?: 0.0) * 1440 + (h.toDoubleOrNull() ?: 0.0) * 60 + (min.toDoubleOrNull() ?: 0.0) +
            (sec.toDoubleOrNull() ?: 0.0) / 60
        return Math.round(total).toInt().takeIf { it > 0 }
    }

    /** Plugin markup text ("Prep Time 20 mins", "1 hr 10 mins", "1 godz. 20 min") → minutes. */
    fun text(s: String?): Int? {
        s ?: return null
        iso(s)?.let { return it }
        val h = HOURS.find(s)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val m = MINUTES.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Math.round(h * 60 + m).toInt().takeIf { it > 0 }
    }
}
