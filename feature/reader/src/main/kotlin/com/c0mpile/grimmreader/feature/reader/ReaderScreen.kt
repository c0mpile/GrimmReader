package com.c0mpile.grimmreader.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.component.PageTurns
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.designsystem.theme.LocalMotionEnabled
import com.c0mpile.grimmreader.core.designsystem.theme.PageImages
import com.c0mpile.grimmreader.core.designsystem.theme.colorFilter
import com.c0mpile.grimmreader.core.designsystem.theme.palette
import com.c0mpile.grimmreader.core.designsystem.theme.paperGrain
import com.c0mpile.grimmreader.core.model.Bookmark
import com.c0mpile.grimmreader.core.model.PageTheme
import com.c0mpile.grimmreader.core.model.ReaderPrefs
import com.c0mpile.grimmreader.reader.ebook.EbookController
import com.c0mpile.grimmreader.reader.ebook.EbookCss
import com.c0mpile.grimmreader.reader.ebook.EbookEvent
import com.c0mpile.grimmreader.reader.ebook.EbookLayout
import com.c0mpile.grimmreader.reader.ebook.EbookReader
import com.c0mpile.grimmreader.reader.ebook.PageColors
import com.c0mpile.grimmreader.reader.paged.PagedReader
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** What the reader shows over the page besides its bars; one at a time. */
private sealed interface Overlay {
    data object None : Overlay

    data object QuickSettings : Overlay

    data object Settings : Overlay

    data class Left(
        val tab: LeftTab,
    ) : Overlay

    data class Right(
        val tab: RightTab,
    ) : Overlay
}

@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenDictionaries: () -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel<ReaderViewModel, ReaderViewModel.Factory> { it.create(bookId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPrefs.collectAsStateWithLifecycle()
    val lookup by viewModel.lookups.current.collectAsStateWithLifecycle()
    var chrome by rememberSaveable { mutableStateOf(true) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val snackbar = remember { SnackbarHostState() }
    // One controller per ebook view, hoisted so the panels and the position bar can jump through it.
    val ebook = state.content as? ReaderContent.Ebook
    val controller = remember(ebook?.book, ebook?.restoreKey) { EbookController() }
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
    // The looked-up word stays highlighted while its definitions are shown.
    val lookupOpen = lookup != null
    LaunchedEffect(controller, lookupOpen) { if (!lookupOpen) controller.clearLookup() }
    ImmersiveMode(fullscreen)
    val textSettings = state.content !is ReaderContent.Paged
    val panelOpen = overlay is Overlay.Left || overlay is Overlay.Right
    val close = { overlay = Overlay.None }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().then(if (panelOpen) Modifier.blur(PANEL_BLUR) else Modifier)) {
            // While the bars are shown, a tap on the page only hides them; nothing turns.
            val turns = if (chrome) PageTurns.Off else prefs.pageTurns()
            Content(state.content, prefs, turns, viewModel, controller, onPageReady = { pageReady = true }, onToggleChrome = {
                chrome = !chrome
                if (overlay == Overlay.QuickSettings) overlay = Overlay.None
            })
            PageOverlays(state, prefs)
        }
        AnimatedVisibility(chrome, enter = if (motion) fadeIn() else fadeIn(snap()), exit = if (motion) fadeOut() else fadeOut(snap())) {
            Chrome(
                state,
                fullscreen = fullscreen,
                quickSettings = overlay == Overlay.QuickSettings,
                actions =
                    ChromeActions(
                        onClose = onBack,
                        onToggleBookmark = viewModel::toggleBookmark,
                        onOverlay = { overlay = if (overlay == it) Overlay.None else it },
                        onFullscreen = { fullscreen = !fullscreen },
                        onPrev = { turnPage(-1, state, controller, viewModel) },
                        onNext = { turnPage(1, state, controller, viewModel) },
                        onSeekFraction = controller::goToFraction,
                        onSeekPage = viewModel::goToPage,
                    ),
            ) {
                QuickSettings(
                    prefs,
                    textSettings,
                    onChange = viewModel::setReaderPrefs,
                    onMore = { overlay = Overlay.Settings },
                )
            }
        }
        Panels(
            overlay,
            state,
            viewModel,
            controller,
            searchable = ebook != null,
            onOverlay = { overlay = it },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 64.dp))
    }
    lookup?.let { current ->
        DictionarySheet(
            current,
            onLookUp = viewModel.lookups::lookUp,
            onOpenDictionaries = {
                viewModel.lookups.close()
                onOpenDictionaries()
            },
            onDismiss = viewModel.lookups::close,
        )
    }
    if (overlay == Overlay.Settings) {
        SettingsDialog(prefs, textSettings, onChange = viewModel::setReaderPrefs, onDismiss = close)
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

/** The left (contents, bookmarks, highlights) and right (search, notes) panels. */
@Composable
private fun BoxScope.Panels(
    overlay: Overlay,
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    controller: EbookController,
    searchable: Boolean,
    onOverlay: (Overlay) -> Unit,
) {
    val search by viewModel.panels.search.collectAsStateWithLifecycle()
    val annotations by viewModel.panels.annotations.collectAsStateWithLifecycle()
    val close = { onOverlay(Overlay.None) }
    SidePanel(overlay is Overlay.Left, PanelSide.LEFT, maxWidth = LEFT_PANEL_WIDTH, onDismiss = close) {
        LeftPanel(
            state,
            annotations,
            tab = (overlay as? Overlay.Left)?.tab ?: LeftTab.CONTENTS,
            onTab = { onOverlay(Overlay.Left(it)) },
            onChapter = { entry ->
                close()
                controller.goTo(entry.href)
            },
            onBookmark = { bookmark ->
                close()
                openBookmark(bookmark, controller, viewModel)
            },
            onDeleteBookmark = viewModel::removeBookmark,
            onLoadAnnotations = viewModel.panels::loadAnnotations,
        )
    }
    SidePanel(overlay is Overlay.Right, PanelSide.RIGHT, maxWidth = RIGHT_PANEL_WIDTH, onDismiss = close) {
        RightPanel(
            search,
            annotations,
            tab = (overlay as? Overlay.Right)?.tab ?: RightTab.SEARCH,
            canSearch = searchable,
            onTab = { onOverlay(Overlay.Right(it)) },
            onSearch = { query -> controller.search(query, viewModel.panels.startSearch(query)) },
            onClearSearch = {
                viewModel.panels.clearSearch()
                controller.clearSearch()
            },
            onHit = { hit ->
                close()
                controller.goTo(hit.cfi)
            },
            onLoadAnnotations = viewModel.panels::loadAnnotations,
        )
    }
}

private val PANEL_BLUR = 6.dp

private fun ReaderPrefs.pageTurns() = PageTurns(edge = turnZone / PERCENT_F, taps = tapToTurn, swipes = swipeToTurn)

private const val PERCENT_F = 100f

/**
 * The bars take every touch in their area, also between and around their buttons; without this a tap
 * next to a button reached the page underneath and turned it. Being hit is enough to keep the touch from
 * the page (a sibling underneath); nothing is consumed, so the buttons and the position slider still work.
 */
private fun Modifier.blockTouches() =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent()
        }
    }

