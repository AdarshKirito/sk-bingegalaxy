import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import {
  FiShield, FiUsers, FiUserCheck, FiMonitor, FiFilter, FiRefreshCw,
  FiArrowUp, FiArrowDown, FiSearch, FiCheck, FiX, FiClock, FiActivity,
  FiUserX, FiSlash,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import './AdminSecurity.css';

/**
 * Super-admin console — the highest-privilege area of the product.
 *
 * Tabs:
 *  - Overview:        platform-wide user / session counts + recent activity preview.
 *  - Active sessions: every live refresh-session; super-admin can force-logout a single
 *                     session or every session for a given user.
 *  - Audit log:       auth-sensitive events with server-side filters (event type, actor id,
 *                     target id) and a client-side free-text search across the loaded page.
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

      <nav className="sec-tabs" role="tablist" aria-label="Super admin sections">
        {[
          ['overview', 'Overview'],
          ['sessions', 'Active sessions'],
          ['audit',    'Audit log'],
          ['admins',   'Admins'],
        ].map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={tab === key ? 'active' : ''}
          >
            {label}
          </button>
        ))}
      </nav>

      {tab === 'overview' && <OverviewTab onJump={setTab} />}
      {tab === 'sessions' && <SessionsTab />}
      {tab === 'audit'    && <AuditTab />}
      {tab === 'admins'   && <AdminsTab />}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Overview
   ────────────────────────────────────────────────────────────── */
function OverviewTab({ onJump }) {
  const [stats, setStats]       = useState(null);
  const [recent, setRecent]     = useState([]);
  const [loading, setLoad]      = useState(true);
  const [updatedAt, setUpdated] = useState(null);

  const load = useCallback(() => {
    setLoad(true);
    Promise.all([
      authService.getSuperAdminStats(),
      authService.getAuditLog({ page: 0, size: 5 }),
    ])
      .then(([statsRes, auditRes]) => {
        setStats(statsRes.data?.data);
        setRecent(auditRes.data?.data?.content || []);
        setUpdated(new Date());
      })
      .catch((err) => toast.error(err.response?.data?.message || 'Failed to load stats'))
      .finally(() => setLoad(false));
  }, []);
  useEffect(() => { load(); }, [load]);

  if (loading && !stats) return <div className="sec-card"><p>Loading…</p></div>;
  if (!stats) return null;

  const tiles = [
    { label: 'Customers',       value: stats.customers,      icon: <FiUsers />,      jump: null },
    { label: 'Admins',          value: stats.admins,         icon: <FiUserCheck />,  jump: 'admins' },
    { label: 'Super admins',    value: stats.superAdmins,    icon: <FiShield />,     jump: 'admins' },
    { label: 'Active sessions', value: stats.activeSessions, icon: <FiMonitor />,    jump: 'sessions' },
  ];
  return (
    <>
      <div className="sec-stats">
        {tiles.map((t) => (
          <button
            type="button"
            className={`sec-stat${t.jump ? ' sec-stat-clickable' : ''}`}
            key={t.label}
            onClick={t.jump ? () => onJump(t.jump) : undefined}
            disabled={!t.jump}
            aria-label={t.jump ? `Open ${t.label.toLowerCase()}` : t.label}
          >
            <div className="sec-stat-icon">{t.icon}</div>
            <div className="sec-stat-label">{t.label}</div>
            <div className="sec-stat-value">{Number(t.value || 0).toLocaleString()}</div>
          </button>
        ))}
      </div>

      <div className="sec-card">
        <div className="sec-card-head">
          <div>
            <h2>Operational health</h2>
            <p>
              Snapshot of the identity plane.
              {updatedAt && (
                <> Last refreshed <span className="sec-meta"><FiClock /> {fmt(updatedAt.toISOString())}</span></>
              )}
            </p>
          </div>
          <button className="sec-btn" onClick={load} disabled={loading}>
            <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
          </button>
        </div>
        <p style={{ fontSize: 14, color: 'var(--text-muted)', margin: 0 }}>
          {stats.activeSessions > 0
            ? `${stats.activeSessions.toLocaleString()} active session${stats.activeSessions === 1 ? '' : 's'} across the platform.`
            : 'No active sessions — everyone is signed out.'}
        </p>
      </div>

      <div className="sec-card">
        <div className="sec-card-head">
          <div>
            <h2><FiShield style={{ verticalAlign: 'middle', marginRight: 6 }} /> Authority Handover</h2>
            <p>
              Temporarily delegate per-page super-admin authority to an admin, capped
              at 24 hours. Lock individual records so even delegated admins cannot
              modify them. Every action audit-logged.
            </p>
          </div>
          <a className="sec-btn" href="/admin/super/authority">Open console →</a>
        </div>
      </div>

      <div className="sec-card">
        <div className="sec-card-head">
          <div>
            <h2><FiActivity style={{ verticalAlign: 'middle', marginRight: 6 }} /> Recent activity</h2>
            <p>The last 5 auth-sensitive events. Open the Audit log tab for full search.</p>
          </div>
          <button className="sec-btn" onClick={() => onJump('audit')}>
            View full log →
          </button>
        </div>
        {recent.length === 0 ? (
          <div className="sec-empty"><h3>No audit entries yet</h3></div>
        ) : (
          <ul className="sec-activity">
            {recent.map((a) => (
              <li key={a.id}>
                <span className={`sec-pill ${a.success ? 'success' : 'fail'}`}>
                  {a.success ? <FiCheck /> : <FiX />} {a.success ? 'OK' : 'FAIL'}
                </span>
                <code className="sec-event">{a.eventType}</code>
                <span className="sec-activity-actor">
                  {a.actorId ? <>actor <code>#{a.actorId}</code></> : 'anonymous'}
                  {a.targetEmail ? <> → {a.targetEmail}</>
                    : a.targetId ? <> → <code>#{a.targetId}</code></> : null}
                </span>
                <span className="sec-activity-time">{fmt(a.createdAt)}</span>
              </li>
            ))}
          </ul>
        )}
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
  const [search, setSearch] = useState('');

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

  const revokeAllForUser = async (userId) => {
    if (!window.confirm(
      `Revoke EVERY active session for user #${userId}? They will be signed out of all devices.`,
    )) return;
    try {
      const res = await authService.revokeAllSessionsForUser(userId);
      const n = res.data?.data ?? 0;
      toast.success(`Revoked ${n} session${n === 1 ? '' : 's'} for user #${userId}`);
      refresh();
    } catch (err) { toast.error(err.response?.data?.message || 'Failed'); }
  };

  const filtered = useMemo(() => {
    const rows = data?.content || [];
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((s) => {
      const hay = [
        `#${s.userId}`, `${s.userId}`, `#${s.id}`, s.deviceLabel, s.userAgent, s.ipAddress,
      ].filter(Boolean).join(' ').toLowerCase();
      return hay.includes(q);
    });
  }, [data, search]);

  // Tally repeat user-ids on the current page so we can offer "revoke all for this user"
  // as an at-a-glance action when one user has multiple sessions.
  const userSessionCount = useMemo(() => {
    const m = new Map();
    (data?.content || []).forEach((s) => m.set(s.userId, (m.get(s.userId) || 0) + 1));
    return m;
  }, [data]);

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Active sessions</h2>
          <p>
            Every live refresh-session. Revoking instantly signs the user out of that device.
            {data && <> · <span className="sec-meta">{data.totalElements ?? data.content?.length ?? 0} total</span></>}
          </p>
        </div>
        <button className="sec-btn" onClick={refresh} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {(data?.content?.length ?? 0) > 0 && (
        <div className="sec-filters">
          <FiSearch style={{ color: 'var(--text-muted)' }} />
          <input
            type="search"
            placeholder="Search this page by user ID, device, IP, or user-agent…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ flex: 1, maxWidth: 420 }}
          />
          {search && (
            <span className="sec-meta">
              {filtered.length} of {data.content.length} on this page
            </span>
          )}
        </div>
      )}

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
                {filtered.map((s) => {
                  const dupCount = userSessionCount.get(s.userId) || 0;
                  return (
                    <tr key={s.id}>
                      <td>
                        <code>#{s.userId}</code>
                        {dupCount > 1 && (
                          <span className="sec-pill warn" style={{ marginLeft: 6 }} title="Multiple sessions on this page">
                            ×{dupCount}
                          </span>
                        )}
                      </td>
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
                        <div className="sec-actions">
                          <button className="sec-btn sec-btn-danger" onClick={() => revoke(s.id)}>
                            Revoke
                          </button>
                          {dupCount > 1 && (
                            <button
                              className="sec-btn"
                              onClick={() => revokeAllForUser(s.userId)}
                              title={`Revoke all sessions for user #${s.userId}`}
                            >
                              <FiUserX /> All
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
                {filtered.length === 0 && (
                  <tr>
                    <td colSpan={6} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 24 }}>
                      No sessions match "{search}" on this page.
                    </td>
                  </tr>
                )}
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
  'REGISTER','EMAIL_VERIFIED','EMAIL_VERIFICATION_SENT',
  'PASSWORD_CHANGED','PASSWORD_RESET_REQUESTED','PASSWORD_RESET_COMPLETED',
  'MFA_ENROLLED','MFA_DISABLED','MFA_RECOVERY_USED',
  'ADMIN_CREATED','ADMIN_UPDATED','USER_DELETED','USER_BULK_DELETED','USER_BANNED','USER_UNBANNED',
  'ROLE_PROMOTED','ROLE_DEMOTED',
  'SESSION_REVOKED','SESSION_REVOKED_ALL','LOGOUT','TOKEN_REFRESHED',
];

function AuditTab() {
  const [page, setPage] = useState(0);
  const [eventType, setEventType] = useState('');
  // Two layers per ID input: the raw text the user is typing, and the
  // debounced "applied" value that actually drives the server query. This
  // stops every keystroke from firing a request and prevents the classic
  // page-reset / fetch double-call cascade.
  const [actorIdInput, setActorIdInput]   = useState('');
  const [targetIdInput, setTargetIdInput] = useState('');
  const actorId  = useDebounced(actorIdInput, 300);
  const targetId = useDebounced(targetIdInput, 300);
  const [textSearch, setText]     = useState('');
  const [data, setData]           = useState(null);
  const [loading, setLoad]        = useState(true);

  // Server-side params — only the ones with content are sent. Every change to
  // a server-side filter resets the page to 0 *atomically* by deriving
  // serverParams from the (possibly-clamped) inputs and using `page` only
  // for explicit pager clicks. We keep an effect that resets `page` if it
  // ever falls past totalPages after a filter change.
  const serverParams = useMemo(() => {
    const p = { page, size: 50 };
    if (eventType) p.eventType = eventType;
    const a = parseId(actorId);  if (a !== null) p.actorId = a;
    const t = parseId(targetId); if (t !== null) p.targetId = t;
    return p;
  }, [page, eventType, actorId, targetId]);

  // Stale-response guard. When filters change rapidly, a slow first request
  // could finish AFTER a faster second one and clobber good data with stale
  // results. Each fetch tags itself with a monotonic id; only the latest
  // in-flight id is allowed to commit to state.
  const reqIdRef = useRef(0);
  const refresh = useCallback(() => {
    const myId = ++reqIdRef.current;
    setLoad(true);
    authService.getAuditLog(serverParams)
      .then((res) => { if (myId === reqIdRef.current) setData(res.data?.data); })
      .catch((err) => {
        if (myId === reqIdRef.current) {
          toast.error(err.response?.data?.message || 'Failed to load audit log');
        }
      })
      .finally(() => { if (myId === reqIdRef.current) setLoad(false); });
  }, [serverParams]);
  useEffect(() => { refresh(); }, [refresh]);

  // When a server-side filter changes (event/actor/target), jump back to page 0.
  // We compare against a ref so we only call setPage when filters actually change,
  // never on plain page navigation — which avoids the double-fetch cascade.
  const filtersRef = useRef('');
  useEffect(() => {
    const sig = `${eventType}|${actorId}|${targetId}`;
    if (filtersRef.current !== sig) {
      filtersRef.current = sig;
      if (page !== 0) setPage(0);
    }
  }, [eventType, actorId, targetId, page]);

  const filtered = useMemo(() => {
    const rows = data?.content || [];
    const q = textSearch.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((a) => {
      const hay = [
        a.eventType, a.actorRole, a.targetEmail, a.ipAddress,
        a.failureReason, a.details, a.userAgent,
        a.actorId != null ? `#${a.actorId} ${a.actorId}` : '',
        a.targetId != null ? `#${a.targetId} ${a.targetId}` : '',
      ].filter(Boolean).join(' ').toLowerCase();
      return hay.includes(q);
    });
  }, [data, textSearch]);

  const hasFilters = eventType || actorIdInput || targetIdInput || textSearch;
  const clearFilters = () => {
    setEventType(''); setActorIdInput(''); setTargetIdInput(''); setText(''); setPage(0);
  };

  // Helpers for the click-to-filter links in audit rows.
  const filterByActor  = (id) => { setActorIdInput(String(id));  setPage(0); };
  const filterByTarget = (id) => { setTargetIdInput(String(id)); setPage(0); };

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Audit log</h2>
          <p>
            Every auth-sensitive event, with actor, target, and outcome. Filter by event,
            actor or target user ID — or search the loaded page by email, IP, role, or details.
          </p>
        </div>
        <button className="sec-btn" onClick={refresh} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <div className="sec-filters sec-filters-grid">
        <label className="sec-field">
          <span className="sec-field-label"><FiFilter /> Event type</span>
          <select value={eventType} onChange={(e) => setEventType(e.target.value)}>
            <option value="">All events</option>
            {AUDIT_EVENTS.map((e) => <option key={e} value={e}>{e}</option>)}
          </select>
        </label>
        <label className="sec-field">
          <span className="sec-field-label">Actor user ID</span>
          <input
            type="text"
            inputMode="numeric"
            placeholder="e.g. 42"
            value={actorIdInput}
            onChange={(e) => setActorIdInput(e.target.value)}
          />
        </label>
        <label className="sec-field">
          <span className="sec-field-label">Target user ID</span>
          <input
            type="text"
            inputMode="numeric"
            placeholder="e.g. 137"
            value={targetIdInput}
            onChange={(e) => setTargetIdInput(e.target.value)}
          />
        </label>
        <label className="sec-field sec-field-grow">
          <span className="sec-field-label"><FiSearch /> Search this page</span>
          <input
            type="search"
            placeholder="Filter by email, IP, role, details…"
            value={textSearch}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        {hasFilters && (
          <button className="sec-btn" onClick={clearFilters} style={{ alignSelf: 'flex-end' }}>
            <FiSlash /> Clear filters
          </button>
        )}
      </div>

      {data && (
        <p className="sec-meta" style={{ marginBottom: 8 }}>
          {data.totalElements != null && (
            <>
              {data.totalElements.toLocaleString()} entr{data.totalElements === 1 ? 'y' : 'ies'} match the server filters
              {textSearch && <> · {filtered.length} on this page match "{textSearch}"</>}
            </>
          )}
        </p>
      )}

      {!data || data.content.length === 0 ? (
        <div className="sec-empty">
          <h3>No audit entries</h3>
          <p>{hasFilters ? 'Try widening or clearing your filters.' : 'Nothing has happened yet.'}</p>
        </div>
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
                {filtered.map((a) => (
                  <tr key={a.id}>
                    <td style={{ whiteSpace: 'nowrap' }}>{fmt(a.createdAt)}</td>
                    <td><code style={{ fontSize: 12 }}>{a.eventType}</code></td>
                    <td>
                      {a.actorId ? (
                        <span className="sec-actor-cell">
                          <button
                            type="button"
                            className="sec-link"
                            onClick={() => filterByActor(a.actorId)}
                            title="Filter by this actor"
                          >
                            #{a.actorId}
                          </button>{' '}
                          <RolePill role={a.actorRole} />
                        </span>
                      ) : '—'}
                    </td>
                    <td>
                      {a.targetId ? (
                        <button
                          type="button"
                          className="sec-link"
                          onClick={() => filterByTarget(a.targetId)}
                          title="Filter by this target"
                        >
                          {a.targetEmail || `#${a.targetId}`}
                        </button>
                      ) : (a.targetEmail || '—')}
                    </td>
                    <td>{a.ipAddress || '—'}</td>
                    <td>
                      <span className={`sec-pill ${a.success ? 'success' : 'fail'}`}>
                        {a.success ? <FiCheck /> : <FiX />} {a.success ? 'OK' : 'FAIL'}
                      </span>
                    </td>
                    <td style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis' }}
                        title={a.failureReason || a.details || ''}>
                      {a.failureReason || a.details || ''}
                    </td>
                  </tr>
                ))}
                {filtered.length === 0 && (
                  <tr>
                    <td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 24 }}>
                      No audit entries on this page match "{textSearch}".
                    </td>
                  </tr>
                )}
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
  const [roleFilter, setRoleFilter] = useState('');

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
    return admins.filter((a) => {
      if (roleFilter && String(a.role).toUpperCase() !== roleFilter) return false;
      if (!q) return true;
      const name = `${a.firstName || ''} ${a.lastName || ''}`.toLowerCase();
      return name.includes(q)
        || (a.email || '').toLowerCase().includes(q)
        || String(a.id || '').includes(q);
    });
  }, [admins, search, roleFilter]);

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

  const counts = useMemo(() => ({
    total: admins.length,
    admin: admins.filter((a) => String(a.role).toUpperCase() === 'ADMIN').length,
    super: admins.filter((a) => String(a.role).toUpperCase() === 'SUPER_ADMIN').length,
  }), [admins]);

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Admin roster</h2>
          <p>
            Manage admin privileges. You cannot demote yourself or the last super-admin.
            <span className="sec-meta"> · {counts.total} total ({counts.super} super, {counts.admin} admin)</span>
          </p>
        </div>
        <button className="sec-btn" onClick={refresh} disabled={loading}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <div className="sec-filters">
        <FiSearch style={{ color: 'var(--text-muted)' }} />
        <input
          type="search"
          placeholder="Search by ID, name, or email…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ flex: 1, maxWidth: 360 }}
        />
        <select value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)}>
          <option value="">All roles</option>
          <option value="ADMIN">ADMIN only</option>
          <option value="SUPER_ADMIN">SUPER_ADMIN only</option>
        </select>
        {(search || roleFilter) && (
          <span className="sec-meta">{filtered.length} of {admins.length}</span>
        )}
      </div>

      {filtered.length === 0 ? (
        <div className="sec-empty">
          <h3>No admins</h3>
          <p>{search || roleFilter ? 'Try widening your filters.' : ''}</p>
        </div>
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
                    {String(a.role).toUpperCase() === 'ADMIN' ? (
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

/** Parse a user-typed ID. Returns null if blank or not a positive integer. */
function parseId(raw) {
  const s = String(raw || '').trim();
  if (!s) return null;
  if (!/^\d{1,18}$/.test(s)) return null;
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? n : null;
}

/**
 * Returns {@code value} debounced by {@code delay} ms. Used so that typing in
 * the audit-log filter inputs doesn't fire one API call per keystroke.
 */
function useDebounced(value, delay) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return v;
}

function fmt(iso) {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}
