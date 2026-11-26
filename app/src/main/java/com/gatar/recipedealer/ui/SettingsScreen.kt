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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.R
import com.gatar.recipedealer.container
import com.gatar.recipedealer.settings.AppSettings
import com.gatar.recipedealer.settings.ColorTheme
import com.gatar.recipedealer.upload.HttpCaptureSender
import kotlinx.coroutines.launch

/**
 * Three tabs (user, 2026-11-26): "Ogólne" (connection test, color theme, switches, tag manager), "Moje konto" (names,
 * e-mail, password, logout, deleting the account) and "Mój dom" (household, members and devices, invitations, login
 * codes). Logged out, the account and household tabs only offer the login. The token itself is not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onTags: () -> Unit, onLogin: () -> Unit, onPassword: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val repo = container.settings
    val account by repo.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    val state = rememberAccountState()
    // once per login (also after logging in from a tab), when a tab needs it
    LaunchedEffect(account.token, account.needsLogin, tab) {
        if (account.needsLogin) {
            state.me = null
            state.error = null
        } else if (tab != 0 && state.me == null && !state.busy) state.reload()
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            text = {
                Text(stringResource(R.string.logout_confirm) +
                    if (repo.current.userName.isBlank()) "\n\n" + stringResource(R.string.logout_confirm_enrolled) else "")
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; container.signOut() }) {
                    Text(stringResource(R.string.settings_logout))
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    AccountDialogs(state)

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.settings_tab_general, R.string.settings_tab_account, R.string.settings_tab_household)
                    .forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
                    }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    tab == 0 -> GeneralTab(onBack, onTags)
                    account.needsLogin -> LoggedOut(account.authRejected, onLogin)
                    tab == 1 -> AccountTab(state, onPassword, onLogout = { confirmLogout = true })
                    else -> HouseholdTab(state)
                }
            }
        }
    }
}

@Composable
private fun LoggedOut(rejected: Boolean, onLogin: () -> Unit) {
    Text(stringResource(if (rejected) R.string.settings_rejected else R.string.settings_logged_out),
        style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
}

/** "Ogólne": the server (fixed per release, only the connection test), the color theme, switches, tags. */
@Composable
private fun GeneralTab(onBack: () -> Unit, onTags: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.settings
    val initial = remember { repo.current }
    val account by repo.settings.collectAsStateWithLifecycle()
    var background by remember { mutableStateOf(initial.backgroundMode) }
    var autoSend by remember { mutableStateOf(initial.autoSend) }
    var tabletView by remember { mutableStateOf(initial.tabletView) }
    var translateNew by remember { mutableStateOf(initial.translateNew) }
    var health by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() {
        repo.update {
            it.copy(backgroundMode = background, autoSend = autoSend, tabletView = tabletView, translateNew = translateNew)
        }
        Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    Text(stringResource(R.string.settings_backend), style = MaterialTheme.typography.titleMedium)
    // fixed per release (user, 2026-11-25); its address is not shown (user, 2026-11-26)
    OutlinedButton(
        onClick = {
            health = context.getString(R.string.health_testing)
            scope.launch {
                health = when (val h = HttpCaptureSender(AppSettings.SERVER_URL, repo.current.token).health()) {
                    HttpCaptureSender.Health.Ok -> context.getString(R.string.health_ok)
                    HttpCaptureSender.Health.Unauthorized -> context.getString(R.string.health_unauthorized)
                    is HttpCaptureSender.Health.Failed -> context.getString(R.string.health_failed, h.reason)
                }
            }
        },
    ) { Text(stringResource(R.string.settings_test)) }
    health?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    HorizontalDivider()
    // applied at once (not with "Zapisz"), so the choice can be seen right away
    ColorThemeSelect(account.colorTheme) { theme -> repo.update { it.copy(colorTheme = theme) } }
    SwitchRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_desc), background) { background = it }
    SwitchRow(stringResource(R.string.settings_autosend), stringResource(R.string.settings_autosend_desc), autoSend) { autoSend = it }
    SwitchRow(stringResource(R.string.settings_tablet), stringResource(R.string.settings_tablet_desc), tabletView) { tabletView = it }
    SwitchRow(stringResource(R.string.settings_translate, AppSettings.deviceLanguageName()),
        stringResource(R.string.settings_translate_desc), translateNew) { translateNew = it }
    if (repo.current.hasBackend) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onTags).padding(vertical = 4.dp)) {
            Text(stringResource(R.string.tag_manager), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.tag_manager_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Button(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_save))
    }
}

@Composable
fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "–" }, style = MaterialTheme.typography.bodyLarge)
    }
}

private val ColorTheme.label get() = when (this) {
    ColorTheme.SYSTEM -> R.string.theme_system
    ColorTheme.LIGHT -> R.string.theme_light
    ColorTheme.DARK -> R.string.theme_dark
    ColorTheme.KITCHEN -> R.string.theme_kitchen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorThemeSelect(current: ColorTheme, onSelect: (ColorTheme) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(current.label),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_theme)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ColorTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(stringResource(theme.label)) },
                    onClick = { expanded = false; onSelect(theme) },
                )
            }
        }
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
