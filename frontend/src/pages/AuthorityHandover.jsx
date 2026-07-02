import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'react-toastify';
import {
  FiShield, FiUserPlus, FiClock, FiSlash, FiLock, FiUnlock, FiRefreshCw,
  FiUsers, FiAlertTriangle, FiCheck, FiCheckCircle,
} from 'react-icons/fi';
import { authService, authorityService, adminService } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import SEO from '../components/SEO';
import './AdminSecurity.css';

/**
 * Authority Handover Console (native super-admin only).
 *
 * Lets a native super-admin temporarily delegate per-page super-admin authority to
 * a regular ADMIN user, capped at 24h, scoped to a chosen subset of the 10
 * super-admin pages, and audit-logged end-to-end. The grantee receives elevated
 * authority transparently — their JWT is force-refreshed and the gateway elevates
 * their X-User-Role only on paths matching the granted scopes.
 *
 * Also exposes the resource-lock surface: a native super-admin can lock any
 * record so even delegated admins cannot mutate it. Locks are advisory in the UI
 * (banner + disabled actions) and enforced server-side by the lock-aware
 * controllers that opt in.
 *
 * Tabs:
 *   1. Active grants  — table of currently-effective grants with revoke action.
 *   2. Grant history  — full timeline (granted / revoked / expired) for forensics.
 *   3. Locks          — every active resource lock + release action.
 *   4. New grant      — form: pick admin, pick scopes, set duration, supply reason.
 */

const SCOPE_LABELS = {
  CURRENCIES:      { label: 'Currencies',           hint: 'Manage tenant currency catalogue' },
  NOTIFICATIONS:   { label: 'Notification templates', hint: 'Edit transactional templates' },
  LOYALTY:         { label: 'Loyalty centre',        hint: 'Tiers, points, redemption rules' },
  OPS:             { label: 'Operations console',    hint: 'Waitlist / freezes / approvals' },
  ALL_USERS:       { label: 'All users directory',   hint: 'Search & manage every user' },
  CUSTOMER_EDIT:   { label: 'Customer edit',         hint: 'Modify any customer profile' },
  ADMIN_REGISTER:  { label: 'Admin onboarding',      hint: 'Register new admin users' },
  HOME_CMS:        { label: 'Home CMS',              hint: 'Edit landing & marketing pages' },
  ACCOUNT_CMS:     { label: 'Account-page CMS',      hint: 'Edit customer account templates' },
  SUPER_DASHBOARD: { label: 'Super-admin dashboard', hint: 'Audit log, sessions, role mgmt' },
};
const ALL_SCOPES = Object.keys(SCOPE_LABELS);

// Capabilities a super-admin can lock for one binge, or across ALL binges, so a
// regular binge admin can view but not change them. Each token MUST match the
// server-side AuthorityLockGuard capability enforced by that feature's controller
// (e.g. AdminPricingController enforces "PRICING"). Add an entry here only after the
// matching mutation endpoints call authorityLockGuard.requireUnlocked(token, role).
const LOCKABLE_CAPABILITIES = [
  { value: 'PRICING', label: 'Pricing — rate codes, customer pricing, surge' },
  { value: 'TAXES', label: 'Taxes — tax rules' },
  { value: 'EVENT_TYPES', label: 'Event Types' },
  { value: 'ADD_ONS', label: 'Add-ons' },
  { value: 'CATEGORIES', label: 'Categories — event & add-on categories' },
  { value: 'VENUE_ROOMS', label: 'Venue Rooms — rooms & maintenance blocks' },
  { value: 'CANCELLATION', label: 'Cancellation — tiers & policy' },
];
const ALL_BINGES = 'ALL';

// GRANT polarity (opposite of the locks above): a TIMEZONE_CHANGE row in the
// shared resource-lock store means a binge admin is PERMITTED to change the venue
// timezone (default is DENY). Managed in its own "Timezone permissions" tab with
// grant/revoke language, and deliberately filtered OUT of the freeze "Resource
// locks" table so the two polarities never get confused. Must match the backend
// AuthorityLockGuard.TIMEZONE_CHANGE token.
const TIMEZONE_CHANGE = 'TIMEZONE_CHANGE';

