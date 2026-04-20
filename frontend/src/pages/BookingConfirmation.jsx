import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
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
  FiPrinter,
  FiRefreshCw,
  FiRepeat,
  FiSend,
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
  const [rescheduleOpen, setRescheduleOpen] = useState(false);
  const [transferOpen, setTransferOpen] = useState(false);
  const [recurringGroupBookings, setRecurringGroupBookings] = useState(null);
  const [rescheduleForm, setRescheduleForm] = useState({ newBookingDate: '', newStartTime: '', newDurationMinutes: '' });
  const [transferForm, setTransferForm] = useState({ recipientName: '', recipientEmail: '', recipientPhone: '' });
  const [actionLoading, setActionLoading] = useState(false);

  useEffect(() => {
    bookingService.getByRef(ref)
      .then(res => setBooking(res.data.data))
      .catch(err => {
        console.error('Failed to load booking:', err);
        const status = err?.response?.status;
        if (status === 404) setError('Booking not found');
        else if (status === 403) setError('You do not have permission to view this booking');
        else setError(err?.response?.data?.message || 'Failed to load booking details');
      })
      .finally(() => setLoading(false));
  }, [ref]);

  const handleReschedule = async () => {
    if (!rescheduleForm.newBookingDate || !rescheduleForm.newStartTime) {
      toast.error('Please select a new date and time.');
      return;
    }
    setActionLoading(true);
    try {
      const payload = {
        newBookingDate: rescheduleForm.newBookingDate,
        newStartTime: rescheduleForm.newStartTime + ':00',
        newDurationMinutes: rescheduleForm.newDurationMinutes ? Number(rescheduleForm.newDurationMinutes) : null,
      };
      const res = await bookingService.rescheduleBooking(ref, payload);
      setBooking(res.data.data);
      toast.success('Booking rescheduled successfully');
      setRescheduleOpen(false);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to reschedule');
    } finally {
      setActionLoading(false);
    }
  };

  const handleTransfer = async () => {
    if (!transferForm.recipientName.trim() || !transferForm.recipientEmail.trim()) {
      toast.error('Recipient name and email are required.');
      return;
    }
    setActionLoading(true);
    try {
      const res = await bookingService.transferBooking(ref, transferForm);
      setBooking(res.data.data);
      toast.success('Booking transferred successfully');
      setTransferOpen(false);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to transfer');
    } finally {
      setActionLoading(false);
    }
  };

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
  // Build a clean customer-facing early checkout message from raw booking data (not the operational note)
  const earlyCheckoutDisplay = (() => {
    if (!booking.earlyCheckoutNote && !booking.actualCheckoutTime) return null;
    if (booking.actualCheckoutTime) {
      const checkoutTime = new Date(booking.actualCheckoutTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      const mins = booking.actualUsedMinutes;
      if (mins != null) {
        const h = Math.floor(mins / 60);
        const m = mins % 60;
        const dur = h > 0 && m > 0 ? `${h}h ${m}m` : h > 0 ? `${h}h` : `${m}m`;
        return `Checked out at ${checkoutTime}. Session duration: ${dur}.`;
      }
      return `Checked out at ${checkoutTime}.`;
    }
    return null;
  })();
  const addOnsLabel = (booking.addOns || []).map((item) => item.name ?? item.addOnName).filter(Boolean).join(', ');
  const detailCards = [
    { icon: <FiLayers />, label: 'Event', value: eventLabel },
    { icon: <FiCalendar />, label: 'Date', value: booking.bookingDate || 'Not set' },
    { icon: <FiClock />, label: 'Start time', value: booking.startTime || 'Not set' },
    { icon: <FiHash />, label: 'Duration', value: durationLabel },
    { icon: <FiUsers />, label: 'Guests', value: `${booking.numberOfGuests || 1} guest${Number(booking.numberOfGuests || 1) === 1 ? '' : 's'}` },
    { icon: <FiMapPin />, label: 'Venue', value: venueLabel },
    ...(booking.venueRoomName ? [{ icon: <FiMapPin />, label: 'Room', value: booking.venueRoomName }] : []),
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
            {booking.canCustomerReschedule && (
              <button className="btn btn-secondary btn-sm" onClick={() => { setRescheduleForm({ newBookingDate: booking.bookingDate || '', newStartTime: booking.startTime ? booking.startTime.substring(0, 5) : '', newDurationMinutes: '' }); setRescheduleOpen(true); }}>
                <FiRepeat /> Reschedule
              </button>
            )}
            {booking.canCustomerTransfer && (
              <button className="btn btn-secondary btn-sm" onClick={() => { setTransferForm({ recipientName: '', recipientEmail: '', recipientPhone: '' }); setTransferOpen(true); }}>
                <FiSend /> Transfer
              </button>
            )}
            <Link to="/my-bookings" className="btn btn-secondary btn-sm">My Bookings</Link>
            <Link to="/payments" className="btn btn-secondary btn-sm">Payments</Link>
            <button className="btn btn-secondary btn-sm" onClick={() => window.print()} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem' }}>
              <FiPrinter /> Print Receipt
            </button>
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
            {booking.surgeMultiplier && Number(booking.surgeMultiplier) > 1 && (
              <div className="customer-flow-row" style={{ color: '#92400e' }}>
                <span>⚡ {booking.surgeLabel || 'Peak pricing'}</span>
                <strong>{booking.surgeMultiplier}× multiplier</strong>
              </div>
            )}
            {(booking.loyaltyPointsRedeemed || 0) > 0 && (
              <div className="customer-flow-row" style={{ color: '#5b21b6' }}>
                <span>🎁 Loyalty discount ({booking.loyaltyPointsRedeemed} pts)</span>
                <strong>−{formatAmount(booking.loyaltyDiscountAmount)}</strong>
              </div>
            )}
            {(booking.loyaltyPointsEarned || 0) > 0 && (
              <div className="customer-flow-row" style={{ color: '#059669' }}>
                <span>⭐ Points earned</span>
                <strong>+{booking.loyaltyPointsEarned} pts</strong>
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

          {earlyCheckoutDisplay && (
            <div className="customer-flow-note customer-flow-note-success">
              <strong><FiClock /> Early checkout</strong>
              <p>{earlyCheckoutDisplay}</p>
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

        {(booking.transferred || booking.recurringGroupId || booking.rescheduleCount > 0) && (
          <article className="customer-flow-card customer-flow-card-span customer-flow-stack">
            <div className="customer-flow-card-head">
              <div>
                <span className="customer-flow-section-label">Booking history</span>
                <h2>Changes and lineage</h2>
              </div>
            </div>
            <div className="customer-flow-list">
              {booking.transferred && (
                <div className="customer-flow-row">
                  <span><FiSend /> Transferred from</span>
                  <strong>{booking.originalCustomerName || 'Another customer'}</strong>
                </div>
              )}
              {booking.rescheduleCount > 0 && (
                <div className="customer-flow-row">
                  <span><FiRefreshCw /> Rescheduled</span>
                  <strong>{booking.rescheduleCount} time{booking.rescheduleCount > 1 ? 's' : ''}{booking.originalBookingRef ? ` (originally ${booking.originalBookingRef})` : ''}</strong>
                </div>
              )}
              {booking.recurringGroupId && (
                <div className="customer-flow-row">
                  <span><FiRepeat /> Recurring series</span>
                  <button type="button" className="btn btn-secondary btn-sm" style={{ fontSize: '0.8rem' }}
                    onClick={async () => {
                      try {
                        setRecurringGroupBookings([]);
                        const res = await bookingService.getRecurringGroup(booking.recurringGroupId);
                        setRecurringGroupBookings(toArray(res.data?.data));
                      } catch { toast.error('Failed to load recurring series'); setRecurringGroupBookings(null); }
                    }}>View all in series</button>
                </div>
              )}
            </div>
          </article>
        )}
      </section>

      {/* Recurring Group Modal */}
      {recurringGroupBookings !== null && (
        <div className="modal-overlay" onClick={() => setRecurringGroupBookings(null)}>
          <div className="modal-content card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '560px', padding: '2rem' }}>
            <h2 style={{ marginBottom: '1rem' }}><FiRepeat /> Recurring Series</h2>
            {recurringGroupBookings.length === 0 ? (
              <p style={{ color: 'var(--text-muted)' }}>Loading...</p>
            ) : (
              <div style={{ maxHeight: '400px', overflowY: 'auto' }}>
                {recurringGroupBookings.map(b => (
                  <div key={b.bookingRef} className="card" style={{ padding: '0.75rem 1rem', marginBottom: '0.5rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <strong>{b.bookingRef}</strong>
                      <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                        {b.bookingDate} · {b.startTime}
                      </div>
                    </div>
                    <span className={`badge ${b.status === 'CONFIRMED' ? 'badge-success' : b.status === 'CANCELLED' ? 'badge-danger' : 'badge-warning'}`}>
                      {b.status}
                    </span>
                  </div>
                ))}
              </div>
            )}
            <div style={{ textAlign: 'right', marginTop: '1rem' }}>
              <button className="btn btn-secondary" onClick={() => setRecurringGroupBookings(null)}>Close</button>
            </div>
          </div>
        </div>
      )}

      {/* Reschedule Modal */}
      {rescheduleOpen && (
        <div className="modal-overlay" onClick={() => !actionLoading && setRescheduleOpen(false)}>
          <div className="modal-content card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '480px', padding: '2rem' }}>
            <h2 style={{ marginBottom: '0.5rem' }}>Reschedule Booking</h2>
            <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', marginBottom: '1.5rem' }}>
              {booking.bookingRef} — {eventLabel}
            </p>
            <label style={{ display: 'block', marginBottom: '1rem' }}>
              <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>New Date</span>
              <input type="date" className="form-control" value={rescheduleForm.newBookingDate}
                onChange={(e) => setRescheduleForm(prev => ({ ...prev, newBookingDate: e.target.value }))}
                min={new Date().toLocaleDateString('en-CA')} />
            </label>
            <label style={{ display: 'block', marginBottom: '1rem' }}>
              <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>New Start Time</span>
              <input type="time" className="form-control" value={rescheduleForm.newStartTime}
                onChange={(e) => setRescheduleForm(prev => ({ ...prev, newStartTime: e.target.value }))} />
            </label>
            <label style={{ display: 'block', marginBottom: '1.5rem' }}>
              <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>New Duration (minutes, leave empty to keep current)</span>
              <input type="number" className="form-control" min="30" step="30" placeholder="Same as original"
                value={rescheduleForm.newDurationMinutes}
                onChange={(e) => setRescheduleForm(prev => ({ ...prev, newDurationMinutes: e.target.value }))} />
            </label>
            <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end' }}>
              <button className="btn btn-secondary btn-sm" disabled={actionLoading} onClick={() => setRescheduleOpen(false)}>Cancel</button>
              <button className="btn btn-primary btn-sm" disabled={actionLoading} onClick={handleReschedule}>
                {actionLoading ? 'Rescheduling...' : 'Confirm Reschedule'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Transfer Modal */}
      {transferOpen && (
        <div className="modal-overlay" onClick={() => !actionLoading && setTransferOpen(false)}>
          <div className="modal-content card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '480px', padding: '2rem' }}>
            <h2 style={{ marginBottom: '0.5rem' }}>Transfer Booking</h2>
            <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', marginBottom: '1.5rem' }}>
              {booking.bookingRef} — {eventLabel}
            </p>
            <label style={{ display: 'block', marginBottom: '1rem' }}>
              <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>Recipient Name</span>
              <input type="text" className="form-control" placeholder="Full name of the new guest"
                value={transferForm.recipientName}
                onChange={(e) => setTransferForm(prev => ({ ...prev, recipientName: e.target.value }))} />
            </label>
            <label style={{ display: 'block', marginBottom: '1rem' }}>
              <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>Recipient Email</span>
              <input type="email" className="form-control" placeholder="name@example.com"
                value={transferForm.recipientEmail}
                onChange={(e) => setTransferForm(prev => ({ ...prev, recipientEmail: e.target.value }))} />
            </label>
            <label style={{ display: 'block', marginBottom: '1.5rem' }}>
              <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>Recipient Phone (optional)</span>
              <input type="tel" className="form-control" placeholder="+91XXXXXXXXXX"
                value={transferForm.recipientPhone}
                onChange={(e) => setTransferForm(prev => ({ ...prev, recipientPhone: e.target.value }))} />
            </label>
            <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end' }}>
              <button className="btn btn-secondary btn-sm" disabled={actionLoading} onClick={() => setTransferOpen(false)}>Cancel</button>
              <button className="btn btn-primary btn-sm" disabled={actionLoading} onClick={handleTransfer}>
                {actionLoading ? 'Transferring...' : 'Confirm Transfer'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
