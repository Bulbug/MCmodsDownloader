import { Box, Boxes, Package, Paintbrush, Braces, Glasses, Plug, ArrowLeft, ArrowRight, ChevronLeft, ChevronRight } from 'lucide-react'
import { TYPES, T, PLUGIN_LOADERS, PLUGIN_NAMES } from './types.js'

const TYPE_ICONS = {
  mod: <Box size={20} />,
  modpack: <Boxes size={20} />,
  resourcepack: <Paintbrush size={20} />,
  datapack: <Braces size={20} />,
  shader: <Glasses size={20} />,
  plugin: <Plug size={20} />
}

const NAV_SECTIONS = [
  { id: 'convert', label: 'Convert .mrpack', icon: ArrowRight },
  { id: 'packs', label: 'Saved modpacks', icon: Package },
  { id: 'favorites', label: 'Favorites', icon: 'heart' },
  { id: 'settings', label: 'Settings', icon: 'cog' },
  { id: 'about', label: 'About', icon: 'info' }
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
        {(!collapsed || collapsed) && (
          <div className="sidebar-section-title" aria-hidden="true">Content Types</div>
        )}
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
              {section.icon === 'heart' ? (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
                </svg>
              ) : section.icon === 'cog' ? (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <circle cx="12" cy="12" r="3" />
                  <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
                </svg>
              ) : section.icon === 'info' ? (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <circle cx="12" cy="12" r="10" />
                  <path d="M12 16v-4" />
                  <path d="M12 8h.01" />
                </svg>
              ) : (
                <section.icon size={20} />
              )}
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