import { useState, useCallback, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { adminService, adminSupportService, notificationService, toArray } from '../services/endpoints';
import { formatServerDateTime } from '../services/timeFormat';
import { toast } from 'react-toastify';
import {
  FiSearch, FiSend, FiAlertTriangle, FiGift, FiXCircle, FiRefreshCw,
  FiMessageSquare, FiTrash2, FiBookmark, FiPlus, FiCheckCircle,
  FiArrowUpCircle, FiArrowDownCircle,
} from 'react-icons/fi';
import './AdminPages.css';
import './AdminSupportConsole.css';
import { venueMoney, venueSymbol } from '../utils/venueLocale';

const LEVELS = ['NONE', 'L1', 'L2', 'L3'];
const levelClass = (level) => `sc-level sc-level-${(level || 'NONE').toLowerCase()}`;

/**
 * Operator support console. Search a booking by reference, then inspect and
 * act on it: threaded notes, resend confirmation, a guided escalation
 * workflow (raise → work → resolve), goodwill credit, per-row notification
 * retries, and cancellation with reason.
 */
export default function AdminSupportConsole() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [query, setQuery] = useState(searchParams.get('ref') || '');
  const [booking, setBooking] = useState(null);
  const [notes, setNotes] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(false);

  const [newNote, setNewNote] = useState({ body: '', visibility: 'INTERNAL', pinned: false });
  const [escalationReason, setEscalationReason] = useState('');
  const [escalationBusy, setEscalationBusy] = useState(false);
  const [goodwill, setGoodwill] = useState({ amount: '', reason: '' });
  const [cancelReason, setCancelReason] = useState('');
  // Work queue: open escalations for this binge (what needs attention NOW).
  const [escalationQueue, setEscalationQueue] = useState([]);

  const loadQueue = useCallback(() => {
    adminSupportService.listEscalations()
      .then((res) => setEscalationQueue(toArray(res.data?.data)))
      .catch(() => setEscalationQueue([]));
  }, []);
  useEffect(() => { loadQueue(); }, [loadQueue]);

  const loadAll = useCallback(async (ref) => {
    if (!ref) return;
    setLoading(true);
    try {
      const [bk, nt, nf] = await Promise.all([
        adminSupportService.getByRef(ref).catch(() => null),
        adminSupportService.listNotes(ref).catch(() => null),
        notificationService.byBooking(ref).catch(() => null),
      ]);
      const found = bk?.data?.data || null;
      setBooking(found);
      setNotes(toArray(nt?.data?.data));
      setNotifications(toArray(nf?.data?.data));
      if (!found) toast.warn('No booking found for that reference');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load booking');
    } finally {
      setLoading(false);
    }
  }, []);

  // Keep the per-booking action forms in sync with whichever booking is
  // loaded — a stale reason from the previous booking must never be
  // submitted against the next one.
  useEffect(() => {
    setEscalationReason('');
    setGoodwill({ amount: '', reason: '' });
    setCancelReason('');
  }, [booking?.bookingRef]);

  const handleSearch = (e) => {
    e?.preventDefault();
    const ref = query.trim();
    if (!ref) return;
    setSearchParams({ ref });
    loadAll(ref);
  };

  // Auto-load when arrived via deep-link (e.g. from the AdminBookings
  // detail modal "Open in Support Console" button or a recovery-queue
  // alert toast). Re-runs only when the query-param ref changes.
  useEffect(() => {
    const ref = searchParams.get('ref');
    if (ref && ref.trim() && ref !== booking?.bookingRef) {
      setQuery(ref.trim()); // keep the search box in sync with the deep link
      loadAll(ref.trim());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  // ── Notes ──────────────────────────────────────────────────────────────
  const submitNote = async (e) => {
    e.preventDefault();
    if (!booking?.bookingRef || !newNote.body.trim()) return;
    try {
      await adminSupportService.addNote(booking.bookingRef, newNote);
      setNewNote({ body: '', visibility: 'INTERNAL', pinned: false });
      toast.success('Note added');
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to add note');
    }
  };

  const removeNote = async (id) => {
    if (!window.confirm('Delete this note? This cannot be undone.')) return;
    try {
      await adminSupportService.deleteNote(id);
      toast.success('Note deleted');
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Delete failed');
    }
  };

  const togglePin = async (note) => {
    try {
      await adminSupportService.pinNote(note.id, !note.pinned);
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Pin failed');
    }
  };

  // ── Operator actions ────────────────────────────────────────────────────
  const resend = async () => {
    if (!booking?.bookingRef) return;
    try {
      await adminSupportService.resendConfirmation(booking.bookingRef);
      toast.success('Confirmation re-sent');
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Resend failed');
    }
  };

  /**
   * Guided escalation transition. Escalating (to a higher OR lower non-NONE
   * level) requires a reason — it becomes the work-queue context for the next
   * operator. Resolving (NONE) may reuse the reason box as a resolution note.
   */
  const setEscalationLevel = async (level) => {
    if (!booking?.bookingRef) return;
    if (level !== 'NONE' && !escalationReason.trim()) {
      toast.error('Add a reason first — it tells the next operator what to do');
      return;
    }
    setEscalationBusy(true);
    try {
      await adminSupportService.escalate(booking.bookingRef, level, escalationReason.trim());
      toast.success(level === 'NONE' ? 'Escalation resolved' : `Escalated to ${level}`);
      setEscalationReason('');
      await loadAll(booking.bookingRef);
      loadQueue();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Escalation update failed');
    } finally {
      setEscalationBusy(false);
    }
  };

  const submitGoodwill = async (e) => {
    e.preventDefault();
    if (!booking?.bookingRef) return;
    if (!goodwill.amount || Number(goodwill.amount) <= 0) {
      toast.error('Enter a positive goodwill amount');
      return;
    }
    try {
      await adminSupportService.goodwill(booking.bookingRef, Number(goodwill.amount), goodwill.reason);
      toast.success('Goodwill issued');
      setGoodwill({ amount: '', reason: '' });
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Goodwill failed');
    }
  };

  const submitCancel = async () => {
    if (!booking?.bookingRef) return;
    if (!cancelReason.trim()) {
      toast.error('Please supply a cancellation reason');
      return;
    }
    if (!window.confirm(`Cancel booking ${booking.bookingRef}? This is irreversible.`)) return;
    try {
      await adminService.cancelBooking(booking.bookingRef, cancelReason.trim());
      toast.success('Booking cancelled');
      setCancelReason('');
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Cancel failed');
    }
  };

  const retryNotification = async (id) => {
    try {
      await adminSupportService.retryNotification(id);
      toast.success('Notification retried');
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Retry failed');
    }
  };

  const currentLevel = booking?.escalationLevel || 'NONE';
  const currentIdx = Math.max(0, LEVELS.indexOf(currentLevel));
  const nextLevel = currentIdx < LEVELS.length - 1 ? LEVELS[currentIdx + 1] : null;
  const lowerLevel = currentIdx > 1 ? LEVELS[currentIdx - 1] : null;

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiMessageSquare /> Support Console</h1>
          <p>Look up a booking by reference and act on it: notes, escalation workflow, goodwill, resends, and cancellations with reason.</p>
        </div>
        <button type="button" className="btn btn-secondary" onClick={() => booking && loadAll(booking.bookingRef)} disabled={loading || !booking}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <form onSubmit={handleSearch} className="adm-flow-card sc-search-form">
        <input
          type="text"
          placeholder="Booking reference (e.g. SK24ABC123)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          autoFocus
        />
        <button type="submit" className="btn btn-primary" disabled={loading || !query.trim()}>
          <FiSearch /> Search
        </button>
      </form>

      {/* ── Escalation work queue ─────────────────────────────────── */}
      {escalationQueue.length > 0 && (
        <section className="adm-flow-card" style={{ marginBottom: '1.25rem' }}>
          <h3 style={{ marginTop: 0 }}><FiAlertTriangle /> Open escalations ({escalationQueue.length})</h3>
          <div className="adm-table-wrap">
            <table className="sc-table">
              <thead>
                <tr>
                  <th>Ref</th>
                  <th>Customer</th>
                  <th>Level</th>
                  <th>Reason</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {escalationQueue.map((b) => (
                  <tr key={b.bookingRef} className="sc-queue-row"
                      onClick={() => { setQuery(b.bookingRef); setSearchParams({ ref: b.bookingRef }); loadAll(b.bookingRef); }}>
                    <td><strong>{b.bookingRef}</strong></td>
                    <td>{b.customerName}</td>
                    <td><span className={levelClass(b.escalationLevel)}>{b.escalationLevel}</span></td>
                    <td style={{ maxWidth: 320, fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                      {b.escalationReason || '—'}
                    </td>
                    <td><span className="badge">{b.status}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {!booking ? (
        <section className="adm-flow-card">
          <div className="admin-empty-state">
            Search for a booking to begin{escalationQueue.length > 0 ? ', or pick one from the escalation queue above' : ''}.
          </div>
        </section>
      ) : (
        <>
          {/* ── Booking summary ─────────────────────────────────────── */}
          <section className="adm-flow-card" style={{ marginBottom: '1.25rem' }}>
            <h3 style={{ marginTop: 0 }}>Booking {booking.bookingRef}</h3>
            <div className="sc-summary-grid">
              <Field label="Customer">{booking.customerName} <small style={{ color: 'var(--text-secondary)' }}>(#{booking.customerId})</small></Field>
              <Field label="Email">{booking.customerEmail}</Field>
              <Field label="Phone">{booking.customerPhoneCountryCode} {booking.customerPhone}</Field>
              <Field label="Status"><span className="badge">{booking.status}</span></Field>
              <Field label="Date / Time">{booking.bookingDate} {booking.startTime}</Field>
              <Field label="Total">{venueMoney(booking.totalAmount)}</Field>
              <Field label="Escalation">
                <span className={levelClass(currentLevel)}>{currentLevel}</span>
                {booking.escalationReason && (
                  <div className="sc-subtext">{booking.escalationReason}</div>
                )}
              </Field>
              <Field label="Goodwill">
                {booking.goodwillCredit ? `${venueMoney(booking.goodwillCredit)}` : '—'}
                {booking.goodwillReason && (
                  <div className="sc-subtext">{booking.goodwillReason}</div>
                )}
              </Field>
            </div>
            {booking.cancellationReason && (
              <div className="sc-callout sc-callout-danger">
                <strong>Cancellation reason:</strong> {booking.cancellationReason}
              </div>
            )}
          </section>

          {/* ── Action grid ─────────────────────────────────────────── */}
          <section className="adm-flow-card" style={{ marginBottom: '1.25rem' }}>
            <h3 style={{ marginTop: 0 }}>Actions</h3>
            <div className="sc-action-grid">
              {/* Escalation workflow */}
              <div className="sc-action-card">
                <div className="sc-action-card-title"><FiAlertTriangle /> Escalation workflow</div>
                <p className="sc-action-hint">
                  Current level: <span className={levelClass(currentLevel)}>{currentLevel}</span>
                  {currentLevel === 'NONE'
                    ? ' — raise to L1 to put this booking on the support work queue.'
                    : ' — work it, hand it up, or resolve it when done.'}
                </p>
                <input
                  type="text"
                  maxLength={500}
                  placeholder={currentLevel === 'NONE'
                    ? 'Reason (required to escalate)'
                    : 'Reason / resolution note'}
                  value={escalationReason}
                  onChange={(e) => setEscalationReason(e.target.value)}
                />
                <div className="sc-esc-actions">
                  {nextLevel && (
                    <button type="button" className="btn btn-primary btn-sm" disabled={escalationBusy}
                      onClick={() => setEscalationLevel(nextLevel)}>
                      <FiArrowUpCircle /> Escalate to {nextLevel}
                    </button>
                  )}
                  {lowerLevel && (
                    <button type="button" className="btn btn-secondary btn-sm" disabled={escalationBusy}
                      onClick={() => setEscalationLevel(lowerLevel)}>
                      <FiArrowDownCircle /> De-escalate to {lowerLevel}
                    </button>
                  )}
                  {currentLevel !== 'NONE' && (
                    <button type="button" className="btn btn-secondary btn-sm" disabled={escalationBusy}
                      onClick={() => setEscalationLevel('NONE')}>
                      <FiCheckCircle /> Resolve
                    </button>
                  )}
                </div>
              </div>

              {/* Resend confirmation */}
              <div className="sc-action-card">
                <div className="sc-action-card-title"><FiSend /> Resend confirmation</div>
                <p className="sc-action-hint">
                  Re-emit the BOOKING_CONFIRMED event. Requires status=CONFIRMED.
                </p>
                <button type="button" className="btn btn-primary btn-sm" onClick={resend} disabled={booking.status !== 'CONFIRMED'} style={{ alignSelf: 'flex-start' }}>
                  <FiSend /> Resend
                </button>
              </div>

              {/* Goodwill */}
              <div className="sc-action-card">
                <div className="sc-action-card-title"><FiGift /> Goodwill credit</div>
                <form onSubmit={submitGoodwill} style={{ display: 'grid', gap: '0.5rem' }}>
                  <input
                    type="number"
                    min="1"
                    max="10000"
                    step="1"
                    placeholder={`Amount (${venueSymbol()})`}
                    value={goodwill.amount}
                    onChange={(e) => setGoodwill(s => ({ ...s, amount: e.target.value }))}
                  />
                  <input
                    type="text"
                    maxLength={500}
                    placeholder="Reason"
                    value={goodwill.reason}
                    onChange={(e) => setGoodwill(s => ({ ...s, reason: e.target.value }))}
                  />
                  <button type="submit" className="btn btn-secondary btn-sm" style={{ justifySelf: 'start' }}>Issue</button>
                </form>
              </div>

              {/* Cancel with reason */}
              <div className="sc-action-card">
                <div className="sc-action-card-title"><FiXCircle /> Cancel with reason</div>
                <textarea
                  rows={2}
                  maxLength={500}
                  placeholder="Cancellation reason (required)"
                  value={cancelReason}
                  onChange={(e) => setCancelReason(e.target.value)}
                  style={{ width: '100%' }}
                />
                <button type="button" className="btn btn-danger btn-sm" onClick={submitCancel} disabled={!cancelReason.trim() || booking.status === 'CANCELLED'} style={{ alignSelf: 'flex-start' }}>
                  <FiXCircle /> Cancel booking
                </button>
              </div>
            </div>
          </section>

          {/* ── Notes thread ────────────────────────────────────────── */}
          <section className="adm-flow-card" style={{ marginBottom: '1.25rem' }}>
            <h3 style={{ marginTop: 0 }}>Notes ({notes.length})</h3>
            <form onSubmit={submitNote} style={{ display: 'grid', gap: '0.5rem', marginBottom: '0.75rem' }}>
              <textarea
                rows={3}
                maxLength={5000}
                placeholder="Add a note…"
                value={newNote.body}
                onChange={(e) => setNewNote(n => ({ ...n, body: e.target.value }))}
                style={{ width: '100%' }}
              />
              <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
                <select value={newNote.visibility} onChange={(e) => setNewNote(n => ({ ...n, visibility: e.target.value }))}>
                  <option value="INTERNAL">Internal</option>
                  <option value="CUSTOMER">Customer-visible</option>
                </select>
                <label style={{ display: 'flex', gap: '0.3rem', alignItems: 'center' }}>
                  <input type="checkbox" checked={newNote.pinned} onChange={(e) => setNewNote(n => ({ ...n, pinned: e.target.checked }))} />
                  Pinned
                </label>
                <button type="submit" className="btn btn-primary btn-sm" disabled={!newNote.body.trim()}>
                  <FiPlus /> Add note
                </button>
              </div>
            </form>
            {notes.length === 0 ? (
              <div className="admin-empty-state">No notes yet.</div>
            ) : (
              <ul className="sc-note-list">
                {notes.map(n => (
                  <li key={n.id} className={`sc-note${n.pinned ? ' sc-note-pinned' : ''}`}>
                    <div className="sc-note-head">
                      <strong>{n.authorName}</strong>
                      <span className="sc-note-meta">
                        <span className={`sc-vis-badge${n.visibility === 'CUSTOMER' ? ' customer' : ''}`}>
                          {n.visibility}
                        </span>
                        {n.edited && <small style={{ color: 'var(--text-secondary)' }}>(edited)</small>}
                        <small style={{ color: 'var(--text-secondary)' }}>{formatServerDateTime(n.createdAt)}</small>
                        <button type="button" className="btn btn-ghost btn-xs" title="Pin / Unpin" onClick={() => togglePin(n)}>
                          <FiBookmark />
                        </button>
                        <button type="button" className="btn btn-ghost btn-xs" title="Delete" onClick={() => removeNote(n.id)}>
                          <FiTrash2 />
                        </button>
                      </span>
                    </div>
                    <div className="sc-note-body">{n.body}</div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* ── Notifications timeline ──────────────────────────────── */}
          <section className="adm-flow-card">
            <h3 style={{ marginTop: 0 }}>Notifications ({notifications.length})</h3>
            {notifications.length === 0 ? (
              <div className="admin-empty-state">No notifications recorded for this booking.</div>
            ) : (
              <div className="adm-table-wrap">
                <table className="sc-table">
                  <thead>
                    <tr>
                      <th>Channel</th>
                      <th>Subject / Type</th>
                      <th>Status</th>
                      <th>Retries</th>
                      <th>Last error</th>
                      <th style={{ width: 100 }} />
                    </tr>
                  </thead>
                  <tbody>
                    {notifications.map(n => (
                      <tr key={n.id}>
                        <td>{n.channel}</td>
                        <td>{n.subject || n.notificationType}</td>
                        <td>
                          <span className={`badge ${n.deliveryStatus === 'SENT' ? 'badge-success' : n.deliveryStatus === 'FAILED' ? 'badge-danger' : 'badge-warning'}`}>
                            {n.deliveryStatus}
                          </span>
                        </td>
                        <td>{n.retryCount}</td>
                        <td style={{ maxWidth: 280, fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                          {n.failureReason || '—'}
                        </td>
                        <td>
                          {n.deliveryStatus !== 'SENT' && (
                            <button type="button" className="btn btn-secondary btn-xs" onClick={() => retryNotification(n.id)}>
                              <FiRefreshCw /> Retry
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div>
      <div className="sc-field-label">{label}</div>
      <div>{children}</div>
    </div>
  );
}
