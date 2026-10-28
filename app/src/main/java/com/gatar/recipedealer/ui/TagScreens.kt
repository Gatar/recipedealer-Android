package com.gatar.recipedealer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.CategoryChange
import com.gatar.recipedealer.api.Tag
import com.gatar.recipedealer.api.TagCatalog
import com.gatar.recipedealer.api.TagCategory
import com.gatar.recipedealer.api.TagChange
import com.gatar.recipedealer.container
import com.gatar.recipedealer.recipes.TagFilter
import com.gatar.recipedealer.recipes.UsedCategory
import kotlinx.coroutines.launch

/** Muted sage pills of the tag column; a tag is lighter than its category, a selected tag darker. */
private class PillColors(
    val category: Color, val onCategory: Color,
    val tag: Color, val onTag: Color,
    val selected: Color, val onSelected: Color,
)

private val LightPills = PillColors(
    category = Color(0xFFBAC7AD), onCategory = Color(0xFF2C3528),
    tag = Color(0xFFEDF0E9), onTag = Color(0xFF3A4336),
    selected = Color(0xFF9AAE8E), onSelected = Color(0xFF1C2618),
)

private val DarkPills = PillColors(
    category = Color(0xFF2A3327), onCategory = Color(0xFFD5DECE),
    tag = Color(0xFF4D5949), onTag = Color(0xFFE6EBE1),
    selected = Color(0xFF8BA07F), onSelected = Color(0xFF141C11),
)

private val PillShape = RoundedCornerShape(10.dp)

/**
 * The tag column next to the recipe list: only categories and tags some recipe carries, in the tag manager's order;
 * category pills fold (a folded one is faded), tag pills toggle the filter.
 */
