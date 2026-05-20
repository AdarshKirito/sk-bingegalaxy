import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiBarChart2, FiCalendar, FiCheckCircle, FiClock, FiAlertTriangle, FiDownload } from 'react-icons/fi';
import { exportReportCSV, exportReportPDF } from '../services/exportUtils';
import { useAuth } from '../context/AuthContext';
import './AdminPages.css';

const todayISO = () => new Date().toISOString().slice(0, 10);

export default function AdminReports() {
  const { isSuperAdmin } = useAuth();
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState(todayISO());
  const [days, setDays] = useState('');
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);

  // Operational date + audit state
  const [opInfo, setOpInfo] = useState(null);  // OperationalDateDto from backend
  const [auditResult, setAuditResult] = useState(null);
  const [auditing, setAuditing] = useState(false);

  // SUPER_ADMIN manual operational-date override
  const [overrideDate, setOverrideDate] = useState('');
  const [overrideBusy, setOverrideBusy] = useState(false);
  const [advanceBusy, setAdvanceBusy] = useState(false);

  // Fetch operational date info (poll every 30s so the "available" status refreshes near midnight)
  const fetchOpInfo = useCallback(() => {
    adminService.getOperationalDate()
      .then(res => setOpInfo(res.data.data || res.data))
      .catch(() => {});
  }, []);

  useEffect(() => {
    fetchOpInfo();
    const timer = setInterval(fetchOpInfo, 30000);
    return () => clearInterval(timer);
  }, [fetchOpInfo]);

  // When days field changes, calculate fromDate = toDate - N days
  const handleDaysChange = (val) => {
    setDays(val);
    const n = parseInt(val, 10);
    if (!isNaN(n) && n > 0 && toDate) {
      const d = new Date(toDate + 'T00:00:00');
      d.setDate(d.getDate() - n);
      setFromDate(d.toISOString().slice(0, 10));
    }
  };

  // When fromDate changes, recalculate days
  const handleFromDateChange = (val) => {
    setFromDate(val);
    if (val && toDate) {
      const diff = Math.round((new Date(toDate + 'T00:00:00') - new Date(val + 'T00:00:00')) / 86400000);
      setDays(diff >= 0 ? String(diff) : '');
    }
  };

  // When toDate changes, recalculate days if fromDate is set
  const handleToDateChange = (val) => {
    setToDate(val);
    if (fromDate && val) {
      const diff = Math.round((new Date(val + 'T00:00:00') - new Date(fromDate + 'T00:00:00')) / 86400000);
      setDays(diff >= 0 ? String(diff) : '');
    }
  };

  const fetchReport = () => {
    if (!fromDate || !toDate) { toast.error('Please select both dates'); return; }
    if (fromDate > toDate) { toast.error('From date cannot be after To date'); return; }
    setLoading(true);
    adminService.getReportByDateRange(fromDate, toDate)
      .then(res => setReport(res.data.data || res.data))
      .catch(err => toast.error(err.response?.data?.message || err.userMessage || 'Failed to load report'))
      .finally(() => setLoading(false));
  };

  const handleRunAudit = async () => {
    setAuditing(true);
    try {
      const res = await adminService.runAudit();
      const result = res.data.data || res.data;
      setAuditResult(result);
      toast.success('Audit completed — operational date advanced to ' + result.newOperationalDate);
      fetchOpInfo(); // Refresh the operational date info
    } catch (err) {
      toast.error(err.response?.data?.message || err.userMessage || 'Audit failed');
    }
    setAuditing(false);
  };

  // ── SUPER_ADMIN: manual operational-date controls ──
  const handleAdvanceOpDate = async () => {
    if (!isSuperAdmin) return;
    if (!window.confirm(
      `Advance the operational date by one day?\n\n` +
      `Current: ${opInfo?.operationalDate}\n` +
      `This skips the daily audit — bookings on the current operational day ` +
      `that aren't checked-in / completed will remain in their current status.`)) return;
    setAdvanceBusy(true);
    try {
      const res = await adminService.advanceOperationalDate();
      const result = res.data.data || res.data;
      toast.success(`Operational date advanced to ${result.operationalDate}`);
      fetchOpInfo();
    } catch (err) {
      toast.error(err.response?.data?.message || err.userMessage || 'Failed to advance operational date');
    } finally {
      setAdvanceBusy(false);
    }
  };

  const handleSetOpDate = async () => {
    if (!isSuperAdmin) return;
    if (!overrideDate) { toast.error('Pick a date to override'); return; }
    if (overrideDate === opInfo?.operationalDate) {
      toast.info('Operational date is already set to that value');
      return;
    }
    if (!window.confirm(
      `Override operational date to ${overrideDate}?\n\n` +
      `Current: ${opInfo?.operationalDate}\n` +
      `This bypasses the nightly audit. Bookings on intermediate days will ` +
      `keep their current status until manually reconciled.`)) return;
    setOverrideBusy(true);
    try {
      const res = await adminService.setOperationalDate(overrideDate);
      const result = res.data.data || res.data;
      toast.success(`Operational date set to ${result.operationalDate}`);
      setOverrideDate('');
      fetchOpInfo();
    } catch (err) {
      toast.error(err.response?.data?.message || err.userMessage || 'Failed to set operational date');
    } finally {
      setOverrideBusy(false);
    }
  };

  const daysLabel = fromDate && toDate ? (() => {
    const diff = Math.round((new Date(toDate + 'T00:00:00') - new Date(fromDate + 'T00:00:00')) / 86400000);
    return diff === 0 ? '(Single day)' : diff === 1 ? '(1 day)' : `(${diff} days)`;
  })() : '';

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiBarChart2 /> Analytics</span>
          <h1>Reports</h1>
          <p>Revenue and booking analytics by date range</p>
        </div>
      </div>

      {/* Date range selector */}
      <div className="adm-form">
        <h3><FiCalendar style={{ marginRight: 6, verticalAlign: -2 }} />Select Date Range</h3>
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div className="input-group" style={{ margin: 0 }}>
            <label>From Date</label>
            <input type="date" value={fromDate} onChange={e => handleFromDateChange(e.target.value)} max={toDate || todayISO()} />
          </div>
          <div className="input-group" style={{ margin: 0 }}>
            <label>To Date</label>
            <input type="date" value={toDate} onChange={e => handleToDateChange(e.target.value)} max={todayISO()} />
          </div>
          <div className="input-group" style={{ margin: 0 }}>
            <label>Days Back</label>
            <input type="number" min="0" value={days} onChange={e => handleDaysChange(e.target.value)}
              placeholder="e.g. 5" style={{ width: '90px' }} />
          </div>
          <button className="btn btn-primary" onClick={fetchReport} disabled={loading || !fromDate || !toDate}>
            {loading ? 'Loading...' : 'Get Reports'}
          </button>
        </div>
        {daysLabel && <p className="adm-hint" style={{ marginTop: '0.5rem' }}>{daysLabel} — {fromDate} to {toDate}</p>}
      </div>

      {loading ? (
        <div className="loading"><div className="spinner"></div></div>
      ) : report ? (
        <>
          {/* Period badge */}
          <div className="adm-card" style={{ padding: '0.75rem 1.15rem', fontSize: '0.85rem', display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
            <span><strong>Period:</strong> {report.fromDate} — {report.toDate}</span>
            <button className="btn btn-secondary btn-sm" onClick={() => exportReportCSV(report, `${report.fromDate} to ${report.toDate}`)} title="Export CSV">
              <FiDownload size={14} /> CSV
            </button>
            <button className="btn btn-secondary btn-sm" onClick={() => exportReportPDF(report, `${report.fromDate} to ${report.toDate}`)} title="Export PDF">
              <FiDownload size={14} /> PDF
            </button>
          </div>

          {/* Stats cards */}
          <div className="adm-grid-3">
            <div className="adm-stat" style={{ '--stat-accent': 'var(--primary)' }}>
              <div className="adm-stat-value">{report.totalBookings ?? 0}</div>
              <div className="adm-stat-label">Total Bookings</div>
            </div>
            <div className="adm-stat" style={{ '--stat-accent': 'var(--success)' }}>
              <div className="adm-stat-value">₹{Number(report.totalRevenue ?? 0).toLocaleString()}</div>
              <div className="adm-stat-label">Actual Revenue (Collected)</div>
            </div>
            <div className="adm-stat" style={{ '--stat-accent': 'var(--warning, #f59e0b)' }}>
              <div className="adm-stat-value">₹{Number(report.estimatedRevenue ?? 0).toLocaleString()}</div>
              <div className="adm-stat-label">Estimated Revenue (All Bookings)</div>
            </div>
          </div>

          {/* Summary table */}
          <div className="adm-table-wrap">
            <div style={{ padding: '1rem 1rem 0' }}><h3>Summary</h3></div>
            <table className="adm-table">
              <thead>
                <tr>
                  <th>Metric</th>
                  <th>Value</th>
                </tr>
              </thead>
              <tbody>
                <tr><td>Period</td><td>{report.period}</td></tr>
                <tr><td>From</td><td>{report.fromDate}</td></tr>
                <tr><td>To</td><td>{report.toDate}</td></tr>
                <tr><td>Total Bookings</td><td className="highlight">{report.totalBookings}</td></tr>
                <tr><td>Actual Revenue (Collected)</td><td className="highlight success">₹{Number(report.totalRevenue ?? 0).toLocaleString()}</td></tr>
                <tr><td>Estimated Revenue (All Bookings)</td><td className="highlight warning">₹{Number(report.estimatedRevenue ?? 0).toLocaleString()}</td></tr>
              </tbody>
            </table>
          </div>
        </>
      ) : (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiBarChart2 /></span>
          <h3>No report loaded</h3>
          <p>Select a date range above and click "Get Reports" to view analytics.</p>
        </div>
      )}

      {/* Audit Section */}
      <div className="adm-card">
        <h3 style={{ marginBottom: '0.5rem' }}><FiCheckCircle style={{ marginRight: 6, verticalAlign: -2 }} />Daily Audit</h3>
        <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '1rem' }}>
          Run audit to auto-resolve the current operational day: unchecked-in → No-Show, checked-in but not finished → Completed.
          After audit, the system advances to the next business day.
        </p>

        {/* Operational date status panel */}
        {opInfo && (
          <div className="adm-op-panel" style={{ marginBottom: '1rem' }}>
            <div>
              <span className="adm-op-label">Operational Date: </span>
              <span className="adm-op-value">{opInfo.operationalDate}</span>
            </div>
            <div>
              <span className="adm-op-label">Server Time: </span>
              <span className="adm-op-value mono">
                {opInfo.serverDateTime ? opInfo.serverDateTime.substring(11, 16) : '--:--'}
              </span>
            </div>
            <div>
              <span className="adm-op-label">Audit: </span>
              {opInfo.auditAvailable ? (
                <span style={{ color: 'var(--success)', fontWeight: 700 }}><FiCheckCircle style={{ verticalAlign: -2, marginRight: 3 }} />Available now</span>
              ) : (
                <span style={{ color: 'var(--warning, #f59e0b)', fontWeight: 600 }}>
                  <FiClock style={{ verticalAlign: -2, marginRight: 3 }} />{opInfo.auditUnavailableReason}
                </span>
              )}
            </div>
          </div>
        )}

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <button
            className="btn btn-primary btn-sm"
            onClick={handleRunAudit}
            disabled={auditing || !opInfo?.auditAvailable}
            title={opInfo?.auditAvailable ? 'Run daily audit' : (opInfo?.auditUnavailableReason || 'Not available yet')}
          >
            {auditing ? 'Running Audit…' : `Run Audit for ${opInfo?.operationalDate ?? '…'}`}
          </button>
        </div>
        {opInfo && !opInfo.auditAvailable && (
          <p className="adm-hint" style={{ marginTop: '0.4rem' }}>
            <FiAlertTriangle style={{ verticalAlign: -2, marginRight: 3 }} />
            The audit button will activate after 11:59 PM server time.
          </p>
        )}

        {/* SUPER_ADMIN-only manual operational-date overrides.
            Used to recover from clock drift, push past stuck audit windows,
            and roll forward the business day in non-prod environments. */}
        {isSuperAdmin && (
          <div
            className="adm-op-override"
            style={{
              marginTop: '1.25rem',
              padding: '0.9rem 1rem',
              border: '1px dashed var(--border)',
              borderRadius: 'var(--radius-sm)',
              background: 'rgba(255, 165, 0, 0.05)'
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem' }}>
              <FiAlertTriangle style={{ color: 'var(--warning, #f59e0b)' }} />
              <strong style={{ fontSize: '0.9rem' }}>Super-admin override</strong>
              <span style={{ fontSize: '0.78rem', color: 'var(--text-secondary)' }}>
                — bypass the nightly audit gate
              </span>
            </div>
            <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', margin: '0 0 0.6rem' }}>
              Use these controls to recover from clock drift or to roll the business day forward
              in non-production environments. Bookings on intermediate days are <strong>not</strong>
              auto-resolved — the daily audit is the canonical path.
            </p>
            <div style={{ display: 'flex', gap: '0.6rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={handleAdvanceOpDate}
                disabled={advanceBusy || overrideBusy || !opInfo}
                title="Advance the operational date by one calendar day"
              >
                {advanceBusy ? 'Advancing…' : '+1 Day'}
              </button>
              <div className="input-group" style={{ margin: 0 }}>
                <label htmlFor="op-override-date" style={{ fontSize: '0.78rem' }}>Set to specific date</label>
                <input
                  id="op-override-date"
                  type="date"
                  value={overrideDate}
                  onChange={(e) => setOverrideDate(e.target.value)}
                  disabled={overrideBusy || advanceBusy}
                  style={{ minWidth: 160 }}
                />
              </div>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={handleSetOpDate}
                disabled={overrideBusy || advanceBusy || !overrideDate}
                title="Force-set the operational date (within ±90/30 days)"
              >
                {overrideBusy ? 'Setting…' : 'Override'}
              </button>
            </div>
          </div>
        )}

        {auditResult && (
          <div className="adm-audit-result" style={{ marginTop: '1rem' }}>
            <h4>Audit Results — {auditResult.auditDate}</h4>
            <div className="adm-audit-stats">
              <div><strong>{auditResult.totalProcessed}</strong> bookings processed</div>
              <div style={{ color: 'var(--danger)' }}><strong>{auditResult.markedNoShow}</strong> marked No-Show</div>
              <div style={{ color: 'var(--success)' }}><strong>{auditResult.markedCompleted}</strong> marked Completed</div>
              {auditResult.newOperationalDate && (
                <div style={{ color: 'var(--primary)' }}>
                  Operational date advanced to <strong>{auditResult.newOperationalDate}</strong>
                </div>
              )}
            </div>
            {auditResult.affectedBookingRefs?.length > 0 && (
              <div style={{ marginTop: '0.5rem', fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
                Affected: {auditResult.affectedBookingRefs.join(', ')}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
