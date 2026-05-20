import { useState, useEffect, useMemo } from 'react';
import { slotHoldService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiClock, FiUser, FiMail, FiUsers, FiXCircle, FiRefreshCw } from 'react-icons/fi';
import { formatTime12h } from '../utils/format';
import './AdminPages.css';

const fmtTime = (timeStr) => formatTime12h(timeStr) || '--:--';

/**
 * Admin view of currently-active pre-payment slot holds for the selected
 * binge. Shows a live countdown per hold and lets the admin force-release
 * any hold (e.g. a customer who closed their browser without releasing).
 *
 * The list is binge-scoped server-side via {@code AdminBingeScopeService}.
 */
export default function AdminSlotHolds() {
  const [holds, setHolds] = useState([]);
  const [loading, setLoading] = useState(false);
  const [busyToken, setBusyToken] = useState(null);
  const [now, setNow] = useState(Date.now());

  const fetchHolds = async () => {
    setLoading(true);
    try {
      const res = await slotHoldService.adminList();
      setHolds(toArray(res.data?.data));
    } catch {
      toast.error('Failed to load active slot holds');
      setHolds([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHolds();
    // Refresh the list every 30 s so released / expired holds drop off
    // without the admin having to reload the page.
    const t = setInterval(fetchHolds, 30000);
    return () => clearInterval(t);
  }, []);

  // 1 s tick for countdown — only updates the displayed countdown, not the list.
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const handleRelease = async (hold) => {
    if (!window.confirm(`Force-release the slot hold for ${hold.customerName || hold.customerEmail || 'this customer'}? They'll have to re-select the slot if they were still in checkout.`)) return;
    setBusyToken(hold.holdToken);
    try {
      await slotHoldService.adminRelease(hold.holdToken, 'ADMIN_FORCED_RELEASE');
      toast.success('Hold released');
      await fetchHolds();
    } catch (e) {
      toast.error(e.response?.data?.message || e.userMessage || 'Failed to release hold');
    } finally {
      setBusyToken(null);
    }
  };

  const liveHolds = useMemo(() => {
    // Server filters to ACTIVE only and excludes expired-but-uncleaned-up
    // entries, but we double-check client-side for the countdown so an
    // expired hold disappears immediately rather than at the next refresh.
    return holds.filter(h => h.expiresAt && new Date(h.expiresAt).getTime() > now);
  }, [holds, now]);

  const fmtCountdown = (expiresAt) => {
    const remaining = Math.max(0, Math.floor((new Date(expiresAt).getTime() - now) / 1000));
    const m = Math.floor(remaining / 60);
    const s = remaining % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  };

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1>Active Slot Holds</h1>
          <p className="adm-page-subtitle">
            Customers currently reserving a slot during checkout. Holds release automatically when the timer ends or the customer completes / cancels checkout.
          </p>
        </div>
        <button className="btn btn-secondary" onClick={fetchHolds} disabled={loading}>
          <FiRefreshCw style={{ marginRight: 6 }} />
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {liveHolds.length === 0 && !loading && (
        <div className="adm-empty-state">
          <p>No active slot holds right now.</p>
        </div>
      )}

      {liveHolds.length > 0 && (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead>
              <tr>
                <th>Customer</th>
                <th>Slot</th>
                <th><FiUsers /> Guests</th>
                <th><FiClock /> Time left</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {liveHolds.map(h => {
                const sec = Math.max(0, Math.floor((new Date(h.expiresAt).getTime() - now) / 1000));
                const low = sec < 60;
                return (
                  <tr key={h.holdToken}>
                    <td>
                      <div><FiUser style={{ marginRight: 4 }} />{h.customerName || '—'}</div>
                      {h.customerEmail && (
                        <div style={{ fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                          <FiMail style={{ marginRight: 4 }} />{h.customerEmail}
                        </div>
                      )}
                    </td>
                    <td>
                      <div>{h.bookingDate} · {fmtTime(h.startTime)}</div>
                      <div style={{ fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                        {h.eventTypeName || `Event #${h.eventTypeId}`} · {h.durationMinutes} min
                      </div>
                    </td>
                    <td>{h.numberOfGuests}</td>
                    <td style={{ fontFamily: 'monospace', color: low ? '#b91c1c' : 'inherit', fontWeight: 600 }}>
                      {fmtCountdown(h.expiresAt)}
                    </td>
                    <td>
                      <button
                        className="btn btn-danger btn-sm"
                        onClick={() => handleRelease(h)}
                        disabled={busyToken === h.holdToken}
                      >
                        <FiXCircle style={{ marginRight: 4 }} />
                        {busyToken === h.holdToken ? 'Releasing…' : 'Release'}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
