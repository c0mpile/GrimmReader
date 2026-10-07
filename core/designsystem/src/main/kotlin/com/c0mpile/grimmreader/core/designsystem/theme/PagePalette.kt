package com.c0mpile.grimmreader.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.c0mpile.grimmreader.core.model.PageTheme

/** False when the system animator scale is 0; every animation must check it. */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/**
 * Colours of the reading area under a [PageTheme]; the app UI around it stays on the app theme. E-ink pages
 * also show comic/PDF images in grayscale and turn without animation.
 */
data class PagePalette(
    val background: Color,
    val text: Color,
    val link: Color,
    val grayscaleImages: Boolean = false,
    val instantTurns: Boolean = false,
)

fun PageTheme.palette(): PagePalette =
    with(GrimmTokens) {
        when (this@palette) {
            PageTheme.EINK -> PagePalette(WarmPaper, WarmInk, WarmInk, grayscaleImages = true, instantTurns = true)
            PageTheme.LIGHT -> PagePalette(LightPage, LightText, LightPrimary)
            PageTheme.DARK -> PagePalette(DarkPage, DarkText, DarkPrimary)
            PageTheme.AMOLED -> PagePalette(Color.Black, DarkTextSecondary, DarkPrimary)
        }
    }

/** Saturation 0, contrast ×1.15; applied to comic/PDF pages on E-ink pages. */
val EinkImageFilter: ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix().apply {
            setToSaturation(0f)
            val c = 1.15f
            val t = (1f - c) * 128f
            timesAssign(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        },
    )
