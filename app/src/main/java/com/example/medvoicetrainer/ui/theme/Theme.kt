package com.example.medvoicetrainer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = TextDark,
    primaryContainer = DarkBrandTint,
    onPrimaryContainer = TextLight,
    secondary = PrimaryBlue,
    onSecondary = LightSurface,
    tertiary = AccentAmber,
    onTertiary = OnAccentInk,
    background = DarkBackground,
    onBackground = TextLight,
    surface = DarkSurface,
    onSurface = TextLight,
    onSurfaceVariant = TextMutedDark,
    error = DangerRedStrong,
    errorContainer = DangerContainer.copy(alpha = 0.18f)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = LightSurface,
    primaryContainer = BrandTint,
    onPrimaryContainer = TextDark,
    secondary = PrimaryBright,
    onSecondary = LightSurface,
    tertiary = AccentAmber,
    onTertiary = OnAccentInk,
    background = LightBackground,
    onBackground = TextDark,
    surface = LightSurface,
    onSurface = TextDark,
    onSurfaceVariant = TextMuted,
    error = DangerRedStrong,
    errorContainer = DangerContainer
)

@Composable
fun MedVoiceTrainerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set to false to enforce our exact brand palette
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            try {
                var context: android.content.Context? = view.context
                while (context is android.content.ContextWrapper && context !is Activity) {
                    val base = context.baseContext
                    if (base == null || base === context) {
                        context = null
                        break
                    }
                    context = base
                }
                if (context is Activity) {
                    val window = context.window
                    window.statusBarColor = colorScheme.secondary.toArgb()
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // Ignore styling crashes to protect runtime on preview/emulator
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
