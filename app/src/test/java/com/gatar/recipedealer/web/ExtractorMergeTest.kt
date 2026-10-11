package com.gatar.recipedealer.web

import com.gatar.recipedealer.capture.LanguageGuess
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.Stage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractorMergeTest {
    private fun output(json: String): JsonObject = CaptureJson.parseToJsonElement(json).jsonObject

    @Test fun jsonLdGraphWithTypeArraysAndIdReferences() {
        val out = output("""
        {"jsonLd": [[{"@context":"https://schema.org","@graph":[
            {"@type":"Person","@id":"#p","name":"Ania &amp; Tomek"},
            {"@type":"ImageObject","@id":"#img","url":"https://e/a.jpg?w=100"},
            {"@type":["Recipe","NewsArticle"],"@id":"https://e/r#recipe","name":"Bigos","author":{"@id":"#p"},
             "image":[{"@id":"#img"},"https://e/a.jpg?w=500","https://e/b.jpg"],
             "recipeYield":["4","4 porcje"],"prepTime":"PT1H30M","cookTime":"P0DT2H","totalTime":"PT0M",
             "recipeIngredient":["1 kg kapusty","<b>500 g</b> mięsa"],
             "recipeInstructions":"Pokrój kapustę.<br>Duś 2 godziny."}]}]],
         "microdata": [], "pluginHits": {}, "heuristic": {"ingredients": [], "instructions": [], "notes": []}}
        """)
        val m = ExtractorMerge.merge(out)
        assertEquals("Bigos", m.recipe.title)
        assertEquals("Ania & Tomek", m.author)
        assertEquals("https://e/r#recipe", m.jsonLdRecipeId)
        assertEquals(listOf("https://e/a.jpg?w=100", "https://e/b.jpg"), m.recipe.images)
        assertEquals("4 porcje", m.recipe.servings?.text)
        assertEquals(4, m.recipe.servings?.count)
        assertEquals(90, m.recipe.times.prepMinutes)
        assertEquals(120, m.recipe.times.cookMinutes)
        assertNull(m.recipe.times.totalMinutes)
        assertEquals(listOf(Group(null, listOf("1 kg kapusty", "500 g mięsa"))), m.recipe.ingredientGroups)
        assertEquals(listOf(Group(null, listOf("Pokrój kapustę.", "Duś 2 godziny."))), m.recipe.stepGroups)
        assertEquals(Stage.JSON_LD, m.provenance["author"])
    }

    @Test fun howToSectionsBecomeNamedGroups() {
        val groups = JsonLd.instructions(CaptureJson.parseToJsonElement("""[
            {"@type":"HowToStep","text":"Rozgrzej piekarnik."},
            {"@type":"HowToSection","name":"Ciasto:","itemListElement":[{"@type":"HowToStep","text":"Zagnieć."},{"@type":"HowToStep","name":"Schłodź."}]},
            {"@type":"HowToStep","text":"Piecz."}]"""))
        assertEquals(listOf(Group(null, listOf("Rozgrzej piekarnik.")), Group("Ciasto", listOf("Zagnieć.", "Schłodź.")),
            Group(null, listOf("Piecz."))), groups)
    }

    @Test fun flatWinnerReplacedByLowerStageGroupsWithSameCount() {
        val out = output("""
        {"jsonLd": [{"@type":"Recipe","name":"X","recipeIngredient":["a","b","c"]}],
         "microdata": [],
         "pluginHits": {"wprm":{"found":true,"recipe":{"recipeIngredient":[{"name":"Ciasto:","items":["▢ a"]},{"name":"Krem","items":["b ,","c"]}]}}},
         "heuristic": {"ingredients": [], "instructions": [], "notes": []}}
        """)
        val m = ExtractorMerge.merge(out)
        assertEquals(listOf(Group("Ciasto", listOf("a")), Group("Krem", listOf("b,", "c"))), m.recipe.ingredientGroups)
        assertEquals(Stage.PLUGIN, m.provenance["ingredients"])
        assertEquals(Stage.JSON_LD, m.provenance["title"])
    }

    @Test fun flatWinnerKeptWhenCountsDiffer() {
        val out = output("""
        {"jsonLd": [{"@type":"Recipe","recipeIngredient":["a","b","c"]}],
         "heuristic": {"ingredients": [{"name":"G","items":["a","b"]}], "instructions": [], "notes": []}}
        """)
        assertEquals(listOf(Group(null, listOf("a", "b", "c"))), ExtractorMerge.merge(out).recipe.ingredientGroups)
    }

    @Test fun ogImageWhenNoStageHasImages() {
        val out = output("""{"ogImage":"https://e/og.jpg","heuristic":{"recipeHeading":"T","ingredients":[{"items":["a"]}],"instructions":[],"notes":[]}}""")
        val m = ExtractorMerge.merge(out)
        assertEquals(listOf("https://e/og.jpg"), m.recipe.images)
        assertEquals(Stage.PAGE, m.provenance["images"])
    }

    @Test fun servingsCountOnlyWhenClear() {
        assertEquals(12, ExtractorMerge.servingsCount("12 porcji"))
        assertEquals(4, ExtractorMerge.servingsCount("4"))
        assertEquals(6, ExtractorMerge.servingsCount("dla 6 osób"))
        assertEquals(2, ExtractorMerge.servingsCount("2 servings"))
        assertNull(ExtractorMerge.servingsCount("4-6 porcji"))
        assertNull(ExtractorMerge.servingsCount("1 tortownica 24 cm"))
        assertNull(ExtractorMerge.servingsCount("12 sztuk"))
    }

    @Test fun durations() {
        assertEquals(20, Durations.text("Prep Time 20 mins"))
        assertEquals(70, Durations.text("1 hr 10 mins"))
        assertEquals(80, Durations.text("1 godz. 20 min"))
        assertEquals(45, Durations.iso("PT45M"))
        assertNull(Durations.iso("bogus"))
    }

    @Test fun textNormalization() {
        assertEquals("Mama’s „szarlotka” ½ kg", Text.norm("Mama&rsquo;s &bdquo;szarlotka&rdquo;\n &frac12;&nbsp;kg"))
        assertEquals("a b", Text.norm("<p>a</p><p>b</p>"))
    }

    @Test fun languageFromContentNotPage() {
        assertEquals("pl", LanguageGuess.guess("Zetrzyj ziemniaki i cebulę, dodaj jajko oraz sól.", "en"))
        assertEquals("en", LanguageGuess.guess("Mix the flour with sugar and bake for 20 minutes.", "pl"))
        assertNull(LanguageGuess.guess("Bigos", "pl"))
    }
}