const fmtTs = (iso) => {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
};
const remaining = (iso) => {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return 'expired';
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
};

export default function AuthorityHandover() {
  const [tab, setTab] = useState('active');

  const [grants, setGrants] = useState([]);
  const [grantsLoading, setGrantsLoading] = useState(false);
  const [activeOnly, setActiveOnly] = useState(true);

  const [locks, setLocks] = useState([]);
  const [locksLoading, setLocksLoading] = useState(false);

  const [admins, setAdmins] = useState([]);
  const [binges, setBinges] = useState([]);
  const [lockForm, setLockForm] = useState({ capability: LOCKABLE_CAPABILITIES[0].value, bingeId: ALL_BINGES, reason: '' });
  const [creatingLock, setCreatingLock] = useState(false);
  const confirm = useConfirm();

  const reload = useCallback(async () => {
    setGrantsLoading(true);
    try {
      const res = await authorityService.listGrants({ activeOnly, page: 0, size: 100 });
      const body = res.data?.data;
      setGrants(Array.isArray(body?.content) ? body.content : []);
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Failed to load grants');
    } finally {
      setGrantsLoading(false);
    }
  }, [activeOnly]);

  const reloadLocks = useCallback(async () => {
    setLocksLoading(true);
    try {
      const res = await authorityService.listLocks({ page: 0, size: 100 });
      const body = res.data?.data;
      // Exclude TIMEZONE_CHANGE rows — those are GRANTS (permissions), not freeze
      // locks, and are managed in the dedicated "Timezone permissions" tab.
      const rows = Array.isArray(body?.content) ? body.content : [];
      setLocks(rows.filter(l => l.resourceType !== TIMEZONE_CHANGE));
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Failed to load locks');
    } finally {
      setLocksLoading(false);
    }
  }, []);

  useEffect(() => { reload(); }, [reload]);
  useEffect(() => { reloadLocks(); }, [reloadLocks]);
  useEffect(() => {
    // Load the admin pool once for the picker; cheap, ~dozens of rows.
    // The endpoint returns either a flat array (legacy) or a Spring Page<UserDto>
    // depending on the build, so we normalise both shapes.
    authService.getAllAdmins()
      .then(res => {
        const payload = res.data?.data;
        const list = Array.isArray(payload)
          ? payload
          : (Array.isArray(payload?.content) ? payload.content : []);
        setAdmins(list);
      })
      .catch(() => { /* picker can render empty */ });
  }, []);
  useEffect(() => {
    // Binge list for the capability-lock target picker (super-admin sees all binges).
    adminService.getAdminBinges()
      .then(res => {
        const payload = res.data?.data;
        setBinges(Array.isArray(payload) ? payload : (Array.isArray(payload?.content) ? payload.content : []));
      })
      .catch(() => { /* picker still offers "All binges" */ });
  }, []);

  const onCreateCapabilityLock = async (e) => {
    e.preventDefault();
    const reason = (lockForm.reason || '').trim();
    if (reason.length < 4) { toast.error('A reason (min 4 chars) is required for the audit log'); return; }
    setCreatingLock(true);
    try {
      await authorityService.createLock({
        resourceType: lockForm.capability,
        resourceId: lockForm.bingeId,           // a binge id, or "ALL" for every binge
        reason,
      });
      const target = lockForm.bingeId === ALL_BINGES ? 'all binges' : `binge #${lockForm.bingeId}`;
      toast.success(`Locked ${lockForm.capability} for ${target}`);
      setLockForm(f => ({ ...f, reason: '' }));
      reloadLocks();
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Failed to create lock');
    } finally {
      setCreatingLock(false);
    }
  };

  const onRevoke = async (g) => {
    const result = await confirm({
      title: `Revoke authority for ${g.granteeEmail || ('user #' + g.granteeUserId)}?`,
      message: 'The grantee will lose elevated access on their next request. This is audit-logged.',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Reason for revocation',
      reasonPlaceholder: 'e.g. Task completed early, follow-up unnecessary',
      reasonMaxLength: 500,
      confirmLabel: 'Revoke',
      variant: 'danger',
    });
    if (!result) return;
    try {
      await authorityService.revokeGrant(g.id, result.reason);
      toast.success('Authority revoked');
      reload();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Revoke failed');
    }
  };

  const onReleaseLock = async (l) => {
    const result = await confirm({
      title: `Release lock on ${l.resourceType}#${l.resourceId}?`,
      message: 'Delegated admins will regain the ability to modify this record.',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Reason',
      reasonPlaceholder: 'e.g. Owner approved unblocking',
      confirmLabel: 'Release',
      variant: 'danger',
    });
    if (!result) return;
    try {
      await authorityService.releaseLock(l.id, result.reason);
      toast.success('Lock released');
      reloadLocks();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Release failed');
    }
  };

  return (
    <div className="sec-page">
      <SEO title="Authority Handover" description="Delegate temporary super-admin authority and manage resource locks." />

      <div className="sec-header">
        <div className="sec-header-copy">
          <span className="sec-kicker"><FiShield /> SUPER ADMIN</span>
          <h1>Authority Handover</h1>
          <p>
            Temporarily delegate super-admin authority to an admin, scoped to specific
            pages and capped to 24 hours. Lock individual records to keep them
            untouchable even by delegated admins. Every action is audit-logged.
          </p>
        </div>
      </div>

      <nav className="sec-tabs" role="tablist" aria-label="Authority sections">
        {[
          ['active',   'Active grants'],
          ['history',  'Grant history'],
          ['locks',    'Resource locks'],
          ['timezone', 'Timezone permissions'],
          ['new',      'New grant'],
        ].map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={tab === key}
            className={tab === key ? 'active' : ''}
            onClick={() => {
              setTab(key);
              if (key === 'history') { setActiveOnly(false); }
              if (key === 'active')  { setActiveOnly(true); }
            }}
          >
            {label}
          </button>
        ))}
      </nav>

      {(tab === 'active' || tab === 'history') && (
        <GrantsTable
          grants={grants}
          loading={grantsLoading}
          showRevoke={tab === 'active'}
          onRevoke={onRevoke}
          onReload={reload}
        />
      )}

      {tab === 'locks' && (
        <>
          <section className="sec-card" style={{ marginBottom: '1rem' }}>
            <header className="sec-card-head">
              <h2><FiLock /> Lock a capability</h2>
              <p>
                Freeze a feature so a binge admin can <strong>view but not change</strong> it —
                for one binge or across <strong>all binges</strong>. Native super-admins are never
                blocked and can release the lock here at any time.
              </p>
            </header>
            <form onSubmit={onCreateCapabilityLock} style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'flex-end' }}>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13 }}>
                <span>Capability</span>
                <select value={lockForm.capability}
                  onChange={(e) => setLockForm(f => ({ ...f, capability: e.target.value }))}>
                  {LOCKABLE_CAPABILITIES.map(c => (
                    <option key={c.value} value={c.value}>{c.label}</option>
                  ))}
                </select>
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13 }}>
                <span>Applies to</span>
                <select value={lockForm.bingeId}
                  onChange={(e) => setLockForm(f => ({ ...f, bingeId: e.target.value }))}>
                  <option value={ALL_BINGES}>All binges</option>
                  {binges.map(b => (
                    <option key={b.id} value={String(b.id)}>{b.name || `Binge #${b.id}`}</option>
                  ))}
                </select>
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, flex: 1, minWidth: 240 }}>
                <span>Reason (audit)</span>
                <input type="text" maxLength={500} value={lockForm.reason}
                  placeholder="e.g. Pricing frozen for the festival promo period"
                  onChange={(e) => setLockForm(f => ({ ...f, reason: e.target.value }))} />
              </label>
              <button type="submit" className="btn btn-primary" disabled={creatingLock}>
                <FiLock /> {creatingLock ? 'Locking…' : 'Lock'}
              </button>
            </form>
          </section>
          <LocksTable locks={locks} loading={locksLoading} onRelease={onReleaseLock} onReload={reloadLocks} />
        </>
      )}

      {tab === 'timezone' && (
        <TimezonePermissionsSection binges={binges} confirm={confirm} />
      )}

      {tab === 'new' && (
        <NewGrantForm
          admins={admins}
          onCreated={() => { setTab('active'); reload(); }}
        />
      )}
    </div>
  );
}

