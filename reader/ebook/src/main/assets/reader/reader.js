// GrimmReader ebook host: foliate-js (vendored, see assets/foliate/SOURCE.md) talking to Kotlin through
// the "grimm" WebMessageListener. Only app-local URLs are reachable; the WebView has no network.
import '../foliate/view.js'

const post = m => grimm.postMessage(JSON.stringify(m))
const params = new URLSearchParams(location.search)
const view = document.createElement('foliate-view')
document.body.append(view)

let ready = false
let lastCfi = null
view.addEventListener('relocate', e => {
    const d = e.detail
    // foliate emits a transient relocate at the section start before the restored position (Spike a).
    if (!ready || !d.cfi || d.cfi === lastCfi) return
    lastCfi = d.cfi
    post({
        t: 'relocate', cfi: d.cfi,
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
    setAnimated: on => on ? view.renderer.setAttribute('animated', '') : view.renderer.removeAttribute('animated'),
}

try {
    const blob = await (await fetch('/book/current')).blob()
    await view.open(new File([blob], params.get('name') || 'book'))
    window.grimm_api.setAnimated(params.get('animated') === '1')
    if (params.get('css')) window.grimm_api.setStyle(params.get('css'))
    const toc = (view.book.toc ?? []).map(i => ({ label: i.label, href: i.href }))
    await view.init({ lastLocation: params.get('cfi') || null })
    ready = true
    post({ t: 'ready', toc })
    // Report the restored position once init settled.
    const loc = view.lastLocation
    if (loc?.cfi) { lastCfi = null; view.dispatchEvent(new CustomEvent('relocate', { detail: loc })) }
} catch (err) {
    post({ t: 'error', message: String(err) })
}
