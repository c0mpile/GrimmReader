package com.c0mpile.grimmreader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.designsystem.theme.LocalMotionEnabled
import com.c0mpile.grimmreader.core.designsystem.theme.palette
import com.c0mpile.grimmreader.core.model.Bookmark
import com.c0mpile.grimmreader.core.model.PageTheme
import com.c0mpile.grimmreader.core.model.ReaderPrefs
import com.c0mpile.grimmreader.reader.ebook.EbookController
import com.c0mpile.grimmreader.reader.ebook.EbookCss
import com.c0mpile.grimmreader.reader.ebook.EbookEvent
import com.c0mpile.grimmreader.reader.ebook.EbookReader
import com.c0mpile.grimmreader.reader.ebook.PageColors
import com.c0mpile.grimmreader.reader.paged.PagedReader

private const val MIN_FONT = 12
private const val MAX_FONT = 36

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel<ReaderViewModel, ReaderViewModel.Factory> { it.create(bookId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPrefs.collectAsStateWithLifecycle()
    var chrome by rememberSaveable { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var bookmarkList by remember { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val snackbar = remember { SnackbarHostState() }
    // One controller per ebook view, hoisted so the bookmark list can jump through it.
    val ebook = state.content as? ReaderContent.Ebook
    val controller = remember(ebook?.file, ebook?.restoreKey) { EbookController() }
    var pageReady by remember(controller) { mutableStateOf(false) }
    val bookmarkCfis = state.bookmarks.mapNotNull { it.cfi }
    LaunchedEffect(controller, pageReady, bookmarkCfis) { if (pageReady) controller.setBookmarks(bookmarkCfis) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.messageShown()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.flush() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Content(state.content, prefs, viewModel, controller, onPageReady = { pageReady = true }, onToggleChrome = { chrome = !chrome })
        AnimatedVisibility(chrome, enter = if (motion) fadeIn() else fadeIn(snap()), exit = if (motion) fadeOut() else fadeOut(snap())) {
            Chrome(
                state,
                onBack = onBack,
                onToggleBookmark = viewModel::toggleBookmark,
                onBookmarks = { bookmarkList = true },
                onSettings = { settings = true },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 48.dp))
    }
    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }) {
            ReaderSettings(prefs, textSettings = state.content !is ReaderContent.Paged, onChange = viewModel::setReaderPrefs)
        }
    }
    if (bookmarkList) {
        ModalBottomSheet(onDismissRequest = { bookmarkList = false }) {
            BookmarkList(
                bookmarks = state.bookmarks,
                current = state.bookmarkHere,
                onOpen = { bookmark ->
                    bookmarkList = false
                    val cfi = bookmark.cfi
                    val page = bookmark.page
                    if (cfi != null) {
                        controller.goTo(cfi)
                    } else if (page != null) {
                        viewModel.goToPage(page)
                    }
                },
                onDelete = viewModel::removeBookmark,
            )
        }
    }
    state.offer?.let { offer ->
        AlertDialog(
            onDismissRequest = viewModel::dismissOffer,
            title = { Text("Continue where you left off?") },
            text = { Text("This book was read further on another device or the web (%.0f %%).".format(offer.locator.percent)) },
            confirmButton = { TextButton(onClick = viewModel::acceptOffer) { Text("Go there") } },
            dismissButton = { TextButton(onClick = viewModel::dismissOffer) { Text("Stay here") } },
        )
    }
}

private fun <T> snap() =
    androidx.compose.animation.core
        .snap<T>()

