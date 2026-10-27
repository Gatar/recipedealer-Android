package com.gatar.recipedealer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.R
import com.gatar.recipedealer.container
import com.gatar.recipedealer.upload.HttpCaptureSender
import com.gatar.recipedealer.upload.UploadQueue
import kotlinx.coroutines.launch

/**
 * Backend base URL, the account (login, password, logout; the token itself is not shown), background mode,
 * auto-send; entry to the tag manager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onTags: () -> Unit, onLogin: () -> Unit, onPassword: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val repo = container.settings
    val initial = remember { repo.current }
    val account by repo.settings.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(initial.baseUrl) }
    var background by remember { mutableStateOf(initial.backgroundMode) }
    var autoSend by remember { mutableStateOf(initial.autoSend) }
    var tabletView by remember { mutableStateOf(initial.tabletView) }
    var health by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val urlValid = url.isBlank() || isAllowedUrl(url.trim())

    fun save() {
        val backendChanged = url.trim().trimEnd('/') != repo.current.baseUrl
        repo.update { it.copy(baseUrl = url, backgroundMode = background, autoSend = autoSend, tabletView = tabletView) }
        if (backendChanged) UploadQueue.resume(context, includeAuthFailures = true)
        Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; container.signOut() }) { Text(stringResource(R.string.settings_logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
            Text(stringResource(R.string.settings_backend), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url, onValueChange = { url = it; health = null },
                label = { Text(stringResource(R.string.settings_url)) },
                supportingText = {
                    Text(if (urlValid) stringResource(R.string.settings_url_hint) else stringResource(R.string.settings_https_only))
                },
                isError = !urlValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = url.isNotBlank() && urlValid,
                    onClick = {
                        health = context.getString(R.string.health_testing)
                        scope.launch {
                            health = when (val h = HttpCaptureSender(url, repo.current.token).health()) {
                                HttpCaptureSender.Health.Ok -> context.getString(R.string.health_ok)
                                HttpCaptureSender.Health.Unauthorized -> context.getString(R.string.health_unauthorized)
                                is HttpCaptureSender.Health.Failed -> context.getString(R.string.health_failed, h.reason)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.settings_test)) }
            }
            health?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    account.authRejected -> stringResource(R.string.settings_rejected)
                    account.token.isBlank() -> stringResource(R.string.settings_logged_out)
                    account.userName.isBlank() -> stringResource(R.string.settings_enrolled)
                    else -> stringResource(R.string.settings_logged_in, account.userName, account.deviceName)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account.needsLogin) {
                    Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
                } else {
                    OutlinedButton(onClick = onPassword) {
                        Text(stringResource(if (account.userName.isBlank()) R.string.password_set else R.string.password_change))
                    }
                    // an enrolled device without a password could not log in again: no logout until it has one
                    if (account.userName.isNotBlank()) {
                        OutlinedButton(onClick = { confirmLogout = true }) { Text(stringResource(R.string.settings_logout)) }
                    }
                }
            }
            HorizontalDivider()
            SwitchRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_desc), background) { background = it }
            SwitchRow(stringResource(R.string.settings_autosend), stringResource(R.string.settings_autosend_desc), autoSend) { autoSend = it }
            SwitchRow(stringResource(R.string.settings_tablet), stringResource(R.string.settings_tablet_desc), tabletView) { tabletView = it }
            if (repo.current.hasBackend) {
                Column(Modifier.fillMaxWidth().clickable(onClick = onTags).padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.tag_manager), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.tag_manager_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { save(); onBack() }, enabled = urlValid, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_save))
            }
        }
    }
}

/** HTTPS only; plain HTTP just for a development server on the emulator host (debug builds allow it). */
fun isAllowedUrl(url: String): Boolean {
    val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (u.host.isNullOrBlank()) return false
    return when (u.scheme?.lowercase()) {
        "https" -> true
        "http" -> u.host == "10.0.2.2" || u.host == "localhost"
        else -> false
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
