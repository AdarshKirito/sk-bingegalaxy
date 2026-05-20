import { useEffect, useMemo, useState, useCallback, useRef } from 'react';
import { toast } from 'react-toastify';
import { Link } from 'react-router-dom';
import {
  FiBell, FiMail, FiMessageSquare, FiPhoneCall, FiCheckCircle,
  FiAlertTriangle, FiClock, FiEye, FiSettings, FiSave, FiInbox,
  FiArrowLeft, FiRefreshCw, FiVolumeX, FiSearch, FiMoon, FiZap,
  FiSend, FiSlash,
} from 'react-icons/fi';
import { format, isToday, isYesterday, isThisWeek } from 'date-fns';
import { notificationService, toArray } from '../services/endpoints';
import './AdminPages.css';
import './CustomerNotifications.css';

/**
 * Customer notification centre.
 *
 * Tab 1 — Inbox: chronological feed of every delivery attempt for the
 *   logged-in user. Smart date buckets (Today / Yesterday / This week /
 *   Older) plus search, channel & status filters. Auto-refresh keeps the
 *   feed fresh while the page is open.
 *
 * Tab 2 — Preferences: master DND toggle, per-channel mutes, per-event
 *   mutes, quiet-hours window, marketing cadence, and a primary-channel
 *   routing hint. The dispatcher honours all of these before each send;
 *   transactional / security messages always bypass these rules.
 *
 * Both tabs talk to /api/v1/notifications via X-User-Email so no recipient
 * needs to be passed explicitly.
 */

const POLL_MS = 60_000;

// Status/delivery rendering. The backend exposes both `deliveryStatus`
// (preferred, see DeliveryStatus enum) and the older boolean `sent` plus
// `failureReason`. We derive a single canonical key for badge rendering.
const STATUS_META = {
  PENDING:    { label: 'Queued',     icon: <FiClock />,         klass: 'cn-pill is-info'    },
  SENT:       { label: 'Sent',       icon: <FiSend />,          klass: 'cn-pill is-info'    },
  DELIVERED:  { label: 'Delivered',  icon: <FiCheckCircle />,   klass: 'cn-pill is-success' },
  OPENED:     { label: 'Opened',     icon: <FiEye />,           klass: 'cn-pill is-success' },
  CLICKED:    { label: 'Clicked',    icon: <FiEye />,           klass: 'cn-pill is-success' },
  BOUNCED:    { label: 'Bounced',    icon: <FiAlertTriangle />, klass: 'cn-pill is-danger'  },
  FAILED:     { label: 'Failed',     icon: <FiAlertTriangle />, klass: 'cn-pill is-danger'  },
  SUPPRESSED: { label: 'Muted',      icon: <FiSlash />,         klass: 'cn-pill is-warning' },
};

const CHANNEL_ICONS = {
  EMAIL:    <FiMail />,
  SMS:      <FiMessageSquare />,
  WHATSAPP: <FiMessageSquare />,
  PUSH:     <FiBell />,
  CALLBACK: <FiPhoneCall />,
};

const TYPE_LABELS = {
  BOOKING_CREATED:        'Booking received',
  BOOKING_CONFIRMED:      'Booking confirmed',
  BOOKING_REMINDER:       'Booking reminder',
  BOOKING_RESCHEDULED:    'Reschedule confirmed',
  BOOKING_CANCELLED:      'Booking cancelled',
  PAYMENT_SUCCESS:        'Payment receipt',
  PAYMENT_FAILED:         'Payment failed',
  REFUND_PROCESSED:       'Refund processed',
  WAITLIST_OFFER:         'Waitlist offer',
  WAITLIST_EXPIRED:       'Waitlist offer expired',
  REVIEW_REQUEST:         'Review request',
  LOYALTY_TIER_UPGRADE:   'Loyalty tier upgrade',
  LOYALTY_REWARD:         'Loyalty reward',
  MARKETING:              'Promotional',
};

const ALL_CHANNELS = ['EMAIL', 'SMS', 'WHATSAPP', 'PUSH'];

const TABS = [
  { id: 'inbox',       label: 'Inbox',       icon: <FiInbox /> },
  { id: 'preferences', label: 'Preferences', icon: <FiSettings /> },
];

