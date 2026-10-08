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
const clamp = (v, lo, hi) => Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : null
// Paginator layout; values are checked here too, they end up in CSS.
const applyLayout = l => {
    const r = view.renderer
    if (!r || !l || typeof l !== 'object') return
    const gap = clamp(l.gap, 0, 0.5), cols = clamp(l.columns, 1, 4)
    const width = clamp(l.maxInlineSize, 200, 4000), height = clamp(l.maxBlockSize, 200, 8000)
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

// Word lookup: the word at a point of the page (CSS px of this window), found in the section frame under it.
// The caret nearest the point is used, so a point in a margin must still fall on the word's own box.
const LOOKUP_KEY = 'grimm-lookup'
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
const clearLookup = () => {
    for (const { doc, overlayer } of view.renderer?.getContents?.() ?? []) {
        overlayer?.remove(LOOKUP_KEY)
        doc?.getSelection()?.removeAllRanges()
    }
}
const lookupAt = (x, y) => {
    if (!ready || !Number.isFinite(x) || !Number.isFinite(y)) return
    clearLookup()
    for (const { doc, overlayer } of view.renderer?.getContents?.() ?? []) {
        const frame = doc?.defaultView?.frameElement
        if (!frame) continue
        const rect = frame.getBoundingClientRect()
        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) continue
        // Fixed-layout pages are scaled frames.
        const sx = frame.clientWidth ? rect.width / frame.clientWidth : 1
        const sy = frame.clientHeight ? rect.height / frame.clientHeight : 1
        const found = wordInDoc(doc, (x - rect.left) / sx, (y - rect.top) / sy)
        if (!found) return
        if (overlayer) overlayer.add(LOOKUP_KEY, found.range, Overlayer.highlight, { color: 'rgb(250, 204, 21)' })
        else doc.getSelection()?.addRange(found.range)
        post({ t: 'lookup', word: found.word })
        return
    }
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
    lookupAt,
    clearLookup,
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
