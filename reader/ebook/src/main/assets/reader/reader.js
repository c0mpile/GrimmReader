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
