package com.gatar.recipedealer.web

import android.content.Context
import android.view.View
import android.webkit.WebView
import com.gatar.recipedealer.capture.CaptureJob
import com.gatar.recipedealer.capture.Extraction
import com.gatar.recipedealer.capture.SourceExtractor
import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.OcrRaw
import com.gatar.recipedealer.model.OcrSource
import com.gatar.recipedealer.model.Stage
import com.gatar.recipedealer.ocr.OcrEngine
import com.gatar.recipedealer.ocr.Tiling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Timing rules of §6.3 (measured in the PoC). */
object WebTiming {
    /** Extract on the hard timeout when the DOM never goes quiet (ad-heavy pages). */
    const val STABILIZE_TIMEOUT_MS = 15_000L
    /** Late content: re-extract every 5 s up to 30 s after the load. */
    const val LATE_INTERVAL_MS = 5_000L
    const val LATE_UNTIL_MS = 30_000L
    /** Cloudflare "Just a moment…" passes by itself in 2–7 s. */
    const val CHALLENGE_MAX_MS = 30_000L
    const val SNAPSHOT_MAX_TILES = 8
}

/** Why a web capture has no usable recipe – drives the failure notification. */
object WebFailure {
    const val CONSENT = "consentWall"
    const val BOT = "botChallenge"
}

/**
 * Runs the whole web algorithm on one [PageController]: stabilize → extract → Cloudflare wait → consent check →
 * late-content retries → OCR fallback. Used by the background service (off-screen WebView).
 */
class WebCaptureRunner(private val page: PageController, private val ocr: OcrEngine) {
    suspend fun capture(url: String): Extraction = withContext(Dispatchers.Main) {
        val timings = linkedMapOf<String, Long>()
        val notes = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0

        page.load(url)
        val stabilized = page.awaitStable(WebTiming.STABILIZE_TIMEOUT_MS)
        timings["stabilize"] = elapsed()
        notes += "trigger=" + if (stabilized) "stable" else "timeout"
        var output = timed(timings, "extract") { page.runExtractor() }

        // Cloudflare: discard the challenge result, wait for the real page; never OCR the challenge page.
        while (output?.botChallenge() != null && elapsed() < WebTiming.CHALLENGE_MAX_MS) {
            notes += "botChallenge=${output.botChallenge()} at ${elapsed()} ms"
            page.awaitStable(minOf(WebTiming.STABILIZE_TIMEOUT_MS, WebTiming.CHALLENGE_MAX_MS - elapsed()))
            output = page.runExtractor()
        }
        if (output == null) {
            errors += "extractor returned nothing"
            return@withContext Extraction(errors = errors, notes = notes, timingsMs = timings)
        }
        val load = { LoadInfo(url, stabilized, elapsed(), timings, notes, errors) }
        if (output.botChallenge() != null) {
            notes += "${WebFailure.BOT}: still on the challenge page after ${elapsed()} ms"
            return@withContext WebResult.extraction(output, load())
        }
        if (page.consentWallShown()) {
            notes += WebFailure.CONSENT
            return@withContext WebResult.extraction(output, load())
        }

        // Late content: no ingredients or no steps yet → again every 5 s up to 30 s after the load.
        var best = WebResult.extraction(output, load())
        var retries = 0
        while (!best.recipe.hasIngredients || !best.recipe.hasSteps) {
            val wait = minOf(WebTiming.LATE_INTERVAL_MS, WebTiming.LATE_UNTIL_MS - elapsed())
            if (wait <= 0) break
            delay(wait)
            retries++
            val again = page.runExtractor() ?: continue
            if (again.botChallenge() != null) continue
            val candidate = WebResult.extraction(again, load())
            if (score(candidate) > score(best)) {
                best = candidate
                notes += "late content found on retry $retries at ${elapsed()} ms"
            }
        }
        if (retries > 0) timings["lateRetries"] = retries.toLong()

        if (!best.recipe.hasIngredients || !best.recipe.hasSteps) best = ocrFallback(best, timings, notes, errors)
        timings["total"] = elapsed()
        best.copy(timingsMs = timings + best.timingsMs.filterKeys { it.startsWith("extractor.") }, notes = notes.toList(),
            errors = (errors + best.errors).distinct())
    }

    /** Whole-page snapshot → ML Kit (tiled) → `loadText` on the OCR text; fills only what the page lacked. */
    private suspend fun ocrFallback(e: Extraction, timings: MutableMap<String, Long>, notes: MutableList<String>,
                                    errors: MutableList<String>): Extraction {
        val t = System.currentTimeMillis()
        val result = runCatching { ocr.recognize(page.snapshotSource(), WebTiming.SNAPSHOT_MAX_TILES) }
            .onFailure { errors += "ocr: ${it.message}" }.getOrNull() ?: return e
        timings["ocr"] = System.currentTimeMillis() - t
        notes += "ocrFallback: ${result.lines.size} lines"
        val raw = e.raw.copy(ocr = OcrRaw(OcrSource.WEBVIEW_SNAPSHOT, result.text, Tiling.toOcrLines(result.lines, 0), result.truncated))
        if (result.text.isBlank()) return e.copy(raw = raw)
        val structured = page.runOnText(result.text)?.let { ExtractorMerge.merge(it) } ?: return e.copy(raw = raw)
        var recipe = e.recipe
        val prov = e.provenance.toMutableMap()
        fun take(key: String, mine: List<Group>, theirs: List<Group>): List<Group> =
            if (mine.none { it.items.isNotEmpty() } && theirs.isNotEmpty()) theirs.also { prov[key] = Stage.OCR } else mine
        recipe = recipe.copy(
            ingredientGroups = take("ingredients", recipe.ingredientGroups, structured.recipe.ingredientGroups),
            stepGroups = take("steps", recipe.stepGroups, structured.recipe.stepGroups),
        )
        if (recipe.title == null && structured.recipe.title != null) {
            recipe = recipe.copy(title = structured.recipe.title)
            prov["title"] = Stage.OCR
        }
        return e.copy(recipe = recipe, provenance = prov, raw = raw)
    }

    private fun score(e: Extraction) =
        (if (e.recipe.hasIngredients) 4 else 0) + (if (e.recipe.hasSteps) 4 else 0) + (if (e.recipe.title != null) 2 else 0) +
            e.recipe.ingredientGroups.sumOf { it.items.size } * 0.001 + e.recipe.stepGroups.sumOf { it.items.size } * 0.001

    private inline fun <T> timed(timings: MutableMap<String, Long>, key: String, block: () -> T): T {
        val t = System.currentTimeMillis()
        return block().also { timings[key] = System.currentTimeMillis() - t }
    }
}

/** Off-screen WebView sized to the screen: no window, reports `visibilityState = "visible"`. */
fun offscreenWebView(context: Context): WebView {
    val dm = context.resources.displayMetrics
    return WebView(context).apply {
        measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        layout(0, 0, dm.widthPixels, dm.heightPixels)
    }
}

/** WEB route of the capture service. */
class WebSourceExtractor(private val context: Context, private val scripts: ExtractorScripts, private val ocr: OcrEngine) :
    SourceExtractor {
    override suspend fun extract(job: CaptureJob): Extraction {
        val url = job.route.url ?: return Extraction(errors = listOf("no url"))
        return withContext(Dispatchers.Main) {
            val webView = offscreenWebView(context)
            try {
                WebCaptureRunner(PageController(webView, scripts), ocr).capture(url)
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }
}
