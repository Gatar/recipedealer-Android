package com.gatar.recipedealer.web

import android.content.Context
import com.gatar.recipedealer.capture.CaptureJob
import com.gatar.recipedealer.capture.Extraction
import com.gatar.recipedealer.capture.SourceExtractor
import com.gatar.recipedealer.capture.TextCleaner
import com.gatar.recipedealer.capture.TextResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TEXT route: the cleaned text is turned into headings / lists by the seed `RecipeExtractor.loadText` and run
 * through the normal extractor in an off-screen WebView with an empty page (~0.5 s). Readability is skipped.
 */
class TextSourceExtractor(private val context: Context, private val scripts: ExtractorScripts) : SourceExtractor {
    override suspend fun extract(job: CaptureJob): Extraction {
        val text = TextCleaner.clean(job.request.text.orEmpty())
        val t0 = System.currentTimeMillis()
        val output = withContext(Dispatchers.Main) {
            val webView = offscreenWebView(context)
            try {
                val page = PageController(webView, scripts)
                webView.loadDataWithBaseURL(null, EMPTY_PAGE, "text/html", "utf-8", null)
                withTimeoutOrNull(10_000) { page.loads.receive() }
                page.runOnText(text)
            } finally {
                webView.destroy()
            }
        }
        val errors = if (output == null) listOf("text structuring failed") else emptyList()
        return TextResult.extraction(text, output, mapOf("structure" to System.currentTimeMillis() - t0), errors)
    }

    private companion object {
        const val EMPTY_PAGE = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
    }
}
