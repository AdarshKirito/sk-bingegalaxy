import { useState, useCallback, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { adminService, adminSupportService, notificationService, toArray } from '../services/endpoints';
import { parseServerDate } from '../services/timeFormat';
import { toast } from 'react-toastify';
import {
  FiSearch, FiSend, FiAlertTriangle, FiGift, FiXCircle, FiRefreshCw,
  FiMessageSquare, FiTrash2, FiBookmark, FiPlus,
} from 'react-icons/fi';
import './AdminPages.css';

const ESCALATION_LEVELS = ['NONE', 'L1', 'L2', 'L3'];

/**
 * Operator support console (Item 24). Search a booking by reference, then
 * inspect / act on it: threaded notes, resend confirmation, escalate,
 * issue goodwill, retry individual notifications, and cancel with reason.
 */
export default function AdminSupportConsole() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [query, setQuery] = useState(searchParams.get('ref') || '');
  const [booking, setBooking] = useState(null);
  const [notes, setNotes] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(false);

  const [newNote, setNewNote] = useState({ body: '', visibility: 'INTERNAL', pinned: false });
  const [escalation, setEscalation] = useState({ level: 'L1', reason: '' });
  const [goodwill, setGoodwill] = useState({ amount: '', reason: '' });
  const [cancelReason, setCancelReason] = useState('');

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

  const submitEscalation = async (e) => {
    e.preventDefault();
    if (!booking?.bookingRef) return;
    try {
      await adminSupportService.escalate(booking.bookingRef, escalation.level, escalation.reason);
      toast.success('Escalation updated');
      loadAll(booking.bookingRef);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Escalation failed');
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

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiMessageSquare /> Support Console</h1>
          <p>Look up a booking by reference and act on it: notes, escalation, goodwill, resends, and cancellations with reason.</p>
        </div>
        <button type="button" className="btn btn-secondary" onClick={() => booking && loadAll(booking.bookingRef)} disabled={loading || !booking}>
          <FiRefreshCw /> {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <form onSubmit={handleSearch} className="adm-flow-card" style={{ display: 'flex', gap: '0.5rem', marginBottom: '1.25rem' }}>
        <input
          type="text"
          placeholder="Booking reference (e.g. SK24ABC123)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1, padding: '0.55rem 0.7rem' }}
          autoFocus
        />
        <button type="submit" className="btn btn-primary" disabled={loading || !query.trim()}>
          <FiSearch /> Search
        </button>
      </form>

      {!booking ? (
        <section className="adm-flow-card">
          <div className="admin-empty-state">Search for a booking to begin.</div>
        </section>
      ) : (
        <>
          {/* ── Booking summary ─────────────────────────────────────── */}
          <section className="adm-flow-card" style={{ marginBottom: '1.25rem' }}>
            <h3 style={{ marginTop: 0 }}>Booking {booking.bookingRef}</h3>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '0.75rem' }}>
              <Field label="Customer">{booking.customerName} <small style={{ color: 'var(--text-secondary)' }}>(#{booking.customerId})</small></Field>
              <Field label="Email">{booking.customerEmail}</Field>
              <Field label="Phone">{booking.customerPhoneCountryCode} {booking.customerPhone}</Field>
              <Field label="Status"><span className="badge">{booking.status}</span></Field>
              <Field label="Date / Time">{booking.bookingDate} {booking.startTime}</Field>
              <Field label="Total">₹{booking.totalAmount}</Field>
              <Field label="Escalation">
                <span className="badge" style={{ background: booking.escalationLevel && booking.escalationLevel !== 'NONE' ? '#fee' : undefined }}>
                  {booking.escalationLevel || 'NONE'}
                </span>
              </Field>
              <Field label="Goodwill">
                {booking.goodwillCredit ? `₹${booking.goodwillCredit}` : '—'}
              </Field>
            </div>
            {booking.cancellationReason && (
              <div style={{ marginTop: '0.75rem', padding: '0.6rem', background: 'var(--surface-2, #fdf6f6)', borderRadius: 4 }}>
                <strong>Cancellation reason:</strong> {booking.cancellationReason}
              </div>
            )}
          </section>

          {/* ── Action grid ─────────────────────────────────────────── */}
          <section className="adm-flow-card" style={{ marginBottom: '1.25rem' }}>
            <h3 style={{ marginTop: 0 }}>Actions</h3>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1rem' }}>
              {/* Resend confirmation */}
              <ActionCard title="Resend confirmation" icon={<FiSend />}>
                <p style={{ fontSize: '0.9em', color: 'var(--text-secondary)' }}>
                  Re-emit the BOOKING_CONFIRMED event. Requires status=CONFIRMED.
                </p>
                <button type="button" className="btn btn-primary btn-sm" onClick={resend} disabled={booking.status !== 'CONFIRMED'}>
                  <FiSend /> Resend
                </button>
              </ActionCard>

              {/* Escalation */}
              <ActionCard title="Escalation" icon={<FiAlertTriangle />}>
                <form onSubmit={submitEscalation} style={{ display: 'grid', gap: '0.5rem' }}>
                  <select value={escalation.level} onChange={(e) => setEscalation(s => ({ ...s, level: e.target.value }))}>
                    {ESCALATION_LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
                  </select>
                  <input
                    type="text"
                    maxLength={500}
                    placeholder="Reason"
                    value={escalation.reason}
                    onChange={(e) => setEscalation(s => ({ ...s, reason: e.target.value }))}
                  />
                  <button type="submit" className="btn btn-secondary btn-sm">Set</button>
                </form>
              </ActionCard>

              {/* Goodwill */}
              <ActionCard title="Goodwill credit" icon={<FiGift />}>
                <form onSubmit={submitGoodwill} style={{ display: 'grid', gap: '0.5rem' }}>
                  <input
                    type="number"
                    min="1"
                    max="10000"
                    step="1"
                    placeholder="Amount (₹)"
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
                  <button type="submit" className="btn btn-secondary btn-sm">Issue</button>
                </form>
              </ActionCard>

              {/* Cancel with reason */}
              <ActionCard title="Cancel with reason" icon={<FiXCircle />}>
                <textarea
                  rows={2}
                  maxLength={500}
                  placeholder="Cancellation reason (required)"
                  value={cancelReason}
                  onChange={(e) => setCancelReason(e.target.value)}
                  style={{ width: '100%' }}
                />
                <button type="button" className="btn btn-danger btn-sm" onClick={submitCancel} disabled={!cancelReason.trim() || booking.status === 'CANCELLED'}>
                  <FiXCircle /> Cancel booking
                </button>
              </ActionCard>
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
              <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
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
              <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.6rem' }}>
                {notes.map(n => (
                  <li key={n.id} style={{ padding: '0.7rem', border: '1px solid var(--border)', borderRadius: 4, background: n.pinned ? 'var(--surface-2, #fffbe6)' : undefined }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.35rem' }}>
                      <strong>{n.authorName}</strong>
                      <span style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                        <span className="badge" style={{ background: n.visibility === 'CUSTOMER' ? '#dff' : '#eee' }}>
                          {n.visibility}
                        </span>
                        {n.edited && <small style={{ color: 'var(--text-secondary)' }}>(edited)</small>}
                        <small style={{ color: 'var(--text-secondary)' }}>{parseServerDate(n.createdAt)?.toLocaleString() || ''}</small>
                        <button type="button" className="btn btn-ghost btn-xs" title="Pin / Unpin" onClick={() => togglePin(n)}>
                          <FiBookmark />
                        </button>
                        <button type="button" className="btn btn-ghost btn-xs" title="Delete" onClick={() => removeNote(n.id)}>
                          <FiTrash2 />
                        </button>
                      </span>
                    </div>
                    <div style={{ whiteSpace: 'pre-wrap' }}>{n.body}</div>
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
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem' }}>Channel</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem' }}>Subject / Type</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem' }}>Status</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem' }}>Retries</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem' }}>Last error</th>
                    <th style={{ width: 100 }} />
                  </tr>
                </thead>
                <tbody>
                  {notifications.map(n => (
                    <tr key={n.id} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={{ padding: '0.4rem 0.6rem' }}>{n.channel}</td>
                      <td style={{ padding: '0.4rem 0.6rem' }}>{n.subject || n.notificationType}</td>
                      <td style={{ padding: '0.4rem 0.6rem' }}>
                        <span className={`badge ${n.deliveryStatus === 'SENT' ? 'badge-success' : n.deliveryStatus === 'FAILED' ? 'badge-danger' : 'badge-warning'}`}>
                          {n.deliveryStatus}
                        </span>
                      </td>
                      <td style={{ padding: '0.4rem 0.6rem' }}>{n.retryCount}</td>
                      <td style={{ padding: '0.4rem 0.6rem', maxWidth: 280, fontSize: '0.85em', color: 'var(--text-secondary)' }}>
                        {n.failureReason || '—'}
                      </td>
                      <td style={{ padding: '0.4rem 0.6rem' }}>
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
      <div style={{ fontSize: '0.75em', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div>{children}</div>
    </div>
  );
}

function ActionCard({ title, icon, children }) {
  return (
    <div style={{ padding: '0.85rem', border: '1px solid var(--border)', borderRadius: 6, background: 'var(--surface, #fff)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontWeight: 600, marginBottom: '0.5rem' }}>
        {icon} {title}
      </div>
      {children}
    </div>
  );
}
