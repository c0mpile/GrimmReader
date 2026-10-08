package com.c0mpile.grimmreader.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.c0mpile.grimmreader.feature.bookdetail.BookDrawer

/**
 * Which book's actions drawer is open. A key is "<place>/<book id>", so a book shown twice on one screen
 * (two dashboard rows) opens its drawer only where it was tapped. [shown] is the drawer that is laid out:
 * when another book is tapped, the old drawer slides shut first and only then moves.
 */
@Stable
internal class BookDrawerState(
    open: String?,
) {
    var open by mutableStateOf(open)
        private set
    var shown by mutableStateOf(open)
        private set

    fun isOpen(
        place: String,
        bookId: Long,
    ) = open == key(place, bookId)

    fun isShown(
        place: String,
        bookId: Long,
    ) = shown == key(place, bookId)

    /** Tapping the open book again closes its drawer. */
    fun toggle(
        place: String,
        bookId: Long,
    ) {
        val key = key(place, bookId)
        open = if (open == key) null else key
        if (shown == null) shown = open
    }

    fun close() {
        open = null
    }

    internal fun settled() {
        shown = open
    }

    /** Drops a drawer whose book is no longer listed (filtered out or removed), so it cannot block the next one. */
    fun forgetMissing(present: Set<String>) {
        if (open != null && open !in present) open = null
        if (shown != null && shown !in present) shown = open
    }

    companion object {
        fun key(
            place: String,
            bookId: Long,
        ) = "$place/$bookId"

        val Saver = Saver<BookDrawerState, String>(save = { it.open }, restore = { BookDrawerState(it) })
    }
}

@Composable
internal fun rememberBookDrawerState() = rememberSaveable(saver = BookDrawerState.Saver) { BookDrawerState(null) }

/** The drawer of [bookId] at [place], sliding open or shut; the caller emits it only where [BookDrawerState.isShown]. */
@Composable
internal fun BookDrawerSlot(
    state: BookDrawerState,
    place: String,
    bookId: Long,
    onRead: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(place, bookId) { MutableTransitionState(false) }
    visible.targetState = state.isOpen(place, bookId)
    val requester = remember { BringIntoViewRequester() }
    AnimatedVisibility(
        visible,
        modifier = modifier.bringIntoViewRequester(requester),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        BookDrawer(bookId, onRead, onClose = state::close)
    }
    val settled = visible.isIdle
    LaunchedEffect(settled, visible.currentState) {
        if (!settled) return@LaunchedEffect
        if (visible.currentState) requester.bringIntoView() else state.settled()
    }
}
