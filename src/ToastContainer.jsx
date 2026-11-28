import { useEffect, useRef } from 'react'
import { Check, AlertTriangle, Info } from 'lucide-react'

const TYPE_ICONS = {
  success: <Check size={14} strokeWidth={2.5} />,
  error: <AlertTriangle size={14} strokeWidth={2.5} />,
  info: <Info size={14} strokeWidth={2.5} />
}

const TYPE_CLASS = { success: 'toast-success', error: 'toast-error', info: 'toast-info' }

export default function ToastContainer({ toasts = [], onDismiss }) {
  const timers = useRef({})

  // Auto-dismiss each toast after its duration.
  useEffect(() => {
    toasts.forEach((t) => {
      if (timers.current[t.id]) return
      timers.current[t.id] = setTimeout(() => {
        onDismiss?.(t.id)
        delete timers.current[t.id]
      }, t.dur || 4000)
    })

    // Clean up timers for toasts that were removed.
    ;(Object.keys(timers.current)).forEach((id) => {
      if (!toasts.some((t) => t.id === id)) {
        clearTimeout(timers.current[id])
        delete timers.current[id]
      }
    })
    return () => {
      Object.values(timers.current).forEach(clearTimeout)
      timers.current = {}
    }
  }, [toasts, onDismiss])

  const handleDismiss = (id) => {
    if (timers.current[id]) {
      clearTimeout(timers.current[id])
      delete timers.current[id]
    }
    onDismiss?.(id)
  }

  return (
    <div className="toast-container" aria-label="Notifications" aria-live="polite" atomic>
      {toasts.map((t) => (
        <div
          key={t.id}
          className={`toast toast-${t.type} ${TYPE_CLASS[t.type] || ''}`}
          role="alert"
          aria-label={t.msg}
        >
          <div className="toast-icon">{TYPE_ICONS[t.type] || TYPE_ICONS.info}</div>
          <div className="toast-body">{t.msg}</div>
          <button className="toast-close" aria-label="Dismiss" onClick={() => handleDismiss(t.id)}>✕</button>
          <div className="toast-progress">
            <div className="toast-progress-fill" />
          </div>
        </div>
      ))}
    </div>
  )
}
