import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiUploadCloud, FiAlertTriangle, FiCheckCircle } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Listing readiness per destination (distribution slice 4).
 *
 * <p>Readiness is per <b>(listing × destination)</b>, not per listing: every destination
 * demands different content — meeting point, age restrictions, inclusions, voucher
 * instructions — so a listing that satisfies Viator may be incomplete for GetYourGuide.
 * The table is therefore keyed on the pair, and a listing appears once per destination.
 *
 * <p><b>Blocking reasons are the product here.</b> A bare "78% ready" tells an operator
 * nothing they can act on; the list of what is missing is the entire reason this screen
 * exists rather than a percentage on the connections page.
 *
 * <p>Publish is offered only at 100%, mirroring the server — which refuses below 100,
 * refuses a non-ACTIVE connection, refuses stop-sell, and refuses a destination the
 * venue never enabled. The button is a convenience, never the enforcement.
 */

const STATE_TONE = {
  LIVE: 'success',
  READY: 'info',
  PUBLISHING: 'info',
  DRAFT: 'muted',
  PAUSED: 'warning',
  BLOCKED: 'danger',
  FAILED: 'danger',
};

export default function AdminListings() {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [busyId, setBusyId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.getDistributionListings();
      setRows(res.data?.data || []);
      setLoaded(true);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load listings');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const publish = async (row) => {
    setBusyId(row.id);
    try {
      await adminService.publishDistributionListing(row.id);
      toast.success(`Published to ${row.destinationName || row.destinationCode}`);
      await load();
    } catch (e) {
      // The server's refusal is the useful message — it names the actual blocker
      // (readiness, connection state, stop-sell, destination not enabled).
      toast.error(e.response?.data?.message || 'Could not publish');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiUploadCloud /> Distribution</span>
          <h1>Listing readiness</h1>
          <p>What each event type still needs before it can go live on a destination.</p>
        </div>
      </div>

      {loading && <div className="loading"><div className="spinner"></div></div>}

      {!loading && loaded && rows.length === 0 && (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiUploadCloud /></span>
          <h3>No listings yet</h3>
          {/* Ambiguity is the thing to remove: "empty" here means no connection has been
              pointed at a destination yet, not that something failed. */}
          <p>
            Listings appear once a connection reaches a destination. Set one up under
            Distribution, then evaluate an event type against it.
          </p>
        </div>
      )}

      {!loading && rows.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">Destination</th>
                <th scope="col">Event type</th>
                <th scope="col">State</th>
                <th scope="col">Readiness</th>
                <th scope="col">Blocking</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const ready = r.readinessPct === 100;
                return (
                  <tr key={r.id}>
                    <td>
                      {r.destinationName || r.destinationCode}
                      <div className="adm-hint" style={{ fontSize: '0.8em' }}>{r.destinationCode}</div>
                    </td>
                    <td>{r.externalProductId || `#${r.eventTypeId}`}</td>
                    <td>
                      <span className={`badge badge-${STATE_TONE[r.publishState] || 'muted'}`}>
                        {r.publishState}
                      </span>
                    </td>
                    <td>
                      {ready
                        ? <span><FiCheckCircle aria-label="Ready" /> 100%</span>
                        : <span>{r.readinessPct}%</span>}
                    </td>
                    <td>
                      {r.blockingReasons?.length > 0 ? (
                        // Listed in full, not truncated to a count. "3 issues" sends the
                        // operator hunting; the reasons are what they act on.
                        <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
                          {r.blockingReasons.map((b) => (
                            <li key={b} className="adm-hint">{b}</li>
                          ))}
                        </ul>
                      ) : (
                        <span className="adm-hint">—</span>
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {r.publishState !== 'LIVE' && (
                        <button
                          className="btn btn-primary btn-sm"
                          disabled={!ready || busyId === r.id}
                          // Disabled below 100% because the server will refuse anyway;
                          // the title says why so a greyed button is not a dead end.
                          title={ready ? 'Publish to this destination'
                                       : 'Resolve the blocking items first'}
                          onClick={() => publish(r)}
                        >
                          {busyId === r.id ? 'Publishing…' : 'Publish'}
                        </button>
                      )}
                      {r.publishState === 'LIVE' && (
                        <span className="adm-hint">
                          {r.lastPublishedAt
                            ? `Live since ${new Date(r.lastPublishedAt).toLocaleDateString()}`
                            : 'Live'}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {!loading && rows.some((r) => r.readinessPct < 100) && (
        <p className="adm-table-note">
          <FiAlertTriangle aria-hidden="true" /> A listing cannot go live below 100%.
          The database enforces this too, so no interface can bypass it.
        </p>
      )}
    </div>
  );
}
