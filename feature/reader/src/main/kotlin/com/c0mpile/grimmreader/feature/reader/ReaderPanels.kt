package com.c0mpile.grimmreader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.c0mpile.grimmreader.core.designsystem.component.BookCover
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.designsystem.theme.LocalMotionEnabled
import com.c0mpile.grimmreader.core.model.Bookmark
import com.c0mpile.grimmreader.core.model.NotebookEntry
import com.c0mpile.grimmreader.reader.ebook.MAX_SEARCH_HITS
import com.c0mpile.grimmreader.reader.ebook.SearchHit
import com.c0mpile.grimmreader.reader.ebook.TocEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal enum class PanelSide { LEFT, RIGHT }

/**
 * A panel sliding in over the reader from one side, like the web reader's sidebars: the page is dimmed
 * behind it, and a tap there, a swipe towards the edge or Back collapses it again.
 */
@Composable
internal fun BoxScope.SidePanel(
    open: Boolean,
    side: PanelSide,
    maxWidth: Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    AnimatedVisibility(
        open,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(if (motion) tween(PANEL_MS) else snap()),
        exit = fadeOut(if (motion) tween(PANEL_MS) else snap()),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    val fromEdge = { width: Int -> if (side == PanelSide.LEFT) -width else width }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val width = min(maxWidth, this.maxWidth * PANEL_FRACTION)
        AnimatedVisibility(
            open,
            modifier = Modifier.align(if (side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd),
            enter = slideInHorizontally(if (motion) tween(PANEL_MS) else snap(), fromEdge),
            exit = slideOutHorizontally(if (motion) tween(PANEL_MS) else snap(), fromEdge),
        ) {
            Box(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(side) {
                        var total = 0f
                        detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                            val towardsEdge = if (side == PanelSide.LEFT) -total else total
                            if (towardsEdge > SWIPE_CLOSE_DP * density) onDismiss()
                        }) { _, amount -> total += amount }
                    }.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Vertical + if (side == PanelSide.LEFT) WindowInsetsSides.Start else WindowInsetsSides.End,
                        ),
                    ),
            ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() } }
        }
    }
    val back = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(state = back, isBackEnabled = open, onBackCompleted = onDismiss)
}

private const val PANEL_MS = 200
private const val SCRIM = 0.55f
private const val PANEL_FRACTION = 0.88f
private const val SWIPE_CLOSE_DP = 64

internal enum class LeftTab { CONTENTS, BOOKMARKS, HIGHLIGHTS }

