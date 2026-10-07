package com.c0mpile.grimmreader.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SetupState
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.feature.bookdetail.BookDetailScreen
import com.c0mpile.grimmreader.feature.library.BookGroupScreen
import com.c0mpile.grimmreader.feature.library.GroupKind
import com.c0mpile.grimmreader.feature.library.LibraryScreen
import com.c0mpile.grimmreader.feature.reader.ReaderScreen
import com.c0mpile.grimmreader.feature.settings.SettingsScreen
import com.c0mpile.grimmreader.feature.setup.SetupScreen
import kotlinx.serialization.Serializable

@Serializable data object LibraryKey : NavKey

@Serializable data object SettingsKey : NavKey

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

@Composable
private fun MainNavigation(
    versionName: String,
    devServerUrl: String,
) {
    val backStack = rememberNavBackStack(LibraryKey)
    val top = backStack.lastOrNull()
    val topLevel = top == LibraryKey || top == SettingsKey

    fun selectTop(key: NavKey) {
        backStack.clear()
        backStack.add(key)
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = top == LibraryKey,
                onClick = { selectTop(LibraryKey) },
                icon = { Icon(LucideIcons.LibraryBig, contentDescription = null) },
                label = { Text("Library") },
            )
            item(
                selected = top == SettingsKey,
                onClick = { selectTop(SettingsKey) },
                icon = { Icon(LucideIcons.Settings, contentDescription = null) },
                label = { Text("Settings") },
            )
        },
        layoutType =
            if (topLevel) {
                NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
                    currentWindowAdaptiveInfo(),
                )
            } else {
                NavigationSuiteType.None
            },
    ) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider =
                    entryProvider {
                        entry<LibraryKey> {
                            LibraryScreen(
                                onOpenBook = { backStack.add(BookKey(it)) },
                                onOpenGroup = { kind, name -> backStack.add(BookGroupKey(kind, name)) },
                            )
                        }
                        entry<BookGroupKey> { key ->
                            BookGroupScreen(
                                kind = key.kind,
                                name = key.name,
                                onBack = { backStack.removeLastOrNull() },
                                onOpenBook = { backStack.add(BookKey(it)) },
                            )
                        }
                        entry<SettingsKey> { SettingsScreen(versionName, onConnectServer = { backStack.add(SetupKey) }) }
                        entry<SetupKey> { SetupScreen(devServerUrl = devServerUrl, onDone = { selectTop(LibraryKey) }) }
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
    }
}
