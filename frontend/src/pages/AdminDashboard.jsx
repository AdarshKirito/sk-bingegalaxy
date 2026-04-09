import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { FiCalendar, FiDollarSign, FiUsers, FiTrendingUp, FiClock, FiClipboard, FiPlusCircle, FiSlash, FiStar, FiBarChart2 } from 'react-icons/fi';
import { SkeletonStatCard } from '../components/ui/Skeleton';
import './AdminDashboard.css';

export default function AdminDashboard() {
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [now, setNow] = useState(new Date());
  const [operationalDate, setOperationalDate] = useState(null);

  useEffect(() => {
    adminService.getDashboardStats()
      .then(res => setStats(res.data.data))
      .catch(() => setError('Failed to load dashboard stats'))
      .finally(() => setLoading(false));
    adminService.getOperationalDate()
      .then(res => {
        const d = res.data.data || res.data;
        if (d?.operationalDate) setOperationalDate(d.operationalDate);
      })
      .catch(() => {});
  }, []);

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

  return (
    <div className="container admin-dash">
      <div className="page-header">
        <h1>Admin Dashboard</h1>
        <p>SK Binge Galaxy management console</p>
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
          <div className="admin-dash-banner-link">Reports &amp; Audit →</div>
        </div>
      </Link>

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
        <Link to="/admin/reports" className="card admin-nav-card">
          <h3><FiBarChart2 style={{ verticalAlign: '-2px' }} /> Reports</h3>
          <p>Revenue and booking analytics by period</p>
        </Link>
      </div>
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
