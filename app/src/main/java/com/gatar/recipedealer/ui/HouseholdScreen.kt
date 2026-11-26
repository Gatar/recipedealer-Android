package com.gatar.recipedealer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.AppContainer
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.Emails
import com.gatar.recipedealer.api.HouseholdView
import com.gatar.recipedealer.api.Invitation
import com.gatar.recipedealer.api.LoginCode
import com.gatar.recipedealer.api.Me
import com.gatar.recipedealer.api.Member
import com.gatar.recipedealer.api.MemberDevice
import com.gatar.recipedealer.api.Roles
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** A destructive action waiting for "OK" in a dialog. */
class Confirm(val text: String, val button: String, val action: suspend (BackendClient) -> ApiResult<*>, val after: () -> Unit = {})

/**
 * What the Settings tabs "Moje konto" and "Mój dom" show (online only): the caller (`GET /v1/me`), the household's
 * users with their devices, invitations (owner), and the dialogs of their actions (`docs/api.md` Households).
 */
@Stable
class AccountState(private val context: Context, private val container: AppContainer, private val scope: CoroutineScope) {
    var me by mutableStateOf<Me?>(null)
    var members by mutableStateOf<List<Member>>(emptyList())
    var invitations by mutableStateOf<List<Invitation>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var confirm by mutableStateOf<Confirm?>(null)
    var invitation by mutableStateOf<Invitation?>(null)
    var loginCode by mutableStateOf<LoginCode?>(null)
    var renaming by mutableStateOf(false)

    suspend fun reload() {
        val client = container.backend() ?: run { error = apiErrorText(context, Inbox.NO_BACKEND); return }
        busy = true
        val r = client.me()
        if (r !is ApiResult.Ok) {
            error = if (isMissingRoute(r)) context.getString(R.string.household_unsupported) else apiErrorText(context, r)
            busy = false
            return
        }
        me = r.value
        // an admin-enrolled device never had a session: the household comes from here (the names are shown from
        // `me`, not stored: a blank stored user name still means "enrolled", see the logout dialog)
        if (container.settings.current.householdId.isBlank()) {
            container.settings.update { it.copy(householdId = r.value.household.householdId) }
        }
        val m = client.members()
        members = m.valueOrEmpty()
        invitations = if (r.value.isOwner) client.invitations().valueOrEmpty() else emptyList()
        error = apiErrorText(context, m)
        busy = false
    }

    fun run(action: suspend (BackendClient) -> ApiResult<*>, after: () -> Unit = {}) {
        val client = container.backend() ?: return
        busy = true
        error = null
        scope.launch {
            val r = action(client)
            if (r is ApiResult.Ok) {
                after()
                if (container.settings.current.needsLogin) { busy = false; return@launch }
                reload()
            } else {
                error = if (isMissingRoute(r)) context.getString(R.string.household_unsupported) else apiErrorText(context, r)
                busy = false
            }
        }
    }

    fun deleteAccount() {
        confirm = Confirm(context.getString(R.string.household_leave_confirm), context.getString(R.string.household_leave),
            { it.deleteMe() }, after = { container.signOut(forgetData = true) })
    }
}

@Composable
fun rememberAccountState(): AccountState {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    return remember { AccountState(context, container, scope) }
}

/** "Moje konto": names, e-mail address (required for owners), password, logout, deleting the account. */
@Composable
fun AccountTab(state: AccountState, onPassword: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val settings = context.container.settings.current
    val self = state.me
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    LabeledValue(stringResource(R.string.login_user), self?.userName ?: settings.userName)
    LabeledValue(stringResource(R.string.login_device), self?.deviceName ?: settings.deviceName)
    if (self != null) {
        if (self.ownerWithoutEmail) EmailBanner(stringResource(R.string.email_owner_banner))
        EmailEditor(state, self)
        Text(stringResource(R.string.household_you, self.household.name, roleLabel(context, self.role)),
            style = MaterialTheme.typography.bodyMedium)
    } else if (state.busy) {
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPassword) {
            val noPassword = self?.let { !it.hasPassword } ?: settings.userName.isBlank()
            Text(stringResource(if (noPassword) R.string.password_set else R.string.password_change))
        }
        OutlinedButton(onClick = onLogout) { Text(stringResource(R.string.settings_logout)) }
    }
    if (self != null) {
        OutlinedButton(enabled = !state.busy, onClick = state::deleteAccount) {
            Text(stringResource(R.string.household_leave), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EmailEditor(state: AccountState, self: Me) {
    var email by remember(self.email) { mutableStateOf(self.email.orEmpty()) }
    val changed = email.trim() != self.email.orEmpty()
    // an owner cannot remove its address; a member may leave the field empty
    val valid = if (email.isBlank()) !self.isOwner else Emails.valid(email)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EmailField(email, { email = it }, required = self.isOwner, modifier = Modifier.weight(1f))
        Button(enabled = changed && valid && !state.busy, onClick = {
            state.run({ c -> c.setEmail(email.trim()).also { r -> if (r is ApiResult.Ok) state.me = r.value } })
        }) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun EmailBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp))
    }
}

