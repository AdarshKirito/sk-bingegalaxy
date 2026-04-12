import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { bookingService, paymentService } from '../services/endpoints';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import { toast } from 'react-toastify';
import {
  FiArrowRight,
  FiMapPin,
  FiUser,
  FiMail,
  FiPhone,
  FiCreditCard,
  FiCalendar,
  FiDollarSign,
} from 'react-icons/fi';
import './Entrance.css';

export default function PlatformDashboard() {
  const { user } = useAuth();
  const { selectBinge, clearBinge } = useBinge();
  const navigate = useNavigate();
  const [binges, setBinges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [totalSpent, setTotalSpent] = useState(null);
  const [totalBookings, setTotalBookings] = useState(null);

  // Clear binge on entrance so navbar shows entrance mode
  useEffect(() => {
    clearBinge();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    (async () => {
      try {
        const res = await bookingService.getAllActiveBinges();
        setBinges(res.data.data || res.data || []);
      } catch {
        toast.error('Failed to load venues');
      } finally {
        setLoading(false);
      }
    })();
    // Fetch aggregate spending — this may fail without binge, handle gracefully
    paymentService.getMyPayments()
      .then((res) => {
        const payments = res.data.data || res.data || [];
        const total = payments
          .filter((p) => p.status === 'COMPLETED' || p.status === 'SUCCESS')
          .reduce((sum, p) => sum + (p.amount || 0), 0);
        setTotalSpent(total);
        setTotalBookings(payments.length);
      })
      .catch(() => { /* no aggregate data available */ });
  }, []);

  const handleSelect = (binge) => {
    selectBinge({ id: binge.id, name: binge.name, address: binge.address });
    toast.success(`Selected: ${binge.name}`);
    navigate('/dashboard');
  };

  const customer = user || {};
  const displayName = [customer.firstName, customer.lastName].filter(Boolean).join(' ') || 'Customer';

  if (loading) {
    return (
      <div className="container entrance">
        <SEO title="Dashboard" description="Your personal hub on SK Binge Galaxy." />
        <SkeletonGrid count={4} columns={2} />
      </div>
    );
  }

  return (
    <div className="container entrance">
      <SEO title="Dashboard" description="Your personal hub on SK Binge Galaxy." />

      {/* ── Hero ──────────────────────────────────────────────────── */}
      <section className="entrance-hero">
        <div className="entrance-welcome">
          <span className="entrance-kicker">Welcome back</span>
          <h1>Hello, {customer.firstName || 'there'}!</h1>
          <p>
            This is your personal dashboard on SK Binge Galaxy. Choose a venue below
            to start planning your next private screening experience.
          </p>
          <div className="entrance-welcome-actions">
            <Link to="/account" className="btn btn-secondary">My Account</Link>
          </div>
        </div>

        <aside className="entrance-profile">
          <span className="entrance-kicker">Your Profile</span>
          <h2>{displayName}</h2>
          <div className="entrance-profile-list">
            <div>
              <span><FiMail style={{ marginRight: '0.3rem', verticalAlign: 'middle' }} /> Email</span>
              <strong>{customer.email || 'Not available'}</strong>
            </div>
            <div>
              <span><FiPhone style={{ marginRight: '0.3rem', verticalAlign: 'middle' }} /> Phone</span>
              <strong>{customer.phone || 'Not set'}</strong>
            </div>
          </div>
          <div className="entrance-profile-inline-actions">
            <Link to="/account" className="btn btn-primary btn-sm">Edit Profile</Link>
          </div>
        </aside>
      </section>

      {/* ── Quick stats ───────────────────────────────────────────── */}
      <div className="entrance-stats">
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiMapPin /></span>
          <strong>{binges.length}</strong>
          <p>Available Venues</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiDollarSign /></span>
          <strong>{totalSpent != null ? `₹${totalSpent.toLocaleString()}` : '—'}</strong>
          <p>Total Spent</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiCreditCard /></span>
          <strong>{totalBookings != null ? totalBookings : '—'}</strong>
          <p>Total Payments</p>
        </div>
        <div className="entrance-stat-card">
          <span className="entrance-stat-icon"><FiCalendar /></span>
          <strong>—</strong>
          <p>Select a venue for bookings</p>
        </div>
      </div>

      {/* ── Venue selection ───────────────────────────────────────── */}
      <section className="entrance-panel">
        <div className="entrance-panel-head">
          <div>
            <span className="entrance-kicker">Venues</span>
            <h2>Select a venue to begin</h2>
          </div>
          <span className="entrance-inline-link">{binges.length} venue{binges.length !== 1 ? 's' : ''} available</span>
        </div>

        {binges.length === 0 ? (
          <div className="entrance-empty">
            <span className="entrance-empty-icon"><FiMapPin /></span>
            <h3>No venues available</h3>
            <p>No active venues right now. Check back soon.</p>
          </div>
        ) : (
          <div className="entrance-grid">
            {binges.map((binge) => (
              <article
                key={binge.id}
                className="entrance-venue-card"
                onClick={() => handleSelect(binge)}
                onKeyDown={(e) => e.key === 'Enter' && handleSelect(binge)}
                tabIndex={0}
                role="button"
                aria-label={`Select ${binge.name}`}
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
            <h2>Explore</h2>
          </div>
        </div>
        <div className="entrance-nav-grid">
          <Link to="/binges" className="entrance-nav-card">
            <h3><FiMapPin /> Browse Venues</h3>
            <p>View all available venues and select one</p>
          </Link>
          <Link to="/account" className="entrance-nav-card">
            <h3><FiUser /> Account Settings</h3>
            <p>Update your profile, password, and preferences</p>
          </Link>
        </div>
      </section>
    </div>
  );
}
