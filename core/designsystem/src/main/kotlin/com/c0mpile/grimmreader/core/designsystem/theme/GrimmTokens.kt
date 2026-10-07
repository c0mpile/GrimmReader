package com.c0mpile.grimmreader.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** Grimmory web tokens ("grimmory" theme) mapped onto Material 3 roles (PLAN §8). */
internal object GrimmTokens {
    val LightPrimary = Color(0xFFF54900)
    val LightPage = Color(0xFFFAFAF9)
    val LightText = Color(0xFF0C0A09)
    val DarkPrimary = Color(0xFFFF8904)
    val DarkOnPrimary = Color(0xFF0A0A0A)
    val DarkPage = Color(0xFF171717)
    val DarkCard = Color(0xFF262626)
    val DarkApp = Color(0xFF0E0B0A)
    val DarkBorder = Color(0xFF404040)
    val DarkText = Color(0xFFFAFAFA)
    val DarkTextSecondary = Color(0xFFD4D4D4)
    val WarmPaper = Color(0xFFF4F1EA)
    val WarmInk = Color(0xFF1A1A1A)
    val CoolPaper = Color(0xFFEEF0EF)
    val CoolInk = Color(0xFF161819)

    // Page themes: Sepia matches the Grimmory web reader's light Sepia; Night is a warm near-black with
    // amber (low-blue, about 2300 K) text.
    val SepiaPage = Color(0xFFF1E8D0)
    val SepiaText = Color(0xFF5B4636)
    val SepiaLink = Color(0xFF008B8B)
    val NightPage = Color(0xFF0E0C0A)
    val NightText = Color(0xFFE0A46E)
    val NightLink = Color(0xFFF2B872)
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

private fun Color.compositeOn(background: Color): Color =
    Color(
        red = red * alpha + background.red * (1 - alpha),
        green = green * alpha + background.green * (1 - alpha),
        blue = blue * alpha + background.blue * (1 - alpha),
    )
