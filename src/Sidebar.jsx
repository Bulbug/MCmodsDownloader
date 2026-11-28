import { Box, Boxes, Package, Paintbrush, Braces, Glasses, Plug, ChevronLeft, ChevronRight, Heart, Settings, Info } from 'lucide-react'
import { TYPES, PLUGIN_LOADERS, PLUGIN_NAMES } from './types.js'

const LOADER_NAMES = { fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge', quilt: 'Quilt' }

const TYPE_ICONS = {
  mod: <Box size={20} />,
  modpack: <Boxes size={20} />,
  resourcepack: <Paintbrush size={20} />,
  datapack: <Braces size={20} />,
  shader: <Glasses size={20} />,
  plugin: <Plug size={20} />
}

const NAV_SECTIONS = [
  { id: 'convert', label: 'Convert .mrpack', icon: <ChevronRight size={20} /> },
  { id: 'packs', label: 'Saved modpacks', icon: <Package size={20} /> },
  { id: 'favorites', label: 'Favorites', icon: <Heart size={20} /> },
  { id: 'settings', label: 'Settings', icon: <Settings size={20} /> },
  { id: 'about', label: 'About', icon: <Info size={20} /> }
]

export default function Sidebar({
  view,
  setView,
  type,
  setType,
  collapsed,
  setCollapsed,
  counts,
  isPlugin,
  curLoader,
  setLoader,
  setPloader
}) {
  const handleTypeChange = (t) => {
    setType(t.id)
    setView('browse')
  }

  const handleNavClick = (id) => {
    setView(id)
  }

  const handleLoaderChange = (e) => {
    const value = e.target.value
    if (isPlugin) setPloader(value)
    else setLoader(value)
  }

  const loaderOptions = isPlugin
    ? Object.entries(PLUGIN_LOADERS).map(([key]) => [key, PLUGIN_NAMES[key] || key])
    : Object.entries(LOADER_NAMES)

  return (
    <aside className={`sidebar ${collapsed ? 'collapsed' : ''}`} role="navigation" aria-label="Main navigation">
      {/* Collapse toggle */}
      <button
        className="sidebar-toggle"
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        onClick={() => setCollapsed(!collapsed)}
        aria-expanded={!collapsed}
      >
        {collapsed ? <ChevronRight size={18} /> : <ChevronLeft size={18} />}
      </button>

      {/* Type tiles */}
      <div className="sidebar-section">
        <div className="sidebar-section-title" aria-hidden="true">Content Types</div>
        <div className="type-tiles" role="group" aria-label="Content types">
          {TYPES.map((t) => (
            <button
              key={t.id}
              className={`type-tile ${view === 'browse' && type === t.id ? 'active' : ''}`}
              aria-pressed={view === 'browse' && type === t.id}
              onClick={() => handleTypeChange(t)}
              title={t.label}
            >
              {TYPE_ICONS[t.id]}
              {!collapsed && <span>{t.label}</span>}
            </button>
          ))}
        </div>
      </div>

      {/* Loader / platform selector */}
      {!collapsed && (
        <div className="sidebar-section">
          <div className="sidebar-section-title" aria-hidden="true">{isPlugin ? 'Server Platform' : 'Loader'}</div>
          <select
            className="loader-select"
            value={curLoader}
            onChange={handleLoaderChange}
            aria-label={isPlugin ? 'Server platform' : 'Mod loader'}
          >
            {loaderOptions.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>
      )}

      {/* Sections */}
      <div className="sidebar-section">
        {!collapsed && (
          <div className="sidebar-section-title" aria-hidden="true">My Stuff</div>
        )}
        <div className="nav-sections" role="group" aria-label="My stuff">
          {NAV_SECTIONS.map((section) => (
            <button
              key={section.id}
              className={`nav-item ${view === section.id ? 'active' : ''}`}
              aria-pressed={view === section.id}
              onClick={() => handleNavClick(section.id)}
            >
              {section.icon}
              {!collapsed && (
                <span className="nav-label">{section.label}</span>
              )}
              {counts[section.id] && !collapsed && (
                <span className="nav-badge">{counts[section.id]}</span>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Footer: version info */}
      {!collapsed && (
        <div className="sidebar-footer">
          <small>DIV Modpack Builder</small>
        </div>
      )}
    </aside>
  )
}
