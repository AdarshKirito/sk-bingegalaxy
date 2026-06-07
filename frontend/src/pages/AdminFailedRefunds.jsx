import { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import { FiRefreshCw, FiRotateCcw, FiDollarSign, FiExternalLink } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Failed-refund queue.
 *
 * Surfaces every refund whose per-attempt lifecycle ended in FAILED — i.e. money
 * the business owes a customer that never settled at the gateway. Left unworked,
 * these become trust / chargeback liabilities, so this is the single place ops can
 * see them all and retry.
 *
 * Retry is maker-checker aware: above the configured threshold the server returns
 * HTTP 202 and creates an approval request (handled at /admin/approvals); below it
 * the gateway is charged again immediately.
 *
 * Binge-scoped server-side (X-Binge-Id) — renders inside the admin-binge shell.
 */

const fmt = (v) => (v == null ? '—' : new Date(v).toLocaleString());
const money = (v) => (v == null ? '—' : `₹${Number(v).toLocaleString()}`);

export default function AdminFailedRefunds() {
  const confirm = useConfirm();

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
      const res = await adminService.getFailedRefunds({ page, size: 20 });
      const d = res.data?.data || res.data;
      setItems(Array.isArray(d) ? d : (d?.content || []));
      setTotalPages(d?.totalPages ?? 0);
      setTotal(d?.totalElements ?? (Array.isArray(d) ? d.length : 0));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load failed refunds');
      setItems([]);
      setTotalPages(0);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [page, tick]);

  useEffect(() => { fetchList(); }, [fetchList]);

  const retry = async (r) => {
    const ok = await confirm({
      title: `Retry failed refund #${r.id}?`,
      message: `Amount: ${money(r.amount)}. If this exceeds the maker-checker threshold an approval `
        + 'request is created for another admin to review (see Approvals). Otherwise the gateway is '
        + 'charged again immediately. Make sure the original refund truly failed before retrying.',
      confirmLabel: 'Retry refund',
      cancelLabel: 'Cancel',
      variant: 'danger',
    });
    if (!ok) return;
    setBusyId(r.id);
    try {
      const res = await adminService.retryFailedRefund(r.id);
      if (res.status === 202) {
        toast.info(res.data?.message || 'Approval required — see Approvals');
      } else {
        toast.success('Refund retry issued');
      }
      setTick((t) => t + 1);
    } catch (err) {
      // The axios interceptor rejects 4xx; a 202 can also surface here in some flows.
      const msg = err.response?.data?.message || err.message;
      if (err.response?.status === 202) toast.info(msg);
      else toast.error(msg || 'Refund retry failed');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="admin-page" style={{ maxWidth: 1280, margin: '0 auto', padding: '1.25rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem', flexWrap: 'wrap', gap: '0.75rem' }}>
        <div>
          <h1 style={{ margin: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
            <FiDollarSign /> Failed refunds
          </h1>
          <p style={{ margin: '0.25rem 0 0', color: 'var(--text-secondary)' }}>
            Refunds that didn’t settle at the gateway — money the business still owes these customers.
            Retry from here; large amounts route through maker-checker approval automatically.
          </p>
        </div>
        <button className="btn-secondary" onClick={() => setTick((t) => t + 1)} disabled={loading}>
          <FiRefreshCw style={{ verticalAlign: '-2px', marginRight: 6 }} /> Refresh
        </button>
      </div>

      {!loading && (
        <div style={{ marginBottom: '0.75rem', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
          {total} failed refund{total === 1 ? '' : 's'}
        </div>
      )}

      {loading ? (
        <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading…</div>
      ) : items.length === 0 ? (
        <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
          No failed refunds 🎉 — every refund has settled.
        </div>
      ) : (
        <div style={{ display: 'grid', gap: '0.75rem' }}>
          {items.map((r) => (
            <div key={r.id} style={{
              border: '1px solid var(--border)', borderRadius: 8, padding: '1rem',
              background: 'var(--surface, #111827)',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
                <div style={{ flex: 1, minWidth: 280 }}>
                  <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginBottom: '0.4rem', flexWrap: 'wrap' }}>
                    <strong>#{r.id}</strong>
                    <span style={{
                      background: 'rgba(239, 68, 68, 0.15)', color: '#ef4444',
                      padding: '2px 8px', borderRadius: 4, fontSize: '0.78rem', fontWeight: 600,
                    }}>FAILED</span>
                    {r.retryCount > 0 && (
                      <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                        retried {r.retryCount}×
                      </span>
                    )}
                  </div>
                  <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', lineHeight: 1.6 }}>
                    <div><strong>Amount:</strong> {money(r.amount)}</div>
                    {r.bookingRef && (
                      <div>
                        <strong>Booking:</strong>{' '}
                        <Link to={`/admin/bookings?ref=${encodeURIComponent(r.bookingRef)}`} style={{ color: 'var(--primary)' }}>
                          {r.bookingRef} <FiExternalLink style={{ verticalAlign: '-2px' }} />
                        </Link>
                      </div>
                    )}
                    {r.reason && <div><strong>Reason:</strong> {r.reason}</div>}
                    {r.failureReason && (
                      <div style={{ color: '#ef4444' }}><strong>Failure:</strong> {r.failureReason}</div>
                    )}
                    <div style={{ marginTop: 6, display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
                      {r.initiatedBy && <span><strong>Initiated by:</strong> {r.initiatedBy}</span>}
                      <span><strong>Created:</strong> {fmt(r.createdAt)}</span>
                    </div>
                  </div>
                </div>
                <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
                  <button className="btn-danger" disabled={busyId === r.id} onClick={() => retry(r)}>
                    <FiRotateCcw style={{ verticalAlign: '-2px', marginRight: 4 }} /> Retry
                  </button>
                </div>
              </div>
            </div>
          ))}
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
