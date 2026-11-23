package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppThemeMode

private val SonoraDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldOnContainer,
    secondary = CyanSecondary,
    onSecondary = CyanOnSecondary,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = CyanOnContainer,
    tertiary = VioletTertiary,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceHigh,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = OutlineSubtle,
    error = CoralError
)

private val SonoraLightColorScheme = lightColorScheme(
    primary = Color(0xFF00C875),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2F5D4),
    onPrimaryContainer = Color(0xFF032911),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF082F49),
    background = Color(0xFF090A0E),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF141720),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E222F),
    onSurfaceVariant = Color(0xFF8F97A6),
    surfaceContainerLowest = Color(0xFF090A0E),
    surfaceContainerLow = Color(0xFF141720),
    surfaceContainer = Color(0xFF1E222F),
    surfaceContainerHigh = Color(0xFF282D3E),
    surfaceContainerHighest = Color(0xFF32384D),
    outline = Color(0xFF212636),
    error = Color(0xFFDC2626)
)

private val SonoraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SonoraTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> SonoraDarkColorScheme
        else -> SonoraDarkColorScheme // Default to sleek dark design as shown in reference photo
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SonoraTypography,
        shapes = SonoraShapes,
        content = content
    )
}
