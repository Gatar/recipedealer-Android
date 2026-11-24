package com.gatar.recipedealer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatar.recipedealer.container
import com.gatar.recipedealer.settings.ColorTheme

/**
 * The app's colors from the color theme setting. SYSTEM (the original look): Material You colors from the wallpaper,
 * light or dark as the system says. The others are fixed palettes (`Color.kt`), whatever the system mode.
 */
@Composable
fun RecipeDealerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings by context.container.settings.settings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (settings.colorTheme) {
        ColorTheme.SYSTEM -> if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        ColorTheme.LIGHT -> SageLight
        ColorTheme.DARK -> SageDark
        ColorTheme.KITCHEN -> Kitchen
    }
    // enableEdgeToEdge picks the status / navigation bar icon color from the system mode; a fixed theme decides itself
    val dark = colorScheme.background.luminance() < 0.5f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

/** Whether the current color scheme is a dark one (the setting may differ from the system mode). */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
