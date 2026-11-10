import { T, typeOf, loaderSet } from './types.js'
const BASE = 'https://api.modrinth.com/v2'
const cache = new Map()
const enc = (a) => encodeURIComponent(JSON.stringify(a))
export async function j(path, signal) {
  if (cache.has(path)) return cache.get(path)
  const r = await fetch(BASE + path, { signal })
  if (r.status === 429) throw new Error('Modrinth rate limit reached. Wait a moment and retry.')
  if (!r.ok) throw new Error(`Modrinth returned ${r.status} for ${path}`)
  const data = await r.json()
  cache.set(path, data)
  return data
}
export const gameVersions = async () =>
  (await j('/tag/game_version')).filter((v) => v.version_type === 'release').map((v) => v.version)
// Only mods and modpacks are filtered by loader; resource packs, shaders and data packs are not.
export const versionsPath = (id, gv, type, loader) =>
  `/project/${id}/version?game_versions=${enc([gv])}${T[typeOf(type)].loader ? `&loaders=${enc(loaderSet(typeOf(type), loader))}` : ''}`
export function search({ query, version, loader, sort, offset, type, category }, signal) {
  const t = typeOf(type)
  const facets = [[`project_type:${T[t].ptype || t}`], [`versions:${version}`]]
  if (T[t].loader) facets.push(loaderSet(t, loader).map((c) => `categories:${c}`))
  if (category) facets.push([`categories:${category}`])
  return j(`/search?query=${encodeURIComponent(query)}&facets=${enc(facets)}&index=${sort}&offset=${offset}&limit=20`, signal)
}
const noRelease = (type, loader, gv) => `No ${T[type].loader ? loader + ' ' : ''}release for Minecraft ${gv}`
// Recursively resolves required dependencies. `loaders` is { loader, ploader }: the mod loader and the plugin platform.
export async function resolve(roots, gv, loaders) {
  const out = new Map()
  const pick = (t) => (t === 'plugin' ? loaders.ploader : loaders.loader)
  async function visit(id, by, hint) {
    if (out.has(id)) { if (by) out.get(id).requiredBy.add(by); return }
    const e = { id, title: id, type: 'mod', requiredBy: new Set(by ? [by] : []), root: false, optIds: [], incompat: [] }
    out.set(id, e)
    const p = await j(`/project/${id}`)
    e.title = p.title; e.slug = p.slug; e.icon = p.icon_url; e.type = typeOf(p.project_type)
    // Plugins are "mod" projects on Modrinth, so a plugin (or a dependency of one) keeps the plugin type.
    if (e.type === 'mod' && hint === 'plugin') e.type = 'plugin'
    const L = pick(e.type)
    e.client = p.client_side; e.server = p.server_side
    const vers = await j(versionsPath(id, gv, e.type, L))
    const v = vers.find((x) => x.version_type === 'release') || vers[0]
    if (!v) { e.error = noRelease(e.type, L, gv); return }
    e.version = v
    e.file = v.files.find((f) => f.primary) || v.files[0]
    for (const d of v.dependencies || []) {
      if (d.dependency_type === 'optional' && d.project_id) e.optIds.push(d.project_id)
      if (d.dependency_type === 'incompatible' && d.project_id) e.incompat.push(d.project_id)
      if (d.dependency_type !== 'required') continue
      let pid = d.project_id
      if (!pid && d.version_id) pid = (await j(`/version/${d.version_id}`)).project_id
      if (pid) await visit(pid, p.title, e.type)
    }
  }
  // A few roots are resolved in parallel to keep large selections fast without hammering the API.
  const queue = [...roots]
  await Promise.all(Array.from({ length: 4 }, async () => {
    while (queue.length) {
      const r = queue.shift()
      try { await visit(r.id, null, r.type) } catch (err) {
        out.set(r.id, { id: r.id, title: r.title, type: typeOf(r.type), requiredBy: new Set(), error: err.message })
      }
      if (out.has(r.id)) out.get(r.id).root = true
    }
  }))
  const ids = new Set()
  for (const e of out.values()) for (const i of e.optIds || []) if (!out.has(i)) ids.add(i)
  const optional = []
  for (const id of [...ids].slice(0, 8)) {
    try { const p = await j(`/project/${id}`); optional.push({ id, title: p.title, type: typeOf(p.project_type) }) } catch {}
  }
  // Keep the user's selection order, then the dependencies.
  const first = roots.map((r) => out.get(r.id)).filter(Boolean)
  const arr = [...new Set([...first, ...out.values()])]
  arr.optional = optional
  return arr
}
const PACK_DIRS = ['mods/', 'resourcepacks/', 'shaderpacks/']
// Maps the files of an .mrpack to Modrinth projects using their SHA-1 hashes.
export async function mrpackToMods(index) {
  const hashes = (index.files || []).filter((f) => PACK_DIRS.some((d) => f.path?.startsWith(d))).map((f) => f.hashes?.sha1).filter(Boolean)
  if (!hashes.length) throw new Error('No downloadable mods, resource packs or shaders found in this pack')
  const r = await fetch(BASE + '/version_files', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ hashes, algorithm: 'sha1' }) })
  if (!r.ok) throw new Error('Modrinth returned ' + r.status)
  const map = await r.json()
  const ids = [...new Set(Object.values(map).map((v) => v.project_id))]
  const projs = ids.length ? await j(`/projects?ids=${enc(ids)}`) : []
  return { mods: projs.map((p) => ({ id: p.id, title: p.title, type: typeOf(p.project_type) })), missing: hashes.length - Object.keys(map).length }
}
