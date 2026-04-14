import { useState, useEffect, useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { authService, bookingService } from '../services/endpoints';
import { CUSTOMER_SUPPORT, mergeSupportContact } from '../services/customerExperience';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import { toast } from 'react-toastify';
import {
  FiArrowRight,
  FiMapPin,
  FiUser,
  FiMail,
  FiPhone,
  FiSearch,
  FiClock,
  FiMessageCircle,
  FiInfo,
  FiStar,
} from 'react-icons/fi';
import './Entrance.css';

// Render star icons based on average rating
function StarRating({ avg, count }) {
  const rounded = Math.round((avg || 0) * 2) / 2;
  const stars = [];
  for (let i = 1; i <= 5; i++) {
    if (i <= rounded) {
      stars.push(<FiStar key={i} style={{ fill: 'var(--gold, #c29e46)', color: 'var(--gold, #c29e46)', width: 14, height: 14 }} />);
    } else if (i - 0.5 === rounded) {
      stars.push(<FiStar key={i} style={{ color: 'var(--gold, #c29e46)', width: 14, height: 14 }} />);
    } else {
      stars.push(<FiStar key={i} style={{ color: 'var(--border-hover, #ccc)', width: 14, height: 14 }} />);
    }
  }
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.82rem', color: 'var(--text-secondary)', marginTop: '0.35rem' }}>
      {stars}
      <strong style={{ fontSize: '0.82rem', color: 'var(--text)' }}>{avg ? avg.toFixed(1) : '—'}</strong>
      <span>({count || 0} review{count === 1 ? '' : 's'})</span>
    </span>
  );
}

const RECENT_BINGES_KEY = 'sk-recent-binges';
const MAX_RECENT = 3;

const saveRecentBinge = (binge) => {
  try {
    const stored = JSON.parse(localStorage.getItem(RECENT_BINGES_KEY) || '[]');
    const filtered = stored.filter((b) => b.id !== binge.id);
    filtered.unshift({ id: binge.id, name: binge.name, address: binge.address, ts: Date.now() });
    localStorage.setItem(RECENT_BINGES_KEY, JSON.stringify(filtered.slice(0, MAX_RECENT)));
  } catch { /* ignore */ }
};

const getRecentBinges = () => {
  try { return JSON.parse(localStorage.getItem(RECENT_BINGES_KEY) || '[]'); } catch { return []; }
};

