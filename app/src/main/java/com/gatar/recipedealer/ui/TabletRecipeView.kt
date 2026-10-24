package com.gatar.recipedealer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatar.recipedealer.R
import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.recipes.CachedRecipe

// Kitchen tablet: always white with dark text on ecru cards, also in the dark theme (read from a distance).
private val PageBackground = Color.White
private val Ecru = Color(0xFFF3EEDC)
private val Ink = Color(0xFF2B2622)
private val InkMuted = Color(0xFF6B6157)
private val CardShape = RoundedCornerShape(20.dp)
/** Larger than the corner radius: text and the photo never reach the rounded corners. */
private val CardPadding = 24.dp
private val Gap = 20.dp

/**
 * Recipe detail for the kitchen tablet: title (1/3) and photo (2/3) side by side, then ingredients and steps at
 * full width. Chosen in the settings ("Widok tabletu"), not by screen size.
 */
@Composable
fun TabletRecipeView(cached: CachedRecipe, recipe: Recipe, banners: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(PageBackground).verticalScroll(rememberScrollState()).padding(Gap),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        banners()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Card(Modifier.weight(1f).fillMaxHeight()) {
                // a narrow column (the tablet view on a phone) would break the title inside words
                val titleStyle = if (LocalConfiguration.current.screenWidthDp >= 600) MaterialTheme.typography.headlineLarge
                    else MaterialTheme.typography.titleMedium
                Text(cached.title ?: stringResource(R.string.untitled), style = titleStyle,
                    fontWeight = FontWeight.SemiBold, color = Ink)
                val meta = listOfNotNull(
                    recipe.servings?.text?.let { "Porcje: $it" },
                    recipe.times.prepMinutes?.let { "Przygotowanie: $it min" },
                    recipe.times.cookMinutes?.let { "Gotowanie: $it min" },
                    recipe.times.totalMinutes?.let { "Razem: $it min" },
                )
                meta.forEach { Text(it, style = if (LocalConfiguration.current.screenWidthDp >= 600) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodySmall, color = InkMuted) }
            }
            Card(Modifier.weight(2f)) {
                val image = cachedImage(cached.thumbnailBlob ?: cached.recipeId, 1600) { recipeImage(cached, it) }
                val photo = Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(12.dp))
                if (image != null) Image(image, null, photo, contentScale = ContentScale.Crop)
                else Box(photo.background(Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Restaurant, null, Modifier.size(64.dp), tint = InkMuted)
                }
            }
        }
        if (recipe.ingredientGroups.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                CardTitle(stringResource(R.string.section_ingredients))
                Groups(recipe.ingredientGroups) { _, item -> "•  $item" }
            }
        }
        if (recipe.stepGroups.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                CardTitle(stringResource(R.string.section_steps))
                Groups(recipe.stepGroups) { i, item -> "${i + 1}.  $item" }
            }
        }
        if (recipe.notes.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                CardTitle("Wskazówki")
                recipe.notes.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge, color = Ink) }
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(CardShape).background(Ecru).padding(CardPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Ink)
}

@Composable
private fun Groups(groups: List<Group>, line: (Int, String) -> String) {
    groups.forEach { g ->
        g.name?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.padding(top = 6.dp))
        }
        g.items.forEachIndexed { i, item -> Text(line(i, item), style = MaterialTheme.typography.bodyLarge, color = Ink) }
    }
}
