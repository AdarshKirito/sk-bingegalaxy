import { useEffect, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { adminRiskService, toArray } from '../services/endpoints';
import { parseServerDate } from '../services/timeFormat';
import { toast } from 'react-toastify';
import { FiAlertTriangle, FiCheckCircle, FiRefreshCw, FiFlag } from 'react-icons/fi';
import { useBinge } from '../context/BingeContext';
import './AdminPages.css';

const RULE_LABELS = {
  SHARED_PHONE_MULTIPLE_ACCOUNTS: 'Shared phone (multiple accounts)',
  SHARED_EMAIL_MULTIPLE_ACCOUNTS: 'Shared email (multiple accounts)',
  REPEATED_PENDING_CANCELLATIONS: 'Repeated pending cancellations',
  REPEATED_NO_SHOWS: 'Repeated no-shows',
  UNUSUALLY_HIGH_VALUE: 'Unusually high-value booking',
  RAPID_REBOOKING_BURST: 'Rapid booking burst',
  MANUAL: 'Manual flag',
};

const SEVERITY_BADGE = {
  HIGH: 'badge-danger',
  MEDIUM: 'badge-warning',
  LOW: 'badge-info',
};

export default function AdminRiskFlags() {
  const { selectedBinge } = useBinge();
  const [searchParams, setSearchParams] = useSearchParams();
  const refFilter = searchParams.get('ref') || '';
  const [flags, setFlags] = useState([]);
  const [loading, setLoading] = useState(false);
  const [openOnly, setOpenOnly] = useState(true);
  const [refreshTick, setRefreshTick] = useState(0);

  const fetchFlags = useCallback(async () => {
    if (!selectedBinge?.id) return;
    setLoading(true);
    try {
      // When a deep-link supplies ?ref=BK-xxxx, fetch only that booking's
      // flags (single indexed query). Otherwise fall back to the
      // binge-wide list. The toggle below clears the filter cleanly.
      const res = refFilter
        ? await adminRiskService.listForBooking(refFilter)
        : await adminRiskService.list(selectedBinge.id, openOnly, 0, 100);
      const payload = res.data?.data;
      const content = Array.isArray(payload)
        ? payload
        : (payload?.content ?? toArray(payload));
      // The per-booking endpoint returns ALL flags regardless of status —
      // honour the openOnly toggle locally so the UI stays consistent.
      const filtered = refFilter && openOnly
        ? content.filter(f => (f.status || 'OPEN') === 'OPEN')
        : content;
      setFlags(filtered);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load risk flags');
      setFlags([]);
    } finally {
      setLoading(false);
    }
  }, [selectedBinge?.id, openOnly, refFilter]);

  useEffect(() => { fetchFlags(); }, [fetchFlags, refreshTick]);

  const handleAcknowledge = async (flag) => {
    const note = window.prompt(`Acknowledge "${RULE_LABELS[flag.ruleCode] || flag.ruleCode}" for booking ${flag.bookingRef}? Optional note:`, '');
    if (note === null) return;
    try {
      await adminRiskService.acknowledge(flag.id, note);
      toast.success('Flag acknowledged');
      setRefreshTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to acknowledge');
    }
  };

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiAlertTriangle /> Booking Risk Flags</h1>
          <p>Automated and manual risk signals for bookings at <strong>{selectedBinge?.name || '—'}</strong>. Flags are advisory — they do not block bookings on their own.</p>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          <label style={{ display: 'flex', gap: '0.4rem', alignItems: 'center', fontSize: '0.9em' }}>
            <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} />
            Open only
          </label>
          <button type="button" className="btn btn-secondary" onClick={() => setRefreshTick(t => t + 1)} disabled={loading}>
            <FiRefreshCw /> {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      <section className="adm-flow-card">
        <h3 style={{ marginTop: 0 }}>{openOnly ? 'Open' : 'All'} flags ({flags.length})</h3>
        {refFilter && (
          <div style={{
            marginBottom: '0.75rem', padding: '0.5rem 0.75rem',
            background: 'rgba(59,130,246,0.08)', borderRadius: 'var(--radius-sm)',
            display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap',
            fontSize: '0.85rem',
          }}>
            <span>Filtered to booking <strong>{refFilter}</strong></span>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => setSearchParams({})}
            >Clear filter</button>
          </div>
        )}
        {loading ? (
          <div className="admin-loading">Loading…</div>
        ) : flags.length === 0 ? (
          <div className="admin-empty-state">No {openOnly ? 'open ' : ''}flags at this binge.</div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Booking</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Customer</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Rule</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Severity</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Reason</th>
                <th style={{ textAlign: 'left', padding: '0.5rem 0.6rem', borderBottom: '1px solid var(--border)' }}>When</th>
                <th style={{ width: '160px', borderBottom: '1px solid var(--border)' }} />
              </tr>
            </thead>
            <tbody>
              {flags.map(f => (
                <tr key={f.id}>
                  <td style={{ padding: '0.5rem 0.6rem', fontFamily: 'monospace' }}>{f.bookingRef}</td>
                  <td style={{ padding: '0.5rem 0.6rem' }}>{f.customerId}</td>
                  <td style={{ padding: '0.5rem 0.6rem' }}>
                    <FiFlag style={{ verticalAlign: '-2px', marginRight: 4 }} />
                    {RULE_LABELS[f.ruleCode] || f.ruleCode}
                  </td>
                  <td style={{ padding: '0.5rem 0.6rem' }}>
                    <span className={`badge ${SEVERITY_BADGE[f.severity] || 'badge-info'}`}>{f.severity}</span>
                  </td>
                  <td style={{ padding: '0.5rem 0.6rem', maxWidth: 380 }}>{f.reason || '—'}</td>
                  <td style={{ padding: '0.5rem 0.6rem', fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                    {f.createdAt ? (parseServerDate(f.createdAt)?.toLocaleString() || '—') : '—'}
                  </td>
                  <td style={{ padding: '0.5rem 0.6rem' }}>
                    {f.acknowledged ? (
                      <span style={{ color: 'var(--success, #2c8a4a)', fontSize: '0.9em' }}>
                        <FiCheckCircle /> Acked
                      </span>
                    ) : (
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleAcknowledge(f)}>
                        <FiCheckCircle /> Acknowledge
                      </button>
                    )}
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
