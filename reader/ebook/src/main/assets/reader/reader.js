// GrimmReader ebook host: foliate-js (vendored, see assets/foliate/SOURCE.md) talking to Kotlin through
// the "grimm" WebMessageListener. Only app-local URLs are reachable; the WebView has no network.
import '../foliate/view.js'
import * as CFI from '../foliate/epubcfi.js'
import { Overlayer } from '../foliate/overlayer.js'

const post = m => grimm.postMessage(JSON.stringify(m))
const params = new URLSearchParams(location.search)
const view = document.createElement('foliate-view')
document.body.append(view)

let ready = false
let lastCfi = null
let visible = null
let bookmarks = []
let searchId = 0

const MAX_HITS = 500
// Top/bottom (side, when vertical) margin of the page. The paginator's default is 48px, room for a running head
// and page number, which this reader does not show; the safe-area padding already keeps text clear of the bars.
const PAGE_MARGIN = 8
const clamp = (v, lo, hi) => Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : null
// Paginator layout; values are checked here too, they end up in CSS.
const applyLayout = l => {
    const r = view.renderer
    if (!r || !l || typeof l !== 'object') return
    const gap = clamp(l.gap, 0, 0.5), cols = clamp(l.columns, 1, 4)
    const width = clamp(l.maxInlineSize, 200, 4000), height = clamp(l.maxBlockSize, 200, 8000)
    r.setAttribute('margin', `${PAGE_MARGIN}px`)
    if (gap != null) r.setAttribute('gap', `${Math.round(gap * 1000) / 10}%`)
    if (cols != null) r.setAttribute('max-column-count', String(Math.round(cols)))
    if (width != null) r.setAttribute('max-inline-size', `${Math.round(width)}px`)
    if (height != null) r.setAttribute('max-block-size', `${Math.round(height)}px`)
}
const parseJson = s => { try { return JSON.parse(s) } catch { return null } }

// Whole-book search, numbered by the app; results go out per chapter. A newer search (or clearSearch) ends an
// older one.
const search = async (query, id) => {
    if (!Number.isInteger(id)) return
    searchId = id
    view.clearSearch()
    if (typeof query !== 'string' || !query.trim()) return
    let hits = 0
    try {
        for await (const r of view.search({ query: query.trim() })) {
            if (id !== searchId) return
            if (r === 'done') break
            if (typeof r.progress === 'number') post({ t: 'search', id, progress: r.progress })
            if (Array.isArray(r.subitems)) {
                const items = r.subitems.slice(0, MAX_HITS - hits).map(({ cfi, excerpt }) => ({
                    cfi, pre: excerpt?.pre ?? '', match: excerpt?.match ?? '', post: excerpt?.post ?? '',
                }))
                hits += items.length
                post({ t: 'search', id, label: r.label ?? '', items })
                if (hits >= MAX_HITS) break
            }
        }
        if (id === searchId) post({ t: 'search', id, done: true })
    } catch (err) {
        if (id === searchId) post({ t: 'search', id, done: true, error: String(err) })
    }
}

