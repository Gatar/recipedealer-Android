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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.CodeLoginRequest
import com.gatar.recipedealer.api.Emails
import com.gatar.recipedealer.api.JoinRequest
import com.gatar.recipedealer.api.LoginRequest
import com.gatar.recipedealer.api.NewHouseholdRequest
import com.gatar.recipedealer.api.PasswordChange
import com.gatar.recipedealer.api.valueOrNull
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.settings.AppSettings
import kotlinx.coroutines.launch

/** What the login screen does: log in, create a household (its owner), join one with an invitation code. */
private enum class LoginMode(val label: Int, val hint: Int) {
    LOGIN(R.string.login, R.string.login_hint),
    NEW_HOUSEHOLD(R.string.household_new, R.string.household_new_hint),
    JOIN(R.string.household_join, R.string.household_join_hint),
}

/**
 * Login with user name and password (`POST /v1/auth/login`) or — below "LUB" on the same tab — with a single-use
 * login code from an owner (`POST /v1/auth/login-code`: only the code; user, 2026-11-26), a new household
 * (`POST /v1/auth/households`) or joining one with an invitation code (`POST /v1/auth/join`). The server address is
 * fixed ([AppSettings.SERVER_URL]). Success stores the token and goes back. Back without logging in = offline use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val initial = remember { container.settings.current }
    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var householdName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var loginCode by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(initial.userName) }
    var password by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(initial.deviceName.ifBlank { container.defaultDeviceName() }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var codeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val emailWrong = email.isNotBlank() && !Emails.valid(email)
    val ready = device.isNotBlank() && !emailWrong && user.isNotBlank() && password.isNotEmpty() && when (mode) {
        LoginMode.LOGIN -> true
        LoginMode.NEW_HOUSEHOLD -> householdName.isNotBlank() && email.isNotBlank()
        LoginMode.JOIN -> code.isNotBlank()
    }

    fun submit() {
        busy = true
        error = null
        codeError = null
        scope.launch {
            val client = BackendClient(AppSettings.SERVER_URL, token = null)
            val r = when (mode) {
                LoginMode.LOGIN -> client.login(LoginRequest(user.trim(), password, device.trim()))
                LoginMode.NEW_HOUSEHOLD -> client.createHousehold(
                    NewHouseholdRequest(householdName.trim(), user.trim(), password, device.trim(), email.trim()))
                LoginMode.JOIN -> client.join(
                    JoinRequest(code.trim(), user.trim(), password, device.trim(), email.trim().ifEmpty { null }))
            }
            busy = false
            if (r is ApiResult.Ok) {
                container.signIn(device.trim(), r.value)
                Toast.makeText(context, context.getString(R.string.login_ok, r.value.userName), Toast.LENGTH_SHORT).show()
                onDone()
            } else error = if (mode != LoginMode.LOGIN && isMissingRoute(r)) context.getString(R.string.household_unsupported)
                else apiErrorText(context, r)
        }
    }

    /** Only the code: the device name is this device's stored or default one, the server keeps it unique. */
    fun submitCode() {
        busy = true
        error = null
        codeError = null
        scope.launch {
            val name = device.trim().ifEmpty { container.defaultDeviceName() }
            val r = BackendClient(AppSettings.SERVER_URL, token = null).loginWithCode(CodeLoginRequest(loginCode.trim(), name))
            if (r is ApiResult.Ok) {
                // the server may have numbered the name ("Pixel 9 (2)"): store the one it uses
                val actual = BackendClient(AppSettings.SERVER_URL, r.value.token).me().valueOrNull()?.deviceName ?: name
                container.signIn(actual, r.value)
                busy = false
                Toast.makeText(context, context.getString(R.string.login_ok, r.value.userName), Toast.LENGTH_SHORT).show()
                onDone()
            } else {
                busy = false
                codeError = if (isMissingRoute(r)) context.getString(R.string.login_code_unsupported) else apiErrorText(context, r)
            }
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
                LoginMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; error = null; codeError = null },
                        shape = SegmentedButtonDefaults.itemShape(i, LoginMode.entries.size),
                    ) { Text(stringResource(m.label), maxLines = 1, softWrap = false) }
                }
            }
            Text(stringResource(mode.hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode == LoginMode.LOGIN) {
                Text(stringResource(R.string.login_with_password), style = MaterialTheme.typography.titleMedium)
            }
            if (mode == LoginMode.NEW_HOUSEHOLD) {
                OutlinedTextField(
                    value = householdName, onValueChange = { householdName = it },
                    label = { Text(stringResource(R.string.household_name)) },
                    placeholder = { Text(stringResource(R.string.household_name_example)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (mode == LoginMode.JOIN) {
                CodeField(code, { code = it }, stringResource(R.string.household_code))
            }
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text(stringResource(R.string.login_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(password, { password = it }, stringResource(R.string.login_password))
            if (mode == LoginMode.NEW_HOUSEHOLD || mode == LoginMode.JOIN) {
                EmailField(email, { email = it }, required = mode == LoginMode.NEW_HOUSEHOLD)
            }
            OutlinedTextField(
                value = device, onValueChange = { device = it },
                label = { Text(stringResource(R.string.login_device)) },
                supportingText = { Text(stringResource(R.string.login_device_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::submit, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (busy) R.string.login_working else when (mode) {
                    LoginMode.LOGIN -> R.string.login
                    LoginMode.NEW_HOUSEHOLD -> R.string.household_create
                    LoginMode.JOIN -> R.string.household_join_action
                }))
            }
            if (mode == LoginMode.LOGIN) {
                OrSeparator()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.login_with_code), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    InfoTooltip(stringResource(R.string.login_code_tooltip))
                }
                CodeField(loginCode, { loginCode = it }, stringResource(R.string.login_code))
                codeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = ::submitCode, enabled = loginCode.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (busy) R.string.login_working else R.string.login_code_action))
                }
            }
            Text(stringResource(R.string.login_offline_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An invitation or login code: the server ignores case, spaces and dashes; rewriting the text here fights the keyboard. */
@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("ABCD-EFGH") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** ——— LUB ——— */
@Composable
private fun OrSeparator() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(stringResource(R.string.login_or), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** An info icon; tapping it (or a long press) shows [text] in a tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoTooltip(text: String) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
    ) {
        IconButton(onClick = { scope.launch { state.show() } }) {
            Icon(Icons.Outlined.Info, contentDescription = text)
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

/** An e-mail address; [required] for an owner, optional for a member. Shape checked with [Emails.valid]. */
@Composable
fun EmailField(value: String, onChange: (String) -> Unit, required: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    val wrong = value.isNotBlank() && !Emails.valid(value)
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(if (required) R.string.email_required_label else R.string.email_optional_label)) },
        isError = wrong,
        supportingText = { Text(stringResource(if (wrong) R.string.email_wrong else R.string.email_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
        modifier = modifier,
    )
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
