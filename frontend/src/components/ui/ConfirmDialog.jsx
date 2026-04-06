import { useRef, useEffect } from 'react';
import { FiAlertTriangle } from 'react-icons/fi';

export default function ConfirmDialog({ open, title, message, confirmLabel = 'Confirm', cancelLabel = 'Cancel', variant = 'danger', onConfirm, onCancel }) {
  const dialogRef = useRef(null);

  useEffect(() => {
    if (!dialogRef.current) return;
    if (open) dialogRef.current.showModal();
    else dialogRef.current.close();
  }, [open]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const handleCancel = (e) => { e.preventDefault(); onCancel?.(); };
    dialog.addEventListener('cancel', handleCancel);
    return () => dialog.removeEventListener('cancel', handleCancel);
  }, [onCancel]);

  if (!open) return null;

  const colors = {
    danger: { bg: 'var(--danger-bg)', color: 'var(--danger)', btnClass: 'btn-danger' },
    warning: { bg: 'var(--warning-bg)', color: 'var(--warning)', btnClass: 'btn-primary' },
    primary: { bg: 'var(--primary-bg)', color: 'var(--primary)', btnClass: 'btn-primary' },
  };
  const c = colors[variant] || colors.danger;

  return (
    <dialog
      ref={dialogRef}
      onClick={(e) => e.target === dialogRef.current && onCancel?.()}
      style={{
        border: 'none', borderRadius: 'var(--radius)', background: 'var(--bg-card)',
        color: 'var(--text)', padding: 0, maxWidth: '400px', width: '90vw',
        boxShadow: 'var(--shadow-lg)',
      }}
    >
      <div style={{ padding: '1.75rem', textAlign: 'center' }}>
        <div style={{
          width: 48, height: 48, borderRadius: 12,
          background: c.bg, color: c.color,
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '1.5rem', marginBottom: '1rem',
        }}>
          <FiAlertTriangle />
        </div>
        <h3 style={{ margin: '0 0 0.5rem', fontSize: '1.15rem' }}>{title}</h3>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', marginBottom: '1.5rem' }}>{message}</p>
        <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'center' }}>
          <button className="btn btn-secondary" onClick={onCancel}>{cancelLabel}</button>
          <button className={`btn ${c.btnClass}`} onClick={onConfirm}>{confirmLabel}</button>
        </div>
      </div>
    </dialog>
  );
}
