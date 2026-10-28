package com.gatar.recipedealer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.gatar.recipedealer.Notifications
import com.gatar.recipedealer.capture.RecipeDraft
import com.gatar.recipedealer.capture.RecipeEdits
import com.gatar.recipedealer.inbox.Inbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.R
import com.gatar.recipedealer.capture.CaptureService
import com.gatar.recipedealer.container
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.store.CaptureEntry
import com.gatar.recipedealer.upload.LocalCaptureSender
import com.gatar.recipedealer.upload.UploadQueue
import com.gatar.recipedealer.web.BrowserActivity
import com.gatar.recipedealer.store.CaptureStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureDetailScreen(
    id: String,
    onBack: () -> Unit,
    onDecide: (String) -> Unit = {},
    extraActions: @Composable (CaptureEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.container
    val entries by container.store.entries.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = NullableDraftSaver) { mutableStateOf<RecipeDraft?>(null) }
    val editable = entry?.capture != null && entry.state.status in setOf(CaptureStatus.DRAFT, CaptureStatus.FAILED)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.title ?: stringResource(R.string.untitled), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (entry != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                },
            )
        },
    ) { padding ->
        if (entry == null) return@Scaffold
        val draft = editing
        if (draft != null && entry.capture != null) {
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecipeEditor(draft) { editing = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val c = container.store.get(id)?.capture ?: return@Button
                        container.store.saveCapture(RecipeEdits.applyEdit(c, draft.toRecipe(c.recipe)))
                        editing = null
                    }) { Text(stringResource(R.string.save_recipe)) }
                    OutlinedButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
                }
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBlock(entry)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val c = entry.capture
                if (c != null && editable) {
                    Button(onClick = { container.send(id); Notifications.cancel(context, id) }) { Text(stringResource(R.string.send)) }
                    if (container.settings.current.hasBackend) {
                        OutlinedButton(onClick = { container.sendAsNew(id); Notifications.cancel(context, id) }) {
                            Text(stringResource(R.string.send_as_new))
                        }
                    }
                    OutlinedButton(onClick = { editing = RecipeDraft.from(c.recipe) }) { Text(stringResource(R.string.edit)) }
                }
                if (c != null) {
                    OutlinedButton(onClick = { Exporter.shareJson(context, container.store, id) }) { Text(stringResource(R.string.share_json)) }
                    OutlinedButton(onClick = { Exporter.shareZip(context, container.store, id) }) { Text(stringResource(R.string.export_zip)) }
                }
                if (entry.state.status == CaptureStatus.FAILED && container.processor.loadJob(id) != null) {
                    OutlinedButton(onClick = { CaptureService.restart(context, id) }) { Text(stringResource(R.string.reprocess)) }
                }
                if (entry.type == SourceType.WEB && entry.state.status != CaptureStatus.EXTRACTING &&
                    container.processor.webUrl(id) != null) {
                    OutlinedButton(onClick = { context.startActivity(BrowserActivity.intent(context, id)) }) {
                        Text(stringResource(R.string.open_page))
                    }
                }
                if (entry.state.status == CaptureStatus.SENT && entry.state.destination != LocalCaptureSender.DESTINATION) {
                    val waiting = entry.state.backendStatus in Inbox.WAITING
                    if (Inbox.canReread(entry.state.backendStatus, entry.state.backendReason)) {
                        // the decision screen runs it and waits for the result
                        Button(onClick = { onDecide(id) }) { Text(stringResource(R.string.reread_ai)) }
                    } else if (waiting || entry.state.backendRecipes.isNotEmpty()) {
                        Button(onClick = { onDecide(id) }) { Text(stringResource(R.string.decide)) }
                    }
                    OutlinedButton(onClick = { UploadQueue.scheduleStatusNow(context, id) }) { Text(stringResource(R.string.refresh_status)) }
                }
                extraActions(entry)
            }
            entry.capture?.let { RecipeView(it, container.store.filesDir(id)) }
            entry.capture?.let { RawJson(it) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; container.store.delete(id); onBack() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatusBlock(entry: CaptureEntry) {
    val context = LocalContext.current
    val c = entry.capture
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(statusText(entry), color = statusColor(entry.state.status), style = MaterialTheme.typography.labelLarge)
        entry.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        entry.state.backendStatus?.let { st ->
            val titles = entry.state.backendTitles.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ") ?: ""
            Text(stringResource(R.string.backend_status, backendStatusLabel(context, st)) + titles, style = MaterialTheme.typography.bodySmall)
        }
        backendReasonText(context, entry.state.backendReason)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        entry.state.backendMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (c != null) {
            val source = listOfNotNull(c.source.type.name, c.source.siteName, c.share.sourceApp).joinToString(" · ")
            Text(source, style = MaterialTheme.typography.bodySmall)
            c.source.url?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            val cc = c.completeness
            Text(
                "Kompletność ${(cc.score * 100).toInt()}% · najlepszy etap: ${cc.bestStage}" +
                    (c.provenance.takeIf { it.isNotEmpty() }?.entries?.joinToString(prefix = "\n", separator = ", ") { "${it.key}: ${it.value}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (c.diagnostics.errors.isNotEmpty()) {
                Text(c.diagnostics.errors.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun RecipeView(c: Capture, filesDir: File? = null) {
    val r = c.recipe
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecipeContent(r)
        c.raw.text?.takeIf { !r.hasIngredients && !r.hasSteps }?.let { Section("Tekst") { Text(it) } }
        c.raw.ocr?.text?.takeIf { it.isNotBlank() }?.let { Section("Tekst z OCR") { Text(it, style = MaterialTheme.typography.bodySmall) } }
        c.raw.pdfText?.takeIf { it.isNotBlank() }?.let { Section("Tekst z PDF") { Text(it, style = MaterialTheme.typography.bodySmall) } }
        if (c.attachments.isNotEmpty()) {
            Section("Załączniki") {
                c.attachments.forEach { a ->
                    Text("${a.name} · ${a.role} · ${a.width ?: "?"}×${a.height ?: "?"} · ${(a.bytes ?: 0) / 1024} KB",
                        style = MaterialTheme.typography.bodySmall)
                    if (filesDir != null && a.mimeType.startsWith("image/")) Thumbnail(File(filesDir, a.name))
                }
            }
        }
    }
}

/** Title, servings / times, ingredient and step groups, notes: the capture preview and the recipe detail. */
@Composable
fun RecipeContent(r: Recipe, showTitle: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) r.title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
        val meta = listOfNotNull(
            r.servings?.text?.let { "Porcje: $it" },
            r.times.prepMinutes?.let { "Przygotowanie: $it min" },
            r.times.cookMinutes?.let { "Gotowanie: $it min" },
            r.times.totalMinutes?.let { "Razem: $it min" },
            r.language?.let { "Język: $it" },
        )
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        if (r.ingredientGroups.isNotEmpty()) Section("Składniki") { Groups(r.ingredientGroups, numbered = false) }
        if (r.stepGroups.isNotEmpty()) Section("Przygotowanie") { Groups(r.stepGroups, numbered = true) }
        if (r.notes.isNotEmpty()) Section("Wskazówki") { r.notes.forEach { Text("• $it") } }
    }
}

@Composable
private fun Thumbnail(file: File) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 400) }
    }
    bitmap?.let { Image(it, null, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit, alignment = Alignment.TopStart) }
}

internal val NullableDraftSaver = Saver<RecipeDraft?, String>(
    save = { d -> d?.let { CaptureJson.encodeToString(RecipeDraft.serializer(), it) } ?: "" },
    restore = { s -> s.takeIf { it.isNotEmpty() }?.let { CaptureJson.decodeFromString(RecipeDraft.serializer(), it) } },
)

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Groups(groups: List<Group>, numbered: Boolean) {
    groups.forEach { g ->
        g.name?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        g.items.forEachIndexed { i, item -> Text(if (numbered) "${i + 1}. $item" else "• $item") }
    }
}

@Composable
private fun RawJson(c: Capture) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(stringResource(R.string.raw_json)) }
    if (open) {
        val json = remember(c) { CaptureJson.encodeToString(Capture.serializer(), c) }
        SelectionContainer {
            Text(json.take(50_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}
