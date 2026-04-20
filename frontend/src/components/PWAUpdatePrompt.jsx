import { useEffect, useRef } from 'react';
import { useRegisterSW } from 'virtual:pwa-register/react';

/**
 * Shows a non-intrusive toast-like banner when a new service worker is available,
 * letting the user choose when to reload. Prevents mid-booking disruption from
 * silent auto-updates.
 */
export default function PWAUpdatePrompt() {
  const intervalRef = useRef(null);
  const {
    needRefresh: [needRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisteredSW(swUrl, r) {
      // Check for updates every 60 minutes
      if (r) {
        intervalRef.current = setInterval(() => { r.update(); }, 60 * 60 * 1000);
      }
    },
  });

  useEffect(() => {
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, []);

  if (!needRefresh) return null;

  return (
    <div
      role="alert"
      style={{
        position: 'fixed',
        bottom: '1.5rem',
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 9999,
        background: 'var(--bg-card, #1e1e2e)',
        border: '1px solid var(--primary, #6366f1)',
        borderRadius: '0.75rem',
        padding: '0.875rem 1.25rem',
        display: 'flex',
        alignItems: 'center',
        gap: '1rem',
        boxShadow: '0 8px 32px rgba(0,0,0,0.4)',
        maxWidth: '400px',
        width: '90vw',
      }}
    >
      <span style={{ flex: 1, fontSize: '0.875rem', color: 'var(--text-primary, #fff)' }}>
        A new version is available.
      </span>
      <button
        onClick={() => updateServiceWorker(true)}
        style={{
          background: 'var(--primary, #6366f1)',
          color: 'var(--text-on-primary, #fff)',
          border: 'none',
          borderRadius: '0.5rem',
          padding: '0.5rem 1rem',
          cursor: 'pointer',
          fontWeight: 600,
          fontSize: '0.8125rem',
          whiteSpace: 'nowrap',
        }}
      >
        Update now
      </button>
    </div>
  );
}
