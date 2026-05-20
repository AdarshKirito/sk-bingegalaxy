import { useState, useEffect } from 'react';
import { currencyService, toArray, authorityService } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { useAuth } from '../context/AuthContext';
import LockBadge, { useResourceLock } from '../components/authority/LockBadge';
import { toast } from 'react-toastify';
import { FiPlus, FiEdit2, FiTrash2, FiX, FiDollarSign, FiToggleLeft, FiToggleRight, FiLock, FiUnlock } from 'react-icons/fi';
import './AdminPages.css';

const FX_SOURCES = ['MANUAL', 'ECB', 'OPENEXCHANGE', 'FIXER'];

const emptyForm = {
  code: '',
  name: '',
  symbol: '',
  rateToBase: '1',
  decimalDigits: 2,
  active: true,
  base: false,
  supportsDisplay: true,
  supportsPayment: false,
  supportsSettlement: false,
  fxSource: 'MANUAL',
};

export default function AdminCurrencies() {
  const confirm = useConfirm();
  const { isSuperAdmin } = useAuth();
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState({ open: false, mode: 'create', item: null });
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);

  const fetchAll = () => {
    setLoading(true);
    currencyService.listAll()
      .then(res => setList(toArray(res.data?.data)))
      .catch(() => toast.error('Failed to load currencies'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchAll(); }, []);

  const openCreate = () => { setForm(emptyForm); setModal({ open: true, mode: 'create', item: null }); };

  const openEdit = (c) => {
    setForm({
      code: c.code,
      name: c.name || '',
      symbol: c.symbol || '',
      rateToBase: String(c.rateToBase ?? 1),
      decimalDigits: c.decimalDigits ?? 2,
      active: c.active !== false,
      base: !!c.base,
      supportsDisplay: c.supportsDisplay !== false,
      supportsPayment: !!c.supportsPayment,
      supportsSettlement: !!c.supportsSettlement,
      fxSource: c.fxSource || 'MANUAL',
    });
    setModal({ open: true, mode: 'edit', item: c });
  };

  const handleSave = async (e) => {
    e.preventDefault();
    const code = form.code.trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(code)) { toast.error('Currency code must be 3 letters'); return; }
    const rate = Number(form.rateToBase);
    if (!(rate > 0)) { toast.error('Rate must be > 0'); return; }
    setSaving(true);
    try {
      await currencyService.upsert({
        code,
        name: form.name.trim(),
        symbol: form.symbol.trim(),
        rateToBase: rate,
        decimalDigits: Number(form.decimalDigits) || 2,
        active: !!form.active,
        base: !!form.base,
        supportsDisplay: !!form.supportsDisplay,
        supportsPayment: !!form.supportsPayment,
        supportsSettlement: !!form.supportsSettlement,
        fxSource: form.fxSource || 'MANUAL',
      });
      toast.success(modal.mode === 'create' ? 'Currency created' : 'Currency updated');
      setModal({ open: false, mode: 'create', item: null });
      fetchAll();
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Save failed');
    } finally { setSaving(false); }
  };

  const handleToggle = async (c) => {
    try { await currencyService.toggle(c.code); fetchAll(); }
    catch (err) { toast.error(err?.response?.data?.message || 'Toggle failed'); }
  };

  const handleDelete = async (c) => {
    if (c.base) { toast.error('Cannot delete base currency'); return; }
    const ok = await confirm({
      title: `Delete currency ${c.code}?`,
      message: 'This currency will no longer be available for display, payment or settlement. Existing data referencing it is preserved.',
      confirmLabel: 'Delete currency',
      variant: 'danger',
    });
    if (!ok) return;
    try { await currencyService.remove(c.code); toast.success('Deleted'); fetchAll(); }
    catch (err) { toast.error(err?.response?.data?.message || 'Delete failed'); }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1><FiDollarSign /> Currencies</h1>
          <p className="page-subtitle">Manage display currencies and exchange rates. The base currency cannot be deleted.</p>
        </div>
        <button className="btn btn-primary" onClick={openCreate}><FiPlus /> Add Currency</button>
      </div>

      {loading ? <p>Loading…</p> : (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr><th>Code</th><th>Name</th><th>Symbol</th><th>Rate to Base</th><th>Decimals</th><th>Roles</th><th>FX Source</th><th>Base</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              {list.map(c => (
                <CurrencyRow
                  key={c.code}
                  c={c}
                  isSuperAdmin={isSuperAdmin}
                  confirm={confirm}
                  onEdit={openEdit}
                  onToggle={handleToggle}
                  onDelete={handleDelete}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {modal.open && (
        <div className="modal-overlay" onClick={(e) => e.target === e.currentTarget && setModal({ open: false, mode: 'create', item: null })}>
          <div className="modal-content" role="dialog" aria-modal="true">
            <div className="modal-header">
              <h2>{modal.mode === 'create' ? 'New Currency' : `Edit ${modal.item?.code}`}</h2>
              <button className="icon-btn" onClick={() => setModal({ open: false, mode: 'create', item: null })}><FiX /></button>
            </div>
            <form onSubmit={handleSave} className="modal-form">
              <div className="form-row">
                <label>Code (ISO 4217)
                  <input required maxLength={3} value={form.code} disabled={modal.mode === 'edit'}
                    onChange={e => setForm(f => ({ ...f, code: e.target.value.toUpperCase() }))} placeholder="USD" />
                </label>
                <label>Symbol<input value={form.symbol} onChange={e => setForm(f => ({ ...f, symbol: e.target.value }))} placeholder="$" /></label>
              </div>
              <label>Name<input required value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="US Dollar" /></label>
              <div className="form-row">
                <label>Rate to base (1 base unit = X of this currency)
                  <input required type="number" step="0.000001" min="0.000001" value={form.rateToBase}
                    onChange={e => setForm(f => ({ ...f, rateToBase: e.target.value }))} />
                </label>
                <label>Decimals<input type="number" min="0" max="6" value={form.decimalDigits}
                  onChange={e => setForm(f => ({ ...f, decimalDigits: e.target.value }))} /></label>
              </div>
              <div className="form-row">
                <label className="checkbox-label"><input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} /> Active</label>
                <label className="checkbox-label"><input type="checkbox" checked={form.base} onChange={e => setForm(f => ({ ...f, base: e.target.checked }))} /> Base currency</label>
              </div>
              <div className="form-row">
                <label className="checkbox-label"><input type="checkbox" checked={form.supportsDisplay} onChange={e => setForm(f => ({ ...f, supportsDisplay: e.target.checked }))} /> Supports display</label>
                <label className="checkbox-label"><input type="checkbox" checked={form.supportsPayment} onChange={e => setForm(f => ({ ...f, supportsPayment: e.target.checked }))} /> Supports payment</label>
                <label className="checkbox-label"><input type="checkbox" checked={form.supportsSettlement} onChange={e => setForm(f => ({ ...f, supportsSettlement: e.target.checked }))} /> Supports settlement</label>
              </div>
              <label>FX Source
                <select value={form.fxSource} onChange={e => setForm(f => ({ ...f, fxSource: e.target.value }))}>
                  {FX_SOURCES.map(v => <option key={v} value={v}>{v}</option>)}
                </select>
              </label>
              <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                Marking a currency as Base will set all other currencies to non-base. Bookings remain stored in the base currency.
              </p>
              <div className="modal-actions">
                <button type="button" className="btn btn-secondary" onClick={() => setModal({ open: false, mode: 'create', item: null })}>Cancel</button>
                <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving…' : 'Save'}</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Lock-aware row. When the currency is locked by a super-admin, delegated admins
 * (anyone who is not a native super-admin) lose the ability to edit / toggle /
 * delete it. Native super-admins always retain the action buttons because they
 * placed the lock and need a path to release it via the Authority Handover page.
 *
 * The lock badge is rendered inline next to the currency code so the constraint
 * is visible without scanning a separate column.
 */
function CurrencyRow({ c, isSuperAdmin, confirm, onEdit, onToggle, onDelete }) {
  const [lock, refetchLock] = useResourceLock('CURRENCY', c.code);
  const [busy, setBusy] = useState(false);
  const isLocked = !!lock;
  const blocked = isLocked && !isSuperAdmin;

  const lockRow = async () => {
    const result = await confirm({
      title: `Lock currency ${c.code}?`,
      message: 'While locked, only you (the locker) and other native super-admins can modify it. Delegated admins will be blocked.',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Reason for lock',
      reasonPlaceholder: 'e.g. Currency rate frozen pending finance review',
      reasonMaxLength: 500,
      confirmLabel: 'Lock',
      variant: 'warning',
    });
    if (!result) return;
    setBusy(true);
    try {
      await authorityService.createLock({ resourceType: 'CURRENCY', resourceId: c.code, reason: result.reason });
      toast.success(`Locked ${c.code}`);
      refetchLock();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Lock failed');
    } finally { setBusy(false); }
  };

  const unlockRow = async () => {
    if (!lock) return;
    const result = await confirm({
      title: `Release lock on ${c.code}?`,
      message: 'Delegated admins will regain the ability to modify this currency.',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Reason',
      reasonPlaceholder: 'e.g. Finance review complete',
      confirmLabel: 'Release',
      variant: 'danger',
    });
    if (!result) return;
    setBusy(true);
    try {
      await authorityService.releaseLock(lock.id, result.reason);
      toast.success('Lock released');
      refetchLock();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Release failed');
    } finally { setBusy(false); }
  };

  return (
    <tr>
      <td>
        <strong>{c.code}</strong>
        {isLocked && (
          <div style={{ marginTop: 4 }}>
            <LockBadge type="CURRENCY" id={c.code} compact />
          </div>
        )}
      </td>
      <td>{c.name}</td>
      <td>{c.symbol}</td>
      <td>{Number(c.rateToBase).toFixed(6)}</td>
      <td>{c.decimalDigits}</td>
      <td style={{ fontSize: '0.78rem' }}>
        {c.supportsDisplay !== false && <span className="badge badge-info" style={{ marginRight: 4 }}>D</span>}
        {c.supportsPayment && <span className="badge badge-info" style={{ marginRight: 4 }}>P</span>}
        {c.supportsSettlement && <span className="badge badge-info">S</span>}
      </td>
      <td>{c.fxSource || 'MANUAL'}</td>
      <td>{c.base ? <span className="badge badge-info">Base</span> : '—'}</td>
      <td><span className={`badge ${c.active ? 'badge-success' : 'badge-muted'}`}>{c.active ? 'Active' : 'Inactive'}</span></td>
      <td className="row-actions">
        <button
          className="icon-btn"
          onClick={() => onEdit(c)}
          disabled={blocked}
          title={blocked ? 'Locked by super-admin' : 'Edit'}
          aria-label="Edit"
        >
          <FiEdit2 />
        </button>
        <button
          className="icon-btn"
          onClick={() => onToggle(c)}
          disabled={blocked}
          title={blocked ? 'Locked by super-admin' : 'Toggle'}
          aria-label="Toggle"
        >
          {c.active ? <FiToggleRight /> : <FiToggleLeft />}
        </button>
        {!c.base && (
          <button
            className="icon-btn icon-btn-danger"
            onClick={() => onDelete(c)}
            disabled={blocked}
            title={blocked ? 'Locked by super-admin' : 'Delete'}
            aria-label="Delete"
          >
            <FiTrash2 />
          </button>
        )}
        {isSuperAdmin && (
          isLocked ? (
            <button
              className="icon-btn"
              onClick={unlockRow}
              disabled={busy}
              title="Release lock"
              aria-label="Release lock"
            >
              <FiUnlock />
            </button>
          ) : (
            <button
              className="icon-btn"
              onClick={lockRow}
              disabled={busy}
              title="Lock this currency"
              aria-label="Lock"
            >
              <FiLock />
            </button>
          )
        )}
      </td>
    </tr>
  );
}
