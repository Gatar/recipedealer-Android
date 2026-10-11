package com.gatar.recipedealer.ui

import androidx.compose.foundation.horizontalScroll
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
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.store.CaptureEntry
import com.gatar.recipedealer.web.BrowserActivity
import com.gatar.recipedealer.store.CaptureStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureDetailScreen(id: String, onBack: () -> Unit, extraActions: @Composable (CaptureEntry) -> Unit = {}) {
    val context = LocalContext.current
    val container = context.container
    val entries by container.store.entries.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
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
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBlock(entry)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val c = entry.capture
                if (c != null && entry.state.status in setOf(CaptureStatus.DRAFT, CaptureStatus.FAILED)) {
                    Button(onClick = { container.send(id) }) { Text(stringResource(R.string.send)) }
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
                extraActions(entry)
            }
            entry.capture?.let { RecipeView(it) }
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
    val c = entry.capture
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(statusText(entry), color = statusColor(entry.state.status), style = MaterialTheme.typography.labelLarge)
        entry.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
fun RecipeView(c: Capture) {
    val r = c.recipe
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        r.title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
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
        c.raw.text?.takeIf { !r.hasIngredients && !r.hasSteps }?.let { Section("Tekst") { Text(it) } }
        c.raw.ocr?.text?.takeIf { it.isNotBlank() }?.let { Section("Tekst z OCR") { Text(it, style = MaterialTheme.typography.bodySmall) } }
        c.raw.pdfText?.takeIf { it.isNotBlank() }?.let { Section("Tekst z PDF") { Text(it, style = MaterialTheme.typography.bodySmall) } }
        if (c.attachments.isNotEmpty()) {
            Section("Załączniki") {
                c.attachments.forEach { Text("${it.name} · ${it.role} · ${it.width ?: "?"}×${it.height ?: "?"} · ${(it.bytes ?: 0) / 1024} KB") }
            }
        }
    }
}

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