export default function PlatformDashboard() {
  const { user } = useAuth();
  const { selectBinge, clearBinge } = useBinge();
  const navigate = useNavigate();
  const [binges, setBinges] = useState([]);
  const [reviewSummaries, setReviewSummaries] = useState({});
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [supportContact, setSupportContact] = useState(CUSTOMER_SUPPORT);

  // Clear binge on entrance so navbar shows entrance mode
  useEffect(() => {
    clearBinge();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    (async () => {
      try {
        const [bingeRes, supportRes] = await Promise.allSettled([
          bookingService.getAllActiveBinges(),
          authService.getSupportContact(),
        ]);
        const bingeList = bingeRes.status === 'fulfilled' ? (bingeRes.value.data.data || bingeRes.value.data || []) : [];
        setBinges(bingeList);
        if (supportRes.status === 'fulfilled') setSupportContact(mergeSupportContact(supportRes.value.data.data));
        // Fetch review summaries for all binges
        const summaries = {};
        await Promise.allSettled(
          bingeList.map(async (b) => {
            try {
              const r = await bookingService.getBingeReviewSummary(b.id);
              summaries[b.id] = r.data.data || r.data || {};
            } catch { summaries[b.id] = {}; }
          })
        );
        setReviewSummaries(summaries);
      } catch {
        toast.error('Failed to load venues');
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const recentBinges = useMemo(() => {
    const recent = getRecentBinges();
    const bingeIds = new Set(binges.map((b) => b.id));
    return recent.filter((r) => bingeIds.has(r.id));
  }, [binges]);

  const filteredBinges = useMemo(() => {
    if (!search.trim()) return binges;
    const q = search.trim().toLowerCase();
    return binges.filter((b) => (b.name || '').toLowerCase().includes(q) || (b.address || '').toLowerCase().includes(q));
  }, [binges, search]);

  const handleSelect = (binge) => {
    saveRecentBinge(binge);
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
      </div>

      {/* ── Recent venues ─────────────────────────────────────────── */}
      {recentBinges.length > 0 && (
        <section className="entrance-panel">
          <div className="entrance-panel-head">
            <div>
              <span className="entrance-kicker"><FiClock style={{ verticalAlign: '-2px', marginRight: 4 }} /> Recently Visited</span>
              <h2>Jump back in</h2>
            </div>
          </div>
          <div className="entrance-grid">
            {recentBinges.map((binge) => (
              <article
                key={binge.id}
                className="entrance-venue-card entrance-venue-card-recent"
                onClick={() => handleSelect(binge)}
                onKeyDown={(e) => e.key === 'Enter' && handleSelect(binge)}
                tabIndex={0}
                role="button"
                aria-label={`Select ${binge.name}`}
              >
                <span className="entrance-kicker"><FiClock /> Recent</span>
                <h3>{binge.name}</h3>
                {binge.address && <p>{binge.address}</p>}
                {reviewSummaries[binge.id]?.averageRating > 0 && (
                  <StarRating avg={reviewSummaries[binge.id].averageRating} count={reviewSummaries[binge.id].totalReviews} />
                )}
                <span className="btn btn-primary btn-sm entrance-venue-enter">
                  Enter <FiArrowRight />
                </span>
              </article>
            ))}
          </div>
        </section>
      )}

      {/* ── Venue selection ───────────────────────────────────────── */}
      <section className="entrance-panel">
        <div className="entrance-panel-head">
          <div>
            <span className="entrance-kicker">Venues</span>
            <h2>Select a venue to begin</h2>
          </div>
          <span className="entrance-inline-link">{binges.length} venue{binges.length !== 1 ? 's' : ''} available</span>
        </div>

        {binges.length > 0 && (
          <div className="entrance-search">
            <FiSearch className="entrance-search-icon" />
            <input
              type="text"
              placeholder="Search venues by name or address…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="entrance-search-input"
            />
          </div>
        )}

        {filteredBinges.length === 0 ? (
          <div className="entrance-empty">
            <span className="entrance-empty-icon"><FiMapPin /></span>
            <h3>{search.trim() ? 'No matching venues' : 'No venues available'}</h3>
            <p>{search.trim() ? 'Try a different search term.' : 'No active venues right now. Check back soon.'}</p>
          </div>
        ) : (
          <div className="entrance-grid">
            {filteredBinges.map((binge) => (
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
                {reviewSummaries[binge.id]?.averageRating > 0 && (
                  <StarRating avg={reviewSummaries[binge.id].averageRating} count={reviewSummaries[binge.id].totalReviews} />
                )}
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

      {/* ── About & Support ───────────────────────────────────────── */}
      <section className="entrance-panel entrance-about-section">
        <div className="entrance-panel-head">
          <div>
            <span className="entrance-kicker"><FiInfo style={{ verticalAlign: '-2px', marginRight: 4 }} /> About</span>
            <h2>SK Binge Galaxy</h2>
          </div>
        </div>
        <p className="entrance-about-description">
          SK Binge Galaxy is your premium private screening and celebration booking platform. Pick a venue, choose your event
          type, customize your experience, and book in minutes. Our team ensures every celebration is seamless, from
          reservation to wrap-up.
        </p>

        {(supportContact.email || supportContact.phoneRaw || supportContact.whatsappRaw) && (
          <div className="entrance-about-contact">
            <h3><FiMessageCircle style={{ verticalAlign: '-2px', marginRight: 6 }} /> Get in Touch</h3>
            <div className="entrance-about-contact-items">
              {supportContact.email && (
                <a href={`mailto:${supportContact.email}`} className="entrance-about-contact-item">
                  <FiMail /> {supportContact.email}
                </a>
              )}
              {supportContact.phoneRaw && (
                <a href={`tel:${supportContact.phoneRaw}`} className="entrance-about-contact-item">
                  <FiPhone /> {supportContact.phoneDisplay || supportContact.phoneRaw}
                </a>
              )}
              {supportContact.whatsappRaw && (
                <a href={`https://wa.me/${supportContact.whatsappRaw}`} target="_blank" rel="noreferrer" className="entrance-about-contact-item">
                  <FiMessageCircle /> WhatsApp
                </a>
              )}
            </div>
            {supportContact.hours && (
              <p className="entrance-about-hours"><FiClock style={{ verticalAlign: '-2px', marginRight: 4 }} /> Support Hours: {supportContact.hours}</p>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
