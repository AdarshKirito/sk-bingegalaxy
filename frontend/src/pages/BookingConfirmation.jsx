import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { FiCheckCircle } from 'react-icons/fi';

export default function BookingConfirmation() {
  const { ref } = useParams();
  const [booking, setBooking] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    bookingService.getByRef(ref)
      .then(res => setBooking(res.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [ref]);

  if (loading) return <div className="loading"><div className="spinner"></div></div>;
  if (!booking) return <div className="container"><p>Booking not found</p></div>;

  const statusBadge = {
    PENDING: 'badge-warning',
    CONFIRMED: 'badge-success',
    CANCELLED: 'badge-danger',
    COMPLETED: 'badge-info',
  }[booking.status] || 'badge-info';

  return (
    <div className="container" style={{ maxWidth: '700px', margin: '0 auto' }}>
      <div style={{ textAlign: 'center', marginBottom: '2rem' }}>
        <FiCheckCircle style={{ fontSize: '3rem', color: 'var(--success)', marginBottom: '0.5rem' }} />
        <h1>Booking {booking.status === 'PENDING' ? 'Created' : booking.status}</h1>
        <p style={{ color: 'var(--text-secondary)', fontFamily: 'monospace', fontSize: '1.1rem' }}>{booking.bookingRef}</p>
      </div>

      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '1rem' }}>
          <span className={`badge ${statusBadge}`}>{booking.status}</span>
          <span className={`badge ${booking.paymentStatus === 'SUCCESS' ? 'badge-success' : 'badge-warning'}`}>
            Payment: {booking.paymentStatus}
          </span>
        </div>

        <div style={{ display: 'grid', gap: '0.6rem' }}>
          <Row label="Event Type" value={booking.eventType?.name ?? booking.eventType} />
          <Row label="Date" value={booking.bookingDate} />
          <Row label="Time" value={booking.startTime} />
          <Row label="Duration" value={(() => {
            const m = booking.durationMinutes || (booking.durationHours * 60);
            const h = Math.floor(m / 60);
            const min = m % 60;
            if (h > 0 && min > 0) return `${h}hr ${min}m`;
            if (h > 0) return `${h}hr`;
            return `${min}m`;
          })()} />
          {booking.addOns?.length > 0 && (
            <Row label="Add-Ons" value={booking.addOns.map(a => a.name ?? a.addOnName).join(', ')} />
          )}
          {booking.specialNotes && <Row label="Notes" value={booking.specialNotes} />}
          {booking.earlyCheckoutNote && (
            <div style={{ padding: '0.6rem 0.85rem', background: 'rgba(16,185,129,0.08)', border: '1px solid var(--success)', borderRadius: '8px', fontSize: '0.88rem', color: 'var(--success)', marginTop: '0.25rem' }}>
              ⏱️ {booking.earlyCheckoutNote}
            </div>
          )}
          <hr style={{ borderColor: 'var(--border)' }} />
          <Row label="Base Amount" value={`₹${booking.baseAmount?.toLocaleString()}`} />
          <Row label="Add-On Amount" value={`₹${booking.addOnAmount?.toLocaleString()}`} />
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '1.2rem', fontWeight: '700' }}>
            <span>Total</span>
            <span style={{ color: 'var(--primary)' }}>₹{booking.totalAmount?.toLocaleString()}</span>
          </div>
        </div>
      </div>

      <div style={{ display: 'flex', gap: '1rem', justifyContent: 'center' }}>
        {booking.status === 'PENDING' && booking.paymentStatus !== 'SUCCESS' && (
          <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary">Proceed to Payment</Link>
        )}
        <Link to="/my-bookings" className="btn btn-secondary">My Bookings</Link>
        <Link to="/book" className="btn btn-secondary">Book Another</Link>
      </div>
    </div>
  );
}

function Row({ label, value }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.95rem' }}>
      <span style={{ color: 'var(--text-secondary)' }}>{label}</span>
      <span>{value}</span>
    </div>
  );
}
