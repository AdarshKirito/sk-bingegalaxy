import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiTrendingUp, FiAlertTriangle } from 'react-icons/fi';
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
 * Styling follows the `.adm-*` vocabulary used by AdminReports, its closest sibling
 * (a date-range analytics page). This repo has TWO admin CSS systems and the other one
 * (`.admin-card`, `.admin-header`) has no definitions for most of what a page like this
 * needs -- using it renders a structurally correct page that looks broken.
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

/** Above this share of attempts, a source's cancellations are worth flagging. */
const HIGH_CANCEL_RATE = 0.3;

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
  const [fromDate, setFromDate] = useState(daysAgoISO(30));
  const [toDate, setToDate] = useState(todayISO());
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async (from, to) => {
    if (!from || !to) return;
    if (new Date(to) < new Date(from)) {
      toast.error('"To" cannot be earlier than "From".');
      return;
    }
    if ((new Date(to) - new Date(from)) / 86400000 > MAX_RANGE_DAYS) {
      toast.error(`Choose a range of ${MAX_RANGE_DAYS} days or less.`);
      return;
    }
    setLoading(true);
    try {
      const res = await adminService.getAttributionPerformance(from, to);
      setRows(res.data?.data || []);
      setLoaded(true);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load channel attribution');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(fromDate, toDate); }, [load]); // eslint-disable-line react-hooks/exhaustive-deps

  const applyRange = (days) => {
    const f = daysAgoISO(days);
    const t = todayISO();
    setFromDate(f);
    setToDate(t);
    load(f, t);
  };

  const totalConversions = rows.reduce((s, r) => s + (r.bookings || 0), 0);
  const totalCancelled = rows.reduce((s, r) => s + (r.cancelled || 0), 0);

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiTrendingUp /> Analytics</span>
          <h1>Channel attribution</h1>
          <p>Bookings that arrived through a referral link, grouped by source.</p>
        </div>
      </div>

      <div className="adm-form">
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div className="input-group" style={{ margin: 0 }}>
            <label htmlFor="attr-from">From</label>
            <input id="attr-from" type="date" value={fromDate} max={toDate}
                   onChange={(e) => setFromDate(e.target.value)} />
          </div>
          <div className="input-group" style={{ margin: 0 }}>
            <label htmlFor="attr-to">To</label>
            <input id="attr-to" type="date" value={toDate} min={fromDate} max={todayISO()}
                   onChange={(e) => setToDate(e.target.value)} />
          </div>
          <button className="btn btn-primary" onClick={() => load(fromDate, toDate)}
                  disabled={loading || !fromDate || !toDate}>
            {loading ? 'Loading…' : 'Apply'}
          </button>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginTop: '0.75rem' }}>
          {RANGES.map((r) => (
            <button key={r.days} className="btn btn-secondary btn-sm" onClick={() => applyRange(r.days)}>
              {r.label}
            </button>
          ))}
        </div>
      </div>

      {loading && <div className="loading"><div className="spinner"></div></div>}

      {!loading && loaded && rows.length === 0 && (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiTrendingUp /></span>
          <h3>No referred bookings in this period</h3>
          {/* Said plainly, because "no data" here is ambiguous and the ambiguity is the
              question the screen exists to answer: no referrals, or no channel live. */}
          <p>
            Expected until a referral channel is live. Direct bookings are not counted
            here — only bookings that arrived through a tracked link.
          </p>
        </div>
      )}

      {!loading && rows.length > 0 && (
        <>
          <div className="adm-card" style={{ padding: '0.75rem 1.15rem', marginBottom: '1rem' }}>
            <strong>{totalConversions}</strong> referred booking{totalConversions === 1 ? '' : 's'}
            {totalCancelled > 0 && (
              <span className="adm-hint" style={{ marginLeft: '0.5rem' }}>
                · {totalCancelled} cancelled or no-show
              </span>
            )}
          </div>

          <div className="admin-table-wrap">
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
                  const cancelRate = attempts ? r.cancelled / attempts : 0;
                  return (
                    <tr key={r.source}>
                      <td>
                        {prettySource(r.source)}
                        <div className="adm-hint" style={{ fontSize: '0.8em' }}>{r.source}</div>
                      </td>
                      <td style={{ textAlign: 'right' }}>{r.bookings}</td>
                      <td style={{ textAlign: 'right' }}>
                        {r.cancelled}
                        {/* Surfaced rather than filtered: a third of a source's bookings
                            falling over is the most useful thing this table can say. */}
                        {cancelRate > HIGH_CANCEL_RATE && (
                          <span title="High cancellation rate for this source"
                                style={{ marginLeft: 4, verticalAlign: 'middle' }}>
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
          </div>

          <p className="adm-table-note">
            Revenue counts confirmed, checked-in and completed bookings only, in this
            venue&apos;s currency. Pending bookings are excluded until they are paid.
          </p>
        </>
      )}
    </div>
  );
}
