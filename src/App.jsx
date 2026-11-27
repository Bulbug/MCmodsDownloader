import { useEffect, useRef, useState } from 'react'
import JSZip from 'jszip'
import { Search, Package, Download, Trash2, Plus, Check, AlertTriangle, ExternalLink, Loader2, Heart, Settings, Info, Sun, Moon, Bookmark, Box, Boxes, Paintbrush, Braces, Glasses, Plug, ChevronLeft, ChevronRight, Upload, Folder, ArrowLeft, ArrowRight, Heart as HeartIcon, Cog, Info as InfoIcon } from 'lucide-react'
import Details from './Details.jsx'
import Sidebar from './Sidebar.jsx'
import { gameVersions, search, resolve, mrpackToMods, j, versionsPath } from './api.js'
import { TYPES, T, typeOf, PLUGIN_LOADERS, PLUGIN_NAMES, CATS } from './types.js'

const LOADERS = ['fabric', 'forge', 'neoforge', 'quilt']
const LOADER_NAMES = { fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge', quilt: 'Quilt' }
const SORTS = [['relevance', 'Relevance'], ['downloads', 'Downloads'], ['follows', 'Follows'], ['updated', 'Recently updated']]
const PAGE_SIZE = 20, MAX_PAGES = 100
const LOADER_META = { fabric: ['https://meta.fabricmc.net/v2/versions/loader', 'fabric-loader'], quilt: ['https://meta.quiltmc.org/v3/versions/loader', 'quilt-loader'] }
const CONFLICTS = [['starlight', 'scalablelux'], ['starlight', 'phosphor'], ['scalablelux', 'phosphor'], ['lithium', 'canary'], ['sodium', 'embeddium'], ['sodium', 'rubidium'], ['embeddium', 'rubidium'], ['iris', 'oculus']]
const PERF = { fabric: ['sodium', 'lithium', 'ferrite-core', 'modernfix', 'entityculling', 'immediatelyfast'], forge: ['embeddium', 'modernfix', 'ferrite-core', 'entityculling', 'immediatelyfast'] }
PERF.quilt = PERF.fabric; PERF.neoforge = PERF.forge
const DL_HOSTS = ['cdn.modrinth.com', 'github.com', 'raw.githubusercontent.com', 'gitlab.com']

const okPath = (p) => typeof p === 'string' && !!p && !p.startsWith('/') && !/^[A-Za-z]:/.test(p) && !p.split(/[\\/]/).includes('..')
const pageList = (p, n) => {
  const s = new Set([1, n, p - 1, p, p + 1, p === 1 ? 3 : 0, p === n ? n - 2 : 0])
  const nums = [...s].filter((x) => x >= 1 && x <= n).sort((x, y) => x - y)
  return nums.flatMap((x, i) => (i && x - nums[i - 1] > 1 ? (x - nums[i - 1] === 2 ? [x - 1, x] : ['…', x]) : [x]))
}
const TYPE_ICONS = { mod: <Box size={16} />, resourcepack: <Paintbrush size={16} />, datapack: <Braces size={16} />, shader: <Glasses size={16} />, modpack: <Boxes size={16} />, plugin: <Plug size={16} /> }
const NAV = [['convert', 'Convert .mrpack', Upload], ['packs', 'Saved modpacks', Bookmark], ['favorites', 'Favorites', Heart], ['settings', 'Settings', Settings], ['about', 'About', Info]]
const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('')
const safe = (n) => n.replace(/[^A-Za-z0-9._+-]/g, '_').slice(0, 120)
const fmt = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : n)
const toB64 = (o) => btoa(unescape(encodeURIComponent(JSON.stringify(o)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const fromB64 = (s) => JSON.parse(decodeURIComponent(escape(atob(s.replace(/-/g, '+').replace(/_/g, '/')))))

function validPack(d) {
  if (!d || typeof d.v !== 'string' || !LOADERS.includes(d.l) || !Array.isArray(d.m)) throw new Error('Not a valid pack')
  const m = d.m.filter((x) => x && /^[A-Za-z0-9]{6,12}$/.test(x.id)).slice(0, 100).map((x) => ({ id: x.id, title: String(x.title || x.id).slice(0, 80), type: typeOf(x.type) }))
  if (!m.length) throw new Error('The pack contains no mods')
  return { v: d.v.slice(0, 20), l: d.l, p: PLUGIN_LOADERS[d.p] ? d.p : undefined, m }
}

export default function App() {
  const [versions, setVersions] = useState([])
  const [version, setVersion] = useState('')
  const [loader, setLoader] = useState('fabric')
  const [ploader, setPloader] = useState('paper')
  const [type, setType] = useState('mod')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState('downloads')
  const [hits, setHits] = useState([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [category, setCategory] = useState('')
  const [conv, setConv] = useState(null)
  const [convSide, setConvSide] = useState('client')
  const [over, setOver] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [tick, setTick] = useState(0)
  const [selected, setSelected] = useState([])
  const [plan, setPlan] = useState([])
  const [planBusy, setPlanBusy] = useState(false)
  const [dl, setDl] = useState(null)
  const [panel, setPanel] = useState(false)
  const [view, setView] = useState('browse')
  const [favs, setFavs] = useState([])
  const [packs, setPacks] = useState([])
  const [detail, setDetail] = useState(null)
  const [packName, setPackName] = useState('')
  const [note, setNote] = useState('')
  const [updates, setUpdates] = useState({})
  const [pending, setPending] = useState({})
  const [perfBusy, setPerfBusy] = useState(false)
  const [theme, setTheme] = useState('dark')
  const [launcherFolder, setLauncherFolder] = useState('')
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)

  const abort = useRef(null)
  const cancel = useRef(null)
  const lastZip = useRef(null)
  const searchRef = useRef(null)
  const convAbort = useRef(null)
  const [searchHist, setSearchHist] = useState([])
const [toasts, setToasts] = useState([])

  useEffect(() => {
    async function init() {
      if (window.app) {
        const config = await window.app.read()
        setVersion(config.version || '')
        setLoader(config.loader || 'fabric')
        setPloader(config.ploader || 'paper')
        setType(config.type || 'mod')
        setTheme(config.theme || 'dark')
        setLauncherFolder(config.launcherFolder || '')

        const savedPacks = await window.app.readPacks()
        setPacks(Array.isArray(savedPacks) ? savedPacks : [])

        const savedFavs = await window.app.readFavs()
        setFavs(Array.isArray(savedFavs) ? savedFavs : [])
      }
    }
    init()
    gameVersions().then((v) => { setVersions(v); setVersion((c) => c || v[0]) }).catch((e) => setError(e.message))
  }, [tick])

  useEffect(() => {
    if (window.app) window.app.write({ version, loader, ploader, type, theme, launcherFolder })
    document.documentElement.dataset.theme = theme
  }, [version, loader, ploader, type, theme, launcherFolder])

  useEffect(() => { if (window.app) window.app.writePacks(packs) }, [packs])
  useEffect(() => { if (window.app) window.app.writeFavs(favs) }, [favs])

  const isPlugin = type === 'plugin'
  const curLoader = isPlugin ? ploader : loader
  const loaderLabel = isPlugin ? PLUGIN_NAMES[ploader] : LOADER_NAMES[loader]

  const runSearch = (offset) => {
    if (!version || !query.trim()) return
    abort.current?.abort()
    const ac = (abort.current = new AbortController())
    setBusy(true); setError('')
    search({ query, version, loader: curLoader, sort, offset, type, category }, ac.signal)
      .then((r) => {
        setHits(r.hits); setTotal(r.total_hits)
        if (query.trim() && !searchHist.some((s) => s.query === query.trim())) {
          setSearchHist((h) => [{ query: query.trim(), version, loader: curLoader, type }.concat(h).slice(0, 8)])
        }
      })
      .catch((e) => e.name !== 'AbortError' && setError(e.message))
      .finally(() => !ac.signal.aborted && setBusy(false))
  }

  useEffect(() => { setHits([]); setTotal(0); if (version) setBusy(true) }, [type])

  const fkey = [query, version, curLoader, sort, tick, type, category].join('|')
  const lastKey = useRef(fkey)
  useEffect(() => {
    const changed = lastKey.current !== fkey
    lastKey.current = fkey
    if (changed && page !== 1) { setPage(1); return }
    const t = setTimeout(() => runSearch((page - 1) * PAGE_SIZE), changed ? 350 : 0)
    return () => clearTimeout(t)
  }, [fkey, page])

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') setPanel(false)
      else if (e.key === '/' && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName)) { e.preventDefault(); searchRef.current?.focus() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const pages = Math.min(Math.ceil(total / PAGE_SIZE), MAX_PAGES)
  const goPage = (n) => { if (n < 1 || n > pages || n === page) return; setHits([]); setPage(n); window.scrollTo({ top: 0, behavior: 'smooth' }) }

  useEffect(() => {
    if (!version || !selected.length) { setPlan([]); return }
    let dead = false
    setPlanBusy(true)
    resolve(selected, version, { loader, ploader }).then((p) => !dead && setPlan(p)).catch((e) => !dead && setError(e.message)).finally(() => !dead && setPlanBusy(false))
    return () => { dead = true }
  }, [selected, version, loader, ploader])

  const toggle = (h) => setSelected((s) => (s.some((x) => x.id === h.project_id) ? s.filter((x) => x.id !== h.project_id) : [...s, { id: h.project_id, title: h.title, type: typeOf(type) }]))
  const toggleLog = (h) => {
    const on = selected.some((x) => x.id === h.project_id)
    toggle(h)
    toast(on ? 'Removed from pack' : 'Added to pack', on ? 'info' : 'success', 2500)
  }
  const broken = plan.filter((p) => p.error)
  const ready = plan.filter((p) => p.file)
  const canExport = ready.length > 0 && !broken.length && !planBusy && !dl?.running

  const toggleFav = (f) => setFavs((a) => (a.some((x) => x.id === f.id) ? a.filter((x) => x.id !== f.id) : [...a, f]))
  const addRemove = (id, title, t) => setSelected((s) => (s.some((x) => x.id === id) ? s.filter((x) => x.id !== id) : [...s, { id, title, type: typeOf(t) }]))

  const savePack = () => {
    const p = {
      id: crypto.randomUUID(),
      name: packName.trim() || `Pack ${packs.length + 1}`,
      version, loader, ploader,
      mods: selected,
      saved: Date.now(),
      vers: Object.fromEntries(ready.map((x) => [x.id, { vid: x.version.id, num: x.version.version_number, title: x.title }]))
    }
    setPacks((ps) => [...ps, p])
    setPackName('')
    toast(`Pack "${p.name}" saved`, 'success', 3000)
  }

  const openPack = (p) => {
    if (selected.length && !confirm('Replace your current selection with this pack?')) return
    setVersion(p.version); setLoader(LOADERS.includes(p.loader) ? p.loader : 'fabric'); setPloader(PLUGIN_LOADERS[p.ploader] ? p.ploader : 'paper'); setSelected(p.mods.filter((m) => m && m.id)); setView('browse')
  }

  const renamePack = (p) => { const n = prompt('Rename pack', p.name)?.trim(); if (n) setPacks((a) => a.map((x) => (x.id === p.id ? { ...x, name: n } : x))) }
  const dupPack = (p) => setPacks((a) => [...a, { ...p, id: crypto.randomUUID(), name: p.name + ' (copy)' }])
  const delPack = (p) => confirm(`Delete "${p.name}"?`) && setPacks((a) => a.filter((x) => x.id !== p.id))

  const toast = (msg, type = 'info', dur = 4000) => {
    const id = Date.now()
    setToasts((t) => [...t, { id, msg, type, dur }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), dur)
  }

  const retryFailed = () => {
    if (!dl?.failed?.length || dl?.running) return
    setSelected((s) => [...s, ...dl.failed.map((f) => ({ id: f.id, title: f.title, type: f.type }))])
    setDl(null)
  }

  const addPerfMods = async () => {
    if (perfBusy) return
    setPerfBusy(true)
    try {
      const perf = PERF[curLoader] || []
      const existing = new Set(selected.map((s) => s.id))
      const toAdd = perf.filter((id) => !existing.has(id))
      if (!toAdd.length) {
        setNote('Performance mods already added!')
        setPerfBusy(false)
        return
      }
      setSelected((s) => [...s, ...toAdd.map((id) => ({ id, title: id, type: typeOf(type) }))])
      toast(`Added ${toAdd.length} performance mod(s)`, 'success', 3000)
    } catch (e) {
      setError(e.message)
    } finally {
      setPerfBusy(false)
    }
  }

  const resetAll = async () => {
    if (!confirm('Erase saved packs, favorites and selection?')) return
    if (window.app) {
      await window.app.write({ version: '', launcherFolder: '', theme: 'dark', loader: 'fabric', ploader: 'paper' })
      await window.app.writePacks([])
      await window.app.writeFavs([])
    }
    setPacks([]); setFavs([]); setSelected([])
    setVersion(''); setLoader('fabric'); setType('mod'); setTheme('dark'); setLauncherFolder('')
  }

  const selectLauncherFolder = async () => {
    if (!window.app) return
    const res = await window.app.showOpenDialog({
      title: 'Select Legacy Launcher Game Folder',
      properties: ['openDirectory']
    })
    if (!res.canceled && res.filePaths[0]) {
      setLauncherFolder(res.filePaths[0])
    }
  }

  // Install directly into the Legacy Launcher game folder
  async function installToLauncher() {
    if (!launcherFolder) {
      alert('Please set your Legacy Launcher folder in Settings first!')
      setView('settings')
      return
    }

    const ac = (cancel.current = new AbortController())
    const list = ready
    setDl({ running: true, done: 0, total: list.length, current: 'Installing directly to launcher…', failed: [] })

    let installedCount = 0
    const failed = []

    for (const [i, p] of list.entries()) {
      if (ac.signal.aborted) break
      const subDir = T[typeOf(p.type)].dir
      const targetDir = `${launcherFolder}/${subDir}`
      const targetPath = `${targetDir}/${safe(p.file.filename)}`

      setDl((d) => ({ ...d, current: p.file.filename, done: i }))

      try {
        const r = await fetch(p.file.url, { signal: ac.signal })
        if (!r.ok) throw new Error('HTTP ' + r.status)
        const buf = await r.arrayBuffer()

        // Write file directly via Electron IPC
        await window.app.fsMkdir(targetDir)
        await window.app.fsWriteFile(targetPath, Buffer.from(buf))
        installedCount++
      } catch (err) {
        if (ac.signal.aborted) break
        failed.push(p)
      }
    }

    if (ac.signal.aborted) {
      setDl({ running: false, cancelled: true, done: installedCount, total: list.length, failed })
      return
    }

    setDl({
      running: false,
      done: installedCount,
      total: list.length,
      note: `Successfully installed ${installedCount} files directly to ${launcherFolder}!`,
      failed
    })
  }

  async function exportZip(only) {
    const ac = (cancel.current = new AbortController())
    const snap = only && lastZip.current ? lastZip.current : { zip: new JSZip(), files: [], version, loader, ploader }
    const { zip, files } = snap
    const failed = []
    const list = only || ready
    const before = files.length
    setDl({ running: true, done: before, total: before + list.length, current: '', failed: [] })
    for (const [i, p] of list.entries()) {
      if (ac.signal.aborted) break
      setDl((d) => ({ ...d, current: p.file.filename, done: before + i }))
      try {
        const r = await fetch(p.file.url, { signal: ac.signal })
        if (!r.ok) throw new Error('HTTP ' + r.status)
        const buf = await r.arrayBuffer()
        const want = p.file.hashes?.sha512
        if (want && hex(await crypto.subtle.digest('SHA-512', buf)) !== want) throw new Error('Hash mismatch')
        const name = safe(p.file.filename)
        const dir = T[typeOf(p.type)].dir
        zip.file(dir + '/' + name, buf)
        files.push({ project: p.title, projectId: p.id, type: typeOf(p.type), versionId: p.version.id, url: `https://modrinth.com/${typeOf(p.type)}/${p.slug}`, file: dir + '/' + name, verified: !!want, reason: p.root ? 'selected' : 'required by ' + [...p.requiredBy].join(', ') })
      } catch (err) { if (ac.signal.aborted) break; failed.push(p) }
    }
    if (ac.signal.aborted) { setDl({ running: false, cancelled: true, done: files.length, total: before + list.length, failed: only || [] }); return }
    setDl((d) => ({ ...d, stage: 'Building ZIP…' }))
    zip.file('manifest.json', JSON.stringify({ app: 'DIV Modpack Builder', exported: new Date().toISOString(), minecraft: snap.version, loader: snap.loader, ...(files.some((f) => f.type === 'plugin') ? { pluginPlatform: snap.ploader } : {}), mods: files, failed: failed.map((p) => p.title) }, null, 2))

    // Save blob
    const blob = await zip.generateAsync({ type: 'blob' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = `div-modpack-${snap.version}-${snap.loader}.zip`; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 10000)

    lastZip.current = snap
    setDl({ running: false, done: files.length, total: files.length + failed.length, failed, retried: !!only })
  }

  const counts = { selection: selected.length, packs: packs.length, favorites: favs.length }

  return (
    <div className="app">
      <header className="app-header">
        <div className="header-left">
          <div className="brand" title="DIV Modpack Builder"><svg className="logo" viewBox="0 0 32 32" aria-hidden="true"><path d="M16 3 28 9.5 16 16 4 9.5z" fill="var(--copper)"/><path d="M16 16v13L4 22.5v-13z" fill="var(--teal)" opacity=".85"/><path d="M16 16v13l12-6.5v-13z" fill="var(--teal)" opacity=".5"/></svg><span><b>DIV</b></span></div>
        </div>
        <div className="header-center">
          <label className="search"><Search size={18} /><input ref={searchRef} placeholder={`Search ${T[type].label.toLowerCase()} for Minecraft ${version}`} aria-label="Search" value={query} onChange={(e) => { setQuery(e.target.value); if (view !== 'browse') setView('browse') }} /><kbd aria-hidden="true">/</kbd></label>
        </div>
        <div className="header-right">
          <button className="icon" aria-label="Toggle theme" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}</button>
        </div>
      </header>
      <div className="app-body">
        <Sidebar
          view={view}
          setView={setView}
          type={type}
          setType={setType}
          collapsed={sidebarCollapsed}
          setCollapsed={setSidebarCollapsed}
          counts={counts}
          isPlugin={isPlugin}
          curLoader={curLoader}
          setLoader={setLoader}
          setPloader={setPloader}
        />
        <main className="app-main">
          <div className="content-header">
            <div className="filters">
              <div className="cats" role="group" aria-label="Category">
                {['', ...CATS[type]].map((c) => <button key={c || 'all'} className={'cat' + (category === c ? ' active' : '')} aria-pressed={category === c} onClick={() => setCategory(c)}>{c ? c.replace(/-/g, ' ') : 'All'}</button>)}
              </div>
              <span className="count" aria-live="polite">{total ? `${total.toLocaleString()} results` : ''}</span>
              <select value={sort} onChange={(e) => setSort(e.target.value)} aria-label="Sort">{SORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>
            </div>
            {isPlugin && <div className="note">Showing plugins that run on {PLUGIN_NAMES[ploader]}, including {PLUGIN_LOADERS[ploader].slice(1).join(' and ') || 'its own'} plugins. Downloads go in a <code>plugins/</code> folder.</div>}
          </div>
        <section className="browse" hidden={view !== 'browse'}>
          <h1 className="sr">{T[type].label} for Minecraft {version}{T[type].loader ? ` on ${loaderLabel}` : ''}</h1>
          <div className="filters">
            <div className="cats" role="group" aria-label="Category">
              {['', ...CATS[type]].map((c) => <button key={c || 'all'} className={'cat' + (category === c ? ' active' : '')} aria-pressed={category === c} onClick={() => setCategory(c)}>{c ? c.replace(/-/g, ' ') : 'All'}</button>)}
            </div>
            <span className="count" aria-live="polite">{total ? `${total.toLocaleString()} results` : ''}</span>
            <select value={sort} onChange={(e) => setSort(e.target.value)} aria-label="Sort">{SORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>
          </div>
          {isPlugin && <div className="note">Showing plugins that run on {PLUGIN_NAMES[ploader]}, including {PLUGIN_LOADERS[ploader].slice(1).join(' and ') || 'its own'} plugins. Downloads go in a <code>plugins/</code> folder.</div>}
          {error && <div className="err"><AlertTriangle size={16} /> {error} <button onClick={() => setTick((t) => t + 1)}>Retry</button></div>}
          {!busy && !error && !hits.length && <p className="empty">No {T[type].label.toLowerCase()} found for Minecraft {version}{T[type].loader ? ` on ${loaderLabel}` : ''}. Try another search{T[type].loader ? ', version or loader' : ' or version'}.</p>}
          <div className="grid">
            {busy && !hits.length && Array.from({ length: 6 }, (_, i) => <div key={i} className="card sk" aria-hidden="true" />)}
            {hits.map((h) => {
              const on = selected.some((x) => x.id === h.project_id)
              return (
                <article key={h.project_id} className={'card' + (on ? ' picked' : '')}>
                  {type !== 'mod' && h.featured_gallery && <img className="shot" src={h.featured_gallery} alt="" loading="lazy" />}
                  {h.icon_url ? <img src={h.icon_url} alt="" width="56" height="56" loading="lazy" /> : <div className="ph" aria-hidden="true"><Package size={22} /></div>}
                  <div className="body">
                    <h3>{h.title}</h3>
                    <span className="by">by {h.author}</span>
                    <p>{h.description}</p>
                    <div className="meta">
                      <span className="stat" title="Downloads"><Download size={12} /> {fmt(h.downloads)}</span>
                      <span className="stat" title="Follows"><Heart size={12} /> {fmt(h.follows || 0)}</span>
                      {h.display_categories?.slice(0, 3).map((c) => <span key={c} className="chip">{c}</span>)}
                    </div>
                    <div className="row">
                      <button onClick={() => setDetail({ id: h.project_id, title: h.title, type })}>Details</button>
                      <button className={'icon' + (favs.some((f) => f.id === h.project_id) ? ' on' : '')} aria-label={`Favorite ${h.title}`} onClick={() => toggleFav({ id: h.project_id, title: h.title, icon: h.icon_url, slug: h.slug, type })}><Heart size={14} /></button>
                    </div>
                  </div>
                  <button className={'addbtn' + (on ? ' on' : '')} aria-pressed={on} aria-label={`Add ${h.title} to pack`} onClick={() => toggleLog(h)}>{on ? <Check size={18} /> : <Plus size={20} />}</button>
                </article>
              )
            })}
          </div>
          {busy && <p className="empty"><Loader2 className="spin" size={16} /> Loading…</p>}
          {pages > 1 && (
            <nav className="pager" aria-label="Pagination">
              <button aria-label="Previous page" disabled={page === 1 || busy} onClick={() => goPage(page - 1)}><ChevronLeft size={18} /></button>
              {pageList(page, pages).map((n, i) => n === '…' ? <span key={'e' + i} className="gap" aria-hidden="true">…</span> : <button key={n} className={n === page ? 'active' : ''} aria-label={`Page ${n}`} aria-current={n === page ? 'page' : undefined} disabled={busy} onClick={() => goPage(n)}>{n}</button>)}
              <button aria-label="Next page" disabled={page === pages || busy} onClick={() => goPage(page + 1)}><ChevronRight size={18} /></button>
            </nav>
          )}
        </section>
        {view === 'browse' && (
            <section className="browse" aria-labelledby="results-heading">
              <h1 id="results-heading" className="sr">{T[type].label} for Minecraft {version}{T[type].loader ? ` on ${loaderLabel}` : ''}</h1>
              {error && <div className="err"><AlertTriangle size={16} /> {error} <button onClick={() => setTick((t) => t + 1)}>Retry</button></div>}
              {!busy && !error && !hits.length && <p className="empty">No {T[type].label.toLowerCase()} found for Minecraft {version}{T[type].loader ? ` on ${loaderLabel}` : ''}. Try another search{T[type].loader ? ', version or loader' : ' or version'}.</p>}
              <div className="grid" role="list">
                {busy && !hits.length && Array.from({ length: 6 }, (_, i) => <div key={i} className="card sk" aria-hidden="true" role="listitem" />)}
                {hits.map((h) => {
                  const on = selected.some((x) => x.id === h.project_id)
                  return (
                    <article key={h.project_id} className={'card' + (on ? ' picked' : '')} role="listitem">
                      {type !== 'mod' && h.featured_gallery && <img className="shot" src={h.featured_gallery} alt="" loading="lazy" />}
                      {h.icon_url ? <img src={h.icon_url} alt="" width="56" height="56" loading="lazy" /> : <div className="ph" aria-hidden="true"><Package size={22} /></div>}
                      <div className="body">
                        <h3>{h.title}</h3>
                        <span className="by">by {h.author}</span>
                        <p>{h.description}</p>
                        <div className="meta">
                          <span className="stat" title="Downloads"><Download size={12} /> {fmt(h.downloads)}</span>
                          <span className="stat" title="Follows"><Heart size={12} /> {fmt(h.follows || 0)}</span>
                          {h.display_categories?.slice(0, 3).map((c) => <span key={c} className="chip">{c}</span>)}
                        </div>
                        <div className="row">
                          <button onClick={() => setDetail({ id: h.project_id, title: h.title, type })}>Details</button>
                          <button className={'icon' + (favs.some((f) => f.id === h.project_id) ? ' on' : '')} aria-label={`Favorite ${h.title}`} onClick={() => toggleFav({ id: h.project_id, title: h.title, icon: h.icon_url, slug: h.slug, type })}><Heart size={14} /></button>
                        </div>
                      </div>
                      <button className={'addbtn' + (on ? ' on' : '')} aria-pressed={on} aria-label={`Add ${h.title} to pack`} onClick={() => toggleLog(h)}>{on ? <Check size={18} /> : <Plus size={20} />}</button>
                    </article>
                  )
                })}
              </div>
              {busy && <p className="empty"><Loader2 className="spin" size={16} /> Loading…</p>}
              {pages > 1 && (
                <nav className="pager" aria-label="Pagination">
                  <button aria-label="Previous page" disabled={page === 1 || busy} onClick={() => goPage(page - 1)}><ChevronLeft size={18} /></button>
                  {pageList(page, pages).map((n, i) => n === '…' ? <span key={'e' + i} className="gap" aria-hidden="true">…</span> : <button key={n} className={n === page ? 'active' : ''} aria-label={`Page ${n}`} aria-current={n === page ? 'page' : undefined} disabled={busy} onClick={() => goPage(n)}>{n}</button>)}
                  <button aria-label="Next page" disabled={page === pages || busy} onClick={() => goPage(page + 1)}><ChevronRight size={18} /></button>
                </nav>
              )}
            </section>
          )}
          {view === 'favorites' && (
            <section className="browse">
              <h2>Favorites ({favs.length})</h2>
              {!favs.length && <p className="empty">Tap the heart on any mod to keep it here.</p>}
              <div className="grid">
                {favs.map((f) => {
                  const on = selected.some((x) => x.id === f.id)
                  return (
                    <article key={f.id} className={'card' + (on ? ' picked' : '')}>
                      {f.icon ? <img src={f.icon} alt="" width="56" height="56" loading="lazy" /> : <div className="ph" aria-hidden="true"><Package size={22} /></div>}
                      <div className="body"><h3>{f.title}</h3>
                        <div className="row">
                          <button onClick={() => setDetail({ id: f.id, title: f.title, type: f.type })}>Details</button>
                          <button className="icon" aria-label={`Remove ${f.title} from favorites`} onClick={() => toggleFav(f)}><Trash2 size={14} /></button>
                        </div></div>
                      <button className={'addbtn' + (on ? ' on' : '')} aria-pressed={on} aria-label={`Add ${f.title} to pack`} onClick={() => addRemove(f.id, f.title, f.type)}>{on ? <Check size={18} /> : <Plus size={20} />}</button>
                    </article>
                  )
                })}
              </div>
            </section>
          )}
          {view === 'packs' && (
            <section className="browse">
              <h2>Saved modpacks</h2>
              <div className="bar">
                <input style={{ flex: 1 }} placeholder="Name this pack" value={packName} onChange={(e) => setPackName(e.target.value)} />
                <button className="primary" disabled={!selected.length} onClick={savePack}>Save current selection</button>
              </div>
              {!packs.length && <p className="empty">No saved packs yet. Pick some mods, name the pack and save it.</p>}
              <ul>
                {packs.map((p) => (
                  <li key={p.id}>
                    <div><b>{p.name}</b><small>{p.mods.length} mods · Minecraft {p.version} · {p.loader}</small>{updates[p.id] && <small>{updates[p.id]}</small>}</div>
                    <div className="row">
                      <button onClick={() => checkUpdates(p)}>Check updates</button>{pending[p.id] && <button className="primary" onClick={() => updatePack(p)}>Update pack</button>}<button onClick={() => openPack(p)}>Open</button><button onClick={() => renamePack(p)}>Rename</button>
                      <button onClick={() => dupPack(p)}>Duplicate</button><button className="icon" aria-label="Delete pack" onClick={() => delPack(p)}><Trash2 size={14} /></button>
                    </div>
                  </li>
                ))}
              </ul>
              <label className="filebtn">Import .mrpack or manifest.json<input type="file" accept=".mrpack,.json" hidden onChange={importFile} /></label>
              <button className="ghost wide" onClick={resetAll}>Reset all local data</button>
            </section>
          )}
          {view === 'settings' && (
            <section className="browse">
              <h2>Settings</h2>
              <h3>Legacy Launcher Folder</h3>
              <p>Set the path to your Legacy Launcher game directory to install mods directly into your game files.</p>
              <div className="bar" style={{ marginBottom: '20px' }}>
                <input style={{ flex: 1 }} readOnly value={launcherFolder || 'No folder selected'} placeholder="Select folder..." />
                <button className="primary" onClick={selectLauncherFolder}><Folder size={16} /> Choose Folder</button>
              </div>

              <h3>Theme</h3>
              <div className="row" style={{ marginBottom: '20px' }}>
                {['dark', 'light'].map((m) => <button key={m} className={theme === m ? 'on' : ''} onClick={() => setTheme(m)}>{m === 'dark' ? 'Dark' : 'Light'}</button>)}
              </div>

              <p>Your selection, favorites, saved packs and theme are stored in this app.</p>
              <button className="ghost wide" onClick={resetAll}>Reset all local data</button>
            </section>
          )}
          {view === 'convert' && (
            <section className="browse convert">
              <h2>Convert .mrpack to ZIP</h2>
              <p className="sub">Choose a Modrinth modpack (.mrpack) and it converts right away. Every file is downloaded and hash-checked, then you get one ZIP with the mods and config files in place.</p>
              <div className="seg" role="group" aria-label="Pack type">
                {[['client', 'Client pack'], ['server', 'Server pack']].map(([v, l]) => <button key={v} className={convSide === v ? 'active' : ''} aria-pressed={convSide === v} disabled={conv?.running} onClick={() => setConvSide(v)}>{l}</button>)}
              </div>
              <label className={'drop' + (over ? ' over' : '')} onDragOver={(e) => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)} onDrop={(e) => { e.preventDefault(); setOver(false); if (!conv?.running) convertMrpack(e.dataTransfer.files[0]) }}>
                <Upload size={28} aria-hidden="true" />
                <b>Drop your .mrpack here</b>
                <span>or click to choose a file</span>
                <input type="file" accept=".mrpack" disabled={conv?.running} onChange={(e) => { const f = e.target.files[0]; e.target.value = ''; convertMrpack(f) }} />
              </label>
              {conv?.running && (
                <div className="status" role="status">
                  <div>{conv.stage}{conv.total ? ` (${conv.done}/${conv.total})` : ''}</div>
                  {conv.current && <small>{conv.current}</small>}
                  <progress value={conv.done} max={conv.total || 1} aria-label="Conversion progress" />
                  <button onClick={() => convAbort.current?.abort()}>Cancel</button>
                </div>
              )}
              {conv?.error && <div className="err" role="alert">{conv.error}</div>}
              {conv?.cancelled && <div className="status">Conversion cancelled.</div>}
              {conv && !conv.running && !conv.error && !conv.cancelled && (
                <div className={'status' + (conv.failed.length ? ' warn' : ' ok')} role="status">
                  <b>Done: {conv.pack || conv.name}{conv.mc ? ` (Minecraft ${conv.mc})` : ''}</b>
                  <div>{conv.done} of {conv.total} files downloaded, plus {conv.extra} config file(s). The ZIP was saved to your downloads.</div>
                  {conv.skipped > 0 && <small>{conv.skipped} file(s) were left out (not for a {convSide} pack, or an unsafe path).</small>}
                  {conv.failed.length > 0 && <div>Could not download {conv.failed.length}: {conv.failed.map((p) => p.split('/').pop()).join(', ')}</div>}
                </div>
              )}
            </section>
          )}
          {view === 'about' && (
            <section className="browse">
              <h2>About DIV Modpack Builder</h2>
              <p>Search Modrinth for mods, modpacks, resource packs, shaders and data packs. Pick a Minecraft version and loader, and install directly to your Legacy Launcher folder or download as a ZIP/mrpack.</p>
              <p>All mod data and files come from the Modrinth API. This is an unofficial tool and is not affiliated with Modrinth, Mojang or Microsoft.</p>
              <p>Dependency resolution and the known-conflict warnings reduce problems but cannot guarantee a crash-free pack.</p>
            </section>
          )}
        </main>
        <aside className="pack-panel" id="pack-panel" aria-label="My pack">
          <div className="ahead"><h2>My pack</h2><span className="badge big">{selected.length}</span><button className="icon" aria-label="Close pack" onClick={() => setPanel(false)}>✕</button></div>
          <button className="ghost wide" disabled={perfBusy} onClick={addPerfMods}>Add performance mods</button>
          {planBusy && <p><Loader2 className="spin" size={14} /> Resolving dependencies…</p>}
          {TYPES.map((t) => {
            const items = plan.filter((p) => p.type === t.id)
            if (!items.length) return null
            return (
              <div key={t.id} className="grp">
                <h4>{t.label} <i className="badge">{items.length}</i></h4>
                <ul>
                  {items.map((p) => (
                    <li key={p.id} className={p.error ? 'bad' : p.root ? '' : 'dep'}>
                      <div><b>{p.title}</b>
                        <small>{p.error ? p.error : p.root ? p.file?.filename : 'Dependency of ' + [...p.requiredBy].join(', ')}</small></div>
                      {p.root && <button className="icon" aria-label={`Remove ${p.title}`} onClick={() => setSelected((s) => s.filter((x) => x.id !== p.id))}><Trash2 size={14} /></button>}
                    </li>
                  ))}
                </ul>
              </div>
            )
          })}
          {!selected.length && <p className="empty">Add mods to start your pack.</p>}
          <div className={'status ' + (broken.length ? 'bad' : ready.length ? 'ok' : '')}>
            {broken.length ? `Incompatible: ${broken.length} mod(s) have no release for ${version} on ${loader}. Remove them or change version/loader.` : ready.length ? 'All required dependencies resolved from Modrinth.' : 'Nothing to check yet.'}
          </div>
          <p className="small">{ready.length} files · {(ready.reduce((n, p) => n + (p.file.size || 0), 0) / 1048576).toFixed(1)} MB</p>
          {dl && (
            <div className="dl">
              <progress value={dl.done} max={dl.total || 1} />
              <small>{dl.running ? `${dl.stage || dl.current} (${dl.done}/${dl.total})` : dl.note || `${dl.cancelled ? 'Cancelled. ' : ''}${dl.done}/${dl.total} downloaded`}</small>
              {dl.failed?.length && !dl.running && <button className="ghost wide" onClick={retryFailed}>Retry {dl.failed.length} failed</button>}
            </div>
          )}
          {dl?.running ? (
            <button className="wide" onClick={() => cancel.current?.abort()}>Cancel download</button>
          ) : (
            <>
              {launcherFolder && <button className="primary wide" disabled={!canExport} onClick={installToLauncher} style={{ marginBottom: '8px' }}>Install directly to Launcher</button>}
              <button className="wide" disabled={!canExport} onClick={() => exportZip()}>Download ZIP</button>
            </>
          )}
          {!!selected.length && <button className="ghost wide" onClick={() => confirm('Clear all selected mods?') && setSelected([])}>Clear selection</button>}
          {!!selected.length && <button className="ghost wide" onClick={copyLink}>Copy share link</button>}
          {note && <p className="small" role="status">{note}</p>}
        </aside>
      </div>
      {(selected.length > 0 || dl) && (
        <div className="dock" role="region" aria-label="Your pack">
          <div className="stack" aria-hidden="true">
            {plan.slice(0, 5).map((p) => p.icon ? <img key={p.id} src={p.icon} alt="" width="36" height="36" /> : <span key={p.id} className="ph" />)}
            {plan.length > 5 && <span className="more">+{plan.length - 5}</span>}
          </div>
          <div className="dtxt">
            <b>{ready.length} files · {(ready.reduce((n, p) => n + (p.file.size || 0), 0) / 1048576).toFixed(1)} MB</b>
            <small className={broken.length ? 'bad' : ''}>{dl?.running ? `${dl.stage || dl.current} (${dl.done}/${dl.total})` : broken.length ? `${broken.length} incompatible` : planBusy ? 'Resolving dependencies…' : ready.length ? 'Dependencies resolved' : 'Checking…'}</small>
          </div>
          <button className="ghost" aria-expanded={panel} aria-controls="pack-panel" onClick={() => setPanel(!panel)}>{panel ? 'Hide pack' : 'View pack'}</button>
          {dl?.running
            ? <button onClick={() => cancel.current?.abort()}>Cancel</button>
            : (
              <>
                {launcherFolder ? (
                  <button className="primary" disabled={!canExport} onClick={installToLauncher}><Download size={16} /> Install to Launcher</button>
                ) : (
                  <button className="primary" disabled={!canExport} onClick={() => exportZip()}><Download size={16} /> Download ZIP</button>
                )}
              </>
            )}
          {dl?.running && <progress className="dprog" value={dl.done} max={dl.total || 1} aria-label="Download progress" />}
        </div>
      )}
      {detail && <Details item={detail} version={version} loader={typeOf(detail.type) === 'plugin' ? ploader : loader} onClose={() => setDetail(null)} onBase={(base) => { setNote('Modpack base imported.'); setVersion(detail.version); setDetail(null); }} />}
      <div className="toast-container" style={{ position: 'fixed', bottom: '100px', right: '20px', zIndex: 1000, display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.type}`} style={{ padding: '10px 16px', borderRadius: '10px', background: t.type === 'error' ? 'var(--red)' : t.type === 'success' ? 'var(--teal)' : 'var(--panel)', color: t.type === 'error' || t.type === 'success' ? '#fff' : 'var(--text)', border: '1px solid var(--line)', boxShadow: 'var(--shadow)', animation: 'slideIn .2s ease both' }}>
            {t.msg}
          </div>
        ))}
      </div>
    </div>
  )
}
