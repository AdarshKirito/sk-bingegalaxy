import { useRef, useEffect, useState } from 'react';
import { FiAlertTriangle } from 'react-icons/fi';

export default function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'danger',
  onConfirm,
  onCancel,
  // Optional reason capture — when `withReason` is true, a textarea is shown
  // and the entered text is passed to onConfirm(reason). `reasonRequired`
  // disables the confirm button when the text is empty.
  withReason = false,
  reasonLabel = 'Reason',
  reasonPlaceholder = 'Add an optional note for the audit log…',
  reasonRequired = false,
  reasonMaxLength = 500,
  busy = false,
}) {
  const dialogRef = useRef(null);
  const textareaRef = useRef(null);
  const [reason, setReason] = useState('');

  useEffect(() => {
    if (!dialogRef.current) return;
    if (open) {
      setReason('');
      dialogRef.current.showModal();
      // Focus the textarea (if any) once opened for accessibility.
      setTimeout(() => textareaRef.current?.focus(), 0);
    } else {
      dialogRef.current.close();
    }
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

  const trimmedReason = reason.trim();
  const confirmDisabled = busy || (withReason && reasonRequired && !trimmedReason);

  const handleConfirm = () => {
    if (confirmDisabled) return;
    if (withReason) onConfirm?.(trimmedReason);
    else onConfirm?.();
  };

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="confirm-dialog-title"
      onClick={(e) => e.target === dialogRef.current && !busy && onCancel?.()}
      style={{
        border: 'none', borderRadius: 'var(--radius)', background: 'var(--bg-card)',
        color: 'var(--text)', padding: 0, maxWidth: '460px', width: '92vw',
        boxShadow: 'var(--shadow-lg)',
      }}
    >
      <div style={{ padding: '1.75rem', textAlign: withReason ? 'left' : 'center' }}>
        <div style={{
          width: 48, height: 48, borderRadius: 12,
          background: c.bg, color: c.color,
          display: withReason ? 'flex' : 'inline-flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '1.5rem', marginBottom: '1rem',
        }}>
          <FiAlertTriangle />
        </div>
        <h3 id="confirm-dialog-title" style={{ margin: '0 0 0.5rem', fontSize: '1.15rem' }}>{title}</h3>
        {message ? (
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', marginBottom: withReason ? '1rem' : '1.5rem' }}>{message}</p>
        ) : null}
        {withReason ? (
          <div style={{ marginBottom: '1.25rem' }}>
            <label style={{ display: 'block', fontSize: '0.85rem', fontWeight: 600, marginBottom: '0.4rem' }}>
              {reasonLabel}{reasonRequired ? <span style={{ color: 'var(--danger)' }}> *</span> : null}
            </label>
            <textarea
              ref={textareaRef}
              className="form-control"
              rows={3}
              maxLength={reasonMaxLength}
              placeholder={reasonPlaceholder}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              style={{ width: '100%', resize: 'vertical' }}
              disabled={busy}
            />
            <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textAlign: 'right', marginTop: '0.25rem' }}>
              {reason.length}/{reasonMaxLength}
            </div>
          </div>
        ) : null}
        <div style={{ display: 'flex', gap: '0.75rem', justifyContent: withReason ? 'flex-end' : 'center' }}>
          <button className="btn btn-secondary" onClick={onCancel} disabled={busy}>{cancelLabel}</button>
          <button
            className={`btn ${c.btnClass}`}
            onClick={handleConfirm}
            disabled={confirmDisabled}
            aria-busy={busy ? 'true' : undefined}
          >
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </dialog>
  );
}
