import { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { disputeService } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import { FiRefreshCw, FiClock, FiEdit3, FiAlertOctagon, FiExternalLink } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Payment dispute / chargeback triage console.
 *
 * Disputes are ingested from Razorpay webhooks (payment.dispute.*) by the
 * payment-service and are money-at-risk: each carries a hard respond-by deadline
 * after which the chargeback is auto-lost. The "Open" tab is sorted most-urgent
 * first (server-side, by respondBy ASC); ops record evidence via timestamped
 * notes before submitting a response in the Razorpay dashboard.
 *
 * Everything here is binge-scoped server-side (X-Binge-Id), so this page only
 * renders inside the admin-binge shell.
 */

const TABS = [
  { key: 'open', label: 'Open · needs action' },
  { key: 'all',  label: 'All (history)' },
];

const STATUS_COLOR = {
  OPEN:          { bg: 'rgba(245, 158, 11, 0.15)', fg: '#f59e0b' },
  UNDER_REVIEW:  { bg: 'rgba(59, 130, 246, 0.15)', fg: '#3b82f6' },
  WON:           { bg: 'rgba(16, 185, 129, 0.15)', fg: '#10b981' },
  LOST:          { bg: 'rgba(239, 68, 68, 0.15)',  fg: '#ef4444' },
  ACCEPTED:      { bg: 'rgba(107, 114, 128, 0.15)', fg: '#6b7280' },
};

const TERMINAL = new Set(['WON', 'LOST', 'ACCEPTED']);

const fmt = (v) => (v == null ? '—' : new Date(v).toLocaleString());

const StatusPill = ({ s }) => {
  const c = STATUS_COLOR[s] || STATUS_COLOR.OPEN;
  return (
    <span style={{
      background: c.bg, color: c.fg, padding: '2px 8px', borderRadius: 4,
      fontSize: '0.78rem', fontWeight: 600, letterSpacing: '0.02em',
    }}>{s}</span>
  );
};

/** Render the respond-by deadline as a RAG urgency badge + human countdown. */
function DeadlineBadge({ minutes }) {
  if (minutes == null) return null;
  const overdue = minutes < 0;
  const abs = Math.abs(minutes);
  const days = Math.floor(abs / 1440);
  const hours = Math.floor((abs % 1440) / 60);
  const mins = abs % 60;
  const human = days > 0 ? `${days}d ${hours}h` : hours > 0 ? `${hours}h ${mins}m` : `${mins}m`;

  let bg, fg, label;
  if (overdue)            { bg = 'rgba(127, 29, 29, 0.4)';  fg = '#fca5a5'; label = `OVERDUE by ${human}`; }
  else if (minutes < 1440){ bg = 'rgba(239, 68, 68, 0.15)'; fg = '#ef4444'; label = `${human} left`; }
  else if (minutes < 2880){ bg = 'rgba(245, 158, 11, 0.15)';fg = '#f59e0b'; label = `${human} left`; }
  else                    { bg = 'rgba(16, 185, 129, 0.15)';fg = '#10b981'; label = `${human} left`; }

  return (
    <span style={{
      background: bg, color: fg, padding: '2px 8px', borderRadius: 4,
      fontSize: '0.78rem', fontWeight: 700, letterSpacing: '0.02em',
      display: 'inline-flex', alignItems: 'center', gap: 4,
    }}>
      <FiClock style={{ verticalAlign: '-2px' }} /> {label}
    </span>
  );
}

export default function AdminDisputes() {
  const confirm = useConfirm();

  const [tab, setTab] = useState('open');
  const [items, setItems] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState(null);
  const [tick, setTick] = useState(0);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const fn = tab === 'open' ? disputeService.listOpen : disputeService.listAll;
      const res = await fn({ page, size: 20 });
      const d = res.data?.data || res.data;
      // payment-service returns a Spring Page: { content, totalPages, totalElements, number }
      setItems(Array.isArray(d) ? d : (d?.content || []));
      setTotalPages(d?.totalPages ?? 0);
      setTotal(d?.totalElements ?? (Array.isArray(d) ? d.length : 0));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load disputes');
      setItems([]);
      setTotalPages(0);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [tab, page, tick]);

  useEffect(() => { fetchList(); }, [fetchList]);

  // Reset to the first page whenever the tab changes.
  const switchTab = (key) => { setTab(key); setPage(0); };

  const addNote = async (dispute) => {
    const result = await confirm({
      title: `Add note to dispute ${dispute.gatewayDisputeId || `#${dispute.id}`}`,
      message: 'Notes are timestamped, stamped with your email, and appended (never overwritten). '
        + 'Use them to record the evidence you are gathering before responding in the Razorpay dashboard.',
      confirmLabel: 'Save note',
      cancelLabel: 'Cancel',
      variant: 'primary',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Note',
      reasonPlaceholder: 'e.g. Pulled signed booking confirmation + check-in photo as evidence…',
    });
    if (!result) return;
    const notes = (result.reason || '').trim();
    if (!notes) return;
    setBusyId(dispute.id);
    try {
      await disputeService.updateNotes(dispute.id, notes);
      toast.success('Note saved');
      setTick((t) => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to save note');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="admin-page" style={{ maxWidth: 1280, margin: '0 auto', padding: '1.25rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem', flexWrap: 'wrap', gap: '0.75rem' }}>
        <div>
          <h1 style={{ margin: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
            <FiAlertOctagon /> Payment disputes
          </h1>
          <p style={{ margin: '0.25rem 0 0', color: 'var(--text-secondary)' }}>
            Razorpay chargebacks for this venue. Respond before the deadline — an unanswered
            dispute is auto-lost and the funds are debited. Gather evidence, log it as notes,
            then submit your response in the Razorpay dashboard.
          </p>
        </div>
        <button className="btn-secondary" onClick={() => setTick((t) => t + 1)} disabled={loading}>
          <FiRefreshCw style={{ verticalAlign: '-2px', marginRight: 6 }} /> Refresh
        </button>
      </div>

      <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        {TABS.map((tb) => (
          <button
            key={tb.key}
            onClick={() => switchTab(tb.key)}
            style={{
              padding: '0.4rem 0.8rem', borderRadius: 4, cursor: 'pointer',
              border: '1px solid var(--border)',
              background: tab === tb.key ? 'var(--primary)' : 'transparent',
              color: tab === tb.key ? '#fff' : 'var(--text-primary)',
              fontWeight: tab === tb.key ? 600 : 400,
            }}>{tb.label}</button>
        ))}
        {!loading && (
          <span style={{ marginLeft: 'auto', alignSelf: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
            {total} dispute{total === 1 ? '' : 's'}
          </span>
        )}
      </div>

      {loading ? (
        <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading…</div>
      ) : items.length === 0 ? (
        <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
          {tab === 'open'
            ? 'No open disputes 🎉 — nothing needs a response right now.'
            : 'No disputes on record for this venue.'}
        </div>
      ) : (
        <div style={{ display: 'grid', gap: '0.75rem' }}>
          {items.map((d) => {
            const terminal = TERMINAL.has(d.status);
            return (
              <div key={d.id} style={{
                border: '1px solid var(--border)', borderRadius: 8, padding: '1rem',
                background: 'var(--surface, #111827)',
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
                  <div style={{ flex: 1, minWidth: 280 }}>
                    <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginBottom: '0.4rem', flexWrap: 'wrap' }}>
                      <strong style={{ color: 'var(--primary)' }}>{d.gatewayDisputeId || `#${d.id}`}</strong>
                      <StatusPill s={d.status} />
                      {!terminal && <DeadlineBadge minutes={d.minutesUntilDeadline} />}
                    </div>
                    <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', lineHeight: 1.6 }}>
                      <div>
                        <strong>Amount:</strong> {d.amount != null ? `${d.amount} ${d.currency || ''}` : '—'}
                      </div>
                      {d.bookingRef && (
                        <div>
                          <strong>Booking:</strong>{' '}
                          <Link to={`/admin/bookings?ref=${encodeURIComponent(d.bookingRef)}`} style={{ color: 'var(--primary)' }}>
                            {d.bookingRef} <FiExternalLink style={{ verticalAlign: '-2px' }} />
                          </Link>
                        </div>
                      )}
                      {d.transactionId && <div><strong>Txn:</strong> {d.transactionId}</div>}
                      {(d.reasonCode || d.reasonDescription) && (
                        <div><strong>Reason:</strong> {[d.reasonCode, d.reasonDescription].filter(Boolean).join(' — ')}</div>
                      )}
                      <div style={{ marginTop: 6, display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
                        <span><strong>Respond by:</strong> {fmt(d.respondBy)}</span>
                        <span><strong>Raised:</strong> {fmt(d.gatewayCreatedAt || d.createdAt)}</span>
                      </div>
                      {d.opsNotes && (
                        <div style={{ marginTop: 8, padding: '8px 10px', background: 'rgba(255,255,255,0.04)', borderRadius: 4, whiteSpace: 'pre-wrap', fontSize: '0.85rem' }}>
                          <strong>Ops notes</strong>
                          <div style={{ marginTop: 4 }}>{d.opsNotes}</div>
                        </div>
                      )}
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
                    <button
                      className="btn-secondary"
                      disabled={busyId === d.id || terminal}
                      title={terminal ? 'This dispute is resolved — notes are locked' : 'Add an evidence note'}
                      onClick={() => addNote(d)}>
                      <FiEdit3 style={{ verticalAlign: '-2px', marginRight: 4 }} /> Add note
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '0.75rem', marginTop: '1rem' }}>
          <button className="btn-secondary" disabled={page <= 0 || loading} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            ← Prev
          </button>
          <span style={{ color: 'var(--text-secondary)' }}>Page {page + 1} of {totalPages}</span>
          <button className="btn-secondary" disabled={page >= totalPages - 1 || loading} onClick={() => setPage((p) => p + 1)}>
            Next →
          </button>
        </div>
      )}
    </div>
  );
}
