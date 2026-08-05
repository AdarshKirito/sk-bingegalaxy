import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiTrendingUp, FiAlertTriangle, FiInfo } from 'react-icons/fi';
import './AdminPages.css';
import { venueMoney } from '../utils/venueLocale';

/**
 * Where bookings actually came from (distribution design G-B).
 *
 * Until this screen existed the attribution captured at checkout was write-only:
 * stored correctly, readable only by calling the API by hand. That made the Google
 * Things to Do business case exactly as unprovable as before the feature was built,
 * which is the whole reason attribution was sequenced ahead of any connector.
 *
 * Two presentation rules the numbers depend on:
 *
 *  - Cancellations are shown BESIDE conversions, never folded into them. A source with
 *    a high cancellation rate is a fact about that source; hiding it would make the
 *    worst channel look like the best.
 *  - Revenue is per-venue in the venue's own currency, with no grand total across
 *    venues. Under native per-binge pricing, summing venues would add different
 *    currencies together and produce a confident, meaningless number.
 */

const todayISO = () => new Date().toISOString().slice(0, 10);
const daysAgoISO = (n) => new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);

/** Server caps a single query at 366 days; mirror it so the UI cannot ask for a 400. */
const MAX_RANGE_DAYS = 366;

const RANGES = [
  { label: 'Last 7 days', days: 7 },
  { label: 'Last 30 days', days: 30 },
  { label: 'Last 90 days', days: 90 },
  { label: 'Last 12 months', days: 365 },
];

/**
 * Sources are stored canonically (lowercase, underscored) and never prettified
 * server-side, so an unrecognised channel survives verbatim. Presentation is the right
 * place to make them readable — and an unknown source must still render, not vanish.
 */
const prettySource = (s) =>
  (s || 'unknown')
    .split('_')
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');

export default function AdminAttribution() {
  const [from, setFrom] = useState(daysAgoISO(30));
  const [to, setTo] = useState(todayISO());
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async (fromDate, toDate) => {
    if (!fromDate || !toDate) return;
    if (new Date(toDate) < new Date(fromDate)) {
      toast.error('“To” cannot be earlier than “From”.');
      return;
    }
    const spanDays = (new Date(toDate) - new Date(fromDate)) / 86400000;
    if (spanDays > MAX_RANGE_DAYS) {
      toast.error(`Choose a range of ${MAX_RANGE_DAYS} days or less.`);
      return;
    }
    setLoading(true);
    try {
      const res = await adminService.getAttributionPerformance(fromDate, toDate);
      setRows(res.data?.data || []);
      setLoaded(true);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load channel attribution');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(from, to); }, [load]); // eslint-disable-line react-hooks/exhaustive-deps

  const applyRange = (days) => {
    const f = daysAgoISO(days);
    const t = todayISO();
    setFrom(f);
    setTo(t);
    load(f, t);
  };

  const totalConversions = rows.reduce((s, r) => s + (r.bookings || 0), 0);
  const totalCancelled = rows.reduce((s, r) => s + (r.cancelled || 0), 0);

  return (
    <div className="admin-page">
      <div className="admin-header">
        <h1><FiTrendingUp /> Channel attribution</h1>
        <p className="admin-subtitle">
          Bookings that arrived through a referral link, by source.
        </p>
      </div>

      <div className="admin-card" style={{ marginBottom: '1rem' }}>
        <div className="form-row">
          <label>
            From
            <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label>
            To
            <input type="date" value={to} min={from} max={todayISO()} onChange={(e) => setTo(e.target.value)} />
          </label>
          <button className="btn btn-primary" onClick={() => load(from, to)} disabled={loading}>
            {loading ? 'Loading…' : 'Apply'}
          </button>
        </div>
        <div className="row-actions" style={{ marginTop: '0.5rem' }}>
          {RANGES.map((r) => (
            <button key={r.days} className="btn btn-secondary btn-sm" onClick={() => applyRange(r.days)}>
              {r.label}
            </button>
          ))}
        </div>
      </div>

      {loaded && rows.length === 0 && (
        <div className="admin-card admin-empty">
          <FiInfo aria-hidden="true" />
          <h3>No referred bookings in this period</h3>
          {/* Said plainly, because "no data" here is ambiguous and the ambiguity
              matters: it may mean no referrals, or that no channel is live yet. */}
          <p>
            This is expected until a referral channel is live. Direct bookings are not
            counted here — only bookings that arrived through a tracked link.
          </p>
        </div>
      )}

      {rows.length > 0 && (
        <>
          <div className="admin-card" style={{ marginBottom: '1rem' }}>
            <strong>{totalConversions}</strong> referred booking{totalConversions === 1 ? '' : 's'}
            {totalCancelled > 0 && (
              <span className="muted"> · {totalCancelled} cancelled or no-show</span>
            )}
          </div>

          <div className="admin-card" style={{ overflowX: 'auto' }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th scope="col">Source</th>
                  <th scope="col" style={{ textAlign: 'right' }}>Bookings</th>
                  <th scope="col" style={{ textAlign: 'right' }}>Cancelled</th>
                  <th scope="col" style={{ textAlign: 'right' }}>Revenue</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const attempts = (r.bookings || 0) + (r.cancelled || 0);
                  const cancelRate = attempts ? (r.cancelled / attempts) : 0;
                  return (
                    <tr key={r.source}>
                      <td>
                        {prettySource(r.source)}
                        <div className="muted" style={{ fontSize: '0.8em' }}>{r.source}</div>
                      </td>
                      <td style={{ textAlign: 'right' }}>{r.bookings}</td>
                      <td style={{ textAlign: 'right' }}>
                        {r.cancelled}
                        {/* Surfaced rather than filtered: a third of a source's bookings
                            falling over is the most useful thing this table can say. */}
                        {cancelRate > 0.3 && (
                          <span title="High cancellation rate for this source" style={{ marginLeft: 4 }}>
                            <FiAlertTriangle aria-label="High cancellation rate" />
                          </span>
                        )}
                      </td>
                      <td style={{ textAlign: 'right' }}>{venueMoney(r.revenue, r)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p className="muted" style={{ marginTop: '0.75rem', fontSize: '0.85em' }}>
              Revenue counts confirmed, checked-in and completed bookings only, in this
              venue&apos;s currency. Pending bookings are excluded until they are paid.
            </p>
          </div>
        </>
      )}
    </div>
  );
}
