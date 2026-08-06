import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiActivity, FiAlertOctagon, FiAlertTriangle, FiInfo, FiCheckCircle } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Distribution health overview (slice 7).
 *
 * <p><b>Named problems, not a score.</b> A single "health: 82%" is unactionable and
 * hides which of several unrelated failures is happening — an expired credential and a
 * blocked listing are different jobs for different people. Every alert carries the
 * action that resolves it, because an alert with no action is just anxiety.
 *
 * <p>The alerts arrive already ordered worst-first from the server, and are rendered in
 * that order rather than re-sorted here: a missing credential means a channel is dead
 * now, an expiring one means it will be, and burying the outage under the warning is the
 * mistake this ordering exists to prevent.
 */

const TONE = {
  CRITICAL: { cls: 'danger', icon: <FiAlertOctagon /> },
  WARNING: { cls: 'warning', icon: <FiAlertTriangle /> },
  INFO: { cls: 'info', icon: <FiInfo /> },
};

/** The .text-* utility classes do not exist in either admin stylesheet; use the
 * same CSS custom properties the badges resolve to, with a safe fallback. */
const toneColor = (tone) =>
  tone === 'danger' ? 'var(--danger, #dc2626)'
  : tone === 'warning' ? 'var(--warning, #d97706)'
  : undefined;

function Stat({ label, value, tone }) {
  return (
    <div className="adm-mini-card">
      <div className="adm-mini-card-title">{label}</div>
      <div style={{ fontSize: '1.6rem', fontWeight: 600, color: toneColor(tone) }}
           >{value}</div>
    </div>
  );
}

export default function AdminDistributionHealth() {
  const [health, setHealth] = useState(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.getDistributionHealth();
      setHealth(res.data?.data || null);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load distribution health');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiActivity /> Distribution</span>
          <h1>Channel health</h1>
          <p>Whether this venue&apos;s channels are actually selling, and what to fix if not.</p>
        </div>
      </div>

      {loading && <div className="loading"><div className="spinner"></div></div>}

      {!loading && health && (
        <>
          {health.alerts?.length === 0 ? (
            <div className="adm-card" style={{ padding: '0.9rem 1.15rem', marginBottom: '1rem' }}>
              <FiCheckCircle aria-hidden="true" /> Nothing needs attention.
              {/* An empty state has to be trustworthy, or an operator learns to ignore
                  the panel entirely — and then misses the day it is not empty. */}
            </div>
          ) : (
            <div style={{ marginBottom: '1rem' }}>
              {health.alerts.map((a, i) => {
                const tone = TONE[a.severity] || TONE.INFO;
                return (
                  <div key={i} className="adm-card"
                       style={{ padding: '0.8rem 1.15rem', marginBottom: '0.5rem' }}>
                    <div>
                      <span className={`badge badge-${tone.cls}`}>{a.severity}</span>{' '}
                      {a.message}
                    </div>
                    <div className="adm-hint" style={{ marginTop: '0.25rem' }}>{a.action}</div>
                  </div>
                );
              })}
            </div>
          )}

          <div className="adm-panel-stack" style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap' }}>
            <Stat label="Connections" value={health.connectionsTotal} />
            <Stat label="Active" value={health.connectionsActive} />
            <Stat label="Degraded" value={health.connectionsDegraded}
                  tone={health.connectionsDegraded > 0 ? 'danger' : null} />
            <Stat label="Paused" value={health.connectionsPaused} />
            <Stat label="Listings live" value={health.listingsLive} />
            <Stat label="Listings blocked" value={health.listingsBlocked}
                  tone={health.listingsBlocked > 0 ? 'warning' : null} />
            <Stat label="Inbox failed" value={health.inboxFailed}
                  tone={health.inboxFailed > 0 ? 'danger' : null} />
            {/* Counted, deliberately not alerted: each superseded row is the ordering
                rule working. Only a spike means a provider's delivery is degrading, and
                that is a judgement for a human looking at the number. */}
            <Stat label="Superseded" value={health.inboxSuperseded} />
          </div>

          <p className="adm-table-note">
            <Link to="/admin/distribution">Connections</Link>{' · '}
            <Link to="/admin/listings">Listings</Link>{' · '}
            <Link to="/admin/inbox">Reservation inbox</Link>
            {health.generatedAt && ` · as of ${new Date(health.generatedAt).toLocaleTimeString()}`}
          </p>
        </>
      )}
    </div>
  );
}
