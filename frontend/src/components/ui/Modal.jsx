import { useEffect, useRef } from 'react';

export default function Modal({ open, onClose, title, children, style }) {
  const dialogRef = useRef(null);
  const backdropClickCount = useRef(0);
  const backdropTimer = useRef(null);

  useEffect(() => {
    if (!dialogRef.current) return;
    if (open) {
      dialogRef.current.showModal();
    } else {
      dialogRef.current.close();
    }
  }, [open]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const handleCancel = (e) => {
      e.preventDefault();
      // Require double-press of Escape to close
      backdropClickCount.current += 1;
      if (backdropClickCount.current >= 2) {
        backdropClickCount.current = 0;
        clearTimeout(backdropTimer.current);
        onClose?.();
      } else {
        clearTimeout(backdropTimer.current);
        backdropTimer.current = setTimeout(() => { backdropClickCount.current = 0; }, 600);
      }
    };
    dialog.addEventListener('cancel', handleCancel);
    return () => {
      dialog.removeEventListener('cancel', handleCancel);
      clearTimeout(backdropTimer.current);
    };
  }, [onClose]);

  const handleBackdropClick = (e) => {
    if (e.target !== dialogRef.current) return;
    // Require double-tap on backdrop to close
    backdropClickCount.current += 1;
    if (backdropClickCount.current >= 2) {
      backdropClickCount.current = 0;
      clearTimeout(backdropTimer.current);
      onClose?.();
    } else {
      clearTimeout(backdropTimer.current);
      backdropTimer.current = setTimeout(() => { backdropClickCount.current = 0; }, 600);
    }
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
        {children}
      </div>
    </dialog>
  );
}
