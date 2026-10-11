package com.gatar.recipedealer.ui

import android.widget.Toast
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.container
import com.gatar.recipedealer.upload.HttpCaptureSender
import com.gatar.recipedealer.upload.UploadQueue
import kotlinx.coroutines.launch

/** Backend base URL and token (D3), background mode, auto-send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.settings
    val initial = remember { repo.current }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var background by remember { mutableStateOf(initial.backgroundMode) }
    var autoSend by remember { mutableStateOf(initial.autoSend) }
    var health by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val urlValid = url.isBlank() || isAllowedUrl(url.trim())

    fun save() {
        val backendChanged = url.trim().trimEnd('/') != repo.current.baseUrl || token.trim() != repo.current.token
        repo.update { it.copy(baseUrl = url, token = token, backgroundMode = background, autoSend = autoSend) }
        if (backendChanged) UploadQueue.resume(context, includeAuthFailures = true)
        Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
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
            OutlinedTextField(
                value = token, onValueChange = { token = it; health = null },
                label = { Text(stringResource(R.string.settings_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = url.isNotBlank() && urlValid,
                    onClick = {
                        health = context.getString(R.string.health_testing)
                        scope.launch {
                            health = when (val h = HttpCaptureSender(url, token).health()) {
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
            SwitchRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_desc), background) { background = it }
            SwitchRow(stringResource(R.string.settings_autosend), stringResource(R.string.settings_autosend_desc), autoSend) { autoSend = it }
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
