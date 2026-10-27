package com.gatar.recipedealer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.LoginRequest
import com.gatar.recipedealer.api.PasswordChange
import com.gatar.recipedealer.api.RegisterRequest
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import kotlinx.coroutines.launch

/**
 * Login or registration (`POST /v1/auth/login` / `register`): server address, user, password, device name (+ the
 * household registration code). Success stores the token and goes back. Back without logging in = offline use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val initial = remember { container.settings.current }
    var register by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var user by remember { mutableStateOf(initial.userName) }
    var password by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(initial.deviceName.ifBlank { container.defaultDeviceName() }) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val urlValid = isAllowedUrl(url.trim())
    val ready = urlValid && user.isNotBlank() && password.isNotEmpty() && device.isNotBlank() && (!register || code.isNotBlank())

    fun submit() {
        busy = true
        error = null
        scope.launch {
            val baseUrl = url.trim().trimEnd('/')
            val client = BackendClient(baseUrl, token = null)
            val r = if (register) client.register(RegisterRequest(user.trim(), password, device.trim(), code.trim()))
            else client.login(LoginRequest(user.trim(), password, device.trim()))
            busy = false
            if (r is ApiResult.Ok) {
                container.signIn(baseUrl, device.trim(), r.value)
                Toast.makeText(context, context.getString(R.string.login_ok, r.value.userName), Toast.LENGTH_SHORT).show()
                onDone()
            } else error = apiErrorText(context, r)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to R.string.login, true to R.string.register).forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = register == mode,
                        onClick = { register = mode; error = null },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(stringResource(label)) }
                }
            }
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text(stringResource(R.string.settings_url)) },
                isError = url.isNotBlank() && !urlValid,
                supportingText = if (url.isNotBlank() && !urlValid) {
                    { Text(stringResource(R.string.settings_https_only)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text(stringResource(R.string.login_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(password, { password = it }, stringResource(R.string.login_password))
            OutlinedTextField(
                value = device, onValueChange = { device = it },
                label = { Text(stringResource(R.string.login_device)) },
                supportingText = { Text(stringResource(R.string.login_device_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (register) PasswordField(code, { code = it }, stringResource(R.string.login_code))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::submit, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (busy) R.string.login_working else if (register) R.string.register else R.string.login))
            }
            Text(stringResource(R.string.login_offline_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** `PUT /v1/me/password`: sets the first password (device enrolled by the admin) or changes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val firstPassword = remember { container.settings.current.userName.isBlank() }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mismatch = repeat.isNotEmpty() && repeat != new

    fun submit() {
        val client = container.backend() ?: run { error = apiErrorText(context, Inbox.NO_BACKEND); return }
        busy = true
        error = null
        scope.launch {
            val r = client.setPassword(PasswordChange(new, current.ifEmpty { null }))
            busy = false
            if (r is ApiResult.Ok) {
                Toast.makeText(context, R.string.password_saved, Toast.LENGTH_SHORT).show()
                onBack()
            } else error = apiErrorText(context, r)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (firstPassword) R.string.password_set else R.string.password_change)) },
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
            PasswordField(current, { current = it }, stringResource(R.string.password_current))
            PasswordField(new, { new = it }, stringResource(R.string.password_new))
            PasswordField(repeat, { repeat = it }, stringResource(R.string.password_repeat),
                error = if (mismatch) stringResource(R.string.password_mismatch) else null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::submit, enabled = new.isNotEmpty() && repeat == new && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (busy) R.string.login_working else R.string.save))
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
