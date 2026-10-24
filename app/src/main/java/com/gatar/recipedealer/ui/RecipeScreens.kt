package com.gatar.recipedealer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.container
import com.gatar.recipedealer.api.SearchHit
import com.gatar.recipedealer.recipes.CachedRecipe
import com.gatar.recipedealer.recipes.ImageCache
import com.gatar.recipedealer.recipes.LocalSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

private enum class RecipeSort { TITLE, UPDATED }

/** "Przepisy": the offline recipe cache (sync feed), pull-to-refresh syncs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onManual: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val recipes by container.recipes.recipes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(RecipeSort.TITLE) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() = scope.launch {
        refreshing = true
        error = apiErrorText(context, container.recipeSync.sync())
        container.inbox.refresh()
        refreshing = false
    }
    val collator = remember { Collator.getInstance(Locale.forLanguageTag("pl")) }
    val sorted = remember(recipes, sort) {
        when (sort) {
            RecipeSort.TITLE -> recipes.sortedWith(compareBy(collator) { it.title.orEmpty() })
            RecipeSort.UPDATED -> recipes.sortedByDescending { it.updatedAt }
        }
    }
    // Search: the server (diacritics, typos, ingredients); meanwhile and offline a local filter over the cache.
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        hits = null
        searchNote = null
        if (query.isBlank()) return@LaunchedEffect
        delay(300)
        val r = container.backend()?.search(query.trim()) ?: return@LaunchedEffect
        if (r is ApiResult.Ok) hits = r.value else searchNote = context.getString(R.string.search_local)
    }
    val local = remember(recipes, query) { LocalSearch.filter(recipes, query) }
    val byId = remember(recipes) { recipes.associateBy { it.recipeId } }
    val shown: List<ListRow> = when {
        query.isBlank() -> sorted.map(::ListRow)
        hits != null -> hits!!.map { h -> ListRow(h, byId[h.recipeId]) }
        else -> local.map(::ListRow)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                actions = {
                    TextButton(onClick = { sort = if (sort == RecipeSort.TITLE) RecipeSort.UPDATED else RecipeSort.TITLE }) {
                        Icon(Icons.AutoMirrored.Default.Sort, null)
                        Text(stringResource(if (sort == RecipeSort.TITLE) R.string.sort_title else R.string.sort_updated))
                    }
                    IconButton(onClick = onManual) { Icon(Icons.Default.Edit, stringResource(R.string.manual_entry)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(refreshing, onRefresh = { refresh() }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        query, { query = it },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.search_clear)) }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                }
                error?.let { item { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) } }
                if (query.isNotBlank()) searchNote?.let {
                    item { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                }
                if (shown.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(if (query.isBlank()) R.string.recipes_empty else R.string.search_empty), textAlign = TextAlign.Center)
                        }
                    }
                }
                items(shown, key = { it.recipeId }) { r ->
                    RecipeRow(r.title, r.subtitle, r.needsReview, r.imageKey, r.thumbnail) { onOpen(r.recipeId) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun RecipeRow(
    title: String?,
    subtitle: String?,
    needsReview: Boolean,
    imageKey: Any,
    thumbnail: (suspend (ImageCache) -> File?)?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            val image = thumbnail?.let { cachedImage(imageKey, 200, it) }
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Restaurant, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(subtitle, if (needsReview) stringResource(R.string.needs_review) else null)
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A row of the recipe list: a cached recipe or a search hit (plus its cached recipe, if any). */
private class ListRow(
    val recipeId: String,
    val title: String?,
    val subtitle: String?,
    val needsReview: Boolean,
    val imageKey: Any,
    val thumbnail: suspend (ImageCache) -> File?,
) {
    constructor(r: CachedRecipe) : this(r.recipeId, r.title, r.sourceSummary, r.needsReview,
        r.imageKey, { recipeImage(r, it) })

    constructor(h: SearchHit, cached: CachedRecipe?) : this(h.recipeId, h.title ?: cached?.title, h.sourceSummary ?: cached?.sourceSummary,
        cached?.needsReview == true, cached?.imageKey ?: h.thumbnailBlob ?: h.recipeId,
        { images -> cached?.let { recipeImage(it, images) } ?: h.thumbnailBlob?.let { images.blob(it) } })
}

/**
 * `images[0]` (the page's photo, or one the user set in the editor), else the blob thumbnail (photo / PDF captures
 * have no image URL). The URL goes first: the server does not change `thumbnailBlob` when the user edits the photo.
 */
internal suspend fun recipeImage(r: CachedRecipe, images: ImageCache): File? =
    r.imageUrl?.let { images.url(it) } ?: r.thumbnailBlob?.let { images.blob(it) }

