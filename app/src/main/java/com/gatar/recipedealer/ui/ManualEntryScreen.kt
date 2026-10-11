package com.gatar.recipedealer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.capture.RecipeDraft
import com.gatar.recipedealer.capture.RecipeEdits
import com.gatar.recipedealer.container
import com.gatar.recipedealer.store.CaptureStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Manual entry (decision D5): typed-in family recipes become a `MANUAL` capture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val container = LocalContext.current.container
    var draft by rememberSaveable(stateSaver = RecipeDraftSaver) { mutableStateOf(RecipeDraft()) }
    val recipe = draft.toRecipe()
    val canSave = recipe.title != null && (recipe.hasIngredients || recipe.hasSteps)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manual_entry)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            RecipeEditor(draft) { draft = it }
            Button(
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                onClick = {
                    val capture = RecipeEdits.manualCapture(
                        UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                        container.processor.clientInfo(), recipe)
                    container.store.saveCapture(capture, CaptureStatus.DRAFT)
                    onSaved(capture.captureId)
                },
            ) { Text(stringResource(R.string.save_recipe)) }
            if (!canSave) Text(stringResource(R.string.manual_hint), Modifier.padding(top = 8.dp))
        }
    }
}