// Word at a point of the page (CSS px of this window), found in the section frame under it.
// The caret nearest the point is used, so a point in a margin must still fall on the word's own box.
const LOOKUP_SLOP = 4
const wordInDoc = (doc, x, y) => {
    let node, offset
    const caret = doc.caretPositionFromPoint?.(x, y)
    if (caret) { node = caret.offsetNode; offset = caret.offset }
    else {
        const r = doc.caretRangeFromPoint?.(x, y)
        if (!r) return null
        node = r.startContainer; offset = r.startOffset
    }
    if (!node || node.nodeType !== 3) return null
    const text = node.data
    const lang = node.parentElement?.closest('[lang]')?.getAttribute('lang') || undefined
    let segmenter
    try { segmenter = new Intl.Segmenter(lang, { granularity: 'word' }) } catch { segmenter = new Intl.Segmenter(undefined, { granularity: 'word' }) }
    const segments = segmenter.segment(text)
    for (const at of [offset, offset - 1]) {
        if (at < 0 || at >= text.length) continue
        const seg = segments.containing(at)
        if (!seg?.isWordLike) continue
        const range = doc.createRange()
        range.setStart(node, seg.index)
        range.setEnd(node, seg.index + seg.segment.length)
        const hit = Array.from(range.getClientRects()).some(r =>
            x >= r.left - LOOKUP_SLOP && x <= r.right + LOOKUP_SLOP && y >= r.top - LOOKUP_SLOP && y <= r.bottom + LOOKUP_SLOP)
        return hit ? { range, word: seg.segment } : null
    }
    return null
}
// Selection: a long press selects the word under the finger, dragging extends it by whole words, lifting reports
// the text and its CFI. The page itself gets no touches, so this is driven by the app's gesture layer.
const SEL_KEY = 'grimm-selection'
const SEL_COLOR = 'rgb(96, 165, 250)'
const MAX_SELECTION = 5000
let sel = null
const snapped = (node, offset, forward) => {
    if (!node || node.nodeType !== 3) return { node, offset }
    const seg = new Intl.Segmenter(undefined, { granularity: 'word' }).segment(node.data).containing(Math.min(offset, node.data.length - 1))
    if (!seg?.isWordLike) return { node, offset }
    return { node, offset: forward ? seg.index + seg.segment.length : seg.index }
}
const clearSelection = () => {
    for (const { doc, overlayer } of view.renderer?.getContents?.() ?? []) {
        overlayer?.remove(SEL_KEY)
        doc?.getSelection()?.removeAllRanges()
    }
    sel = null
}
const drawSelection = () => {
    if (sel.overlayer) {
        sel.overlayer.remove(SEL_KEY)
        sel.overlayer.add(SEL_KEY, sel.range, Overlayer.highlight, { color: SEL_COLOR })
    } else {
        const s = sel.doc.getSelection()
        s.removeAllRanges()
        s.addRange(sel.range)
    }
}
// The section frame under a point of this window, with the point in the frame's own CSS px.
const frameAt = (x, y) => {
    for (const { doc, overlayer, index } of view.renderer?.getContents?.() ?? []) {
        const frame = doc?.defaultView?.frameElement
        if (!frame) continue
        const rect = frame.getBoundingClientRect()
        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) continue
        // Fixed-layout pages are scaled frames.
        const sx = frame.clientWidth ? rect.width / frame.clientWidth : 1
        const sy = frame.clientHeight ? rect.height / frame.clientHeight : 1
        return { doc, overlayer, index, x: (x - rect.left) / sx, y: (y - rect.top) / sy }
    }
    return null
}
const selectAt = (x, y) => {
    clearSelection()
    if (!ready || !Number.isFinite(x) || !Number.isFinite(y)) return
    const at = frameAt(x, y)
    const found = at && wordInDoc(at.doc, at.x, at.y)
    if (!found) return
    sel = { doc: at.doc, overlayer: at.overlayer, index: at.index, anchor: found.range.cloneRange(), range: found.range }
    drawSelection()
}
const extendTo = (x, y) => {
    if (!sel || !Number.isFinite(x) || !Number.isFinite(y)) return
    const at = frameAt(x, y)
    if (!at || at.doc !== sel.doc) return
    let node, offset
    const caret = at.doc.caretPositionFromPoint?.(at.x, at.y)
    if (caret) { node = caret.offsetNode; offset = caret.offset }
    else {
        const r = at.doc.caretRangeFromPoint?.(at.x, at.y)
        if (!r) return
        node = r.startContainer; offset = r.startOffset
    }
    if (!node || node.nodeType !== 3) return
    const probe = sel.doc.createRange()
    probe.setStart(node, offset)
    probe.collapse(true)
    const range = sel.doc.createRange()
    if (probe.compareBoundaryPoints(Range.START_TO_START, sel.anchor) < 0) {
        const p = snapped(node, offset, false)
        range.setStart(p.node, p.offset)
        range.setEnd(sel.anchor.endContainer, sel.anchor.endOffset)
    } else {
        const p = snapped(node, offset, true)
        range.setStart(sel.anchor.startContainer, sel.anchor.startOffset)
        range.setEnd(p.node, p.offset)
    }
    sel.range = range
    drawSelection()
}
// Ids of the highlights the selection touches, so the app can offer to remove them.
const overlapping = async () => {
    const ids = []
    for (const h of highlights) {
        if (h.index !== sel.index) continue
        try {
            const r = (await view.resolveCFI(h.cfi)).anchor(sel.doc)
            if (sel.range.compareBoundaryPoints(Range.END_TO_START, r) < 0 && sel.range.compareBoundaryPoints(Range.START_TO_END, r) > 0) ids.push(h.id)
        } catch { /* a highlight that no longer resolves */ }
    }
    return ids
}
const endSelection = async () => {
    const current = sel
    if (!current) return
    const text = current.range.toString().replace(/\s+/g, ' ').trim().slice(0, MAX_SELECTION)
    if (!text) return clearSelection()
    const over = await overlapping()
    if (sel === current) post({ t: 'selection', text, cfi: view.getCFI(current.index, current.range), over })
}