private val LEFT_PANEL_WIDTH = 360.dp
private val RIGHT_PANEL_WIDTH = 400.dp

/** The bottom bar's arrows: ebooks turn through the page script, comics and PDFs restart the pager. */
private fun turnPage(
    by: Int,
    state: ReaderUiState,
    controller: EbookController,
    viewModel: ReaderViewModel,
) {
    when {
        state.content is ReaderContent.Ebook -> if (by < 0) controller.prev() else controller.next()
        else -> state.page?.let { page -> viewModel.goToPage((page + by).coerceIn(1, state.pageCount ?: page)) }
    }
}

private fun openBookmark(
    bookmark: Bookmark,
    controller: EbookController,
    viewModel: ReaderViewModel,
) {
    val cfi = bookmark.cfi
    val page = bookmark.page
    if (cfi != null) {
        controller.goTo(cfi)
    } else if (page != null) {
        viewModel.goToPage(page)
    }
}

/** Fullscreen hides the system bars (a swipe from the edge shows them briefly); restored on leaving. */
@Composable
private fun ImmersiveMode(on: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, on) {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (on) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (on) insets.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun Content(
    content: ReaderContent,
    prefs: ReaderPrefs,
    turns: PageTurns,
    viewModel: ReaderViewModel,
    controller: EbookController,
    onPageReady: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    when (content) {
        ReaderContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ReaderContent.Ebook -> {
            val page = prefs.pageTheme.palette(prefs.einkTint)
            val css = EbookCss.build(prefs, PageColors(page.background.hex(), page.text.hex(), page.link.hex(), page.images.css()))
            val layout = EbookLayout(prefs.gap, prefs.maxColumnCount, prefs.maxInlineSize, prefs.maxBlockSize)
            key(content.book, content.restoreKey) {
                // The page colour also fills the safe-area margins around the book.
                EbookReader(
                    book = content.book,
                    fileName = content.fileName,
                    initialCfi = content.cfi,
                    css = css,
                    layout = layout,
                    animated = LocalMotionEnabled.current && !page.instantTurns,
                    turns = turns,
                    controller = controller,
                    onEvent = { event ->
                        when (event) {
                            is EbookEvent.Ready -> {
                                viewModel.onEbookReady(event.toc, event.sections)
                                onPageReady()
                            }
                            is EbookEvent.Relocated ->
                                viewModel.onEbookPosition(event.locator, event.tocLabel, event.hasPosition, event.bookmark)
                            is EbookEvent.BookmarkHere -> viewModel.onEbookBookmarkHere(event.cfi)
                            is EbookEvent.Search -> viewModel.panels.onSearch(event)
                            is EbookEvent.Lookup -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.lookups.lookUp(event.word)
                            }
                            is EbookEvent.Failed -> Unit
                        }
                    },
                    onToggleChrome = onToggleChrome,
                    modifier = Modifier.fillMaxSize().background(page.background).safeDrawingPadding(),
                )
            }
        }
        is ReaderContent.Paged -> {
            val page = prefs.pageTheme.palette(prefs.einkTint)
            key(content.restoreKey) {
                PagedReader(
                    content.source,
                    content.page,
                    onPage = viewModel::onPage,
                    onToggleChrome = onToggleChrome,
                    modifier = Modifier.background(page.background),
                    turns = turns,
                    imageFilter = page.images.colorFilter,
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

private class ChromeActions(
    val onClose: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onOverlay: (Overlay) -> Unit,
    val onFullscreen: () -> Unit,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onSeekFraction: (Float) -> Unit,
    val onSeekPage: (Int) -> Unit,
)

/**
 * The web reader's bars, dark in every page theme. Top: contents, bookmark, search | title | notes,
 * fullscreen, settings, close. Bottom: previous, position, slider with section marks, next. The quick
 * settings popover hangs under the settings button.
 */
@Composable
private fun Chrome(
    state: ReaderUiState,
    fullscreen: Boolean,
    quickSettings: Boolean,
    actions: ChromeActions,
    popover: @Composable () -> Unit,
) {
    val canRead = state.content is ReaderContent.Ebook || state.content is ReaderContent.Paged
    Column(Modifier.fillMaxSize()) {
        TopBar(state, fullscreen, quickSettings, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (quickSettings) {
                // Taps outside the popover close it instead of turning the page.
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { actions.onOverlay(Overlay.QuickSettings) },
                )
                Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)) { popover() }
            }
        }
        if (canRead) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .blockTouches()
                    .background(CHROME_BG)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChromeButton(LucideIcons.ChevronLeft, "Previous page", onClick = actions.onPrev)
                PositionBar(state, actions.onSeekFraction, actions.onSeekPage, Modifier.weight(1f))
                ChromeButton(LucideIcons.ChevronRight, "Next page", onClick = actions.onNext)
            }
        }
    }
}

