import { useEffect, useRef, useState } from 'react';

/**
 * Shared modal on the native <dialog> element (focus trap, focus restore and
 * aria-modal come free from showModal()).
 *
 * Dismissal follows the WAI-ARIA dialog pattern: a single Escape press or a
 * single backdrop click closes the dialog. Modals holding unsaved input can
 * opt into `confirmClose` — the first Escape/backdrop attempt then arms a
 * short confirmation window (announced via aria-live for screen readers) and
 * only a second attempt closes.
 */
export default function Modal({ open, onClose, title, children, style, confirmClose = false }) {
  const dialogRef = useRef(null);
  const dismissCount = useRef(0);
  const dismissTimer = useRef(null);
  const [confirmHint, setConfirmHint] = useState(false);

  useEffect(() => {
    if (!dialogRef.current) return;
    if (open) {
      dialogRef.current.showModal();
    } else {
      dialogRef.current.close();
    }
  }, [open]);

  const requestClose = () => {
    if (!confirmClose) {
      onClose?.();
      return;
    }
    dismissCount.current += 1;
    if (dismissCount.current >= 2) {
      dismissCount.current = 0;
      clearTimeout(dismissTimer.current);
      setConfirmHint(false);
      onClose?.();
    } else {
      setConfirmHint(true);
      clearTimeout(dismissTimer.current);
      dismissTimer.current = setTimeout(() => {
        dismissCount.current = 0;
        setConfirmHint(false);
      }, 3000);
    }
  };

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const handleCancel = (e) => {
      e.preventDefault();
      requestClose();
    };
    dialog.addEventListener('cancel', handleCancel);
    return () => {
      dialog.removeEventListener('cancel', handleCancel);
      clearTimeout(dismissTimer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onClose, confirmClose]);

  const handleBackdropClick = (e) => {
    if (e.target !== dialogRef.current) return;
    requestClose();
  };

  if (!open) return null;

  return (
    <dialog
      ref={dialogRef}
      onClick={handleBackdropClick}
      aria-label={title}
      style={{
        border: 'none', borderRadius: 'var(--radius)', background: 'var(--bg-card)',
        color: 'var(--text)', padding: 0, maxWidth: '90vw', maxHeight: '90vh',
        boxShadow: 'var(--shadow-lg)', ...style,
      }}
    >
      <div style={{ padding: '1.5rem', maxHeight: 'calc(90vh - 2px)', overflowY: 'auto' }}>
        {title && (
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ margin: 0 }}>{title}</h2>
            <button onClick={onClose} aria-label="Close dialog"
              style={{ background: 'none', border: 'none', fontSize: '1.5rem', cursor: 'pointer', color: 'var(--text-muted)', padding: '0.25rem' }}>
              &times;
            </button>
          </div>
        )}
        {confirmClose && (
          <div role="status" aria-live="assertive" style={{ position: 'absolute', top: '0.5rem', left: '50%', transform: 'translateX(-50%)' }}>
            {confirmHint && (
              <span style={{
                background: 'var(--bg-secondary, rgba(0,0,0,0.75))', color: 'var(--text)',
                padding: '0.25rem 0.75rem', borderRadius: '4px', fontSize: '0.85em', whiteSpace: 'nowrap',
              }}>
                You may have unsaved changes — press Escape again to close
              </span>
            )}
          </div>
        )}
        {children}
      </div>
    </dialog>
  );
}
