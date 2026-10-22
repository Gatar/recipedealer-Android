package com.gatar.recipedealer.recipes

import java.text.Normalizer

/**
 * Offline fallback of `GET /v1/recipes/search`: every word of the query must occur in the title or an ingredient,
 * ignoring case and Polish diacritics. Title hits first. No typo tolerance (that is the server's job).
 */
object LocalSearch {
    fun filter(recipes: List<CachedRecipe>, query: String): List<CachedRecipe> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return recipes.mapNotNull { r ->
            val title = fold(r.title.orEmpty())
            val text = title + "\n" + fold(r.content().ingredientGroups.flatMap { it.items }.joinToString("\n"))
            if (!words.all { it in text }) null else r to words.count { it in title }
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun fold(s: String): String = Normalizer.normalize(s.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}
