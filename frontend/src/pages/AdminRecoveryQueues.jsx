import { useEffect, useState, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiRefreshCw, FiAlertCircle, FiClock, FiCheckCircle, FiUserX, FiPlay, FiX, FiActivity } from 'react-icons/fi';
import { useAuth } from '../context/AuthContext';
import { useConfirm } from '../components/ui/ConfirmProvider';
import './AdminPages.css';

/**
 * Recovery-first ops console. Each tab surfaces a specific failure mode and
 * the remediation tool that resolves it. All actions are idempotent on the
 * server and audited via BookingEventLog / AuditLog.
 */
const BASE_TABS = [
  { key: 'stuck-pending',      label: 'Stuck pending',     icon: FiClock,       hint: 'PENDING + payment never started' },
  { key: 'expired-holds',      label: 'Expired holds',     icon: FiAlertCircle, hint: 'Hold TTL elapsed but not released' },
  { key: 'paid-not-confirmed', label: 'Paid · not confirmed', icon: FiCheckCircle, hint: 'Payment OK but saga stuck' },
  { key: 'no-show',            label: 'No-show',           icon: FiUserX,       hint: 'Booking ended NO_SHOW' },
];
const SAGA_TAB = { key: 'sagas', label: 'Sagas', icon: FiActivity, hint: 'Compensating / failed orchestration sagas (super-admin)' };

const fmt = (v) => v == null ? '—' : (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v))
  ? new Date(v).toLocaleString()
  : String(v);

