package com.c0mpile.grimmreader.core.model

/** App theme. The app UI is always dark; AMOLED uses true black. Reader pages have their own [PageTheme]. */
enum class ThemeMode { DARK, AMOLED }

/**
 * Colours of the reading area only (the book's pages). Menus, sheets and the reader chrome follow the app
 * theme. [EINK] is paper and ink with grayscale images and instant page turns (see the `eink*` options in
 * [ReaderPrefs]); [NIGHT] is a dark page with amber, low-blue text and warm-filtered images.
 */
enum class PageTheme { EINK, LIGHT, SEPIA, DARK, NIGHT, AMOLED }

enum class EinkTint { WARM, COOL }

data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
)

/** Reader typography; field names follow the web's `ebookReaderSetting` so they can sync later. */
data class ReaderPrefs(
    val fontFamily: String = "Literata",
    val fontSize: Int = 18,
    val lineHeight: Float = 1.5f,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    /** Page layout (foliate paginator): columns side by side when wide enough, gap between them (0..1). */
    val maxColumnCount: Int = 2,
    val gap: Float = 0.05f,
    /** Largest text column width and page height in CSS px. */
    val maxInlineSize: Int = 720,
    val maxBlockSize: Int = 1440,
    val theme: String = "default",
    val pageTheme: PageTheme = PageTheme.DARK,
    /** E-ink page options, used only when [pageTheme] is [PageTheme.EINK]. */
    val einkTint: EinkTint = EinkTint.WARM,
    val einkGrain: Boolean = false,
    /** Simulated refresh flash every N page turns; 0 = off. */
    val einkFlashEvery: Int = 0,
    /** Page turns by tap and/or swipe, both only in the left and right zones, each [turnZone] % of the width. */
    val tapToTurn: Boolean = true,
    val swipeToTurn: Boolean = true,
    val turnZone: Int = 25,
)
