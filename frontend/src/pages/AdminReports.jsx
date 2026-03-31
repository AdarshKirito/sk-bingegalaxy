import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';

const todayISO = () => new Date().toISOString().slice(0, 10);

export default function AdminReports() {
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState(todayISO());
  const [days, setDays] = useState('');
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);

  // Operational date + audit state
  const [opInfo, setOpInfo] = useState(null);  // OperationalDateDto from backend
  const [auditResult, setAuditResult] = useState(null);
  const [auditing, setAuditing] = useState(false);

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
      .catch(err => toast.error(err.response?.data?.message || 'Failed to load report'))
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
      toast.error(err.response?.data?.message || 'Audit failed');
    }
    setAuditing(false);
  };

  const inputStyle = { padding: '0.5rem 0.8rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem' };

  const cardStyle = (accent) => ({
    flex: '1 1 220px', padding: '1.5rem', borderRadius: 'var(--radius-md, 10px)',
    background: 'var(--bg-card, #1e1e2e)', border: '1px solid var(--border)',
    borderLeft: `4px solid ${accent}`, textAlign: 'center',
  });

  const daysLabel = fromDate && toDate ? (() => {
    const diff = Math.round((new Date(toDate + 'T00:00:00') - new Date(fromDate + 'T00:00:00')) / 86400000);
    return diff === 0 ? '(Single day)' : diff === 1 ? '(1 day)' : `(${diff} days)`;
  })() : '';

  return (
    <div className="container">
      <div className="page-header">
        <h1>Reports</h1>
        <p>Revenue and booking analytics by date range</p>
      </div>

      {/* Date range selector */}
      <div className="card" style={{ marginBottom: '1.5rem', padding: '1.25rem' }}>
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div>
            <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 600, marginBottom: '0.3rem' }}>From Date</label>
            <input type="date" value={fromDate} onChange={e => handleFromDateChange(e.target.value)}
              max={toDate || todayISO()} style={inputStyle} />
          </div>
          <div>
            <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 600, marginBottom: '0.3rem' }}>To Date</label>
            <input type="date" value={toDate} onChange={e => handleToDateChange(e.target.value)}
              max={todayISO()} style={inputStyle} />
          </div>
          <div>
            <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 600, marginBottom: '0.3rem' }}>Days Back</label>
            <input type="number" min="0" value={days} onChange={e => handleDaysChange(e.target.value)}
              placeholder="e.g. 5" style={{ ...inputStyle, width: '80px' }} />
          </div>
          <button className="btn btn-primary" onClick={fetchReport} disabled={loading || !fromDate || !toDate}>
            {loading ? 'Loading...' : 'Get Reports'}
          </button>
        </div>
        {daysLabel && <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: '0.5rem' }}>{daysLabel} — {fromDate} to {toDate}</p>}
      </div>

      {loading ? (
        <div className="loading"><div className="spinner"></div></div>
      ) : report ? (
        <>
          {/* Date range */}
          <div className="card" style={{ marginBottom: '1.25rem', padding: '0.75rem 1rem', fontSize: '0.85rem' }}>
            <strong>Period:</strong> {report.fromDate} — {report.toDate}
          </div>

          {/* Stats cards */}
          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '2rem' }}>
            <div style={cardStyle('var(--primary, #6c5ce7)')}>
              <div style={{ fontSize: '2.5rem', fontWeight: 700, color: 'var(--primary)' }}>{report.totalBookings ?? 0}</div>
              <div style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
                Total Bookings
              </div>
            </div>
            <div style={cardStyle('var(--success, #00b894)')}>
              <div style={{ fontSize: '2.5rem', fontWeight: 700, color: 'var(--success)' }}>
                ₹{Number(report.totalRevenue ?? 0).toLocaleString()}
              </div>
              <div style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
                Actual Revenue (Collected)
              </div>
            </div>
            <div style={cardStyle('var(--warning, #fdcb6e)')}>
              <div style={{ fontSize: '2.5rem', fontWeight: 700, color: 'var(--warning, #fdcb6e)' }}>
                ₹{Number(report.estimatedRevenue ?? 0).toLocaleString()}
              </div>
              <div style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
                Estimated Revenue (All Bookings)
              </div>
            </div>
          </div>

          {/* Summary table */}
          <div className="card" style={{ overflowX: 'auto' }}>
            <h3 style={{ marginBottom: '1rem' }}>Summary</h3>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '2px solid var(--border)' }}>
                  <th style={{ textAlign: 'left', padding: '0.75rem', color: 'var(--text-secondary)', fontWeight: 600 }}>Metric</th>
                  <th style={{ textAlign: 'right', padding: '0.75rem', color: 'var(--text-secondary)', fontWeight: 600 }}>Value</th>
                </tr>
              </thead>
              <tbody>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '0.75rem' }}>Period</td>
                  <td style={{ padding: '0.75rem', textAlign: 'right' }}>{report.period}</td>
                </tr>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '0.75rem' }}>From</td>
                  <td style={{ padding: '0.75rem', textAlign: 'right' }}>{report.fromDate}</td>
                </tr>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '0.75rem' }}>To</td>
                  <td style={{ padding: '0.75rem', textAlign: 'right' }}>{report.toDate}</td>
                </tr>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '0.75rem' }}>Total Bookings</td>
                  <td style={{ padding: '0.75rem', textAlign: 'right', fontWeight: 600 }}>{report.totalBookings}</td>
                </tr>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '0.75rem' }}>Actual Revenue (Collected)</td>
                  <td style={{ padding: '0.75rem', textAlign: 'right', fontWeight: 700, color: 'var(--success)' }}>
                    ₹{Number(report.totalRevenue ?? 0).toLocaleString()}
                  </td>
                </tr>
                <tr>
                  <td style={{ padding: '0.75rem' }}>Estimated Revenue (All Bookings)</td>
                  <td style={{ padding: '0.75rem', textAlign: 'right', fontWeight: 700, color: 'var(--warning, #fdcb6e)' }}>
                    ₹{Number(report.estimatedRevenue ?? 0).toLocaleString()}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </>
      ) : (
        <div className="card" style={{ textAlign: 'center', padding: '2rem' }}>
          <p style={{ color: 'var(--text-muted)' }}>Select a date range and click "Get Reports"</p>
        </div>
      )}

      {/* Audit Section */}
      <div className="card" style={{ marginTop: '2rem', padding: '1.25rem' }}>
        <h3 style={{ marginBottom: '0.75rem' }}>Daily Audit</h3>
        <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '1rem' }}>
          Run audit to auto-resolve the current operational day: unchecked-in → No-Show, checked-in but not finished → Completed.
          After audit, the system advances to the next business day.
        </p>

        {/* Operational date status panel */}
        {opInfo && (
          <div style={{ marginBottom: '1rem', padding: '0.85rem 1rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', fontSize: '0.86rem' }}>
            <div style={{ display: 'flex', gap: '2rem', flexWrap: 'wrap', alignItems: 'center' }}>
              <div>
                <span style={{ color: 'var(--text-secondary)' }}>Operational Date: </span>
                <strong style={{ fontSize: '1rem', color: 'var(--primary)' }}>{opInfo.operationalDate}</strong>
              </div>
              <div>
                <span style={{ color: 'var(--text-secondary)' }}>Server Time: </span>
                <strong style={{ fontFamily: 'monospace' }}>
                  {opInfo.serverDateTime ? opInfo.serverDateTime.substring(11, 16) : '--:--'}
                </strong>
              </div>
              <div>
                <span style={{ color: 'var(--text-secondary)' }}>Audit: </span>
                {opInfo.auditAvailable ? (
                  <span style={{ color: 'var(--success, #00b894)', fontWeight: 700 }}>✓ Available now</span>
                ) : (
                  <span style={{ color: 'var(--warning, #fdcb6e)', fontWeight: 600 }}>
                    ⏳ {opInfo.auditUnavailableReason}
                  </span>
                )}
              </div>
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
          <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: '0.4rem' }}>
            The audit button will activate after 11:59 PM server time.
          </p>
        )}

        {auditResult && (
          <div style={{ marginTop: '1rem', padding: '1rem', background: 'var(--bg-input)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)' }}>
            <h4 style={{ marginBottom: '0.5rem' }}>Audit Results — {auditResult.auditDate}</h4>
            <div style={{ display: 'flex', gap: '1.5rem', flexWrap: 'wrap', fontSize: '0.88rem' }}>
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
