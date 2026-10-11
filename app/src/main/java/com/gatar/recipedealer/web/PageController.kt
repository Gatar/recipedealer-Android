package com.gatar.recipedealer.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.ocr.TileSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/** The seed scripts from `assets/`, read once. */
class ExtractorScripts(context: Context) {
    val readability: String by lazy { context.assets.open("Readability.js").bufferedReader().readText() }
    val extractor: String by lazy { context.assets.open("extractor.js").bufferedReader().readText() }
    val stabilityObserver: String by lazy { context.assets.open("stability-observer.js").bufferedReader().readText() }
}

/**
 * Drives one WebView (off-screen or visible): injects the stability observer after every page load, runs the seed
 * extractor, detects consent walls, renders the page for OCR. All calls on the main thread.
 */
@SuppressLint("SetJavaScriptEnabled")
class PageController(val webView: WebView, private val scripts: ExtractorScripts) {
    /** DOM quiet for 5 s (from stability-observer.js). */
    val stable = Channel<Unit>(Channel.CONFLATED)
    /** Every finished page load (Cloudflare replaces its challenge page with the real one). */
    val loads = Channel<String>(Channel.CONFLATED)
    var pageFinishedCount = 0
        private set
    var extraClient: WebViewClient? = null

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
        }
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageFinishedCount++
                view.evaluateJavascript(scripts.stabilityObserver, null)
                loads.trySend(url)
                extraClient?.onPageFinished(view, url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                extraClient?.onPageStarted(view, url, favicon)
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onDomStable() {
            stable.trySend(Unit)
        }
    }

    fun load(url: String) {
        stable.tryReceive()
        webView.loadUrl(url)
    }

    /** Waits for 5 s of DOM quiet; null when [timeoutMs] passed first. */
    suspend fun awaitStable(timeoutMs: Long): Boolean = timeoutMs > 0 && withTimeoutOrNull(timeoutMs) { stable.receive() } != null

    suspend fun eval(script: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { cont.resume(it) }
    }

    /** Readability first, then the extractor – once per document. */
    private suspend fun inject() {
        if (eval("typeof window.RecipeExtractor") == "\"object\"") return
        eval(scripts.readability + "\n;true")
        eval(scripts.extractor + "\n;true")
    }

    suspend fun runExtractor(): JsonObject? {
        inject()
        return parseResult(eval("JSON.stringify(window.RecipeExtractor.run())"))
    }

    /** Text share / OCR text: `loadText` replaces the current page's body, then `run()`. */
    suspend fun runOnText(text: String): JsonObject? {
        inject()
        eval("window.RecipeExtractor.loadText(${JsonPrimitive(text)}); true")
        return parseResult(eval("JSON.stringify(window.RecipeExtractor.run())"))
    }

    /** evaluateJavascript returns the JSON encoding of the value: a quoted string holding our JSON. */
    private fun parseResult(raw: String?): JsonObject? = runCatching {
        val inner = CaptureJson.parseToJsonElement(raw ?: return null).jsonPrimitive.contentOrNull ?: return null
        CaptureJson.parseToJsonElement(inner).jsonObject
    }.getOrNull()

    /**
     * Google's "Before you continue to Google" (Gemini share pages, EU) blocks the content completely; answered
     * once in the visible WebView, the cookie persists for background captures.
     */
    suspend fun consentWallShown(): Boolean {
        val url = webView.url.orEmpty()
        if ("consent.google." in url || "consent.youtube." in url) return true
        val r = eval("""(function(){
            var t = (document.title || '') + ' ' + ((document.querySelector('h1') || {}).textContent || '');
            if (/before you continue|zanim przejdziesz/i.test(t)) return true;
            var f = document.querySelector('form[action*="consent.google"], iframe[src*="consent.google"]');
            return !!(f && f.offsetParent !== null);
        })()""")
        return r == "true"
    }

    /** Whole-page render for OCR (needs WebView.enableSlowWholeDocumentDraw() before the first WebView). */
    fun snapshotSource(): TileSource {
        val width = webView.width.coerceAtLeast(1)
        val height = (webView.contentHeight * webView.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (webView.height < height) {
            webView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            webView.layout(0, 0, width, height)
        }
        return object : TileSource {
            override val width = width
            override val height = height
            override suspend fun render(top: Int, bottom: Int): Bitmap {
                val bmp = Bitmap.createBitmap(width, bottom - top, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                canvas.translate(0f, -top.toFloat())
                webView.draw(canvas)
                return bmp
            }
        }
    }
}

/** Output field helpers. */
fun JsonObject.botChallenge(): String? = str("botChallenge")
