package com.gatar.recipedealer.web

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gatar.recipedealer.MainActivity
import com.gatar.recipedealer.Notifications
import com.gatar.recipedealer.R
import com.gatar.recipedealer.capture.Extraction
import com.gatar.recipedealer.container
import com.gatar.recipedealer.store.CaptureStatus
import com.gatar.recipedealer.ui.theme.RecipeDealerTheme
import kotlinx.coroutines.launch

/**
 * Visible WebView for a web capture that failed in the background (interactive bot check, consent wall, a page
 * that needs interaction) or for shares with background mode off. Extracts by itself once the page is quiet –
 * never while a consent dialog is shown – and on "Wyodrębnij teraz".
 */
@OptIn(ExperimentalMaterial3Api::class)
class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val url = container.processor.webUrl(id) ?: return finish()
        Notifications.cancel(this, id)
        val scripts = ExtractorScripts(this)
        val webView = WebView(this)
        val page = PageController(webView, scripts)
        setContent {
            RecipeDealerTheme {
                var status by remember { mutableStateOf(getString(R.string.browser_loading)) }
                var busy by remember { mutableStateOf(true) }
                val scope = rememberCoroutineScope()

                suspend fun extract(manual: Boolean) {
                    busy = true
                    status = getString(R.string.browser_extracting)
                    val t0 = System.currentTimeMillis()
                    val output = page.runExtractor()
                    val e = output?.let {
                        WebResult.extraction(it, LoadInfo(url, renderTimeMs = null,
                            timingsMs = mapOf("extract" to System.currentTimeMillis() - t0),
                            notes = listOf("visible WebView", if (manual) "trigger=manual" else "trigger=stable")))
                    }
                    busy = false
                    if (e == null || (!manual && !(e.recipe.hasIngredients || e.recipe.hasSteps))) {
                        status = getString(R.string.browser_not_found)
                        return
                    }
                    save(id, e)
                }

                LaunchedEffect(Unit) {
                    if (container.store.get(id)?.state?.status == CaptureStatus.FAILED) {
                        container.store.updateState(id) { it.copy(status = CaptureStatus.EXTRACTING, error = null) }
                    }
                    page.load(url)
                    while (true) {
                        val stable = page.awaitStable(WebTiming.STABILIZE_TIMEOUT_MS)
                        busy = false
                        when {
                            page.consentWallShown() -> status = getString(R.string.browser_consent)
                            page.runExtractor()?.botChallenge() != null -> status = getString(R.string.browser_challenge)
                            stable || page.pageFinishedCount > 0 -> { extract(manual = false); if (isFinishing) break }
                        }
                        page.loads.receive() // wait for the next navigation (user answered a dialog, passed a check)
                        status = getString(R.string.browser_loading)
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(url, maxLines = 1, style = MaterialTheme.typography.titleSmall) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back))
                                }
                            },
                            actions = {
                                Button(onClick = { scope.launch { extract(manual = true) } }, modifier = Modifier.padding(end = 8.dp)) {
                                    Text(stringResource(R.string.extract_now))
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                        AndroidView({ webView }, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun save(id: String, e: Extraction) {
        val entry = container.processor.complete(id, e) ?: return
        Toast.makeText(this, getString(R.string.notif_saved, entry.title ?: getString(R.string.untitled)), Toast.LENGTH_SHORT).show()
        if (container.settings.current.autoSend) container.send(id)
        startActivity(MainActivity.openCaptureIntent(this, id))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // A capture left half-way (user backed out) is shown as failed so it can be retried.
        intent.getStringExtra(EXTRA_ID)?.let { id ->
            container.store.updateState(id) { s ->
                if (s.status == CaptureStatus.EXTRACTING) s.copy(status = CaptureStatus.FAILED, error = getString(R.string.browser_abandoned)) else s
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "captureId"

        fun intent(context: Context, captureId: String) = Intent(context, BrowserActivity::class.java)
            .putExtra(EXTRA_ID, captureId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
