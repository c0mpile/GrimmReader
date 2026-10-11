package com.c0mpile.grimmreader.core.tts

/**
 * The passages of a narration: those fetched and not yet spoken ([head] is the one being spoken or next), those
 * already spoken (for "previous"), and which of them the speech engine has been given. Each time the engine's queue
 * is dropped the [epoch] changes, so late events of the old queue can be told apart from the new one's.
 */
internal class NarrationQueue {
    class Item(
        val seq: Long,
        val chunk: NarrationChunk,
    ) {
        /** Characters of the passage already heard (before a pause); speaking goes on after them. */
        var skip = 0

        val text: String get() = chunk.text.substring(skip)
    }

    private val ahead = ArrayDeque<Item>()
    private val spoken = ArrayDeque<Item>()
    private var nextSeq = 0L
    private var submittedTo = -1L

    var epoch = 0
        private set

    /** The book has no more passages to fetch. */
    var ended = false

    /** The last passage fetched, where a rebuilt page continues. */
    var lastFetched: NarrationChunk? = null
        private set

    val head: Item? get() = ahead.firstOrNull()
    val isEmpty: Boolean get() = ahead.isEmpty()
    val queuedChars: Int get() = ahead.sumOf { it.chunk.text.length }
    val queuedCount: Int get() = ahead.size

    fun clear() {
        ahead.clear()
        spoken.clear()
        nextSeq = 0
        ended = false
        lastFetched = null
        dropSubmitted()
    }

    /** The engine's queue was dropped: everything in [ahead] has to be submitted again. */
    fun dropSubmitted() {
        epoch++
        submittedTo = -1
    }

    fun add(chunks: List<NarrationChunk>) =
        chunks.forEach {
            ahead.addLast(Item(nextSeq++, it))
            lastFetched = it
        }

    /** The passages the engine does not have yet; with [section] set, only those of that section. */
    fun takeUnsubmitted(section: Int?): List<Item> {
        val todo = ahead.filter { it.seq > submittedTo && (section == null || it.chunk.section == section) }
        todo.lastOrNull()?.let { submittedTo = it.seq }
        return todo
    }

    /** [seq] was spoken to the end. */
    fun finished(seq: Long) {
        if (head?.seq == seq) remember(ahead.removeFirst())
    }

    fun skipForward(): Boolean = ahead.removeFirstOrNull()?.also(::remember) != null

    fun skipBack(): Boolean = spoken.removeLastOrNull()?.also { ahead.addFirst(it) } != null

    fun id(item: Item) = "n$epoch:${item.seq}"

    /** The (epoch, seq) of an utterance id made by [id]; null for anyone else's. */
    fun parse(id: String): Pair<Int, Long>? {
        if (!id.startsWith("n")) return null
        val e = id.substring(1).substringBefore(':').toIntOrNull() ?: return null
        val s = id.substringAfter(':').toLongOrNull() ?: return null
        return e to s
    }

    private fun remember(item: Item) {
        item.skip = 0
        spoken.addLast(item)
        while (spoken.size > HISTORY) spoken.removeFirst()
    }

    private companion object {
        const val HISTORY = 30
    }
}
