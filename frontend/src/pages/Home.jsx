import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { FiArrowRight, FiCalendar, FiFilm, FiStar, FiShield, FiHeart, FiGift, FiCamera, FiBriefcase, FiSmile } from 'react-icons/fi';
import SEO from '../components/SEO';
import './Home.css';

export default function Home() {
  const { isAuthenticated, isAdmin } = useAuth();

  return (
    <div className="home">
      <SEO title="Home" description="Book an exclusive private theater for birthdays, anniversaries, proposals, and more with SK Binge Galaxy." />
      <section className="hero">
        <div className="container hero-content">
          <h1>Your Private <span className="highlight">Theater</span> Experience</h1>
          <p>Book an exclusive private theater for birthdays, anniversaries, proposals, movie screenings, and more. Create unforgettable memories with SK Binge Galaxy.</p>
          <div className="hero-actions">
            {isAuthenticated ? (
              isAdmin ? (
                <Link to="/admin/dashboard" className="btn btn-primary">
                  Admin Dashboard <FiArrowRight />
                </Link>
              ) : (
                <Link to="/book" className="btn btn-primary">
                  Book Now <FiArrowRight />
                </Link>
              )
            ) : (
              <>
                <Link to="/register" className="btn btn-primary">
                  Get Started <FiArrowRight />
                </Link>
                <Link to="/login" className="btn btn-secondary">
                  Sign In
                </Link>
              </>
            )}
          </div>
        </div>
      </section>

      <section className="features container">
        <h2>Why Choose Us</h2>
        <p className="section-subtitle">Everything you need for an unforgettable experience</p>
        <div className="grid-4 features-grid">
          <div className="feature-card">
            <div className="feature-icon"><FiFilm /></div>
            <h3>Private Screening</h3>
            <p>Enjoy a fully private theater experience with your loved ones.</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon"><FiCalendar /></div>
            <h3>Easy Booking</h3>
            <p>Choose your date, time, event type, and add-ons in minutes.</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon"><FiStar /></div>
            <h3>Custom Events</h3>
            <p>Birthday parties, proposals, corporate events, and more.</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon"><FiShield /></div>
            <h3>Secure Payments</h3>
            <p>Safe and secure payment processing with multiple options.</p>
          </div>
        </div>
      </section>

      <section className="pricing container">
        <h2>Event Types</h2>
        <p className="section-subtitle">From intimate celebrations to corporate events</p>
        <div className="grid-3" style={{ marginTop: '1.5rem' }}>
          {[
            { name: 'Birthday Party', price: '₹4,999', icon: <FiGift /> },
            { name: 'Anniversary', price: '₹5,999', icon: <FiHeart /> },
            { name: 'Proposal Setup', price: '₹7,999', icon: <FiStar /> },
            { name: 'HD Screening', price: '₹2,999', icon: <FiFilm /> },
            { name: 'Corporate Event', price: '₹9,999', icon: <FiBriefcase /> },
            { name: 'Baby Shower', price: '₹5,499', icon: <FiSmile /> },
          ].map((evt) => (
            <div key={evt.name} className="card pricing-card">
              <span className="pricing-icon">{evt.icon}</span>
              <h3>{evt.name}</h3>
              <p className="pricing-amount">Starting at {evt.price}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
