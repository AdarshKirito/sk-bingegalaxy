import { createContext, useCallback, useContext, useRef, useState } from 'react';
import ConfirmDialog from './ConfirmDialog';

/**
 * Imperative confirmation API.
 *
 * Wrap the app (or admin shell) in <ConfirmProvider>, then call
 * `await confirm({ ... })` to await a user decision.
 *
 * The returned promise resolves to:
 *   - `false`              → user cancelled
 *   - `true`               → user confirmed (no reason captured)
 *   - `{ reason: string }` → user confirmed with `withReason: true`
 *
 * This is a drop-in replacement for `window.confirm` / `window.prompt`
 * with proper modal UX, accessibility, and theme integration.
 */
const ConfirmContext = createContext(null);

export function ConfirmProvider({ children }) {
  const [state, setState] = useState({ open: false, opts: {} });
  const [busy, setBusy] = useState(false);
  const resolveRef = useRef(null);

  const close = useCallback(() => {
    setBusy(false);
    setState((s) => ({ ...s, open: false }));
  }, []);

  const confirm = useCallback((opts = {}) => {
    return new Promise((resolve) => {
      resolveRef.current = resolve;
      setState({ open: true, opts });
    });
  }, []);

  const handleCancel = () => {
    if (busy) return;
    resolveRef.current?.(false);
    resolveRef.current = null;
    close();
  };

  const handleConfirm = (reason) => {
    const result = state.opts?.withReason ? { reason: reason || '' } : true;
    resolveRef.current?.(result);
    resolveRef.current = null;
    close();
  };

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <ConfirmDialog
        open={state.open}
        title={state.opts.title || 'Are you sure?'}
        message={state.opts.message || ''}
        confirmLabel={state.opts.confirmLabel}
        cancelLabel={state.opts.cancelLabel}
        variant={state.opts.variant}
        withReason={!!state.opts.withReason}
        reasonLabel={state.opts.reasonLabel}
        reasonPlaceholder={state.opts.reasonPlaceholder}
        reasonRequired={state.opts.reasonRequired}
        reasonMaxLength={state.opts.reasonMaxLength}
        busy={busy}
        onCancel={handleCancel}
        onConfirm={handleConfirm}
      />
    </ConfirmContext.Provider>
  );
}

/**
 * useConfirm() returns an async function:
 *   const confirm = useConfirm();
 *   if (!(await confirm({ title: 'Delete?', message: '...' }))) return;
 *
 * For prompt-style dialogs:
 *   const result = await confirm({ title: 'Reject', withReason: true, reasonRequired: true });
 *   if (!result) return; // cancelled
 *   const reason = result.reason;
 */
export function useConfirm() {
  const ctx = useContext(ConfirmContext);
  if (!ctx) {
    throw new Error('useConfirm must be used within <ConfirmProvider>');
  }
  return ctx;
}