// Highlights of this book, drawn over the page; sections other than the visible one are drawn when they load.
let highlights = []
const paint = h => view.addAnnotation({ value: h.cfi, color: h.color }).catch(() => {})
view.addEventListener('draw-annotation', e => e.detail.draw(Overlayer.highlight, { color: e.detail.annotation.color }))
view.addEventListener('create-overlay', e => highlights.filter(h => h.index === e.detail.index).forEach(paint))
let highlightRun = 0
const setHighlights = async list => {
    const run = ++highlightRun
    const next = []
    for (const h of Array.isArray(list) ? list : []) {
        if (!Number.isInteger(h?.id) || typeof h.cfi !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(h.color)) continue
        try { next.push({ id: h.id, cfi: h.cfi, color: h.color, index: (await view.resolveCFI(h.cfi)).index }) } catch { /* skip */ }
    }
    if (run !== highlightRun) return
    const keep = new Set(next.map(h => h.cfi))
    for (const old of highlights) if (!keep.has(old.cfi)) view.deleteAnnotation({ value: old.cfi }).catch(() => {})
    highlights = next
    highlights.forEach(paint)
}

// The bookmark whose position starts on the visible page (web bookmarks are range CFIs too), or null.
const bookmarkHere = () => {
    if (!visible) return null
    const start = CFI.collapse(visible), end = CFI.collapse(visible, true)
    return bookmarks.find(b => {
        try {
            const p = CFI.collapse(b)
            return CFI.compare(p, start) >= 0 && (CFI.compare(p, end) < 0 || CFI.compare(p, start) === 0)
        } catch { return false }
    }) ?? null
}
view.addEventListener('relocate', e => {
    const d = e.detail
    // foliate emits a transient relocate at the section start before the restored position (Spike a).
    if (!ready || !d.cfi || d.cfi === lastCfi) return
    lastCfi = d.cfi
    visible = d.cfi
    if (sel) { clearSelection(); post({ t: 'selection', cleared: true }) }
    post({
        t: 'relocate', cfi: d.cfi, bookmark: bookmarkHere(),
        fraction: typeof d.fraction === 'number' ? d.fraction : null,
        href: d.pageItem?.href ?? d.tocItem?.href ?? null,
        toc: d.tocItem?.label ?? null,
    })
})

// Narration. Walks the sections in reading order without rendering them (a detached document per section) and
// hands the app passages of one or two sentences, each with the CFI of its range. The app speaks them, queued far
// ahead, and tells us which one is being spoken so it can be marked and turned to.
const NARR_BLOCKS = new Set([
    'address', 'article', 'aside', 'blockquote', 'caption', 'dd', 'details', 'dialog', 'div', 'dl', 'dt', 'figcaption',
    'figure', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header', 'hgroup', 'hr', 'li', 'main', 'nav', 'ol',
    'p', 'pre', 'section', 'table', 'td', 'th', 'tr', 'ul',
])
const NARR_SKIP = new Set(['script', 'style', 'head', 'rt', 'rp', 'svg', 'math', 'noscript', 'template', 'audio', 'video'])
const NARR_TARGET = 120, NARR_MAX = 600, NARR_HARD = 1200
const NARR_KEY = 'grimm-narration', NARR_COLOR = 'rgb(250, 204, 21)'
let narr = null
let narrChunk = null
let narrRun = 0
const narrReply = (req, body) => post({ t: 'narration', req, ...body })

