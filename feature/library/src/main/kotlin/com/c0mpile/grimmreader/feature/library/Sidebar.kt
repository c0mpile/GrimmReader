package com.c0mpile.grimmreader.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.core.model.Shelf
import kotlinx.coroutines.launch

/** Width of the sidebar panel (expanded) and drawer (compact). */
val SidebarWidth = 280.dp

/**
 * The navigation sidebar, after the Grimmory web UI: Home, Libraries and Shelves sections (each collapsible)
 * with counts, then Settings and the signed-in user. [onCollapse] shows a collapse button (expanded layout).
 */
@Composable
fun Sidebar(
    selected: SidebarDestination?,
    onSelect: (SidebarDestination) -> Unit,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    notebook: Boolean = false,
    viewModel: SidebarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var creatingLibrary by remember { mutableStateOf(false) }
    val collapsed = rememberSaveable(saver = CollapsedSaver) { mutableStateMapOf<String, Boolean>() }
    // A Surface so text and icons get the right content colour on the dark panel.
    Surface(modifier.width(SidebarWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SidebarContent(state, selected, onSelect, onCollapse, notebook, collapsed, { creating = true }) { creatingLibrary = true }
    }
    if (creating) CreateShelfDialog(onDismiss = { creating = false }, create = viewModel::createShelf)
    if (creatingLibrary) CreateLibraryDialog(onDismiss = { creatingLibrary = false }, create = viewModel::createLibrary)
}

@Composable
private fun SidebarContent(
    state: SidebarState,
    selected: SidebarDestination?,
    onSelect: (SidebarDestination) -> Unit,
    onCollapse: (() -> Unit)?,
    notebook: Boolean,
    collapsed: MutableMap<String, Boolean>,
    onCreateShelf: () -> Unit,
    onCreateLibrary: () -> Unit,
) {
    Column(Modifier.safeDrawingPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(
            "GrimmReader",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
        )
        SearchPill { onSelect(SidebarDestination.Search) }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            section("Home", collapsed) {
                entry(LucideIcons.House, "Dashboard", null, selected == SidebarDestination.Dashboard) {
                    onSelect(SidebarDestination.Dashboard)
                }
                browse(LucideIcons.LibraryBig, "All Books", state.allBooks, LibraryScope.All, BrowseMode.BOOKS, selected, onSelect)
                browse(LucideIcons.BookCopy, "Series", state.series, LibraryScope.All, BrowseMode.SERIES, selected, onSelect)
                if (state.comicSeries > 0) {
                    val mode = BrowseMode.COMIC_SERIES
                    browse(LucideIcons.BookCopy, "Comic Series", state.comicSeries, LibraryScope.All, mode, selected, onSelect)
                }
                browse(LucideIcons.Users, "Authors", state.authors, LibraryScope.All, BrowseMode.AUTHORS, selected, onSelect)
                if (state.hasServer && notebook) {
                    entry(LucideIcons.NotebookPen, "Notebook", null, selected == SidebarDestination.Notebook) {
                        onSelect(SidebarDestination.Notebook)
                    }
                }
                if (state.hasServer || state.hasDownloads) {
                    val active = state.activeDownloads.takeIf { it > 0 }
                    entry(LucideIcons.Download, "Downloads", active, selected == SidebarDestination.Downloads) {
                        onSelect(SidebarDestination.Downloads)
                    }
                }
            }
            libraries(state, collapsed, selected, onSelect, onCreateLibrary)
            if (state.hasServer) {
                section("Shelves", collapsed, action = Triple(LucideIcons.Plus, "New shelf", onCreateShelf)) {
                    browse(LucideIcons.Inbox, "Unshelved", state.unshelved, LibraryScope.Unshelved, BrowseMode.BOOKS, selected, onSelect)
                    state.shelves.forEach { shelf ->
                        val scope = if (shelf.magic) LibraryScope.MagicShelf(shelf.id) else LibraryScope.Shelf(shelf.id)
                        browse(shelf.icon(), shelf.name, shelf.bookCount, scope, BrowseMode.BOOKS, selected, onSelect)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SidebarItem(LucideIcons.Settings, "Settings", null, selected == SidebarDestination.Settings) {
            onSelect(SidebarDestination.Settings)
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.hasServer) {
                val name = state.userName ?: "Signed in"
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                Text(name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Spacer(Modifier.weight(1f))
            }
            onCollapse?.let { collapse ->
                IconButton(onClick = collapse) { Icon(LucideIcons.PanelLeftClose, contentDescription = "Hide sidebar") }
            }
        }
    }
}

/** The server's libraries (when connected) and the libraries on this device. */
private fun LazyListScope.libraries(
    state: SidebarState,
    collapsed: MutableMap<String, Boolean>,
    selected: SidebarDestination?,
    onSelect: (SidebarDestination) -> Unit,
    onCreateLibrary: () -> Unit,
) {
    if (state.hasServer) {
        section("Libraries", collapsed) {
            state.libraries.forEach { (library, count) ->
                browse(
                    LucideIcons.Library,
                    library.name,
                    count,
                    LibraryScope.Server(library.id),
                    BrowseMode.BOOKS,
                    selected,
                    onSelect,
                )
            }
        }
    }
    section("On this device", collapsed, action = Triple(LucideIcons.Plus, "New library", onCreateLibrary)) {
        state.localLibraries.forEach { (library, count) ->
            val icon = if (library.serverLibraryId != null) LucideIcons.Library else LucideIcons.Smartphone
            browse(icon, library.name, count, LibraryScope.Local(library.id), BrowseMode.BOOKS, selected, onSelect)
        }
        if (state.unsorted > 0) {
            browse(LucideIcons.Inbox, "Unsorted", state.unsorted, LibraryScope.Unsorted, BrowseMode.BOOKS, selected, onSelect)
        }
    }
}

private fun Shelf.icon(): ImageVector =
    when {
        magic -> LucideIcons.Sparkles
        isFavorites -> LucideIcons.Heart
        else -> LucideIcons.Bookmark
    }

private fun LazyListScope.section(
    title: String,
    collapsed: MutableMap<String, Boolean>,
    action: Triple<ImageVector, String, () -> Unit>? = null,
    content: LazyListScope.() -> Unit,
) {
    val isCollapsed = collapsed[title] == true
    item(key = "section:$title") {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            action?.let { (icon, description, onClick) ->
                IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
                    Icon(icon, contentDescription = description, modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = { collapsed[title] = !isCollapsed }, modifier = Modifier.size(32.dp)) {
                Icon(
                    LucideIcons.ChevronDown,
                    contentDescription = if (isCollapsed) "Show $title" else "Hide $title",
                    modifier = Modifier.size(16.dp).rotate(if (isCollapsed) -90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (!isCollapsed) content()
}

private fun LazyListScope.entry(
    icon: ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) = item(key = "entry:$label") { SidebarItem(icon, label, count, selected, onClick) }

private fun LazyListScope.browse(
    icon: ImageVector,
    label: String,
    count: Int,
    scope: LibraryScope,
    mode: BrowseMode,
    selected: SidebarDestination?,
    onSelect: (SidebarDestination) -> Unit,
) {
    val destination = SidebarDestination.Browse(scope, mode)
    item(key = "browse:${scope.encode()}/$mode") { SidebarItem(icon, label, count, selected == destination) { onSelect(destination) } }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.primary else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primary.copy(alpha = SELECTED_ALPHA) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = content,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        count?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = SEARCH_ALPHA))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            LucideIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CreateLibraryDialog(
    onDismiss: () -> Unit,
    create: suspend (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New library") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Give it a folder in Settings → Storage and the books you put there appear in it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        create(name)
                        onDismiss()
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreateShelfDialog(
    onDismiss: () -> Unit,
    create: suspend (String) -> String?,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New shelf") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = error != null)
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        error = create(name)
                        busy = false
                        if (error == null) onDismiss()
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val SELECTED_ALPHA = 0.16f
private const val SEARCH_ALPHA = 0.5f

private val CollapsedSaver =
    androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>, List<String>>(
        save = { map -> map.filterValues { it }.keys.toList() },
        restore = { keys -> mutableStateMapOf<String, Boolean>().apply { keys.forEach { put(it, true) } } },
    )