export default function AdminRecoveryQueues() {
  const { isSuperAdmin } = useAuth();
  const confirm = useConfirm();
  const TABS = isSuperAdmin ? [...BASE_TABS, SAGA_TAB] : BASE_TABS;

  const [tab, setTab] = useState('stuck-pending');
  const [data, setData] = useState({ rows: [], total: 0, page: 0, size: 50 });
  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState(null);
  const [busyRow, setBusyRow] = useState(null);
  const [tick, setTick] = useState(0);

  // Saga monitoring state — only fetched when the Sagas tab is active.
  const [sagas, setSagas] = useState({ failed: [], compensating: [] });

  // ── Conversion / abandonment funnel ────────────────────
  const today = new Date();
  const isoDate = (d) => d.toISOString().slice(0, 10);
  const initialFrom = (() => { const d = new Date(today); d.setDate(d.getDate() - 7); return isoDate(d); })();
  const [funnelFrom, setFunnelFrom] = useState(initialFrom);
  const [funnelTo, setFunnelTo] = useState(isoDate(today));
  const [funnel, setFunnel] = useState(null);
  const [funnelLoading, setFunnelLoading] = useState(false);

  const fetchFunnel = useCallback(async () => {
    setFunnelLoading(true);
    try {
      const res = await adminService.getRecoveryFunnel({ from: funnelFrom, to: funnelTo });
      setFunnel(res.data?.data || null);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load funnel');
      setFunnel(null);
    } finally { setFunnelLoading(false); }
  }, [funnelFrom, funnelTo]);

  useEffect(() => { fetchFunnel(); }, [fetchFunnel, tick]);

  const fetchSummary = useCallback(async () => {
    try {
      const res = await adminService.getRecoverySummary();
      setSummary(res.data?.data || null);
    } catch { /* non-blocking */ }
  }, []);

  const fetchPage = useCallback(async () => {
    if (tab === 'sagas') return; // sagas tab uses its own loader
    setLoading(true);
    try {
      const params = { page: data.page, size: data.size };
      let res;
      if (tab === 'stuck-pending')          res = await adminService.getRecoveryStuckPending(params);
      else if (tab === 'expired-holds')     res = await adminService.getRecoveryExpiredHolds(params);
      else if (tab === 'paid-not-confirmed') res = await adminService.getRecoveryPaidNotConfirmed(params);
      else                                   res = await adminService.getRecoveryNoShow(params);
      const d = res.data?.data || {};
      setData({
        rows: Array.isArray(d.rows) ? d.rows : [],
        total: d.total || 0,
        page: d.page || 0,
        size: d.size || 50,
      });
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load recovery queue');
      setData({ rows: [], total: 0, page: 0, size: 50 });
    } finally {
      setLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, tick]);

  // Sagas: parallel-fetch failed + compensating from booking-service. Both
  // endpoints require ROLE_SUPER_ADMIN at the gateway, so we don't even
  // attempt the fetch unless the tab is currently the saga tab.
  const fetchSagas = useCallback(async () => {
    if (tab !== 'sagas' || !isSuperAdmin) return;
    setLoading(true);
    try {
      const [failedRes, compRes] = await Promise.all([
        adminService.getFailedSagas(),
        adminService.getCompensatingSagas(),
      ]);
      const failed = failedRes?.data?.data || failedRes?.data || [];
      const comp   = compRes?.data?.data   || compRes?.data   || [];
      setSagas({
        failed: Array.isArray(failed) ? failed : [],
        compensating: Array.isArray(comp) ? comp : [],
      });
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load sagas');
      setSagas({ failed: [], compensating: [] });
    } finally {
      setLoading(false);
    }
  }, [tab, isSuperAdmin]);

  useEffect(() => { fetchSagas(); }, [fetchSagas, tick]);

  useEffect(() => { fetchSummary(); }, [fetchSummary, tick]);
  useEffect(() => { fetchPage(); }, [fetchPage]);

  const releaseHold = async (token) => {
    const result = await confirm({
      title: `Release stale hold ${token.substring(0, 8)}…?`,
      message: 'The slot will become available again immediately. The customer will lose their hold.',
      confirmLabel: 'Release hold',
      variant: 'danger',
      withReason: true,
      reasonRequired: false,
      reasonLabel: 'Reason (optional)',
      reasonPlaceholder: 'ADMIN_RECOVERY_RELEASE',
    });
    if (!result) return;
    const reason = result.reason || 'ADMIN_RECOVERY_RELEASE';
    setBusyRow(token);
    try {
      await adminService.releaseStaleHold(token, reason || 'ADMIN_RECOVERY_RELEASE');
      toast.success(`Hold ${token.substring(0, 8)}… released`);
      setTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Release failed');
    } finally { setBusyRow(null); }
  };

  const cancelStuck = async (ref) => {
    const result = await confirm({
      title: `Force-cancel stuck booking ${ref}?`,
      message: 'This runs full compensation: the slot is released and the booking is refunded if a payment was captured. The customer is notified. This action cannot be undone.',
      confirmLabel: 'Force cancel',
      variant: 'danger',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Cancellation reason',
      reasonPlaceholder: 'Admin recovery: stuck-pending cancellation',
    });
    if (!result) return;
    const reason = result.reason;
    setBusyRow(ref);
    try {
      await adminService.cancelStuckPending(ref, reason);
      toast.success(`Booking ${ref} cancelled`);
      setTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Cancel failed');
    } finally { setBusyRow(null); }
  };

  const replayConfirm = async (ref) => {
    const ok = await confirm({
      title: `Replay confirmation for ${ref}?`,
      message: 'Re-runs the post-payment status decision. Safe to retry: idempotent on the server. The booking will move to CONFIRMED only if payment evidence is now present.',
      confirmLabel: 'Replay confirmation',
      variant: 'primary',
    });
    if (!ok) return;
    setBusyRow(ref);
    try {
      const res = await adminService.replayPaidNotConfirmed(ref);
      toast.success(`Replay complete · status=${res.data?.data?.status}`);
      setTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Replay failed');
    } finally { setBusyRow(null); }
  };

  // Saga → booking projection replay. Same endpoint as the per-booking
  // "Replay" action in the booking support drawer. Idempotent server-side.
  const replaySaga = async (ref) => {
    if (!ref) { toast.error('Saga has no booking reference'); return; }
    const ok = await confirm({
      title: `Replay booking projection for ${ref}?`,
      message: 'Re-runs the read-model rebuild for that booking. Idempotent server-side and safe to retry.',
      confirmLabel: 'Replay projection',
      variant: 'primary',
    });
    if (!ok) return;
    setBusyRow(ref);
    try {
      const res = await adminService.replayBooking(ref);
      const msg = res?.data?.data || res?.data?.message || 'Replay queued';
      toast.success(String(msg));
      setTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Replay failed');
    } finally { setBusyRow(null); }
  };

  const SummaryBadge = ({ label, value, alert }) => (
    <div style={{
      padding: '0.6rem 0.9rem', borderRadius: 8, minWidth: 120,
      background: alert ? 'rgba(239, 68, 68, 0.12)' : 'var(--surface-2, #1f2937)',
      border: `1px solid ${alert ? 'rgba(239, 68, 68, 0.4)' : 'var(--border)'}`,
    }}>
      <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ fontSize: '1.5rem', fontWeight: 600, color: alert ? '#ef4444' : 'var(--text-primary)' }}>{value ?? '—'}</div>
    </div>
  );

  return (
    <div className="admin-page" style={{ maxWidth: 1280, margin: '0 auto', padding: '1.25rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem', flexWrap: 'wrap', gap: '0.75rem' }}>
        <div>
          <h1 style={{ margin: 0 }}>Recovery queues</h1>
          <p style={{ margin: '0.25rem 0 0', color: 'var(--text-secondary)' }}>
            Failure-mode triage. Each row is a booking that needs human review.
          </p>
        </div>
        <button className="btn-secondary" onClick={() => setTick(t => t + 1)} disabled={loading}>
          <FiRefreshCw style={{ verticalAlign: '-2px', marginRight: 6 }} /> Refresh
        </button>
      </div>

      {summary && (
        <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
          <SummaryBadge label="Stuck pending"      value={summary.stuckPending}      alert={summary.stuckPending > 0} />
          <SummaryBadge label="Expired holds"      value={summary.expiredHolds}      alert={summary.expiredHolds > 0} />
          <SummaryBadge label="Paid · not confirmed" value={summary.paidNotConfirmed} alert={summary.paidNotConfirmed > 0} />
          <SummaryBadge label="No-show (7d)"       value={summary.noShowLast7d} />
          <SummaryBadge label="Status"             value={summary.status} alert={summary.status !== 'OK'} />
        </div>
      )}

      <FunnelSection
        from={funnelFrom} to={funnelTo}
        onFrom={setFunnelFrom} onTo={setFunnelTo}
        funnel={funnel} loading={funnelLoading}
        onRefresh={fetchFunnel}
      />

      <div role="tablist" style={{ display: 'flex', gap: '0.5rem', borderBottom: '1px solid var(--border)', marginBottom: '0.75rem', flexWrap: 'wrap' }}>
        {TABS.map(t => {
          const Icon = t.icon;
          const active = t.key === tab;
          return (
            <button
              key={t.key}
              role="tab"
              aria-selected={active}
              onClick={() => { setTab(t.key); setData(d => ({ ...d, page: 0 })); }}
              style={{
                padding: '0.55rem 0.9rem', border: 'none', cursor: 'pointer',
                background: 'transparent',
                color: active ? 'var(--primary)' : 'var(--text-secondary)',
                borderBottom: `2px solid ${active ? 'var(--primary)' : 'transparent'}`,
                fontWeight: active ? 600 : 400,
              }}
              title={t.hint}
            >
              <Icon style={{ verticalAlign: '-2px', marginRight: 6 }} />{t.label}
            </button>
          );
        })}
      </div>

      <div className="adm-table-wrap">
        {tab === 'sagas' ? (
          loading ? (
            <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading…</div>
          ) : (
            <SagaPanel
              compensating={sagas.compensating}
              failed={sagas.failed}
              busyRow={busyRow}
              onReplay={replaySaga}
            />
          )
        ) : loading ? (
          <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading…</div>
        ) : data.rows.length === 0 ? (
          <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
            No items in this queue. <span style={{ color: '#10b981' }}>✓</span>
          </div>
        ) : (
          <RecoveryTable tab={tab} rows={data.rows} busyRow={busyRow}
            onRelease={releaseHold} onCancel={cancelStuck} onReplay={replayConfirm} />
        )}
      </div>

      {tab !== 'sagas' && data.total > data.size && (
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '0.75rem' }}>
          <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
            Page {data.page + 1} of {Math.ceil(data.total / data.size)} · {data.total} total
          </span>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button className="btn-secondary" disabled={data.page === 0}
              onClick={() => setData(d => ({ ...d, page: Math.max(0, d.page - 1) }))}>Prev</button>
            <button className="btn-secondary"
              disabled={(data.page + 1) * data.size >= data.total}
              onClick={() => setData(d => ({ ...d, page: d.page + 1 }))}>Next</button>
          </div>
        </div>
      )}
    </div>
  );
}

