package com.gatar.recipedealer.ui

import android.Manifest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.gatar.recipedealer.MainActivity
import com.gatar.recipedealer.container
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.model.Stage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Manual entry → MANUAL capture → preview → correction marks the field as edited by the user. */
@RunWith(AndroidJUnit4::class)
class ManualEntryTest {
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private fun field(label: String) = rule.onNode(hasText(label) and hasSetTextAction())

    @Test fun enterEditAndKeepProvenance() {
        val store = rule.activity.container.store
        val before = store.entries.value.map { it.id }.toSet()
        rule.onNodeWithContentDescription("Dodaj ręcznie").performClick()
        field("Tytuł").performTextInput("Kluski śląskie babci")
        field("Porcje (np. 4 porcje)").performTextInput("4 porcje")
        field("Składniki – jeden w linii").performScrollTo().performTextInput("1 kg ziemniaków\nmąka ziemniaczana")
        field("Kroki – jeden w linii").performScrollTo().performTextInput("Ugotuj ziemniaki.\nUformuj kluski.")
        rule.onNodeWithText("Zapisz przepis").performScrollTo().performClick()
        rule.waitForIdle()

        val id = store.entries.value.map { it.id }.first { it !in before }
        val c = store.get(id)!!.capture!!
        assertEquals(SourceType.MANUAL, c.source.type)
        assertEquals(listOf("1 kg ziemniaków", "mąka ziemniaczana"), c.recipe.ingredientGroups.single().items)
        assertEquals(4, c.recipe.servings?.count)
        assertEquals(Stage.MANUAL, c.provenance["title"])

        rule.onNodeWithText("Popraw").performClick()
        field("Tytuł").performTextClearance()
        field("Tytuł").performTextInput("Kluski śląskie")
        rule.onNodeWithText("Zapisz przepis").performScrollTo().performClick()
        rule.waitForIdle()
        val edited = store.get(id)!!.capture!!
        assertEquals("Kluski śląskie", edited.recipe.title)
        assertEquals(Stage.USER, edited.provenance["title"])
        assertEquals(Stage.MANUAL, edited.provenance["ingredients"])
        // top bar + recipe view
        assertEquals(2, rule.onAllNodesWithText("Kluski śląskie").fetchSemanticsNodes().size)
        store.delete(id)
    }
}
