import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { bookingService, paymentService } from '../services/endpoints';
import { formatTime12h } from '../utils/format';
import { SkeletonGrid } from '../components/ui/Skeleton';
import SEO from '../components/SEO';
import { FiAlertCircle, FiCalendar, FiCheckCircle, FiCreditCard, FiFilter, FiRefreshCw, FiSearch, FiTrendingUp, FiX } from 'react-icons/fi';
import DOMPurify from 'dompurify';
import { toast } from 'react-toastify';
import './CustomerHub.css';

export default function CustomerPayments() {
  const navigate = useNavigate();
  const [payments, setPayments] = useState([]);
  const [bookings, setBookings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [cancellingRef, setCancellingRef] = useState('');
  const searchInputRef = useRef(null);

  // ── Search semantics ──────────────────────────────────────────
  //  Real-world dashboards (Stripe, Amazon, Notion, Linear) filter
  //  *as you type* with a small debounce — no "Search" button click
  //  required.  We still expose Enter to commit immediately and a
  //  clear (×) button to reset.  An explicit jump-to-booking chip
  //  surfaces when the query exactly matches a known ref, so power
  //  users can navigate directly without an automatic redirect.
  useEffect(() => {
    const trimmed = query.trim();
    const handle = setTimeout(() => setAppliedQuery(trimmed), 220);
    return () => clearTimeout(handle);
  }, [query]);

  const applySearch = () => {
    setAppliedQuery(query.trim());
  };

  const handleSearchKey = (event) => {
    if (event.key === 'Enter') applySearch();
  };

  const clearSearch = () => {
    setQuery('');
    setAppliedQuery('');
    if (searchInputRef.current) searchInputRef.current.focus();
  };

  // ── Cancel an unpaid (PENDING) reservation ────────────────────
  // Re-uses the existing customer cancel endpoint which only allows
  // cancellation of bookings still in PENDING status (matches the
  // "unpaid reservations" panel exactly).  Refund applicability is
  // gated server-side by the binge's refundOnPendingPaymentCancel flag.
  const handleCancelUnpaid = async (booking) => {
    if (!booking?.bookingRef) return;
    const ok = window.confirm(
      `Cancel reservation ${booking.bookingRef}?\n\n` +
      `This will free up the slot. Any refund (if applicable) will follow your venue's cancellation policy.`
    );
    if (!ok) return;
    setCancellingRef(booking.bookingRef);
    try {
      await bookingService.cancelBooking(booking.bookingRef);
      toast.success(`Reservation ${booking.bookingRef} cancelled.`);
      // Refresh both lists so the row drops out of "unpaid".
      const toArray = (val) => Array.isArray(val) ? val : [];
      const [pay, cur, past] = await Promise.allSettled([
        paymentService.getMyPayments(),
        bookingService.getCurrentBookings(),
        bookingService.getPastBookings(),
      ]);
      if (pay.status === 'fulfilled') setPayments(toArray(pay.value?.data?.data));
      const c = cur.status === 'fulfilled' ? toArray(cur.value?.data?.data) : [];
      const p = past.status === 'fulfilled' ? toArray(past.value?.data?.data) : [];
      setBookings([...c, ...p]);
    } catch (err) {
      const msg = err?.response?.data?.message || err?.message || 'Failed to cancel reservation.';
      toast.error(msg);
    } finally {
      setCancellingRef('');
    }
  };

  // Case-insensitive exact match on booking ref / transaction id — used
  // only to render a "Jump to booking" chip, never to redirect automatically.
  const exactMatchRef = (() => {
    const trimmed = query.trim();
    if (!trimmed) return '';
    const upper = trimmed.toUpperCase();
    const matchedBooking = bookings.find(b => b.bookingRef?.toUpperCase() === upper);
    const matchedPayment = payments.find(p =>
      p.bookingRef?.toUpperCase() === upper || p.transactionId?.toUpperCase() === upper
    );
    return matchedBooking?.bookingRef || matchedPayment?.bookingRef || '';
  })();


  useEffect(() => {
    setLoading(true);
    Promise.allSettled([
      paymentService.getMyPayments(),
      bookingService.getCurrentBookings(),
      bookingService.getPastBookings(),
    ]).then(([paymentRes, currentRes, pastRes]) => {
      const toArray = (val) => Array.isArray(val) ? val : [];
      setPayments(paymentRes.status === 'fulfilled' ? toArray(paymentRes.value?.data?.data) : []);
      const currentBookings = currentRes.status === 'fulfilled' ? toArray(currentRes.value?.data?.data) : [];
      const pastBookings = pastRes.status === 'fulfilled' ? toArray(pastRes.value?.data?.data) : [];
      setBookings([...currentBookings, ...pastBookings]);
    }).finally(() => setLoading(false));
  }, []);

  // ── Live sync: refetch payments + reservations whenever the user
  //    returns to the tab, so an admin-recorded charge / refund shows
  //    up without a manual refresh.  Matches how mainstream SaaS
  //    dashboards keep cached lists fresh.
  useEffect(() => {
    const toArray = (val) => Array.isArray(val) ? val : [];
    const refresh = () => {
      Promise.allSettled([
        paymentService.getMyPayments(),
        bookingService.getCurrentBookings(),
        bookingService.getPastBookings(),
      ]).then(([paymentRes, currentRes, pastRes]) => {
        if (paymentRes.status === 'fulfilled') setPayments(toArray(paymentRes.value?.data?.data));
        const cur = currentRes.status === 'fulfilled' ? toArray(currentRes.value?.data?.data) : [];
        const past = pastRes.status === 'fulfilled' ? toArray(pastRes.value?.data?.data) : [];
        if (currentRes.status === 'fulfilled' || pastRes.status === 'fulfilled') {
          setBookings([...cur, ...past]);
        }
      });
    };
    const onFocus = () => refresh();
    const onVisibility = () => { if (!document.hidden) refresh(); };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  // "/" keyboard shortcut to focus the search bar (GitHub / Slack / Linear pattern).
  useEffect(() => {
    const onKey = (event) => {
      const target = event.target;
      const isFormField = target instanceof HTMLElement
        && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable || target.tagName === 'SELECT');
      if (event.key === '/' && !isFormField && !event.metaKey && !event.ctrlKey && !event.altKey) {
        event.preventDefault();
        searchInputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const bookingByRef = bookings.reduce((lookup, booking) => {
    lookup[booking.bookingRef] = booking;
    return lookup;
  }, {});

  const statusBadge = (status) => ({
    SUCCESS: 'badge-success',
    PARTIALLY_PAID: 'badge-warning',
    PARTIALLY_REFUNDED: 'badge-info',
    REFUNDED: 'badge-info',
    FAILED: 'badge-danger',
    INITIATED: 'badge-warning',
  }[status] || 'badge-warning');

  const statusLabel = (status) => ({
    SUCCESS: 'Paid',
    PARTIALLY_PAID: 'Partially Paid',
    PARTIALLY_REFUNDED: 'Partially Refunded',
    REFUNDED: 'Refunded',
    FAILED: 'Failed',
    INITIATED: 'In Progress',
    PENDING: 'Pending',
  }[status] ?? (status?.replace(/_/g, ' ') || 'Pending'));

  const formatAmount = (amount) => `₹${Number(amount || 0).toLocaleString()}`;

  const formatDate = (ts) => ts
    ? new Date(ts).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
    : 'Date unavailable';

  const formatMethodLabel = (method) => ({
    UPI: 'UPI',
    CARD: 'Card',
    BANK_TRANSFER: 'Bank Transfer',
    WALLET: 'Wallet',
    CASH: 'Cash',
  }[method] ?? (method?.replace(/_/g, ' ') || 'Method pending'));
  const sortedPayments = [...payments].sort((left, right) => new Date(right.createdAt || right.paidAt || 0) - new Date(left.createdAt || left.paidAt || 0));
  const filteredPayments = sortedPayments.filter(payment => {
    const booking = bookingByRef[payment.bookingRef] || null;
    const eventName = booking?.eventType?.name || booking?.eventType || '';
    const eventDescription = booking?.eventType?.description || '';
    const searchTarget = [payment.transactionId, payment.bookingRef, eventName, eventDescription, booking?.customerName, booking?.notes, payment.paymentMethod, payment.status]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    const matchesQuery = !appliedQuery || searchTarget.includes(appliedQuery.toLowerCase());
    const matchesStatus = statusFilter === 'ALL' || payment.status === statusFilter;
    const paymentTs = new Date(payment.createdAt || payment.paidAt || 0);
    const matchesFrom = !fromDate || paymentTs >= new Date(fromDate);
    const matchesTo = !toDate || paymentTs <= new Date(toDate + 'T23:59:59');
    return matchesQuery && matchesStatus && matchesFrom && matchesTo;
  });

  const initiatedPayments = sortedPayments.filter(payment => payment.status === 'INITIATED');
  const successfulPayments = sortedPayments.filter(payment => payment.status === 'SUCCESS');
  const refundedPayments = sortedPayments.filter(payment => payment.status === 'REFUNDED' || payment.status === 'PARTIALLY_REFUNDED');
  const failedPayments = sortedPayments.filter(payment => payment.status === 'FAILED');
  const totalPaid = sortedPayments
    .filter(payment => payment.status === 'SUCCESS' || payment.status === 'PARTIALLY_PAID')
    .reduce((sum, payment) => sum + (payment.amount || 0), 0);
  const unpaidBookings = bookings.filter(booking =>
    !['CANCELLED', 'COMPLETED', 'NO_SHOW'].includes(booking.status) &&
    !['SUCCESS', 'REFUNDED'].includes(booking.paymentStatus)
  );

  // Reservations panel must react to the same search + date filters as
  // payments — otherwise typing a booking ref that has no payment row yet
  // makes the card disappear, which reads like the reservations "didn't
  // fetch".  Mirrors how Stripe's Dashboard filters Customers + Payments
  // from the same top-of-page search box.
  const bookingSearchTarget = (booking) => [
    booking.bookingRef,
    booking.eventType?.name || booking.eventType,
    booking.eventType?.description,
    booking.status,
    booking.paymentStatus,
    booking.customerName,
    booking.notes,
  ].filter(Boolean).join(' ').toLowerCase();

  const filteredUnpaidBookings = unpaidBookings.filter(booking => {
    const matchesQuery = !appliedQuery || bookingSearchTarget(booking).includes(appliedQuery.toLowerCase());
    const bookingTs = booking.bookingDate ? new Date(booking.bookingDate) : null;
    const matchesFrom = !fromDate || !bookingTs || bookingTs >= new Date(fromDate);
    const matchesTo = !toDate || !bookingTs || bookingTs <= new Date(toDate + 'T23:59:59');
    return matchesQuery && matchesFrom && matchesTo;
  });

  // Fall back: when a query is typed but no payment rows match, surface ANY
  // matching booking (including paid / past) so the user is never staring at
  // an empty state when their search clearly references a real booking.
  // Mirrors how Stripe Dashboard search returns Customers when no Charges hit.
  const queryMatchedBookings = appliedQuery
    ? bookings.filter(b => bookingSearchTarget(b).includes(appliedQuery.toLowerCase()))
    : [];

  return (
    <div className="container customer-hub">
      <SEO title="Payments" description="Track payment history, in-progress transactions, and unpaid bookings in one customer payments hub." />

      <section className="customer-hub-hero">
        <div className="customer-hub-copy">
          <span className="customer-hub-kicker">Payments Hub</span>
          <h1>See every transaction, retry anything in progress, and keep unpaid bookings visible.</h1>
          <p>This page gives the payments card on the dashboard a full destination instead of just a shortcut.</p>
          <div className="customer-hub-actions">
            <Link to="/my-bookings" className="btn btn-secondary">Open Booking Timeline</Link>
            <Link to="/book" className="btn btn-primary">Create New Booking</Link>
          </div>
        </div>

        <aside className="customer-hub-highlight card">
          <span className="customer-hub-panel-label">Live snapshot</span>
          <h2>{loading ? 'Loading payments...' : initiatedPayments.length > 0 ? `${initiatedPayments.length} payment${initiatedPayments.length === 1 ? '' : 's'} in progress` : `${formatAmount(totalPaid)} collected total`}</h2>
          <p>{loading ? 'Pulling your transaction history.' : initiatedPayments.length > 0 ? 'Continue the in-progress transaction or check the full booking timeline.' : 'No active transactions right now — all payments are settled or recorded.'}</p>
          <div className="customer-hub-highlight-meta">
            <span className="badge badge-success">{successfulPayments.length} successful</span>
            <span className="badge badge-warning">{unpaidBookings.length} unpaid bookings</span>
          </div>
          <strong>{loading ? '–' : formatAmount(totalPaid)}</strong>
          <div className="customer-hub-inline-actions">
            <Link to="/my-bookings" className="btn btn-secondary btn-sm">View Reservations</Link>
            {initiatedPayments[0]?.bookingRef && <Link to={`/payment/${initiatedPayments[0].bookingRef}`} className="btn btn-primary btn-sm">Continue Latest</Link>}
          </div>
        </aside>
      </section>

      <section className="customer-hub-stats">
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiCheckCircle /></span>
          <span className="customer-hub-stat-label">Successful</span>
          <strong>{loading ? '–' : successfulPayments.length}</strong>
          <p>Transactions completed without follow-up.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiTrendingUp /></span>
          <span className="customer-hub-stat-label">Total Paid</span>
          <strong>{loading ? '–' : formatAmount(totalPaid)}</strong>
          <p>Cumulative amount across settled payments.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiRefreshCw /></span>
          <span className="customer-hub-stat-label">Refunded</span>
          <strong>{loading ? '–' : refundedPayments.length}</strong>
          <p>Refunded or partially refunded transactions.</p>
        </article>
        <article className="customer-hub-stat card">
          <span className="customer-hub-stat-icon"><FiAlertCircle /></span>
          <span className="customer-hub-stat-label">Failed</span>
          <strong>{loading ? '–' : failedPayments.length}</strong>
          <p>Payments that need another attempt.</p>
        </article>
      </section>

      {!loading && filteredUnpaidBookings.length > 0 && (
        <section className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Unpaid reservations</span>
              <h2>
                {appliedQuery
                  ? `Matching unpaid reservations (${filteredUnpaidBookings.length})`
                  : 'Reservations still waiting on payment'}
              </h2>
            </div>
            <Link to="/my-bookings" className="customer-hub-inline-link">Open full timeline</Link>
          </div>

          <div className="customer-mini-grid">
            {(appliedQuery ? filteredUnpaidBookings : filteredUnpaidBookings.slice(0, 4)).map(booking => (
              <article key={booking.bookingRef} className="customer-mini-card">
                <div>
                  <span className="customer-booking-ref">{booking.bookingRef}</span>
                  <h3>{booking.eventType?.name ?? booking.eventType}</h3>
                  <p>{booking.bookingDate} at {formatTime12h(booking.startTime)}</p>
                </div>
                <div className="customer-mini-card-actions">
                  <strong>{formatAmount(booking.totalAmount)}</strong>
                  <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">Pay Now</Link>
                  {booking.status === 'PENDING' && (
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      style={{ marginLeft: '0.5rem' }}
                      disabled={cancellingRef === booking.bookingRef}
                      onClick={() => handleCancelUnpaid(booking)}
                    >
                      {cancellingRef === booking.bookingRef ? 'Cancelling…' : 'Cancel'}
                    </button>
                  )}
                </div>
              </article>
            ))}
          </div>
        </section>
      )}

      <section className="customer-hub-toolbar card" data-testid="customer-payments-toolbar">
        <div className="customer-hub-toolbar-row">
          <label className="customer-hub-search">
            <FiSearch aria-hidden="true" />
            <input
              ref={searchInputRef}
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={handleSearchKey}
              placeholder="Search booking ref, transaction ID, event…"
              aria-label="Search payments"
            />
            {query ? (
              <button
                type="button"
                className="customer-hub-search-clear"
                onClick={clearSearch}
                aria-label="Clear search"
                title="Clear search"
              >
                <FiX />
              </button>
            ) : (
              <kbd className="customer-hub-kbd" aria-hidden="true">/</kbd>
            )}
          </label>
        </div>

        <div className="customer-hub-filters customer-hub-filters-wide">
          <label className="customer-hub-select" data-active={statusFilter !== 'ALL'}>
            <FiFilter />
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} aria-label="Filter by payment status">
              <option value="ALL">All statuses</option>
              <option value="SUCCESS">Successful</option>
              <option value="INITIATED">Initiated</option>
              <option value="PARTIALLY_REFUNDED">Partially Refunded</option>
              <option value="REFUNDED">Refunded</option>
              <option value="FAILED">Failed</option>
            </select>
          </label>

          <label className="customer-hub-date-field" data-active={!!fromDate}>
            <span><FiCalendar /> From</span>
            <input
              type="date"
              value={fromDate}
              max={toDate || undefined}
              onChange={(event) => setFromDate(event.target.value)}
              aria-label="From date"
            />
          </label>

          <label className="customer-hub-date-field" data-active={!!toDate}>
            <span><FiCalendar /> To</span>
            <input
              type="date"
              value={toDate}
              min={fromDate || undefined}
              onChange={(event) => setToDate(event.target.value)}
              aria-label="To date"
            />
          </label>
        </div>

        {(appliedQuery || statusFilter !== 'ALL' || fromDate || toDate) && (
          <div className="customer-hub-summary">
            <span className="customer-hub-summary-count">
              <strong>{filteredPayments.length}</strong>
              {filteredPayments.length === 1 ? ' payment' : ' payments'}
              {appliedQuery && (
                <> matching <strong>“{appliedQuery}”</strong></>
              )}
              {exactMatchRef && (
                <>
                  {' · '}
                  <button
                    type="button"
                    className="customer-hub-clear-link"
                    onClick={() => navigate(`/payment/${exactMatchRef}`)}
                  >
                    Open {exactMatchRef} →
                  </button>
                </>
              )}
            </span>
            <span className="customer-hub-summary-actions">
              {((statusFilter !== 'ALL' ? 1 : 0) + (fromDate ? 1 : 0) + (toDate ? 1 : 0)) > 0 && (
                <span className="customer-hub-active-pill">
                  <FiFilter aria-hidden="true" />
                  {(statusFilter !== 'ALL' ? 1 : 0) + (fromDate ? 1 : 0) + (toDate ? 1 : 0)}{' '}
                  {((statusFilter !== 'ALL' ? 1 : 0) + (fromDate ? 1 : 0) + (toDate ? 1 : 0)) === 1 ? 'filter' : 'filters'}
                </span>
              )}
              <button
                type="button"
                className="customer-hub-clear-link"
                onClick={() => { setQuery(''); setAppliedQuery(''); setStatusFilter('ALL'); setFromDate(''); setToDate(''); }}
              >
                <FiRefreshCw aria-hidden="true" style={{ marginRight: '0.3rem', verticalAlign: '-2px' }} /> Clear all
              </button>
            </span>
          </div>
        )}
      </section>

      {loading ? (
        <SkeletonGrid count={4} columns={2} />
      ) : filteredPayments.length === 0 ? (
        <div className="card customer-hub-empty">
          <h2>{appliedQuery ? `No payments match "${appliedQuery}"` : 'No matching payments'}</h2>
          {appliedQuery && queryMatchedBookings.length > 0 ? (
            <>
              <p>
                But {queryMatchedBookings.length === 1 ? 'a booking matches' : `${queryMatchedBookings.length} bookings match`} your search. Open one to view its payment history.
              </p>
              <div className="customer-mini-grid" style={{ marginTop: '1rem' }}>
                {queryMatchedBookings.slice(0, 6).map(booking => (
                  <article key={booking.bookingRef} className="customer-mini-card">
                    <div>
                      <span className="customer-booking-ref">{booking.bookingRef}</span>
                      <h3>{booking.eventType?.name ?? booking.eventType}</h3>
                      <p>{booking.bookingDate} at {formatTime12h(booking.startTime)}</p>
                    </div>
                    <div className="customer-mini-card-actions">
                      <strong>{formatAmount(booking.totalAmount)}</strong>
                      {['SUCCESS', 'REFUNDED', 'PARTIALLY_REFUNDED'].includes(booking.paymentStatus) ? (
                        <Link to={`/booking/${booking.bookingRef}`} className="btn btn-secondary btn-sm">View Booking</Link>
                      ) : (
                        <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">Pay Now</Link>
                      )}
                    </div>
                  </article>
                ))}
              </div>
            </>
          ) : (
            <>
              <p>Once you initiate or complete payments, the history will appear here.</p>
              <Link to="/book" className="btn btn-primary btn-sm">Start a Booking</Link>
            </>
          )}
        </div>
      ) : (
        <div className="customer-payment-grid">
          {filteredPayments.map(payment => {
            const booking = bookingByRef[payment.bookingRef] || null;
            return (
              <article key={payment.transactionId || `${payment.bookingRef}-${payment.id}`} className="card customer-payment-card">
                <div className="customer-booking-topline">
                  <span className={`badge ${statusBadge(payment.status)}`}>{statusLabel(payment.status)}</span>
                  {payment.bookingRef && (
                    <span className="customer-booking-ref">Booking {payment.bookingRef}</span>
                  )}
                </div>

                <div className="customer-payment-head">
                  <div>
                    <h3>{booking?.eventType?.name || booking?.eventType || 'Booking payment'}</h3>
                    <p className="customer-flow-mono">
                      {payment.transactionId || 'ID assigned after payment'}
                    </p>
                  </div>
                  <strong>{formatAmount(payment.amount)}</strong>
                </div>

                <div className="customer-payment-meta">
                  <span><FiCreditCard /> {formatMethodLabel(payment.paymentMethod)}</span>
                  <span>{formatDate(payment.createdAt)}</span>
                </div>

                {payment.failureReason && (
                  <p className="customer-payment-error">{DOMPurify.sanitize(payment.failureReason, { ALLOWED_TAGS: [] })}</p>
                )}

                <div className="customer-booking-actions">
                  {payment.status === 'INITIATED' && payment.bookingRef && (
                    <Link to={`/payment/${payment.bookingRef}`} className="btn btn-primary btn-sm">Continue Payment</Link>
                  )}
                  {payment.status === 'FAILED' && payment.bookingRef && (
                    <Link to={`/payment/${payment.bookingRef}`} className="btn btn-primary btn-sm">Retry Payment</Link>
                  )}
                  {payment.bookingRef && (
                    <Link to={`/booking/${payment.bookingRef}`} className="btn btn-secondary btn-sm">View Booking</Link>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}