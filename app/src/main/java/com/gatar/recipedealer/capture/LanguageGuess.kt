package com.gatar.recipedealer.capture

import com.gatar.recipedealer.model.Recipe

/**
 * Content language of a recipe: `pl` or `en`, or null. Decided on the recipe text, not on the page `lang`
 * (AI-chat share pages say `en` for Polish answers); the page language only breaks a tie.
 */
object LanguageGuess {
    private val PL_LETTERS = Regex("[ąćęłńśźżĄĆĘŁŃŚŹŻ]")
    private val WORD = Regex("\\p{L}+")
    private val PL_WORDS = setOf(
        "i", "w", "z", "na", "do", "ze", "oraz", "lub", "się", "minut", "łyżka", "łyżki", "łyżeczka", "łyżeczki",
        "szklanka", "szklanki", "sól", "pieprz", "jajko", "jajka", "mąki", "cukru", "masła", "dodaj", "wymieszaj",
        "piecz", "gotuj", "około", "ok", "przez", "aż", "składniki", "przygotowanie", "porcji", "szczypta", "olej",
    )
    private val EN_WORDS = setOf(
        "the", "and", "with", "of", "to", "in", "or", "for", "minutes", "cup", "cups", "tbsp", "tsp", "tablespoon",
        "teaspoon", "salt", "pepper", "egg", "eggs", "flour", "sugar", "butter", "add", "mix", "bake", "cook", "until",
        "about", "ingredients", "instructions", "servings", "into", "pinch", "oil",
    )

    fun of(recipe: Recipe, pageLang: String? = null): String? = guess(
        buildString {
            recipe.title?.let { appendLine(it) }
            recipe.ingredientGroups.forEach { g -> g.items.forEach(::appendLine) }
            recipe.stepGroups.forEach { g -> g.items.forEach(::appendLine) }
        },
        pageLang,
    )

    fun guess(text: String, pageLang: String? = null): String? {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < 3) return null
        val pl = words.count { it in PL_WORDS } + 2 * PL_LETTERS.findAll(text).count().coerceAtMost(words.size)
        val en = words.count { it in EN_WORDS }
        val page = pageLang?.substringBefore('-')?.lowercase()?.takeIf { it == "pl" || it == "en" }
        return when {
            pl > en -> "pl"
            en > pl -> "en"
            else -> page
        }
    }
}
