package com.gatar.recipedealer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.ExtractionRun
import com.gatar.recipedealer.api.ExtractionStats
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import java.util.Locale

/** "Statystyki dla nerdów": every extraction run behind a recipe, tokens and cost (online only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionStatsScreen(recipeId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<ExtractionStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recipeId) {
        val r = context.container.backend()?.extractionStats(recipeId) ?: Inbox.NO_BACKEND
        stats = (r as? ApiResult.Ok)?.value
        error = if (isMissingRoute(r)) context.getString(R.string.stats_unsupported) else apiErrorText(context, r)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val s = stats
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (s == null) {
                if (error == null) item { Text(stringResource(R.string.loading)) }
                return@LazyColumn
            }
            item {
                val t = s.totals
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Razem", style = MaterialTheme.typography.titleMedium)
                    Text("Przebiegi: ${t.runs} (AI: ${t.llmRuns})")
                    Text(tokens(t.inputTokens, t.cacheCreationInputTokens, t.cacheReadInputTokens, t.outputTokens))
                    Text("Koszt: ${usd(t.costUsd ?: 0.0)}", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (s.models.isNotEmpty()) {
                item { Text("Modele", style = MaterialTheme.typography.titleMedium) }
                items(s.models, key = { it.model }) { m ->
                    Column {
                        Text("${m.model} · ${m.runs}× · ${m.costUsd?.let(::usd) ?: "brak ceny"}", style = MaterialTheme.typography.titleSmall)
                        Text(tokens(m.inputTokens, m.cacheCreationInputTokens, m.cacheReadInputTokens, m.outputTokens),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Przebiegi (od najstarszego)", style = MaterialTheme.typography.titleMedium) }
            if (s.runs.isEmpty()) item { Text("Brak przebiegów.") }
            items(s.runs, key = { it.runId }) { RunCard(it) }
        }
    }
}

@Composable
private fun RunCard(run: ExtractionRun) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOfNotNull(stepLabel(run.step), purposeLabel(run.purpose)).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall)
                val status = listOfNotNull(statusLabel(run.status), "użyty w przepisie".takeIf { run.applied })
                Text(status.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                    color = if (run.status == "failed" || run.status == "rejected") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary)
                val small = MaterialTheme.typography.bodySmall
                run.startedAt?.let { Text(formatInstant(it), style = small) }
                if (run.validationErrors.isNotEmpty()) Text("Walidacja: ${run.validationErrors.joinToString(", ")}", style = small)
                run.errorType?.let { Text("Błąd: $it", style = small) }
                run.stopReason?.let { Text("stop_reason: $it", style = small) }
                // heuristic runs: no model, zero usage
                run.model?.let { Text(it, style = small) }
                if (run.model != null && (run.inputTokens != null || run.outputTokens != null)) {
                    Text(tokens(run.inputTokens ?: 0, run.cacheCreationInputTokens ?: 0, run.cacheReadInputTokens ?: 0,
                        run.outputTokens ?: 0), style = small)
                }
                val tail = listOfNotNull(run.latencyMs?.let { String.format(Locale.ROOT, "%.1f s", it / 1000.0) },
                    run.costUsd?.takeIf { run.model != null }?.let(::usd))
                if (tail.isNotEmpty()) Text(tail.joinToString(" · "), style = small)
            }
        }
    }
}

private fun tokens(input: Long, cacheWrite: Long, cacheRead: Long, output: Long): String {
    val cache = listOfNotNull("zapis do cache ${n(cacheWrite)}".takeIf { cacheWrite > 0 }, "z cache ${n(cacheRead)}".takeIf { cacheRead > 0 })
    return "Tokeny: wejście ${n(input)}" + (cache.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")") ?: "") +
        ", wyjście ${n(output)}"
}

private fun n(v: Long) = String.format(Locale.forLanguageTag("pl"), "%,d", v)

/** Cents are too coarse: one Haiku run costs a fraction of a cent. */
internal fun usd(v: Double) = String.format(Locale.ROOT, "$%.4f", v)

private fun stepLabel(step: String?) = when (step) {
    "heuristic" -> "Telefon (heurystyka)"
    "haiku" -> "Claude Haiku"
    "sonnet" -> "Claude Sonnet"
    else -> step
}

private fun purposeLabel(purpose: String?) = when (purpose) {
    "pipeline" -> null
    "forced" -> "ponowny odczyt"
    "comparison" -> "porównanie modeli"
    else -> purpose
}

private fun statusLabel(status: String?) = when (status) {
    "succeeded" -> "udany"
    "rejected" -> "niekompletny (walidacja)"
    "awaiting_extraction" -> "nic nie odczytano"
    "failed" -> "błąd wywołania"
    "running" -> "w toku"
    else -> status
}
