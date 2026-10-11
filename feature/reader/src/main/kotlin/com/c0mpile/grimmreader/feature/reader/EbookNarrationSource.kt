package com.c0mpile.grimmreader.feature.reader

import com.c0mpile.grimmreader.core.tts.NarrationChunk
import com.c0mpile.grimmreader.core.tts.NarrationSource
import com.c0mpile.grimmreader.reader.ebook.EbookController
import com.c0mpile.grimmreader.reader.ebook.NarrationText

/** The open ebook as narration text: the page script cuts the book into passages and marks the one being spoken. */
internal class EbookNarrationSource(
    private val ebook: EbookController,
) : NarrationSource {
    override suspend fun begin(
        from: String?,
        after: Boolean,
    ): Boolean = ebook.narration.begin(from, after)

    override suspend fun next(count: Int): List<NarrationChunk>? =
        ebook.narration.next(count)?.map { NarrationChunk(it.text, it.cfi, it.section) }

    override fun spoken(chunk: NarrationChunk) = ebook.narration.spoken(NarrationText(chunk.text, chunk.locator, chunk.section))

    override fun clearSpoken() = ebook.narration.clear()
}