@Composable
private fun Content(
    content: ReaderContent,
    prefs: ReaderPrefs,
    viewModel: ReaderViewModel,
    controller: EbookController,
    onPageReady: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    when (content) {
        ReaderContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ReaderContent.Ebook -> {
            val page = prefs.pageTheme.palette()
            val css = EbookCss.build(prefs, PageColors(page.background.hex(), page.text.hex(), page.link.hex(), page.grayscaleImages))
            key(content.file, content.restoreKey) {
                // The page colour also fills the safe-area margins around the book.
                EbookReader(
                    file = content.file,
                    initialCfi = content.cfi,
                    css = css,
                    animated = LocalMotionEnabled.current && !page.instantTurns,
                    controller = controller,
                    onEvent = { event ->
                        when (event) {
                            is EbookEvent.Ready -> onPageReady()
                            is EbookEvent.Relocated ->
                                viewModel.onEbookPosition(event.locator, event.tocLabel, event.hasPosition, event.bookmark)
                            is EbookEvent.BookmarkHere -> viewModel.onEbookBookmarkHere(event.cfi)
                            is EbookEvent.Failed -> Unit
                        }
                    },
                    onToggleChrome = onToggleChrome,
                    modifier = Modifier.fillMaxSize().background(page.background).safeDrawingPadding(),
                )
            }
        }
        is ReaderContent.Paged -> {
            val page = prefs.pageTheme.palette()
            key(content.restoreKey) {
                PagedReader(
                    content.source,
                    content.page,
                    onPage = viewModel::onPage,
                    onToggleChrome = onToggleChrome,
                    modifier = Modifier.background(page.background),
                    grayscale = page.grayscaleImages,
                    instantTurns = page.instantTurns,
                )
            }
        }
        is ReaderContent.Unsupported -> Message("${content.format.name} files cannot be read.")
        is ReaderContent.Fetching ->
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Loading from the server…", style = MaterialTheme.typography.bodyLarge)
                val p = content.progress
                if (p == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        is ReaderContent.Failed -> Message(content.message)
        ReaderContent.NotDownloaded -> Message("This book's file is not on this device.")
    }
}

@Composable
private fun Message(text: String) {
    Box(
        Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

/** Reader chrome is dark in every theme, like the web reader. */
@Composable
private fun Chrome(
    state: ReaderUiState,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
) {
    val canRead = state.content is ReaderContent.Ebook || state.content is ReaderContent.Paged
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xE6171717))
                .safeDrawingPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(LucideIcons.ArrowLeft, contentDescription = "Back", tint = Color.White) }
            Text(state.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (canRead) {
                val marked = state.bookmarkHere != null
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        if (marked) LucideIcons.BookmarkCheck else LucideIcons.Bookmark,
                        contentDescription = if (marked) "Remove bookmark" else "Add bookmark",
                        tint = if (marked) CHROME_ACCENT else Color.White,
                    )
                }
                IconButton(onClick = onBookmarks) { Icon(LucideIcons.List, contentDescription = "Bookmarks", tint = Color.White) }
            }
            IconButton(onClick = onSettings) { Icon(LucideIcons.Settings, contentDescription = "Reading settings", tint = Color.White) }
        }
        Box(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xE6171717))
                .safeDrawingPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(state.location.orEmpty(), color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.percent?.let { Text("%.0f %%".format(it), color = Color.White.copy(alpha = 0.6f)) }
        }
    }
}

/** Accent on the always-dark reader chrome (web: primary-400). */
private val CHROME_ACCENT = Color(0xFFFF8904)

@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    current: Bookmark?,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (bookmarks.isEmpty()) {
            Text(
                "No bookmarks yet. Tap the bookmark icon at the top to mark the current page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            return
        }
        LazyColumn {
            items(bookmarks, key = { it.id }) { bookmark ->
                ListItem(
                    headlineContent = { Text(bookmark.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = bookmarkDetail(bookmark)?.let { detail -> { Text(detail) } },
                    leadingContent = {
                        Icon(
                            if (bookmark.id == current?.id) LucideIcons.BookmarkCheck else LucideIcons.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onDelete(bookmark) }) { Icon(LucideIcons.Trash, contentDescription = "Delete bookmark") }
                    },
                    modifier = Modifier.clickable { onOpen(bookmark) },
                )
            }
        }
    }
}

/** "Page 12" unless the title already says so; else the position when known. */
private fun bookmarkDetail(bookmark: Bookmark): String? {
    val page = bookmark.page?.let { "Page $it" }
    return when {
        page != null && page != bookmark.title -> page
        page == null -> bookmark.percent?.let { "%.0f %%".format(it) }
        else -> null
    }
}

@Composable
private fun ReaderSettings(
    prefs: ReaderPrefs,
    textSettings: Boolean,
    onChange: (ReaderPrefs) -> Unit,
) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Page colours only; menus keep the app theme.
        Text("Page", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageTheme.entries.forEach { theme ->
                val swatch = theme.palette()
                FilterChip(
                    selected = prefs.pageTheme == theme,
                    onClick = { onChange(prefs.copy(pageTheme = theme)) },
                    label = { Text(theme.label()) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(swatch.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    },
                )
            }
        }
        // Comics and PDFs are images: only the page colour applies.
        if (!textSettings) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Font size", Modifier.weight(1f))
            TextButton(onClick = { onChange(prefs.copy(fontSize = (prefs.fontSize - 1).coerceAtLeast(MIN_FONT))) }) { Text("A−") }
            Text("${prefs.fontSize}")
            TextButton(onClick = { onChange(prefs.copy(fontSize = (prefs.fontSize + 1).coerceAtMost(MAX_FONT))) }) { Text("A+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Serif", "Sans", "Publisher").forEach { family ->
                FilterChip(
                    selected = prefs.fontFamily.equals(family, ignoreCase = true) || (family == "Serif" && prefs.fontFamily == "Literata"),
                    onClick = { onChange(prefs.copy(fontFamily = family)) },
                    label = { Text(family) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Justify text", Modifier.weight(1f))
            Switch(checked = prefs.justify, onCheckedChange = { onChange(prefs.copy(justify = it)) })
        }
    }
}

private fun PageTheme.label() =
    when (this) {
        PageTheme.EINK -> "E-ink"
        PageTheme.LIGHT -> "Light"
        PageTheme.DARK -> "Dark"
        PageTheme.AMOLED -> "AMOLED"
    }

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