/** Book header, then Contents / Bookmarks / Highlights. */
@Composable
internal fun LeftPanel(
    state: ReaderUiState,
    annotations: Annotations,
    tab: LeftTab,
    onTab: (LeftTab) -> Unit,
    onChapter: (TocEntry) -> Unit,
    onBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onLoadAnnotations: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(state.title, state.coverUri, Modifier.width(56.dp), shape = RoundedCornerShape(4.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                state.authors?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        PanelTabs(
            listOf(
                PanelTab("Contents", LucideIcons.Book),
                PanelTab("Bookmarks", LucideIcons.Bookmark, badge = state.bookmarks.size),
                PanelTab("Highlights", LucideIcons.PenLine),
            ),
            selected = tab.ordinal,
            onSelect = { onTab(LeftTab.entries[it]) },
        )
        when (tab) {
            LeftTab.CONTENTS ->
                if (state.toc.isEmpty()) {
                    EmptyState(LucideIcons.Book, "No contents", "This book has no table of contents.")
                } else {
                    ContentsTree(state.toc, state.chapter, onChapter)
                }
            LeftTab.BOOKMARKS -> BookmarkList(state.bookmarks, state.bookmarkHere, onBookmark, onDeleteBookmark)
            LeftTab.HIGHLIGHTS -> {
                LaunchedEffect(Unit) { onLoadAnnotations() }
                AnnotationList(annotations, highlights = true)
            }
        }
    }
}

/**
 * Chapters as a tree: entries with sub-chapters fold open and closed with their chevron, a tap on the
 * label jumps there. The chapter being read is highlighted and its parents start open.
 */
@Composable
private fun ContentsTree(
    toc: List<TocEntry>,
    current: String?,
    onOpen: (TocEntry) -> Unit,
) {
    val parents = remember(toc) { tocParents(toc) }
    val currentIndex = toc.indexOfLast { it.label == current }
    var expanded by remember(toc) { mutableStateOf(ancestors(parents, currentIndex)) }
    val visible = toc.indices.filter { i -> ancestors(parents, i).all { it in expanded } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (visible.indexOf(currentIndex) - 2).coerceAtLeast(0))
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(visible, key = { it }) { i ->
            val entry = toc[i]
            val here = i == currentIndex
            val hasChildren = toc.getOrNull(i + 1)?.let { it.depth > entry.depth } == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (here) selectedFill() else Color.Transparent)
                    .clickable { onOpen(entry) }
                    .padding(start = (CONTENTS_PAD + entry.depth * CONTENTS_INDENT).dp, end = 8.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasChildren) {
                    val open = i in expanded
                    IconButton(onClick = { expanded = if (open) expanded - i else expanded + i }) {
                        Icon(
                            if (open) LucideIcons.ChevronDown else LucideIcons.ChevronRight,
                            contentDescription = if (open) "Collapse" else "Expand",
                            tint = mutedText(),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val CONTENTS_PAD = 20
private const val CONTENTS_INDENT = 20

/** Index of each entry's parent (the nearest earlier entry with a smaller depth), or -1. */
internal fun tocParents(toc: List<TocEntry>): IntArray {
    val stack = ArrayDeque<Int>()
    return IntArray(toc.size) { i ->
        while (stack.isNotEmpty() && toc[stack.last()].depth >= toc[i].depth) stack.removeLast()
        val parent = stack.lastOrNull() ?: -1
        stack.addLast(i)
        parent
    }
}

internal fun ancestors(
    parents: IntArray,
    index: Int,
): Set<Int> =
    buildSet {
        var p = parents.getOrElse(index) { -1 }
        while (p >= 0) {
            add(p)
            p = parents[p]
        }
    }

@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    current: Bookmark?,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        EmptyState(LucideIcons.Bookmark, "No bookmarks yet", "Tap the bookmark icon at the top to mark the current page.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(bookmarks, key = { it.id }) { bookmark ->
            val here = bookmark.id == current?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (here) selectedFill() else Color.Transparent)
                    .clickable { onOpen(bookmark) }
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        bookmark.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    bookmarkDetail(bookmark)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = mutedText(), modifier = Modifier.padding(top = 4.dp))
                    }
                }
                IconButton(onClick = { onDelete(bookmark) }) {
                    Icon(LucideIcons.Trash, contentDescription = "Delete bookmark", tint = mutedText(), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The date it was made (like the web), else the page or position. */
private fun bookmarkDetail(bookmark: Bookmark): String? {
    if (bookmark.createdAt > 0) return date(bookmark.createdAt)
    val page = bookmark.page?.let { "Page $it" }
    return when {
        page != null && page != bookmark.title -> page
        page == null -> bookmark.percent?.let { "%.0f %%".format(it) }
        else -> null
    }
}

private fun date(epochMs: Long): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Highlights or notes from the server notebook; read-only (the server gives no position to jump to). */
@Composable
private fun AnnotationList(
    annotations: Annotations,
    highlights: Boolean,
) {
    val icon = if (highlights) LucideIcons.PenLine else LucideIcons.FileText
    val noun = if (highlights) "highlights" else "notes"
    when (annotations) {
        Annotations.NotLoaded, Annotations.Loading ->
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Annotations.Unavailable ->
            EmptyState(
                icon,
                "No $noun yet",
                if (annotations.local) {
                    "Highlights and notes are not available in GrimmReader yet."
                } else {
                    "Could not reach the server. Your $noun from the web reader show here when it is reachable."
                },
            )
        is Annotations.Loaded -> {
            val list = if (highlights) annotations.highlights else annotations.notes
            if (list.isEmpty()) {
                EmptyState(icon, "No $noun yet", "${noun.replaceFirstChar { it.uppercase() }} made in the Grimmory web reader show here.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) { items(list, key = { it.id }) { AnnotationItem(it, highlights) } }
            }
        }
    }
}

@Composable
private fun AnnotationItem(
    entry: NotebookEntry,
    highlight: Boolean,
) {
    val accent = entry.color?.let(::parseColor) ?: MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val main = if (highlight) entry.text else entry.note ?: entry.text
            main?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
            if (!highlight && entry.note != null && entry.text != null) {
                Text(
                    "“${entry.text}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedText(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = listOfNotNull(entry.chapter, entry.createdAt?.let(::date)).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = mutedText())
        }
    }
}

private fun parseColor(hex: String): Color? =
    hex
        .removePrefix("#")
        .takeIf { it.length == RGB_DIGITS }
        ?.toLongOrNull(HEX)
        ?.let { Color(OPAQUE or it) }

private const val RGB_DIGITS = 6
private const val HEX = 16
private const val OPAQUE = 0xFF000000

internal enum class RightTab { SEARCH, NOTES }

/** Search (ebooks) and notes, with the tab's name as the header like the web. */
@Composable
internal fun RightPanel(
    search: SearchState,
    annotations: Annotations,
    tab: RightTab,
    canSearch: Boolean,
    onTab: (RightTab) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onHit: (SearchHit) -> Unit,
    onLoadAnnotations: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            if (tab == RightTab.SEARCH) "Search" else "Notes",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(20.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        PanelTabs(
            listOf(PanelTab("Search", LucideIcons.Search), PanelTab("Notes", LucideIcons.FileText)),
            selected = tab.ordinal,
            onSelect = { onTab(RightTab.entries[it]) },
        )
        when (tab) {
            RightTab.SEARCH ->
                if (canSearch) {
                    SearchTab(search, onSearch, onClearSearch, onHit)
                } else {
                    EmptyState(LucideIcons.Search, "Search is not available", "Only ebooks can be searched.")
                }
            RightTab.NOTES -> {
                LaunchedEffect(Unit) { onLoadAnnotations() }
                AnnotationList(annotations, highlights = false)
            }
        }
    }
}

@Composable
private fun SearchTab(
    search: SearchState,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onHit: (SearchHit) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(LucideIcons.Search, contentDescription = null, tint = mutedText(), modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(start = 12.dp)) {
            if (query.isEmpty()) Text("Search in book…", style = MaterialTheme.typography.bodyLarge, color = mutedText())
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = {
                        if (query.isNotBlank()) onSearch(query.trim())
                        focus.clearFocus()
                    }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = {
                query = ""
                onClear()
            }) { Icon(LucideIcons.X, contentDescription = "Clear search", tint = mutedText(), modifier = Modifier.size(18.dp)) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    search.progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
    val count = search.groups.sumOf { it.hits.size }
    when {
        search.query.isEmpty() -> EmptyState(LucideIcons.Search, "Search this book", "Enter text to find in the book")
        count == 0 && search.progress == null -> EmptyState(LucideIcons.Search, "No results", "Nothing matches “${search.query}”.")
        else ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        when {
                            count >= MAX_SEARCH_HITS -> "$MAX_SEARCH_HITS+ results"
                            count == 1 -> "1 result"
                            else -> "$count results"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedText(),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                search.groups.forEach { group ->
                    if (group.label.isNotBlank()) {
                        item {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = mutedText(),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(group.hits) { hit -> SearchHitRow(hit) { onHit(hit) } }
                }
            }
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val text =
        buildAnnotatedString {
            append(hit.pre.trimStart())
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(hit.match) }
            append(hit.post.trimEnd())
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    )
}
