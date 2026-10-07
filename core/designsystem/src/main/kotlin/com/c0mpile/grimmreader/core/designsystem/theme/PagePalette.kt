package com.c0mpile.grimmreader.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import com.c0mpile.grimmreader.core.model.EinkTint
import com.c0mpile.grimmreader.core.model.PageTheme
import kotlin.random.Random

/** False when the system animator scale is 0; every animation must check it. */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/** How pictures on the page are shown: comic/PDF pages and images inside ebooks. */
enum class PageImages { NORMAL, GRAYSCALE, WARM }

/**
 * Colours of the reading area under a [PageTheme]; the app UI around it stays on the app theme. E-ink pages
 * show pictures in grayscale and turn without animation; Night pages warm and dim them.
 */
data class PagePalette(
    val background: Color,
    val text: Color,
    val link: Color,
    val images: PageImages = PageImages.NORMAL,
    val instantTurns: Boolean = false,
)

fun PageTheme.palette(tint: EinkTint = EinkTint.WARM): PagePalette =
    with(GrimmTokens) {
        when (this@palette) {
            PageTheme.EINK -> {
                val paper = if (tint == EinkTint.WARM) WarmPaper else CoolPaper
                val ink = if (tint == EinkTint.WARM) WarmInk else CoolInk
                PagePalette(paper, ink, ink, PageImages.GRAYSCALE, instantTurns = true)
            }
            PageTheme.LIGHT -> PagePalette(LightPage, LightText, LightPrimary)
            PageTheme.SEPIA -> PagePalette(SepiaPage, SepiaText, SepiaLink)
            PageTheme.DARK -> PagePalette(DarkPage, DarkText, DarkPrimary)
            PageTheme.NIGHT -> PagePalette(NightPage, NightText, NightLink, PageImages.WARM)
            PageTheme.AMOLED -> PagePalette(Color.Black, DarkTextSecondary, DarkPrimary)
        }
    }

/** Filter for comic/PDF page bitmaps; null for [PageImages.NORMAL]. */
val PageImages.colorFilter: ColorFilter?
    get() =
        when (this) {
            PageImages.NORMAL -> null
            PageImages.GRAYSCALE -> EinkImageFilter
            PageImages.WARM -> NightImageFilter
        }

/** Saturation 0, contrast ×1.15. */
val EinkImageFilter: ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix().apply {
            setToSaturation(0f)
            val c = 1.15f
            val t = (1f - c) * 128f
            timesAssign(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        },
    )

/** Less blue and green, slightly dimmed: the picture counterpart of Night's amber text. */
private val NightImageFilter: ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, 0f, 0f, 0.72f, 0f, 0f, 0f, 0f, 0f, 0.45f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
    )

private const val GRAIN_SIZE = 128
private const val GRAIN_MAX_ALPHA = 26

/** A fixed noise tile (same seed every time), made once and tiled: no per-frame work beyond one rect. */
private val grainTile: ImageBitmap by lazy {
    val random = Random(GRAIN_SEED)
    val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) { random.nextInt(GRAIN_MAX_ALPHA) shl 24 }
    android.graphics.Bitmap
        .createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

private const val GRAIN_SEED = 7

/** Static paper grain drawn over the content (E-ink page option). Draws only; never handles input. */
fun Modifier.paperGrain(): Modifier =
    drawWithCache {
        val brush = ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))
        onDrawWithContent {
            drawContent()
            drawRect(brush)
        }
    }
