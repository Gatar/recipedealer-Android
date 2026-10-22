package com.gatar.recipedealer.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.Origins
import com.gatar.recipedealer.api.RecipeJson
import com.gatar.recipedealer.api.RevisionView
import com.gatar.recipedealer.capture.RecipeDraft
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.recipes.RecipeEditSession
import com.gatar.recipedealer.recipes.RecipeEditSession.Phase
import com.gatar.recipedealer.recipes.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Holds the edit session across configuration changes; closing the editor any other way releases the lock. */
class RecipeEditViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val container = app.container
    val recipeId: String = handle["id"]!!
    /** Apply this revision (a machine proposal or an older version) instead of editing the head. */
    private val revisionId: String? = handle["revision"]
    val session = container.backend()?.let { RecipeEditSession(it, container.recipes, recipeId, viewModelScope) }
    val state: StateFlow<RecipeEditSession.State> = session?.state
        ?: MutableStateFlow(RecipeEditSession.State(Phase.FAILED, error = Inbox.NO_BACKEND))

    init {
        viewModelScope.launch { start() }
    }

    suspend fun start() {
        val s = session ?: return
        val revision = revisionId?.let { id ->
            (container.backend()?.revisions(recipeId) as? ApiResult.Ok)?.value?.firstOrNull { it.revisionId == id }?.recipe
        }
        s.start(revision)
    }

    /** Back, "Anuluj", or leaving after a save: releases the lock if this session still holds it. */
    override fun onCleared() {
        session?.let { s -> container.appScope.launch { s.cancel() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(onClose: () -> Unit, onDeleted: () -> Unit, vm: RecipeEditViewModel = viewModel()) {
    val context = LocalContext.current
    val session = vm.session
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(stateSaver = NullableDraftSaver) { mutableStateOf<RecipeDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.initial) { if (draft == null) state.initial?.let { draft = RecipeDraft.from(it) } }

    // the lock is released when the view model is cleared (RecipeEditViewModel.onCleared)
    fun close() = onClose()
    BackHandler { close() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_title), maxLines = 1) },
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.phase) {
                Phase.STARTING -> { Text(stringResource(R.string.edit_starting)); return@Column }
                Phase.LOCKED_BY_OTHER -> {
                    Banner(stringResource(R.string.edit_locked_by, state.lockedBy ?: "?"))
                    return@Column
                }
                Phase.FAILED -> {
                    Text(state.error?.let { apiErrorText(context, it) } ?: "", color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                Phase.EDITING, Phase.FINISHED -> {}
            }
            if (state.lockLost) Banner(stringResource(R.string.edit_lock_lost))
            if (state.headChanged) {
                Banner(stringResource(R.string.edit_head_changed))
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        session?.cancel()
                        draft = null
                        vm.start()
                        busy = false
                    }
                }) { Text(stringResource(R.string.edit_reload)) }
            }
            state.error?.takeIf { !state.lockLost && !state.headChanged }?.let {
                Text(apiErrorText(context, it).orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            val d = draft ?: return@Column
            RecipeEditor(d) { draft = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && state.phase == Phase.EDITING, onClick = {
                    scope.launch {
                        busy = true
                        val r = session?.save(d.toRecipe(state.initial ?: Recipe()))
                        busy = false
                        if (r is ApiResult.Ok) {
                            SyncWorker.syncNow(context)
                            onClose()
                        }
                    }
                }) { Text(stringResource(R.string.save_recipe)) }
                OutlinedButton(onClick = ::close) { Text(stringResource(R.string.cancel)) }
                TextButton(enabled = !busy && state.phase == Phase.EDITING, onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.recipe_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.recipe_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        val r = session?.delete()
                        busy = false
                        if (r is ApiResult.Ok) {
                            SyncWorker.syncNow(context)
                            onDeleted()
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** History of a recipe, newest first; revisions newer than the head are machine proposals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevisionsScreen(recipeId: String, onBack: () -> Unit, onApply: (String) -> Unit) {
    val context = LocalContext.current
    var revisions by remember { mutableStateOf<List<RevisionView>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recipeId) {
        val r = context.container.backend()?.revisions(recipeId) ?: Inbox.NO_BACKEND
        revisions = (r as? ApiResult.Ok)?.value
        error = apiErrorText(context, r)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.revisions_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val list = revisions
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            if (list == null && error == null) item { Text(stringResource(R.string.loading), Modifier.padding(16.dp)) }
            val headIndex = list?.indexOfFirst { it.head } ?: -1
            itemsIndexed(list.orEmpty(), key = { _, rev -> rev.revisionId }) { i, rev ->
                val proposal = !rev.head && headIndex > i
                Column(
                    Modifier.fillMaxWidth().clickable { open = if (open == rev.revisionId) null else rev.revisionId }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val who = if (rev.origin == Origins.USER) rev.userName ?: stringResource(R.string.revision_user)
                        else stringResource(R.string.revision_machine)
                    Text(listOfNotNull(rev.createdAt?.let(::formatInstant), who).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                    val marks = listOfNotNull(
                        stringResource(R.string.revision_head).takeIf { rev.head },
                        stringResource(R.string.revision_proposal).takeIf { proposal },
                        stringResource(R.string.needs_review).takeIf { rev.needsReview },
                    )
                    if (marks.isNotEmpty()) Text(marks.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    if (open == rev.revisionId) {
                        RecipeContent(remember(rev.recipe) { RecipeJson.decode(rev.recipe) })
                        if (!rev.head) Button(onClick = { onApply(rev.revisionId) }) { Text(stringResource(R.string.revision_apply)) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
