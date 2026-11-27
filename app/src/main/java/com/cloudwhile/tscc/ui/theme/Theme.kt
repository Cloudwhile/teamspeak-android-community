package com.cloudwhile.tscc.ui.theme

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.core.view.WindowCompat
import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.ui.i18n.AppStrings
import com.cloudwhile.tscc.ui.i18n.LocalAppStrings
import com.cloudwhile.tscc.ui.i18n.resolveAppStrings
import java.util.Locale

@Immutable
data class AppColors(
    val isDark: Boolean,
    val canvas: Color,
    val card: Color,
    val cardElevated: Color,
    val cardPressed: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val pillActiveBg: Color,
    val pillActiveText: Color,
    val periwinkle: Color,
    val periwinkleCard: Color,
    val periwinkleSoft: Color,
    val marigold: Color,
    val marigoldSoft: Color,
    val coral: Color,
    val coralSoft: Color,
    val emerald: Color,
    val emeraldSoft: Color,
    val inkOnPastel: Color,
    val inkMutedOnPastel: Color
)

val DefaultDarkAppColors = AppColors(
    isDark = true,
    canvas = AppDarkCanvas,
    card = AppDarkCard,
    cardElevated = AppDarkElevated,
    cardPressed = AppDarkPressed,
    border = AppDarkBorder,
    textPrimary = AppDarkTextPrimary,
    textSecondary = AppDarkTextSecondary,
    textTertiary = AppDarkTextTertiary,
    pillActiveBg = Color(0xFFFFFFFF),
    pillActiveText = Color(0xFF141416),
    periwinkle = AccentPeriwinkle,
    periwinkleCard = AccentPeriwinkleCard,
    periwinkleSoft = AccentPeriwinkleSoft,
    marigold = AccentMarigold,
    marigoldSoft = AccentMarigoldSoft,
    coral = AccentCoral,
    coralSoft = AccentCoralSoft,
    emerald = AccentEmerald,
    emeraldSoft = AccentEmeraldSoft,
    inkOnPastel = InkOnPastel,
    inkMutedOnPastel = InkMutedOnPastel
)

val DefaultLightAppColors = AppColors(
    isDark = false,
    canvas = AppLightCanvas,
    card = AppLightCard,
    cardElevated = AppLightElevated,
    cardPressed = AppLightPressed,
    border = AppLightBorder,
    textPrimary = AppLightTextPrimary,
    textSecondary = AppLightTextSecondary,
    textTertiary = AppLightTextTertiary,
    pillActiveBg = Color(0xFF141416),
    pillActiveText = Color(0xFFFFFFFF),
    periwinkle = AccentPeriwinkleLightMode,
    periwinkleCard = AccentPeriwinkleCard,
    periwinkleSoft = AccentPeriwinkleSoft,
    marigold = AccentMarigoldLightMode,
    marigoldSoft = AccentMarigoldSoft,
    coral = AccentCoralLightMode,
    coralSoft = AccentCoralSoft,
    emerald = AccentEmeraldLightMode,
    emeraldSoft = AccentEmeraldSoft,
    inkOnPastel = InkOnPastel,
    inkMutedOnPastel = InkMutedOnPastel
)

val LocalAppColors = compositionLocalOf { DefaultDarkAppColors }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current

    val strings: AppStrings
        @Composable
        @ReadOnlyComposable
        get() = LocalAppStrings.current
}

private val AppDarkMaterialScheme = darkColorScheme(
    primary = AccentPeriwinkle,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF282A48),
    onPrimaryContainer = Color(0xFFE0E2FF),
    secondary = AccentEmerald,
    onSecondary = Color(0xFF06291D),
    secondaryContainer = Color(0xFF143D2E),
    onSecondaryContainer = Color(0xFFBBF7D0),
    tertiary = AccentMarigold,
    onTertiary = InkOnPastel,
    background = AppDarkCanvas,
    onBackground = AppDarkTextPrimary,
    surface = AppDarkCard,
    onSurface = AppDarkTextPrimary,
    surfaceVariant = AppDarkElevated,
    onSurfaceVariant = AppDarkTextSecondary,
    outline = Color(0xFF2E2E34),
    error = AccentCoral,
    onError = Color.White
)

private val AppLightMaterialScheme = lightColorScheme(
    primary = AccentPeriwinkle,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EAFF),
    onPrimaryContainer = Color(0xFF1E2266),
    secondary = AccentEmeraldLightMode,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = AccentMarigold,
    onTertiary = InkOnPastel,
    background = AppLightCanvas,
    onBackground = AppLightTextPrimary,
    surface = AppLightCard,
    onSurface = AppLightTextPrimary,
    surfaceVariant = AppLightElevated,
    onSurfaceVariant = AppLightTextSecondary,
    outline = AppLightBorder,
    error = AccentCoral,
    onError = Color.White
)

val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

@Composable
fun TsccTheme(
    darkTheme: Boolean = true,
    language: AppLanguage = AppLanguage.SYSTEM,
    animateTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val sysLocale = ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    val appStrings = remember(language, sysLocale) {
        resolveAppStrings(language, sysLocale)
    }

    val appColors = if (!animateTheme) {
        if (darkTheme) DefaultDarkAppColors else DefaultLightAppColors
    } else {
        val darkFraction by animateFloatAsState(
            targetValue = if (darkTheme) 1f else 0f,
            animationSpec = tween(durationMillis = 220),
            label = "themeMorph"
        )
        remember(darkFraction, darkTheme) {
            when (darkFraction) {
                1f -> DefaultDarkAppColors
                0f -> DefaultLightAppColors
                else -> AppColors(
                    isDark = darkTheme,
                    canvas = lerp(AppLightCanvas, AppDarkCanvas, darkFraction),
                    card = lerp(AppLightCard, AppDarkCard, darkFraction),
                    cardElevated = lerp(AppLightElevated, AppDarkElevated, darkFraction),
                    cardPressed = lerp(AppLightPressed, AppDarkPressed, darkFraction),
                    border = lerp(AppLightBorder, AppDarkBorder, darkFraction),
                    textPrimary = lerp(AppLightTextPrimary, AppDarkTextPrimary, darkFraction),
                    textSecondary = lerp(AppLightTextSecondary, AppDarkTextSecondary, darkFraction),
                    textTertiary = lerp(AppLightTextTertiary, AppDarkTextTertiary, darkFraction),
                    pillActiveBg = lerp(Color(0xFF141416), Color(0xFFFFFFFF), darkFraction),
                    pillActiveText = lerp(Color(0xFFFFFFFF), Color(0xFF141416), darkFraction),
                    periwinkle = lerp(AccentPeriwinkleLightMode, AccentPeriwinkle, darkFraction),
                    periwinkleCard = AccentPeriwinkleCard,
                    periwinkleSoft = AccentPeriwinkleSoft,
                    marigold = lerp(AccentMarigoldLightMode, AccentMarigold, darkFraction),
                    marigoldSoft = AccentMarigoldSoft,
                    coral = lerp(AccentCoralLightMode, AccentCoral, darkFraction),
                    coralSoft = AccentCoralSoft,
                    emerald = lerp(AccentEmeraldLightMode, AccentEmerald, darkFraction),
                    emeraldSoft = AccentEmeraldSoft,
                    inkOnPastel = InkOnPastel,
                    inkMutedOnPastel = InkMutedOnPastel
                )
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalAppStrings provides appStrings
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) AppDarkMaterialScheme else AppLightMaterialScheme,
            typography = AppTypography,
            content = content
        )
    }
}
