import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { authService, bookingService } from '../services/endpoints';
import {
  buildSupportEmailHref,
  buildSupportWhatsAppHref,
  CUSTOMER_SUPPORT,
  downloadBookingSummary,
  EXPERIENCE_STEPS,
  getCallSupportHref,
  HELP_FAQS,
  mergeSupportContact,
} from '../services/customerExperience';
import { SkeletonGrid } from '../components/ui/Skeleton';
import Pagination from '../components/ui/Pagination';
import SEO from '../components/SEO';
import {
  FiArrowRight,
  FiCalendar,
  FiClock,
  FiCreditCard,
  FiDownload,
  FiFilter,
  FiHeadphones,
  FiMail,
  FiMessageCircle,
  FiPhoneCall,
  FiRefreshCw,
  FiSearch,
  FiShield,
  FiStar,
} from 'react-icons/fi';
import './CustomerHub.css';

export default function MyBookings() {
  const { user } = useAuth();
  const { selectedBinge } = useBinge();
  const [tab, setTab] = useState('upcoming');
  const [currentBookings, setCurrentBookings] = useState([]);
  const [pastBookings, setPastBookings] = useState([]);
  const [supportContact, setSupportContact] = useState(CUSTOMER_SUPPORT);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [paymentFilter, setPaymentFilter] = useState('ALL');
  const [datePreset, setDatePreset] = useState('ALL');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [query, setQuery] = useState('');
  const perPage = 6;

  useEffect(() => {
    setLoading(true);
    Promise.allSettled([authService.getSupportContact(), bookingService.getCurrentBookings(), bookingService.getPastBookings()])
      .then(([supportRes, currentRes, pastRes]) => {
        setSupportContact(supportRes.status === 'fulfilled' ? mergeSupportContact(supportRes.value.data.data) : CUSTOMER_SUPPORT);
        setCurrentBookings(currentRes.status === 'fulfilled' ? (currentRes.value.data.data || []) : []);
        setPastBookings(pastRes.status === 'fulfilled' ? (pastRes.value.data.data || []) : []);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    setPage(1);
  }, [tab, statusFilter, paymentFilter, datePreset, fromDate, toDate, query]);

  const statusBadge = (status) => ({
    PENDING: 'badge-warning', CONFIRMED: 'badge-success', CANCELLED: 'badge-danger',
    COMPLETED: 'badge-info', CHECKED_IN: 'badge-success', NO_SHOW: 'badge-danger',
  }[status] || 'badge-info');

  const paymentBadge = (paymentStatus) => ({
    SUCCESS: 'badge-success',
    PARTIALLY_REFUNDED: 'badge-info',
    FAILED: 'badge-danger',
    PENDING: 'badge-warning',
    INITIATED: 'badge-warning',
  }[paymentStatus] || 'badge-warning');

  const formatAmount = (amount) => `Rs ${Number(amount || 0).toLocaleString()}`;
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
  const support = mergeSupportContact(supportContact);
  const baseBookings = tab === 'upcoming' ? sortedUpcoming : tab === 'past' ? sortedPast : allBookings;
  const filteredBookings = useMemo(() => {
    const today = new Date();
    const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate());
    const thirtyDaysAhead = new Date(startOfToday);
    thirtyDaysAhead.setDate(thirtyDaysAhead.getDate() + 30);
    const ninetyDaysBack = new Date(startOfToday);
    ninetyDaysBack.setDate(ninetyDaysBack.getDate() - 90);

    return baseBookings.filter((booking) => {
      const matchesStatus = statusFilter === 'ALL' || booking.status === statusFilter;
      const matchesPayment = paymentFilter === 'ALL' || (booking.paymentStatus || 'PENDING') === paymentFilter;
      const searchTarget = [
        booking.bookingRef,
        booking.eventType?.name || booking.eventType,
        booking.bookingDate,
        booking.startTime,
        booking.customerName,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      const matchesQuery = !query.trim() || searchTarget.includes(query.trim().toLowerCase());

      const bookingDate = booking.bookingDate ? new Date(`${booking.bookingDate}T00:00:00`) : null;
      const matchesPreset = !bookingDate || datePreset === 'ALL'
        || (datePreset === 'NEXT_30' && bookingDate >= startOfToday && bookingDate <= thirtyDaysAhead)
        || (datePreset === 'THIS_MONTH' && bookingDate.getMonth() === today.getMonth() && bookingDate.getFullYear() === today.getFullYear())
        || (datePreset === 'LAST_90' && bookingDate >= ninetyDaysBack && bookingDate <= startOfToday);
      const matchesCustomFrom = !fromDate || booking.bookingDate >= fromDate;
      const matchesCustomTo = !toDate || booking.bookingDate <= toDate;

      return matchesStatus && matchesPayment && matchesQuery && matchesPreset && matchesCustomFrom && matchesCustomTo;
    });
  }, [baseBookings, statusFilter, paymentFilter, datePreset, fromDate, toDate, query]);
  const pagedBookings = filteredBookings.slice((page - 1) * perPage, page * perPage);
  const nextBooking = sortedUpcoming[0] || null;
  const pendingPayments = allBookings.filter((booking) => booking.status !== 'CANCELLED' && booking.paymentStatus !== 'SUCCESS');
  const successfulBookings = allBookings.filter(booking => booking.paymentStatus === 'SUCCESS').length;
  const totalSpend = allBookings
    .filter(booking => booking.paymentStatus === 'SUCCESS')
    .reduce((sum, booking) => sum + (booking.totalAmount || 0), 0);
  const unpaidBalance = pendingPayments.reduce((sum, booking) => sum + (booking.totalAmount || 0), 0);

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
      <SEO title="My Bookings" description="Control reservations, finish pending balances, repeat favorites, download summaries, and get support from one booking command center." />

      <section className="customer-hub-hero">
        <div className="customer-hub-copy">
          <span className="customer-hub-kicker">Booking Control Center</span>
          <h1>Run your reservation timeline like a control center instead of a static list.</h1>
          <p>Filter by status and date, continue pending balances, repeat earlier setups, download booking summaries, and reach support from the same screen.</p>
          <div className="customer-hub-actions">
            <Link to="/book" className="btn btn-primary">Create New Booking</Link>
            <Link to="/account" className="btn btn-secondary">Open Account Area</Link>
          </div>
        </div>

        <aside className="customer-hub-highlight card">
          <span className="customer-hub-panel-label">Next priority</span>
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
                  <Link to={`/payment/${nextBooking.bookingRef}`} className="btn btn-secondary btn-sm">Pay Pending Balance</Link>
                )}
              </div>
            </>
          ) : (
            <>
              <h2>No active reservation yet</h2>
              <p>Your next booking will appear here with payment and support actions ready.</p>
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
          <span className="customer-hub-stat-label">Pending Balance</span>
          <strong>{loading ? '-' : formatAmount(unpaidBalance)}</strong>
          <p>Outstanding amount across pending reservations.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiStar /></span>
          <span className="customer-hub-stat-label">Paid Reservations</span>
          <strong>{loading ? '-' : successfulBookings}</strong>
          <p>Trips already closed out successfully.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiClock /></span>
          <span className="customer-hub-stat-label">Total Spend</span>
          <strong>{loading ? '-' : formatAmount(totalSpend)}</strong>
          <p>Across all successfully paid reservations.</p>
        </article>
      </section>

      <section className="customer-hub-panel card">
        <div className="customer-hub-panel-head">
          <div>
            <span className="customer-hub-panel-label">Quick actions</span>
            <h2>Move faster without opening each booking one by one</h2>
          </div>
          <Link to="/payments" className="customer-hub-inline-link">Payments hub</Link>
        </div>
        <div className="customer-mini-grid customer-mini-grid-3up">
          <article className="customer-mini-card">
            <div>
              <span className="customer-booking-ref">Pending balance</span>
              <h3>{formatAmount(unpaidBalance)}</h3>
              <p>{pendingPayments.length} booking{pendingPayments.length === 1 ? '' : 's'} still need payment.</p>
            </div>
            <div className="customer-mini-card-actions">
              {pendingPayments[0] ? <Link to={`/payment/${pendingPayments[0].bookingRef}`} className="btn btn-primary btn-sm">Pay latest</Link> : <Link to="/payments" className="btn btn-secondary btn-sm">Open payments</Link>}
            </div>
          </article>
          <article className="customer-mini-card">
            <div>
              <span className="customer-booking-ref">Support</span>
              <h3>Help with changes</h3>
              <p>Reach the team with your booking reference already attached.</p>
            </div>
            <div className="customer-mini-card-actions">
              {support.whatsappRaw ? <a href={buildSupportWhatsAppHref({ supportContact: support, customerName: `${user?.firstName || ''} ${user?.lastName || ''}`.trim(), topic: 'booking support' })} target="_blank" rel="noreferrer" className="btn btn-primary btn-sm">WhatsApp</a> : support.email ? <a href={buildSupportEmailHref({ supportContact: support, customerName: `${user?.firstName || ''} ${user?.lastName || ''}`.trim(), topic: 'booking support' })} className="btn btn-primary btn-sm">Email Support</a> : <span className="customer-account-note">Support contact unavailable</span>}
            </div>
          </article>
          <article className="customer-mini-card">
            <div>
              <span className="customer-booking-ref">Venue</span>
              <h3>{selectedBinge?.name || 'Choose a venue'}</h3>
              <p>{selectedBinge?.address || 'Use the venue selector if you want a different experience base.'}</p>
            </div>
            <div className="customer-mini-card-actions">
              <Link to="/book" className="btn btn-secondary btn-sm">Create booking</Link>
            </div>
          </article>
        </div>
      </section>

      <section className="customer-hub-toolbar card">
        <div className="customer-hub-tabs">
          <button className={`btn btn-sm ${tab === 'upcoming' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab('upcoming')}>Upcoming</button>
          <button className={`btn btn-sm ${tab === 'past' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab('past')}>Past</button>
          <button className={`btn btn-sm ${tab === 'all' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab('all')}>All</button>
        </div>

        <div className="customer-hub-filters customer-hub-filters-wide">
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

          <label className="customer-hub-select">
            <FiCalendar />
            <select value={datePreset} onChange={(event) => setDatePreset(event.target.value)}>
              <option value="ALL">All dates</option>
              <option value="NEXT_30">Next 30 days</option>
              <option value="THIS_MONTH">This month</option>
              <option value="LAST_90">Last 90 days</option>
            </select>
          </label>

          <label className="customer-hub-date-field">
            <span>From</span>
            <input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} />
          </label>

          <label className="customer-hub-date-field">
            <span>To</span>
            <input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} />
          </label>

          <button className="btn btn-secondary btn-sm" onClick={() => {
            setStatusFilter('ALL');
            setPaymentFilter('ALL');
            setDatePreset('ALL');
            setFromDate('');
            setToDate('');
            setQuery('');
          }}>
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
            {pagedBookings.map((booking) => {
              const customerName = [user?.firstName, user?.lastName].filter(Boolean).join(' ');

              return (
              <article key={booking.bookingRef} className="card customer-booking-card customer-booking-card-rich">
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
                  <span><FiClock /> {booking.startTime} and {formatDuration(booking)}</span>
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
                    <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">Pay Pending Balance</Link>
                  )}
                  {(booking.eventType?.id || booking.eventTypeId) && (
                    <Link to="/book" state={getRepeatBookingState(booking)} className="btn btn-secondary btn-sm">
                      Book Similar <FiArrowRight />
                    </Link>
                  )}
                </div>

                <div className="customer-booking-actions customer-booking-actions-support">
                  <button type="button" className="btn btn-secondary btn-sm" onClick={() => downloadBookingSummary(booking, { customerName, venueName: selectedBinge?.name })}>
                    <FiDownload /> Download Summary
                  </button>
                  {support.email && <a href={buildSupportEmailHref({ supportContact: support, bookingRef: booking.bookingRef, customerName, topic: 'Booking help' })} className="btn btn-secondary btn-sm">
                    <FiMail /> Email Support
                  </a>}
                  {support.whatsappRaw && <a href={buildSupportWhatsAppHref({ supportContact: support, bookingRef: booking.bookingRef, customerName, topic: 'booking help' })} target="_blank" rel="noreferrer" className="btn btn-secondary btn-sm">
                    <FiMessageCircle /> WhatsApp
                  </a>}
                  {support.phoneRaw && <a href={getCallSupportHref(support)} className="btn btn-secondary btn-sm">
                    <FiPhoneCall /> Call
                  </a>}
                </div>

                <div className="customer-booking-support-note">
                  <span><FiHeadphones /> Need help with a change, payment, or arrival timing? Use your booking ref for faster support.</span>
                </div>
              </article>
              );
            })}
          </div>

          <Pagination page={page} totalPages={Math.ceil(filteredBookings.length / perPage)} onPageChange={setPage} />
        </>
      )}

      <section className="customer-booking-help-grid">
        <article className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">FAQ</span>
              <h2>Answers that reduce back-and-forth</h2>
            </div>
          </div>
          <div className="customer-faq-list customer-faq-list-compact">
            {HELP_FAQS.slice(0, 3).map((item) => (
              <article key={item.question} className="customer-faq-item">
                <h3>{item.question}</h3>
                <p>{item.answer}</p>
              </article>
            ))}
          </div>
        </article>

        <article className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Trust and policy</span>
              <h2>Know what to expect before the day arrives</h2>
            </div>
          </div>
          <div className="customer-account-policy-list">
            <p><FiShield /> Cancellation and reschedule help is fastest before the booking date and before refund disputes begin.</p>
            <p><FiCreditCard /> Payment support covers pending balances, failed attempts, and refund follow-up questions.</p>
            <p><FiPhoneCall /> Call or WhatsApp support during {support.hours} for urgent booking issues.</p>
          </div>
        </article>

        <article className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">How your experience works</span>
              <h2>Three steps from plan to private screening</h2>
            </div>
          </div>
          <ol className="customer-steps-list customer-steps-list-tight">
            {EXPERIENCE_STEPS.map((step) => <li key={step}>{step}</li>)}
          </ol>
        </article>
      </section>
    </div>
  );
}