/** Contents, bookmark, search | title and chapter | notes, fullscreen, settings, close. */
@Composable
private fun TopBar(
    state: ReaderUiState,
    fullscreen: Boolean,
    quickSettings: Boolean,
    actions: ChromeActions,
) {
    val canRead = state.content is ReaderContent.Ebook || state.content is ReaderContent.Paged
    val ebook = state.content is ReaderContent.Ebook
    // Phones: Notes stays a tab of the search panel, so the title keeps some room.
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_BAR_DP
    Row(
        Modifier
            .fillMaxWidth()
            .blockTouches()
            .background(CHROME_BG)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRead) {
            ChromeButton(LucideIcons.Menu, "Contents and bookmarks") { actions.onOverlay(Overlay.Left(LeftTab.CONTENTS)) }
            val marked = state.bookmarkHere != null
            ChromeButton(
                if (marked) LucideIcons.BookmarkCheck else LucideIcons.Bookmark,
                if (marked) "Remove bookmark" else "Add bookmark",
                tint = if (marked) CHROME_ACCENT else CHROME_FG,
                onClick = actions.onToggleBookmark,
            )
            if (ebook) ChromeButton(LucideIcons.Search, "Search") { actions.onOverlay(Overlay.Right(RightTab.SEARCH)) }
        }
        TitleBlock(state.title, state.location?.takeIf { ebook }, Modifier.weight(1f))
        if (canRead) {
            if (wide || !ebook) ChromeButton(LucideIcons.FileText, "Notes") { actions.onOverlay(Overlay.Right(RightTab.NOTES)) }
            ChromeButton(
                if (fullscreen) LucideIcons.Minimize else LucideIcons.Maximize,
                if (fullscreen) "Exit fullscreen" else "Fullscreen",
                onClick = actions.onFullscreen,
            )
            ChromeButton(
                LucideIcons.Settings,
                "Reading settings",
                tint = if (quickSettings) CHROME_ACCENT else CHROME_FG,
            ) { actions.onOverlay(Overlay.QuickSettings) }
        }
        ChromeButton(LucideIcons.X, "Close book", onClick = actions.onClose)
    }
}

