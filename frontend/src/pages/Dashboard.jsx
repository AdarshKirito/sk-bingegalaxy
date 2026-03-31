import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { bookingService } from '../services/endpoints';
import { FiCalendar, FiClock, FiArrowRight } from 'react-icons/fi';
import './Dashboard.css';

export default function Dashboard() {
  const { user } = useAuth();
  const [currentBookings, setCurrentBookings] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    bookingService.getCurrentBookings()
      .then(res => setCurrentBookings(res.data.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="container dashboard">
      <div className="page-header">
        <h1>Hello, {user.firstName}! 👋</h1>
        <p>Welcome to your SK Binge Galaxy dashboard</p>
      </div>

      <div className="grid-3 dash-actions">
        <Link to="/book" className="card dash-action-card">
          <FiCalendar className="dash-icon" />
          <h3>Book Now</h3>
          <p>Reserve your private theater</p>
          <FiArrowRight className="dash-arrow" />
        </Link>
        <Link to="/my-bookings" className="card dash-action-card">
          <FiClock className="dash-icon" />
          <h3>My Bookings</h3>
          <p>View all your reservations</p>
          <FiArrowRight className="dash-arrow" />
        </Link>
        <Link to="/my-bookings" className="card dash-action-card">
          <span className="dash-icon-text">📋</span>
          <h3>Booking History</h3>
          <p>Past events and receipts</p>
          <FiArrowRight className="dash-arrow" />
        </Link>
      </div>

      <section className="dash-upcoming">
        <h2>Upcoming Bookings</h2>
        {loading ? (
          <div className="loading"><div className="spinner"></div></div>
        ) : currentBookings.length === 0 ? (
          <div className="card" style={{ textAlign: 'center', padding: '3rem' }}>
            <p style={{ color: 'var(--text-muted)', marginBottom: '1rem' }}>No upcoming bookings</p>
            <Link to="/book" className="btn btn-primary btn-sm">Make a Booking</Link>
          </div>
        ) : (
          <div className="grid-2">
            {currentBookings.slice(0, 4).map(b => (
              <Link to={`/booking/${b.bookingRef}`} key={b.bookingRef} className="card booking-preview-card">
                <div className="bpc-header">
                  <span className="badge badge-info">{b.status}</span>
                  <span className="bpc-ref">{b.bookingRef}</span>
                </div>
                <h4>{b.eventType?.name ?? b.eventType}</h4>
                <p>{b.bookingDate} at {b.startTime} • {b.durationHours}h</p>
                <p className="bpc-amount">₹{b.totalAmount?.toLocaleString()}</p>
              </Link>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