/**
 * "Mój dom": the household with its limits (recipes, users, Claude this month), its users with their devices,
 * invitations (owner), renaming, deleting. Owners manage everyone (once they have an e-mail address); members only
 * their own devices.
 */
@Composable
fun HouseholdTab(state: AccountState) {
    val context = LocalContext.current
    val container = context.container
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    val self = state.me
    if (self == null) {
        if (state.busy) Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
        return
    }
    val h = self.household
    // owner actions wait for the owner's e-mail address (the server answers 409 email_required)
    val manage = self.isOwner && !self.ownerWithoutEmail
    if (self.ownerWithoutEmail) EmailBanner(stringResource(R.string.email_owner_banner_household))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(h.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (manage) {
            IconButton(onClick = { state.renaming = true }, enabled = !state.busy) {
                Icon(Icons.Default.Edit, stringResource(R.string.household_rename))
            }
        }
    }
    Limits(h)

    HorizontalDivider()
    Text(stringResource(R.string.household_members), style = MaterialTheme.typography.titleMedium)
    // one card per user and device (user, 2026-11-26); a user without a working device gets one card too
    state.members.flatMap { m -> m.devices.ifEmpty { listOf(null) }.map { m to it } }.forEach { (m, device) ->
        MemberCard(
            member = m,
            device = device,
            self = self,
            manage = manage,
            enabled = !state.busy,
            onRole = { role -> state.run({ it.setRole(m.userId, role) }) },
            onLoginCode = {
                state.run({ c ->
                    c.createLoginCode(m.userId, device?.deviceId).also { r -> if (r is ApiResult.Ok) state.loginCode = r.value }
                })
            },
            onRemove = {
                state.confirm = Confirm(context.getString(R.string.household_remove_confirm, m.userName),
                    context.getString(R.string.household_remove), { it.removeMember(m.userId) })
            },
            onRevoke = { d ->
                state.confirm = Confirm(
                    context.getString(if (d.current) R.string.household_revoke_own_confirm else R.string.household_revoke_confirm,
                        d.deviceName),
                    context.getString(R.string.household_revoke),
                    { it.revokeDevice(d.deviceId) },
                    // this device's token is dead now: back to the login screen, the cache stays
                    after = { if (d.current) container.signOut() },
                )
            },
        )
    }

    if (self.isOwner) {
        HorizontalDivider()
        Text(stringResource(R.string.household_invitations), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.household_invitations_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.invitations.forEach { inv ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.household_invitation_row, inv.createdBy ?: "?",
                        inv.expiresAt?.let(::formatInstant) ?: "?"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { state.run({ it.revokeInvitation(inv.invitationId) }) }, enabled = !state.busy) {
                    Icon(Icons.Default.Close, stringResource(R.string.household_invitation_revoke))
                }
            }
        }
        Button(
            enabled = manage && !state.busy,
            onClick = { state.run({ c -> c.createInvitation().also { r -> if (r is ApiResult.Ok) state.invitation = r.value } }) },
        ) { Text(stringResource(R.string.household_invite)) }
    }

    if (self.isOwner && h.householdId != AppSettings.DEFAULT_HOUSEHOLD_ID) {
        HorizontalDivider()
        OutlinedButton(
            enabled = !state.busy,
            onClick = {
                state.confirm = Confirm(context.getString(R.string.household_delete_confirm, h.name),
                    context.getString(R.string.household_delete),
                    { it.deleteHousehold() }, after = { container.signOut(forgetData = true) })
            },
        ) { Text(stringResource(R.string.household_delete), color = MaterialTheme.colorScheme.error) }
    }
}

