import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import { FiCalendar, FiDollarSign, FiUsers, FiTrendingUp, FiClock, FiClipboard, FiPlusCircle, FiSlash, FiStar, FiBarChart2, FiAlertTriangle, FiRefreshCw, FiBell, FiActivity, FiWifi, FiWifiOff } from 'react-icons/fi';
import { SkeletonStatCard } from '../components/ui/Skeleton';
import useRealtimeUpdates from '../hooks/useRealtimeUpdates';
import './AdminDashboard.css';

export default function AdminDashboard() {
  const { isSuperAdmin } = useAuth();
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [now, setNow] = useState(new Date());
  const [operationalDate, setOperationalDate] = useState(null);
  const [opDateError, setOpDateError] = useState(false);
  const [auditAvailable, setAuditAvailable] = useState(false);
  const [runningAudit, setRunningAudit] = useState(false);
  const [retryingNotifications, setRetryingNotifications] = useState(false);
  const [failedSagas, setFailedSagas] = useState([]);
  const [compensatingSagas, setCompensatingSagas] = useState([]);

  const refreshStats = useCallback(() => {
    adminService.getDashboardStats()
      .then(res => setStats(res.data.data))
      .catch(() => {});
  }, []);

  // Real-time updates: auto-refresh stats when a booking/payment event arrives
  const { connected } = useRealtimeUpdates({
    onEvent: (event) => {
      toast.info(`Live: ${event?.type?.replace('.', ' ') || 'update'} — ${event?.ref || ''}`, { autoClose: 4000 });
      refreshStats();
    },
  });

  const fetchOpDate = () => {
    setOpDateError(false);
    adminService.getOperationalDate()
      .then(res => {
        const d = res.data.data || res.data;
        if (d?.operationalDate) setOperationalDate(d.operationalDate);
        if (d?.auditAvailable) setAuditAvailable(true);
        else setAuditAvailable(false);
      })
      .catch(() => setOpDateError(true));
  };

  useEffect(() => {
    adminService.getDashboardStats()
      .then(res => setStats(res.data.data))
      .catch(() => setError('Failed to load dashboard stats'))
      .finally(() => setLoading(false));
    fetchOpDate();
    if (isSuperAdmin) {
      adminService.getFailedSagas().then(res => setFailedSagas(res.data.data || res.data || [])).catch(() => {});
      adminService.getCompensatingSagas().then(res => setCompensatingSagas(res.data.data || res.data || [])).catch(() => {});
    }
  }, [isSuperAdmin]);

  // Live clock
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  if (loading) return (
    <div className="container admin-dash">
      <div className="page-header"><h1>Admin Dashboard</h1><p>SK Binge Galaxy management console</p></div>
      <div className="grid-4 stat-cards">
        {Array.from({ length: 4 }).map((_, i) => <SkeletonStatCard key={i} />)}
      </div>
      <div className="grid-4 stat-cards" style={{ marginTop: '0.75rem' }}>
        {Array.from({ length: 4 }).map((_, i) => <SkeletonStatCard key={i} />)}
      </div>
    </div>
  );

  if (error) return (
    <div className="container admin-dash">
      <div className="page-header"><h1>Admin Dashboard</h1><p>SK Binge Galaxy management console</p></div>
      <p style={{ color: 'var(--danger, #ef4444)', textAlign: 'center', marginTop: '2rem' }}>{error}</p>
      <button className="btn btn-primary" style={{ display: 'block', margin: '1rem auto' }} onClick={() => window.location.reload()}>Retry</button>
    </div>
  );

  const dateStr = now.toLocaleDateString('en-IN', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
  const timeStr = now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });

  const handleRunAudit = async () => {
    if (!confirm('Run end-of-day audit? This will resolve no-shows and finalize today\'s bookings.')) return;
    setRunningAudit(true);
    try {
      await adminService.runAudit();
      toast.success('Audit completed — operational date advanced');
      fetchOpDate();
      window.location.reload();
    } catch (err) { toast.error(err.response?.data?.message || err.userMessage || 'Audit failed'); }
    setRunningAudit(false);
  };

  const handleRetryNotifications = async () => {
    setRetryingNotifications(true);
    try {
      await adminService.retryFailedNotifications();
      toast.success('Failed notifications retried');
    } catch (err) { toast.error(err.response?.data?.message || err.userMessage || 'Retry failed'); }
    setRetryingNotifications(false);
  };

  return (
    <div className="container admin-dash">
      <div className="page-header">
        <h1>Admin Dashboard</h1>
        <p>SK Binge Galaxy management console</p>
        <span className={`live-badge ${connected ? 'live-badge-on' : 'live-badge-off'}`} title={connected ? 'Live updates active' : 'Live updates disconnected'}>
          {connected ? <FiWifi size={14} /> : <FiWifiOff size={14} />}
          {connected ? ' Live' : ' Offline'}
        </span>
      </div>

      {/* Live Date & Time + Operational Date */}
      <Link to="/admin/reports" className="admin-dash-banner">
        <div className="card admin-dash-banner-inner">
          <FiClock size={28} className="admin-dash-banner-icon" />
          <div>
            <div className="admin-dash-banner-time">{timeStr}</div>
            <div className="admin-dash-banner-date">{dateStr}</div>
          </div>
          {operationalDate && (
            <div className="admin-dash-banner-meta">
              <div className="admin-dash-banner-label">Operational Day</div>
              <div className="admin-dash-banner-value">{operationalDate}</div>
            </div>
          )}
          {opDateError && !operationalDate && (
            <div className="admin-dash-banner-meta" style={{ color: 'var(--warning, orange)' }}>
              <div className="admin-dash-banner-label">Operational Day</div>
              <div className="admin-dash-banner-value" style={{ fontSize: '0.85rem' }}>
                <FiAlertTriangle style={{ verticalAlign: '-2px', marginRight: 4 }} />
                Failed to load
              </div>
            </div>
          )}
          <div className="admin-dash-banner-link">Reports &amp; Audit →</div>
        </div>
      </Link>

      {/* Audit + Operations Row */}
      <div style={{ display: 'flex', gap: '0.75rem', marginTop: '0.75rem', flexWrap: 'wrap', alignItems: 'center' }}>
        {opDateError && (
          <button className="btn btn-secondary btn-sm" onClick={fetchOpDate} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
            <FiRefreshCw size={14} /> Retry Operational Date
          </button>
        )}
        {auditAvailable && (
          <button className="btn btn-primary btn-sm" onClick={handleRunAudit} disabled={runningAudit} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
            <FiActivity size={14} /> {runningAudit ? 'Running Audit...' : 'Run End-of-Day Audit'}
          </button>
        )}
        <button className="btn btn-secondary btn-sm" onClick={handleRetryNotifications} disabled={retryingNotifications} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
          <FiBell size={14} /> {retryingNotifications ? 'Retrying...' : 'Retry Failed Notifications'}
        </button>
      </div>

      <div className="grid-4 stat-cards">
        <StatCard icon={<FiCalendar />} label="Today's Bookings" value={stats?.todayTotal || 0} color="var(--primary)" to="/admin/bookings?tab=today&sub=all" />
        <StatCard icon={<FiUsers />} label="Pending" value={stats?.todayPending || 0} color="var(--warning)" to="/admin/bookings?tab=today&sub=pending" />
        <StatCard icon={<FiTrendingUp />} label="Confirmed" value={stats?.todayConfirmed || 0} color="var(--success)" to="/admin/bookings?tab=today&sub=confirmed" />
        <StatCard icon={<FiDollarSign />} label="Today's Revenue" value={`₹${(stats?.todayRevenue ?? 0).toLocaleString()}`} color="var(--secondary)" to="/admin/reports" />
      </div>

      <div className="grid-4 stat-cards" style={{ marginTop: '0.75rem' }}>
        <StatCard icon={<FiTrendingUp />} label="Estimated Today's Revenue" value={`₹${(stats?.todayEstimatedRevenue ?? 0).toLocaleString()}`} color="var(--primary)" to="/admin/reports" />
        <StatCard icon={<FiCalendar />} label="Checked In" value={stats?.todayCheckedIn || 0} color="var(--success)" to="/admin/bookings?tab=today&sub=checkedIn" />
        <StatCard icon={<FiCalendar />} label="Completed Today" value={stats?.todayCompleted || 0} color="var(--info, #0984e3)" to="/admin/bookings?tab=today&sub=completed" />
        <StatCard icon={<FiDollarSign />} label="Cancelled Today" value={stats?.todayCancelled || 0} color="var(--danger)" to="/admin/bookings?tab=today&sub=cancelled" />
      </div>

      <div className="grid-3" style={{ marginTop: '1.5rem' }}>
        <Link to="/admin/bookings" className="card admin-nav-card">
          <h3><FiClipboard style={{ verticalAlign: '-2px' }} /> Manage Bookings</h3>
          <p>View, update, and manage all reservations</p>
        </Link>
        <Link to="/admin/book" className="card admin-nav-card">
          <h3><FiPlusCircle style={{ verticalAlign: '-2px' }} /> Book Now (Walk-In)</h3>
          <p>Create a booking for walk-in or phone customers</p>
        </Link>
        <Link to="/admin/blocked-dates" className="card admin-nav-card">
          <h3><FiSlash style={{ verticalAlign: '-2px' }} /> Block Dates</h3>
          <p>Manage date and slot availability</p>
        </Link>
        <Link to="/admin/event-types" className="card admin-nav-card">
          <h3><FiStar style={{ verticalAlign: '-2px' }} /> Event Types & Add-Ons</h3>
          <p>Manage packages and pricing</p>
        </Link>
        <Link to="/admin/rate-codes" className="card admin-nav-card">
          <h3><FiStar style={{ verticalAlign: '-2px' }} /> Rate Codes</h3>
          <p>Edit pricing tiers and rate codes</p>
        </Link>
        <Link to="/admin/customer-pricing" className="card admin-nav-card">
          <h3><FiStar style={{ verticalAlign: '-2px' }} /> Customer Pricing</h3>
          <p>Assign custom pricing profiles</p>
        </Link>
        <Link to="/admin/reports" className="card admin-nav-card">
          <h3><FiBarChart2 style={{ verticalAlign: '-2px' }} /> Reports</h3>
          <p>Revenue and booking analytics by period</p>
        </Link>
      </div>

      {/* Super Admin: Saga Monitoring */}
      {isSuperAdmin && (failedSagas.length > 0 || compensatingSagas.length > 0) && (
        <div style={{ marginTop: '1.5rem' }}>
          <h2 style={{ fontSize: '1.1rem', marginBottom: '0.75rem' }}>
            <FiAlertTriangle style={{ verticalAlign: '-2px', marginRight: 6, color: 'var(--warning)' }} />
            System Health
          </h2>
          <div className="grid-4 stat-cards">
            {failedSagas.length > 0 && (
              <div className="card stat-card" style={{ borderLeft: '3px solid var(--danger, #e74c3c)' }}>
                <div className="stat-icon" style={{ color: 'var(--danger)' }}><FiAlertTriangle /></div>
                <div>
                  <p className="stat-value">{failedSagas.length}</p>
                  <p className="stat-label">Failed Sagas</p>
                </div>
              </div>
            )}
            {compensatingSagas.length > 0 && (
              <div className="card stat-card" style={{ borderLeft: '3px solid var(--warning, orange)' }}>
                <div className="stat-icon" style={{ color: 'var(--warning)' }}><FiRefreshCw /></div>
                <div>
                  <p className="stat-value">{compensatingSagas.length}</p>
                  <p className="stat-label">Compensating Sagas</p>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function StatCard({ icon, label, value, color, to }) {
  const content = (
    <div className="card stat-card">
      <div className="stat-icon" style={{ color }}>{icon}</div>
      <div>
        <p className="stat-value">{value}</p>
        <p className="stat-label">{label}</p>
      </div>
      {to && <div className="stat-card-arrow" style={{ color }}>→</div>}
    </div>
  );
  return to ? <Link to={to} className="stat-card-link">{content}</Link> : content;
}
