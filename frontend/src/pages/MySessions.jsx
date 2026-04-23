import { useCallback, useEffect, useState } from 'react';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiMonitor, FiRefreshCw, FiShield, FiAlertTriangle } from 'react-icons/fi';
import SEO from '../components/SEO';
import './AdminSecurity.css';

/**
 * Self-service session manager. Shows every active refresh-token session for the
 * current user and lets them force-logout a specific device.
 */
export default function MySessions() {
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const res = await authService.getMySessions();
      setSessions(res.data?.data || []);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load sessions');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const revoke = async (id, current) => {
    if (current && !window.confirm('This will sign out the current browser. Continue?')) return;
    if (!current && !window.confirm('Sign out this device?')) return;
    try {
      await authService.revokeMySession(id);
      toast.success('Session revoked');
      refresh();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to revoke session');
    }
  };

  return (
    <div className="sec-page">
      <SEO title="My Sessions" description="Manage your active sign-in sessions." />

      <div className="sec-header">
        <div className="sec-header-copy">
          <span className="sec-kicker"><FiShield /> SECURITY</span>
          <h1>Active sessions</h1>
          <p>
            Every browser and device currently signed in to your account. If anything looks
            unfamiliar, revoke it immediately and change your password.
          </p>
        </div>
        <button className="sec-btn" onClick={refresh} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <div className="sec-card">
        {loading && sessions.length === 0 ? (
          <div className="sec-empty"><h3>Loading…</h3></div>
        ) : sessions.length === 0 ? (
          <div className="sec-empty">
            <h3>No active sessions</h3>
            <p>You're not signed in anywhere else.</p>
          </div>
        ) : (
          <>
            <div className="mfa-warning" style={{ marginBottom: 16 }}>
              <FiAlertTriangle size={18} />
              <span>
                Don't recognise one of these? Revoke it and then change your password — someone else
                may have access to your account.
              </span>
            </div>

            <div className="sec-table-wrap">
              <table className="sec-table">
                <thead>
                  <tr>
                    <th>Device</th>
                    <th>IP address</th>
                    <th>Signed in</th>
                    <th>Last seen</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {sessions.map((s) => (
                    <tr key={s.id}>
                      <td>
                        <div className="sec-device-cell">
                          <div className="sec-device-icon"><FiMonitor /></div>
                          <div>
                            <div className="sec-device-name">
                              {s.deviceLabel || 'Unknown device'}
                              {s.current && (
                                <span className="sec-pill current" style={{ marginLeft: 8 }}>
                                  THIS DEVICE
                                </span>
                              )}
                            </div>
                            <div className="sec-device-meta">Session #{s.id}</div>
                          </div>
                        </div>
                      </td>
                      <td>{s.ipAddress || '—'}</td>
                      <td>{fmt(s.createdAt)}</td>
                      <td>{fmt(s.lastSeenAt)}</td>
                      <td>
                        <button
                          className="sec-btn sec-btn-danger"
                          onClick={() => revoke(s.id, s.current)}
                        >
                          {s.current ? 'Sign out' : 'Revoke'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function fmt(iso) {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}
