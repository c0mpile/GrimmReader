package com.c0mpile.grimmreader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.BookLayout
import com.c0mpile.grimmreader.core.model.BookSort
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.core.model.LibraryView
import com.c0mpile.grimmreader.core.model.ReaderPrefs
import com.c0mpile.grimmreader.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the first-run screen decided. */
enum class SetupState { NOT_DONE, LOCAL_ONLY, SERVER }

/** Non-secret app settings. Secrets live in [SecretStore]. */
class AppPreferences(
    private val store: DataStore<Preferences>,
) {
    val setupState: Flow<SetupState> = store.data.map { p -> enumOr(p[SETUP], SetupState.NOT_DONE) }

    /** Modes saved by older builds (System, Light, E-ink) fall back to Dark. */
    val appearance: Flow<Appearance> = store.data.map { p -> Appearance(mode = enumOr(p[THEME], ThemeMode.DARK)) }

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
                pageTheme = enumOr(p[PAGE_THEME], defaults.pageTheme),
            )
        }

    val libraryView: Flow<LibraryView> =
        store.data.map { p ->
            LibraryView(
                scope = LibraryScope.decode(p[LIBRARY_SCOPE]),
                mode = enumOr(p[LIBRARY_MODE], BrowseMode.BOOKS),
                sort = enumOr(p[LIBRARY_SORT], BookSort.TITLE),
                layout = enumOr(p[LIBRARY_LAYOUT], BookLayout.GRID),
            )
        }

    suspend fun setLibraryView(view: LibraryView) {
        store.edit {
            it[LIBRARY_SCOPE] = view.scope.encode()
            it[LIBRARY_MODE] = view.mode.name
            it[LIBRARY_SORT] = view.sort.name
            it[LIBRARY_LAYOUT] = view.layout.name
        }
    }

    /** When the library of server row [serverId] was last mirrored successfully (epoch ms); 0 when never. */
    suspend fun libraryRefreshedAt(serverId: Long): Long {
        val value = store.data.first()[LIBRARY_REFRESHED] ?: return 0
        return if (value.substringBefore(':') == serverId.toString()) value.substringAfter(':').toLongOrNull() ?: 0 else 0
    }

    suspend fun setLibraryRefreshedAt(
        serverId: Long,
        at: Long,
    ) {
        store.edit { it[LIBRARY_REFRESHED] = "$serverId:$at" }
    }

    suspend fun setSetupState(state: SetupState) {
        store.edit { it[SETUP] = state.name }
    }

    suspend fun setAppearance(appearance: Appearance) {
        store.edit { it[THEME] = appearance.mode.name }
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
            it[PAGE_THEME] = prefs.pageTheme.name
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(
        value: String?,
        default: E,
    ): E = value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private companion object {
        val SETUP = stringPreferencesKey("setup_state")
        val THEME = stringPreferencesKey("theme_mode")
        val FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val FONT_SIZE = intPreferencesKey("reader_font_size")
        val LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val JUSTIFY = booleanPreferencesKey("reader_justify")
        val HYPHENATE = booleanPreferencesKey("reader_hyphenate")
        val COLUMNS = intPreferencesKey("reader_columns")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val PAGE_THEME = stringPreferencesKey("reader_page_theme")
        val LIBRARY_SCOPE = stringPreferencesKey("library_scope")
        val LIBRARY_MODE = stringPreferencesKey("library_mode")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
        val LIBRARY_REFRESHED = stringPreferencesKey("library_refreshed_at")
    }
}
