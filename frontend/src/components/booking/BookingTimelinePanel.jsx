import { useEffect, useState } from 'react';
import { bookingService } from '../../services/endpoints';
import { FiActivity, FiCheckCircle, FiClock, FiCreditCard, FiAlertCircle, FiXCircle, FiSend, FiRotateCcw, FiFlag } from 'react-icons/fi';

/**
 * Read-only customer-facing booking lifecycle timeline. Pulls the
 * privacy-filtered timeline endpoint (excludes internal IDs, IP/UA, and
 * admin-only events) and renders the milestones in chronological order.
 */
const TYPE_META = {
  CREATED:           { icon: FiCheckCircle, color: '#10b981', label: 'Booking created' },
  SLOT_HELD:         { icon: FiClock,       color: '#3b82f6', label: 'Slot held' },
  PAYMENT_INITIATED: { icon: FiCreditCard,  color: '#3b82f6', label: 'Payment started' },
  PAYMENT_SUCCEEDED: { icon: FiCheckCircle, color: '#10b981', label: 'Payment received' },
  PAYMENT_FAILED:    { icon: FiAlertCircle, color: '#ef4444', label: 'Payment failed' },
  CONFIRMED:         { icon: FiCheckCircle, color: '#10b981', label: 'Booking confirmed' },
  NOTIFICATION_SENT: { icon: FiSend,        color: '#6b7280', label: 'Confirmation sent' },
  REMINDER_SENT:     { icon: FiSend,        color: '#6b7280', label: 'Reminder sent' },
  CHECKED_IN:        { icon: FiCheckCircle, color: '#10b981', label: 'Checked in' },
  COMPLETED:         { icon: FiCheckCircle, color: '#10b981', label: 'Completed' },
  CANCELLED:         { icon: FiXCircle,     color: '#ef4444', label: 'Cancelled' },
  REFUND_INITIATED:  { icon: FiRotateCcw,   color: '#f59e0b', label: 'Refund started' },
  REFUND_COMPLETED:  { icon: FiCheckCircle, color: '#10b981', label: 'Refund completed' },
  REFUND_FAILED:     { icon: FiAlertCircle, color: '#ef4444', label: 'Refund failed' },
  RESCHEDULED:       { icon: FiClock,       color: '#3b82f6', label: 'Rescheduled' },
  // Item 26 — surfaced when an out-of-order payment.success arrives after a
  // booking has already gone terminal. The customer rarely sees this thanks
  // to the privacy filter, but admins viewing the full timeline (and any
  // customer the filter intentionally lets through, e.g. refund-pending)
  // get a clearly-flagged entry instead of a raw enum string.
  MANUAL_REVIEW_FLAGGED: { icon: FiFlag,    color: '#ef4444', label: 'Manual review required' },
};

const fmt = (v) => {
  if (!v) return '';
  try { return new Date(v).toLocaleString(); } catch { return String(v); }
};

export default function BookingTimelinePanel({ bookingRef }) {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!bookingRef) return;
    setLoading(true);
    setError('');
    bookingService.getMyTimeline(bookingRef)
      .then(res => {
        const d = res.data?.data || res.data;
        setRows(Array.isArray(d) ? d : []);
      })
      .catch(err => setError(err.response?.data?.message || 'Could not load timeline'))
      .finally(() => setLoading(false));
  }, [bookingRef]);

  if (loading) {
    return <div style={{ padding: '0.5rem 0', color: 'var(--text-secondary)' }}>Loading timeline…</div>;
  }
  if (error) {
    return <div style={{ padding: '0.5rem 0', color: '#ef4444' }}>{error}</div>;
  }
  if (rows.length === 0) {
    return <div style={{ padding: '0.5rem 0', color: 'var(--text-secondary)' }}>No timeline events yet.</div>;
  }

  return (
    <div style={{ marginTop: '0.5rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--text-secondary)', marginBottom: '0.5rem' }}>
        <FiActivity /> <span style={{ fontSize: '0.85rem', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Booking timeline</span>
      </div>
      <ol style={{ listStyle: 'none', padding: 0, margin: 0, position: 'relative' }}>
        {rows.map((r, idx) => {
          const meta = TYPE_META[r.eventType] || { icon: FiActivity, color: '#6b7280', label: r.eventType };
          const Icon = meta.icon;
          return (
            <li key={idx} style={{
              display: 'flex', gap: '0.75rem',
              padding: '0.4rem 0',
              borderLeft: '2px solid var(--border)',
              paddingLeft: '0.85rem',
              marginLeft: '0.5rem',
              position: 'relative',
            }}>
              <span style={{
                position: 'absolute', left: -9, top: '0.55rem',
                width: 16, height: 16, borderRadius: '50%',
                background: meta.color, display: 'inline-flex',
                alignItems: 'center', justifyContent: 'center',
                color: '#fff', fontSize: 10,
              }}><Icon size={10} /></span>
              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
                  <strong>{meta.label}</strong>
                  <small style={{ color: 'var(--text-secondary)' }}>{fmt(r.at)}</small>
                </div>
                {r.description && (
                  <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: 2 }}>
                    {r.description}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