/** Book title, with the chapter under it when known. */
@Composable
private fun TitleBlock(
    title: String,
    chapter: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            color = CHROME_FG,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        chapter?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = CHROME_MUTED,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChromeButton(
    icon: ImageVector,
    description: String,
    tint: Color = CHROME_FG,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Position box ("11%", or "3 / 40" for comics and PDFs) and a slider to jump; ebooks mark where sections
 * start. While dragging, the box shows the target; the jump happens on release.
 */
@Composable
private fun PositionBar(
    state: ReaderUiState,
    onSeekFraction: (Float) -> Unit,
    onSeekPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val pages = state.pageCount?.takeIf { state.content is ReaderContent.Paged && it > 1 }
    val fraction =
        when {
            pages != null -> ((state.page ?: 1) - 1f) / (pages - 1)
            else -> (state.percent ?: 0f) / PERCENT
        }
    val shown = drag ?: fraction
    val label =
        if (pages != null) {
            "${(shown * (pages - 1)).roundToInt() + 1} / $pages"
        } else {
            "${(shown * PERCENT).roundToInt()}%"
        }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(6.dp)
        Text(
            label,
            color = CHROME_FG,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier =
                Modifier
                    .widthIn(min = 64.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        ThinSlider(
            value = shown,
            description = "Position in book",
            colors = SliderColors(CHROME_TRACK, CHROME_MARK, CHROME_ACCENT),
            marks = if (pages == null) state.sections else emptyList(),
            onDrag = { drag = it },
            onRelease = {
                drag?.let { target ->
                    if (pages != null) onSeekPage((target * (pages - 1)).roundToInt() + 1) else onSeekFraction(target)
                }
                drag = null
            },
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
        )
    }
}

private const val PERCENT = 100f
private const val WIDE_BAR_DP = 600

/** Reader chrome colours (web: black bars, white icons, primary-400 accent). */
private val CHROME_BG = Color(0xF20A0A0A)
private val CHROME_FG = Color.White.copy(alpha = 0.85f)
private val CHROME_MUTED = Color.White.copy(alpha = 0.5f)
private val CHROME_TRACK = Color.White.copy(alpha = 0.15f)
private val CHROME_MARK = Color.White.copy(alpha = 0.35f)
private val CHROME_ACCENT = Color(0xFFFF8904)

/** E-ink paper grain and refresh flash over the page. They only draw; touches go through to the page. */
@Composable
private fun BoxScope.PageOverlays(
    state: ReaderUiState,
    prefs: ReaderPrefs,
) {
    val reading = state.content is ReaderContent.Ebook || state.content is ReaderContent.Paged
    if (reading && prefs.pageTheme == PageTheme.EINK && prefs.einkGrain) Box(Modifier.matchParentSize().paperGrain())
    refreshFlash(prefs, state.pageTurns)?.let { Box(Modifier.matchParentSize().background(it)) }
}

/** E-ink refresh flash: one ink frame, then one paper frame, every N page turns; null otherwise. */
@Composable
private fun refreshFlash(
    prefs: ReaderPrefs,
    pageTurns: Int,
): Color? {
    var flash by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(pageTurns) {
        val every = prefs.einkFlashEvery.takeIf { prefs.pageTheme == PageTheme.EINK && it > 0 } ?: return@LaunchedEffect
        if (pageTurns == 0 || pageTurns % every != 0) return@LaunchedEffect
        val page = prefs.pageTheme.palette(prefs.einkTint)
        flash = page.text
        delay(FLASH_INK_MS)
        flash = page.background
        delay(FLASH_PAPER_MS)
        flash = null
    }
    return flash
}

private const val FLASH_INK_MS = 120L
private const val FLASH_PAPER_MS = 90L

/** CSS filter for pictures inside ebooks, matching the bitmap filters used for comic/PDF pages. */
private fun PageImages.css(): String? =
    when (this) {
        PageImages.NORMAL -> null
        PageImages.GRAYSCALE -> "grayscale(1) contrast(1.15)"
        PageImages.WARM -> "sepia(0.4) brightness(0.8)"
    }

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
