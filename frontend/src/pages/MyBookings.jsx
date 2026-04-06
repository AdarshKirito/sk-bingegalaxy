import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { SkeletonGrid } from '../components/ui/Skeleton';
import Pagination from '../components/ui/Pagination';
import SEO from '../components/SEO';
import { FiArrowRight, FiCalendar, FiClock, FiCreditCard, FiFilter, FiRefreshCw, FiSearch, FiStar } from 'react-icons/fi';
import './CustomerHub.css';

export default function MyBookings() {
  const [tab, setTab] = useState('upcoming');
  const [currentBookings, setCurrentBookings] = useState([]);
  const [pastBookings, setPastBookings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [paymentFilter, setPaymentFilter] = useState('ALL');
  const [query, setQuery] = useState('');
  const perPage = 6;

  useEffect(() => {
    setLoading(true);
    Promise.allSettled([bookingService.getCurrentBookings(), bookingService.getPastBookings()])
      .then(([currentRes, pastRes]) => {
        setCurrentBookings(currentRes.status === 'fulfilled' ? (currentRes.value.data.data || []) : []);
        setPastBookings(pastRes.status === 'fulfilled' ? (pastRes.value.data.data || []) : []);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    setPage(1);
  }, [tab, statusFilter, paymentFilter, query]);

  const statusBadge = (s) => ({
    PENDING: 'badge-warning', CONFIRMED: 'badge-success', CANCELLED: 'badge-danger',
    COMPLETED: 'badge-info', CHECKED_IN: 'badge-success', NO_SHOW: 'badge-danger',
  }[s] || 'badge-info');

  const paymentBadge = (paymentStatus) => ({
    SUCCESS: 'badge-success',
    PARTIALLY_REFUNDED: 'badge-info',
    FAILED: 'badge-danger',
    PENDING: 'badge-warning',
  }[paymentStatus] || 'badge-warning');

  const formatAmount = (amount) => `₹${Number(amount || 0).toLocaleString()}`;
  const formatDuration = (booking) => {
    const totalMinutes = booking.durationMinutes || ((booking.durationHours || 0) * 60);
    if (!totalMinutes) return 'Flexible duration';
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    if (hours > 0 && minutes > 0) return `${hours}h ${minutes}m`;
    if (hours > 0) return `${hours}h`;
    return `${minutes}m`;
  };

  const sortedUpcoming = [...currentBookings].sort((left, right) => new Date(`${left.bookingDate}T${left.startTime || '00:00'}`) - new Date(`${right.bookingDate}T${right.startTime || '00:00'}`));
  const sortedPast = [...pastBookings].sort((left, right) => new Date(`${right.bookingDate}T${right.startTime || '00:00'}`) - new Date(`${left.bookingDate}T${left.startTime || '00:00'}`));
  const allBookings = [...sortedUpcoming, ...sortedPast];
  const baseBookings = tab === 'upcoming' ? sortedUpcoming : tab === 'past' ? sortedPast : allBookings;
  const filteredBookings = baseBookings.filter(booking => {
    const matchesStatus = statusFilter === 'ALL' || booking.status === statusFilter;
    const matchesPayment = paymentFilter === 'ALL' || (booking.paymentStatus || 'PENDING') === paymentFilter;
    const searchTarget = [booking.bookingRef, booking.eventType?.name || booking.eventType, booking.bookingDate, booking.startTime]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    const matchesQuery = !query.trim() || searchTarget.includes(query.trim().toLowerCase());
    return matchesStatus && matchesPayment && matchesQuery;
  });
  const pagedBookings = filteredBookings.slice((page - 1) * perPage, page * perPage);
  const nextBooking = sortedUpcoming[0] || null;
  const pendingPayments = allBookings.filter(booking => booking.status === 'PENDING' && booking.paymentStatus !== 'SUCCESS');
  const successfulBookings = allBookings.filter(booking => booking.paymentStatus === 'SUCCESS').length;
  const totalSpend = allBookings
    .filter(booking => booking.paymentStatus === 'SUCCESS')
    .reduce((sum, booking) => sum + (booking.totalAmount || 0), 0);

  const getRepeatBookingState = (booking) => ({
    eventTypeId: booking.eventType?.id || booking.eventTypeId,
    eventTypeName: booking.eventType?.name || booking.eventType,
    prefillBooking: {
      eventTypeId: booking.eventType?.id || booking.eventTypeId,
      durationMinutes: booking.durationMinutes || ((booking.durationHours || 0) * 60),
      numberOfGuests: booking.numberOfGuests || 1,
      addOns: (booking.addOns || []).map(addOn => ({
        addOnId: addOn.addOnId || addOn.id,
        quantity: addOn.quantity || 1,
        price: addOn.price || 0,
        name: addOn.name || addOn.addOnName || '',
      })),
      specialNotes: booking.specialNotes || '',
    },
  });

  return (
    <div className="container customer-hub">
      <SEO title="My Bookings" description="Track your reservations, filter by payment and status, and repeat earlier experiences in a few clicks." />

      <section className="customer-hub-hero">
        <div className="customer-hub-copy">
          <span className="customer-hub-kicker">Customer Control Center</span>
          <h1>Manage every reservation from one timeline instead of bouncing between pages.</h1>
          <p>Filter upcoming and past bookings, pick up pending payments, and launch a similar booking with the same event setup already loaded.</p>
          <div className="customer-hub-actions">
            <Link to="/book" className="btn btn-primary">Create New Booking</Link>
            <Link to="/payments" className="btn btn-secondary">Open Payments</Link>
          </div>
        </div>

        <aside className="customer-hub-highlight card">
          <span className="customer-hub-panel-label">Next up</span>
          {loading ? (
            <h2>Loading your reservations...</h2>
          ) : nextBooking ? (
            <>
              <h2>{nextBooking.eventType?.name ?? nextBooking.eventType}</h2>
              <p>{nextBooking.bookingDate} at {nextBooking.startTime} for {formatDuration(nextBooking)}</p>
              <div className="customer-hub-highlight-meta">
                <span className={`badge ${statusBadge(nextBooking.status)}`}>{nextBooking.status}</span>
                <span className={`badge ${paymentBadge(nextBooking.paymentStatus)}`}>{nextBooking.paymentStatus || 'PENDING'}</span>
              </div>
              <strong>{formatAmount(nextBooking.totalAmount)}</strong>
              <div className="customer-hub-inline-actions">
                <Link to={`/booking/${nextBooking.bookingRef}`} className="btn btn-primary btn-sm">View Booking</Link>
                {nextBooking.paymentStatus !== 'SUCCESS' && (
                  <Link to={`/payment/${nextBooking.bookingRef}`} className="btn btn-secondary btn-sm">Pay Now</Link>
                )}
              </div>
            </>
          ) : (
            <>
              <h2>No active reservation yet</h2>
              <p>Your next booking will appear here with direct payment and repeat-booking actions.</p>
              <Link to="/book" className="btn btn-primary btn-sm">Book Now</Link>
            </>
          )}
        </aside>
      </section>

      <section className="customer-hub-stats">
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiCalendar /></span>
          <span className="customer-hub-stat-label">Upcoming</span>
          <strong>{loading ? '–' : sortedUpcoming.length}</strong>
          <p>Reservations that still need your attention.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiCreditCard /></span>
          <span className="customer-hub-stat-label">Pending Payments</span>
          <strong>{loading ? '–' : pendingPayments.length}</strong>
          <p>Bookings waiting for payment completion.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiStar /></span>
          <span className="customer-hub-stat-label">Paid Reservations</span>
          <strong>{loading ? '–' : successfulBookings}</strong>
          <p>Confirmed bookings already covered.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiClock /></span>
          <span className="customer-hub-stat-label">Total Spend</span>
          <strong>{loading ? '–' : formatAmount(totalSpend)}</strong>
          <p>Across all successfully paid reservations.</p>
        </article>
      </section>

      <section className="customer-hub-toolbar card">
        <div className="customer-hub-tabs">
          <button className={`btn btn-sm ${tab === 'upcoming' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab('upcoming')}>Upcoming</button>
          <button className={`btn btn-sm ${tab === 'past' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab('past')}>Past</button>
          <button className={`btn btn-sm ${tab === 'all' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab('all')}>All</button>
        </div>

        <div className="customer-hub-filters">
          <label className="customer-hub-search">
            <FiSearch />
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search by ref, event, date"
            />
          </label>

          <label className="customer-hub-select">
            <FiFilter />
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
              <option value="ALL">All statuses</option>
              <option value="PENDING">Pending</option>
              <option value="CONFIRMED">Confirmed</option>
              <option value="CHECKED_IN">Checked in</option>
              <option value="COMPLETED">Completed</option>
              <option value="CANCELLED">Cancelled</option>
            </select>
          </label>

          <label className="customer-hub-select">
            <FiCreditCard />
            <select value={paymentFilter} onChange={(event) => setPaymentFilter(event.target.value)}>
              <option value="ALL">All payments</option>
              <option value="SUCCESS">Paid</option>
              <option value="PENDING">Pending</option>
              <option value="PARTIALLY_REFUNDED">Partially Refunded</option>
              <option value="FAILED">Failed</option>
            </select>
          </label>

          <button className="btn btn-secondary btn-sm" onClick={() => { setStatusFilter('ALL'); setPaymentFilter('ALL'); setQuery(''); }}>
            <FiRefreshCw /> Reset
          </button>
        </div>
      </section>

      {loading ? (
        <SkeletonGrid count={6} columns={2} />
      ) : filteredBookings.length === 0 ? (
        <div className="card customer-hub-empty">
          <h2>No matching bookings</h2>
          <p>Try clearing the filters or create a fresh reservation.</p>
          <Link to="/book" className="btn btn-primary btn-sm">Make a Booking</Link>
        </div>
      ) : (
        <>
          <div className="customer-booking-grid">
            {pagedBookings.map(booking => (
              <article key={booking.bookingRef} className="card customer-booking-card">
                <div className="customer-booking-topline">
                  <span className={`badge ${statusBadge(booking.status)}`}>{booking.status}</span>
                  <span className={`badge ${paymentBadge(booking.paymentStatus)}`}>{booking.paymentStatus || 'PENDING'}</span>
                </div>

                <div className="customer-booking-head">
                  <div>
                    <span className="customer-booking-ref">{booking.bookingRef}</span>
                    <h3>{booking.eventType?.name ?? booking.eventType}</h3>
                  </div>
                  <strong>{formatAmount(booking.totalAmount)}</strong>
                </div>

                <div className="customer-booking-meta">
                  <span><FiCalendar /> {booking.bookingDate}</span>
                  <span><FiClock /> {booking.startTime} • {formatDuration(booking)}</span>
                  <span><FiCreditCard /> {booking.paymentMethod?.replace('_', ' ') || 'Payment method at checkout'}</span>
                </div>

                {(booking.addOns || []).length > 0 && (
                  <div className="customer-booking-tags">
                    {booking.addOns.slice(0, 3).map(addOn => (
                      <span key={`${booking.bookingRef}-${addOn.addOnId || addOn.id}`} className="customer-booking-tag">
                        {addOn.name || addOn.addOnName || 'Add-on'} x{addOn.quantity || 1}
                      </span>
                    ))}
                  </div>
                )}

                <div className="customer-booking-actions">
                  <Link to={`/booking/${booking.bookingRef}`} className="btn btn-secondary btn-sm">View Details</Link>
                  {booking.paymentStatus !== 'SUCCESS' && booking.status !== 'CANCELLED' && (
                    <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">Complete Payment</Link>
                  )}
                  {(booking.eventType?.id || booking.eventTypeId) && (
                    <Link to="/book" state={getRepeatBookingState(booking)} className="btn btn-secondary btn-sm">
                      Book Similar <FiArrowRight />
                    </Link>
                  )}
                </div>
              </article>
            ))}
          </div>

          <Pagination page={page} totalPages={Math.ceil(filteredBookings.length / perPage)} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