function RecoveryTable({ tab, rows, busyRow, onRelease, onCancel, onReplay }) {
  if (tab === 'expired-holds') {
    return (
      <table className="adm-table" style={{ width: '100%' }}>
        <thead><tr>
          <th>Token</th><th>Customer</th><th>Date</th><th>Expired</th><th>Overdue (min)</th><th></th>
        </tr></thead>
        <tbody>{rows.map(r => (
          <tr key={r.id}>
            <td><code>{(r.holdToken || '').substring(0, 12)}…</code></td>
            <td>{r.customerId}</td>
            <td>{fmt(r.bookingDate)}</td>
            <td>{fmt(r.expiresAt)}</td>
            <td>{r.overdueMinutes}</td>
            <td><button className="btn-danger" disabled={busyRow === r.holdToken}
              onClick={() => onRelease(r.holdToken)}>
              <FiX style={{ verticalAlign: '-2px' }} /> Release
            </button></td>
          </tr>
        ))}</tbody>
      </table>
    );
  }
  if (tab === 'stuck-pending') {
    return (
      <table className="adm-table" style={{ width: '100%' }}>
        <thead><tr>
          <th>Booking ref</th><th>Customer</th><th>Email</th><th>Amount</th><th>Created</th><th>Age (min)</th><th></th>
        </tr></thead>
        <tbody>{rows.map(r => (
          <tr key={r.bookingRef}>
            <td><code>{r.bookingRef}</code></td>
            <td>{r.customerId}</td>
            <td>{r.customerEmail || '—'}</td>
            <td>{r.amount}</td>
            <td>{fmt(r.createdAt)}</td>
            <td>{r.ageMinutes}</td>
            <td><button className="btn-danger" disabled={busyRow === r.bookingRef}
              onClick={() => onCancel(r.bookingRef)}>
              <FiX style={{ verticalAlign: '-2px' }} /> Cancel
            </button></td>
          </tr>
        ))}</tbody>
      </table>
    );
  }
  if (tab === 'paid-not-confirmed') {
    return (
      <table className="adm-table" style={{ width: '100%' }}>
        <thead><tr>
          <th>Booking ref</th><th>Customer</th><th>Amount</th><th>Collected</th><th>Paid at</th><th>Stale (min)</th><th></th>
        </tr></thead>
        <tbody>{rows.map(r => (
          <tr key={r.bookingRef}>
            <td><code>{r.bookingRef}</code></td>
            <td>{r.customerId} · {r.customerEmail || '—'}</td>
            <td>{r.amount}</td>
            <td>{r.collectedAmount}</td>
            <td>{fmt(r.paidAt)}</td>
            <td>{r.staleMinutes}</td>
            <td><button className="btn-primary" disabled={busyRow === r.bookingRef}
              onClick={() => onReplay(r.bookingRef)}>
              <FiPlay style={{ verticalAlign: '-2px' }} /> Replay
            </button></td>
          </tr>
        ))}</tbody>
      </table>
    );
  }
  // no-show — read-only follow-up
  return (
    <table className="adm-table" style={{ width: '100%' }}>
      <thead><tr>
        <th>Booking ref</th><th>Customer</th><th>Date</th><th>Start</th><th>Amount</th><th>Collected</th>
      </tr></thead>
      <tbody>{rows.map(r => (
        <tr key={r.bookingRef}>
          <td><code>{r.bookingRef}</code></td>
          <td>{r.customerId} · {r.customerEmail || '—'}</td>
          <td>{fmt(r.bookingDate)}</td>
          <td>{r.startTime}</td>
          <td>{r.amount}</td>
          <td>{r.collectedAmount}</td>
        </tr>
      ))}</tbody>
    </table>
  );
}

