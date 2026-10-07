package com.c0mpile.grimmreader.core.model

/** App theme. The app UI is always dark; AMOLED uses true black. Reader pages have their own [PageTheme]. */
enum class ThemeMode { DARK, AMOLED }

/**
 * Colours of the reading area only (the book's pages). Menus, sheets and the reader chrome follow the app
 * theme. [EINK] is paper and ink with grayscale comic/PDF images and instant page turns.
 */
enum class PageTheme { EINK, LIGHT, DARK, AMOLED }

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
    val maxColumnCount: Int = 1,
    val theme: String = "default",
    val pageTheme: PageTheme = PageTheme.DARK,
)
