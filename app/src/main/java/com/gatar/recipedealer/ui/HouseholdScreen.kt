package com.gatar.recipedealer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.HouseholdView
import com.gatar.recipedealer.api.Invitation
import com.gatar.recipedealer.api.Me
import com.gatar.recipedealer.api.Member
import com.gatar.recipedealer.api.MemberDevice
import com.gatar.recipedealer.api.Roles
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.settings.AppSettings
import kotlinx.coroutines.launch
import java.util.Locale

/** A destructive action waiting for "OK" in a dialog. */
private class Confirm(val text: String, val button: String, val action: suspend (BackendClient) -> ApiResult<*>, val after: () -> Unit = {})

/**
 * Settings → "Dom" (online only): the household with its limits (recipes, users, Claude this month), its users with
 * their devices, invitations (owner), renaming, leaving / deleting. Owners manage everyone; members only their own
 * devices and account (`docs/api.md` Households).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    var me by remember { mutableStateOf<Me?>(null) }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var invitations by remember { mutableStateOf<List<Invitation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var created by remember { mutableStateOf<Invitation?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val client = container.backend() ?: run { error = apiErrorText(context, Inbox.NO_BACKEND); busy = false; return }
        busy = true
        val r = client.me()
        if (r !is ApiResult.Ok) {
            error = if (isMissingRoute(r)) context.getString(R.string.household_unsupported) else apiErrorText(context, r)
            busy = false
            return
        }
        me = r.value
        // an admin-enrolled device never had a session: the household comes from here
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
                if (container.settings.current.needsLogin) return@launch
                reload()
            } else {
                error = apiErrorText(context, r)
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.household_title)) },
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val self = me
            if (self == null) {
                if (busy) Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val h = self.household
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (self.isOwner) {
                    IconButton(onClick = { renaming = true }, enabled = !busy) {
                        Icon(Icons.Default.Edit, stringResource(R.string.household_rename))
                    }
                }
            }
            Text(stringResource(R.string.household_you, self.userName, roleLabel(context, self.role)),
                style = MaterialTheme.typography.bodyMedium)
            Limits(h)

            HorizontalDivider()
            Text(stringResource(R.string.household_members), style = MaterialTheme.typography.titleMedium)
            members.forEach { m ->
                MemberCard(
                    member = m,
                    self = self,
                    enabled = !busy,
                    onRole = { role -> run({ it.setRole(m.userId, role) }) },
                    onRemove = {
                        confirm = Confirm(context.getString(R.string.household_remove_confirm, m.userName),
                            context.getString(R.string.household_remove), { it.removeMember(m.userId) })
                    },
                    onRevoke = { d ->
                        confirm = Confirm(
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
                invitations.forEach { inv ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.household_invitation_row, inv.createdBy ?: "?",
                                inv.expiresAt?.let(::formatInstant) ?: "?"),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { run({ it.revokeInvitation(inv.invitationId) }) }, enabled = !busy) {
                            Icon(Icons.Default.Close, stringResource(R.string.household_invitation_revoke))
                        }
                    }
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        run({ c -> c.createInvitation().also { r -> if (r is ApiResult.Ok) created = r.value } })
                    },
                ) { Text(stringResource(R.string.household_invite)) }
            }

            HorizontalDivider()
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    confirm = Confirm(context.getString(R.string.household_leave_confirm), context.getString(R.string.household_leave),
                        { it.deleteMe() }, after = { container.signOut(forgetData = true) })
                },
            ) { Text(stringResource(R.string.household_leave)) }
            if (self.isOwner && h.householdId != AppSettings.DEFAULT_HOUSEHOLD_ID) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        confirm = Confirm(context.getString(R.string.household_delete_confirm, h.name),
                            context.getString(R.string.household_delete),
                            { it.deleteHousehold() }, after = { container.signOut(forgetData = true) })
                    },
                ) { Text(stringResource(R.string.household_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { confirm = null; run(c.action, c.after) }) { Text(c.button) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    created?.let { inv ->
        val name = me?.household?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { created = null },
            title = { Text(stringResource(R.string.household_invitation_created)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(inv.code.orEmpty(), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.household_invitation_how, inv.expiresAt?.let(::formatInstant) ?: "?"),
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { shareInvitation(context, name, inv) }) { Text(stringResource(R.string.household_invitation_share)) }
            },
            dismissButton = { TextButton(onClick = { created = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(me?.household?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
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
                    renaming = false
                    run({ it.renameHousehold(name.trim()) })
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
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

@Composable
private fun MemberCard(
    member: Member,
    self: Me,
    enabled: Boolean,
    onRole: (String) -> Unit,
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
                    Text(roleLabel(context, member.role), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (self.isOwner) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.household_member_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val other = if (member.role == Roles.OWNER) Roles.MEMBER else Roles.OWNER
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (other == Roles.OWNER) R.string.household_make_owner
                                        else R.string.household_make_member))
                                },
                                onClick = { menu = false; onRole(other) },
                            )
                            if (!isSelf) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.household_remove)) },
                                    onClick = { menu = false; onRemove() },
                                )
                            }
                        }
                    }
                }
            }
            if (member.devices.isEmpty()) {
                Text(stringResource(R.string.household_no_devices), style = MaterialTheme.typography.bodySmall)
            }
            member.devices.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(d.deviceName + if (d.current) " " + stringResource(R.string.household_this_device) else "",
                            style = MaterialTheme.typography.bodyMedium)
                        d.lastUsedAt?.let {
                            Text(stringResource(R.string.household_last_used, formatInstant(it)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (self.isOwner || isSelf) {
                        IconButton(onClick = { onRevoke(d) }, enabled = enabled) {
                            Icon(Icons.Default.Close, stringResource(R.string.household_revoke))
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

/** The system share sheet with the code and how to use it (copying is one of its targets). */
private fun shareInvitation(context: Context, household: String, inv: Invitation) {
    val text = context.getString(R.string.household_invitation_text, household, inv.code.orEmpty(),
        inv.expiresAt?.let(::formatInstant) ?: "?")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
