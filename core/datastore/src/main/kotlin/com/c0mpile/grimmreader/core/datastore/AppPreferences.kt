package com.c0mpile.grimmreader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.EinkTint
import com.c0mpile.grimmreader.core.model.ReaderPrefs
import com.c0mpile.grimmreader.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the first-run screen decided. */
enum class SetupState { NOT_DONE, LOCAL_ONLY, SERVER }

/** Non-secret app settings. Secrets live in [SecretStore]. */
class AppPreferences(
    private val store: DataStore<Preferences>,
) {
    val setupState: Flow<SetupState> = store.data.map { p -> enumOr(p[SETUP], SetupState.NOT_DONE) }

    val appearance: Flow<Appearance> =
        store.data.map { p ->
            Appearance(
                mode = enumOr(p[THEME], ThemeMode.SYSTEM),
                einkTint = enumOr(p[EINK_TINT], EinkTint.WARM),
                einkFlashEvery = p[EINK_FLASH] ?: 0,
                einkGrain = p[EINK_GRAIN] ?: false,
            )
        }

    val readerPrefs: Flow<ReaderPrefs> =
        store.data.map { p ->
            val defaults = ReaderPrefs()
            ReaderPrefs(
                fontFamily = p[FONT_FAMILY] ?: defaults.fontFamily,
                fontSize = p[FONT_SIZE] ?: defaults.fontSize,
                lineHeight = p[LINE_HEIGHT] ?: defaults.lineHeight,
                justify = p[JUSTIFY] ?: defaults.justify,
                hyphenate = p[HYPHENATE] ?: defaults.hyphenate,
                maxColumnCount = p[COLUMNS] ?: defaults.maxColumnCount,
                theme = p[READER_THEME] ?: defaults.theme,
            )
        }

    suspend fun setSetupState(state: SetupState) {
        store.edit { it[SETUP] = state.name }
    }

    suspend fun setAppearance(appearance: Appearance) {
        store.edit {
            it[THEME] = appearance.mode.name
            it[EINK_TINT] = appearance.einkTint.name
            it[EINK_FLASH] = appearance.einkFlashEvery
            it[EINK_GRAIN] = appearance.einkGrain
        }
    }

    suspend fun setReaderPrefs(prefs: ReaderPrefs) {
        store.edit {
            it[FONT_FAMILY] = prefs.fontFamily
            it[FONT_SIZE] = prefs.fontSize
            it[LINE_HEIGHT] = prefs.lineHeight
            it[JUSTIFY] = prefs.justify
            it[HYPHENATE] = prefs.hyphenate
            it[COLUMNS] = prefs.maxColumnCount
            it[READER_THEME] = prefs.theme
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(
        value: String?,
        default: E,
    ): E = value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private companion object {
        val SETUP = stringPreferencesKey("setup_state")
        val THEME = stringPreferencesKey("theme_mode")
        val EINK_TINT = stringPreferencesKey("eink_tint")
        val EINK_FLASH = intPreferencesKey("eink_flash_every")
        val EINK_GRAIN = booleanPreferencesKey("eink_grain")
        val FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val FONT_SIZE = intPreferencesKey("reader_font_size")
        val LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val JUSTIFY = booleanPreferencesKey("reader_justify")
        val HYPHENATE = booleanPreferencesKey("reader_hyphenate")
        val COLUMNS = intPreferencesKey("reader_columns")
        val READER_THEME = stringPreferencesKey("reader_theme")
    }
}
