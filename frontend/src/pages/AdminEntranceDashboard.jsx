import { useState, useEffect, useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { adminService } from '../services/endpoints';
import { parseServerDate } from '../services/timeFormat';
import { currencyForCountry } from '../utils/currency';
import SEO from '../components/SEO';
import TimezonePicker from '../components/TimezonePicker';
import { SkeletonGrid } from '../components/ui/Skeleton';
import { toast } from 'react-toastify';
import {
  FiArrowRight,
  FiMapPin,
  FiUser,
  FiMail,
  FiShield,
  FiUsers,
  FiActivity,
  FiSettings,
  FiSearch,
  FiClock,
  FiCheckCircle,
  FiXCircle,
  FiInbox,
} from 'react-icons/fi';
import './Entrance.css';

const RECENT_ADMIN_KEY = 'sk-recent-admin-binges';
const MAX_RECENT = 3;

const saveRecentBinge = (binge) => {
  try {
    const stored = JSON.parse(localStorage.getItem(RECENT_ADMIN_KEY) || '[]');
    const filtered = stored.filter((b) => b.id !== binge.id);
    filtered.unshift({ id: binge.id, name: binge.name, address: binge.address, ts: Date.now() });
    localStorage.setItem(RECENT_ADMIN_KEY, JSON.stringify(filtered.slice(0, MAX_RECENT)));
  } catch { /* ignore */ }
};

const getRecentBinges = () => {
  try { return JSON.parse(localStorage.getItem(RECENT_ADMIN_KEY) || '[]'); } catch { return []; }
};

export default function AdminEntranceDashboard() {
  const { user, isSuperAdmin } = useAuth();
  const { selectBinge } = useBinge();
  const confirm = useConfirm();
  const navigate = useNavigate();
  const [binges, setBinges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  // Super-admin-only: pending binge approval requests.
  const [pendingBinges, setPendingBinges] = useState([]);
  const [pendingLoading, setPendingLoading] = useState(false);
  const [decidingId, setDecidingId] = useState(null);

  // Note: We intentionally do NOT clearBinge() on mount. The previously-selected
  // binge is preserved so admins can refresh per-binge URLs and deep-link back.
  // Switching venues is an explicit action via the venue card or sidebar venue
  // button (which calls clearBinge through navigation back here).

  useEffect(() => {
    (async () => {
      try {
        const res = await adminService.getAdminBinges();
        setBinges(res.data.data || res.data || []);
      } catch {
        toast.error('Failed to load binges');
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  // Super-admin only: load pending approval queue.
  useEffect(() => {
    if (!isSuperAdmin) return;
    setPendingLoading(true);
    (async () => {
      try {
        const res = await adminService.getPendingBinges();
        setPendingBinges(res.data.data || res.data || []);
      } catch {
        // Non-fatal; entrance dashboard still works without pending list.
      } finally {
        setPendingLoading(false);
      }
    })();
  }, [isSuperAdmin]);

  const refreshPending = async () => {
    try {
      const res = await adminService.getPendingBinges();
      setPendingBinges(res.data.data || res.data || []);
    } catch { /* ignore */ }
  };

  // Approval confirmation: the super-admin verifies the venue's timezone (with live
  // venue-local time), country → payment currency, decides whether the tax system
  // runs at this venue, AND which binge-operation modules the owning admin starts
  // with (V71 permission matrix) — BEFORE the venue goes live.
  const [approveModal, setApproveModal] = useState(null); // { binge, taxesEnabled, disabledModules: Set, accessRemarks }

  const handleApprove = (binge) => {
    if (decidingId) return;
    setApproveModal({
      binge,
      // Pre-filled with what the admin submitted; the super-admin can correct any of
      // these before the venue goes live (admins can't set timezone/currency at all).
      timezone: binge.timezone || '',
      country: (binge.country || '').toUpperCase(),
      taxesEnabled: binge.taxesEnabled !== false,
      disabledModules: new Set(),
      accessRemarks: '',
    });
  };

  const toggleApprovalModule = (key) => {
    setApproveModal((m) => {
      const next = new Set(m.disabledModules);
      if (next.has(key)) next.delete(key); else next.add(key);
      return { ...m, disabledModules: next };
    });
  };

  const confirmApprove = async () => {
    const { binge, taxesEnabled, disabledModules, accessRemarks, timezone, country } = approveModal;
    // Approval is the genuineness gate — never let a venue go live with a missing
    // country (currency/tax/payment methods derive from it) or timezone.
    if (!/^[A-Z]{2}$/.test(country || '')) {
      toast.error('Set a valid 2-letter country code before approving.');
      return;
    }
    if (!timezone) {
      toast.error('Set the venue timezone before approving.');
      return;
    }
    setDecidingId(binge.id);
    try {
      await adminService.approveBinge(binge.id, {
        taxesEnabled,
        timezone: timezone || undefined,
        // Only send country when the super-admin actually changed it, so a normal
        // approval never re-derives currency/taxes needlessly.
        country: (country && country !== (binge.country || '').toUpperCase()) ? country : undefined,
        disabledModules: Array.from(disabledModules || []),
        accessRemarks: accessRemarks || undefined,
      });
      toast.success(`Approved: ${binge.name}`);
      setApproveModal(null);
      // Refresh both queues since the approved binge now also appears in the
      // main admin list as active.
      await Promise.all([
        refreshPending(),
        adminService.getAdminBinges().then((r) => setBinges(r.data.data || r.data || [])),
      ]);
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Failed to approve binge');
    } finally {
      setDecidingId(null);
    }
  };

  const venueLocalTime = (timezone) => {
    try {
      return new Intl.DateTimeFormat(undefined, {
        timeZone: timezone, weekday: 'short', hour: '2-digit', minute: '2-digit',
      }).format(new Date());
    } catch {
      return null;
    }
  };

  const handleReject = async (binge) => {
    if (decidingId) return;
    const result = await confirm({
      title: `Reject "${binge.name}"?`,
      message: 'Rejecting removes this binge from the approval queue. The maker will be notified with your reason.',
      confirmLabel: 'Reject binge',
      variant: 'danger',
      withReason: true,
      reasonRequired: false,
      reasonLabel: 'Reason (visible in audit log)',
      reasonPlaceholder: 'Add an optional note explaining why…',
    });
    if (!result) return;
    const reason = result.reason || '';
    setDecidingId(binge.id);
    try {
      await adminService.rejectBinge(binge.id, reason);
      toast.success(`Rejected: ${binge.name}`);
      await refreshPending();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Failed to reject binge');
    } finally {
      setDecidingId(null);
    }
  };

  const handleSelect = (binge) => {
    saveRecentBinge(binge);
    // The store normalises to the canonical selected-binge shape — pass the
    // full object so support contacts / timezone / policies aren't dropped.
    selectBinge(binge);
    toast.success(`Entered: ${binge.name}`);
    navigate('/admin/dashboard');
  };

  const admin = user || {};
  const displayName = [admin.firstName, admin.lastName].filter(Boolean).join(' ') || 'Admin';
  const roleLabel = isSuperAdmin ? 'Super Admin' : 'Admin';
  const activeCount = binges.filter((b) => b.active).length;
  const inactiveCount = binges.length - activeCount;
  const activeBinges = binges.filter((b) => b.active);

  const recentBinges = useMemo(() => {
    const recent = getRecentBinges();
    const activeIds = new Set(activeBinges.map((b) => b.id));
    return recent.filter((r) => activeIds.has(r.id));
  }, [activeBinges]);

  const filteredBinges = useMemo(() => {
    if (!search.trim()) return activeBinges;
    const q = search.trim().toLowerCase();
    return activeBinges.filter((b) => (b.name || '').toLowerCase().includes(q) || (b.address || '').toLowerCase().includes(q));
  }, [activeBinges, search]);

  if (loading) {
    return (
      <div className="container entrance">
        <SEO title="Admin Dashboard" description="SK Binge Galaxy admin overview." />
        <SkeletonGrid count={4} columns={2} />
      </div>
    );
  }

  return (
    <div className="container entrance">
      <SEO title="Admin Dashboard" description="SK Binge Galaxy admin overview." />

      {/* ── Hero ──────────────────────────────────────────────────── */}
      <section className="entrance-hero">
        <div className="entrance-welcome">
          <span className="entrance-kicker"><FiShield style={{ verticalAlign: '-2px', marginRight: 4 }} /> {roleLabel} Console</span>
          <h1>Welcome, {admin.firstName || 'Admin'}!</h1>
          <p>
            This is your admin control centre. Select a venue below to manage its bookings,
            catalog, and operations — or use the quick links to manage your account and platform settings.
          </p>
          <div className="entrance-badges">
            <span className="entrance-badge">{binges.length} total venue{binges.length !== 1 ? 's' : ''}</span>
            <span className="entrance-badge entrance-badge-success">{activeCount} active</span>
            {inactiveCount > 0 && <span className="entrance-badge entrance-badge-warning">{inactiveCount} inactive</span>}
          </div>
        </div>

        <aside className="entrance-profile">
          <span className="entrance-kicker">Your Profile</span>
          <h2>{displayName}</h2>
          <div className="entrance-profile-list">
            <div>
              <span><FiMail style={{ marginRight: '0.3rem', verticalAlign: 'middle' }} /> Email</span>
              <strong>{admin.email || 'Not available'}</strong>
            </div>
            <div>
              <span><FiShield style={{ marginRight: '0.3rem', verticalAlign: 'middle' }} /> Role</span>
              <strong>{roleLabel}</strong>
            </div>
          </div>
          <div className="entrance-profile-inline-actions">
            <Link to="/admin/account" className="btn btn-primary btn-sm">Edit Profile</Link>
          </div>
        </aside>
      </section>

      {/* ── Quick stats ───────────────────────────────────────────── */}
      <div className="entrance-stats">
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiMapPin /></span>
          <strong>{binges.length}</strong>
          <p>Total Venues</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiActivity /></span>
          <strong>{activeCount}</strong>
          <p>Active Venues</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiSettings /></span>
          <strong>{inactiveCount}</strong>
          <p>Inactive Venues</p>
        </div>
      </div>
      {/* ── Pending approvals (SUPER_ADMIN only) ─────────────────── */}
      {isSuperAdmin && (
        <section className="entrance-panel">
          <div className="entrance-panel-head">
            <div>
              <span className="entrance-kicker"><FiInbox style={{ verticalAlign: '-2px', marginRight: 4 }} /> Approval Queue</span>
              <h2>
                Pending binge requests
                {pendingBinges.length > 0 && (
                  <span className="entrance-badge entrance-badge-warning" style={{ marginLeft: '0.6rem', verticalAlign: 'middle' }}>
                    {pendingBinges.length} waiting
                  </span>
                )}
              </h2>
            </div>
          </div>

          {pendingLoading ? (
            <SkeletonGrid count={2} columns={2} />
          ) : pendingBinges.length === 0 ? (
            <div className="entrance-empty">
              <span className="entrance-empty-icon"><FiCheckCircle /></span>
              <h3>All caught up</h3>
              <p>No new binge requests are awaiting your approval.</p>
            </div>
          ) : (
            <div className="entrance-grid">
              {pendingBinges.map((b) => {
                const busy = decidingId === b.id;
                return (
                  <article key={b.id} className="entrance-venue-card entrance-venue-card-recent">
                    <span className="entrance-kicker"><FiInbox /> Pending approval</span>
                    <h3>{b.name}</h3>
                    {b.address && <p>{b.address}</p>}
                    <p style={{ fontSize: '0.85rem', opacity: 0.75 }}>
                      Requested by admin #{b.adminId}
                      {b.createdAt && ` • ${parseServerDate(b.createdAt)?.toLocaleString() || ''}`}
                    </p>
                    <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginTop: '0.75rem' }}>
                      <button
                        type="button"
                        className="btn btn-primary btn-sm"
                        disabled={busy}
                        onClick={() => handleApprove(b)}
                      >
                        <FiCheckCircle style={{ marginRight: 4 }} />
                        {busy ? 'Working…' : 'Approve'}
                      </button>
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        disabled={busy}
                        onClick={() => handleReject(b)}
                      >
                        <FiXCircle style={{ marginRight: 4 }} />
                        Reject
                      </button>
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </section>
      )}

      {approveModal && (() => {
        const b = approveModal.binge;
        const ccy = b.currency || currencyForCountry(b.country);
        return (
          <div className="modal-overlay" onClick={() => !decidingId && setApproveModal(null)}>
            <div className="modal-content" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '600px' }}>
              <div className="modal-header">
                <h2>Approve “{b.name}”</h2>
                <button type="button" className="btn btn-secondary btn-sm"
                        onClick={() => setApproveModal(null)} disabled={!!decidingId}><FiXCircle /></button>
              </div>
              <p style={{ marginTop: 0, color: 'var(--text-muted)' }}>
                Verify this is a genuine venue and correct anything before it goes live —
                bookings, slot times, prices, taxes and payment methods all derive from these.
              </p>

              {/* ── Genuineness review: everything the admin submitted, read-only, so a
                     super-admin can judge whether this is a real venue. ── */}
              <div className="entrance-review" style={{
                border: '1px solid var(--border)', borderRadius: 10, padding: '0.7rem 0.85rem',
                margin: '0.5rem 0 0.9rem', display: 'grid', gridTemplateColumns: '1fr 1fr',
                gap: '0.5rem 1rem', fontSize: '0.84rem',
              }}>
                <div style={{ gridColumn: '1 / -1' }}>
                  <small style={{ color: 'var(--text-muted)' }}>Address</small>
                  <div>{b.address || [b.addressLine1, b.addressLine2, b.city, b.state, b.postalCode].filter(Boolean).join(', ') || '— none provided'}</div>
                  <small style={{ color: (b.latitude && b.longitude) ? 'var(--success-text, green)' : 'var(--warning-text, #b45309)' }}>
                    {(b.latitude && b.longitude) ? `Geocoded (${b.latitude}, ${b.longitude})` : 'Not geocoded — no map coordinates'}
                  </small>
                </div>
                <div>
                  <small style={{ color: 'var(--text-muted)' }}>Public contact</small>
                  <div>{b.supportEmail || '— no email'}</div>
                  <div>{b.supportPhone ? `${b.supportPhoneCountryCode || ''} ${b.supportPhone}` : '— no phone'}</div>
                </div>
                <div>
                  <small style={{ color: 'var(--text-muted)' }}>Owner (private)</small>
                  <div>{b.ownerEmail || '— no email'}</div>
                  <div>{b.ownerPhone ? `${b.ownerPhoneCountryCode || ''} ${b.ownerPhone}` : '— no phone'}</div>
                </div>
                <div>
                  <small style={{ color: 'var(--text-muted)' }}>Operating hours</small>
                  <div>{(b.openTime && b.closeTime) ? `${String(b.openTime).slice(0, 5)}–${String(b.closeTime).slice(0, 5)}` : '— not set'}</div>
                </div>
                <div>
                  <small style={{ color: 'var(--text-muted)' }}>Submitted</small>
                  <div>{b.createdAt ? new Date(b.createdAt).toLocaleString() : '—'}</div>
                </div>
              </div>

              {/* ── Correctable at approval ── */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.6rem 1rem', margin: '0.4rem 0 0.75rem' }}>
                <div>
                  <small style={{ color: 'var(--text-muted)' }}>Country (sets currency, taxes, payment methods)</small>
                  <input
                    type="text" maxLength={2}
                    value={approveModal.country}
                    onChange={(e) => setApproveModal((m) => ({ ...m, country: e.target.value.toUpperCase().replace(/[^A-Z]/g, '') }))}
                    placeholder="e.g. US, IN, AE"
                    style={{ width: '100%', textTransform: 'uppercase' }}
                  />
                  {/^[A-Z]{2}$/.test(approveModal.country) ? (
                    <small>
                      Payment currency: <strong>{currencyForCountry(approveModal.country)}</strong>
                      {approveModal.country !== (b.country || '').toUpperCase() && b.country
                        ? ` (was ${b.country} → ${ccy})` : ''}
                    </small>
                  ) : (
                    <small style={{ color: 'var(--warning-text, #b45309)' }}>Enter a valid 2-letter country code.</small>
                  )}
                </div>
                <div>
                  <small style={{ color: 'var(--text-muted)' }}>Timezone — set it freely</small>
                  <TimezonePicker
                    id="approve-timezone"
                    value={approveModal.timezone}
                    onChange={(tz) => setApproveModal((m) => ({ ...m, timezone: tz }))}
                    required
                  />
                  {approveModal.timezone && venueLocalTime(approveModal.timezone) && (
                    <small>Local time now: {venueLocalTime(approveModal.timezone)}</small>
                  )}
                </div>
              </div>
              <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', margin: '0.5rem 0 1rem' }}>
                <input
                  type="checkbox"
                  checked={approveModal.taxesEnabled}
                  onChange={(e) => setApproveModal((m) => ({ ...m, taxesEnabled: e.target.checked }))}
                  style={{ marginTop: '0.2rem' }}
                />
                <span>
                  <strong>Enable the tax system for this venue</strong>
                  <br />
                  <small style={{ color: 'var(--text-muted)' }}>
                    The venue country's standard tax (e.g. GST/VAT) is auto-assigned and applied to
                    every booking. You can toggle this later or edit rules in the tax console.
                  </small>
                </span>
              </label>

              {/* V71: which binge-operation options the owning admin may use from day one.
                  Everything starts enabled; untick to disable. Editable later on the
                  binge's About / Access page. */}
              <div style={{ margin: '0.25rem 0 0.9rem' }}>
                <strong style={{ fontSize: '0.9rem' }}>Options available to this venue's admin</strong>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(170px, 1fr))', gap: '0.25rem 0.75rem', marginTop: '0.4rem', maxHeight: 170, overflowY: 'auto' }}>
                  {[
                    ['REPORTS', 'Reports'], ['MESSAGES', 'Messages'], ['VENUE', 'Venue'],
                    ['ROOMS', 'Rooms'], ['EVENT_TYPES', 'Event Types'], ['RATE_CODES', 'Rate Codes'],
                    ['SURGE_RULES', 'Surge Rules'], ['BLOCKED_DATES', 'Blocked Dates'], ['SLOT_HOLDS', 'Slot Holds'],
                    ['PEOPLE', 'People'], ['USERS', 'Users'], ['WAITLIST', 'Waitlist'],
                    ['CUSTOMER_FREEZES', 'Customer Freezes'], ['RISK_FLAGS', 'Risk Flags'],
                    ['SUPPORT_CONSOLE', 'Support Console'], ['DISPUTES', 'Disputes'], ['FAILED_REFUNDS', 'Failed Refunds'],
                  ].map(([key, label]) => (
                    <label key={key} style={{ display: 'flex', alignItems: 'center', gap: '0.35rem', fontSize: '0.82rem' }}>
                      <input type="checkbox"
                        checked={!approveModal.disabledModules.has(key)}
                        onChange={() => toggleApprovalModule(key)} />
                      {label}
                    </label>
                  ))}
                </div>
                <small style={{ color: 'var(--text-muted)' }}>
                  Unticked options return “disabled by Super Admin” for this admin. Change anytime
                  from Binges → the venue's About page.
                </small>
              </div>
              <div className="input-group" style={{ marginBottom: '0.9rem' }}>
                <label>Access remarks (optional)</label>
                <input value={approveModal.accessRemarks}
                  onChange={(e) => setApproveModal((m) => ({ ...m, accessRemarks: e.target.value }))}
                  placeholder="Why any options are off, onboarding notes…" maxLength={1000} />
              </div>
              <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                <button type="button" className="btn btn-secondary"
                        onClick={() => setApproveModal(null)} disabled={!!decidingId}>Cancel</button>
                <button type="button" className="btn btn-primary" onClick={confirmApprove} disabled={!!decidingId}>
                  <FiCheckCircle style={{ marginRight: 4 }} />
                  {decidingId ? 'Approving…' : 'Confirm & Approve'}
                </button>
              </div>
            </div>
          </div>
        );
      })()}

      {/* ── Recent venues ─────────────────────────────────────────── */}
      {recentBinges.length > 0 && (
        <section className="entrance-panel">
          <div className="entrance-panel-head">
            <div>
              <span className="entrance-kicker"><FiClock style={{ verticalAlign: '-2px', marginRight: 4 }} /> Recently Managed</span>
              <h2>Jump back in</h2>
            </div>
          </div>
          <div className="entrance-grid">
            {recentBinges.map((binge) => (
              <article
                key={binge.id}
                className="entrance-venue-card entrance-venue-card-recent"
                onClick={() => handleSelect(binge)}
                onKeyDown={(e) => e.key === 'Enter' && handleSelect(binge)}
                tabIndex={0}
                role="button"
                aria-label={`Enter ${binge.name}`}
              >
                <span className="entrance-kicker"><FiClock /> Recent</span>
                <h3>{binge.name}</h3>
                {binge.address && <p>{binge.address}</p>}
                <span className="btn btn-primary btn-sm entrance-venue-enter">
                  Enter <FiArrowRight />
                </span>
              </article>
            ))}
          </div>
        </section>
      )}

      {/* ── Venue selection ───────────────────────────────────────── */}
      <section className="entrance-panel">
        <div className="entrance-panel-head">
          <div>
            <span className="entrance-kicker">Venues</span>
            <h2>Enter a venue to manage</h2>
          </div>
          <Link to="/admin/binges" className="entrance-inline-link">Manage venues →</Link>
        </div>

        {activeBinges.length > 0 && (
          <div className="entrance-search">
            <FiSearch className="entrance-search-icon" />
            <input
              type="text"
              placeholder="Search venues by name or address…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="entrance-search-input"
            />
          </div>
        )}

        {filteredBinges.length === 0 ? (
          <div className="entrance-empty">
            <span className="entrance-empty-icon"><FiMapPin /></span>
            <h3>{search.trim() ? 'No matching venues' : 'No venues yet'}</h3>
            <p>{search.trim() ? 'Try a different search term.' : 'Create your first venue from the Binges management page.'}</p>
            {!search.trim() && <Link to="/admin/binges" className="btn btn-primary btn-sm">Create Venue</Link>}
          </div>
        ) : (
          <div className="entrance-grid">
            {filteredBinges.map((binge) => (
              <article
                key={binge.id}
                className="entrance-venue-card"
                onClick={() => handleSelect(binge)}
                onKeyDown={(e) => e.key === 'Enter' && handleSelect(binge)}
                tabIndex={0}
                role="button"
                aria-label={`Enter ${binge.name}`}
              >
                <span className="entrance-kicker"><FiMapPin /> Venue</span>
                <h3>{binge.name}</h3>
                {binge.address && <p>{binge.address}</p>}
                <span className="btn btn-primary btn-sm entrance-venue-enter">
                  Enter <FiArrowRight />
                </span>
              </article>
            ))}
          </div>
        )}
      </section>

      {/* ── Quick navigation ──────────────────────────────────────── */}
      <section className="entrance-panel">
        <div className="entrance-panel-head">
          <div>
            <span className="entrance-kicker">Quick Links</span>
            <h2>Platform Tools</h2>
          </div>
        </div>
        <div className="entrance-nav-grid">
          <Link to="/admin/binges" className="entrance-nav-card">
            <h3><FiMapPin /> Manage Binges</h3>
            <p>Create, edit, and toggle venues</p>
          </Link>
          <Link to="/admin/account" className="entrance-nav-card">
            <h3><FiUser /> Account Settings</h3>
            <p>Update your profile and credentials</p>
          </Link>
          {isSuperAdmin && (
            <Link to="/admin/all-users" className="entrance-nav-card">
              <h3><FiUsers /> All Users</h3>
              <p>Manage admins and customers across the platform</p>
            </Link>
          )}
          {isSuperAdmin && (
            <Link to="/admin/register" className="entrance-nav-card">
              <h3><FiShield /> Add Admin</h3>
              <p>Register a new admin account</p>
            </Link>
          )}
          {isSuperAdmin && (
            <Link to="/admin/super" className="entrance-nav-card">
              <h3><FiShield /> Super Admin Console</h3>
              <p>Audit log, sessions, promote / demote admins</p>
            </Link>
          )}
          {isSuperAdmin && (
            <Link to="/admin/home-editor" className="entrance-nav-card">
              <h3><FiSettings /> Edit Home Page</h3>
              <p>Update hero, packages and gallery seen by every visitor</p>
            </Link>
          )}
          {isSuperAdmin && (
            <Link to="/admin/terms-editor" className="entrance-nav-card">
              <h3><FiSettings /> Terms &amp; Legal Content</h3>
              <p>Edit the Terms customers and new admins must accept at sign-up</p>
            </Link>
          )}
          <Link to="/admin/security/mfa" className="entrance-nav-card">
            <h3><FiShield /> Two-factor auth</h3>
            <p>Enable or manage your authenticator app</p>
          </Link>
          <Link to="/admin/sessions" className="entrance-nav-card">
            <h3><FiShield /> My sessions</h3>
            <p>Review and sign out other devices</p>
          </Link>
        </div>
      </section>
    </div>
  );
}
