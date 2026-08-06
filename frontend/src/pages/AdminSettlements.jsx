import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiDollarSign, FiAlertTriangle } from 'react-icons/fi';
import './AdminPages.css';

/**
 * What channels owe this venue, and whether it has arrived (distribution slice 6, G-E).
 *
 * <p><b>These are receivables, not revenue.</b> Viator is merchant of record and pays
 * the net rate only <em>after</em> the experience completes, so channel money is
 * routinely weeks away at the moment a booking appears. Presenting it as cash in the
 * bank would misstate a venue's cash flow — the single most damaging error in the v1
 * design this work replaced.
 *
 * <p><b>Totals are per currency and never summed.</b> A destination may remit in one
 * currency while the venue banks in another; one combined figure would be confident and
 * meaningless.
 */

const STATUS_TONE = {
  PAID: 'success',
  EXPECTED: 'info',
  PENDING: 'muted',
  SHORT_PAID: 'danger',
  OVER_PAID: 'warning',
  DISPUTED: 'danger',
  WRITTEN_OFF: 'muted',
};

/**
 * Minor units → display. Kept here rather than on the wire so no rounding happens
 * between the database and the screen.
 */
const money = (minor, currency) => {
  const v = (Number(minor) || 0) / 100;
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(v);
  } catch {
    // An unrecognised ISO code must still render a number rather than blank the cell.
    return `${currency} ${v.toFixed(2)}`;
  }
};

export default function AdminSettlements() {
  const [rows, setRows] = useState([]);
  const [totals, setTotals] = useState({});
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [busyId, setBusyId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [list, sums] = await Promise.all([
        adminService.getDistributionSettlements(),
        adminService.getDistributionSettlementTotals(),
      ]);
      setRows(list.data?.data || []);
      setTotals(sums.data?.data || {});
      setLoaded(true);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load settlements');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const recordPayout = async (row) => {
    const entered = window.prompt(
      `Amount received for ${row.bookingRef} (${row.settlementCurrency}), in major units:`,
      (row.outstandingMinor / 100).toFixed(2));
    if (entered === null) return;
    const minor = Math.round(parseFloat(entered) * 100);
    if (!Number.isFinite(minor) || minor < 0) {
      toast.error('Enter a valid amount.');
      return;
    }
    setBusyId(row.id);
    try {
      await adminService.recordDistributionPayout(row.id, minor);
      toast.success('Payout recorded');
      await load();
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not record the payout');
    } finally {
      setBusyId(null);
    }
  };

  const overdueCount = rows.filter((r) => r.overdue).length;

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiDollarSign /> Distribution</span>
          <h1>Channel settlements</h1>
          <p>Money channels owe this venue. Not yet received — these are receivables.</p>
        </div>
      </div>

      {Object.keys(totals).length > 0 && (
        <div className="adm-panel-stack"
             style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
          {/* One card per currency. Deliberately no grand total. */}
          {Object.entries(totals).map(([currency, minor]) => (
            <div key={currency} className="adm-mini-card">
              <div className="adm-mini-card-title">Outstanding · {currency}</div>
              <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{money(minor, currency)}</div>
            </div>
          ))}
        </div>
      )}

      {overdueCount > 0 && (
        <div className="adm-card" style={{ padding: '0.75rem 1.15rem', marginBottom: '1rem' }}>
          <FiAlertTriangle aria-hidden="true" />{' '}
          <strong>{overdueCount}</strong> settlement{overdueCount === 1 ? '' : 's'} past the
          expected payout date.
        </div>
      )}

      {loading && <div className="loading"><div className="spinner"></div></div>}

      {!loading && loaded && rows.length === 0 && (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiDollarSign /></span>
          <h3>Nothing outstanding</h3>
          <p>
            Settlements appear once a channel-collected booking is taken. Direct bookings
            are settled through payments, not here.
          </p>
        </div>
      )}

      {!loading && rows.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">Booking</th>
                <th scope="col">Destination</th>
                <th scope="col" style={{ textAlign: 'right' }}>Outstanding</th>
                <th scope="col">Expected</th>
                <th scope="col">Status</th>
                <th scope="col" style={{ textAlign: 'right' }}>Variance</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.bookingRef}</td>
                  <td>{r.destinationCode}</td>
                  <td style={{ textAlign: 'right' }}>
                    {money(r.outstandingMinor, r.settlementCurrency)}
                  </td>
                  <td>
                    {r.expectedPayoutAt || '—'}
                    {r.overdue && (
                      <span title="Past the expected payout date" style={{ marginLeft: 4 }}>
                        <FiAlertTriangle aria-label="Overdue" />
                      </span>
                    )}
                  </td>
                  <td>
                    <span className={`badge badge-${STATUS_TONE[r.settlementStatus] || 'muted'}`}>
                      {r.settlementStatus}
                    </span>
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    {/* Shown even when zero: a variance column that only appears on
                        problems makes its absence ambiguous. */}
                    {r.varianceMinor === 0
                      ? '—'
                      : money(r.varianceMinor, r.settlementCurrency)}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn btn-secondary btn-sm"
                            disabled={busyId === r.id}
                            onClick={() => recordPayout(r)}>
                      {busyId === r.id ? 'Saving…' : 'Record payout'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="adm-table-note">
        Recording a payout stores the difference against what was owed. A short payment
        is marked <strong>SHORT_PAID</strong> rather than settled, so it stays visible
        until it is chased.
      </p>
    </div>
  );
}
