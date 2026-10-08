package com.c0mpile.grimmreader.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SetupState
import com.c0mpile.grimmreader.core.designsystem.component.LocalOpenSidebar
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.feature.bookdetail.BookDetailScreen
import com.c0mpile.grimmreader.feature.downloads.DownloadsScreen
import com.c0mpile.grimmreader.feature.library.BookGroupScreen
import com.c0mpile.grimmreader.feature.library.DashboardScreen
import com.c0mpile.grimmreader.feature.library.GroupKind
import com.c0mpile.grimmreader.feature.library.LibraryScreen
import com.c0mpile.grimmreader.feature.library.Sidebar
import com.c0mpile.grimmreader.feature.library.SidebarDestination
import com.c0mpile.grimmreader.feature.notebook.NotebookBookScreen
import com.c0mpile.grimmreader.feature.notebook.NotebookScreen
import com.c0mpile.grimmreader.feature.reader.ReaderScreen
import com.c0mpile.grimmreader.feature.settings.SettingsScreen
import com.c0mpile.grimmreader.feature.setup.SetupScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object DashboardKey : NavKey

/** A library screen: [scope] is [LibraryScope.encode]d; [search] opens it with the search field active. */
@Serializable data class BrowseKey(
    val scope: String,
    val mode: BrowseMode = BrowseMode.BOOKS,
    val search: Boolean = false,
) : NavKey

@Serializable data object SettingsKey : NavKey

@Serializable data object NotebookKey : NavKey

@Serializable data object DownloadsKey : NavKey

@Serializable data class NotebookBookKey(
    val serverBookId: Long,
    val title: String,
    val localBookId: Long? = null,
) : NavKey

@Serializable data object SetupKey : NavKey

@Serializable data class BookKey(
    val bookId: Long,
) : NavKey

@Serializable data class BookGroupKey(
    val kind: GroupKind,
    val name: String,
) : NavKey

@Serializable data class ReaderKey(
    val bookId: Long,
) : NavKey

@Composable
fun GrimmApp(
    prefs: AppPreferences,
    versionName: String,
    devServerUrl: String,
) {
    val setup by prefs.setupState.collectAsStateWithLifecycle(initialValue = null)
    when (setup) {
        null -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        SetupState.NOT_DONE -> SetupScreen(devServerUrl = devServerUrl, onDone = {})
        else -> MainNavigation(versionName, devServerUrl)
    }
}

/** The sidebar entry a top-level screen belongs to; null for detail screens (book, reader, setup). */
private fun NavKey.destination(): SidebarDestination? =
    when (this) {
        DashboardKey -> SidebarDestination.Dashboard
        SettingsKey -> SidebarDestination.Settings
        NotebookKey -> SidebarDestination.Notebook
        DownloadsKey -> SidebarDestination.Downloads
        is BrowseKey -> SidebarDestination.Browse(LibraryScope.decode(scope), mode)
        else -> null
    }

private fun SidebarDestination.key(): NavKey =
    when (this) {
        SidebarDestination.Dashboard -> DashboardKey
        SidebarDestination.Settings -> SettingsKey
        SidebarDestination.Search -> BrowseKey(LibraryScope.All.encode(), search = true)
        SidebarDestination.Notebook -> NotebookKey
        SidebarDestination.Downloads -> DownloadsKey
        is SidebarDestination.Browse -> BrowseKey(scope.encode(), mode)
    }

/**
 * Sidebar navigation after the Grimmory web UI: a collapsible panel on wide screens (840 dp and up), a
 * drawer opened from the menu button otherwise. Detail screens (book, reader) take the whole window.
 */
@Composable
private fun MainNavigation(
    versionName: String,
    devServerUrl: String,
) {
    val backStack = rememberNavBackStack(DashboardKey)
    val selected = backStack.lastOrNull()?.destination()
    val topLevel = selected != null

    fun go(destination: SidebarDestination) {
        backStack.clear()
        backStack.add(destination.key())
    }
    val display: @Composable () -> Unit = { Screens(backStack, versionName, devServerUrl, ::go) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (maxWidth >= WIDE) {
            var panel by rememberSaveable { mutableStateOf(true) }
            Row(Modifier.fillMaxSize()) {
                if (topLevel && panel) {
                    Sidebar(selected, ::go, onCollapse = { panel = false }, notebook = true)
                    VerticalDivider()
                }
                CompositionLocalProvider(LocalOpenSidebar provides ({ panel = true }).takeIf { !panel }) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { display() }
                }
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = topLevel || drawer.isOpen,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Sidebar(selected, { destination ->
                            go(destination)
                            scope.launch { drawer.close() }
                        }, notebook = true)
                    }
                },
            ) {
                CompositionLocalProvider(LocalOpenSidebar provides { scope.launch { drawer.open() } }) { display() }
            }
        }
    }
}

@Composable
private fun Screens(
    backStack: NavBackStack<NavKey>,
    versionName: String,
    devServerUrl: String,
    go: (SidebarDestination) -> Unit,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider =
            entryProvider {
                entry<DashboardKey> { DashboardScreen(onRead = { backStack.add(ReaderKey(it)) }) }
                entry<BrowseKey> { key ->
                    LibraryScreen(
                        scope = LibraryScope.decode(key.scope),
                        mode = key.mode,
                        startSearching = key.search,
                        onRead = { backStack.add(ReaderKey(it)) },
                        onOpenGroup = { kind, name -> backStack.add(BookGroupKey(kind, name)) },
                    )
                }
                entry<BookGroupKey> { key ->
                    BookGroupScreen(
                        kind = key.kind,
                        name = key.name,
                        onBack = { backStack.removeLastOrNull() },
                        onRead = { backStack.add(ReaderKey(it)) },
                    )
                }
                entry<NotebookKey> {
                    NotebookScreen(onOpen = { backStack.add(NotebookBookKey(it.serverBookId, it.title, it.localBookId)) })
                }
                entry<NotebookBookKey> { key ->
                    NotebookBookScreen(
                        serverBookId = key.serverBookId,
                        title = key.title,
                        localBookId = key.localBookId,
                        onBack = { backStack.removeLastOrNull() },
                        onOpenBook = { backStack.add(BookKey(it)) },
                    )
                }
                entry<DownloadsKey> { DownloadsScreen(onOpenBook = { backStack.add(BookKey(it)) }) }
                entry<SettingsKey> { SettingsScreen(versionName, onConnectServer = { backStack.add(SetupKey) }) }
                entry<SetupKey> { SetupScreen(devServerUrl = devServerUrl, onDone = { go(SidebarDestination.Dashboard) }) }
                entry<BookKey> { key ->
                    BookDetailScreen(
                        bookId = key.bookId,
                        onBack = { backStack.removeLastOrNull() },
                        onRead = { backStack.add(ReaderKey(it)) },
                    )
                }
                entry<ReaderKey> { key -> ReaderScreen(key.bookId, onBack = { backStack.removeLastOrNull() }) }
            },
    )
}

private val WIDE = 840.dp
