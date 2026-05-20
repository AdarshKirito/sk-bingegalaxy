import { useEffect, useState, useCallback, useRef } from 'react';
import { toast } from 'react-toastify';
import {
  FiActivity, FiAlertTriangle, FiPlay, FiRefreshCw, FiZap,
  FiTool, FiClock, FiCheckCircle, FiXCircle, FiDatabase,
} from 'react-icons/fi';
import { adminService, toArray } from '../services/endpoints';
import './AdminPages.css';

/**
 * Super-admin operations console.
 *
 * Backed by the booking-service /admin/ops/* endpoints which require
 * ROLE_SUPER_ADMIN. Three concerns:
 *
 *   1. DLT replay   \u2014 push messages off a dead-letter Kafka topic back onto
 *                       the live topic. Topic must be in the server allow-list.
 *   2. Outbox retry \u2014 reset failedPermanent=false on transactional-outbox
 *                       rows so the publisher picks them up again. Either a
 *                       single row or all permanently-failed rows.
 *   3. Health pulse \u2014 the snapshot returned by /admin/ops/health, which
 *                       summarises poison counts + stuck rows.
 *
 * The page is intentionally restrained \u2014 these are dangerous buttons. Each
 * action is gated behind window.confirm with an explicit message describing
 * what will happen.
 */

const TABS = [
  { id: 'health',  label: 'Health',       icon: <FiActivity /> },
  { id: 'dlt',     label: 'DLT replay',   icon: <FiZap /> },
  { id: 'outbox',  label: 'Outbox retry', icon: <FiTool /> },
];

// Server enforces the real allow-list. We mirror the canonical topic names
// here so admins don\u2019t have to memorise them; unknown topics are still
// rejected by the controller.
const KNOWN_DLT_TOPICS = [
  'booking.events.dlt',
  'payment.events.dlt',
  'notification.events.dlt',
  'availability.events.dlt',
  'waitlist.events.dlt',
];

