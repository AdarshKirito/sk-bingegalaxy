import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { adminService } from '../services/endpoints';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import { toast } from 'react-toastify';
import {
  FiArrowRight,
  FiMapPin,
  FiUser,
  FiMail,
  FiShield,
  FiUsers,
  FiActivity,
  FiBarChart2,
  FiSettings,
} from 'react-icons/fi';
import './Entrance.css';

export default function AdminEntranceDashboard() {
  const { user, isSuperAdmin } = useAuth();
  const { selectBinge, clearBinge } = useBinge();
  const navigate = useNavigate();
  const [binges, setBinges] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    clearBinge();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    (async () => {
      try {
        const res = await adminService.getAdminBinges();
        setBinges(res.data.data || res.data || []);
      } catch {
        toast.error('Failed to load binges');
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const handleSelect = (binge) => {
    selectBinge({ id: binge.id, name: binge.name, address: binge.address });
    toast.success(`Entered: ${binge.name}`);
    navigate('/admin/dashboard');
  };

  const admin = user || {};
  const displayName = [admin.firstName, admin.lastName].filter(Boolean).join(' ') || 'Admin';
  const roleLabel = isSuperAdmin ? 'Super Admin' : 'Admin';
  const activeCount = binges.filter((b) => b.active).length;
  const inactiveCount = binges.length - activeCount;

  if (loading) {
    return (
      <div className="container entrance">
        <SEO title="Admin Dashboard" description="SK Binge Galaxy admin overview." />
        <SkeletonGrid count={4} columns={2} />
      </div>
    );
  }

  return (
    <div className="container entrance">
      <SEO title="Admin Dashboard" description="SK Binge Galaxy admin overview." />

      {/* ── Hero ──────────────────────────────────────────────────── */}
      <section className="entrance-hero">
        <div className="entrance-welcome">
          <span className="entrance-kicker"><FiShield style={{ verticalAlign: '-2px', marginRight: 4 }} /> {roleLabel} Console</span>
          <h1>Welcome, {admin.firstName || 'Admin'}!</h1>
          <p>
            This is your admin control centre. Select a venue below to manage its bookings,
            catalog, and operations — or use the quick links to manage your account and platform settings.
          </p>
          <div className="entrance-badges">
            <span className="entrance-badge">{binges.length} total venue{binges.length !== 1 ? 's' : ''}</span>
            <span className="entrance-badge entrance-badge-success">{activeCount} active</span>
            {inactiveCount > 0 && <span className="entrance-badge entrance-badge-warning">{inactiveCount} inactive</span>}
          </div>
        </div>

        <aside className="entrance-profile">
          <span className="entrance-kicker">Your Profile</span>
          <h2>{displayName}</h2>
          <div className="entrance-profile-list">
            <div>
              <span><FiMail style={{ marginRight: '0.3rem', verticalAlign: 'middle' }} /> Email</span>
              <strong>{admin.email || 'Not available'}</strong>
            </div>
            <div>
              <span><FiShield style={{ marginRight: '0.3rem', verticalAlign: 'middle' }} /> Role</span>
              <strong>{roleLabel}</strong>
            </div>
          </div>
          <div className="entrance-profile-inline-actions">
            <Link to="/admin/account" className="btn btn-primary btn-sm">Edit Profile</Link>
          </div>
        </aside>
      </section>

      {/* ── Quick stats ───────────────────────────────────────────── */}
      <div className="entrance-stats">
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiMapPin /></span>
          <strong>{binges.length}</strong>
          <p>Total Venues</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiActivity /></span>
          <strong>{activeCount}</strong>
          <p>Active Venues</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiBarChart2 /></span>
          <strong>—</strong>
          <p>Select a venue for revenue</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiSettings /></span>
          <strong>{inactiveCount}</strong>
          <p>Inactive Venues</p>
        </div>
      </div>

      {/* ── Venue selection ───────────────────────────────────────── */}
      <section className="entrance-panel">
        <div className="entrance-panel-head">
          <div>
            <span className="entrance-kicker">Venues</span>
            <h2>Enter a venue to manage</h2>
          </div>
          <Link to="/admin/binges" className="entrance-inline-link">Manage venues →</Link>
        </div>

        {binges.length === 0 ? (
          <div className="entrance-empty">
            <span className="entrance-empty-icon"><FiMapPin /></span>
            <h3>No venues yet</h3>
            <p>Create your first venue from the Binges management page.</p>
            <Link to="/admin/binges" className="btn btn-primary btn-sm">Create Venue</Link>
          </div>
        ) : (
          <div className="entrance-grid">
            {binges.filter((b) => b.active).map((binge) => (
              <article
                key={binge.id}
                className="entrance-venue-card"
                onClick={() => handleSelect(binge)}
                onKeyDown={(e) => e.key === 'Enter' && handleSelect(binge)}
                tabIndex={0}
                role="button"
                aria-label={`Enter ${binge.name}`}
              >
                <span className="entrance-kicker"><FiMapPin /> Venue</span>
                <h3>{binge.name}</h3>
                {binge.address && <p>{binge.address}</p>}
                <span className="btn btn-primary btn-sm entrance-venue-enter">
                  Enter <FiArrowRight />
                </span>
              </article>
            ))}
          </div>
        )}
      </section>

      {/* ── Quick navigation ──────────────────────────────────────── */}
      <section className="entrance-panel">
        <div className="entrance-panel-head">
          <div>
            <span className="entrance-kicker">Quick Links</span>
            <h2>Platform Tools</h2>
          </div>
        </div>
        <div className="entrance-nav-grid">
          <Link to="/admin/binges" className="entrance-nav-card">
            <h3><FiMapPin /> Manage Binges</h3>
            <p>Create, edit, and toggle venues</p>
          </Link>
          <Link to="/admin/account" className="entrance-nav-card">
            <h3><FiUser /> Account Settings</h3>
            <p>Update your profile and credentials</p>
          </Link>
          {isSuperAdmin && (
            <Link to="/admin/all-users" className="entrance-nav-card">
              <h3><FiUsers /> All Users</h3>
              <p>Manage admins and customers across the platform</p>
            </Link>
          )}
          {isSuperAdmin && (
            <Link to="/admin/register" className="entrance-nav-card">
              <h3><FiShield /> Add Admin</h3>
              <p>Register a new admin account</p>
            </Link>
          )}
        </div>
      </section>
    </div>
  );
}