/**
 * Super-admin management of the venue-timezone GRANT (default-deny). Granting
 * here writes a TIMEZONE_CHANGE row to the shared resource-lock store; the
 * booking-service AuthorityLockGuard.requireTimezoneChangePermitted consults it
 * before allowing a binge admin to change a venue's IANA zone. Native
 * super-admins never need a grant.
 */
function TimezonePermissionsSection({ binges, confirm }) {
  const [grants, setGrants] = useState([]);
  const [loading, setLoading] = useState(false);
  const [form, setForm] = useState({ bingeId: ALL_BINGES, reason: '' });
  const [creating, setCreating] = useState(false);

  const targetName = useCallback((resourceId) => (
    resourceId === ALL_BINGES
      ? 'All venues'
      : (binges.find(b => String(b.id) === String(resourceId))?.name || `Binge #${resourceId}`)
  ), [binges]);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const res = await authorityService.listLocks({ type: TIMEZONE_CHANGE, page: 0, size: 100 });
      const body = res.data?.data;
      setGrants(Array.isArray(body?.content) ? body.content : []);
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Failed to load timezone permissions');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { reload(); }, [reload]);

  const onGrant = async (e) => {
    e.preventDefault();
    const reason = (form.reason || '').trim();
    if (reason.length < 4) { toast.error('A reason (min 4 chars) is required for the audit log'); return; }
    setCreating(true);
    try {
      await authorityService.createLock({
        resourceType: TIMEZONE_CHANGE,
        resourceId: form.bingeId,        // a binge id, or "ALL" for every venue
        reason,
      });
      toast.success(`Timezone-change permission granted for ${targetName(form.bingeId)}`);
      setForm({ bingeId: ALL_BINGES, reason: '' });
      reload();
    } catch (err) {
      // A unique (type,id) row already exists → already granted.
      toast.error(err?.response?.data?.message || 'Failed to grant permission (it may already be granted)');
    } finally {
      setCreating(false);
    }
  };

  const onRevoke = async (g) => {
    const result = await confirm({
      title: `Revoke timezone permission for ${targetName(g.resourceId)}?`,
      message: 'That venue’s admin will no longer be able to change its timezone. Native super-admins are unaffected.',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Reason',
      reasonPlaceholder: 'e.g. Venue zone finalized, no further changes needed',
      confirmLabel: 'Revoke',
      variant: 'danger',
    });
    if (!result) return;
    try {
      await authorityService.releaseLock(g.id, result.reason);
      toast.success('Timezone permission revoked');
      reload();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Revoke failed');
    }
  };

  return (
    <section className="sec-card">
      <header className="sec-card-head">
        <div>
          <h2><FiClock /> Venue timezone — change permissions</h2>
          <p>
            Changing a venue’s timezone reinterprets the wall-clock of every existing
            booking, so it is <strong>super-admin only by default</strong>. Grant the
            ability to a single venue or to <strong>all venues</strong>. You (native
            super-admin) can always change any venue’s zone without a grant.
          </p>
        </div>
        <button className="btn btn-ghost" onClick={reload} disabled={loading}>
          <FiRefreshCw /> Refresh
        </button>
      </header>

      <form onSubmit={onGrant} style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'flex-end', marginBottom: '1rem' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13 }}>
          <span>Grant to</span>
          <select value={form.bingeId} onChange={(e) => setForm(f => ({ ...f, bingeId: e.target.value }))}>
            <option value={ALL_BINGES}>All venues</option>
            {binges.map(b => (
              <option key={b.id} value={String(b.id)}>{b.name || `Binge #${b.id}`}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, flex: 1, minWidth: 240 }}>
          <span>Reason (audit)</span>
          <input type="text" maxLength={500} value={form.reason}
            placeholder="e.g. Venue relocating to a new region; admin to set the zone"
            onChange={(e) => setForm(f => ({ ...f, reason: e.target.value }))} />
        </label>
        <button type="submit" className="btn btn-primary" disabled={creating}>
          <FiCheckCircle /> {creating ? 'Granting…' : 'Grant permission'}
        </button>
      </form>

      {loading && <div className="sec-empty">Loading…</div>}
      {!loading && grants.length === 0 && (
        <div className="sec-empty"><FiLock /> No grants — only super-admins can change venue timezones.</div>
      )}
      {!loading && grants.length > 0 && (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr>
                <th>Permitted venue</th>
                <th>Granted by</th>
                <th>Granted at</th>
                <th>Reason</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {grants.map(g => (
                <tr key={g.id}>
                  <td>
                    <div style={{ fontWeight: 600 }}>
                      <FiCheckCircle /> {targetName(g.resourceId)}
                    </div>
                    {g.resourceId === ALL_BINGES && (
                      <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>every venue</div>
                    )}
                  </td>
                  <td>
                    <div style={{ fontSize: 13 }}>{g.lockedByName || ('User #' + g.lockedBy)}</div>
                  </td>
                  <td>{fmtTs(g.lockedAt)}</td>
                  <td style={{ maxWidth: 320 }}>
                    <div style={{ fontSize: 12, whiteSpace: 'normal', wordBreak: 'break-word' }}>{g.reason}</div>
                  </td>
                  <td>
                    <button className="btn btn-danger btn-sm" onClick={() => onRevoke(g)}>
                      <FiSlash /> Revoke
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function GrantsTable({ grants, loading, showRevoke, onRevoke, onReload }) {
  return (
    <section className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>{showRevoke ? 'Active grants' : 'Grant history'}</h2>
          <p>{showRevoke
            ? 'Currently-effective delegations. Revoke any of them at any time.'
            : 'Every grant ever issued, including revoked and expired entries.'}</p>
        </div>
        <button className="btn btn-ghost" onClick={onReload} disabled={loading}>
          <FiRefreshCw /> Refresh
        </button>
      </div>

      {loading && <div className="sec-empty">Loading…</div>}
      {!loading && grants.length === 0 && (
        <div className="sec-empty">
          <FiUsers /> No grants to display.
        </div>
      )}
      {!loading && grants.length > 0 && (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr>
                <th>Grantee</th>
                <th>Scopes</th>
                <th>Granted by</th>
                <th>Granted</th>
                <th>Expires</th>
                <th>State</th>
                <th>Reason</th>
                {showRevoke && <th></th>}
              </tr>
            </thead>
            <tbody>
              {grants.map((g) => (
                <tr key={g.id}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{g.granteeName || ('User #' + g.granteeUserId)}</div>
                    <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{g.granteeEmail}</div>
                  </td>
                  <td>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                      {(g.scopes || []).map(s => (
                        <span key={s} className="chip" title={SCOPE_LABELS[s]?.hint || s}>
                          {SCOPE_LABELS[s]?.label || s}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td>
                    <div style={{ fontSize: 13 }}>{g.grantedByName || ('User #' + g.grantedBy)}</div>
                    <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{g.grantedByEmail}</div>
                  </td>
                  <td title={fmtTs(g.grantedAt)}>{fmtTs(g.grantedAt)}</td>
                  <td title={fmtTs(g.expiresAt)}>
                    {fmtTs(g.expiresAt)}
                    {g.active && (
                      <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                        <FiClock /> {remaining(g.expiresAt)} left
                      </div>
                    )}
                  </td>
                  <td>
                    {g.active ? (
                      <span className="badge badge-success"><FiCheckCircle /> Active</span>
                    ) : g.revokedAt ? (
                      <span className="badge badge-danger"><FiSlash /> Revoked</span>
                    ) : (
                      <span className="badge badge-muted"><FiClock /> Expired</span>
                    )}
                  </td>
                  <td style={{ maxWidth: 280 }}>
                    <div style={{ fontSize: 12, whiteSpace: 'normal', wordBreak: 'break-word' }}>{g.reason}</div>
                    {g.revokeReason && (
                      <div style={{ fontSize: 12, marginTop: 4, color: 'var(--danger, #c0392b)' }}>
                        Revoked: {g.revokeReason}
                      </div>
                    )}
                  </td>
                  {showRevoke && (
                    <td>
                      <button className="btn btn-danger btn-sm" onClick={() => onRevoke(g)}>
                        <FiSlash /> Revoke
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function LocksTable({ locks, loading, onRelease, onReload }) {
  return (
    <section className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Resource locks</h2>
          <p>
            A locked record cannot be modified by anyone except the owner who placed
            the lock — not even by an admin who currently holds an authority grant.
          </p>
        </div>
        <button className="btn btn-ghost" onClick={onReload} disabled={loading}>
          <FiRefreshCw /> Refresh
        </button>
      </div>

      {loading && <div className="sec-empty">Loading…</div>}
      {!loading && locks.length === 0 && (
        <div className="sec-empty"><FiUnlock /> No active locks.</div>
      )}
      {!loading && locks.length > 0 && (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr>
                <th>Resource</th>
                <th>Locked by</th>
                <th>Locked at</th>
                <th>Reason</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {locks.map(l => (
                <tr key={l.id}>
                  <td>
                    <div style={{ fontWeight: 600 }}>
                      <FiLock /> {l.resourceType}
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--text-muted)', wordBreak: 'break-all' }}>
                      {l.resourceId}
                    </div>
                  </td>
                  <td>
                    <div style={{ fontSize: 13 }}>{l.lockedByName || ('User #' + l.lockedBy)}</div>
                    <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{l.lockedByEmail}</div>
                  </td>
                  <td>{fmtTs(l.lockedAt)}</td>
                  <td style={{ maxWidth: 320 }}>
                    <div style={{ fontSize: 12, whiteSpace: 'normal', wordBreak: 'break-word' }}>
                      {l.reason}
                    </div>
                  </td>
                  <td>
                    <button className="btn btn-secondary btn-sm" onClick={() => onRelease(l)}>
                      <FiUnlock /> Release
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function NewGrantForm({ admins, onCreated }) {
  const [granteeUserId, setGranteeUserId] = useState('');
  const [scopes, setScopes] = useState(new Set());
  const [durationHours, setDurationHours] = useState(4);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const eligible = useMemo(
    () => admins.filter(a => a.role === 'ADMIN' && a.active),
    [admins]
  );

  const toggleScope = (s) => {
    setScopes(prev => {
      const next = new Set(prev);
      if (next.has(s)) next.delete(s); else next.add(s);
      return next;
    });
  };

  const reasonValid = reason.trim().length >= 8 && reason.trim().length <= 500;
  const canSubmit = granteeUserId && scopes.size > 0 && reasonValid && !submitting
    && durationHours >= 1 && durationHours <= 24;

  const submit = async (e) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await authorityService.createGrant({
        granteeUserId: Number(granteeUserId),
        scopes: Array.from(scopes),
        durationHours: Number(durationHours),
        reason: reason.trim(),
      });
      toast.success('Authority granted');
      // Reset
      setGranteeUserId('');
      setScopes(new Set());
      setDurationHours(4);
      setReason('');
      onCreated?.();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Grant failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2><FiUserPlus /> New authority grant</h2>
          <p>
            Choose an admin, the pages they should temporarily co-own, and how long
            the grant lasts. Best practice: grant the smallest viable set of scopes
            for the shortest viable duration.
          </p>
        </div>
      </div>

      <form onSubmit={submit} className="sec-form" style={{ display: 'grid', gap: 16, padding: 16 }}>
        <div className="sec-warn" style={{ display: 'flex', gap: 8, alignItems: 'flex-start',
          background: 'rgba(255,193,7,0.08)', border: '1px solid rgba(255,193,7,0.35)',
          borderRadius: 8, padding: 12 }}>
          <FiAlertTriangle style={{ flex: '0 0 auto', marginTop: 2 }} />
          <div style={{ fontSize: 13 }}>
            Granting authority hands a regular admin elevated access to your platform
            until the duration expires. Use a unique, traceable reason — the audit log
            will preserve it forever.
          </div>
        </div>

        <label>
          <span style={{ display: 'block', fontWeight: 600, marginBottom: 4 }}>Grantee admin</span>
          <select
            value={granteeUserId}
            onChange={(e) => setGranteeUserId(e.target.value)}
            required
            style={{ width: '100%', padding: 8 }}
          >
            <option value="">Select an admin…</option>
            {eligible.map(a => (
              <option key={a.id} value={a.id}>
                {a.firstName} {a.lastName} — {a.email}
              </option>
            ))}
          </select>
          {eligible.length === 0 && (
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>
              No eligible admins (need role=ADMIN and active).
            </div>
          )}
        </label>

        <fieldset style={{ border: '1px solid var(--border, #2a2f3a)', borderRadius: 8, padding: 12 }}>
          <legend style={{ fontWeight: 600, padding: '0 6px' }}>Scopes</legend>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 8 }}>
            {ALL_SCOPES.map(s => (
              <label key={s} style={{ display: 'flex', gap: 8, alignItems: 'flex-start',
                padding: 8, border: '1px solid var(--border, #2a2f3a)', borderRadius: 6,
                background: scopes.has(s) ? 'rgba(56,189,248,0.08)' : 'transparent', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={scopes.has(s)}
                  onChange={() => toggleScope(s)}
                  style={{ marginTop: 4 }}
                />
                <div>
                  <div style={{ fontWeight: 600, fontSize: 13 }}>{SCOPE_LABELS[s].label}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{SCOPE_LABELS[s].hint}</div>
                </div>
              </label>
            ))}
          </div>
          <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
            <button type="button" className="btn btn-ghost btn-sm"
              onClick={() => setScopes(new Set(ALL_SCOPES))}><FiCheck /> Select all</button>
            <button type="button" className="btn btn-ghost btn-sm"
              onClick={() => setScopes(new Set())}>Clear</button>
          </div>
        </fieldset>

        <label>
          <span style={{ display: 'block', fontWeight: 600, marginBottom: 4 }}>
            Duration (hours): <strong>{durationHours}h</strong>
          </span>
          <input
            type="range"
            min="1"
            max="24"
            step="1"
            value={durationHours}
            onChange={(e) => setDurationHours(Number(e.target.value))}
            style={{ width: '100%' }}
          />
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--text-muted)' }}>
            <span>1h</span><span>4h (default)</span><span>24h (max)</span>
          </div>
        </label>

        <label>
          <span style={{ display: 'block', fontWeight: 600, marginBottom: 4 }}>
            Reason <span style={{ color: 'var(--danger, #c0392b)' }}>*</span>
          </span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Why is this grant necessary? Reference a ticket, incident, or task. Minimum 8 chars."
            rows={3}
            minLength={8}
            maxLength={500}
            required
            style={{ width: '100%', padding: 8, resize: 'vertical' }}
          />
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            {reason.length}/500 — recorded in the audit log for forensic review.
          </div>
        </label>

        <div style={{ display: 'flex', gap: 8 }}>
          <button type="submit" className="btn btn-primary" disabled={!canSubmit}>
            <FiUserPlus /> {submitting ? 'Granting…' : 'Grant authority'}
          </button>
        </div>
      </form>
    </section>
  );
}