export default function AdminOps() {
  const [tab, setTab] = useState('health');

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiTool /> Operations</h1>
          <p>Maintenance tools for poisoned messages, stuck outbox rows, and platform health.</p>
        </div>
      </div>

      <div role="tablist" style={{
        display: 'flex', gap: '0.5rem',
        borderBottom: '1px solid var(--border)',
        marginBottom: '1rem', flexWrap: 'wrap',
      }}>
        {TABS.map(t => {
          const active = t.id === tab;
          return (
            <button
              key={t.id}
              role="tab"
              aria-selected={active}
              onClick={() => setTab(t.id)}
              style={{
                padding: '0.6rem 1rem', border: 'none', cursor: 'pointer',
                background: 'transparent',
                color: active ? 'var(--primary)' : 'var(--text-secondary)',
                borderBottom: `2px solid ${active ? 'var(--primary)' : 'transparent'}`,
                fontWeight: active ? 600 : 500,
                display: 'inline-flex', alignItems: 'center', gap: '0.4rem',
              }}
            >
              {t.icon} {t.label}
            </button>
          );
        })}
      </div>

      {tab === 'health' && <HealthPanel />}
      {tab === 'dlt'    && <DltPanel />}
      {tab === 'outbox' && <OutboxPanel />}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Health
// ─────────────────────────────────────────────────────────────
function HealthPanel() {
  const [snapshot, setSnapshot] = useState(null);
  const [loading, setLoading] = useState(true);
  const [auto, setAuto] = useState(true);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [rebuildBusy, setRebuildBusy] = useState(false);
  const [rebuildResult, setRebuildResult] = useState(null);
  const timerRef = useRef(null);

  // Full CQRS projection rebuild. Replays every booking row through the
  // projection service, restoring read-model parity after a corruption
  // incident. Long-running and write-heavy — gated behind a hard confirm
  // and disabled while in flight to prevent double-submits.
  const rebuildAllProjections = useCallback(async () => {
    const ok = window.confirm(
      'Rebuild ALL CQRS projections?\n\n'
      + 'This re-runs the booking projection for every booking in the system. '
      + 'It can take several minutes on large datasets and temporarily '
      + 'increases DB load. Use only when read-model drift is suspected '
      + '(e.g. after a controller hot-fix or restore-from-backup).'
    );
    if (!ok) return;
    setRebuildBusy(true);
    setRebuildResult(null);
    try {
      const res = await adminService.replayAll();
      const msg = res?.data?.data || res?.data?.message || 'Rebuild complete';
      setRebuildResult({ ok: true, msg: String(msg) });
      toast.success(String(msg));
    } catch (err) {
      const msg = err?.response?.data?.message || err?.message || 'Rebuild failed';
      setRebuildResult({ ok: false, msg });
      toast.error(msg);
    } finally {
      setRebuildBusy(false);
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.getOpsHealth();
      setSnapshot(res.data?.data || null);
      setLastUpdated(new Date());
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load health');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!auto) return undefined;
    timerRef.current = setInterval(load, 30_000);
    return () => clearInterval(timerRef.current);
  }, [auto, load]);

  const poisonRows = toArray(snapshot?.poisonRows);
  const dltCounts  = snapshot?.dltCounts && typeof snapshot.dltCounts === 'object' ? snapshot.dltCounts : {};
  const outboxStuck = snapshot?.outboxStuck ?? snapshot?.stuckCount ?? poisonRows.length;
  const totalDlt = Object.values(dltCounts).reduce((a, b) => a + (Number(b) || 0), 0);

  return (
    <>
      <div className="admin-toolbar">
        <div className="admin-toolbar-group">
          <label className="admin-toolbar-label">
            <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} />
            Auto-refresh every 30s
          </label>
          {lastUpdated && (
            <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
              <FiClock /> Updated {lastUpdated.toLocaleTimeString()}
            </span>
          )}
        </div>
        <div className="admin-toolbar-group" style={{ marginLeft: 'auto' }}>
          <button className="btn btn-secondary btn-sm" onClick={load} disabled={loading}>
            <FiRefreshCw /> Refresh
          </button>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '1rem', marginBottom: '1.25rem' }}>
        <StatCard label="Outbox stuck rows"   value={outboxStuck} severity={outboxStuck > 0 ? 'danger' : 'ok'} icon={<FiAlertTriangle />} />
        <StatCard label="DLT total messages"  value={totalDlt}    severity={totalDlt > 0 ? 'warning' : 'ok'}    icon={<FiZap />} />
        <StatCard label="Topics with DLT"     value={Object.keys(dltCounts).length} severity="info" icon={<FiActivity />} />
      </div>

      {Object.keys(dltCounts).length > 0 && (
        <section className="adm-card" style={{ padding: '1rem 1.1rem', marginBottom: '1.25rem' }}>
          <h3 style={{ margin: '0 0 0.6rem', fontSize: '0.95rem' }}>DLT depths by topic</h3>
          <div className="adm-table-wrap">
            <table className="adm-table">
              <thead><tr><th>Topic</th><th style={{ textAlign: 'right' }}>Messages</th></tr></thead>
              <tbody>
                {Object.entries(dltCounts).sort(([,a],[,b]) => (Number(b)||0) - (Number(a)||0)).map(([topic, count]) => (
                  <tr key={topic}>
                    <td><code style={{ fontSize: '0.85rem' }}>{topic}</code></td>
                    <td style={{ textAlign: 'right', fontWeight: 600 }}>{count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {poisonRows.length > 0 && (
        <section className="adm-card" style={{ padding: '1rem 1.1rem' }}>
          <h3 style={{ margin: '0 0 0.6rem', fontSize: '0.95rem' }}>Stuck outbox rows (sample)</h3>
          <div className="adm-table-wrap">
            <table className="adm-table">
              <thead><tr>
                <th>ID</th><th>Topic</th><th>Attempts</th><th>Last error</th><th>Created</th>
              </tr></thead>
              <tbody>
                {poisonRows.slice(0, 50).map(r => (
                  <tr key={r.id}>
                    <td><code style={{ fontSize: '0.8rem' }}>{r.id}</code></td>
                    <td><code style={{ fontSize: '0.8rem' }}>{r.topic || r.eventType || '\u2014'}</code></td>
                    <td>{r.attempts ?? '\u2014'}</td>
                    <td style={{ maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                        title={r.lastError || ''}>
                      {r.lastError || '\u2014'}
                    </td>
                    <td>{r.createdAt ? new Date(r.createdAt).toLocaleString() : '\u2014'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {!loading && totalDlt === 0 && outboxStuck === 0 && (
        <div className="admin-empty-state">
          <FiCheckCircle size={48} style={{ color: 'var(--success, #10b981)' }} />
          <h3>All clear</h3>
          <p>No poisoned messages and no stuck outbox rows. Nothing needs your attention right now.</p>
        </div>
      )}

      {/* ── CQRS projection rebuild (super-admin) ──────────────── */}
      <section className="adm-card" style={{ padding: '1rem 1.1rem', marginTop: '1.25rem', border: '1px solid rgba(239,68,68,0.35)' }}>
        <h3 style={{ margin: '0 0 0.4rem', fontSize: '0.95rem', display: 'inline-flex', alignItems: 'center', gap: '0.4rem' }}>
          <FiDatabase /> CQRS projections
        </h3>
        <p style={{ margin: '0 0 0.75rem', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
          Rebuild the booking read-model projections for every booking. Useful after a
          schema migration, a backup restore, or when admins notice stale data on
          listing pages. Per-booking replay is also available from the booking
          support drawer.
        </p>
        <div style={{ display: 'flex', gap: '0.6rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <button
            type="button"
            className="btn btn-danger btn-sm"
            onClick={rebuildAllProjections}
            disabled={rebuildBusy}
            style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem' }}
          >
            <FiRefreshCw className={rebuildBusy ? 'spin' : ''} />
            {rebuildBusy ? 'Rebuilding…' : 'Rebuild all projections'}
          </button>
          {rebuildResult && (
            <span style={{
              fontSize: '0.85rem',
              color: rebuildResult.ok ? 'var(--success, #10b981)' : 'var(--danger, #ef4444)',
            }}>
              {rebuildResult.ok ? '✓ ' : '✕ '}{rebuildResult.msg}
            </span>
          )}
        </div>
      </section>
    </>
  );
}

function StatCard({ label, value, severity = 'info', icon }) {
  const colorMap = {
    ok:      'var(--success, #10b981)',
    warning: 'var(--warning, #f59e0b)',
    danger:  'var(--danger,  #ef4444)',
    info:    'var(--primary, #3b82f6)',
  };
  return (
    <div className="adm-card" style={{ padding: '1rem 1.1rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: colorMap[severity], fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
        {icon} <span>{label}</span>
      </div>
      <div style={{ fontSize: '2rem', fontWeight: 700, marginTop: '0.25rem' }}>{value ?? 0}</div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// DLT replay
// ─────────────────────────────────────────────────────────────
function DltPanel() {
  const [topic, setTopic] = useState(KNOWN_DLT_TOPICS[0]);
  const [customTopic, setCustomTopic] = useState('');
  const [max, setMax] = useState(100);
  const [busy, setBusy] = useState(false);
  const [lastResult, setLastResult] = useState(null);

  const onReplay = async () => {
    const t = topic === '__custom__' ? customTopic.trim() : topic;
    if (!t) {
      toast.warn('Pick or type a DLT topic');
      return;
    }
    if (!window.confirm(
      `Replay up to ${max} messages from "${t}" back to the live topic?\n\n` +
      `Each replayed message will be re-consumed by every subscriber. ` +
      `If the original failure cause is still present they will land back in the DLT.`
    )) return;
    setBusy(true);
    try {
      const res = await adminService.replayDlt(t, max);
      const data = res.data?.data;
      setLastResult({ topic: t, ...data });
      toast.success(`Replayed ${data?.replayed ?? data?.count ?? '?'} messages`);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Replay failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(320px, 1fr) minmax(320px, 1fr)', gap: '1.25rem' }}>
      <section className="adm-card" style={{ padding: '1rem 1.1rem' }}>
        <h3 style={{ margin: '0 0 0.75rem', fontSize: '1rem' }}>
          <FiZap /> Replay dead-letter topic
        </h3>
        <p style={{ margin: '0 0 0.85rem', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
          Push poisoned messages back onto the live topic in batches. Use after fixing
          the consumer-side bug that caused the messages to fail.
        </p>

        <div className="adm-form-grid">
          <label className="adm-form-field" style={{ gridColumn: '1 / -1' }}>
            <span>Source topic</span>
            <select value={topic} onChange={(e) => setTopic(e.target.value)} disabled={busy}>
              {KNOWN_DLT_TOPICS.map(t => <option key={t} value={t}>{t}</option>)}
              <option value="__custom__">Custom\u2026</option>
            </select>
          </label>
          {topic === '__custom__' && (
            <label className="adm-form-field" style={{ gridColumn: '1 / -1' }}>
              <span>Custom topic name</span>
              <input
                type="text"
                value={customTopic}
                onChange={(e) => setCustomTopic(e.target.value)}
                placeholder="my-service.events.dlt"
                style={{ fontFamily: 'monospace' }}
                disabled={busy}
              />
            </label>
          )}
          <label className="adm-form-field">
            <span>Max messages</span>
            <input
              type="number"
              min={1}
              max={10000}
              value={max}
              onChange={(e) => setMax(Math.max(1, Number(e.target.value) || 1))}
              disabled={busy}
            />
          </label>
        </div>

        <div style={{ marginTop: '0.85rem' }}>
          <button className="btn btn-primary" onClick={onReplay} disabled={busy}>
            <FiPlay /> {busy ? 'Replaying\u2026' : 'Replay batch'}
          </button>
        </div>
      </section>

      <section className="adm-card" style={{ padding: '1rem 1.1rem' }}>
        <h3 style={{ margin: '0 0 0.75rem', fontSize: '1rem' }}>Last replay</h3>
        {lastResult ? (
          <div style={{ fontSize: '0.85rem' }}>
            <div><strong>Topic:</strong> <code>{lastResult.topic}</code></div>
            <div><strong>Replayed:</strong> {lastResult.replayed ?? lastResult.count ?? '\u2014'}</div>
            {lastResult.errors != null && <div><strong>Errors:</strong> {lastResult.errors}</div>}
            {lastResult.message && <div style={{ marginTop: '0.4rem', color: 'var(--text-secondary)' }}>{lastResult.message}</div>}
          </div>
        ) : (
          <p style={{ margin: 0, color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
            No replay run yet in this session.
          </p>
        )}
      </section>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Outbox retry
// ─────────────────────────────────────────────────────────────
function OutboxPanel() {
  const [snapshot, setSnapshot] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState(null);
  const [busyAll, setBusyAll] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.getOpsHealth();
      setSnapshot(res.data?.data || null);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load outbox snapshot');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const rows = toArray(snapshot?.poisonRows);

  const onRetryOne = async (row) => {
    if (!window.confirm(`Retry outbox row ${row.id}?\nIt will become eligible for the next publisher tick.`)) return;
    setBusyId(row.id);
    try {
      await adminService.retryOutbox(row.id);
      toast.success(`Row ${row.id} re-enabled`);
      await load();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Retry failed');
    } finally {
      setBusyId(null);
    }
  };

  const onRetryAll = async () => {
    if (!window.confirm(
      `Re-enable ALL ${rows.length} permanently-failed outbox rows?\n\n` +
      `Every row will be re-attempted on the next publisher tick. ` +
      `If the underlying error is unresolved they will fail again and be flagged.`
    )) return;
    setBusyAll(true);
    try {
      await adminService.retryOutbox();
      toast.success('All failed rows re-enabled');
      await load();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Retry-all failed');
    } finally {
      setBusyAll(false);
    }
  };

  return (
    <>
      <div className="admin-toolbar">
        <div className="admin-toolbar-group">
          <span className="admin-toolbar-label"><FiTool /> Stuck rows: <strong>{rows.length}</strong></span>
        </div>
        <div className="admin-toolbar-group" style={{ marginLeft: 'auto' }}>
          <button className="btn btn-secondary btn-sm" onClick={load} disabled={loading}>
            <FiRefreshCw /> Refresh
          </button>
          <button className="btn btn-primary btn-sm" onClick={onRetryAll} disabled={busyAll || rows.length === 0}>
            <FiPlay /> {busyAll ? 'Retrying\u2026' : 'Retry all'}
          </button>
        </div>
      </div>

      {loading ? (
        <div className="admin-loading">Loading outbox snapshot\u2026</div>
      ) : rows.length === 0 ? (
        <div className="admin-empty-state">
          <FiCheckCircle size={48} style={{ color: 'var(--success, #10b981)' }} />
          <h3>Outbox is healthy</h3>
          <p>No permanently-failed rows. The publisher is keeping up.</p>
        </div>
      ) : (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead><tr>
              <th>ID</th><th>Topic / event</th><th>Attempts</th><th>Last error</th><th>Created</th><th></th>
            </tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id}>
                  <td><code style={{ fontSize: '0.8rem' }}>{r.id}</code></td>
                  <td><code style={{ fontSize: '0.8rem' }}>{r.topic || r.eventType || '\u2014'}</code></td>
                  <td>{r.attempts ?? '\u2014'}</td>
                  <td style={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                      title={r.lastError || ''}>
                    {r.lastError ? <span style={{ color: 'var(--danger, #ef4444)' }}><FiXCircle /> {r.lastError}</span> : '\u2014'}
                  </td>
                  <td>{r.createdAt ? new Date(r.createdAt).toLocaleString() : '\u2014'}</td>
                  <td>
                    <button className="btn btn-sm btn-secondary" onClick={() => onRetryOne(r)} disabled={busyId === r.id}>
                      <FiPlay /> {busyId === r.id ? '\u2026' : 'Retry'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
