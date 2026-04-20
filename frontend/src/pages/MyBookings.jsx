import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { authService, bookingService, toArray } from '../services/endpoints';
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
import { toast } from 'react-toastify';
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
  FiMapPin,
  FiMessageCircle,
  FiPhoneCall,
  FiRefreshCw,
  FiRepeat,
  FiSearch,
  FiSend,
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
  const [pendingReviews, setPendingReviews] = useState([]);
  const [reviewDrafts, setReviewDrafts] = useState({});
  const [submittingReviewRef, setSubmittingReviewRef] = useState('');
  const [supportContact, setSupportContact] = useState(CUSTOMER_SUPPORT);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [paymentFilter, setPaymentFilter] = useState('ALL');
  const [datePreset, setDatePreset] = useState('ALL');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [query, setQuery] = useState('');
  const [waitlistEntries, setWaitlistEntries] = useState([]);
  const [rescheduleModal, setRescheduleModal] = useState(null);
  const [transferModal, setTransferModal] = useState(null);
  const [rescheduleForm, setRescheduleForm] = useState({ newBookingDate: '', newStartTime: '', newDurationMinutes: '' });
  const [transferForm, setTransferForm] = useState({ recipientName: '', recipientEmail: '', recipientPhone: '' });
  const [actionLoading, setActionLoading] = useState(false);
  const [recurringGroupBookings, setRecurringGroupBookings] = useState(null); // null = closed, 'loading' = fetching, [] = empty, [...] = results
  const perPage = 6;

  useEffect(() => {
    setLoading(true);
    Promise.allSettled([
      authService.getSupportContact(),
      bookingService.getCurrentBookings(),
      bookingService.getPastBookings(),
      bookingService.getPendingReviews(),
      bookingService.getMyWaitlist(),
    ])
      .then(([supportRes, currentRes, pastRes, pendingReviewRes, waitlistRes]) => {
        const baseSupport = supportRes.status === 'fulfilled' ? mergeSupportContact(supportRes.value.data.data) : CUSTOMER_SUPPORT;
        setSupportContact(baseSupport);
        setCurrentBookings(currentRes.status === 'fulfilled' ? toArray(currentRes.value?.data?.data) : []);
        setPastBookings(pastRes.status === 'fulfilled' ? toArray(pastRes.value?.data?.data) : []);
        setPendingReviews(pendingReviewRes.status === 'fulfilled' ? toArray(pendingReviewRes.value?.data?.data) : []);
        setWaitlistEntries(waitlistRes.status === 'fulfilled' ? toArray(waitlistRes.value?.data?.data) : []);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      bookingService.getPendingReviews()
        .then((res) => setPendingReviews(toArray(res.data?.data)))
        .catch(() => null);
    }, 30000);
    return () => clearInterval(interval);
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
    PARTIALLY_PAID: 'badge-warning',
    FAILED: 'badge-danger',
    PENDING: 'badge-warning',
    INITIATED: 'badge-warning',
    REFUNDED: 'badge-info',
  }[paymentStatus] || 'badge-warning');

  const paymentLabel = (paymentStatus) => ({
    PARTIALLY_PAID: 'Partially Paid',
    PARTIALLY_REFUNDED: 'Partially Refunded',
  }[paymentStatus] || paymentStatus || 'PENDING');

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
  const support = mergeSupportContact(supportContact, selectedBinge);
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

  const handleCancelBooking = async (bookingRef, refundPct) => {
    const refundNote = refundPct != null ? ` You will receive a ${refundPct}% refund.` : '';
    if (!window.confirm(`Cancel this booking?${refundNote} This cannot be undone.`)) return;
    try {
      await bookingService.cancelBooking(bookingRef);
      toast.success('Booking cancelled');
      setCurrentBookings(prev => prev.map(b => b.bookingRef === bookingRef ? { ...b, status: 'CANCELLED' } : b));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to cancel booking');
    }
  };

  const handleLeaveWaitlist = async (entryId) => {
    if (!window.confirm('Leave the waitlist for this slot?')) return;
    try {
      await bookingService.leaveWaitlist(entryId);
      toast.success('Removed from waitlist');
      setWaitlistEntries(prev => prev.filter(e => e.id !== entryId));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to leave waitlist');
    }
  };

  const openRescheduleModal = (booking) => {
    setRescheduleForm({
      newBookingDate: booking.bookingDate || '',
      newStartTime: booking.startTime ? booking.startTime.substring(0, 5) : '',
      newDurationMinutes: '',
    });
    setRescheduleModal(booking);
  };

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
      const res = await bookingService.rescheduleBooking(rescheduleModal.bookingRef, payload);
      const updated = res.data.data;
      setCurrentBookings(prev => prev.map(b => b.bookingRef === updated.bookingRef ? updated : b));
      toast.success('Booking rescheduled successfully');
      setRescheduleModal(null);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to reschedule booking');
    } finally {
      setActionLoading(false);
    }
  };

  const openTransferModal = (booking) => {
    setTransferForm({ recipientName: '', recipientEmail: '', recipientPhone: '' });
    setTransferModal(booking);
  };

  const handleTransfer = async () => {
    if (!transferForm.recipientName.trim() || !transferForm.recipientEmail.trim()) {
      toast.error('Recipient name and email are required.');
      return;
    }
    setActionLoading(true);
    try {
      const res = await bookingService.transferBooking(transferModal.bookingRef, transferForm);
      const updated = res.data.data;
      setCurrentBookings(prev => prev.map(b => b.bookingRef === updated.bookingRef ? updated : b));
      toast.success('Booking transferred successfully');
      setTransferModal(null);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to transfer booking');
    } finally {
      setActionLoading(false);
    }
  };

  const updateReviewDraft = (bookingRef, patch) => {
    setReviewDrafts((prev) => ({
      ...prev,
      [bookingRef]: {
        rating: prev[bookingRef]?.rating || 0,
        comment: prev[bookingRef]?.comment || '',
        ...patch,
      },
    }));
  };

  const submitReview = async (bookingRef, skipped = false) => {
    const draft = reviewDrafts[bookingRef] || { rating: 0, comment: '' };
    if (!skipped && (!draft.rating || draft.rating < 1)) {
      toast.error('Please select a rating out of 5 or choose Skip.');
      return;
    }

    setSubmittingReviewRef(bookingRef);
    try {
      await bookingService.submitCustomerReview(bookingRef, {
        rating: skipped ? null : draft.rating,
        comment: draft.comment,
        skipped,
      });
      setPendingReviews((prev) => prev.filter((b) => b.bookingRef !== bookingRef));
      toast.success(skipped ? 'Review skipped for now.' : 'Thanks for sharing your review.');
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to submit review.');
    } finally {
      setSubmittingReviewRef('');
    }
  };

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
                <span className={`badge ${paymentBadge(nextBooking.paymentStatus)}`}>{paymentLabel(nextBooking.paymentStatus)}</span>
              </div>
              <strong>{formatAmount(nextBooking.totalAmount)}</strong>
              <div className="customer-hub-inline-actions">
                <Link to={`/booking/${nextBooking.bookingRef}`} className="btn btn-primary btn-sm">View Booking</Link>
                {(nextBooking.paymentStatus !== 'SUCCESS' || (nextBooking.balanceDue > 0.01)) && (
                  <Link to={`/payment/${nextBooking.bookingRef}`} className="btn btn-secondary btn-sm">
                    {nextBooking.balanceDue > 0.01 ? `Pay Balance ${formatAmount(nextBooking.balanceDue)}` : 'Pay Pending Balance'}
                  </Link>
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

      {pendingReviews.length > 0 && (
        <section className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Share your experience</span>
              <h2>Rate completed binges to improve recommendations and service quality</h2>
            </div>
          </div>
          <div className="customer-mini-grid">
            {pendingReviews.map((booking) => {
              const draft = reviewDrafts[booking.bookingRef] || { rating: 0, comment: '' };
              return (
                <article key={booking.bookingRef} className="customer-mini-card">
                  <div>
                    <span className="customer-booking-ref">{booking.bookingRef}</span>
                    <h3>{booking.eventType?.name ?? booking.eventType}</h3>
                    <p>{booking.bookingDate} at {booking.startTime}</p>
                  </div>
                  <div className="customer-review-stars" role="group" aria-label="Rate this booking">
                    {[1, 2, 3, 4, 5].map((star) => (
                      <button
                        key={`${booking.bookingRef}-${star}`}
                        type="button"
                        className={`customer-review-star ${draft.rating >= star ? 'active' : ''}`}
                        onClick={() => updateReviewDraft(booking.bookingRef, { rating: star })}
                      >
                        ★
                      </button>
                    ))}
                  </div>
                  <textarea
                    className="customer-review-input"
                    placeholder="Tell us what stood out (optional)"
                    value={draft.comment}
                    onChange={(event) => updateReviewDraft(booking.bookingRef, { comment: event.target.value })}
                    rows={3}
                  />
                  <div className="customer-mini-card-actions">
                    <button
                      type="button"
                      className="btn btn-primary btn-sm"
                      disabled={submittingReviewRef === booking.bookingRef}
                      onClick={() => submitReview(booking.bookingRef, false)}
                    >
                      {submittingReviewRef === booking.bookingRef ? 'Submitting...' : 'Submit Review'}
                    </button>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      disabled={submittingReviewRef === booking.bookingRef}
                      onClick={() => submitReview(booking.bookingRef, true)}
                    >
                      Skip
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      )}

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
              <p>{support.email ? `Email: ${support.email}` : 'Email: not configured for this venue yet.'}</p>
              <p>{support.phoneDisplay ? `Phone: ${support.phoneDisplay}` : 'Phone: not configured for this venue yet.'}</p>
              <p>{support.whatsappRaw ? `WhatsApp: ${support.whatsappRaw}` : 'WhatsApp: not configured for this venue yet.'}</p>
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
          {waitlistEntries.length > 0 && (
            <button className={`btn btn-sm ${tab === 'waitlist' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab('waitlist')}>Waitlist ({waitlistEntries.length})</button>
          )}
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
      ) : tab === 'waitlist' ? (
        <section className="customer-booking-grid">
          {waitlistEntries.length === 0 ? (
            <div className="card customer-hub-empty">
              <h2>No waitlist entries</h2>
              <p>You'll see your waitlist positions here when a fully-booked slot is joined.</p>
            </div>
          ) : waitlistEntries.map((entry) => (
            <article key={entry.id} className="card customer-booking-card customer-booking-card-rich">
              <div className="customer-booking-topline">
                <span className={`badge ${entry.status === 'OFFERED' ? 'badge-success' : entry.status === 'WAITING' ? 'badge-warning' : 'badge-info'}`}>{entry.status}</span>
              </div>
              <div className="customer-booking-head">
                <div>
                  <span className="customer-booking-ref">Position #{entry.position}</span>
                  <h3>{entry.eventTypeName || 'Event'}</h3>
                </div>
              </div>
              <div className="customer-booking-meta">
                <span><FiCalendar /> {entry.preferredDate}</span>
                <span><FiClock /> {entry.preferredStartTime} for {entry.durationMinutes}m</span>
              </div>
              {entry.status === 'OFFERED' && entry.offerExpiresAt && (
                <p style={{ fontSize: '0.85rem', color: 'var(--success)', fontWeight: 600, margin: '0.5rem 0' }}>
                  A spot opened up! Offer expires at {new Date(entry.offerExpiresAt).toLocaleTimeString()}
                </p>
              )}
              <div className="customer-booking-actions">
                {entry.status === 'OFFERED' && (
                  <Link to="/book" state={{ eventTypeId: entry.eventTypeId, prefillBooking: { eventTypeId: entry.eventTypeId, durationMinutes: entry.durationMinutes, numberOfGuests: entry.numberOfGuests || 1 } }} className="btn btn-primary btn-sm">Book Now</Link>
                )}
                {(entry.status === 'WAITING' || entry.status === 'OFFERED') && (
                  <button type="button" className="btn btn-danger btn-sm" onClick={() => handleLeaveWaitlist(entry.id)}>Leave Waitlist</button>
                )}
              </div>
            </article>
          ))}
        </section>
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
                  <span className={`badge ${paymentBadge(booking.paymentStatus)}`}>{paymentLabel(booking.paymentStatus)}</span>
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
                  {booking.venueRoomName && <span><FiMapPin /> {booking.venueRoomName}</span>}
                </div>

                {(booking.surgeMultiplier > 1 || booking.loyaltyPointsEarned > 0 || booking.loyaltyPointsRedeemed > 0) && (
                  <div className="customer-booking-tags">
                    {booking.surgeMultiplier > 1 && (
                      <span className="customer-booking-tag" style={{ background: '#fef3c7', color: '#92400e' }}>⚡ {booking.surgeLabel || 'Peak'} ({booking.surgeMultiplier}×)</span>
                    )}
                    {booking.loyaltyPointsRedeemed > 0 && (
                      <span className="customer-booking-tag" style={{ background: '#ede9fe', color: '#5b21b6' }}>🎁 −{booking.loyaltyPointsRedeemed} pts</span>
                    )}
                    {booking.loyaltyPointsEarned > 0 && (
                      <span className="customer-booking-tag" style={{ background: '#d1fae5', color: '#059669' }}>⭐ +{booking.loyaltyPointsEarned} pts</span>
                    )}
                  </div>
                )}

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
                  {booking.canCustomerCancel !== false && ['PENDING', 'CONFIRMED'].includes(booking.status) && (
                    <button type="button" className="btn btn-danger btn-sm" disabled={booking.canCustomerCancel === false} title={booking.customerCancelMessage || ''} onClick={() => handleCancelBooking(booking.bookingRef, booking.cancellationRefundPercentage)}>Cancel Booking</button>
                  )}
                  {booking.canCustomerReschedule && (
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => openRescheduleModal(booking)}>
                      <FiRepeat /> Reschedule
                    </button>
                  )}
                  {booking.canCustomerTransfer && (
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => openTransferModal(booking)}>
                      <FiSend /> Transfer
                    </button>
                  )}
                  {(booking.paymentStatus !== 'SUCCESS' || (booking.balanceDue > 0.01)) && booking.status !== 'CANCELLED' && booking.status !== 'COMPLETED' && (
                    <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">
                      {booking.balanceDue > 0.01 ? `Pay Balance ${formatAmount(booking.balanceDue)}` : 'Pay Pending Balance'}
                    </Link>
                  )}
                  {(booking.eventType?.id || booking.eventTypeId) && (
                    <Link to="/book" state={getRepeatBookingState(booking)} className="btn btn-secondary btn-sm">
                      Book Similar <FiArrowRight />
                    </Link>
                  )}
                </div>

                {booking.transferred && (
                  <div className="customer-booking-support-note" style={{ borderLeft: '3px solid var(--info)' }}>
                    <span><FiSend /> Transferred from {booking.originalCustomerName || 'another customer'}</span>
                  </div>
                )}
                {booking.recurringGroupId && (
                  <div className="customer-booking-support-note" style={{ borderLeft: '3px solid var(--primary)' }}>
                    <span><FiRepeat /> Part of recurring series</span>
                    <button type="button" className="btn btn-secondary btn-sm" style={{ marginLeft: '0.5rem', fontSize: '0.75rem' }}
                      onClick={async () => {
                        try {
                          setRecurringGroupBookings('loading');
                          const res = await bookingService.getRecurringGroup(booking.recurringGroupId);
                          setRecurringGroupBookings(toArray(res.data?.data));
                        } catch { toast.error('Failed to load recurring series'); setRecurringGroupBookings(null); }
                      }}>View all in series</button>
                  </div>
                )}
                {booking.rescheduleCount > 0 && (
                  <div className="customer-booking-support-note" style={{ borderLeft: '3px solid var(--warning)' }}>
                    <span><FiRefreshCw /> Rescheduled {booking.rescheduleCount} time{booking.rescheduleCount > 1 ? 's' : ''}</span>
                  </div>
                )}

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
                {booking.canCustomerCancel === false && booking.status === 'PENDING' && booking.paymentStatus === 'PENDING' && (
                  <div className="customer-booking-support-note">
                    <span><FiShield /> {booking.customerCancelMessage || 'Cancellation is not allowed for this booking right now.'}</span>
                  </div>
                )}
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

      {/* Recurring Group Modal */}
      {recurringGroupBookings !== null && (
        <div className="modal-overlay" onClick={() => setRecurringGroupBookings(null)}>
          <div className="modal-content card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '560px', padding: '2rem' }}>
            <h2 style={{ marginBottom: '1rem' }}><FiRepeat /> Recurring Series</h2>
            {recurringGroupBookings === 'loading' ? (
              <p style={{ color: 'var(--text-muted)' }}>Loading...</p>
            ) : recurringGroupBookings.length === 0 ? (
              <p style={{ color: 'var(--text-muted)' }}>No bookings found in this series.</p>
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
      {rescheduleModal && (
        <div className="modal-overlay" onClick={() => !actionLoading && setRescheduleModal(null)}>
          <div className="modal-content card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '480px', padding: '2rem' }}>
            <h2 style={{ marginBottom: '0.5rem' }}>Reschedule Booking</h2>
            <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', marginBottom: '1.5rem' }}>
              {rescheduleModal.bookingRef} — {rescheduleModal.eventType?.name ?? rescheduleModal.eventType}
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
              <button className="btn btn-secondary btn-sm" disabled={actionLoading} onClick={() => setRescheduleModal(null)}>Cancel</button>
              <button className="btn btn-primary btn-sm" disabled={actionLoading} onClick={handleReschedule}>
                {actionLoading ? 'Rescheduling...' : 'Confirm Reschedule'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Transfer Modal */}
      {transferModal && (
        <div className="modal-overlay" onClick={() => !actionLoading && setTransferModal(null)}>
          <div className="modal-content card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '480px', padding: '2rem' }}>
            <h2 style={{ marginBottom: '0.5rem' }}>Transfer Booking</h2>
            <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', marginBottom: '1.5rem' }}>
              {transferModal.bookingRef} — {transferModal.eventType?.name ?? transferModal.eventType}
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
              <button className="btn btn-secondary btn-sm" disabled={actionLoading} onClick={() => setTransferModal(null)}>Cancel</button>
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
