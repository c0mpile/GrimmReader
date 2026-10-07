// GrimmReader ebook host: foliate-js (vendored, see assets/foliate/SOURCE.md) talking to Kotlin through
// the "grimm" WebMessageListener. Only app-local URLs are reachable; the WebView has no network.
import '../foliate/view.js'
import * as CFI from '../foliate/epubcfi.js'

const post = m => grimm.postMessage(JSON.stringify(m))
const params = new URLSearchParams(location.search)
const view = document.createElement('foliate-view')
document.body.append(view)

let ready = false
let lastCfi = null
let visible = null
let bookmarks = []

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
}

try {
    const blob = await (await fetch('/book/current')).blob()
    await view.open(new File([blob], params.get('name') || 'book'))
    const toc = (view.book.toc ?? []).map(i => ({ label: i.label, href: i.href }))
    await view.init({ lastLocation: params.get('cfi') || null })
    // No page-turn animation for the initial positioning; styles apply once the first section is shown.
    window.grimm_api.setAnimated(params.get('animated') === '1')
    if (params.get('css')) window.grimm_api.setStyle(params.get('css'))
    ready = true
    post({ t: 'ready', toc })
    // Report the restored position once init settled.
    const loc = view.lastLocation
    if (loc?.cfi) { lastCfi = null; view.dispatchEvent(new CustomEvent('relocate', { detail: loc })) }
} catch (err) {
    post({ t: 'error', message: String(err) })
}
