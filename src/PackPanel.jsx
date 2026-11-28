import React from 'react';
import './PackPanel.css';

const PackPanel = ({
  open,
  selected,
  plan,
  dl,
  canExport,
  perfBusy,
  planBusy,
  note,
  addPerfMods,
  installToLauncher,
  exportZip,
  cancelDownload,
  clearSelection,
  copyLink,
  onClose,
}) => {
  const ready = plan.filter((p) => p.file);
  const broken = plan.filter((p) => p.error);
  const sizeMB = (ready.reduce((n, p) => n + (p.file.size || 0), 0) / 1048576).toFixed(1);
  const count = ready.length;
  const hasSelection = selected.length > 0;

  return (
    <aside
      className={`pack-panel ${open ? 'open' : ''}`}
      id="pack-panel"
      aria-label="My pack"
    >
      {/* Overlay backdrop (shown on mobile / when the panel slides in) */}
      <div className="pack-panel-overlay" onClick={onClose} />

      <div className="pack-panel-inner">
        <div className="ahead">
          <h2>My pack</h2>
          <span className="badge big">{selected.length}</span>
          <button className="icon" aria-label="Close pack" onClick={onClose}>
            ✕
          </button>
        </div>

        <button
          className="ghost wide"
          disabled={perfBusy}
          onClick={addPerfMods}
        >
          Add performance mods
        </button>

        {planBusy && (
          <p><span className="spin" aria-hidden="true">⟳</span> Resolving dependencies…</p>
        )}

        {plan.length > 0 && (
          <>
            <h3>Dependency Groups</h3>
            <ul>
              {plan.map((p) => (
                <li key={p.id} className={p.error ? 'bad' : p.root ? '' : 'dep'}>
                  <div>
                    <b>{p.title}</b>
                    <small>
                      {p.error
                        ? p.error
                        : p.root
                          ? p.file?.filename
                          : 'Dependency of ' + [...p.requiredBy].join(', ')}
                    </small>
                  </div>
                  {p.root && (
                    <button
                      className="icon"
                      aria-label={`Remove ${p.title}`}
                      onClick={() => clearSelection()}
                    >
                      ✕
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}

        {!hasSelection && (
          <p className="empty">Add mods to start your pack.</p>
        )}

        <div className={`status ${broken.length ? 'bad' : ready.length ? 'ok' : ''}`}>
          {broken.length
            ? `Incompatible: ${broken.length} mod(s) have no release.`
            : ready.length
              ? 'All dependencies resolved.'
              : 'Nothing to check yet.'}
        </div>

        <div className="size-summary">
          {count} files · {sizeMB} MB
        </div>

        {dl && (
          <div className="dl">
            <progress value={dl.done} max={dl.total || 1} />
            <small>
              {dl.running
                ? `${dl.stage || dl.current} (${dl.done}/${dl.total})`
                : dl.note || `${dl.cancelled ? 'Cancelled. ' : ''}${dl.done}/${dl.total} downloaded`}
            </small>
            {dl.failed?.length && !dl.running && (
              <button className="ghost wide" onClick={onClose}>
                Retry failed
              </button>
            )}
          </div>
        )}

        {dl?.running ? (
          <button className="wide" onClick={cancelDownload}>
            Cancel download
          </button>
        ) : (
          <>
            {typeof installToLauncher === 'function' && (
              <button
                className="primary wide"
                disabled={!canExport}
                onClick={installToLauncher}
                style={{ marginBottom: '8px' }}
              >
                Install directly to Launcher
              </button>
            )}
            <button className="wide" disabled={!canExport} onClick={exportZip}>
              Download ZIP
            </button>
          </>
        )}

        {hasSelection && (
          <button className="ghost wide" onClick={clearSelection}>
            Clear selection
          </button>
        )}

        {hasSelection && <button className="ghost wide" onClick={copyLink}>Copy share link</button>}

        {note && <p className="small" role="status">{note}</p>}
      </div>
    </aside>
  );
};

export default PackPanel;