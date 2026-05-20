import { useEffect, useState, useCallback } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import { FiLock, FiUnlock, FiPlus, FiRefreshCw, FiClock } from 'react-icons/fi';
import { useBinge } from '../context/BingeContext';
import './AdminPages.css';

const TRIGGER_LABELS = {
  CUSTOMER_CANCELLATIONS: 'Repeated cancellations',
  PAYMENT_TIMEOUTS: 'Payment timeouts',
  MANUAL: 'Manual (admin)',
};

function Countdown({ until }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const target = new Date(until).getTime();
  const remaining = Math.max(0, target - now);
  if (remaining <= 0) return <span style={{ color: 'var(--text-secondary)' }}>Expired</span>;
  const totalSec = Math.floor(remaining / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  return <span><FiClock style={{ verticalAlign: '-2px' }} /> {String(h).padStart(2, '0')}:{String(m).padStart(2, '0')}:{String(s).padStart(2, '0')}</span>;
}

export default function AdminCustomerFreezes() {
  const { selectedBinge } = useBinge();
  const confirm = useConfirm();
  const [freezes, setFreezes] = useState([]);
  const [loading, setLoading] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ customerId: '', durationMinutes: 60, reason: '' });

  const fetchFreezes = useCallback(async () => {
    if (!selectedBinge?.id) return;
    setLoading(true);
    try {
      const res = await adminService.listFreezes(selectedBinge.id, true);
      setFreezes(toArray(res.data?.data));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load freezes');
      setFreezes([]);
    } finally {
      setLoading(false);
    }
  }, [selectedBinge?.id]);

  useEffect(() => { fetchFreezes(); }, [fetchFreezes, refreshTick]);

  const handleLift = async (freeze) => {
    const result = await confirm({
      title: `Lift freeze for customer #${freeze.customerId}?`,
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
    if (!form.customerId) { toast.error('Customer ID is required'); return; }
    setCreating(true);
    try {
      await adminService.createFreeze({
        customerId: Number(form.customerId),
        bingeId: selectedBinge.id,
        durationMinutes: Number(form.durationMinutes) || 60,
        reason: form.reason || 'Manual freeze',
      });
      toast.success('Manual freeze applied');
      setForm({ customerId: '', durationMinutes: 60, reason: '' });
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
        <form onSubmit={handleCreate} style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(180px, 1fr)) auto', gap: '0.75rem', alignItems: 'end' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <span>Customer ID</span>
            <input type="number" value={form.customerId} onChange={(e) => setForm(f => ({ ...f, customerId: e.target.value }))} required />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <span>Duration (minutes)</span>
            <input type="number" min="1" max="10080" value={form.durationMinutes} onChange={(e) => setForm(f => ({ ...f, durationMinutes: e.target.value }))} required />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <span>Reason</span>
            <input type="text" maxLength={500} value={form.reason} onChange={(e) => setForm(f => ({ ...f, reason: e.target.value }))} placeholder="e.g. Suspected booking abuse" />
          </label>
          <button type="submit" className="btn btn-primary" disabled={creating}>{creating ? 'Applying…' : 'Apply Freeze'}</button>
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
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Customer ID</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Trigger</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Reason</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Time remaining</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Until</th>
                <th style={{ width: '120px', borderBottom: '1px solid var(--border)' }} />
              </tr>
            </thead>
            <tbody>
              {freezes.map(f => (
                <tr key={f.id}>
                  <td style={{ padding: '0.5rem 0.6rem' }}>{f.customerId}</td>
                  <td style={{ padding: '0.5rem 0.6rem' }}>
                    <span className="badge badge-warning">{TRIGGER_LABELS[f.triggerType] || f.triggerType}</span>
                  </td>
                  <td style={{ padding: '0.5rem 0.6rem' }}>{f.reason || '—'}</td>
                  <td style={{ padding: '0.5rem 0.6rem' }}><Countdown until={f.freezeUntil} /></td>
                  <td style={{ padding: '0.5rem 0.6rem', fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                    {new Date(f.freezeUntil).toLocaleString()}
                  </td>
                  <td style={{ padding: '0.5rem 0.6rem' }}>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleLift(f)}>
                      <FiUnlock /> Lift
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
