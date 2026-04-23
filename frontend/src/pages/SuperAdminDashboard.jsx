import { useCallback, useEffect, useMemo, useState } from 'react';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import {
  FiShield, FiUsers, FiUserCheck, FiMonitor, FiFilter, FiRefreshCw,
  FiArrowUp, FiArrowDown, FiSearch, FiCheck, FiX,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import './AdminSecurity.css';

/**
 * Super-admin console — the highest-privilege area of the product.
 *
 * Tabs:
 *  - Overview:      platform-wide user / session counts.
 *  - Active sessions: every live refresh-session; super-admin can force-logout.
 *  - Audit log:       auth-sensitive events (logins, MFA, promotions …) with filter.
 *  - Admins:          promote ADMIN ↔ SUPER_ADMIN; blocks self-demotion server-side.
 *
 * All endpoints are SUPER_ADMIN-gated server-side; this UI is already behind
 * SuperAdminRoute so the inputs below can safely surface every dangerous action.
 */
export default function SuperAdminDashboard() {
  const [tab, setTab] = useState('overview');
  return (
    <div className="sec-page">
      <SEO title="Super Admin Console" description="Platform administration — audit log, active sessions, role management." />

      <div className="sec-header">
        <div className="sec-header-copy">
          <span className="sec-kicker"><FiShield /> SUPER ADMIN</span>
          <h1>Platform Console</h1>
          <p>
            Global visibility across users, sessions, and every auth-sensitive event.
            All actions here are audited and irreversible — use with care.
          </p>
        </div>
      </div>

      <nav className="sec-tabs">
        {[
          ['overview', 'Overview'],
          ['sessions', 'Active sessions'],
          ['audit',    'Audit log'],
          ['admins',   'Admins'],
        ].map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)} className={tab === key ? 'active' : ''}>
            {label}
          </button>
        ))}
      </nav>

      {tab === 'overview' && <OverviewTab />}
      {tab === 'sessions' && <SessionsTab />}
      {tab === 'audit'    && <AuditTab />}
      {tab === 'admins'   && <AdminsTab />}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Overview
   ────────────────────────────────────────────────────────────── */
function OverviewTab() {
  const [stats, setStats]   = useState(null);
  const [loading, setLoad]  = useState(true);
  const load = useCallback(() => {
    setLoad(true);
    authService.getSuperAdminStats()
      .then((res) => setStats(res.data?.data))
      .catch((err) => toast.error(err.response?.data?.message || 'Failed to load stats'))
      .finally(() => setLoad(false));
  }, []);
  useEffect(() => { load(); }, [load]);

  if (loading && !stats) return <div className="sec-card"><p>Loading…</p></div>;
  if (!stats) return null;

  const tiles = [
    { label: 'Customers',      value: stats.customers,      icon: <FiUsers /> },
    { label: 'Admins',         value: stats.admins,         icon: <FiUserCheck /> },
    { label: 'Super admins',   value: stats.superAdmins,    icon: <FiShield /> },
    { label: 'Active sessions',value: stats.activeSessions, icon: <FiMonitor /> },
  ];
  return (
    <>
      <div className="sec-stats">
        {tiles.map((t) => (
          <div className="sec-stat" key={t.label}>
            <div className="sec-stat-icon">{t.icon}</div>
            <div className="sec-stat-label">{t.label}</div>
            <div className="sec-stat-value">{Number(t.value || 0).toLocaleString()}</div>
          </div>
        ))}
      </div>

      <div className="sec-card">
        <div className="sec-card-head">
          <div>
            <h2>Operational health</h2>
            <p>Snapshot of the identity plane. Refresh to re-query the live counts.</p>
          </div>
          <button className="sec-btn" onClick={load}><FiRefreshCw /> Refresh</button>
        </div>
        <p style={{ fontSize: 14, color: 'var(--text-muted)', margin: 0 }}>
          {stats.activeSessions > 0
            ? `${stats.activeSessions.toLocaleString()} active session${stats.activeSessions === 1 ? '' : 's'} across the platform.`
            : 'No active sessions — everyone is signed out.'}
        </p>
      </div>
    </>
  );
}

/* ──────────────────────────────────────────────────────────────
   Active sessions
   ────────────────────────────────────────────────────────────── */
