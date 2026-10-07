package com.c0mpile.grimmreader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.progress.ProgressRepository
import com.c0mpile.grimmreader.core.data.progress.RemotePosition
import com.c0mpile.grimmreader.core.data.stream.OnlineReading
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.model.BookFile
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Locator
import com.c0mpile.grimmreader.core.model.ReaderPrefs
import com.c0mpile.grimmreader.core.model.ReadingDirection
import com.c0mpile.grimmreader.reader.comic.ArchivePageSource
import com.c0mpile.grimmreader.reader.comic.StreamingPageSource
import com.c0mpile.grimmreader.reader.paged.PageSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface ReaderContent {
    data object Loading : ReaderContent

    /** [restoreKey] changes when a remote position is accepted, so the reader view restarts there. */
    data class Ebook(
        val file: File,
        val cfi: String?,
        val restoreKey: Int = 0,
    ) : ReaderContent

    data class Paged(
        val source: PageSource,
        val page: Int,
        val restoreKey: Int = 0,
    ) : ReaderContent

    data class Unsupported(
        val format: BookFormat,
    ) : ReaderContent

    /** A server book being fetched for online reading; [progress] is 0..1 or null when unknown. */
    data class Fetching(
        val progress: Float?,
    ) : ReaderContent

    data class Failed(
        val message: String,
    ) : ReaderContent

    data object NotDownloaded : ReaderContent
}

data class ReaderUiState(
    val title: String = "",
    val content: ReaderContent = ReaderContent.Loading,
    val percent: Float? = null,
    val location: String? = null,
    val offer: RemotePosition? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = ReaderViewModel.Factory::class)
class ReaderViewModel
    @AssistedInject
    constructor(
        @Assisted private val bookId: Long,
        private val library: LibraryRepository,
        private val progress: ProgressRepository,
        private val online: OnlineReading,
        prefs: AppPreferences,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ReaderUiState())
        val state: StateFlow<ReaderUiState> = _state.asStateFlow()

        val readerPrefs: StateFlow<ReaderPrefs> = prefs.readerPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())
        private val appPrefs = prefs

        private val pending = MutableStateFlow<Locator?>(null)
        private var source: PageSource? = null

        init {
            viewModelScope.launch { open() }
            // Push at most every 2 s of idle time; flush() covers pause and close.
            pending
                .filterNotNull()
                .debounce(SAVE_DEBOUNCE_MS)
                .onEach { progress.save(bookId, it) }
                .launchIn(viewModelScope)
        }

        private suspend fun open() {
            val book = library.observeBook(bookId).first() ?: return
            val file = book.primaryFile
            _state.update { it.copy(title = book.title, percent = book.progressPercent) }
            val local = progress.local(bookId)
            val content =
                try {
                    val path = file?.localUri
                    val serverId = book.serverId
                    when {
                        file == null -> ReaderContent.NotDownloaded
                        path != null -> openLocal(File(path), file.format, local)
                        book.source == BookSource.SERVER && serverId != null -> openOnline(serverId, file, local)
                        else -> ReaderContent.NotDownloaded
                    }
                } catch (_: IOException) {
                    ReaderContent.Failed(ONLINE_FAILED)
                }
            _state.update { it.copy(content = content) }
            if (content is ReaderContent.Ebook || content is ReaderContent.Paged) {
                progress.remoteToOffer(bookId)?.let { offer -> _state.update { it.copy(offer = offer) } }
            }
        }

        private suspend fun openLocal(
            file: File,
            format: BookFormat,
            local: Locator?,
        ): ReaderContent =
            if (format.isReflowable) {
                ReaderContent.Ebook(file, (local as? Locator.Epub)?.cfi)
            } else {
                val opened = withContext(Dispatchers.IO) { ArchivePageSource.open(file, format, null) }
                if (opened == null) ReaderContent.Unsupported(format) else paged(opened, local)
            }

        /** Not downloaded: ebooks are fetched whole into the cache, comics are streamed page by page. */
        private suspend fun openOnline(
            serverBookId: Long,
            file: BookFile,
            local: Locator?,
        ): ReaderContent =
            when {
                file.format.isComic -> {
                    val pages = online.comicPages(serverBookId)
                    if (pages.isEmpty()) {
                        ReaderContent.Failed(ONLINE_FAILED)
                    } else {
                        paged(StreamingPageSource(pages, ReadingDirection.LTR) { online.comicPage(serverBookId, it) }, local)
                    }
                }
                file.format.isReflowable -> {
                    _state.update { it.copy(content = ReaderContent.Fetching(null)) }
                    val (cached, format) =
                        online.book(serverBookId, file.serverFileId, file.format) { p ->
                            _state.update { s ->
                                if (s.content is ReaderContent.Fetching) s.copy(content = ReaderContent.Fetching(p)) else s
                            }
                        }
                    openLocal(cached, format, local)
                }
                else -> ReaderContent.Unsupported(file.format)
            }

        private fun paged(
            opened: PageSource,
            local: Locator?,
        ): ReaderContent {
            source = opened
            return ReaderContent.Paged(opened, ((local as? Locator.Page)?.page ?: 1) - 1)
        }

        fun onEbookPosition(
            locator: Locator.Epub,
            tocLabel: String?,
            hasPosition: Boolean,
        ) {
            _state.update { it.copy(location = tocLabel, percent = if (hasPosition) locator.percent else it.percent) }
            if (hasPosition) pending.value = locator
        }

        fun onPage(index: Int) {
            val count = source?.pageCount ?: return
            val locator = Locator.Page(index + 1, count)
            _state.update { it.copy(location = "${index + 1} / $count", percent = locator.percent) }
            pending.value = locator
        }

        fun acceptOffer() {
            val offer = _state.value.offer ?: return
            viewModelScope.launch {
                progress.acceptRemote(bookId, offer)
                pending.value = null
                _state.update { s ->
                    val content =
                        when (val c = s.content) {
                            is ReaderContent.Ebook ->
                                c.copy(
                                    cfi = (offer.locator as? Locator.Epub)?.cfi ?: c.cfi,
                                    restoreKey =
                                        c.restoreKey + 1,
                                )
                            is ReaderContent.Paged ->
                                c.copy(
                                    page = ((offer.locator as? Locator.Page)?.page ?: 1) - 1,
                                    restoreKey =
                                        c.restoreKey + 1,
                                )
                            else -> c
                        }
                    s.copy(offer = null, content = content)
                }
            }
        }

        fun dismissOffer() = _state.update { it.copy(offer = null) }

        fun setReaderPrefs(prefs: ReaderPrefs) = viewModelScope.launch { appPrefs.setReaderPrefs(prefs) }

        /** Saves the latest position now (app paused or reader closed). */
        fun flush() {
            val locator = pending.value ?: return
            pending.value = null
            viewModelScope.launch { progress.save(bookId, locator) }
        }

        override fun onCleared() {
            pending.value?.let { locator -> kotlinx.coroutines.runBlocking { progress.save(bookId, locator) } }
            source?.close()
        }

        @AssistedFactory
        interface Factory {
            fun create(bookId: Long): ReaderViewModel
        }

        private companion object {
            const val SAVE_DEBOUNCE_MS = 2_000L
            const val ONLINE_FAILED = "Could not load this book from the server. Check the connection, or download it to read offline."
        }
    }
