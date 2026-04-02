import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { FiCalendar, FiDollarSign, FiUsers, FiTrendingUp, FiClock } from 'react-icons/fi';
import './AdminDashboard.css';

export default function AdminDashboard() {
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [now, setNow] = useState(new Date());
  const [operationalDate, setOperationalDate] = useState(null);

  useEffect(() => {
    adminService.getDashboardStats()
      .then(res => setStats(res.data.data))
      .catch(() => {})
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

  if (loading) return <div className="loading"><div className="spinner"></div></div>;

  const dateStr = now.toLocaleDateString('en-IN', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
  const timeStr = now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });

  return (
    <div className="container admin-dash">
      <div className="page-header">
        <h1>Admin Dashboard</h1>
        <p>SK Binge Galaxy management console</p>
      </div>

      {/* Live Date & Time + Operational Date */}
      <Link to="/admin/reports" style={{ textDecoration: 'none' }}>
        <div className="card" style={{ display: 'flex', alignItems: 'center', gap: '1rem', padding: '1rem 1.5rem', marginBottom: '1.5rem', cursor: 'pointer', border: '1px solid var(--primary)', background: 'var(--primary-transparent, rgba(108,92,231,0.06))' }}>
          <FiClock size={28} color="var(--primary)" />
          <div>
            <div style={{ fontSize: '1.3rem', fontWeight: 700, fontFamily: 'monospace', color: 'var(--text)' }}>{timeStr}</div>
            <div style={{ fontSize: '0.88rem', color: 'var(--text-secondary)' }}>{dateStr}</div>
          </div>
          {operationalDate && (
            <div style={{ marginLeft: '1.5rem', paddingLeft: '1.5rem', borderLeft: '1px solid var(--border)' }}>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Operational Day</div>
              <div style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--primary)' }}>{operationalDate}</div>
            </div>
          )}
          <div style={{ marginLeft: 'auto', fontSize: '0.8rem', color: 'var(--primary)', fontWeight: 600 }}>
            Reports & Audit →
          </div>
        </div>
      </Link>

      <div className="grid-4 stat-cards">
        <StatCard icon={<FiCalendar />} label="Today's Bookings" value={stats?.totalBookings || 0} color="var(--primary)" to="/admin/bookings?tab=today&sub=all" />
        <StatCard icon={<FiUsers />} label="Pending" value={stats?.pendingBookings || 0} color="var(--warning)" to="/admin/bookings?tab=byStatus&status=PENDING" />
        <StatCard icon={<FiTrendingUp />} label="Confirmed" value={stats?.confirmedBookings || 0} color="var(--success)" to="/admin/bookings?tab=byStatus&status=CONFIRMED" />
        <StatCard icon={<FiDollarSign />} label="Today's Revenue" value={`₹${(stats?.todayRevenue ?? 0).toLocaleString()}`} color="var(--secondary)" to="/admin/reports" />
      </div>

      <div className="grid-4 stat-cards" style={{ marginTop: '0.75rem' }}>
        <StatCard icon={<FiTrendingUp />} label="Estimated Today's Revenue" value={`₹${(stats?.todayEstimatedRevenue ?? 0).toLocaleString()}`} color="var(--primary)" to="/admin/reports" />
        <StatCard icon={<FiCalendar />} label="Checked In" value={stats?.todayCheckedIn || 0} color="var(--success)" to="/admin/bookings?tab=byStatus&status=CHECKED_IN" />
        <StatCard icon={<FiCalendar />} label="Completed Today" value={stats?.todayCompleted || 0} color="var(--info, #0984e3)" to="/admin/bookings?tab=byStatus&status=COMPLETED" />
        <StatCard icon={<FiDollarSign />} label="Cancelled Today" value={stats?.todayCancelled || 0} color="var(--danger)" to="/admin/bookings?tab=byStatus&status=CANCELLED" />
      </div>

      <div className="grid-3" style={{ marginTop: '1.5rem' }}>
        <Link to="/admin/bookings" className="card admin-nav-card">
          <h3>📋 Manage Bookings</h3>
          <p>View, update, and manage all reservations</p>
        </Link>
        <Link to="/admin/book" className="card admin-nav-card">
          <h3>➕ Book Now (Walk-In)</h3>
          <p>Create a booking for walk-in or phone customers</p>
        </Link>
        <Link to="/admin/blocked-dates" className="card admin-nav-card">
          <h3>🚫 Block Dates</h3>
          <p>Manage date and slot availability</p>
        </Link>
        <Link to="/admin/event-types" className="card admin-nav-card">
          <h3>🎭 Event Types & Add-Ons</h3>
          <p>Manage packages and pricing</p>
        </Link>
        <Link to="/admin/reports" className="card admin-nav-card">
          <h3>📊 Reports</h3>
          <p>Revenue and booking analytics by period</p>
        </Link>
      </div>
    </div>
  );
}

function StatCard({ icon, label, value, color, to }) {
  const content = (
    <div className="card stat-card" style={to ? { cursor: 'pointer', transition: 'transform 0.15s, box-shadow 0.15s' }
      : undefined}
      onMouseEnter={e => { if (to) { e.currentTarget.style.transform = 'translateY(-2px)'; e.currentTarget.style.boxShadow = '0 4px 16px rgba(0,0,0,0.18)'; } }}
      onMouseLeave={e => { if (to) { e.currentTarget.style.transform = ''; e.currentTarget.style.boxShadow = ''; } }}>
      <div className="stat-icon" style={{ color }}>{icon}</div>
      <div>
        <p className="stat-value">{value}</p>
        <p className="stat-label">{label}</p>
      </div>
      {to && <div style={{ marginLeft: 'auto', fontSize: '0.75rem', color, opacity: 0.7, alignSelf: 'center' }}>→</div>}
    </div>
  );
  return to ? <Link to={to} style={{ textDecoration: 'none', display: 'contents' }}>{content}</Link> : content;
}
