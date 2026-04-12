import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import SEO from '../components/SEO';
import {
  FiAlertCircle,
  FiArrowRight,
  FiCalendar,
  FiCheckCircle,
  FiClock,
  FiCreditCard,
  FiHash,
  FiLayers,
  FiMail,
  FiMapPin,
  FiShield,
  FiUsers,
} from 'react-icons/fi';
import DOMPurify from 'dompurify';
import useBingeStore from '../stores/bingeStore';
import './CustomerHub.css';

const formatAmount = (value) => `₹${Number(value || 0).toLocaleString()}`;

const formatLabel = (value, fallback = 'Pending') => {
  if (!value) return fallback;
  return String(value)
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (char) => char.toUpperCase());
};

const formatDuration = (booking) => {
  const minutes = booking?.durationMinutes || (booking?.durationHours ? booking.durationHours * 60 : 0);
  if (!minutes) return 'Not set';

  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;

  if (hours > 0 && remainingMinutes > 0) return `${hours}hr ${remainingMinutes}m`;
  if (hours > 0) return `${hours}hr`;
  return `${remainingMinutes}m`;
};

export default function BookingConfirmation() {
  const { ref } = useParams();
  const { selectedBinge } = useBingeStore();
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

  const eventLabel = booking.eventType?.name ?? booking.eventType ?? 'Private screening';
  const venueLabel = selectedBinge?.name || booking.venueName || booking.bingeName || 'Selected venue';
  const bookingStatus = String(booking.status || 'PENDING').toUpperCase();
  const paymentStatus = String(booking.paymentStatus || 'PENDING').toUpperCase();
  const paymentStatusLabel = formatLabel(paymentStatus, 'Pending');
  const bookingStatusLabel = formatLabel(bookingStatus, 'Pending');
  const paymentComplete = paymentStatus === 'SUCCESS' || paymentStatus === 'REFUNDED' || paymentStatus === 'PARTIALLY_REFUNDED';
  const needsPayment = bookingStatus === 'PENDING' && !paymentComplete;
  const paidButPending = bookingStatus === 'PENDING' && paymentComplete;
  const statusBadge = {
    PENDING: 'badge-warning',
    CONFIRMED: 'badge-success',
    CANCELLED: 'badge-danger',
    COMPLETED: 'badge-info',
  }[bookingStatus] || 'badge-info';
  const paymentBadge = paymentComplete
    ? 'badge-success'
    : paymentStatus === 'FAILED'
      ? 'badge-danger'
      : paymentStatus === 'INITIATED'
        ? 'badge-warning'
        : 'badge-info';
  const durationLabel = formatDuration(booking);
  const cleanNotes = booking.specialNotes ? DOMPurify.sanitize(booking.specialNotes, { ALLOWED_TAGS: [] }) : '';
  const cleanEarlyCheckoutNote = booking.earlyCheckoutNote ? DOMPurify.sanitize(booking.earlyCheckoutNote, { ALLOWED_TAGS: [] }) : '';
  const addOnsLabel = (booking.addOns || []).map((item) => item.name ?? item.addOnName).filter(Boolean).join(', ');
  const detailCards = [
    { icon: <FiLayers />, label: 'Event', value: eventLabel },
    { icon: <FiCalendar />, label: 'Date', value: booking.bookingDate || 'Not set' },
    { icon: <FiClock />, label: 'Start time', value: booking.startTime || 'Not set' },
    { icon: <FiHash />, label: 'Duration', value: durationLabel },
    { icon: <FiUsers />, label: 'Guests', value: `${booking.numberOfGuests || 1} guest${Number(booking.numberOfGuests || 1) === 1 ? '' : 's'}` },
    { icon: <FiMapPin />, label: 'Venue', value: venueLabel },
  ];
  const summaryFacts = [
    { label: 'Total', value: formatAmount(booking.totalAmount) },
    { label: 'Payment', value: paymentStatusLabel },
    { label: 'Booking ref', value: booking.bookingRef, mono: true },
    { label: 'Contact', value: booking.customerEmail || 'Linked to your account' },
  ];
  const heading = bookingStatus === 'CONFIRMED' && paymentComplete
    ? 'Your booking is confirmed and ready.'
    : paidButPending
      ? 'Payment is recorded and the booking is waiting for final confirmation.'
      : needsPayment
        ? 'Your reservation is created, but payment still needs attention.'
        : bookingStatus === 'CANCELLED'
          ? 'This reservation has been cancelled.'
          : bookingStatus === 'COMPLETED'
            ? 'This booking has already been completed.'
            : `Booking ${bookingStatusLabel.toLowerCase()}.`;
  const description = bookingStatus === 'CONFIRMED' && paymentComplete
    ? 'This is now a proper final review screen with the reservation details, payment state, and next actions grouped together.'
    : paidButPending
      ? 'If this page was opened right after payment, give the booking a moment to catch up. The payment is already on file.'
      : needsPayment
        ? 'The reservation exists, but the last handoff is still unfinished. Use the payment page to close the loop.'
        : bookingStatus === 'CANCELLED'
          ? 'The summary stays available so the cancellation is still clear and traceable.'
          : 'Use this page as the stable place to review the reservation and move into the next action.';
  const stages = [
    {
      title: 'Reservation',
      caption: `Ref ${booking.bookingRef} is created.`,
      icon: <FiHash />,
      state: 'complete',
    },
    {
      title: 'Payment',
      caption: paymentComplete ? `${paymentStatusLabel} and synced.` : `${paymentStatusLabel} right now.`,
      icon: <FiCreditCard />,
      state: paymentComplete ? 'complete' : needsPayment ? 'active' : 'pending',
    },
    {
      title: 'Ready',
      caption: bookingStatus === 'CONFIRMED' ? 'Reservation confirmed.' : bookingStatus === 'COMPLETED' ? 'Experience completed.' : bookingStatus === 'CANCELLED' ? 'Booking cancelled.' : 'Waiting for final confirmation.',
      icon: bookingStatus === 'CONFIRMED' || bookingStatus === 'COMPLETED' ? <FiCheckCircle /> : <FiArrowRight />,
      state: bookingStatus === 'COMPLETED' ? 'complete' : bookingStatus === 'CONFIRMED' ? 'active' : 'pending',
    },
  ];
  const nextSteps = needsPayment
    ? [
        {
          title: 'Finish payment from the booking flow',
          body: 'Open the payment page for this booking and complete the transaction before treating the reservation as locked in.',
        },
        {
          title: 'Return here after payment',
          body: 'This confirmation page becomes more useful once the booking and payment states are aligned.',
        },
        {
          title: 'Use My Bookings if anything needs follow-up',
          body: 'The timeline view stays the best place for later support, reminders, and changes.',
        },
      ]
    : bookingStatus === 'CONFIRMED'
      ? [
          {
            title: 'Keep the reference handy',
            body: `Use ${booking.bookingRef} whenever you need support or want to revisit this reservation quickly.`,
          },
          {
            title: 'Review notes and add-ons before the day',
            body: 'This page now keeps the important booking extras visible instead of burying them below the fold.',
          },
          {
            title: 'Use My Bookings for any later changes',
            body: 'That timeline keeps the reservation history, support links, and payments connected in one place.',
          },
        ]
      : bookingStatus === 'CANCELLED'
        ? [
            {
              title: 'Keep this summary as the record',
              body: 'The cancellation state and booking reference stay visible here for later support conversations.',
            },
            {
              title: 'Check payment history if a refund is expected',
              body: 'The payments hub is the right place to confirm whether the transaction was refunded or reversed.',
            },
            {
              title: 'Create a new reservation separately',
              body: 'A cancelled booking should not be reused. Start a fresh booking when you are ready again.',
            },
          ]
        : [
            {
              title: 'Use this as the final review screen',
              body: 'The main booking facts, amount, and status are all grouped here now for a cleaner handoff.',
            },
            {
              title: 'Open payments if the money state matters',
              body: 'The payments hub gives a clearer transaction-by-transaction view when you need it.',
            },
            {
              title: 'Return to My Bookings for timeline context',
              body: 'That page stays best for reviewing the reservation among your other bookings.',
            },
          ];

  return (
    <div className="container customer-flow-shell customer-flow-shell-wide">
      <SEO title="Booking Confirmation" description="Review your booking details, payment state, and next actions from one confirmation screen." />

      <section className="customer-flow-hero">
        <div className="customer-flow-copy">
          <span className="customer-flow-kicker">Booking confirmation</span>
          <h1>{heading}</h1>
          <p>{description}</p>

          <div className="customer-flow-stagebar">
            {stages.map((stage) => (
              <article key={stage.title} className={`customer-flow-stage customer-flow-stage--${stage.state}`}>
                <div className="customer-flow-stage-top">
                  <span className="customer-flow-stage-icon">{stage.icon}</span>
                  <strong>{stage.title}</strong>
                </div>
                <small>{stage.caption}</small>
              </article>
            ))}
          </div>

          <div className="customer-flow-inline">
            {needsPayment && (
              <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">Proceed to Payment</Link>
            )}
            <Link to="/my-bookings" className="btn btn-secondary btn-sm">My Bookings</Link>
            <Link to="/payments" className="btn btn-secondary btn-sm">Payments</Link>
          </div>
        </div>

        <aside className="customer-flow-summary">
          <span className="customer-flow-kicker">Reservation digest</span>
          <h2>{eventLabel}</h2>
          <p>{booking.bookingDate} at {booking.startTime}</p>
          <div className="customer-flow-badges">
            <span className={`badge ${statusBadge}`}>{bookingStatusLabel}</span>
            <span className={`badge ${paymentBadge}`}>Payment: {paymentStatusLabel}</span>
          </div>
          <strong>{formatAmount(booking.totalAmount)}</strong>
          <div className="customer-flow-fact-grid">
            {summaryFacts.map((fact) => (
              <div key={fact.label} className="customer-flow-fact">
                <span>{fact.label}</span>
                <strong className={fact.mono ? 'customer-flow-mono' : ''}>{fact.value}</strong>
              </div>
            ))}
          </div>
        </aside>
      </section>

      <section className="customer-flow-grid">
        <article className="customer-flow-card customer-flow-stack">
          <div className="customer-flow-card-head">
            <div>
              <span className="customer-flow-section-label">Reservation details</span>
              <h2>The booking information is grouped into one cleaner review block.</h2>
              <p>The event, timing, guest count, and venue now scan more easily than the earlier stacked row layout.</p>
            </div>
            <span className="customer-booking-ref">{booking.bookingRef}</span>
          </div>

          <div className="customer-flow-detail-grid">
            {detailCards.map((detail) => (
              <div key={detail.label} className="customer-flow-detail-item">
                <span>{detail.icon}{detail.label}</span>
                <strong>{detail.value}</strong>
              </div>
            ))}
          </div>

          {addOnsLabel && (
            <div className="customer-flow-note customer-flow-note-info">
              <strong><FiLayers /> Add-ons locked into this booking</strong>
              <p>{addOnsLabel}</p>
            </div>
          )}

          <div className="customer-flow-note customer-flow-note-info">
            <strong><FiMail /> Booking contact</strong>
            <p>{booking.customerEmail || 'This reservation is tied to the primary account email.'}</p>
          </div>
        </article>

        <article className="customer-flow-card customer-flow-stack">
          <div className="customer-flow-card-head">
            <div>
              <span className="customer-flow-section-label">Pricing and status</span>
              <h2>The amount breakdown is now easier to verify at a glance.</h2>
              <p>Pricing, payment state, and notes sit together so the confirmation does not feel like a plain receipt anymore.</p>
            </div>
            <strong className="customer-flow-amount">{formatAmount(booking.totalAmount)}</strong>
          </div>

          <div className="customer-flow-list">
            <div className="customer-flow-row">
              <span>Base amount</span>
              <strong>{formatAmount(booking.baseAmount)}</strong>
            </div>
            <div className="customer-flow-row">
              <span>Add-on amount</span>
              <strong>{formatAmount(booking.addOnAmount)}</strong>
            </div>
            {(booking.guestAmount || 0) > 0 && (
              <div className="customer-flow-row">
                <span>Guest charge</span>
                <strong>{formatAmount(booking.guestAmount)}</strong>
              </div>
            )}
            <div className="customer-flow-row">
              <span>Payment state</span>
              <strong>{paymentStatusLabel}</strong>
            </div>
            <div className="customer-flow-total">
              <span>Total</span>
              <strong className="customer-flow-amount">{formatAmount(booking.totalAmount)}</strong>
            </div>
          </div>

          {cleanNotes && (
            <div className="customer-flow-note customer-flow-note-info">
              <strong><FiAlertCircle /> Booking note</strong>
              <p>{cleanNotes}</p>
            </div>
          )}

          {cleanEarlyCheckoutNote && (
            <div className="customer-flow-note customer-flow-note-success">
              <strong><FiClock /> Early checkout note</strong>
              <p>{cleanEarlyCheckoutNote}</p>
            </div>
          )}
        </article>

        <article className="customer-flow-card customer-flow-card-span customer-flow-stack">
          <div className="customer-flow-card-head">
            <div>
              <span className="customer-flow-section-label">What happens next</span>
              <h2>The page now ends with clear next actions instead of a dead stop.</h2>
              <p>The goal is to make the confirmation page feel complete whether the booking is paid, pending, confirmed, or cancelled.</p>
            </div>
            <span className="customer-flow-section-label"><FiShield /> Better handoff after booking</span>
          </div>

          <div className="customer-flow-step-list">
            {nextSteps.map((step, index) => (
              <div key={step.title} className="customer-flow-step">
                <span className="customer-flow-step-index">{index + 1}</span>
                <div className="customer-flow-step-copy">
                  <strong>{step.title}</strong>
                  <p>{step.body}</p>
                </div>
              </div>
            ))}
          </div>

          <div className="customer-flow-actions customer-flow-actions-left">
            {needsPayment && (
              <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary">Proceed to Payment <FiArrowRight /></Link>
            )}
            <Link to="/my-bookings" className="btn btn-secondary">My Bookings</Link>
            <Link to="/book" className="btn btn-secondary">Book Another</Link>
          </div>
        </article>
      </section>
    </div>
  );
}