const narrBuildChunks = async index => {
    const section = view.book.sections[index]
    if (!section || section.linear === 'no') return []
    let doc
    try { doc = await section.createDocument() } catch { return [] }
    if (!doc?.body) return []
    const runs = []
    let cur = null
    const flush = () => { if (cur && cur.text.trim()) runs.push(cur); cur = null }
    const walk = (el, block) => {
        for (let n = el.firstChild; n; n = n.nextSibling) {
            if (n.nodeType === 3) {
                if (!n.data) continue
                if (!cur || cur.block !== block) { flush(); cur = { block, parts: [], text: '' } }
                cur.parts.push({ node: n, start: cur.text.length })
                cur.text += n.data
            } else if (n.nodeType === 1) {
                const name = n.localName?.toLowerCase()
                if (NARR_SKIP.has(name) || n.hasAttribute('hidden') || n.getAttribute('aria-hidden') === 'true') continue
                // Footnote markers ("1", "[2]") are not read.
                if (name === 'a' && n.parentElement?.localName === 'sup' && /^\[?\d+\]?$/.test(n.textContent.trim())) continue
                if (name === 'br') { flush(); continue }
                const isBlock = NARR_BLOCKS.has(name)
                if (isBlock) flush()
                walk(n, isBlock ? n : block)
                if (isBlock) flush()
            }
        }
    }
    walk(doc.body, doc.body)
    flush()
    const chunks = []
    for (const run of runs) {
        const lang = run.block.closest?.('[lang]')?.getAttribute('lang') || doc.documentElement.getAttribute('lang') || undefined
        let segmenter
        try { segmenter = new Intl.Segmenter(lang, { granularity: 'sentence' }) } catch { segmenter = new Intl.Segmenter(undefined, { granularity: 'sentence' }) }
        const at = offset => {
            // The text node and offset of a character offset of the run.
            let part = run.parts[0]
            for (const p of run.parts) { if (p.start <= offset) part = p; else break }
            return [part.node, Math.min(offset - part.start, part.node.data.length)]
        }
        const emit = (from, to) => {
            while (to - from > NARR_HARD) {
                let cut = run.text.lastIndexOf(' ', from + NARR_HARD)
                if (cut <= from) cut = from + NARR_HARD
                emit(from, cut)
                from = cut
            }
            while (from < to && /\s/.test(run.text[from])) from++
            while (to > from && /\s/.test(run.text[to - 1])) to--
            const text = run.text.slice(from, to).replace(/\s+/g, ' ')
            if (!/[\p{L}\p{N}]/u.test(text)) return
            const range = doc.createRange()
            const [sn, so] = at(from)
            // The end is looked up on the last character, so it stays in the node that holds it.
            const [en, eo] = at(to - 1)
            range.setStart(sn, so)
            range.setEnd(en, eo + 1)
            try { chunks.push({ text, cfi: view.getCFI(index, range), section: index }) } catch { /* no CFI for this range */ }
        }
        let acc = null
        for (const { index: i, segment } of segmenter.segment(run.text)) {
            if (acc && acc.e - acc.s + segment.length > NARR_MAX) { emit(acc.s, acc.e); acc = null }
            acc = acc ? { s: acc.s, e: i + segment.length } : { s: i, e: i + segment.length }
            if (acc.e - acc.s >= NARR_TARGET) { emit(acc.s, acc.e); acc = null }
        }
        if (acc) emit(acc.s, acc.e)
    }
    return chunks
}

// Where reading starts: the visible page, or a given CFI (just after it when `after`).
const narrationBegin = async (req, from, after) => {
    try {
        narr = null
        const startCfi = typeof from === 'string' && from ? (after ? CFI.collapse(from, true) : CFI.collapse(from)) : (visible ? CFI.collapse(visible) : null)
        const index = startCfi ? (await view.resolveCFI(startCfi)).index : 0
        narr = { index, startCfi, queue: [], done: false, first: true }
        narrReply(req, { ok: true })
    } catch (err) {
        narrReply(req, { ok: false, error: String(err) })
    }
}
const narrationNext = async (req, count) => {
    const n = narr
    try {
        if (!n) return narrReply(req, { chunks: [], error: 'not started' })
        while (n.queue.length < count && !n.done) {
            if (n.index >= view.book.sections.length) { n.done = true; break }
            let chunks = await narrBuildChunks(n.index)
            if (n.first) {
                n.first = false
                // The first section starts at the page: keep what ends after its start.
                if (n.startCfi) chunks = chunks.filter(c => CFI.compare(CFI.collapse(c.cfi, true), n.startCfi) > 0)
            }
            n.queue.push(...chunks)
            n.index++
        }
        if (n !== narr) return
        narrReply(req, { chunks: n.queue.splice(0, count) })
    } catch (err) {
        narrReply(req, { chunks: [], error: String(err) })
    }
}

