// Content types shown in the sidebar, in display order.
// `loader: true` means releases are filtered by the selected loader / server platform.
// `ptype` is the Modrinth project_type to search (plugins are "mod" projects on server platforms).
export const TYPES = [
  { id: 'mod', label: 'Mods', dir: 'mods', loader: true },
  { id: 'resourcepack', label: 'Resource Packs', dir: 'resourcepacks', loader: false },
  { id: 'datapack', label: 'Data Packs', dir: 'datapacks', loader: false },
  { id: 'shader', label: 'Shaders', dir: 'shaderpacks', loader: false },
  { id: 'modpack', label: 'Modpacks', dir: 'modpacks', loader: true },
  { id: 'plugin', label: 'Plugins', dir: 'plugins', loader: true, ptype: 'mod' },
]
export const T = Object.fromEntries(TYPES.map((t) => [t.id, t]))
export const typeOf = (x) => (T[x] ? x : 'mod')

// Server platforms for plugins. Each lists the Modrinth loaders whose plugins run on it.
export const PLUGIN_LOADERS = {
  paper: ['paper', 'spigot', 'bukkit'],
  purpur: ['purpur', 'paper', 'spigot', 'bukkit'],
  spigot: ['spigot', 'bukkit'],
  folia: ['folia'],
}
export const PLUGIN_NAMES = { paper: 'Paper', purpur: 'Purpur', spigot: 'Spigot', folia: 'Folia' }
// The Modrinth loader names to filter by for a type + chosen loader/platform.
export const loaderSet = (type, loader) => (type === 'plugin' ? PLUGIN_LOADERS[loader] || [loader] : [loader])