@Composable
internal fun cachedImage(key: Any, maxWidth: Int, load: suspend (ImageCache) -> File?): ImageBitmap? {
    val images = LocalContext.current.container.images
    val bitmap by produceState<ImageBitmap?>(null, key) {
        value = load(images)?.let { f -> withContext(Dispatchers.IO) { decodeSampled(f, maxWidth) } }
    }
    return bitmap
}

/** Decodes [file] at the smallest power-of-two sample that keeps at least [maxWidth] px. */
fun decodeSampled(file: File, maxWidth: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= maxWidth) s *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

/**
 * One recipe from the cache (works offline), refreshed with a conditional GET. Deleted / merged recipes leave the
 * cache; a merged one offers its target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    onBack: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    actions: @Composable (CachedRecipe) -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.container
    val all by container.recipes.recipes.collectAsStateWithLifecycle()
    val cached = all.firstOrNull { it.recipeId == recipeId }
    val uri = LocalUriHandler.current
    var note by remember { mutableStateOf<String?>(null) }
    var mergedInto by remember { mutableStateOf(container.recipes.redirect(recipeId)) }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val tablet = settings.tabletView
    LaunchedEffect(recipeId) {
        val client = container.backend() ?: return@LaunchedEffect
        when (val r = client.recipe(recipeId, container.recipes.get(recipeId)?.detailEtag)) {
            is ApiResult.Ok -> container.recipes.putDetail(r.value)
            ApiResult.NotModified -> {}
            is ApiResult.Failed -> if (r.code == 410) {
                val target = if (r.error == ApiErrors.RECIPE_MERGED) r.details.firstNotNullOfOrNull { UUID.find(it)?.value } else null
                container.recipes.remove(recipeId, target)
                mergedInto = target
                note = context.getString(if (r.error == ApiErrors.RECIPE_MERGED) R.string.recipe_merged else R.string.recipe_deleted)
            } else note = apiErrorText(context, r)
            is ApiResult.Retry -> note = if (cached != null) context.getString(R.string.recipe_offline) else apiErrorText(context, r)
            else -> note = apiErrorText(context, r)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cached?.title ?: stringResource(R.string.untitled), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    // only recipes that came from a web page have urls (text / photos / PDF / manual have none)
                    cached?.urls?.firstOrNull { it.startsWith("http") }?.let { url ->
                        IconButton(onClick = { runCatching { uri.openUri(url) } }) {
                            Icon(Icons.Default.OpenInBrowser, stringResource(R.string.open_in_browser))
                        }
                    }
                    // the same switch as in the settings, at hand in the kitchen
                    IconButton(onClick = { container.settings.update { it.copy(tabletView = !it.tabletView) } }) {
                        if (tablet) Icon(Icons.Default.PhoneAndroid, stringResource(R.string.view_phone))
                        else Icon(Icons.Default.TabletAndroid, stringResource(R.string.view_tablet))
                    }
                    cached?.let { actions(it) }
                },
            )
        },
        containerColor = if (tablet) Color.White else MaterialTheme.colorScheme.background,
    ) { padding ->
        @Composable
        fun ColumnScope.status() {
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            mergedInto?.let { target ->
                TextButton(onClick = { onOpenRecipe(target) }) { Text(stringResource(R.string.recipe_open_merged)) }
            }
            if (cached == null && note == null && mergedInto == null) Text(stringResource(R.string.loading))
            if (cached?.needsReview == true) Banner(stringResource(R.string.recipe_needs_review))
            cached?.lock?.lockedBy?.let { Banner(stringResource(R.string.edit_locked_by, it)) }
        }
        if (tablet && cached != null) {
            val recipe = remember(cached.recipe) { cached.content() }
            Box(Modifier.fillMaxSize().padding(padding)) { TabletRecipeView(cached, recipe) { status() } }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (cached == null) {
                status()
                return@Column
            }
            status()
            cachedImage(cached.imageKey, 1080) { recipeImage(cached, it) }?.let {
                Image(it, null, Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            }
            val recipe = remember(cached.recipe) { cached.content() }
            Text(cached.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.headlineSmall)
            RecipeContent(recipe, showTitle = false)
            if (cached.sourceSummary != null || cached.urls.isNotEmpty()) {
                Text(stringResource(R.string.recipe_source), style = MaterialTheme.typography.titleMedium)
                cached.sourceSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                cached.urls.forEach { url ->
                    Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { runCatching { uri.openUri(url) } })
                }
            }
        }
    }
}

private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

@Composable
fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
    }
}
