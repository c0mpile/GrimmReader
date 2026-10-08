package com.c0mpile.grimmreader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.BookLayout
import com.c0mpile.grimmreader.core.model.BookSort
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
                gap = p[GAP] ?: defaults.gap,
                maxInlineSize = p[MAX_INLINE] ?: defaults.maxInlineSize,
                maxBlockSize = p[MAX_BLOCK] ?: defaults.maxBlockSize,
                theme = p[READER_THEME] ?: defaults.theme,
                pageTheme = enumOr(p[PAGE_THEME], defaults.pageTheme),
                einkTint = enumOr(p[EINK_TINT], defaults.einkTint),
                einkGrain = p[EINK_GRAIN] ?: defaults.einkGrain,
                einkFlashEvery = p[EINK_FLASH] ?: defaults.einkFlashEvery,
            )
        }

    val libraryView: Flow<LibraryView> =
        store.data.map { p ->
            LibraryView(
                sort = enumOr(p[LIBRARY_SORT], BookSort.TITLE),
                layout = enumOr(p[LIBRARY_LAYOUT], BookLayout.GRID),
            )
        }

    suspend fun setLibraryView(view: LibraryView) {
        store.edit {
            it[LIBRARY_SORT] = view.sort.name
            it[LIBRARY_LAYOUT] = view.layout.name
        }
    }

    /** Folders (SAF tree URIs) scanned for local books, read in place. */
    val bookFolders: Flow<List<String>> = store.data.map { p -> p[BOOK_FOLDERS].orEmpty().sorted() }

    /** Folder (SAF tree URI) that server downloads are saved to; null = app storage. */
    val downloadFolder: Flow<String?> = store.data.map { p -> p[DOWNLOAD_FOLDER] }

    suspend fun setBookFolders(folders: Set<String>) {
        store.edit { it[BOOK_FOLDERS] = folders }
    }

    suspend fun setDownloadFolder(folder: String?) {
        store.edit { if (folder == null) it.remove(DOWNLOAD_FOLDER) else it[DOWNLOAD_FOLDER] = folder }
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
            it[GAP] = prefs.gap
            it[MAX_INLINE] = prefs.maxInlineSize
            it[MAX_BLOCK] = prefs.maxBlockSize
            it[READER_THEME] = prefs.theme
            it[PAGE_THEME] = prefs.pageTheme.name
            it[EINK_TINT] = prefs.einkTint.name
            it[EINK_GRAIN] = prefs.einkGrain
            it[EINK_FLASH] = prefs.einkFlashEvery
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

        // New key: the old "reader_columns" held 1 without being applied (foliate used its own 2).
        val COLUMNS = intPreferencesKey("reader_max_columns")
        val GAP = floatPreferencesKey("reader_gap")
        val MAX_INLINE = intPreferencesKey("reader_max_inline_size")
        val MAX_BLOCK = intPreferencesKey("reader_max_block_size")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val PAGE_THEME = stringPreferencesKey("reader_page_theme")

        // Same keys as the former app-wide E-ink look, so earlier choices carry over.
        val EINK_TINT = stringPreferencesKey("eink_tint")
        val EINK_FLASH = intPreferencesKey("eink_flash_every")
        val EINK_GRAIN = booleanPreferencesKey("eink_grain")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
        val LIBRARY_REFRESHED = stringPreferencesKey("library_refreshed_at")
        val BOOK_FOLDERS = stringSetPreferencesKey("book_folders")
        val DOWNLOAD_FOLDER = stringPreferencesKey("download_folder")
    }
}
