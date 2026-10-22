package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSearchTest {
    private fun recipe(id: String, json: String) =
        CachedRecipe(id, "2026-10-22T10:00:00Z", recipe = ApiJson.parseToJsonElement(json).jsonObject)

    private val recipes = listOf(
        recipe("golabki", """{"title":"Gołąbki z ryżem","ingredientGroups":[{"items":["główka kapusty","mięso mielone"]}]}"""),
        recipe("bigos", """{"title":"Bigos","ingredientGroups":[{"items":["kapusta kiszona","kiełbasa"]}]}"""),
        recipe("risotto", """{"title":"Risotto","ingredientGroups":[{"items":["ryż arborio"]}]}"""),
        recipe("nalesniki", """{"title":"Puszyste naleśniki"}"""),
    )

    @Test fun ignoresDiacriticsAndCase() {
        assertEquals(listOf("golabki"), LocalSearch.filter(recipes, "GOLABKI").map { it.recipeId })
        assertEquals(listOf("nalesniki"), LocalSearch.filter(recipes, "nalesniki").map { it.recipeId })
    }

    @Test fun searchesIngredientsTitleHitsFirst() {
        assertEquals(setOf("bigos", "golabki"), LocalSearch.filter(recipes, "kapust").map { it.recipeId }.toSet())
        assertEquals(listOf("golabki", "risotto"), LocalSearch.filter(recipes, "ryz").map { it.recipeId })
        assertEquals(listOf("bigos"), LocalSearch.filter(recipes, "bigos kielbasa").map { it.recipeId })
        assertEquals(listOf("golabki"), LocalSearch.filter(recipes, "golabki kapust").map { it.recipeId })
    }

    @Test fun blankQueryFindsNothing() {
        assertEquals(emptyList<CachedRecipe>(), LocalSearch.filter(recipes, "  "))
    }
}
