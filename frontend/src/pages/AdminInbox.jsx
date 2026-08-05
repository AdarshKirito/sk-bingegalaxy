import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiInbox, FiRefreshCw, FiAlertTriangle } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Reservation inbox recovery console (distribution slice 6).
 *
 * <p>Every inbound provider message is persisted before anything is interpreted, so this
 * screen can answer the question a venue actually asks: <em>"a booking came through on
 * Viator — where is it?"</em> Without it the inbox is a table only a developer can read,
 * and a rejected reservation is indistinguishable from one that never arrived.
 *
 * <p><b>Status is the whole story, and the four failure states mean different things:</b>
 * SUPERSEDED (an older message overtaken by a newer one — nothing is wrong),
 * REJECTED (legitimately refused, e.g. the slot was taken — not retryable),
 * FAILED (processing errored — retryable), RECEIVED (still in flight). Collapsing them
 * into "error" would have an operator retrying a rejection forever.
 */

const STATUS_TONE = {
  APPLIED: 'success',
  RECEIVED: 'info',
  SUPERSEDED: 'muted',
  REJECTED: 'warning',
  FAILED: 'danger',
};

/** Said in the operator's language, not the enum's. */
const STATUS_HELP = {
  APPLIED: 'Booked — the reservation exists.',
  RECEIVED: 'Received, not yet applied.',
  SUPERSEDED: 'A newer message for this reservation won. Nothing is wrong.',
  REJECTED: 'Refused for a reason — see below. Retrying will not help.',
  FAILED: 'Processing errored. Safe to retry.',
};

export default function AdminInbox() {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [busyId, setBusyId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.getDistributionInbox(50);
      setRows(res.data?.data || []);
      setLoaded(true);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load the reservation inbox');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const retry = async (row) => {
    setBusyId(row.id);
    try {
      await adminService.retryDistributionInboxEntry(row.id);
      toast.success('Message requeued');
      await load();
    } catch (e) {
      // The server explains why a non-FAILED message cannot be retried; that sentence
      // is more useful than anything this page could invent.
      toast.error(e.response?.data?.message || 'Could not requeue');
    } finally {
      setBusyId(null);
    }
  };

  const failedCount = rows.filter((r) => r.status === 'FAILED').length;

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiInbox /> Distribution</span>
          <h1>Reservation inbox</h1>
          <p>Every message received from a channel, and what happened to it.</p>
        </div>
      </div>

      {failedCount > 0 && (
        <div className="adm-card" style={{ padding: '0.75rem 1.15rem', marginBottom: '1rem' }}>
          <FiAlertTriangle aria-hidden="true" />{' '}
          <strong>{failedCount}</strong> message{failedCount === 1 ? '' : 's'} failed and can be retried.
        </div>
      )}

      {loading && <div className="loading"><div className="spinner"></div></div>}

      {!loading && loaded && rows.length === 0 && (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiInbox /></span>
          <h3>No messages yet</h3>
          {/* Empty here is ambiguous between "no channel is live" and "something is
              broken", and that ambiguity is what an operator needs resolved. */}
          <p>
            Messages appear once a channel starts sending reservations. Feed-only
            destinations such as Google never send any — those bookings arrive directly.
          </p>
        </div>
      )}

      {!loading && rows.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">Received</th>
                <th scope="col">Destination</th>
                <th scope="col">Reference</th>
                <th scope="col">Type</th>
                <th scope="col">Status</th>
                <th scope="col">Detail</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.receivedAt ? new Date(r.receivedAt).toLocaleString() : '—'}</td>
                  <td>{r.destinationName || r.destinationCode}</td>
                  <td>
                    {r.externalRef}
                    {r.bookingRef && (
                      <div className="adm-hint" style={{ fontSize: '0.8em' }}>→ {r.bookingRef}</div>
                    )}
                  </td>
                  <td>
                    {r.messageType}
                    {/* RECEIPT_ORDER means the ordering was luck, not the provider's.
                        An operator reconciling a dispute needs to know which. */}
                    {r.orderingBasis === 'RECEIPT_ORDER' && (
                      <div className="adm-hint" style={{ fontSize: '0.75em' }}>
                        order not provider-supplied
                      </div>
                    )}
                  </td>
                  <td>
                    <span className={`badge badge-${STATUS_TONE[r.status] || 'muted'}`}
                          title={STATUS_HELP[r.status]}>
                      {r.status}
                    </span>
                  </td>
                  <td className="adm-hint">
                    {r.rejectReason || STATUS_HELP[r.status] || '—'}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    {/* Offered only for FAILED. Retrying a REJECTED message would fail
                        identically or succeed against a slot since taken; retrying a
                        SUPERSEDED one re-applies a message already overtaken. */}
                    {r.status === 'FAILED' && (
                      <button className="btn btn-secondary btn-sm"
                              disabled={busyId === r.id}
                              onClick={() => retry(r)}>
                        <FiRefreshCw aria-hidden="true" />{' '}
                        {busyId === r.id ? 'Requeuing…' : 'Retry'}
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
