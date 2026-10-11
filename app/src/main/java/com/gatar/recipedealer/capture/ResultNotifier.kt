package com.gatar.recipedealer.capture

import android.content.Context
import com.gatar.recipedealer.Notifications
import com.gatar.recipedealer.R
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.store.CaptureEntry

object ResultNotifier {
    fun notify(context: Context, entry: CaptureEntry) {
        val capture = entry.capture ?: return
        if (isUsable(capture)) {
            Notifications.saved(context, entry, summary(context, capture))
        } else {
            Notifications.failed(context, entry, context.getString(R.string.notif_failed_text))
        }
    }

    /** IMAGE / PDF are always worth sending (the backend reads the raw material); others need a structured part. */
    fun isUsable(c: Capture) = when (c.source.type) {
        SourceType.IMAGE, SourceType.PDF -> c.attachments.isNotEmpty() || !c.raw.ocr?.text.isNullOrBlank() ||
            !c.raw.pdfText.isNullOrBlank()
        else -> c.completeness.hasIngredients || c.completeness.hasSteps
    }

    fun summary(context: Context, c: Capture): String {
        val ingredients = c.recipe.ingredientGroups.sumOf { it.items.size }
        val steps = c.recipe.stepGroups.sumOf { it.items.size }
        return context.getString(R.string.notif_summary, ingredients, steps, (c.completeness.score * 100).toInt())
    }
}
