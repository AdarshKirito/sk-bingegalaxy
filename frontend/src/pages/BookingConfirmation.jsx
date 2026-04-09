import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import SEO from '../components/SEO';
import { FiCheckCircle, FiClock } from 'react-icons/fi';
import DOMPurify from 'dompurify';
import './CustomerHub.css';

export default function BookingConfirmation() {
  const { ref } = useParams();
  const [booking, setBooking] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    bookingService.getByRef(ref)
      .then(res => setBooking(res.data.data))
      .catch(err => {
        console.error('Failed to load booking:', err);
        setError(err?.response?.status === 404 ? 'Booking not found' : 'Failed to load booking details');
      })
      .finally(() => setLoading(false));
  }, [ref]);

  if (loading) return <div className="loading"><div className="spinner"></div></div>;
  if (error) return <div className="container customer-flow-shell customer-flow-shell-narrow"><div className="customer-flow-card customer-flow-empty"><h2>{error}</h2></div></div>;
  if (!booking) return <div className="container customer-flow-shell customer-flow-shell-narrow"><div className="customer-flow-card customer-flow-empty"><h2>Booking not found</h2></div></div>;

  const statusBadge = {
    PENDING: 'badge-warning',
    CONFIRMED: 'badge-success',
    CANCELLED: 'badge-danger',
    COMPLETED: 'badge-info',
  }[booking.status] || 'badge-info';
  const durationLabel = (() => {
    const minutes = booking.durationMinutes || (booking.durationHours * 60);
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = minutes % 60;
    if (hours > 0 && remainingMinutes > 0) return `${hours}hr ${remainingMinutes}m`;
    if (hours > 0) return `${hours}hr`;
    return `${remainingMinutes}m`;
  })();

  return (
    <div className="container customer-flow-shell customer-flow-shell-narrow">
      <SEO title="Booking Confirmation" description="Review your booking details, payment state, and next actions from one confirmation screen." />

      <section className="customer-flow-card customer-flow-empty">
        <span className="customer-flow-icon"><FiCheckCircle /></span>
        <h2>Booking {booking.status === 'PENDING' ? 'created' : booking.status.toLowerCase()}</h2>
        <p className="customer-booking-ref">{booking.bookingRef}</p>
        <div className="customer-flow-badges">
          <span className={`badge ${statusBadge}`}>{booking.status}</span>
          <span className={`badge ${booking.paymentStatus === 'SUCCESS' ? 'badge-success' : 'badge-warning'}`}>
            Payment: {booking.paymentStatus}
          </span>
        </div>
      </section>

      <section className="customer-flow-card customer-flow-stack">
        <div className="customer-flow-row">
          <span>Event type</span>
          <strong>{booking.eventType?.name ?? booking.eventType}</strong>
        </div>
        <div className="customer-flow-row">
          <span>Date</span>
          <strong>{booking.bookingDate}</strong>
        </div>
        <div className="customer-flow-row">
          <span>Time</span>
          <strong>{booking.startTime}</strong>
        </div>
        <div className="customer-flow-row">
          <span>Duration</span>
          <strong>{durationLabel}</strong>
        </div>
        {booking.addOns?.length > 0 && (
          <div className="customer-flow-row">
            <span>Add-ons</span>
            <strong>{booking.addOns.map((item) => item.name ?? item.addOnName).join(', ')}</strong>
          </div>
        )}
        {booking.specialNotes && (
          <div className="customer-flow-row">
            <span>Notes</span>
            <strong>{DOMPurify.sanitize(booking.specialNotes, { ALLOWED_TAGS: [] })}</strong>
          </div>
        )}
        {booking.earlyCheckoutNote && (
          <div className="customer-flow-alert customer-flow-alert-success">
            <strong><FiClock /> Early checkout note</strong>
            <p>{DOMPurify.sanitize(booking.earlyCheckoutNote, { ALLOWED_TAGS: [] })}</p>
          </div>
        )}
        <div className="customer-flow-row">
          <span>Base amount</span>
          <strong>{`₹${booking.baseAmount?.toLocaleString()}`}</strong>
        </div>
        <div className="customer-flow-row">
          <span>Add-on amount</span>
          <strong>{`₹${booking.addOnAmount?.toLocaleString()}`}</strong>
        </div>
        <div className="customer-flow-total">
          <span>Total</span>
          <strong className="customer-flow-amount">{`₹${booking.totalAmount?.toLocaleString()}`}</strong>
        </div>
      </section>

      <div className="customer-flow-actions">
        {booking.status === 'PENDING' && booking.paymentStatus !== 'SUCCESS' && (
          <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary">Proceed to Payment</Link>
        )}
        <Link to="/my-bookings" className="btn btn-secondary">My Bookings</Link>
        <Link to="/book" className="btn btn-secondary">Book Another</Link>
      </div>
    </div>
  );
}
