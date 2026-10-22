package com.gatar.recipedealer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.R
import com.gatar.recipedealer.container
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.store.CaptureEntry
import com.gatar.recipedealer.store.CaptureStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureListScreen(
    onOpen: (String) -> Unit,
    onSettings: (() -> Unit)? = null,
    onManual: (() -> Unit)? = null,
) {
    val container = LocalContext.current.container
    val entries by container.store.entries.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_captures)) },
                actions = {
                    onManual?.let {
                        IconButton(onClick = it) { Icon(Icons.Default.Edit, stringResource(R.string.manual_entry)) }
                    }
                    onSettings?.let {
                        IconButton(onClick = it) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.list_empty), textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(entries, key = { it.id }) { entry ->
                    CaptureRow(entry, onClick = { onOpen(entry.id) }, onRetry = { container.send(entry.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(entry: CaptureEntry, onClick: () -> Unit, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sourceIcon(entry.type), contentDescription = entry.type?.name)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatInstant(entry.capturedAt) + " · " + statusText(entry),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(entry.state.status),
            )
            entry.state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (entry.state.status == CaptureStatus.FAILED && entry.capture != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

fun sourceIcon(type: SourceType?) = when (type) {
    SourceType.WEB -> Icons.Default.Language
    SourceType.IMAGE -> Icons.Default.Image
    SourceType.PDF -> Icons.Default.PictureAsPdf
    SourceType.MANUAL -> Icons.Default.Edit
    SourceType.TEXT, null -> Icons.AutoMirrored.Default.Notes
}

@Composable
fun statusText(entry: CaptureEntry): String = when (entry.state.status) {
    CaptureStatus.EXTRACTING -> stringResource(R.string.status_extracting)
    CaptureStatus.DRAFT -> stringResource(R.string.status_draft)
    CaptureStatus.QUEUED -> stringResource(R.string.status_queued)
    CaptureStatus.SENT -> if (entry.state.destination == "local") stringResource(R.string.status_sent_local)
        else stringResource(R.string.status_sent) + (entry.state.backendStatus?.let { " · " + backendStatusLabel(LocalContext.current, it) } ?: "")
    CaptureStatus.FAILED -> stringResource(R.string.status_failed)
}

@Composable
fun statusColor(status: CaptureStatus) = when (status) {
    CaptureStatus.FAILED -> MaterialTheme.colorScheme.error
    CaptureStatus.SENT -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
