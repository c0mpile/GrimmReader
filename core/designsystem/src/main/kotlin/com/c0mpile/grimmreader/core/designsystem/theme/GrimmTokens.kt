package com.c0mpile.grimmreader.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.c0mpile.grimmreader.core.model.EinkTint

/** Grimmory web tokens ("grimmory" theme) mapped onto Material 3 roles (PLAN §8). */
internal object GrimmTokens {
    val LightPrimary = Color(0xFFF54900)
    val LightPage = Color(0xFFFAFAF9)
    val LightCard = Color(0xFFFFFFFF)
    val LightApp = Color(0xFFF1EFED)
    val LightBorder = Color(0xFFE7E5E4)
    val LightText = Color(0xFF0C0A09)
    val LightTextSecondary = Color(0xFF57534D)
    val DarkPrimary = Color(0xFFFF8904)
    val DarkOnPrimary = Color(0xFF0A0A0A)
    val DarkPage = Color(0xFF171717)
    val DarkCard = Color(0xFF262626)
    val DarkApp = Color(0xFF0E0B0A)
    val DarkBorder = Color(0xFF404040)
    val DarkText = Color(0xFFFAFAFA)
    val DarkTextSecondary = Color(0xFFD4D4D4)
    val DangerLight = Color(0xFFFB2C36)
    val WarmPaper = Color(0xFFF4F1EA)
    val WarmInk = Color(0xFF1A1A1A)
    val CoolPaper = Color(0xFFEEF0EF)
    val CoolInk = Color(0xFF161819)
}

internal fun lightGrimmScheme(): ColorScheme =
    with(GrimmTokens) {
        lightColorScheme(
            primary = LightPrimary,
            onPrimary = Color.White,
            primaryContainer = LightPrimary.copy(alpha = 0.10f).compositeOn(LightCard),
            onPrimaryContainer = LightText,
            background = LightPage,
            onBackground = LightText,
            surface = LightCard,
            onSurface = LightText,
            surfaceVariant = LightApp,
            onSurfaceVariant = LightTextSecondary,
            surfaceContainerLowest = LightCard,
            surfaceContainerLow = LightApp,
            surfaceContainer = LightApp,
            surfaceContainerHigh = LightBorder.copy(alpha = 0.6f).compositeOn(LightCard),
            surfaceContainerHighest = LightBorder,
            outline = LightBorder,
            outlineVariant = LightBorder,
            error = DangerLight,
        )
    }

internal fun darkGrimmScheme(amoled: Boolean): ColorScheme =
    with(GrimmTokens) {
        val page = if (amoled) Color.Black else DarkPage
        val card = if (amoled) Color.Black else DarkCard
        val app = if (amoled) Color.Black else DarkApp
        val border = if (amoled) DarkText.copy(alpha = 0.18f).compositeOn(Color.Black) else DarkBorder
        darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimary.copy(alpha = 0.16f).compositeOn(card),
            onPrimaryContainer = DarkText,
            background = page,
            onBackground = DarkText,
            surface = card,
            onSurface = DarkText,
            surfaceVariant = app,
            onSurfaceVariant = DarkTextSecondary,
            surfaceContainerLowest = page,
            surfaceContainerLow = app,
            surfaceContainer = card,
            surfaceContainerHigh = card,
            surfaceContainerHighest = border,
            outline = border,
            outlineVariant = border,
            surfaceTint = if (amoled) Color.Transparent else DarkPrimary,
        )
    }

/** Paper and ink only: no accent colour, no tinted surfaces; secondary text is ink at 80 %. */
internal fun einkScheme(tint: EinkTint): ColorScheme =
    with(GrimmTokens) {
        val paper = if (tint == EinkTint.WARM) WarmPaper else CoolPaper
        val ink = if (tint == EinkTint.WARM) WarmInk else CoolInk
        lightColorScheme(
            primary = ink,
            onPrimary = paper,
            primaryContainer = paper,
            onPrimaryContainer = ink,
            secondary = ink,
            onSecondary = paper,
            secondaryContainer = paper,
            onSecondaryContainer = ink,
            tertiary = ink,
            onTertiary = paper,
            background = paper,
            onBackground = ink,
            surface = paper,
            onSurface = ink,
            surfaceVariant = paper,
            onSurfaceVariant = ink.copy(alpha = 0.8f).compositeOn(paper),
            surfaceTint = Color.Transparent,
            surfaceBright = paper,
            surfaceDim = paper,
            surfaceContainerLowest = paper,
            surfaceContainerLow = paper,
            surfaceContainer = paper,
            surfaceContainerHigh = paper,
            surfaceContainerHighest = paper,
            outline = ink,
            outlineVariant = ink,
            error = ink,
            onError = paper,
            inverseSurface = ink,
            inverseOnSurface = paper,
        )
    }

private fun Color.compositeOn(background: Color): Color =
    Color(
        red = red * alpha + background.red * (1 - alpha),
        green = green * alpha + background.green * (1 - alpha),
        blue = blue * alpha + background.blue * (1 - alpha),
    )
