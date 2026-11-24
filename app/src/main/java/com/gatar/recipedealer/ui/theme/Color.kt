package com.gatar.recipedealer.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Fixed palettes for the color theme setting (user, 2026-11-24). Every role a component reads is set, including the
// surface containers (navigation bar, cards, menus): left out, they fall back to the Material baseline's purple tint.

/** "Jasny": sage green on a warm off-white, matching the green tag pills. */
val SageLight = lightColorScheme(
    primary = Color(0xFF3F6837), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC0EFB1), onPrimaryContainer = Color(0xFF002201),
    secondary = Color(0xFF54634D), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E8CD), onSecondaryContainer = Color(0xFF121F0E),
    tertiary = Color(0xFF386667), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBBEBEC), onTertiaryContainer = Color(0xFF002021),
    background = Color(0xFFF8FAF0), onBackground = Color(0xFF191D17),
    surface = Color(0xFFF8FAF0), onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDFE4D7), onSurfaceVariant = Color(0xFF43483F),
    outline = Color(0xFF73796E), outlineVariant = Color(0xFFC3C8BC),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F5EA),
    surfaceContainer = Color(0xFFECEFE4), surfaceContainerHigh = Color(0xFFE6E9DE),
    surfaceContainerHighest = Color(0xFFE1E4D9), surfaceDim = Color(0xFFD8DBD1), surfaceBright = Color(0xFFF8FAF0),
    inverseSurface = Color(0xFF2E322B), inverseOnSurface = Color(0xFFEFF2E7), inversePrimary = Color(0xFFA5D396),
)

/** "Ciemny": the same sage green on a near-black green-grey. */
val SageDark = darkColorScheme(
    primary = Color(0xFFA5D396), onPrimary = Color(0xFF11380B),
    primaryContainer = Color(0xFF275020), onPrimaryContainer = Color(0xFFC0EFB1),
    secondary = Color(0xFFBBCBB2), onSecondary = Color(0xFF263422),
    secondaryContainer = Color(0xFF3C4B37), onSecondaryContainer = Color(0xFFD7E8CD),
    tertiary = Color(0xFFA0CFD0), onTertiary = Color(0xFF003738),
    tertiaryContainer = Color(0xFF1E4E4F), onTertiaryContainer = Color(0xFFBBEBEC),
    background = Color(0xFF11140F), onBackground = Color(0xFFE1E4D9),
    surface = Color(0xFF11140F), onSurface = Color(0xFFE1E4D9),
    surfaceVariant = Color(0xFF43483F), onSurfaceVariant = Color(0xFFC3C8BC),
    outline = Color(0xFF8D9387), outlineVariant = Color(0xFF43483F),
    surfaceContainerLowest = Color(0xFF0C0F0A), surfaceContainerLow = Color(0xFF191D17),
    surfaceContainer = Color(0xFF1D211B), surfaceContainerHigh = Color(0xFF282B25),
    surfaceContainerHighest = Color(0xFF33362F), surfaceDim = Color(0xFF11140F), surfaceBright = Color(0xFF373A34),
    inverseSurface = Color(0xFFE1E4D9), inverseOnSurface = Color(0xFF2E322B), inversePrimary = Color(0xFF3F6837),
)

/** "Kuchnia": terracotta and olive mustard on cream, a warm light theme like an old cookbook. */
val Kitchen = lightColorScheme(
    primary = Color(0xFF9A4521), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF), onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFF77574C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCF), onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF6A5F2F), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E4A7), onTertiaryContainer = Color(0xFF221B00),
    background = Color(0xFFFFF8F0), onBackground = Color(0xFF231A16),
    surface = Color(0xFFFFF8F0), onSurface = Color(0xFF231A16),
    surfaceVariant = Color(0xFFF5DED6), onSurfaceVariant = Color(0xFF53433E),
    outline = Color(0xFF85736D), outlineVariant = Color(0xFFD8C2BA),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF1E8),
    surfaceContainer = Color(0xFFFBEBE2), surfaceContainerHigh = Color(0xFFF5E5DC),
    surfaceContainerHighest = Color(0xFFEFDFD7), surfaceDim = Color(0xFFE8D7CF), surfaceBright = Color(0xFFFFF8F0),
    inverseSurface = Color(0xFF392E2B), inverseOnSurface = Color(0xFFFFEDE6), inversePrimary = Color(0xFFFFB59A),
)