function SessionsTab() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [loading, setLoad] = useState(true);

  const refresh = useCallback(() => {
    setLoad(true);
    authService.getAllActiveSessions({ page, size: 50 })
      .then((res) => setData(res.data?.data))
      .catch((err) => toast.error(err.response?.data?.message || 'Failed to load sessions'))
      .finally(() => setLoad(false));
  }, [page]);
  useEffect(() => { refresh(); }, [refresh]);

  const revoke = async (id) => {
    if (!window.confirm('Force sign-out this session?')) return;
    try {
      await authService.revokeAnySession(id);
      toast.success('Session revoked');
      refresh();
    } catch (err) { toast.error(err.response?.data?.message || 'Failed'); }
  };

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Active sessions</h2>
          <p>Every live refresh-session. Revoking instantly signs the user out of that device.</p>
        </div>
        <button className="sec-btn" onClick={refresh} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {!data || data.content.length === 0 ? (
        <div className="sec-empty"><h3>No active sessions</h3><p>Everyone is signed out.</p></div>
      ) : (
        <>
          <div className="sec-table-wrap">
            <table className="sec-table">
              <thead>
                <tr>
                  <th>User</th><th>Device</th><th>IP</th><th>Created</th><th>Last seen</th><th></th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((s) => (
                  <tr key={s.id}>
                    <td><code>#{s.userId}</code></td>
                    <td>
                      <div className="sec-device-cell">
                        <div className="sec-device-icon"><FiMonitor /></div>
                        <div>
                          <div className="sec-device-name">{s.deviceLabel || 'Unknown device'}</div>
                          <div className="sec-device-meta">Session #{s.id}</div>
                        </div>
                      </div>
                    </td>
                    <td>{s.ipAddress || '—'}</td>
                    <td>{fmt(s.createdAt)}</td>
                    <td>{fmt(s.lastSeenAt)}</td>
                    <td>
                      <button className="sec-btn sec-btn-danger" onClick={() => revoke(s.id)}>
                        Revoke
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Audit log
   ────────────────────────────────────────────────────────────── */
const AUDIT_EVENTS = [
  'LOGIN_SUCCESS','LOGIN_FAILED','LOGIN_LOCKED','LOGIN_MFA_CHALLENGED','LOGIN_MFA_FAILED',
  'REGISTER','PASSWORD_CHANGED','PASSWORD_RESET_COMPLETED','MFA_ENROLLED','MFA_DISABLED',
  'ADMIN_CREATED','USER_DELETED','USER_BULK_DELETED','USER_BANNED','USER_UNBANNED',
  'ROLE_PROMOTED','ROLE_DEMOTED','SESSION_REVOKED','SESSION_REVOKED_ALL','LOGOUT','TOKEN_REFRESHED',
];

function AuditTab() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState('');
  const [data, setData] = useState(null);
  const [loading, setLoad] = useState(true);

  const refresh = useCallback(() => {
    setLoad(true);
    authService.getAuditLog({ page, size: 50, eventType: filter || undefined })
      .then((res) => setData(res.data?.data))
      .catch((err) => toast.error(err.response?.data?.message || 'Failed to load audit log'))
      .finally(() => setLoad(false));
  }, [page, filter]);
  useEffect(() => { refresh(); }, [refresh]);

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Audit log</h2>
          <p>Every auth-sensitive event, with actor, target, and outcome.</p>
        </div>
        <button className="sec-btn" onClick={refresh} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <div className="sec-filters">
        <FiFilter style={{ color: 'var(--text-muted)' }} />
        <select value={filter} onChange={(e) => { setPage(0); setFilter(e.target.value); }}>
          <option value="">All events</option>
          {AUDIT_EVENTS.map((e) => <option key={e} value={e}>{e}</option>)}
        </select>
      </div>

      {!data || data.content.length === 0 ? (
        <div className="sec-empty"><h3>No audit entries</h3><p>Nothing has happened yet for this filter.</p></div>
      ) : (
        <>
          <div className="sec-table-wrap">
            <table className="sec-table">
              <thead>
                <tr>
                  <th>Time</th><th>Event</th><th>Actor</th><th>Target</th>
                  <th>IP</th><th>Outcome</th><th>Details</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((a) => (
                  <tr key={a.id}>
                    <td style={{ whiteSpace: 'nowrap' }}>{fmt(a.createdAt)}</td>
                    <td><code style={{ fontSize: 12 }}>{a.eventType}</code></td>
                    <td>
                      {a.actorId ? <>#{a.actorId} <RolePill role={a.actorRole} /></> : '—'}
                    </td>
                    <td>{a.targetEmail || (a.targetId ? `#${a.targetId}` : '—')}</td>
                    <td>{a.ipAddress || '—'}</td>
                    <td>
                      <span className={`sec-pill ${a.success ? 'success' : 'fail'}`}>
                        {a.success ? <FiCheck /> : <FiX />} {a.success ? 'OK' : 'FAIL'}
                      </span>
                    </td>
                    <td style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {a.failureReason || a.details || ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Admins
   ────────────────────────────────────────────────────────────── */
function AdminsTab() {
  const [admins, setAdmins] = useState([]);
  const [loading, setLoad]  = useState(true);
  const [search, setSearch] = useState('');

  const refresh = useCallback(() => {
    setLoad(true);
    authService.getAllAdmins()
      .then((res) => setAdmins(res.data?.data?.content || res.data?.data || []))
      .catch((err) => toast.error(err.response?.data?.message || 'Failed to load admins'))
      .finally(() => setLoad(false));
  }, []);
  useEffect(() => { refresh(); }, [refresh]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return admins;
    return admins.filter((a) => {
      const name = `${a.firstName || ''} ${a.lastName || ''}`.toLowerCase();
      return name.includes(q) || (a.email || '').toLowerCase().includes(q);
    });
  }, [admins, search]);

  const promote = async (id) => {
    if (!window.confirm('Promote this admin to SUPER_ADMIN? They gain full platform control.')) return;
    try { await authService.promoteAdmin(id); toast.success('Promoted to SUPER_ADMIN'); refresh(); }
    catch (err) { toast.error(err.response?.data?.message || 'Failed'); }
  };
  const demote = async (id) => {
    if (!window.confirm('Demote this SUPER_ADMIN to ADMIN? They lose super-admin privileges.')) return;
    try { await authService.demoteAdmin(id); toast.success('Demoted to ADMIN'); refresh(); }
    catch (err) { toast.error(err.response?.data?.message || 'Failed'); }
  };

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Admin roster</h2>
          <p>Manage admin privileges. You cannot demote yourself.</p>
        </div>
        <button className="sec-btn" onClick={refresh} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <div className="sec-filters">
        <FiSearch style={{ color: 'var(--text-muted)' }} />
        <input
          type="search"
          placeholder="Search by name or email…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ flex: 1, maxWidth: 320 }}
        />
      </div>

      {filtered.length === 0 ? (
        <div className="sec-empty"><h3>No admins</h3></div>
      ) : (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr>
                <th>ID</th><th>Name</th><th>Email</th><th>Role</th>
                <th>Status</th><th>2FA</th><th></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((a) => (
                <tr key={a.id}>
                  <td><code>#{a.id}</code></td>
                  <td style={{ fontWeight: 600 }}>{a.firstName} {a.lastName}</td>
                  <td>{a.email}</td>
                  <td><RolePill role={a.role} /></td>
                  <td>
                    <span className={`sec-pill ${a.active ? 'success' : 'fail'}`}>
                      {a.active ? 'Active' : 'Disabled'}
                    </span>
                  </td>
                  <td>
                    <span className={`sec-pill ${a.mfaEnabled ? 'mfa-on' : 'mfa-off'}`}>
                      {a.mfaEnabled ? 'Enabled' : 'Off'}
                    </span>
                  </td>
                  <td>
                    {a.role === 'ADMIN' ? (
                      <button className="sec-btn sec-btn-primary" onClick={() => promote(a.id)}>
                        <FiArrowUp /> Promote
                      </button>
                    ) : (
                      <button className="sec-btn" onClick={() => demote(a.id)}>
                        <FiArrowDown /> Demote
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Shared bits
   ────────────────────────────────────────────────────────────── */
function RolePill({ role }) {
  const r = String(role || '').toUpperCase();
  const tone = r === 'SUPER_ADMIN' ? 'super' : r === 'ADMIN' ? 'admin' : 'customer';
  return <span className={`sec-pill ${tone}`}>{r || '—'}</span>;
}

function Pager({ page, totalPages, onChange }) {
  if (!totalPages || totalPages <= 1) return null;
  return (
    <div className="sec-pager">
      <button className="sec-btn" disabled={page === 0} onClick={() => onChange(page - 1)}>← Prev</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button className="sec-btn" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next →</button>
    </div>
  );
}

function fmt(iso) {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}