@Composable
fun TagColumn(
    used: List<UsedCategory>,
    selected: Set<String>,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    onFold: (String) -> Unit,
    onClear: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pills = if (isSystemInDarkTheme()) DarkPills else LightPills
    LazyColumn(modifier.padding(horizontal = 6.dp)) {
        item {
            // Fixed height with the clear button beside the icon: selecting a tag must not push the list down.
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onManage) {
                    Icon(Icons.Default.Tune, stringResource(R.string.tag_manager), Modifier.size(20.dp))
                }
                if (selected.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.CleaningServices, stringResource(R.string.tags_clear), Modifier.size(20.dp))
                    }
                }
            }
        }
        used.forEach { uc ->
            val folded = uc.category.name in collapsed
            item(key = "c:" + uc.category.name) {
                Pill(uc.category.title, pills.category.copy(alpha = if (folded) 0.5f else 1f), pills.onCategory,
                    MaterialTheme.typography.titleSmall,
                    Modifier.padding(top = 10.dp, bottom = 4.dp).clip(PillShape).clickable { onFold(uc.category.name) })
            }
            if (!folded) {
                items(uc.tags, key = { it.first.id }) { (tag, _) ->
                    val on = tag.id in selected
                    Pill(tag.title, if (on) pills.selected else pills.tag, if (on) pills.onSelected else pills.onTag,
                        MaterialTheme.typography.bodySmall,
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp).clip(PillShape)
                            .toggleable(on, role = Role.Checkbox) { onToggle(tag.id) })
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, textColor: Color, style: TextStyle, modifier: Modifier) {
    Text(text, modifier.fillMaxWidth().background(color, PillShape).padding(horizontal = 8.dp, vertical = 6.dp),
        color = textColor, style = style, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** The recipe's tags as small chips (tag titles, catalog order); ids missing from the catalog are skipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tagIds: List<String>, catalog: TagCatalog, modifier: Modifier = Modifier) {
    val byId = remember(catalog) { catalog.tags }
    val tags = tagIds.mapNotNull { byId[it] }
    if (tags.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { t ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                Text(t.title, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** Checkboxes over the whole catalog; [onConfirm] gets the chosen ids in catalog order. */
@Composable
fun TagPickerDialog(
    catalog: TagCatalog,
    initial: Collection<String>,
    onDismiss: () -> Unit,
    onManage: (() -> Unit)? = null,
    onConfirm: (List<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(initial.toSet()) }
    // nothing to save without tags: offer the tag manager instead
    val noTags = catalog.categories.all { it.tags.isEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_edit)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (noTags) Text(stringResource(R.string.tags_none_defined))
                catalog.categories.filter { it.tags.isNotEmpty() }.forEach { c ->
                    Text(c.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    c.tags.forEach { t ->
                        val on = t.id in chosen
                        Row(
                            Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox) { chosen = if (it) chosen + t.id else chosen - t.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Text(t.title, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!noTags) TextButton(onClick = { onConfirm(catalog.categories.flatMap { it.tags }.map { it.id }.filter { it in chosen }) }) {
                Text(stringResource(R.string.save))
            } else if (onManage != null) TextButton(onClick = onManage) { Text(stringResource(R.string.tag_manager)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private sealed interface TagDialog {
    data object NewCategory : TagDialog
    data class EditCategory(val category: TagCategory) : TagDialog
    data class NewTag(val category: TagCategory) : TagDialog
    data class EditTag(val category: TagCategory, val tag: Tag) : TagDialog
    data class DeleteCategory(val category: TagCategory) : TagDialog
    data class DeleteTag(val category: TagCategory, val tag: Tag) : TagDialog
}

/** Categories and tags with their order (online only; every change answers with the whole catalog). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val catalog by container.tags.catalog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<TagDialog?>(null) }

    fun change(call: suspend (BackendClient) -> ApiResult<TagCatalog>) = scope.launch {
        val client = container.backend()
        if (client == null) {
            error = context.getString(R.string.api_no_backend)
            return@launch
        }
        busy = true
        val r = call(client)
        if (r is ApiResult.Ok) {
            container.tags.put(r.value)
            dialog = null
            // renamed / moved / deleted tags change the recipes' tag ids
            container.appScope.launch { container.recipeSync.sync() }
        }
        error = when {
            r is ApiResult.Failed && r.error == ApiErrors.TAG_EXISTS -> context.getString(R.string.tag_exists)
            isMissingRoute(r) -> context.getString(R.string.tags_unsupported)
            r is ApiResult.Failed && r.code == 404 -> context.getString(R.string.tag_unknown)
            else -> apiErrorText(context, r)
        }
        busy = false
    }
    LaunchedEffect(Unit) { change { client -> container.tags.refresh(client) } }
    // an error of an earlier change does not belong to a newly opened dialog
    LaunchedEffect(dialog) { if (dialog != null) error = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tag_manager)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { dialog = TagDialog.NewCategory }, enabled = !busy) {
                        Icon(Icons.Default.Add, stringResource(R.string.tag_add_category))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.tag_manager_online), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // an open dialog shows the error itself
                if (dialog == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
                if (catalog.categories.isEmpty() && !busy) Text(stringResource(R.string.tag_manager_empty))
            }
            items(catalog.categories, key = { it.name }) { c ->
                val index = catalog.categories.indexOf(c)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        ManagerRow(
                            title = c.title, name = c.name, count = null, header = true, enabled = !busy,
                            canUp = index > 0, canDown = index < catalog.categories.lastIndex,
                            onUp = { change { it.updateCategory(c.name, CategoryChange(position = index - 1)) } },
                            onDown = { change { it.updateCategory(c.name, CategoryChange(position = index + 1)) } },
                            onEdit = { dialog = TagDialog.EditCategory(c) },
                            onDelete = { dialog = TagDialog.DeleteCategory(c) },
                        )
                        HorizontalDivider()
                        if (c.tags.isEmpty()) {
                            Text(stringResource(R.string.tag_empty_category), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        c.tags.forEachIndexed { i, t ->
                            ManagerRow(
                                title = t.title, name = t.name, count = t.recipeCount, header = false, enabled = !busy,
                                canUp = i > 0, canDown = i < c.tags.lastIndex,
                                onUp = { change { it.updateTag(c.name, t.name, TagChange(position = i - 1)) } },
                                onDown = { change { it.updateTag(c.name, t.name, TagChange(position = i + 1)) } },
                                onEdit = { dialog = TagDialog.EditTag(c, t) },
                                onDelete = { dialog = TagDialog.DeleteTag(c, t) },
                            )
                        }
                        TextButton(onClick = { dialog = TagDialog.NewTag(c) }, enabled = !busy, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Icon(Icons.Default.Add, null)
                            Text(stringResource(R.string.tag_add))
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { dialog = TagDialog.NewCategory }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(stringResource(R.string.tag_add_category))
                }
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        TagDialog.NewCategory -> TagEditDialog(stringResource(R.string.tag_category_new), "", "", creating = true, busy = busy,
            error = error, onDismiss = { dialog = null }) { title, name, _ ->
            change { it.createCategory(CategoryChange(name = name, title = title)) }
        }
        is TagDialog.EditCategory -> TagEditDialog(stringResource(R.string.tag_category_edit), d.category.title, d.category.name,
            creating = false, busy = busy, error = error, onDismiss = { dialog = null }) { title, name, _ ->
            change {
                it.updateCategory(d.category.name, CategoryChange(
                    name = name.takeIf { n -> n != d.category.name }, title = title.takeIf { t -> t != d.category.title }))
            }
        }
        is TagDialog.NewTag -> TagEditDialog(stringResource(R.string.tag_new), "", "", creating = true, busy = busy,
            error = error, onDismiss = { dialog = null }) { title, name, _ ->
            change { it.createTag(d.category.name, TagChange(name = name, title = title)) }
        }
        is TagDialog.EditTag -> TagEditDialog(stringResource(R.string.tag_edit), d.tag.title, d.tag.name, creating = false,
            busy = busy, error = error, categories = catalog.categories, category = d.category,
            onDismiss = { dialog = null }) { title, name, category ->
            change {
                it.updateTag(d.category.name, d.tag.name, TagChange(
                    name = name.takeIf { n -> n != d.tag.name }, title = title.takeIf { t -> t != d.tag.title },
                    category = category?.name?.takeIf { n -> n != d.category.name }))
            }
        }
        is TagDialog.DeleteCategory -> ConfirmDialog(
            stringResource(R.string.tag_delete_category_confirm, d.category.title),
            d.category.tags.sumOf { it.recipeCount }, onDismiss = { dialog = null },
        ) { change { it.deleteCategory(d.category.name) } }
        is TagDialog.DeleteTag -> ConfirmDialog(
            stringResource(R.string.tag_delete_confirm, d.tag.title), d.tag.recipeCount, onDismiss = { dialog = null },
        ) { change { it.deleteTag(d.category.name, d.tag.name) } }
    }
}

@Composable
private fun ManagerRow(
    title: String, name: String, count: Int?, header: Boolean, enabled: Boolean, canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = if (header) 12.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = if (header) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(name, count?.let { "($it)" }).joinToString(" "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onUp, enabled = enabled && canUp) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.tag_move_up)) }
        IconButton(onClick = onDown, enabled = enabled && canDown) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.tag_move_down)) }
        IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Default.Edit, stringResource(R.string.recipe_edit)) }
        IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
    }
}

/** Title + name; while creating, the name follows the title until the user edits it. */
@Composable
private fun TagEditDialog(
    heading: String,
    initialTitle: String,
    initialName: String,
    creating: Boolean,
    busy: Boolean,
    error: String?,
    categories: List<TagCategory> = emptyList(),
    category: TagCategory? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, name: String, category: TagCategory?) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var name by remember { mutableStateOf(initialName) }
    var nameTouched by remember { mutableStateOf(!creating) }
    var target by remember { mutableStateOf(category) }
    var menu by remember { mutableStateOf(false) }
    val nameValid = TagFilter.NAME.matches(name) && name.length <= 40
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it; if (!nameTouched) name = TagFilter.slug(it) },
                    label = { Text(stringResource(R.string.tag_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.lowercase(); nameTouched = true },
                    label = { Text(stringResource(R.string.tag_name)) }, singleLine = true, isError = name.isNotEmpty() && !nameValid,
                    supportingText = { Text(stringResource(R.string.tag_name_hint)) }, modifier = Modifier.fillMaxWidth())
                if (!creating && name != initialName) {
                    Text(stringResource(R.string.tag_rename_note), style = MaterialTheme.typography.bodySmall)
                }
                if (categories.isNotEmpty()) {
                    Text(stringResource(R.string.tag_category), style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { menu = true }) {
                            Text(target?.title.orEmpty())
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            categories.forEach { c ->
                                DropdownMenuItem(text = { Text(c.title) }, onClick = { target = c; menu = false })
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), name, target) }, enabled = !busy && title.isNotBlank() && nameValid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(text: String, recipeCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                if (recipeCount > 0) Text(stringResource(R.string.tag_in_use, recipeCount), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
