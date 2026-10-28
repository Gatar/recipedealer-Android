package com.gatar.recipedealer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.Notifications
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.CaptureStates
import com.gatar.recipedealer.api.CaptureView
import com.gatar.recipedealer.api.ExtractedCandidate
import com.gatar.recipedealer.api.RecipeMatch
import com.gatar.recipedealer.api.Resolution
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.store.CaptureEntry
import com.gatar.recipedealer.upload.UploadQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Do decyzji": captures of the whole household waiting for a decision or for help. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val items by container.inbox.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() = scope.launch {
        refreshing = true
        error = apiErrorText(context, container.inbox.refresh())
        refreshing = false
    }
    LaunchedEffect(Unit) { refresh() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.inbox_title)) }) }) { padding ->
        PullToRefreshBox(refreshing, onRefresh = { refresh() }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                if (items.isEmpty() && error == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.inbox_empty), textAlign = TextAlign.Center)
                        }
                    }
                }
                items(items, key = { it.captureId }) { v ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(v.captureId) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(v.title ?: v.sourceUrl ?: stringResource(R.string.untitled), style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(v.receivedAt?.let(::formatInstant), backendStatusLabel(context, v.status),
                            v.candidates.size.takeIf { it > 1 }?.let { context.getString(R.string.inbox_candidates, it) })
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = if (v.status == CaptureStates.NEEDS_ATTENTION) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                        v.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** NEW / UPDATE per extracted candidate of one capture (this phone's or another one's). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionScreen(captureId: String, onBack: () -> Unit, onOpenRecipe: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val container = context.container
    val entries by container.store.entries.collectAsStateWithLifecycle()
    val local = entries.firstOrNull { it.id == captureId }
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf(local?.let(::cachedView)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun load() = scope.launch {
        busy = true
        val r = container.inbox.load(captureId)
        if (r is ApiResult.Ok) view = r.value
        error = apiErrorText(context, r)
        busy = false
    }
    fun decide(index: Int, resolution: Resolution) = scope.launch {
        busy = true
        val r = container.inbox.resolve(captureId, index, resolution)
        error = apiErrorText(context, r)
        // after recipe_gone / already_resolved the inbox reloaded the capture
        view = (r as? ApiResult.Ok)?.value ?: container.inbox.items.value.firstOrNull { it.captureId == captureId }
            ?: container.store.get(captureId)?.let(::cachedView) ?: view
        busy = false
        if (r is ApiResult.Ok) {
            Notifications.cancel(context, captureId)
            container.recipeSync.sync()
        }
    }
    var rereadNote by remember { mutableStateOf<String?>(null) }
    /** Forces the LLM extraction, then polls while the screen is open; the StatusWorker notifies if the user leaves. */
    fun reread() = scope.launch {
        busy = true
        rereadNote = null
        val r = container.inbox.forceLlm(captureId)
        error = if (isMissingRoute(r)) context.getString(R.string.reread_unsupported) else apiErrorText(context, r)
        if (r is ApiResult.Ok) {
            Notifications.cancel(context, captureId)
            UploadQueue.scheduleStatus(context, captureId)
            rereadNote = context.getString(R.string.reread_running)
            var v = r.value
            view = v
            var polls = 0
            while (Inbox.isPending(v.status) && polls++ < REREAD_POLLS) {
                delay(REREAD_POLL_MS)
                (container.inbox.load(captureId) as? ApiResult.Ok)?.value?.let { v = it; view = it }
            }
            rereadNote = context.getString(when {
                Inbox.isPending(v.status) -> R.string.reread_still_running
                v.status == CaptureStates.NEEDS_ATTENTION -> R.string.reread_failed
                v.message?.startsWith(NO_COMPLETE_RECIPE) == true -> R.string.reread_no_change
                else -> R.string.reread_done
            })
            if (!Inbox.isPending(v.status)) Notifications.cancel(context, captureId)
            if (v.status == CaptureStates.DONE) container.recipeSync.sync()
        }
        busy = false
    }
    LaunchedEffect(captureId) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.decision_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val v = view
            Text(v?.title ?: local?.title ?: v?.sourceUrl ?: stringResource(R.string.untitled), style = MaterialTheme.typography.headlineSmall)
            if (v != null) {
                Text(backendStatusLabel(context, v.status), style = MaterialTheme.typography.labelLarge,
                    color = if (v.status == CaptureStates.NEEDS_ATTENTION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                backendReasonText(context, v.reason)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                v.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            rereadNote?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (busy && rereadNote == null) Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (v == null) return@Column
            if (Inbox.canReread(v.status, v.reason)) {
                Button(onClick = { reread() }, enabled = !busy) { Text(stringResource(R.string.reread_ai)) }
            }
            if (Inbox.isPending(v.status)) {
                TextButton(onClick = { load() }, enabled = !busy) { Text(stringResource(R.string.refresh_status)) }
            }
            if (v.status == CaptureStates.NEEDS_DECISION) {
                v.candidates.forEach { c ->
                    CandidateCard(captureId, c, enabled = !busy) { decide(c.index, it) }
                }
            }
            if (v.recipes.isNotEmpty()) {
                Text(stringResource(R.string.decision_result), style = MaterialTheme.typography.titleMedium)
                v.recipes.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.title ?: stringResource(R.string.untitled), Modifier.weight(1f))
                        onOpenRecipe?.let { open -> TextButton(onClick = { open(r.recipeId) }) { Text(stringResource(R.string.open_recipe)) } }
                    }
                }
            }
        }
    }
}

private const val NEW = "\u0000new"
private const val REREAD_POLL_MS = 3_000L
/** ~3 min: Haiku and then Sonnet usually answer within a minute. */
private const val REREAD_POLLS = 60
/** The server's message for a forced run that left an applied recipe as it was (state `done`). */
private const val NO_COMPLETE_RECIPE = "Automatic extraction found no complete recipe"

@Composable
private fun CandidateCard(captureId: String, c: ExtractedCandidate, enabled: Boolean, onDecide: (Resolution) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var options by remember { mutableStateOf<List<RecipeMatch>>(emptyList()) }
    var matchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(captureId, c.index, c.title, c.urls) {
        val client = context.container.backend() ?: return@LaunchedEffect
        val r = client.recipeCandidates(c.title, c.urls.firstOrNull())
        options = (r as? ApiResult.Ok)?.value.orEmpty()
        matchError = apiErrorText(context, r)
    }
    // A stored decision wins; else a URL match (score 1.0) is almost certainly the same recipe; else a new one.
    var choice by remember(c, options) {
        mutableStateOf(c.targetRecipeId ?: if (c.resolution == Resolution.NEW) NEW
            else options.firstOrNull { it.matchReason == "URL" }?.recipeId ?: NEW)
    }
    // tags for a new recipe (an update keeps the recipe's own tags)
    val catalog by context.container.tags.catalog.collectAsStateWithLifecycle()
    var tags by remember(c) { mutableStateOf(listOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.decision_counts, c.ingredientCount, c.stepCount), style = MaterialTheme.typography.bodySmall)
            if (c.needsReview) AssistChip(onClick = {}, label = { Text(stringResource(R.string.needs_review)) })
            c.urls.firstOrNull()?.let { url ->
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { runCatching { uri.openUri(url) } })
            }
            c.resolution?.let { Text(stringResource(R.string.decision_stored, it), style = MaterialTheme.typography.bodySmall) }
            matchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            options.forEach { m ->
                val extra = listOfNotNull(m.sourceSummary, stringResource(R.string.decision_same_url).takeIf { m.matchReason == "URL" })
                val label = stringResource(R.string.decision_update, m.title ?: m.recipeId) + extra.joinToString("") { " · $it" }
                OptionRow(label, choice == m.recipeId) { choice = m.recipeId }
            }
            OptionRow(stringResource(R.string.decision_new), choice == NEW) { choice = NEW }
            if (choice == NEW && catalog.categories.any { it.tags.isNotEmpty() }) {
                TagChips(tags, catalog, Modifier.padding(start = 12.dp))
                TextButton(onClick = { picking = true }) {
                    Icon(Icons.AutoMirrored.Default.Label, null)
                    Text(stringResource(R.string.tags_choose), Modifier.padding(start = 8.dp))
                }
            }
            Button(
                enabled = enabled,
                onClick = { onDecide(if (choice == NEW) Resolution.new(tags) else Resolution.update(choice)) },
            ) { Text(stringResource(R.string.decision_confirm)) }
        }
    }
    if (picking) TagPickerDialog(catalog, tags, onDismiss = { picking = false }) { tags = it; picking = false }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the local capture knows from the last status poll (offline fallback). */
private fun cachedView(e: CaptureEntry): CaptureView? = e.state.backendStatus?.let { status ->
    CaptureView(captureId = e.id, status = status, reason = e.state.backendReason, message = e.state.backendMessage,
        recipes = e.state.backendRecipes, candidates = e.state.candidates, title = e.title, sourceUrl = e.capture?.source?.url)
}