/** The dialogs of both tabs: confirmations, a new invitation or login code, renaming. */
@Composable
fun AccountDialogs(state: AccountState) {
    val context = LocalContext.current
    state.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { state.confirm = null },
            text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { state.confirm = null; state.run(c.action, c.after) }) { Text(c.button) } },
            dismissButton = { TextButton(onClick = { state.confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.invitation?.let { inv ->
        val expires = inv.expiresAt?.let(::formatInstant) ?: "?"
        CodeDialog(
            title = stringResource(R.string.household_invitation_created),
            code = inv.code.orEmpty(),
            how = stringResource(R.string.household_invitation_how, expires),
            shareText = context.getString(R.string.household_invitation_text, state.me?.household?.name.orEmpty(),
                inv.code.orEmpty(), expires),
            onClose = { state.invitation = null },
        )
    }
    state.loginCode?.let { lc ->
        val expires = lc.expiresAt?.let(::formatInstant) ?: "?"
        // a code for a device logs in as that device
        val target = lc.deviceName?.let { stringResource(R.string.login_code_target_device, lc.userName, it) } ?: lc.userName
        CodeDialog(
            title = stringResource(R.string.login_code_created, target),
            code = lc.code,
            how = stringResource(R.string.login_code_how, target, expires),
            shareText = context.getString(R.string.login_code_text, target, lc.code, expires),
            onClose = { state.loginCode = null },
        )
    }
    if (state.renaming) {
        var name by remember { mutableStateOf(state.me?.household?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { state.renaming = false },
            title = { Text(stringResource(R.string.household_rename)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.household_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    state.renaming = false
                    state.run({ it.renameHousehold(name.trim()) })
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { state.renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A single-use code shown once, with the system share sheet (copying is one of its targets). */
@Composable
private fun CodeDialog(title: String, code: String, how: String, shareText: String, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                Text(how, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = { share(context, shareText) }) { Text(stringResource(R.string.household_invitation_share)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun Limits(h: HouseholdView) {
    val context = LocalContext.current
    fun limit(count: String, max: String?) =
        if (max == null) context.getString(R.string.household_unlimited, count) else "$count / $max"
    LabeledValue(stringResource(R.string.household_recipes), limit(h.recipeCount.toString(), h.recipeLimit?.toString()))
    LabeledValue(stringResource(R.string.household_users), limit(h.userCount.toString(), h.userLimit?.toString()))
    LabeledValue(stringResource(R.string.household_claude), limit(dollars(h.llmSpentUsd), h.llmBudgetUsd?.let(::dollars)))
}

/** Cents are enough here ("Statystyki dla nerdów" show 4 decimals). */
private fun dollars(v: Double) = String.format(Locale.ROOT, "$%.2f", v)

/** One user with one of its devices ([device] null: the user has no working device). */
@Composable
private fun MemberCard(
    member: Member,
    device: MemberDevice?,
    self: Me,
    manage: Boolean,
    enabled: Boolean,
    onRole: (String) -> Unit,
    onLoginCode: () -> Unit,
    onRemove: () -> Unit,
    onRevoke: (MemberDevice) -> Unit,
) {
    val context = LocalContext.current
    val isSelf = member.userId == self.userId
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(member.userName + if (isSelf) " " + stringResource(R.string.household_self) else "",
                        style = MaterialTheme.typography.titleMedium)
                    if (device == null) {
                        Text(stringResource(R.string.household_no_devices), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(device.deviceName + if (device.current) " " + stringResource(R.string.household_this_device) else "",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        listOfNotNull(roleLabel(context, member.role),
                            device?.lastUsedAt?.let { stringResource(R.string.household_last_used, formatInstant(it)) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // owners manage everyone; members only revoke their own devices
                val canRevoke = device != null && (self.isOwner || isSelf)
                if (manage || canRevoke) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.household_member_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (manage) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.login_code_generate)) },
                                    onClick = { menu = false; onLoginCode() },
                                )
                                if (member.role == Roles.OWNER) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.household_make_member)) },
                                        onClick = { menu = false; onRole(Roles.MEMBER) },
                                    )
                                } else {
                                    // only a user with an e-mail address becomes an owner
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(if (member.hasEmail) R.string.household_make_owner
                                                else R.string.household_make_owner_no_email))
                                        },
                                        enabled = member.hasEmail,
                                        onClick = { menu = false; onRole(Roles.OWNER) },
                                    )
                                }
                                if (!isSelf) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.household_remove)) },
                                        onClick = { menu = false; onRemove() },
                                    )
                                }
                            }
                            if (canRevoke) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.household_revoke)) },
                                    onClick = { menu = false; onRevoke(device!!) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun roleLabel(context: Context, role: String) =
    context.getString(if (role == Roles.OWNER) R.string.role_owner else R.string.role_member)

private fun <T> ApiResult<List<T>>.valueOrEmpty(): List<T> = (this as? ApiResult.Ok)?.value.orEmpty()

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