// Resolve a canonical status key from whatever shape the backend sends.
function resolveStatus(n) {
  if (n.failureReason && /suppressed/i.test(n.failureReason)) return 'SUPPRESSED';
  if (n.deliveryStatus) return n.deliveryStatus;
  if (n.status) return n.status; // legacy field
  if (n.sent) return 'SENT';
  if (n.failureReason) return 'FAILED';
  return 'PENDING';
}

function resolveType(n) {
  return n.type || n.notificationType || 'NOTIFICATION';
}

function resolveAttempts(n) {
  return n.retryCount ?? n.attempts ?? 0;
}

function dayBucket(date) {
  if (isToday(date))      return { key: 'today',     label: 'Today' };
  if (isYesterday(date))  return { key: 'yesterday', label: 'Yesterday' };
  if (isThisWeek(date, { weekStartsOn: 1 })) return { key: 'week', label: 'Earlier this week' };
  return { key: format(date, 'yyyy-MM'), label: format(date, 'MMMM yyyy') };
}

export default function CustomerNotifications() {
  const [tab, setTab] = useState('inbox');

  return (
    <div className="container adm-shell cn-shell">
      <Link to="/account" className="btn btn-secondary btn-sm" style={{ marginBottom: '0.6rem' }}>
        <FiArrowLeft /> Back to account
      </Link>

      <header className="cn-hero">
        <h1><FiBell /> Notification centre</h1>
        <p>Review every email, SMS, WhatsApp and push message we&rsquo;ve sent you, and decide exactly what you want to receive.</p>
      </header>

      <div role="tablist" className="cn-tabs">
        {TABS.map(t => (
          <button
            key={t.id}
            role="tab"
            aria-selected={t.id === tab}
            className={`cn-tab${t.id === tab ? ' is-active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.icon} {t.label}
          </button>
        ))}
      </div>

      {tab === 'inbox'       && <InboxPanel />}
      {tab === 'preferences' && <PreferencesPanel />}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Inbox
// ─────────────────────────────────────────────────────────────
function InboxPanel() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [channelFilter, setChannelFilter] = useState('');
  const pollRef = useRef(null);

  const load = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setLoading(true);
    try {
      const res = await notificationService.myNotifications();
      setItems(toArray(res.data?.data));
    } catch (err) {
      if (!silent) toast.error(err.response?.data?.message || 'Failed to load notifications');
      setItems([]);
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    pollRef.current = setInterval(() => load({ silent: true }), POLL_MS);
    return () => clearInterval(pollRef.current);
  }, [load]);

  const stats = useMemo(() => {
    let sent = 0, failed = 0, suppressed = 0;
    for (const n of items) {
      const s = resolveStatus(n);
      if (s === 'SUPPRESSED') suppressed++;
      else if (s === 'FAILED' || s === 'BOUNCED') failed++;
      else if (n.sent || ['SENT','DELIVERED','OPENED','CLICKED'].includes(s)) sent++;
    }
    return { sent, failed, suppressed, total: items.length };
  }, [items]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return items.filter(n => {
      if (statusFilter && resolveStatus(n) !== statusFilter) return false;
      if (channelFilter && n.channel !== channelFilter) return false;
      if (!q) return true;
      const hay = [n.bookingRef, resolveType(n), n.subject, n.channel].filter(Boolean).join(' ').toLowerCase();
      return hay.includes(q);
    });
  }, [items, search, statusFilter, channelFilter]);

  // Smart day buckets — keys preserve chronological order via the iterator.
  const groups = useMemo(() => {
    const ordered = [];
    const index = new Map();
    for (const n of filtered) {
      const t = n.createdAt || n.sentAt;
      const date = t ? new Date(t) : null;
      const bucket = date ? dayBucket(date) : { key: 'unknown', label: 'Unknown date' };
      if (!index.has(bucket.key)) {
        const entry = { key: bucket.key, label: bucket.label, items: [] };
        index.set(bucket.key, entry);
        ordered.push(entry);
      }
      index.get(bucket.key).items.push(n);
    }
    return ordered;
  }, [filtered]);

  return (
    <>
      {items.length > 0 && (
        <div className="cn-hero-meta" style={{ marginTop: '-0.4rem', marginBottom: '0.85rem' }}>
          <span className="cn-stat">{stats.total} total</span>
          {stats.sent > 0       && <span className="cn-stat cn-stat-success"><FiCheckCircle /> {stats.sent} delivered</span>}
          {stats.failed > 0     && <span className="cn-stat cn-stat-danger"><FiAlertTriangle /> {stats.failed} failed</span>}
          {stats.suppressed > 0 && <span className="cn-stat cn-stat-warning"><FiSlash /> {stats.suppressed} muted</span>}
        </div>
      )}

      <div className="cn-toolbar">
        <div className="cn-search">
          <FiSearch />
          <input
            type="search"
            placeholder="Search by booking ref, subject, or type"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
          <option value="">All statuses</option>
          {Object.keys(STATUS_META).map(s => <option key={s} value={s}>{STATUS_META[s].label}</option>)}
        </select>
        <select value={channelFilter} onChange={(e) => setChannelFilter(e.target.value)}>
          <option value="">All channels</option>
          {ALL_CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        <span className="cn-toolbar-spacer" />
        <button className="btn btn-secondary btn-sm" onClick={() => load()} disabled={loading}>
          <FiRefreshCw /> Refresh
        </button>
      </div>

      {loading ? (
        <div className="cn-loading"><span className="cn-spinner" /> Loading your notifications…</div>
      ) : filtered.length === 0 ? (
        <div className="cn-empty">
          <FiInbox size={42} />
          <h3>{items.length === 0 ? 'No notifications yet' : 'Nothing matches your filters'}</h3>
          <p>{items.length === 0
            ? 'Once you make a booking, confirmations and reminders will land here.'
            : 'Try clearing the search or status / channel filters.'}</p>
        </div>
      ) : (
        groups.map(g => (
          <section key={g.key}>
            <h4 className="cn-day-heading">{g.label}</h4>
            <div className="cn-day-list">
              {g.items.map(n => <NotificationRow key={n.id || `${n.createdAt}-${resolveType(n)}`} n={n} />)}
            </div>
          </section>
        ))
      )}
    </>
  );
}

function NotificationRow({ n }) {
  const statusKey = resolveStatus(n);
  const meta = STATUS_META[statusKey] || { label: statusKey, icon: <FiMail />, klass: 'cn-pill is-muted' };
  const channelIcon = CHANNEL_ICONS[n.channel] || <FiBell />;
  const typeLabel = TYPE_LABELS[resolveType(n)] || resolveType(n);
  const time = n.sentAt || n.createdAt;
  const attempts = resolveAttempts(n);

  const cardClass = statusKey === 'SUPPRESSED' ? 'is-suppressed'
                  : (statusKey === 'FAILED' || statusKey === 'BOUNCED') ? 'is-failed'
                  : 'is-success';

  // Show the failure reason only when it isn't the suppression marker.
  const showFailure = (statusKey === 'FAILED' || statusKey === 'BOUNCED')
                      && n.failureReason
                      && !/suppressed/i.test(n.failureReason);

  return (
    <article className={`cn-card ${cardClass}`}>
      <div className={`cn-card-channel ch-${n.channel || 'EMAIL'}`} aria-hidden="true">{channelIcon}</div>

      <div className="cn-card-body">
        <div className="cn-card-row1">
          <span className="cn-card-title">{typeLabel}</span>
          <span className={meta.klass}>{meta.icon} {meta.label}</span>
          <span className="cn-card-via">via {(n.channel || 'unknown').toLowerCase()}</span>
        </div>
        {n.subject && <div className="cn-card-subject">{n.subject}</div>}
        {showFailure && (
          <div className="cn-card-error">
            <FiAlertTriangle /> {n.failureReason}
          </div>
        )}
        <div className="cn-card-foot">
          {attempts > 0 && <span>{attempts} attempt{attempts === 1 ? '' : 's'}</span>}
          {n.recipientEmail && <span>to {n.recipientEmail}</span>}
        </div>
      </div>

      <div className="cn-card-actions">
        <span className="cn-time">{time ? format(new Date(time), 'p') : ''}</span>
        {n.bookingRef && (
          <Link to={`/my-bookings?ref=${encodeURIComponent(n.bookingRef)}`}>
            View booking #{n.bookingRef}
          </Link>
        )}
      </div>
    </article>
  );
}

// ─────────────────────────────────────────────────────────────
// Preferences
// ─────────────────────────────────────────────────────────────
const FREQUENCY_OPTIONS = [
  { value: 'IMMEDIATE', label: 'Send as it happens' },
  { value: 'DAILY',     label: 'Daily digest' },
  { value: 'WEEKLY',    label: 'Weekly digest' },
  { value: 'NEVER',     label: 'Never send promotional messages' },
];

const TYPE_GROUPS = [
  { id: 'bookings',  title: 'Booking lifecycle',   types: ['BOOKING_CREATED','BOOKING_CONFIRMED','BOOKING_REMINDER','BOOKING_RESCHEDULED','BOOKING_CANCELLED'] },
  { id: 'payments',  title: 'Payments & refunds',  types: ['PAYMENT_SUCCESS','PAYMENT_FAILED','REFUND_PROCESSED'] },
  { id: 'waitlist',  title: 'Waitlist',            types: ['WAITLIST_OFFER','WAITLIST_EXPIRED'] },
  { id: 'loyalty',   title: 'Loyalty & rewards',   types: ['LOYALTY_TIER_UPGRADE','LOYALTY_REWARD'] },
  { id: 'marketing', title: 'Promotional',         types: ['MARKETING','REVIEW_REQUEST'] },
];

const TRANSACTIONAL_TYPES = new Set([
  'BOOKING_CONFIRMED', 'BOOKING_CANCELLED', 'PAYMENT_FAILED', 'REFUND_PROCESSED',
]);

const COMMON_TIMEZONES = [
  'UTC',
  'Asia/Kolkata',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Asia/Dubai',
  'Europe/London',
  'Europe/Berlin',
  'Europe/Paris',
  'America/New_York',
  'America/Chicago',
  'America/Los_Angeles',
  'Australia/Sydney',
];

const DEFAULT_PREFS = Object.freeze({
  globalOptOut: false,
  mutedChannels: [],
  mutedTypes: [],
  quietHoursEnabled: false,
  quietHoursStart: '22:00',
  quietHoursEnd: '08:00',
  quietHoursTimezone: '',
  marketingFrequency: 'IMMEDIATE',
  primaryChannel: '',
});

function PreferencesPanel() {
  const [prefs, setPrefs] = useState(DEFAULT_PREFS);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);

  const browserZone = useMemo(() => {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; }
    catch { return ''; }
  }, []);

  const zoneOptions = useMemo(() => {
    const set = new Set([browserZone, ...COMMON_TIMEZONES].filter(Boolean));
    return Array.from(set);
  }, [browserZone]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await notificationService.getPreferences();
      const p = res.data?.data || {};
      setPrefs({
        globalOptOut:        !!p.globalOptOut,
        mutedChannels:       Array.isArray(p.mutedChannels) ? p.mutedChannels : [],
        mutedTypes:          Array.isArray(p.mutedTypes)    ? p.mutedTypes    : [],
        quietHoursEnabled:   !!p.quietHoursEnabled,
        quietHoursStart:     p.quietHoursStart   || '22:00',
        quietHoursEnd:       p.quietHoursEnd     || '08:00',
        quietHoursTimezone:  p.quietHoursTimezone || browserZone || 'UTC',
        marketingFrequency:  p.marketingFrequency || 'IMMEDIATE',
        primaryChannel:      p.primaryChannel    || '',
      });
      setDirty(false);
    } catch (err) {
      if (err.response?.status !== 404) {
        toast.error(err.response?.data?.message || 'Failed to load preferences');
      }
    } finally {
      setLoading(false);
    }
  }, [browserZone]);

  useEffect(() => { load(); }, [load]);

  const update = (patch) => { setPrefs(p => ({ ...p, ...patch })); setDirty(true); };

  const toggleChannel = (ch) => {
    setPrefs(p => {
      const muted = p.mutedChannels.includes(ch);
      return { ...p, mutedChannels: muted ? p.mutedChannels.filter(c => c !== ch) : [...p.mutedChannels, ch] };
    });
    setDirty(true);
  };

  const toggleType = (t) => {
    if (TRANSACTIONAL_TYPES.has(t)) return;
    setPrefs(p => {
      const muted = p.mutedTypes.includes(t);
      return { ...p, mutedTypes: muted ? p.mutedTypes.filter(x => x !== t) : [...p.mutedTypes, t] };
    });
    setDirty(true);
  };

  const save = async () => {
    setBusy(true);
    try {
      await notificationService.updatePreferences(prefs);
      toast.success('Preferences saved');
      setDirty(false);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <div className="cn-loading"><span className="cn-spinner" /> Loading preferences…</div>;

  const dnd = prefs.globalOptOut;

  return (
    <div className="cn-pref-shell">
      {/* Master kill-switch */}
      <section className="cn-pref-card">
        <div className="cn-pref-row">
          <FiVolumeX size={26} style={{ color: '#f59e0b', flexShrink: 0 }} />
          <div className="cn-pref-grow">
            <strong>Pause everything</strong>
            <span className="cn-pref-help-inline">
              Stops every channel and type until you turn this back off. Booking-critical
              messages (failed payments, cancellations) are still delivered.
            </span>
          </div>
          <label className="cn-switch cn-switch-warning">
            <input
              type="checkbox"
              checked={dnd}
              onChange={(e) => update({ globalOptOut: e.target.checked })}
            />
            <span className="cn-slider" />
          </label>
        </div>
      </section>

      {/* Quiet hours */}
      <section className={`cn-pref-card${dnd ? ' cn-section-disabled' : ''}`}>
        <div className="cn-pref-row">
          <FiMoon size={20} style={{ color: '#6366f1', flexShrink: 0 }} />
          <div className="cn-pref-grow">
            <strong>Quiet hours</strong>
            <span className="cn-pref-help-inline">
              Pause non-essential alerts during this window. Booking-critical
              messages still come through.
            </span>
          </div>
          <label className="cn-switch">
            <input
              type="checkbox"
              checked={prefs.quietHoursEnabled}
              disabled={dnd}
              onChange={(e) => update({ quietHoursEnabled: e.target.checked })}
            />
            <span className="cn-slider" />
          </label>
        </div>
        <div className="cn-quiet-grid">
          <div>
            <label htmlFor="cn-qh-start">Start</label>
            <input
              id="cn-qh-start"
              type="time"
              value={prefs.quietHoursStart}
              disabled={!prefs.quietHoursEnabled || dnd}
              onChange={(e) => update({ quietHoursStart: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="cn-qh-end">End</label>
            <input
              id="cn-qh-end"
              type="time"
              value={prefs.quietHoursEnd}
              disabled={!prefs.quietHoursEnabled || dnd}
              onChange={(e) => update({ quietHoursEnd: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="cn-qh-tz">Time zone</label>
            <select
              id="cn-qh-tz"
              value={prefs.quietHoursTimezone}
              disabled={!prefs.quietHoursEnabled || dnd}
              onChange={(e) => update({ quietHoursTimezone: e.target.value })}
            >
              {zoneOptions.map(z => <option key={z} value={z}>{z}</option>)}
            </select>
          </div>
        </div>
      </section>

      {/* Channels */}
      <section className={`cn-pref-card${dnd ? ' cn-section-disabled' : ''}`}>
        <h3><FiSend /> Channels</h3>
        <p className="cn-pref-help">
          Tick the channels you&rsquo;re happy to receive on. We&rsquo;ll fall back to a
          non-muted channel where possible.
        </p>
        <div className="cn-channel-grid">
          {ALL_CHANNELS.map(ch => {
            const muted = prefs.mutedChannels.includes(ch);
            return (
              <label key={ch} className={`cn-channel-tile${muted ? ' is-muted' : ''}`}>
                <input
                  type="checkbox"
                  checked={!muted}
                  disabled={dnd}
                  onChange={() => toggleChannel(ch)}
                />
                <span className={`cn-channel-icon ch-${ch}`} style={{ background: 'transparent' }}>
                  {CHANNEL_ICONS[ch]}
                </span>
                <span>{ch}</span>
              </label>
            );
          })}
        </div>

        <div style={{ marginTop: '1rem' }}>
          <strong style={{ fontSize: '0.88rem' }}>Preferred channel</strong>
          <p className="cn-pref-help" style={{ marginTop: '0.2rem' }}>
            When a message can be delivered through several channels, prefer this one.
          </p>
          <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
            <label className={`cn-channel-tile${!prefs.primaryChannel ? ' is-muted' : ''}`} style={{ flex: '0 0 auto' }}>
              <input
                type="radio"
                name="cn-primary"
                checked={!prefs.primaryChannel}
                disabled={dnd}
                onChange={() => update({ primaryChannel: '' })}
              />
              <span>Auto</span>
            </label>
            {ALL_CHANNELS.map(ch => (
              <label
                key={ch}
                className="cn-channel-tile"
                style={{ flex: '0 0 auto', opacity: prefs.mutedChannels.includes(ch) ? 0.5 : 1 }}
              >
                <input
                  type="radio"
                  name="cn-primary"
                  checked={prefs.primaryChannel === ch}
                  disabled={dnd || prefs.mutedChannels.includes(ch)}
                  onChange={() => update({ primaryChannel: ch })}
                />
                {CHANNEL_ICONS[ch]} <span>{ch}</span>
              </label>
            ))}
          </div>
        </div>
      </section>

      {/* Marketing cadence */}
      <section className={`cn-pref-card${dnd ? ' cn-section-disabled' : ''}`}>
        <h3><FiZap /> Promotional messages</h3>
        <p className="cn-pref-help">
          Choose how often you want curated offers and review requests. Booking
          confirmations and receipts are unaffected.
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '0.4rem' }}>
          {FREQUENCY_OPTIONS.map(o => (
            <label key={o.value} className={`cn-channel-tile${prefs.marketingFrequency === o.value ? '' : ' is-muted'}`}>
              <input
                type="radio"
                name="cn-freq"
                checked={prefs.marketingFrequency === o.value}
                disabled={dnd}
                onChange={() => update({ marketingFrequency: o.value })}
              />
              <span>{o.label}</span>
            </label>
          ))}
        </div>
      </section>

      {/* Per-type fine-grained */}
      <section className={`cn-pref-card${dnd ? ' cn-section-disabled' : ''}`}>
        <h3><FiBell /> What we send</h3>
        <p className="cn-pref-help">
          Pick which kinds of messages you want to receive. Items marked
          &ldquo;always on&rdquo; are critical to your booking and can&rsquo;t be muted.
        </p>
        {TYPE_GROUPS.map(group => (
          <div key={group.id} className="cn-type-group">
            <h4>{group.title}</h4>
            <div className="cn-type-grid">
              {group.types.map(t => {
                const muted = prefs.mutedTypes.includes(t);
                const locked = TRANSACTIONAL_TYPES.has(t);
                return (
                  <label
                    key={t}
                    className={`cn-type-tile${locked ? ' is-disabled' : ''}`}
                    title={locked ? 'Always on — critical to your booking' : undefined}
                  >
                    <input
                      type="checkbox"
                      checked={locked ? true : !muted}
                      disabled={locked || dnd}
                      onChange={() => toggleType(t)}
                    />
                    <span>{TYPE_LABELS[t] || t}</span>
                    {locked && <span className="cn-pill is-info" style={{ marginLeft: 'auto' }}>Always on</span>}
                  </label>
                );
              })}
            </div>
          </div>
        ))}
      </section>

      <div className="cn-actions-bar">
        <button className="btn btn-primary" onClick={save} disabled={busy || !dirty}>
          <FiSave /> {busy ? 'Saving…' : dirty ? 'Save changes' : 'All saved'}
        </button>
        <button className="btn btn-secondary" onClick={load} disabled={busy}>
          <FiRefreshCw /> Discard
        </button>
      </div>
    </div>
  );
}
