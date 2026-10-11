package com.gatar.recipedealer.capture

import com.gatar.recipedealer.model.Raw

/** TEXT without structuring: only the cleaned text (structuring runs in a WebView, see TextExtractor). */
val CleanTextOnly = SourceExtractor { job ->
    Extraction(raw = Raw(text = TextCleaner.clean(job.request.text.orEmpty())))
}

/** Placeholder for routes whose extractor is not available: the capture still records what arrived. */
fun notSupported(what: String) = SourceExtractor { Extraction(notes = listOf("$what extraction not available")) }
