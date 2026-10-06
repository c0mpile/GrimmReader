package com.c0mpile.grimmreader.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED, EINK }

enum class EinkTint { WARM, COOL }

/** App-wide appearance. The E-ink look options only apply when [mode] is [ThemeMode.EINK]. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val einkTint: EinkTint = EinkTint.WARM,
    /** Simulated refresh flash every N page turns; 0 = off. */
    val einkFlashEvery: Int = 0,
    val einkGrain: Boolean = false,
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
)
