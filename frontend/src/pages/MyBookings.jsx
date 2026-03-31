import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { bookingService } from '../services/endpoints';

export default function MyBookings() {
  const [tab, setTab] = useState('current');
  const [bookings, setBookings] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    const fetcher = tab === 'current' ? bookingService.getCurrentBookings() : bookingService.getPastBookings();
    fetcher
      .then(res => setBookings(res.data.data || []))
      .catch(() => setBookings([]))
      .finally(() => setLoading(false));
  }, [tab]);

  const statusBadge = (s) => ({
    PENDING: 'badge-warning', CONFIRMED: 'badge-success', CANCELLED: 'badge-danger',
    COMPLETED: 'badge-info', CHECKED_IN: 'badge-success', NO_SHOW: 'badge-danger',
  }[s] || 'badge-info');

  return (
    <div className="container">
      <div className="page-header">
        <h1>My Bookings</h1>
      </div>

      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1.5rem' }}>
        <button className={`btn ${tab === 'current' ? 'btn-primary' : 'btn-secondary'} btn-sm`}
          onClick={() => setTab('current')}>Upcoming</button>
        <button className={`btn ${tab === 'past' ? 'btn-primary' : 'btn-secondary'} btn-sm`}
          onClick={() => setTab('past')}>Past</button>
      </div>

      {loading ? (
        <div className="loading"><div className="spinner"></div></div>
      ) : bookings.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '3rem' }}>
          <p style={{ color: 'var(--text-muted)', marginBottom: '1rem' }}>No {tab} bookings found</p>
          {tab === 'current' && <Link to="/book" className="btn btn-primary btn-sm">Make a Booking</Link>}
        </div>
      ) : (
        <div className="grid-2">
          {bookings.map(b => (
            <Link to={`/booking/${b.bookingRef}`} key={b.bookingRef} className="card" style={{ textDecoration: 'none', color: 'var(--text)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
                <span className={`badge ${statusBadge(b.status)}`}>{b.status}</span>
                <span style={{ fontFamily: 'monospace', fontSize: '0.8rem', color: 'var(--text-muted)' }}>{b.bookingRef}</span>
              </div>
              <h3 style={{ fontSize: '1.05rem', marginBottom: '0.25rem' }}>{b.eventType?.name ?? b.eventType}</h3>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                {b.bookingDate} at {b.startTime} • {b.durationHours}h
              </p>
              <p style={{ color: 'var(--primary-light)', fontWeight: 600, marginTop: '0.5rem' }}>
                ₹{b.totalAmount?.toLocaleString()}
              </p>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