const onVisiblePage = cfi => {
    if (!visible) return false
    try {
        const p = CFI.collapse(cfi)
        return CFI.compare(p, CFI.collapse(visible)) >= 0 && CFI.compare(p, CFI.collapse(visible, true)) < 0
    } catch { return false }
}
const narrationDraw = async chunk => {
    try {
        const r = await view.resolveCFI(chunk.cfi)
        const c = view.renderer.getContents().find(x => x.index === r.index)
        if (!c?.overlayer) return
        c.overlayer.remove(NARR_KEY)
        c.overlayer.add(NARR_KEY, r.anchor(c.doc), Overlayer.highlight, { color: NARR_COLOR })
    } catch { /* section not shown */ }
}
const narrationFollow = async (chunk, run) => {
    try {
        if (!onVisiblePage(chunk.cfi)) {
            await view.goTo(chunk.cfi)
            if (run !== narrRun) return
        }
        await narrationDraw(chunk)
    } catch { /* the page is not there (any more) */ }
}
// Only a visible page is turned and drawn; a hidden one (screen off, app in the background) catches up on return.
const narrationSpoken = chunk => {
    if (!chunk || typeof chunk.cfi !== 'string') return
    narrChunk = chunk
    const run = ++narrRun
    if (document.visibilityState === 'visible') narrationFollow(chunk, run)
}
const narrationClear = () => {
    narrChunk = null
    narrRun++
    for (const { overlayer } of view.renderer?.getContents?.() ?? []) overlayer?.remove(NARR_KEY)
}
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && narrChunk) narrationFollow(narrChunk, ++narrRun)
})
view.addEventListener('create-overlay', e => { if (narrChunk && narrChunk.section === e.detail.index) narrationDraw(narrChunk) })

window.grimm_api = {
    next: () => view.next(),
    prev: () => view.prev(),
    goTo: target => view.goTo(target),
    goToFraction: f => view.goToFraction(f),
    setStyle: css => view.renderer.setStyles?.(css),
    // Bookmark CFIs of this book; answers with the one on the visible page.
    setBookmarks: list => { bookmarks = Array.isArray(list) ? list.filter(c => typeof c === 'string') : []; post({ t: 'bookmark', cfi: bookmarkHere() }) },
    setAnimated: on => on ? view.renderer.setAttribute('animated', '') : view.renderer.removeAttribute('animated'),
    setLayout: applyLayout,
    search: (query, id) => { search(query, id) },
    clearSearch: () => { searchId++; view.clearSearch() },
    selectAt,
    extendTo,
    endSelection,
    clearSelection,
    setHighlights,
    narrationBegin,
    narrationNext,
    narrationSpoken,
    narrationClear,
}

try {
    const blob = await (await fetch('/book/current')).blob()
    await view.open(new File([blob], params.get('name') || 'book'))
    // Chapters in reading order, nested ones flattened with their depth.
    const flat = (items, depth) => (items ?? []).flatMap(i => [{ label: i.label, href: i.href, depth }, ...flat(i.subitems, depth + 1)])
    const toc = flat(view.book.toc, 0)
    applyLayout(parseJson(params.get('layout')))
    await view.init({ lastLocation: params.get('cfi') || null })
    // No page-turn animation for the initial positioning; styles apply once the first section is shown.
    window.grimm_api.setAnimated(params.get('animated') === '1')
    if (params.get('css')) window.grimm_api.setStyle(params.get('css'))
    ready = true
    // Where each section starts (0..1), for the marks on the position slider.
    post({ t: 'ready', toc, sections: view.getSectionFractions().filter(Number.isFinite) })
    // Report the restored position once init settled.
    const loc = view.lastLocation
    if (loc?.cfi) { lastCfi = null; view.dispatchEvent(new CustomEvent('relocate', { detail: loc })) }
} catch (err) {
    post({ t: 'error', message: String(err) })
}