/**
 * Saga triage panel — read-only listing of compensating and failed
 * orchestration sagas with a per-row "Replay" action that re-runs the
 * booking projection (the same idempotent endpoint exposed in the booking
 * support drawer). The lists come from the in-memory orchestrator, so this
 * tab is a snapshot — refresh after each remediation to confirm the saga
 * cleared.
 */
function SagaPanel({ compensating, failed, busyRow, onReplay }) {
  const renderTable = (rows, kind) => {
    if (!rows.length) {
      return (
        <div style={{ padding: '1.25rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
          No {kind} sagas. <span style={{ color: '#10b981' }}>✓</span>
        </div>
      );
    }
    return (
      <table className="adm-table" style={{ width: '100%' }}>
        <thead><tr>
          <th>Booking ref</th><th>Status</th><th>Step</th><th>Last error</th><th>Updated</th><th></th>
        </tr></thead>
        <tbody>{rows.map(r => {
          const ref = r.bookingRef || r.bookingReference || r.aggregateId || r.sagaId;
          return (
            <tr key={r.sagaId || r.id || ref}>
              <td><code>{ref || '—'}</code></td>
              <td>{r.status || r.state || '—'}</td>
              <td>{r.currentStep || r.step || '—'}</td>
              <td style={{ maxWidth: 380, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  title={r.lastError || r.errorMessage || ''}>
                {r.lastError || r.errorMessage || '—'}
              </td>
              <td>{fmt(r.updatedAt || r.lastUpdated)}</td>
              <td>
                {kind === 'failed' && ref ? (
                  <button className="btn-primary" disabled={busyRow === ref}
                    onClick={() => onReplay(ref)}>
                    <FiPlay style={{ verticalAlign: '-2px' }} /> Replay
                  </button>
                ) : null}
              </td>
            </tr>
          );
        })}</tbody>
      </table>
    );
  };

  return (
    <div>
      <section style={{ marginBottom: '1rem' }}>
        <h3 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem' }}>
          Compensating <span style={{ color: 'var(--text-muted, var(--text-secondary))', fontWeight: 500 }}>({compensating.length})</span>
        </h3>
        <p style={{ margin: '0 0 0.6rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
          Sagas mid-rollback. Read-only — let them complete; replay only after they reach FAILED.
        </p>
        {renderTable(compensating, 'compensating')}
      </section>
      <section>
        <h3 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem', color: 'var(--danger, #ef4444)' }}>
          Failed <span style={{ color: 'var(--text-muted, var(--text-secondary))', fontWeight: 500 }}>({failed.length})</span>
        </h3>
        <p style={{ margin: '0 0 0.6rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
          Saga reached terminal failure. Replay rebuilds the booking read-model so admins can see the corrected state.
        </p>
        {renderTable(failed, 'failed')}
      </section>
    </div>
  );
}

/**
 * Conversion / abandonment funnel for the selected binge over a date range.
 * Read-only analytics — no actions, just visibility into where bookings drop
 * off in the checkout flow.
 */
function FunnelSection({ from, to, onFrom, onTo, funnel, loading, onRefresh }) {
  const pct = (n, d) => d > 0 ? `${((n / d) * 100).toFixed(1)}%` : '—';
  const Bar = ({ label, value, base, color }) => {
    const w = base > 0 ? Math.max(2, Math.min(100, (value / base) * 100)) : 0;
    return (
      <div style={{ marginBottom: '0.5rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.85rem', marginBottom: 4 }}>
          <span>{label}</span>
          <span style={{ color: 'var(--text-secondary)' }}>{value} · {pct(value, base)}</span>
        </div>
        <div style={{ height: 8, background: 'var(--surface-2, #1f2937)', borderRadius: 4, overflow: 'hidden' }}>
          <div style={{ width: `${w}%`, height: '100%', background: color, transition: 'width 0.3s' }} />
        </div>
      </div>
    );
  };
  const started = funnel?.started ?? 0;
  return (
    <section style={{
      marginBottom: '1.25rem', padding: '1rem', borderRadius: 8,
      background: 'var(--surface-1, #111827)', border: '1px solid var(--border)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.75rem', marginBottom: '0.75rem' }}>
        <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Conversion funnel</h2>
        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <label style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>From{' '}
            <input type="date" value={from} onChange={e => onFrom(e.target.value)} max={to} />
          </label>
          <label style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>To{' '}
            <input type="date" value={to} onChange={e => onTo(e.target.value)} min={from} />
          </label>
          <button className="btn-secondary" onClick={onRefresh} disabled={loading}>
            <FiRefreshCw style={{ verticalAlign: '-2px', marginRight: 4 }} /> Apply
          </button>
        </div>
      </div>

      {loading && <div style={{ color: 'var(--text-secondary)' }}>Loading funnel…</div>}
      {!loading && !funnel && <div style={{ color: 'var(--text-secondary)' }}>Select a binge to view funnel.</div>}
      {!loading && funnel && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1.25rem' }}>
          <div>
            <h3 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em', margin: '0 0 0.5rem' }}>Stages</h3>
            <Bar label="Started"   value={funnel.started}   base={started} color="#3b82f6" />
            <Bar label="Paid"      value={funnel.paid}      base={started} color="#06b6d4" />
            <Bar label="Confirmed" value={funnel.confirmed} base={started} color="#10b981" />
            <Bar label="Completed" value={funnel.completed} base={started} color="#22c55e" />
          </div>
          <div>
            <h3 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em', margin: '0 0 0.5rem' }}>Drop-offs</h3>
            <Bar label="Abandoned (auto-cancel)" value={funnel.abandonedAuto}       base={started} color="#f59e0b" />
            <Bar label="Cancelled by customer"   value={funnel.cancelledByCustomer} base={started} color="#ef4444" />
            <Bar label="No-show"                 value={funnel.noShow}              base={started} color="#a855f7" />
          </div>
          <div>
            <h3 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em', margin: '0 0 0.5rem' }}>Rates</h3>
            <div style={{ padding: '0.75rem', background: 'var(--surface-2, #1f2937)', borderRadius: 6, marginBottom: '0.5rem' }}>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Conversion (started → confirmed)</div>
              <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#10b981' }}>
                {((funnel.conversionRate || 0) * 100).toFixed(1)}%
              </div>
            </div>
            <div style={{ padding: '0.75rem', background: 'var(--surface-2, #1f2937)', borderRadius: 6 }}>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Abandonment (auto-cancel)</div>
              <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#f59e0b' }}>
                {((funnel.abandonmentRate || 0) * 100).toFixed(1)}%
              </div>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
