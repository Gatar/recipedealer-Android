package com.gatar.recipedealer.capture

import com.gatar.recipedealer.CaptureSchema
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.Raw
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.Servings
import com.gatar.recipedealer.model.ShareInfo
import com.gatar.recipedealer.model.Source
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.model.Stage
import com.gatar.recipedealer.model.Times
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeEditsTest {
    private val client = ClientInfo("1.0.0", 36, "Pixel 9")
    private val recipe = Recipe(
        title = "Szarlotka", language = "pl", servings = Servings("12 porcji", 12), times = Times(cookMinutes = 50),
        ingredientGroups = listOf(Group("Ciasto", listOf("320 g mąki", "1 jajko"))),
        stepGroups = listOf(Group(null, listOf("Zagnieść.", "Piec."))), notes = listOf("Uwaga"),
        images = listOf("https://e/a.jpg"))

    @Test fun draftRoundTrip() {
        assertEquals(recipe, RecipeDraft.from(recipe).toRecipe(recipe))
    }

    @Test fun draftParsing() {
        val r = RecipeDraft(
            title = "  Pierogi ", servings = "4", prepMinutes = "x", cookMinutes = "0", totalMinutes = " 90 ",
            ingredientGroups = listOf(GroupDraft("Ciasto:", "500 g mąki\n\n  woda  \n"), GroupDraft("", "  ")),
            stepGroups = listOf(GroupDraft("", "Zagnieć ciasto.\nLep pierogi.")), notes = "",
        ).toRecipe()
        assertEquals("Pierogi", r.title)
        assertEquals(Servings("4", 4), r.servings)
        assertEquals(Times(null, null, 90), r.times)
        assertEquals(listOf(Group("Ciasto", listOf("500 g mąki", "woda"))), r.ingredientGroups)
        assertEquals(listOf(Group(null, listOf("Zagnieć ciasto.", "Lep pierogi."))), r.stepGroups)
        assertEquals("pl", r.language)
    }

    @Test fun editMarksChangedFieldsAsUserAndKeepsRaw() {
        val c = Capture(
            captureId = "3f6c1a52-8a0e-4c5e-9a43-2a1f0d6b7c11", capturedAt = "2026-10-11T10:00:00Z", client = client,
            share = ShareInfo(), source = Source(SourceType.WEB, "https://e/x"), recipe = recipe,
            provenance = mapOf("title" to Stage.JSON_LD, "ingredients" to Stage.HEURISTIC, "steps" to Stage.HEURISTIC,
                "servings" to Stage.HEURISTIC, "cookMinutes" to Stage.JSON_LD, "notes" to Stage.HEURISTIC, "images" to Stage.PAGE),
            raw = Raw(text = "surowy tekst"))
        val draft = RecipeDraft.from(recipe).copy(title = "Szarlotka babci", notes = "")
        val edited = RecipeEdits.applyEdit(c, draft.toRecipe(c.recipe))
        assertEquals("Szarlotka babci", edited.recipe.title)
        assertEquals(Stage.USER, edited.provenance["title"])
        assertNull(edited.provenance["notes"])
        assertEquals(Stage.HEURISTIC, edited.provenance["ingredients"])
        assertEquals(Stage.PAGE, edited.provenance["images"])
        assertEquals("surowy tekst", edited.raw.text)
        assertEquals(1.0, edited.completeness.score, 0.0)
        assertEquals(emptyList<String>(), CaptureSchema.errors(CaptureJson.encodeToString(Capture.serializer(), edited)))
    }

    @Test fun manualCapture() {
        val c = RecipeEdits.manualCapture("3f6c1a52-8a0e-4c5e-9a43-2a1f0d6b7c11", "2026-10-11T10:00:00Z", client,
            RecipeDraft(title = "Kluski babci", ingredientGroups = listOf(GroupDraft("", "1 kg ziemniaków\n2 jajka")),
                stepGroups = listOf(GroupDraft("", "Ugotuj ziemniaki."))).toRecipe())
        assertEquals(SourceType.MANUAL, c.source.type)
        assertEquals(mapOf("title" to Stage.MANUAL, "ingredients" to Stage.MANUAL, "steps" to Stage.MANUAL), c.provenance)
        assertEquals(Stage.MANUAL, c.completeness.bestStage)
        assertEquals(emptyList<String>(), CaptureSchema.errors(CaptureJson.encodeToString(Capture.serializer(), c)))
    }
}
