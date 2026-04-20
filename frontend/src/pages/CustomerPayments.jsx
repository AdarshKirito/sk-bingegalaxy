import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { bookingService, paymentService } from '../services/endpoints';
import { SkeletonGrid } from '../components/ui/Skeleton';
import SEO from '../components/SEO';
import { FiAlertCircle, FiCalendar, FiCheckCircle, FiCreditCard, FiFilter, FiRefreshCw, FiSearch, FiTrendingUp } from 'react-icons/fi';
import DOMPurify from 'dompurify';
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
  const searchInputRef = useRef(null);

  const applySearch = () => {
    const trimmed = query.trim();
    // Exact match on booking ref or transaction ID → navigate directly
    const upper = trimmed.toUpperCase();
    const matchedBooking = bookings.find(b => b.bookingRef?.toUpperCase() === upper);
    const matchedPayment = payments.find(p =>
      p.bookingRef?.toUpperCase() === upper || p.transactionId?.toUpperCase() === upper
    );
    if (trimmed && (matchedBooking || matchedPayment)) {
      const ref = matchedBooking?.bookingRef || matchedPayment?.bookingRef;
      navigate(`/payment/${ref}`);
      return;
    }
    setAppliedQuery(trimmed);
  };

  const handleSearchKey = (event) => {
    if (event.key === 'Enter') applySearch();
  };


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
    const searchTarget = [payment.transactionId, payment.bookingRef, booking?.eventType?.name || booking?.eventType, payment.paymentMethod, payment.status]
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

      {unpaidBookings.length > 0 && !loading && (
        <section className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Unpaid bookings</span>
              <h2>Reservations still waiting on payment</h2>
            </div>
            <Link to="/my-bookings" className="customer-hub-inline-link">Open full timeline</Link>
          </div>

          <div className="customer-mini-grid">
            {unpaidBookings.slice(0, 4).map(booking => (
              <article key={booking.bookingRef} className="customer-mini-card">
                <div>
                  <span className="customer-booking-ref">{booking.bookingRef}</span>
                  <h3>{booking.eventType?.name ?? booking.eventType}</h3>
                  <p>{booking.bookingDate} at {booking.startTime}</p>
                </div>
                <div className="customer-mini-card-actions">
                  <strong>{formatAmount(booking.totalAmount)}</strong>
                  <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">Pay Now</Link>
                </div>
              </article>
            ))}
          </div>
        </section>
      )}

      <section className="customer-hub-toolbar card">
        <div className="customer-hub-filters customer-hub-filters-wide">
          <label className="customer-hub-search">
            <FiSearch />
            <input
              ref={searchInputRef}
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={handleSearchKey}
              placeholder="Search or enter exact booking ref / transaction ID"
            />
          </label>

          <label className="customer-hub-select">
            <FiFilter />
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
              <option value="ALL">All statuses</option>
              <option value="SUCCESS">Successful</option>
              <option value="INITIATED">Initiated</option>
              <option value="PARTIALLY_REFUNDED">Partially Refunded</option>
              <option value="REFUNDED">Refunded</option>
              <option value="FAILED">Failed</option>
            </select>
          </label>

          <label className="customer-hub-date-field">
            <span><FiCalendar /> From</span>
            <input
              type="date"
              value={fromDate}
              max={toDate || undefined}
              onChange={(event) => setFromDate(event.target.value)}
            />
          </label>

          <label className="customer-hub-date-field">
            <span><FiCalendar /> To</span>
            <input
              type="date"
              value={toDate}
              min={fromDate || undefined}
              onChange={(event) => setToDate(event.target.value)}
            />
          </label>

          <button className="btn btn-primary btn-sm" onClick={applySearch}>
            <FiSearch /> Search
          </button>

          <button
            className="btn btn-secondary btn-sm"
            onClick={() => { setQuery(''); setAppliedQuery(''); setStatusFilter('ALL'); setFromDate(''); setToDate(''); }}
          >
            <FiRefreshCw /> Reset
          </button>
        </div>
        {appliedQuery && (
          <p style={{ marginTop: '0.55rem', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Showing results for <strong style={{ color: 'var(--text)' }}>"{appliedQuery}"</strong>
            {' '}&mdash; <button style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', padding: 0, fontSize: 'inherit' }} onClick={() => { setQuery(''); setAppliedQuery(''); }}>Clear</button>
          </p>
        )}
      </section>

      {loading ? (
        <SkeletonGrid count={4} columns={2} />
      ) : filteredPayments.length === 0 ? (
        <div className="card customer-hub-empty">
          <h2>No matching payments</h2>
          <p>Once you initiate or complete payments, the history will appear here.</p>
          <Link to="/book" className="btn btn-primary btn-sm">Start a Booking</Link>
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