import { useEffect, useState, useCallback, useRef } from 'react';
import { adminService, authService, toArray } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import { FiLock, FiUnlock, FiPlus, FiRefreshCw, FiClock, FiSearch, FiX, FiUser } from 'react-icons/fi';
import { useBinge } from '../context/BingeContext';
import { formatServerDateTime } from '../services/timeFormat';
import './AdminPages.css';

const TRIGGER_LABELS = {
  CUSTOMER_CANCELLATIONS: 'Repeated cancellations',
  PAYMENT_TIMEOUTS: 'Payment timeouts',
  NO_SHOW_PATTERN: 'No-show pattern',
  MANUAL: 'Manual (admin)',
};

/**
 * Countdown driven by a client-side deadline derived from the server's
 * secondsRemaining (deadline = fetch time + remaining). Never parses the
 * zone-less UTC `freezeUntil` with `new Date()` — that reads it as
 * browser-local time and skews the timer by the viewer's UTC offset.
 */
function Countdown({ deadlineMs }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const remaining = Math.max(0, (deadlineMs || 0) - now);
  if (remaining <= 0) return <span style={{ color: 'var(--text-secondary)' }}>Expired</span>;
  const totalSec = Math.floor(remaining / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  return <span><FiClock style={{ verticalAlign: '-2px' }} /> {String(h).padStart(2, '0')}:{String(m).padStart(2, '0')}:{String(s).padStart(2, '0')}</span>;
}

/**
 * Customer lookup — search by first name, last name, email, phone, or exact
 * customer id (all handled by /auth/admin/search-customers). Replaces the old
 * raw "Customer ID" number box: operators identify people, not ids.
 */
function CustomerLookup({ selected, onSelect }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const debounceRef = useRef(null);
  const boxRef = useRef(null);

  useEffect(() => {
    const onDocClick = (e) => {
      if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, []);

  const runSearch = useCallback((q) => {
    if (!q || q.trim().length < 2) { setResults([]); return; }
    setSearching(true);
    authService.searchCustomers(q.trim())
      .then((res) => {
        const d = res.data?.data;
        setResults(toArray(d?.content || d).slice(0, 8));
        setOpen(true);
      })
      .catch(() => setResults([]))
      .finally(() => setSearching(false));
  }, []);

  const onChange = (e) => {
    const q = e.target.value;
    setQuery(q);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => runSearch(q), 300);
  };

  if (selected) {
    return (
      <div className="adm-lookup-selected" style={{
        display: 'flex', alignItems: 'center', gap: '0.5rem',
        padding: '0.45rem 0.6rem', border: '1px solid var(--border)', borderRadius: 6,
      }}>
        <FiUser />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 600 }}>
            {`${selected.firstName || ''} ${selected.lastName || ''}`.trim() || selected.email}
            <span style={{ color: 'var(--text-secondary)', fontWeight: 400 }}> · #{selected.id}</span>
          </div>
          <div style={{ fontSize: '0.8em', color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {selected.email}{selected.phone ? ` · ${selected.phoneCountryCode || ''} ${selected.phone}` : ''}
          </div>
        </div>
        <button type="button" className="btn btn-secondary btn-sm" aria-label="Clear selected customer"
          onClick={() => { onSelect(null); setQuery(''); setResults([]); }}>
          <FiX />
        </button>
      </div>
    );
  }

  return (
    <div ref={boxRef} style={{ position: 'relative' }}>
      <div style={{ position: 'relative' }}>
        <FiSearch style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-secondary)' }} />
        <input
          type="search"
          value={query}
          onChange={onChange}
          onFocus={() => results.length > 0 && setOpen(true)}
          placeholder="Name, email, phone, or customer ID…"
          style={{ width: '100%', paddingLeft: '2rem' }}
          aria-label="Search customer"
        />
      </div>
      {open && (
        <div style={{
          position: 'absolute', zIndex: 30, top: '100%', left: 0, right: 0, marginTop: 4,
          background: 'var(--surface, var(--bg-secondary, #fff))',
          border: '1px solid var(--border)', borderRadius: 6,
          boxShadow: '0 6px 20px rgba(0,0,0,0.15)', maxHeight: 280, overflowY: 'auto',
        }}>
          {searching && <div style={{ padding: '0.6rem', color: 'var(--text-secondary)' }}>Searching…</div>}
          {!searching && results.length === 0 && (
            <div style={{ padding: '0.6rem', color: 'var(--text-secondary)' }}>No matching customers.</div>
          )}
          {!searching && results.map((c) => (
            <button key={c.id} type="button"
              onClick={() => { onSelect(c); setOpen(false); }}
              style={{
                display: 'block', width: '100%', textAlign: 'left', padding: '0.5rem 0.7rem',
                background: 'none', border: 'none', borderBottom: '1px solid var(--border)',
                cursor: 'pointer', color: 'inherit',
              }}>
              <div style={{ fontWeight: 600 }}>
                {`${c.firstName || ''} ${c.lastName || ''}`.trim() || c.email}
                <span style={{ color: 'var(--text-secondary)', fontWeight: 400 }}> · #{c.id}</span>
              </div>
              <div style={{ fontSize: '0.8em', color: 'var(--text-secondary)' }}>
                {c.email}{c.phone ? ` · ${c.phoneCountryCode || ''} ${c.phone}` : ''}
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export default function AdminCustomerFreezes() {
  const { selectedBinge } = useBinge();
  const confirm = useConfirm();
  const [freezes, setFreezes] = useState([]);
  const [loading, setLoading] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);
  const [creating, setCreating] = useState(false);
  const [customer, setCustomer] = useState(null);
  const [form, setForm] = useState({ durationMinutes: 60, reason: '' });

  const fetchFreezes = useCallback(async () => {
    if (!selectedBinge?.id) return;
    setLoading(true);
    try {
      const res = await adminService.listFreezes(selectedBinge.id, true);
      const fetchedAt = Date.now();
      setFreezes(toArray(res.data?.data).map((f) => ({
        ...f,
        deadlineMs: fetchedAt + Math.max(0, Number(f.secondsRemaining) || 0) * 1000,
      })));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load freezes');
      setFreezes([]);
    } finally {
      setLoading(false);
    }
  }, [selectedBinge?.id]);

  useEffect(() => { fetchFreezes(); }, [fetchFreezes, refreshTick]);

  const customerLabel = (f) =>
    f.customerName
      ? `${f.customerName} (#${f.customerId})`
      : `Customer #${f.customerId}`;

  const handleLift = async (freeze) => {
    const result = await confirm({
      title: `Lift freeze for ${customerLabel(freeze)}?`,
      message: 'The customer will regain immediate access to bookings. The lift action and reason are recorded in the audit log.',
      confirmLabel: 'Lift freeze',
      variant: 'primary',
      withReason: true,
      reasonRequired: false,
      reasonLabel: 'Reason (optional)',
      reasonPlaceholder: 'e.g. Verified contact, customer escalation…',
    });
    if (!result) return;
    try {
      await adminService.liftFreeze(freeze.id, result.reason || 'Lifted by admin');
      toast.success('Freeze lifted');
      setRefreshTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to lift freeze');
    }
  };

  const handleCreate = async (e) => {
    e.preventDefault();
    if (!selectedBinge?.id) return;
    if (!customer?.id) { toast.error('Look up and select a customer first'); return; }
    setCreating(true);
    try {
      await adminService.createFreeze({
        customerId: customer.id,
        bingeId: selectedBinge.id,
        durationMinutes: Number(form.durationMinutes) || 60,
        reason: form.reason || 'Manual freeze',
      });
      toast.success(`Manual freeze applied to ${`${customer.firstName || ''} ${customer.lastName || ''}`.trim() || `#${customer.id}`}`);
      setCustomer(null);
      setForm({ durationMinutes: 60, reason: '' });
      setRefreshTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to apply freeze');
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiLock /> Customer Booking Freezes</h1>
          <p>Active booking-flow freezes at <strong>{selectedBinge?.name || '—'}</strong>. Freezes block customers from creating new bookings while support investigates abuse signals.</p>
        </div>
        <button type="button" className="btn btn-secondary" onClick={() => setRefreshTick(t => t + 1)} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {/* ── Apply manual freeze ───────────────────────────────── */}
      <section className="adm-form adm-flow-card" style={{ marginBottom: '1.5rem' }}>
        <h3 style={{ marginTop: 0 }}><FiPlus /> Apply manual freeze</h3>
        <form onSubmit={handleCreate} style={{ display: 'grid', gridTemplateColumns: 'minmax(260px, 2fr) minmax(140px, 1fr) minmax(180px, 2fr) auto', gap: '0.75rem', alignItems: 'end' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <span>Customer</span>
            <CustomerLookup selected={customer} onSelect={setCustomer} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <span>Duration (minutes)</span>
            <input type="number" min="1" max="10080" value={form.durationMinutes} onChange={(e) => setForm(f => ({ ...f, durationMinutes: e.target.value }))} required />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <span>Reason</span>
            <input type="text" maxLength={500} value={form.reason} onChange={(e) => setForm(f => ({ ...f, reason: e.target.value }))} placeholder="e.g. Suspected booking abuse" />
          </label>
          <button type="submit" className="btn btn-primary" disabled={creating || !customer}>{creating ? 'Applying…' : 'Apply Freeze'}</button>
        </form>
      </section>

      {/* ── Active freezes list ───────────────────────────────── */}
      <section className="adm-flow-card">
        <h3 style={{ marginTop: 0 }}>Active freezes ({freezes.length})</h3>
        {loading ? (
          <div className="admin-loading">Loading…</div>
        ) : freezes.length === 0 ? (
          <div className="admin-empty-state">No active freezes at this binge.</div>
        ) : (
          <div className="adm-table-wrap">
            <table className="adm-table" style={{ width: '100%' }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>Customer</th>
                  <th style={{ textAlign: 'left' }}>Trigger</th>
                  <th style={{ textAlign: 'left' }}>Reason</th>
                  <th style={{ textAlign: 'left' }}>Time remaining</th>
                  <th style={{ textAlign: 'left' }}>Until</th>
                  <th style={{ width: '120px' }} />
                </tr>
              </thead>
              <tbody>
                {freezes.map(f => (
                  <tr key={f.id}>
                    <td>
                      <div style={{ fontWeight: 600 }}>{f.customerName || `Customer #${f.customerId}`}</div>
                      <div style={{ fontSize: '0.8em', color: 'var(--text-secondary)' }}>
                        {f.customerEmail ? `${f.customerEmail} · ` : ''}#{f.customerId}
                      </div>
                    </td>
                    <td>
                      <span className="badge badge-warning">{TRIGGER_LABELS[f.triggerType] || f.triggerType}</span>
                    </td>
                    <td>{f.reason || '—'}</td>
                    <td><Countdown deadlineMs={f.deadlineMs} /></td>
                    <td style={{ fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                      {formatServerDateTime(f.freezeUntil)}
                    </td>
                    <td>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleLift(f)}>
                        <FiUnlock /> Lift
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
