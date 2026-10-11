package com.gatar.recipedealer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.capture.GroupDraft
import com.gatar.recipedealer.capture.RecipeDraft
import com.gatar.recipedealer.model.CaptureJson

/** Keeps the form across configuration changes and process death. */
val RecipeDraftSaver = Saver<RecipeDraft, String>(
    save = { CaptureJson.encodeToString(RecipeDraft.serializer(), it) },
    restore = { CaptureJson.decodeFromString(RecipeDraft.serializer(), it) },
)

/** The recipe form of the preview (editing) and of manual entry. Items: one per line. */
@Composable
fun RecipeEditor(draft: RecipeDraft, onChange: (RecipeDraft) -> Unit) {
    val sentences = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft.title, { onChange(draft.copy(title = it)) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_title)) }, keyboardOptions = sentences)
        OutlinedTextField(draft.servings, { onChange(draft.copy(servings = it)) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_servings)) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.prepMinutes, { onChange(draft.copy(prepMinutes = it.filter(Char::isDigit))) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.field_prep)) }, suffix = { Text("min") }, singleLine = true,
                keyboardOptions = number)
            OutlinedTextField(draft.cookMinutes, { onChange(draft.copy(cookMinutes = it.filter(Char::isDigit))) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.field_cook)) }, suffix = { Text("min") }, singleLine = true,
                keyboardOptions = number)
            OutlinedTextField(draft.totalMinutes, { onChange(draft.copy(totalMinutes = it.filter(Char::isDigit))) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.field_total)) }, suffix = { Text("min") }, singleLine = true,
                keyboardOptions = number)
        }
        GroupsEditor(stringResource(R.string.section_ingredients), stringResource(R.string.field_ingredients_hint),
            draft.ingredientGroups) { onChange(draft.copy(ingredientGroups = it)) }
        GroupsEditor(stringResource(R.string.section_steps), stringResource(R.string.field_steps_hint),
            draft.stepGroups) { onChange(draft.copy(stepGroups = it)) }
        OutlinedTextField(draft.notes, { onChange(draft.copy(notes = it)) }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
            label = { Text(stringResource(R.string.section_notes)) }, keyboardOptions = sentences)
    }
}

@Composable
private fun GroupsEditor(title: String, hint: String, groups: List<GroupDraft>, onChange: (List<GroupDraft>) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    groups.forEachIndexed { i, g ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().then(Modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(g.name, { v -> onChange(groups.toMutableList().also { it[i] = g.copy(name = v) }) },
                        Modifier.weight(1f).then(Modifier), singleLine = true,
                        label = { Text(stringResource(R.string.field_group_name)) })
                    if (groups.size > 1) {
                        IconButton(onClick = { onChange(groups.toMutableList().also { it.removeAt(i) }) }) {
                            Icon(Icons.Default.Close, stringResource(R.string.remove_group))
                        }
                    }
                }
                OutlinedTextField(g.text, { v -> onChange(groups.toMutableList().also { it[i] = g.copy(text = v) }) },
                    Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text(hint) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
        }
    }
    TextButton(onClick = { onChange(groups + GroupDraft()) }) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(R.string.add_group))
    }
}
