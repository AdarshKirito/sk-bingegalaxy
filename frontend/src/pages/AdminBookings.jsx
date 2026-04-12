import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { adminService, bookingService, paymentService } from '../services/endpoints';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import DOMPurify from 'dompurify';
import Pagination from '../components/ui/Pagination';
import './AdminBookings.css';

const TABS = [
  { key: 'today', label: "Operational Day" },
  { key: 'upcoming', label: 'Upcoming' },
  { key: 'all', label: 'All Bookings' },
  { key: 'byDate', label: 'By Date' },
  { key: 'byStatus', label: 'By Status' },
];

const TODAY_SUB_TABS = [
  { key: 'all', label: 'All Today' },
  { key: 'pending', label: 'Pending' },
  { key: 'confirmed', label: 'Confirmed' },
  { key: 'ready', label: 'Ready (Pending + Confirmed)' },
  { key: 'checkedIn', label: 'Checked In' },
  { key: 'completed', label: 'Completed' },
  { key: 'cancelled', label: 'Cancelled' },
  { key: 'pendingPayment', label: 'Pending Payment' },
];

const STATUSES = ['PENDING', 'CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW'];

const PAGE_SIZE = 10;

const statusBadge = (s) => ({
  PENDING: 'badge-warning', CONFIRMED: 'badge-success', CANCELLED: 'badge-danger',
  COMPLETED: 'badge-info', CHECKED_IN: 'badge-success', NO_SHOW: 'badge-danger',
}[s] || 'badge-info');

const paymentBadge = (s) => ({
  SUCCESS: 'badge-success', PENDING: 'badge-warning', INITIATED: 'badge-warning',
  PARTIALLY_PAID: 'badge-warning', PARTIALLY_REFUNDED: 'badge-info',
  REFUNDED: 'badge-info', FAILED: 'badge-danger',
}[s] || 'badge-danger');

const todayISO = () => new Date().toISOString().slice(0, 10);

export default function AdminBookings() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  // Read deep-link params from dashboard stat card clicks
  const initTab = searchParams.get('tab') || 'today';
  const initStatus = searchParams.get('status') || 'PENDING';
  const initSub = searchParams.get('sub') || 'ready';

  const [activeTab, setActiveTab] = useState(initTab);
  const [todaySubTab, setTodaySubTab] = useState(initSub);
  const [bookings, setBookings] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);

  // Filters
  const [search, setSearch] = useState('');
  const [isSearchActive, setIsSearchActive] = useState(false);
  const [filterDate, setFilterDate] = useState(todayISO());
  const [filterStatus, setFilterStatus] = useState(initStatus);

  // Today stats
  const [stats, setStats] = useState(null);

  // Operational date (set by last audit; drives "today's arrivals" and check-in availability)
  const [operationalDate, setOperationalDate] = useState(new Date().toISOString().slice(0, 10));

  // Notes modal
  const [notesModal, setNotesModal] = useState({ open: false, booking: null });
  const [notesText, setNotesText] = useState('');
  const [notesSaving, setNotesSaving] = useState(false);

  // Booking detail modal
  const [detailModal, setDetailModal] = useState({ open: false, booking: null, bookingCount: 0 });

  const fetchStats = useCallback(() => {
    adminService.getDashboardStats()
      .then(res => setStats(res.data.data || res.data))
      .catch(() => {});
  }, []);

  const fetchBookings = useCallback(() => {
    setLoading(true);
    let req;
    switch (activeTab) {
      case 'today':         req = adminService.getTodayBookings(page, PAGE_SIZE); break;
      case 'upcoming':      req = adminService.getUpcomingBookings(page, PAGE_SIZE); break;
      case 'byDate':        req = adminService.getBookingsByDate(filterDate, page, PAGE_SIZE); break;
      case 'byStatus':      req = adminService.getBookingsByStatus(filterStatus, page, PAGE_SIZE); break;
      default:              req = adminService.getAllBookings(page, PAGE_SIZE); break;
    }
    req.then(res => {
        const d = res.data.data;
        setBookings(d?.content || []);
        setTotalPages(d?.totalPages || 0);
      })
      .catch(() => { setBookings([]); setTotalPages(0); })
      .finally(() => setLoading(false));
  }, [activeTab, page, filterDate, filterStatus]);

  useEffect(() => { fetchStats(); }, [fetchStats]);
  useEffect(() => {
    const refreshOpDate = () => {
      adminService.getOperationalDate()
        .then(res => {
          const d = res.data.data || res.data;
          if (d?.operationalDate) setOperationalDate(d.operationalDate);
        })
        .catch(() => {});
    };
    refreshOpDate();
    // Refresh operational date every 60 seconds and on tab visibility change
    const interval = setInterval(refreshOpDate, 60000);
    const onVisibility = () => { if (document.visibilityState === 'visible') refreshOpDate(); };
    document.addEventListener('visibilitychange', onVisibility);
    return () => { clearInterval(interval); document.removeEventListener('visibilitychange', onVisibility); };
  }, []);

  useEffect(() => {
    const modalOpen = detailModal.open || notesModal.open;
    if (!modalOpen) return undefined;

    const previousOverflow = document.body.style.overflow;
    const previousPaddingRight = document.body.style.paddingRight;
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;

    document.body.style.overflow = 'hidden';
    if (scrollbarWidth > 0) {
      document.body.style.paddingRight = `${scrollbarWidth}px`;
    }

    return () => {
      document.body.style.overflow = previousOverflow;
      document.body.style.paddingRight = previousPaddingRight;
    };
  }, [detailModal.open, notesModal.open]);

  useEffect(() => {
    if (!isSearchActive) fetchBookings();
  }, [fetchBookings, isSearchActive]);

  const switchTab = (key) => {
    setActiveTab(key);
    setPage(0);
    setSearch('');
    setIsSearchActive(false);
    if (key === 'today') setTodaySubTab('all');
  };

  const handleSearch = async () => {
    if (!search.trim()) { setIsSearchActive(false); fetchBookings(); return; }
    setLoading(true);
    setIsSearchActive(true);
    try {
      const res = await adminService.searchBookings(search);
      const d = res.data.data;
      setBookings(d?.content || (Array.isArray(d) ? d : []));
      setTotalPages(0);
    } catch { setBookings([]); setTotalPages(0); }
    setLoading(false);
  };

  const clearSearch = () => {
    setSearch('');
    setIsSearchActive(false);
  };

  const reload = () => { fetchBookings(); fetchStats(); };

  // Reinstate: navigate to admin booking create with pre-filled data from the original booking
  const handleReinstate = (booking) => {
    if (!confirm(`Reinstate booking ${booking.bookingRef} as a new reservation?`)) return;
    const prefill = {
      customerId: booking.customerId,
      customerName: booking.customerName,
      customerEmail: booking.customerEmail,
      customerPhone: booking.customerPhone,
      eventTypeId: booking.eventType?.id || booking.eventTypeId,
      durationHours: booking.durationHours,
      durationMinutes: booking.durationMinutes || (booking.durationHours * 60),
      numberOfGuests: booking.numberOfGuests || 1,
      specialNotes: booking.specialNotes || '',
      adminNotes: `Reinstated from ${booking.bookingRef}`,
      addOns: (booking.addOns || []).map(a => ({
        addOnId: a.addOnId || a.id,
        quantity: a.quantity || 1,
        price: a.price || 0,
        name: a.name || '',
      })),
    };
    navigate('/admin/book', { state: { reinstate: prefill } });
  };

  // Edit reservation: navigate to booking wizard with existing booking data to change times
  const handleEditReservation = (booking) => {
    const editData = {
      bookingRef: booking.bookingRef,
      customerId: booking.customerId,
      customerName: booking.customerName,
      customerEmail: booking.customerEmail,
      customerPhone: booking.customerPhone,
      eventTypeId: booking.eventType?.id || booking.eventTypeId,
      bookingDate: booking.bookingDate || '',
      startTime: booking.startTime || '',
      durationHours: booking.durationHours,
      durationMinutes: booking.durationMinutes || (booking.durationHours * 60),
      numberOfGuests: booking.numberOfGuests || 1,
      specialNotes: booking.specialNotes || '',
      adminNotes: booking.adminNotes || '',
      addOns: (booking.addOns || []).map(a => ({
        addOnId: a.addOnId || a.id,
        quantity: a.quantity || 1,
        price: a.price || 0,
        name: a.name || '',
      })),
      paymentStatus: booking.paymentStatus,
      status: booking.status,
      totalAmount: booking.totalAmount || 0,
      collectedAmount: booking.collectedAmount || 0,
    };
    navigate('/admin/book', { state: { editBooking: editData } });
  };

  const openNotesModal = (booking) => {
    setNotesText(booking.adminNotes || '');
    setNotesModal({ open: true, booking });
  };

  const handleSaveNotes = async () => {
    setNotesSaving(true);
    try {
      await adminService.updateBooking(notesModal.booking.bookingRef, { adminNotes: notesText });
      toast.success('Notes saved');
      setNotesModal({ open: false, booking: null });
      reload();
    } catch (err) { toast.error(err.response?.data?.message || 'Failed'); }
    finally { setNotesSaving(false); }
  };

  // Open booking detail modal
  const openDetailModal = async (booking) => {
    let count = 0;
    if (booking.customerId) {
      try {
        const res = await adminService.getCustomerBookingCount(booking.customerId);
        count = res.data.data || 0;
      } catch { count = 0; }
    }
    setDetailModal({ open: true, booking, bookingCount: count });
  };

  // Filter today's bookings by sub-tab (client-side)
  const filteredBookings = (() => {
    if (activeTab !== 'today') return bookings;
    switch (todaySubTab) {
      case 'pending': return bookings.filter(b => b.status === 'PENDING');
      case 'confirmed': return bookings.filter(b => b.status === 'CONFIRMED');
      case 'ready': return bookings.filter(b => b.status === 'CONFIRMED' || b.status === 'PENDING');
      case 'checkedIn': return bookings.filter(b => b.status === 'CHECKED_IN');
      case 'completed': return bookings.filter(b => b.status === 'COMPLETED');
      case 'cancelled': return bookings.filter(b => b.status === 'CANCELLED');
      case 'pendingPayment': return bookings.filter(b => b.paymentStatus === 'PENDING');
      default: return bookings;
    }
  })();

  // Styles are now in AdminBookings.css

  return (
    <div className="container">
      <div className="page-header" style={{ marginBottom: '0.5rem' }}>
        <h1>Manage Bookings</h1>
        <p>Manage all reservations, check-ins, and payments</p>
      </div>

      {/* Operational Date Banner + Today Stats Cards */}
      {stats && (
        <div>
          <div className="ab-op-banner">
            <span className="ab-op-banner-label">Operational Day:</span>
            <span className="ab-op-banner-value">{operationalDate}</span>
            <span className="ab-op-banner-hint">All stats below are for this date</span>
          </div>
          <div className="ab-stat-cards">
          {[
            { label: "Total",             val: stats.todayTotal ?? '-',     color: 'var(--primary)' },
            { label: 'Confirmed',          val: stats.todayConfirmed ?? '-', color: 'var(--success)' },
            { label: 'Checked In',         val: stats.todayCheckedIn ?? '-', color: '#3b82f6' },
            { label: 'Pending',            val: stats.todayPending ?? '-',   color: 'var(--warning)' },
            { label: 'Completed',          val: stats.todayCompleted ?? '-', color: '#06b6d4' },
            { label: 'Cancelled',          val: stats.todayCancelled ?? '-', color: 'var(--danger, #e74c3c)' },
            { label: 'Revenue',            val: `₹${(stats.todayRevenue ?? 0).toLocaleString()}`, color: '#10b981' },
            { label: 'Est. Revenue',       val: `₹${(stats.todayEstimatedRevenue ?? 0).toLocaleString()}`, color: '#8b5cf6' },
          ].map(c => (
            <div key={c.label} className="ab-stat-card" style={{ borderLeftColor: c.color }}>
              <div className="ab-stat-card-value" style={{ color: c.color }}>{c.val}</div>
              <div className="ab-stat-card-label">{c.label}</div>
            </div>
          ))}
          </div>
        </div>
      )}

      {/* Tab Bar */}
      <div className={`ab-tabs ${activeTab === 'today' ? 'ab-tabs--no-margin' : 'ab-tabs--margin'}`}>
        {TABS.map(t => (
          <button key={t.key} className={`ab-tab ${activeTab === t.key ? 'active' : ''}`} onClick={() => switchTab(t.key)}>{t.label}</button>
        ))}
      </div>

      {/* Today Sub-Tabs */}
      {activeTab === 'today' && (
        <div className="ab-sub-tabs">
          {TODAY_SUB_TABS.map(st => (
            <button key={st.key}
              className={`ab-sub-tab ${todaySubTab === st.key ? 'active' : ''}`}
              onClick={() => setTodaySubTab(st.key)}>
              {st.label}
            </button>
          ))}
        </div>
      )}

      {/* Filters Row */}
      <div className="ab-filter-row">
        {activeTab === 'byDate' && (
          <input type="date" className="ab-input" value={filterDate} onChange={e => { setFilterDate(e.target.value); setPage(0); }} />
        )}
        {activeTab === 'byStatus' && (
          <select className="ab-input" value={filterStatus} onChange={e => { setFilterStatus(e.target.value); setPage(0); }}>
            {STATUSES.map(s => <option key={s} value={s}>{s.replace('_', ' ')}</option>)}
          </select>
        )}

        <input
          className="ab-input ab-input-search"
          value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Search by ref, name, email, phone..."
          onKeyDown={e => e.key === 'Enter' && handleSearch()}
        />
        <button className="btn btn-primary btn-sm" onClick={handleSearch}>Search</button>
        {isSearchActive && <button className="btn btn-secondary btn-sm" onClick={clearSearch}>Clear</button>}
      </div>

      {/* Booking Table */}
      {loading ? (
        <div className="loading"><div className="spinner"></div></div>
      ) : filteredBookings.length === 0 ? (
        <div className="card ab-empty">
          <p>
            {isSearchActive ? `No bookings found for "${search}"` :
             activeTab === 'today' ? `No bookings for operational day (${operationalDate})` :
             activeTab === 'upcoming' ? "No upcoming bookings" :
             "No bookings found"}
          </p>
        </div>
      ) : (
        <>
          <div className="ab-table-wrap">
            <table className="ab-table">
              <thead>
                <tr>
                  {['Ref', 'Customer', 'Event', 'Date', 'Time', 'Amount', 'Status', 'Payment'].map(h => (
                    <th key={h}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filteredBookings.map(b => (
                  <tr key={b.bookingRef} onClick={() => openDetailModal(b)}>
                    <td><span className="ab-ref">{b.bookingRef}</span></td>
                    <td>
                      <span className="ab-customer-name">{b.customerName || b.customerEmail || 'N/A'}</span><br/>
                      <span className="ab-customer-detail">{b.customerEmail}</span>
                      {b.customerPhone && <><br/><span className="ab-customer-detail">📞 {b.customerPhone}</span></>}
                    </td>
                    <td>{b.eventType?.name ?? b.eventType}</td>
                    <td>{b.bookingDate}</td>
                    <td>{b.startTime} ({(() => { const m = b.durationMinutes || (b.durationHours * 60); const h = Math.floor(m/60); const min = m%60; return h > 0 && min > 0 ? `${h}h ${min}m` : h > 0 ? `${h}h` : `${min}m`; })()})</td>
                    <td className="ab-amount">₹{b.totalAmount?.toLocaleString()}</td>
                    <td><span className={`badge ${statusBadge(b.status)}`}>{b.status?.replace('_', ' ')}</span></td>
                    <td>
                      <span className={`badge ${paymentBadge(b.paymentStatus)}`}>
                        {b.paymentStatus?.replace('_', ' ')}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div style={{ marginTop: '1.5rem' }}>
              <Pagination page={page + 1} totalPages={totalPages} onPageChange={(p) => setPage(p - 1)} />
            </div>
          )}
        </>
      )}

      {/* Booking Detail Modal (Editable) */}
      {detailModal.open && detailModal.booking && (
        <div className="ab-modal-overlay" onClick={() => setDetailModal({ open: false, booking: null, bookingCount: 0 })}>
          <div className="card ab-modal-card"
               onClick={e => e.stopPropagation()}>
            <div className="ab-modal-header">
              <h3>Reservation Details</h3>
              <button className="ab-modal-close"
                onClick={() => setDetailModal({ open: false, booking: null, bookingCount: 0 })}>×</button>
            </div>

            <div className="ab-modal-body">
            <DetailModalTabs
              booking={detailModal.booking}
              bookingCount={detailModal.bookingCount}
              operationalDate={operationalDate}
              onAction={(updatedBooking) => { reload(); if (updatedBooking) setDetailModal(dm => ({ ...dm, booking: updatedBooking })); }}
              onSaved={() => { reload(); setDetailModal({ open: false, booking: null, bookingCount: 0 }); }}
              onReinstate={handleReinstate}
              onEditReservation={handleEditReservation}
              onClose={() => setDetailModal({ open: false, booking: null, bookingCount: 0 })}/>
            </div>
          </div>
        </div>
      )}

      {/* Admin Notes Modal */}
      {notesModal.open && (
        <div className="ab-modal-overlay">
          <div className="card ab-notes-modal">
            <h3 style={{ marginBottom: '0.5rem' }}>Admin Notes</h3>
            <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '1rem' }}>
              {notesModal.booking?.bookingRef} — {notesModal.booking?.customerName}
            </p>
            <textarea
              value={notesText}
              onChange={e => setNotesText(e.target.value)}
              rows={5}
              placeholder="Internal notes (not visible to customer)..."
            />
            <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
              <button className="btn btn-primary btn-sm" onClick={handleSaveNotes} disabled={notesSaving}>
                {notesSaving ? 'Saving...' : 'Save Notes'}
              </button>
              <button className="btn btn-secondary btn-sm" onClick={() => setNotesModal({ open: false, booking: null })}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function DetailModalTabs({ booking: initialBooking, bookingCount, operationalDate, onAction, onSaved, onReinstate, onEditReservation, onClose }) {
  const navigate = useNavigate();
  const { isSuperAdmin } = useAuth();
  const [tab, setTab] = useState('customer');
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [payments, setPayments] = useState([]);
  const [paymentsLoading, setPaymentsLoading] = useState(false);
  const [refundModal, setRefundModal] = useState({ open: false, payment: null });
  const [refundAmount, setRefundAmount] = useState('');
  const [refundReason, setRefundReason] = useState('');
  const [refunding, setRefunding] = useState(false);
  const [refundsByPayment, setRefundsByPayment] = useState({});
  const [actionLoading, setActionLoading] = useState(false);
  const [b, setB] = useState(initialBooking);
  // Check-in is only shown for bookings on the current operational date
  const isTodayBooking = b.bookingDate === (operationalDate || new Date().toISOString().slice(0, 10));

  // Event log state
  const [eventLog, setEventLog] = useState([]);
  const [eventLogPage, setEventLogPage] = useState(0);
  const [eventLogTotal, setEventLogTotal] = useState(0);
  const [eventLogLoading, setEventLogLoading] = useState(false);
  const [replaying, setReplaying] = useState(false);

  // Cancel modal state
  const [cancelModal, setCancelModal] = useState({ open: false });
  const [cancelRefundAmount, setCancelRefundAmount] = useState('');
  const [cancelWithRefund, setCancelWithRefund] = useState(false);

  // Record cash payment state
  const [recordingCash, setRecordingCash] = useState(false);
  const [paymentsLoaded, setPaymentsLoaded] = useState(false);
  const [showAddPayment, setShowAddPayment] = useState(false);
  const [addPaymentForm, setAddPaymentForm] = useState({ amount: '', method: 'CASH', notes: '' });
  const [addingPayment, setAddingPayment] = useState(false);
  const [changeMethodFor, setChangeMethodFor] = useState(null);
  const [changeMethodNewMethod, setChangeMethodNewMethod] = useState('CASH');
  const [changingMethod, setChangingMethod] = useState(false);

  // Price adjustment state
  const [adjustingPrices, setAdjustingPrices] = useState(false);
  const [priceForm, setPriceForm] = useState({
    baseAmount: '', addOnAmount: '', guestAmount: '', reason: ''
  });
  const [savingPrices, setSavingPrices] = useState(false);

  const [editForm, setEditForm] = useState({
    specialNotes: b.specialNotes || '',
    adminNotes: b.adminNotes || '',
  });

  // Silently pre-fetch payments on mount for cash booking detection (so warning banner shows immediately)
  useEffect(() => {
    if (b.bookingRef && b.paymentStatus === 'SUCCESS') {
      paymentService.getByBooking(b.bookingRef)
        .then(res => { setPayments(res.data.data || []); setPaymentsLoaded(true); })
        .catch(() => {});
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Fetch payments when Payment tab is opened, or when booking changes
  useEffect(() => {
    if (tab === 'payment' && b.bookingRef) {
      setPaymentsLoading(true);
      paymentService.getByBooking(b.bookingRef)
        .then(async res => {
          const pmts = res.data.data || [];
          setPayments(pmts);
          setPaymentsLoaded(true);
          // Load refund history for payments that have been (partially) refunded
          const refundedPmts = pmts.filter(p => p.refundCount > 0);
          const histories = await Promise.all(
            refundedPmts.map(p =>
              adminService.getRefundsForPayment(p.id)
                .then(r => ({ id: p.id, refunds: r.data.data || [] }))
                .catch(() => ({ id: p.id, refunds: [] }))
            )
          );
          const map = {};
          histories.forEach(h => { map[h.id] = h.refunds; });
          setRefundsByPayment(map);
        })
        .catch(() => setPayments([]))
        .finally(() => setPaymentsLoading(false));
    }
  }, [tab, b.bookingRef]);

  // Ensure payments are loaded (fetches once if not yet loaded)
  const ensurePaymentsLoaded = async () => {
    if (paymentsLoaded) return payments;
    const res = await paymentService.getByBooking(b.bookingRef);
    const pmts = res.data.data || [];
    setPayments(pmts);
    setPaymentsLoaded(true);
    return pmts;
  };

  const refreshPayments = async () => {
    const res = await paymentService.getByBooking(b.bookingRef);
    const pmts = res.data.data || [];
    setPayments(pmts);
    const refundedPmts = pmts.filter(p => p.refundCount > 0);
    const histories = await Promise.all(
      refundedPmts.map(p =>
        adminService.getRefundsForPayment(p.id)
          .then(r => ({ id: p.id, refunds: r.data.data || [] }))
          .catch(() => ({ id: p.id, refunds: [] }))
      )
    );
    const map = {};
    histories.forEach(h => { map[h.id] = h.refunds; });
    setRefundsByPayment(map);
  };

  // Re-fetch booking data so payment status & booking status reflect Kafka-driven updates
  const refreshBooking = async () => {
    try {
      const res = await bookingService.getByRef(b.bookingRef);
      const updated = res.data.data || res.data;
      setB(updated);
      onAction(updated);
    } catch { /* keep current state */ }
  };

  // Fetch event log when that tab is opened
  useEffect(() => {
    if (tab === 'eventLog' && b.bookingRef) {
      setEventLogLoading(true);
      adminService.getBookingEvents(b.bookingRef, eventLogPage, 20)
        .then(res => {
          const d = res.data.data || res.data;
          setEventLog(d?.content || (Array.isArray(d) ? d : []));
          setEventLogTotal(d?.totalPages || 0);
        })
        .catch(() => setEventLog([]))
        .finally(() => setEventLogLoading(false));
    }
  }, [tab, b.bookingRef, eventLogPage]);

  const handleReplayBooking = async () => {
    if (!confirm(`Rebuild CQRS projection for ${b.bookingRef}? This re-derives the booking state from its event log.`)) return;
    setReplaying(true);
    try {
      await adminService.replayBooking(b.bookingRef);
      toast.success('Projection rebuilt — refreshing booking');
      await refreshBooking();
    } catch (err) { toast.error(err.response?.data?.message || 'Replay failed'); }
    setReplaying(false);
  };

  const handleRefund = async () => {
    if (!refundModal.payment || !refundAmount) return;
    const amt = parseFloat(refundAmount);
    if (isNaN(amt) || amt <= 0) { toast.error('Enter a valid amount'); return; }
    const maxRefundable = refundModal.payment.remainingRefundable ?? refundModal.payment.amount;
    if (amt > maxRefundable) { toast.error(`Refund amount cannot exceed remaining refundable ₹${maxRefundable.toLocaleString()}`); return; }
    if (!confirm(`Refund ₹${amt.toLocaleString()} to customer? This action cannot be undone.`)) return;
    setRefunding(true);
    try {
      await adminService.initiateRefund({
        paymentId: refundModal.payment.id,
        amount: amt,
        reason: refundReason || 'Admin refund',
      });
      toast.success('Refund initiated successfully');
      setRefundModal({ open: false, payment: null });
      setRefundAmount('');
      setRefundReason('');
      await refreshPayments();
      // Optimistically reflect the refund on the booking immediately
      setB(prev => {
        const newCollected = Math.max(0, parseFloat(prev.collectedAmount || 0) - amt);
        const newBalance = parseFloat(prev.totalAmount || 0) - newCollected;
        return {
          ...prev,
          collectedAmount: newCollected,
          balanceDue: newBalance,
          paymentStatus: newCollected < 0.01 ? 'REFUNDED' : 'PARTIALLY_REFUNDED',
        };
      });
      // Sync with server after Kafka has processed the PAYMENT_REFUNDED event
      setTimeout(() => refreshBooking(), 3000);
      setTimeout(() => refreshBooking(), 7000);
    } catch (err) { toast.error(err.response?.data?.message || 'Refund failed'); }
    setRefunding(false);
  };

  // ── Record cash payment ────────────────────────────────────
  const handleRecordCashPayment = async () => {
    if (!confirm(`Record cash payment of ₹${b.totalAmount?.toLocaleString()} for booking ${b.bookingRef}?\nThis will create a payment record so you can issue refunds.`)) return;
    setRecordingCash(true);
    try {
      await adminService.recordCashPayment({
        bookingRef: b.bookingRef,
        amount: b.totalAmount,
        customerId: b.customerId || 0,
        notes: 'Cash collected at venue',
      });
      toast.success('Cash payment recorded successfully');
      await refreshPayments();
      // Optimistic update — show cleared balance immediately (Kafka updates DB asynchronously)
      setB(prev => ({
        ...prev,
        collectedAmount: prev.totalAmount,
        balanceDue: 0,
        paymentStatus: 'SUCCESS',
      }));
      // Sync with server after Kafka has had time to process the event
      setTimeout(() => refreshBooking(), 3000);
      setTimeout(() => refreshBooking(), 7000);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to record cash payment');
    }
    setRecordingCash(false);
  };

  // ── Add additional payment ─────────────────────────────────
  const handleAddPayment = async () => {
    const amt = parseFloat(addPaymentForm.amount);
    if (isNaN(amt) || amt <= 0) { toast.error('Enter a valid amount'); return; }
    // Guard: do not allow collecting more than the booking's balance due
    const balance = (b.balanceDue != null) ? b.balanceDue : ((b.totalAmount || 0) - (b.collectedAmount || 0));
    if (amt > balance + 0.01 && balance >= 0) {
      toast.error(`Amount ₹${amt.toLocaleString()} exceeds remaining balance ₹${Math.max(0, balance).toLocaleString()}`);
      return;
    }
    setAddingPayment(true);
    try {
      await adminService.addPayment({
        bookingRef: b.bookingRef,
        amount: amt,
        customerId: b.customerId || 0,
        paymentMethod: addPaymentForm.method,
        bookingTotalAmount: b.totalAmount || 0,
        notes: addPaymentForm.notes || '',
      });
      setShowAddPayment(false);
      setAddPaymentForm({ amount: '', method: 'CASH', notes: '' });
      await refreshPayments();
      // Optimistic update — reflect the newly added payment amount immediately
      const prevCollected = parseFloat(b.collectedAmount || 0);
      const prevTotal = parseFloat(b.totalAmount || 0);
      const newCollected = prevCollected + amt;
      const newBalance = Math.max(0, prevTotal - newCollected);
      setB(prev => ({
        ...prev,
        collectedAmount: newCollected,
        balanceDue: newBalance,
        paymentStatus: newBalance < 0.01 ? 'SUCCESS' : 'PARTIALLY_PAID',
      }));
      // Sync with server after Kafka has had time to process the event
      setTimeout(() => refreshBooking(), 3000);
      setTimeout(() => refreshBooking(), 7000);
    } catch (err) { toast.error(err.response?.data?.message || 'Failed to record payment'); }
    setAddingPayment(false);
  };

  // ── Change payment method (refund old + record new) ────────
  const handleChangeMethod = async (payment) => {
    const remaining = payment.remainingRefundable ?? payment.amount;
    if (!confirm(`Change payment method from ${payment.paymentMethod} to ${changeMethodNewMethod}?\n\nThis will refund ₹${remaining.toLocaleString()} and re-record with the new method.`)) return;
    setChangingMethod(true);
    try {
      await adminService.initiateRefund({
        paymentId: payment.id,
        amount: remaining,
        reason: `Payment method changed to ${changeMethodNewMethod}`,
      });
      try {
        await adminService.addPayment({
          bookingRef: b.bookingRef,
          amount: remaining,
          customerId: b.customerId || 0,
          paymentMethod: changeMethodNewMethod,
          bookingTotalAmount: b.totalAmount || 0,
          notes: `Method changed from ${payment.paymentMethod} to ${changeMethodNewMethod}`,
        });
      } catch (addErr) {
        toast.error(`Refund succeeded but re-recording failed. Please manually add a ₹${remaining.toLocaleString()} ${changeMethodNewMethod} payment from the Payment tab.`);
        await refreshPayments();
        setTimeout(() => refreshBooking(), 3000);
        setTimeout(() => refreshBooking(), 7000);
        setChangingMethod(false);
        return;
      }
      toast.success(`Method changed \u2192 ${changeMethodNewMethod}`);
      setChangeMethodFor(null);
      await refreshPayments();
      // Optimistically update payment method (net collectedAmount unchanged — refund+re-add cancel out)
      setB(prev => ({ ...prev, paymentMethod: changeMethodNewMethod }));
      // Sync with server after Kafka has processed both the refund and re-add events
      setTimeout(() => refreshBooking(), 3000);
      setTimeout(() => refreshBooking(), 7000);
    } catch (err) { toast.error(err.response?.data?.message || 'Failed to change method'); }
    setChangingMethod(false);
  };

  // ── Inline actions (confirm, check-in, checkout, cancel) ─────────────
  const handleModalConfirm = async () => {
    setActionLoading(true);
    try {
      const res = await adminService.confirmBooking(b.bookingRef);
      toast.success('Booking confirmed!');
      const updated = res.data.data || { ...b, status: 'CONFIRMED' };
      setB(updated);
      onAction(updated);
    } catch (err) { toast.error(err.response?.data?.message || 'Confirm failed'); }
    setActionLoading(false);
  };

  const handleModalCheckIn = async () => {
    // Block check-in if this is a cash booking with no recorded payment
    if (b.paymentStatus === 'SUCCESS') {
      setActionLoading(true);
      try {
        const pmts = await ensurePaymentsLoaded();
        if (pmts.length === 0) {
          toast.error('Cannot check in — cash payment hasn\'t been recorded yet. Go to the Payment tab and click "Record Cash Payment" first.');
          setActionLoading(false);
          return;
        }
      } catch {
        toast.error('Could not verify payment status. Please try again.');
        setActionLoading(false);
        return;
      }
      setActionLoading(false);
    }
    setActionLoading(true);
    try {
      const res = await adminService.checkIn(b.bookingRef);
      toast.success('Checked in!');
      const updated = res.data.data || { ...b, status: 'CHECKED_IN', checkedIn: true };
      setB(updated);
      onAction(updated);
    } catch (err) { toast.error(err.response?.data?.message || 'Check-in failed'); }
    setActionLoading(false);
  };

  const handleModalCheckout = async () => {
    // Check for outstanding balance before checkout
    const balance = (b.balanceDue != null) ? b.balanceDue : ((b.totalAmount || 0) - (b.collectedAmount || 0));
    // Warn if payment was fully refunded
    if (b.paymentStatus === 'REFUNDED') {
      if (!confirm(`⚠️ Payment was fully REFUNDED (₹0 collected on a ₹${(b.totalAmount || 0).toLocaleString()} booking).\n\nCheckout anyway? The booking will be marked as completed with no revenue recorded.`)) return;
    } else if (balance > 0.01) {
      if (!confirm(`Outstanding balance of ₹${balance.toLocaleString()} on this booking.\nCheckout anyway? (You can collect the difference from the Payment tab)`)) return;
    } else if (balance < -0.01) {
      if (!confirm(`Customer overpaid by ₹${Math.abs(balance).toLocaleString()}.\nCheckout anyway? (You can issue a refund from the Payment tab)`)) return;
    }
    setActionLoading(true);
    try {
      const res = await adminService.checkout(b.bookingRef);
      toast.success('Checked out — session completed');
      const updated = res.data.data || { ...b, status: 'COMPLETED' };
      setB(updated);
      onAction(updated);
    } catch (err) { toast.error(err.response?.data?.message || 'Checkout failed'); }
    setActionLoading(false);
  };

  const handleModalUndoCheckIn = async () => {
    setActionLoading(true);
    try {
      const res = await adminService.undoCheckIn(b.bookingRef);
      toast.success('Check-in undone — moved back to arrivals');
      const updated = res.data.data || { ...b, status: 'CONFIRMED', checkedIn: false };
      setB(updated);
      onAction(updated);
    } catch (err) { toast.error(err.response?.data?.message || 'Undo check-in failed'); }
    setActionLoading(false);
  };

  // Feature 8: Cancel with/without refund
  const openCancelModal = () => {
    setCancelWithRefund(false);
    setCancelRefundAmount('');
    setCancelModal({ open: true });
  };

  const handleCancelBooking = async () => {
    setActionLoading(true);
    try {
      // If refunding, process refund first
      if (cancelWithRefund && cancelRefundAmount) {
        const amt = parseFloat(cancelRefundAmount);
        if (isNaN(amt) || amt <= 0) { toast.error('Enter a valid refund amount'); setActionLoading(false); return; }
        // Find the successful payment to refund
        const payRes = await paymentService.getByBooking(b.bookingRef);
        const payList = payRes.data.data || [];
        const successPayment = payList.find(p => p.status === 'SUCCESS' || p.status === 'PARTIALLY_REFUNDED');
        if (!successPayment) {
          toast.error('No refundable payment record found. For cash bookings, go to the Payment tab and click "Record Cash Payment" first.');
          setActionLoading(false); return;
        }
        const maxRefundable = successPayment.remainingRefundable ?? successPayment.amount;
        if (amt > maxRefundable) { toast.error(`Refund amount cannot exceed remaining refundable ₹${maxRefundable.toLocaleString()}`); setActionLoading(false); return; }
        try {
          await adminService.initiateRefund({
            paymentId: successPayment.id,
            amount: amt,
            reason: 'Cancellation refund',
          });
          toast.success('Refund of ₹' + amt.toLocaleString() + ' initiated');
        } catch (refundErr) {
          toast.error('Refund failed: ' + (refundErr.response?.data?.message || 'Unknown error') + '. Booking will still be cancelled — issue refund manually from the Payment tab.');
        }
      }
      // Then cancel the booking
      await adminService.cancelBooking(b.bookingRef, 'Admin cancellation');
      toast.success('Booking cancelled');
      const updated = { ...b, status: 'CANCELLED' };
      setB(updated);
      onAction(updated);
      setCancelModal({ open: false });
    } catch (err) { toast.error(err.response?.data?.message || 'Cancel failed'); }
    setActionLoading(false);
  };

  const handleModalReinstate = () => {
    onReinstate(b);
    onClose();
  };

  const handleSaveEdits = async () => {
    setSaving(true);
    try {
      await adminService.updateBooking(b.bookingRef, editForm);
      toast.success('Booking updated');
      setEditing(false);
      onSaved();
    } catch (err) { toast.error(err.response?.data?.message || 'Failed to update'); }
    setSaving(false);
  };

  // ── Price adjustment handler ───────────────────────────────
  const openPriceAdjustment = () => {
    setPriceForm({
      baseAmount: String(b.baseAmount ?? 0),
      addOnAmount: String(b.addOnAmount ?? 0),
      guestAmount: String(b.guestAmount ?? 0),
      reason: ''
    });
    setAdjustingPrices(true);
  };

  const handleSavePriceAdjustment = async () => {
    const base = parseFloat(priceForm.baseAmount);
    const addOn = parseFloat(priceForm.addOnAmount);
    const guest = parseFloat(priceForm.guestAmount);
    if ([base, addOn, guest].some(v => isNaN(v) || v < 0)) {
      toast.error('All amounts must be valid non-negative numbers');
      return;
    }
    const newTotal = base + addOn + guest;
    const oldTotal = (b.baseAmount || 0) + (b.addOnAmount || 0) + (b.guestAmount || 0);
    if (Math.abs(newTotal - oldTotal) < 0.01) {
      toast.error('No price changes detected');
      return;
    }
    if (!priceForm.reason.trim()) {
      toast.error('Please provide a reason for the price adjustment');
      return;
    }
    if (!confirm(`Adjust total from ₹${oldTotal.toLocaleString()} to ₹${newTotal.toLocaleString()}?\n\nReason: ${priceForm.reason}\n\nThis will be recorded in the audit log.`)) return;
    setSavingPrices(true);
    try {
      const res = await adminService.updateBooking(b.bookingRef, {
        baseAmount: base,
        addOnAmount: addOn,
        guestAmount: guest,
        priceAdjustmentReason: priceForm.reason.trim(),
      });
      const updated = res.data.data || res.data;
      setB(updated);
      onAction(updated);
      setAdjustingPrices(false);
      toast.success(`Price adjusted: ₹${oldTotal.toLocaleString()} → ₹${newTotal.toLocaleString()}`);
    } catch (err) { toast.error(err.response?.data?.message || 'Failed to adjust prices'); }
    setSavingPrices(false);
  };

  const tabBtnStyle = (active) => `ab-detail-tab ${active ? 'active' : ''}`;
  const rowStyle = { display: 'flex', justifyContent: 'space-between', padding: '0.45rem 0', borderBottom: '1px solid var(--border)', fontSize: '0.88rem', alignItems: 'center' };
  const labelStyle = { color: 'var(--text-secondary)', fontWeight: 500 };
  const editInputStyle = { padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', flex: '1', marginLeft: '0.5rem', maxWidth: '280px' };

  return (
    <>
      <div className="ab-detail-tabs">
        <button className={tabBtnStyle(tab === 'customer')} onClick={() => setTab('customer')}>Customer</button>
        <button className={tabBtnStyle(tab === 'reservation')} onClick={() => setTab('reservation')}>Reservation</button>
        <button className={tabBtnStyle(tab === 'payment')} onClick={() => setTab('payment')}>Payment</button>
        <button className={tabBtnStyle(tab === 'eventLog')} onClick={() => setTab('eventLog')}>Event Log</button>
      </div>

      {tab === 'customer' && (
        <div>
          <div style={rowStyle}>
            <span style={labelStyle}>Name</span>
            <span>{b.customerName || 'N/A'}</span>
          </div>
          <div style={rowStyle}>
            <span style={labelStyle}>Email</span>
            <span>{b.customerEmail}</span>
          </div>
          <div style={rowStyle}>
            <span style={labelStyle}>Phone</span>
            <span>{b.customerPhone || 'N/A'}</span>
          </div>
          <div style={rowStyle}><span style={labelStyle}>Customer ID</span><span>{b.customerId || 'N/A'}</span></div>
          <div style={rowStyle}><span style={labelStyle}>Total Bookings</span><span>{bookingCount}</span></div>

          {b.customerId && (
            <div style={{ marginTop: '0.8rem' }}>
              <button className="btn btn-secondary btn-sm" onClick={() => { onClose(); navigate(`/admin/users-config/${b.customerId}`); }}>
                ✏️ Edit Customer Details
              </button>
            </div>
          )}

          {/* Action buttons based on current status */}
          {!cancelModal.open && (
          <div style={{ display: 'flex', gap: '0.6rem', marginTop: '1.2rem', flexWrap: 'wrap' }}>
            {b.status === 'PENDING' && (
              <>
                {(b.paymentStatus === 'SUCCESS' && paymentsLoaded && payments.length === 0) && (
                  <div style={{ width: '100%', padding: '0.5rem 0.75rem', background: 'rgba(255, 100, 0, 0.1)', border: '1px solid orange', borderRadius: 'var(--radius-sm)', fontSize: '0.82rem', color: 'orange', marginBottom: '0.3rem' }}>
                    💵 Cash payment not recorded. Go to the <strong>Payment tab</strong> and click <strong>"Record Cash Payment"</strong> before checking in.
                  </div>
                )}
                <button className="btn btn-primary" style={{ background: 'var(--success, #00b894)' }} onClick={handleModalConfirm} disabled={actionLoading}>
                  {actionLoading ? 'Processing...' : 'Confirm'}
                </button>
                <button className="btn btn-danger" onClick={openCancelModal} disabled={actionLoading}>
                  Cancel Booking
                </button>
              </>
            )}
            {b.status === 'CONFIRMED' && (
              <>
                {(b.paymentStatus === 'SUCCESS' && paymentsLoaded && payments.length === 0) && (
                  <div style={{ width: '100%', padding: '0.5rem 0.75rem', background: 'rgba(255, 100, 0, 0.1)', border: '1px solid orange', borderRadius: 'var(--radius-sm)', fontSize: '0.82rem', color: 'orange', marginBottom: '0.3rem' }}>
                    💵 Cash payment not recorded. Go to the <strong>Payment tab</strong> and click <strong>"Record Cash Payment"</strong> before checking in.
                  </div>
                )}
                {isTodayBooking && (
                  <button className="btn btn-primary" onClick={handleModalCheckIn} disabled={actionLoading}>
                    {actionLoading ? 'Processing...' : 'Check In'}
                  </button>
                )}
                <button className="btn btn-danger" onClick={openCancelModal} disabled={actionLoading}>
                  Cancel Booking
                </button>
              </>
            )}
            {b.status === 'CHECKED_IN' && (
              <>
                {b.paymentStatus === 'REFUNDED' && (
                  <div className="ab-warning-banner ab-warning-banner--danger" style={{ width: '100%', marginBottom: '0.3rem' }}>
                    ⚠️ Payment was <strong>fully refunded</strong> — ₹0 collected on a ₹{(b.totalAmount || 0).toLocaleString()} booking. You can still checkout, but no revenue will be recorded.
                  </div>
                )}
                {(() => {
                  const bal = (b.balanceDue != null) ? b.balanceDue : ((b.totalAmount || 0) - (b.collectedAmount || 0));
                  if (bal > 0.01) return (
                    <div style={{ width: '100%', padding: '0.5rem 0.75rem', background: 'rgba(255, 100, 0, 0.1)', border: '1px solid orange', borderRadius: 'var(--radius-sm)', fontSize: '0.82rem', color: 'orange', marginBottom: '0.3rem' }}>
                      💰 Outstanding balance: <strong>₹{bal.toLocaleString()}</strong>. Collect from the <strong>Payment tab</strong> before checkout.
                    </div>
                  );
                  if (bal < -0.01) return (
                    <div style={{ width: '100%', padding: '0.5rem 0.75rem', background: 'rgba(0, 206, 201, 0.1)', border: '1px solid #00cec9', borderRadius: 'var(--radius-sm)', fontSize: '0.82rem', color: '#00cec9', marginBottom: '0.3rem' }}>
                      💳 Customer overpaid by <strong>₹{Math.abs(bal).toLocaleString()}</strong>. Consider issuing a refund from the <strong>Payment tab</strong>.
                    </div>
                  );
                  return null;
                })()}
                <button className="btn btn-primary" style={{ background: 'var(--success, #00b894)' }} onClick={handleModalCheckout} disabled={actionLoading}>
                  {actionLoading ? 'Processing...' : 'Checkout'}
                </button>
                <button className="btn btn-secondary" onClick={handleModalUndoCheckIn} disabled={actionLoading}>
                  {actionLoading ? 'Processing...' : 'Undo Check-in'}
                </button>
              </>
            )}
            {b.status === 'COMPLETED' && (
              <button className="btn btn-primary" onClick={handleModalReinstate}>
                Reinstate
              </button>
            )}
            {b.status === 'CANCELLED' && (
              <button className="btn btn-primary" onClick={handleModalReinstate}>
                Reinstate
              </button>
            )}
            {b.status === 'NO_SHOW' && (
              <button className="btn btn-primary" onClick={handleModalReinstate}>
                Reinstate
              </button>
            )}
          </div>
          )}

          {/* Cancel Modal — with/without refund */}
          {cancelModal.open && (
            <div style={{ marginTop: '1rem', padding: '1rem', background: 'var(--bg-card)', border: '1px solid var(--danger, #e74c3c)', borderRadius: 'var(--radius-sm)' }}>
              <h4 style={{ marginBottom: '0.75rem', color: 'var(--danger)' }}>Cancel Booking</h4>
              <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.75rem' }}>
                <button className={`btn btn-sm ${!cancelWithRefund ? 'btn-danger' : 'btn-secondary'}`}
                  onClick={() => setCancelWithRefund(false)}>Cancel Without Refund</button>
                <button className={`btn btn-sm ${cancelWithRefund ? 'btn-danger' : 'btn-secondary'}`}
                  onClick={() => { setCancelWithRefund(true); setCancelRefundAmount(String(b.totalAmount || 0)); }}>Cancel With Refund</button>
              </div>
              {cancelWithRefund && (
                <div style={{ marginBottom: '0.75rem' }}>
                  <label style={{ ...labelStyle, display: 'block', marginBottom: '0.3rem' }}>Refund Amount (max ₹{b.totalAmount?.toLocaleString()})</label>
                  <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                    <input type="number" value={cancelRefundAmount} onChange={e => setCancelRefundAmount(e.target.value)}
                      max={b.totalAmount} step="0.01" min="1"
                      style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '150px' }} />
                    <button className="btn btn-sm btn-secondary" onClick={() => setCancelRefundAmount(String(b.totalAmount || 0))}>
                      Full Amount
                    </button>
                  </div>
                </div>
              )}
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button className="btn btn-danger btn-sm" onClick={handleCancelBooking} disabled={actionLoading}>
                  {actionLoading ? 'Processing...' : (cancelWithRefund ? 'Cancel & Refund' : 'Cancel Booking')}
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => setCancelModal({ open: false })}>Go Back</button>
              </div>
            </div>
          )}
        </div>
      )}

      {tab === 'reservation' && (
        <div>
          <div style={rowStyle}><span style={labelStyle}>Booking Ref</span><span style={{ fontFamily: 'monospace' }}>{b.bookingRef}</span></div>
          <div style={rowStyle}><span style={labelStyle}>Event Type</span><span>{b.eventType?.name ?? b.eventType}</span></div>
          <div style={rowStyle}><span style={labelStyle}>Date</span><span>{b.bookingDate}</span></div>
          <div style={rowStyle}><span style={labelStyle}>Time</span><span>{(() => {
            const parts = String(b.startTime).split(':');
            const startMin = parseInt(parts[0],10)*60 + parseInt(parts[1]||'0',10);
            const durMin = b.durationMinutes || (b.durationHours * 60);
            const endMin = startMin + durMin;
            const fmt = (m) => String(Math.floor(m/60)).padStart(2,'0') + ':' + String(m%60).padStart(2,'0');
            return `${fmt(startMin)} – ${fmt(endMin)}`;
          })()}</span></div>
          <div style={rowStyle}><span style={labelStyle}>Duration</span><span>{(() => { const m = b.durationMinutes || (b.durationHours * 60); const h = Math.floor(m/60); const min = m%60; return h > 0 && min > 0 ? `${h}hr ${min}m` : h > 0 ? `${h}hr` : `${min}m`; })()}</span></div>
          {b.numberOfGuests > 0 && (
            <div style={rowStyle}><span style={labelStyle}>Guests</span><span>{b.numberOfGuests}</span></div>
          )}

          {b.addOns && b.addOns.length > 0 && (
            <div style={{ ...rowStyle, flexDirection: 'column', gap: '0.3rem', alignItems: 'stretch' }}>
              <span style={labelStyle}>Add-Ons</span>
              {b.addOns.map((ao, i) => (
                <div key={i} style={{ display: 'flex', justifyContent: 'space-between', paddingLeft: '0.5rem', fontSize: '0.82rem' }}>
                  <span>{ao.name} × {ao.quantity}</span>
                  <span>₹{ao.price?.toLocaleString()}</span>
                </div>
              ))}
            </div>
          )}

          <div style={rowStyle}><span style={labelStyle}>Base Amount</span><span>₹{b.baseAmount?.toLocaleString()}</span></div>
          <div style={rowStyle}><span style={labelStyle}>Add-On Amount</span><span>₹{b.addOnAmount?.toLocaleString()}</span></div>
          {b.guestAmount > 0 && (
            <div style={rowStyle}><span style={labelStyle}>Guest Charge</span><span>₹{b.guestAmount?.toLocaleString()}</span></div>
          )}
          <div style={{ ...rowStyle, fontWeight: 700, fontSize: '1rem' }}><span style={labelStyle}>Total Amount</span><span style={{ color: 'var(--primary)' }}>₹{b.totalAmount?.toLocaleString()}</span></div>
          {b.pricingSource && b.pricingSource !== 'DEFAULT' && (
            <div style={{ ...rowStyle, fontSize: '0.78rem' }}>
              <span style={labelStyle}>Pricing</span>
              <span className={`badge ${b.pricingSource === 'ADMIN_OVERRIDE' ? 'badge-warning' : 'badge-info'}`} style={{ fontSize: '0.72rem' }}>
                {b.pricingSource === 'ADMIN_OVERRIDE' ? '⚙️ Admin Override' : b.pricingSource === 'RATE_CODE' ? `📋 ${b.rateCodeName || 'Rate Code'}` : `👤 Customer Pricing`}
              </span>
            </div>
          )}
          {(() => {
            const bal = (b.balanceDue != null) ? b.balanceDue : ((b.totalAmount || 0) - (b.collectedAmount || 0));
            if (b.collectedAmount != null && b.collectedAmount > 0) return (
              <>
                <div style={rowStyle}><span style={labelStyle}>Collected</span><span>₹{b.collectedAmount?.toLocaleString()}</span></div>
                {Math.abs(bal) > 0.01 && (
                  <div style={{ ...rowStyle, fontWeight: 600 }}>
                    <span style={labelStyle}>{bal > 0 ? 'Balance Due' : 'Overpaid'}</span>
                    <span style={{ color: bal > 0 ? 'var(--danger, #e74c3c)' : '#00cec9' }}>
                      ₹{Math.abs(bal).toLocaleString()}
                    </span>
                  </div>
                )}
              </>
            );
            return null;
          })()}

          <div style={rowStyle}>
            <span style={labelStyle}>Status</span>
            <span className={`badge ${statusBadge(b.status)}`}>{b.status?.replace('_', ' ')}</span>
          </div>
          <div style={rowStyle}>
            <span style={labelStyle}>Payment Status</span>
            <span className={`badge ${paymentBadge(b.paymentStatus)}`}>
              {b.paymentStatus?.replace('_', ' ')}
            </span>
          </div>
          <div style={rowStyle}>
            <span style={labelStyle}>Special Notes</span>
            {editing ? <input style={editInputStyle} value={editForm.specialNotes} onChange={e => setEditForm({ ...editForm, specialNotes: e.target.value })} />
              : <span>{DOMPurify.sanitize(b.specialNotes || 'N/A', { ALLOWED_TAGS: [] })}</span>}
          </div>
          <div style={rowStyle}>
            <span style={labelStyle}>Admin Notes</span>
            {editing ? <input style={editInputStyle} value={editForm.adminNotes} onChange={e => setEditForm({ ...editForm, adminNotes: e.target.value })} />
              : <span>{DOMPurify.sanitize(b.adminNotes || 'N/A', { ALLOWED_TAGS: [] })}</span>}
          </div>
          <div style={rowStyle}><span style={labelStyle}>Created</span><span>{b.createdAt ? new Date(b.createdAt).toLocaleString() : 'N/A'}</span></div>

          {/* Early checkout info */}
          {b.earlyCheckoutNote && (
            <div style={{ marginTop: '0.75rem', padding: '0.6rem 0.8rem', background: 'rgba(0, 206, 201, 0.1)', border: '1px solid #00cec9', borderRadius: 'var(--radius-sm)', fontSize: '0.85rem', color: '#00cec9' }}>
              ⏱️ {DOMPurify.sanitize(b.earlyCheckoutNote, { ALLOWED_TAGS: [] })}
            </div>
          )}
          {b.actualCheckoutTime && !b.earlyCheckoutNote && (
            <div style={rowStyle}><span style={labelStyle}>Checkout Time</span><span>{new Date(b.actualCheckoutTime).toLocaleString()}</span></div>
          )}

          {/* Edit Reservation button — navigates to booking wizard for time/addon changes */}
          {(b.status === 'PENDING' || b.status === 'CONFIRMED' || b.status === 'CHECKED_IN') && (
            <div style={{ marginTop: '0.8rem', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              {!editing && !adjustingPrices && (
                <>
                  <button className="btn btn-primary btn-sm" onClick={() => { onEditReservation(b); onClose(); }}>
                    ✏️ Edit Reservation (Change Time / Add-Ons)
                  </button>
                  <button className="btn btn-secondary btn-sm" onClick={() => setEditing(true)}>
                    Edit Notes
                  </button>
                  <button className="btn btn-secondary btn-sm" onClick={openPriceAdjustment}>
                    💰 Adjust Prices
                  </button>
                </>
              )}
            </div>
          )}

          {/* Price Adjustment Form */}
          {adjustingPrices && (
            <div className="ab-cancel-box" style={{ borderColor: 'var(--primary)', marginTop: '1rem' }}>
              <h4 style={{ marginBottom: '0.5rem', color: 'var(--primary)' }}>Adjust Prices</h4>
              <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }}>
                Override pricing for this reservation. Original total: <strong>₹{b.totalAmount?.toLocaleString()}</strong>
              </p>
              <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', marginBottom: '0.6rem' }}>
                <div>
                  <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: '0.2rem' }}>Base Amount (₹)</label>
                  <input type="number" value={priceForm.baseAmount} onChange={e => setPriceForm(f => ({ ...f, baseAmount: e.target.value }))}
                    min="0" step="1"
                    style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '120px' }} />
                </div>
                <div>
                  <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: '0.2rem' }}>Add-On Amount (₹)</label>
                  <input type="number" value={priceForm.addOnAmount} onChange={e => setPriceForm(f => ({ ...f, addOnAmount: e.target.value }))}
                    min="0" step="1"
                    style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '120px' }} />
                </div>
                <div>
                  <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: '0.2rem' }}>Guest Charge (₹)</label>
                  <input type="number" value={priceForm.guestAmount} onChange={e => setPriceForm(f => ({ ...f, guestAmount: e.target.value }))}
                    min="0" step="1"
                    style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '120px' }} />
                </div>
              </div>
              <div style={{ marginBottom: '0.5rem', padding: '0.4rem 0.6rem', background: 'var(--bg)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', fontSize: '0.82rem' }}>
                New Total: <strong style={{ color: 'var(--primary)' }}>
                  ₹{((parseFloat(priceForm.baseAmount) || 0) + (parseFloat(priceForm.addOnAmount) || 0) + (parseFloat(priceForm.guestAmount) || 0)).toLocaleString()}
                </strong>
                {(() => {
                  const newT = (parseFloat(priceForm.baseAmount) || 0) + (parseFloat(priceForm.addOnAmount) || 0) + (parseFloat(priceForm.guestAmount) || 0);
                  const diff = newT - (b.totalAmount || 0);
                  if (Math.abs(diff) > 0.01) return (
                    <span style={{ marginLeft: '0.5rem', color: diff > 0 ? 'var(--danger, #e74c3c)' : 'var(--success, #00b894)', fontWeight: 600 }}>
                      ({diff > 0 ? '+' : ''}₹{diff.toLocaleString()})
                    </span>
                  );
                  return null;
                })()}
              </div>
              <div style={{ marginBottom: '0.6rem' }}>
                <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: '0.2rem', color: 'var(--danger, #e74c3c)' }}>Reason for adjustment *</label>
                <input value={priceForm.reason} onChange={e => setPriceForm(f => ({ ...f, reason: e.target.value }))}
                  placeholder="e.g. Customer bargain, Promotional discount, Correction..."
                  style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '100%', maxWidth: '400px' }} />
              </div>
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button className="btn btn-primary btn-sm" onClick={handleSavePriceAdjustment} disabled={savingPrices}>
                  {savingPrices ? 'Saving...' : 'Save Price Adjustment'}
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => setAdjustingPrices(false)}>Cancel</button>
              </div>
            </div>
          )}
        </div>
      )}

      {tab === 'payment' && (
        <div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.75rem' }}>
            <button className="btn btn-secondary btn-sm" onClick={() => window.print()} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem' }}>
              🖨️ Print Receipt
            </button>
          </div>
          {/* Payment pending warning for non-completed bookings */}
          {b.paymentStatus === 'PENDING' && b.status !== 'COMPLETED' && b.status !== 'CANCELLED' && (
            <div style={{ padding: '0.6rem 0.8rem', background: 'rgba(255,165,0,0.1)', border: '1px solid var(--warning, orange)', borderRadius: 'var(--radius-sm)', marginBottom: '1rem', fontSize: '0.85rem', color: 'var(--warning, orange)' }}>
              ⚠️ Payment is still PENDING for this booking. Ensure payment is collected before checkout.
            </div>
          )}
          {b.paymentStatus === 'FAILED' && (
            <div style={{ padding: '0.6rem 0.8rem', background: 'rgba(231,76,60,0.1)', border: '1px solid var(--danger, #e74c3c)', borderRadius: 'var(--radius-sm)', marginBottom: '1rem', fontSize: '0.85rem', color: 'var(--danger, #e74c3c)' }}>
              ❌ Payment FAILED — customer may need to retry payment or pay via alternative method.
            </div>
          )}

          {paymentsLoading ? (
            <p style={{ color: 'var(--text-muted)', textAlign: 'center', padding: '1rem' }}>Loading payments...</p>
          ) : payments.length === 0 ? (
            (() => {
              // CASH booking: paymentStatus is SUCCESS but no record exists in payment-service
              const isCashPaid = b.paymentStatus === 'SUCCESS' || b.paymentStatus === 'PARTIALLY_REFUNDED';
              return isCashPaid ? (
                <div style={{ padding: '1rem', background: 'rgba(108,92,231,0.08)', border: '1px solid var(--primary)', borderRadius: 'var(--radius-sm)', marginBottom: '1rem' }}>
                  <p style={{ fontWeight: 600, marginBottom: '0.4rem' }}>💵 Cash Payment Booking</p>
                  <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '0.75rem' }}>
                    This booking was paid in cash (₹{b.totalAmount?.toLocaleString()}). No digital payment record exists yet.
                    Record it in the system to enable refunds.
                  </p>
                  <button className="btn btn-primary btn-sm" onClick={handleRecordCashPayment} disabled={recordingCash}>
                    {recordingCash ? 'Recording...' : `Record Cash Payment ₹${b.totalAmount?.toLocaleString()}`}
                  </button>
                </div>
              ) : (
                <p style={{ color: 'var(--text-muted)', textAlign: 'center', padding: '1rem' }}>No payment records found</p>
              );
            })()
          ) : (
            payments.map((p, idx) => (
              <div key={p.id || idx} style={{ marginBottom: '1rem', padding: '0.75rem', borderRadius: 'var(--radius-sm)', background: 'var(--bg-input)', border: '1px solid var(--border)' }}>
                <div style={rowStyle}><span style={labelStyle}>Transaction ID</span><span style={{ fontFamily: 'monospace', fontSize: '0.82rem' }}>{p.transactionId}</span></div>
                <div style={rowStyle}><span style={labelStyle}>Amount</span><span style={{ fontWeight: 600 }}>₹{p.amount?.toLocaleString()}</span></div>
                <div style={rowStyle}><span style={labelStyle}>Method</span><span>{p.paymentMethod?.replace('_', ' ')}</span></div>
                <div style={rowStyle}>
                  <span style={labelStyle}>Status</span>
                  <span className={`badge ${p.status === 'SUCCESS' ? 'badge-success' : p.status === 'REFUNDED' ? 'badge-info' : p.status === 'PARTIALLY_REFUNDED' ? 'badge-info' : p.status === 'FAILED' ? 'badge-danger' : 'badge-warning'}`}>
                    {p.status?.replace('_', ' ')}
                  </span>
                </div>
                {p.gatewayOrderId && <div style={rowStyle}><span style={labelStyle}>Gateway Order</span><span style={{ fontSize: '0.82rem' }}>{p.gatewayOrderId}</span></div>}
                {p.paidAt && <div style={rowStyle}><span style={labelStyle}>Paid At</span><span>{new Date(p.paidAt).toLocaleString()}</span></div>}
                {p.refundCount > 0 && (
                  <>
                    <div style={rowStyle}><span style={labelStyle}>Total Refunded</span><span style={{ color: '#e74c3c' }}>₹{p.totalRefunded?.toLocaleString()}</span></div>
                    <div style={rowStyle}><span style={labelStyle}>Remaining Refundable</span><span style={{ color: 'var(--success, #00b894)' }}>₹{(p.remainingRefundable ?? 0).toLocaleString()}</span></div>
                  </>
                )}
                {p.failureReason && <div style={rowStyle}><span style={labelStyle}>Failure Reason</span><span style={{ color: 'var(--danger)' }}>{DOMPurify.sanitize(p.failureReason, { ALLOWED_TAGS: [] })}</span></div>}
                {(p.status === 'SUCCESS' || p.status === 'PARTIALLY_REFUNDED') && (
                  <>
                    <div style={{ marginTop: '0.5rem', display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
                      <button
                        className="btn btn-sm btn-danger"
                        disabled={(p.remainingRefundable ?? p.amount) <= 0}
                        onClick={() => {
                          setRefundModal({ open: true, payment: p });
                          setRefundAmount(String(p.remainingRefundable ?? p.amount));
                          setRefundReason('');
                        }}>
                        💳 {(p.remainingRefundable ?? p.amount) <= 0 ? 'Fully Refunded' : 'Refund Payment'}
                      </button>
                      {(p.remainingRefundable ?? p.amount) > 0 && (
                        <button className="btn btn-sm btn-secondary"
                          onClick={() => { setChangeMethodFor(changeMethodFor === p.id ? null : p.id); setChangeMethodNewMethod('CASH'); }}>
                          ⇄ Change Method
                        </button>
                      )}
                      {p.totalRefunded > 0 && (
                        <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                          ₹{p.totalRefunded?.toLocaleString()} refunded
                        </span>
                      )}
                    </div>
                    {changeMethodFor === p.id && (
                      <div style={{ marginTop: '0.5rem', padding: '0.6rem 0.75rem', background: 'var(--bg)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)' }}>
                        <p style={{ fontSize: '0.78rem', color: 'var(--text-secondary)', marginBottom: '0.4rem' }}>
                          Refund ₹{(p.remainingRefundable ?? p.amount)?.toLocaleString()} from <strong>{p.paymentMethod?.replace('_', ' ')}</strong> and record as:
                        </p>
                        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                          <select value={changeMethodNewMethod} onChange={e => setChangeMethodNewMethod(e.target.value)}
                            style={{ padding: '0.3rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.82rem' }}>
                            <option value="CASH">Cash</option>
                            <option value="UPI">UPI</option>
                            <option value="CARD">Card</option>
                            <option value="BANK_TRANSFER">Bank Transfer</option>
                            <option value="WALLET">Wallet</option>
                          </select>
                          <button className="btn btn-sm btn-primary" onClick={() => handleChangeMethod(p)}
                            disabled={changingMethod || changeMethodNewMethod === p.paymentMethod}>
                            {changingMethod ? 'Processing...' : 'Confirm Change'}
                          </button>
                          <button className="btn btn-sm btn-secondary" onClick={() => setChangeMethodFor(null)}>Cancel</button>
                        </div>
                      </div>
                    )}
                  </>
                )}
                {/* Refund history */}
                {refundsByPayment[p.id]?.length > 0 && (
                  <div style={{ marginTop: '0.5rem', paddingTop: '0.5rem', borderTop: '1px solid var(--border)' }}>
                    <div style={{ fontSize: '0.78rem', color: 'var(--text-secondary)', fontWeight: 600, marginBottom: '0.3rem' }}>
                      Refund History ({refundsByPayment[p.id].length})
                    </div>
                    {refundsByPayment[p.id].map((r, ri) => (
                      <div key={ri} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.78rem', color: 'var(--text-secondary)', padding: '0.2rem 0', borderBottom: '1px dotted var(--border)' }}>
                        <span>₹{r.amount?.toLocaleString()} — {r.reason || 'No reason'}</span>
                        <span style={{ color: 'var(--text-muted)' }}>{r.refundedAt ? new Date(r.refundedAt).toLocaleDateString() : ''} by {r.initiatedBy}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))
          )}

          {/* Refund Modal — enhanced with Full Amount button and confirmation */}
          {refundModal.open && (
            <div style={{ marginTop: '1rem', padding: '1rem', background: 'var(--bg-card)', border: '1px solid var(--danger, #e74c3c)', borderRadius: 'var(--radius-sm)' }}>
              <h4 style={{ marginBottom: '0.5rem' }}>Refund Payment</h4>
              <p style={{ fontSize: '0.82rem', color: 'var(--text-secondary)', marginBottom: '0.75rem' }}>
                Paid: ₹{refundModal.payment?.amount?.toLocaleString()}
                {(refundModal.payment?.totalRefunded || 0) > 0 && (
                  <> &bull; Already refunded: ₹{refundModal.payment?.totalRefunded?.toLocaleString()} &bull; Remaining: ₹{(refundModal.payment?.remainingRefundable ?? refundModal.payment?.amount)?.toLocaleString()}</>
                )}
                <br/><span style={{ fontSize: '0.75rem', fontFamily: 'monospace' }}>{refundModal.payment?.transactionId}</span>
              </p>
              <div style={{ marginBottom: '0.5rem' }}>
                <label style={{ ...labelStyle, display: 'block', marginBottom: '0.3rem' }}>Refund Amount (max ₹{(refundModal.payment?.remainingRefundable ?? refundModal.payment?.amount)?.toLocaleString()})</label>
                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                  <input type="number" value={refundAmount} onChange={e => setRefundAmount(e.target.value)}
                    max={refundModal.payment?.remainingRefundable ?? refundModal.payment?.amount} step="0.01" min="1"
                    style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '150px' }} />
                  <button className="btn btn-sm btn-secondary" onClick={() => setRefundAmount(String(refundModal.payment?.remainingRefundable ?? refundModal.payment?.amount ?? 0))}>
                    Full Amount
                  </button>
                </div>
              </div>
              <div style={{ marginBottom: '0.75rem' }}>
                <label style={{ ...labelStyle, display: 'block', marginBottom: '0.3rem' }}>Reason</label>
                <input value={refundReason} onChange={e => setRefundReason(e.target.value)}
                  placeholder="Refund reason..."
                  style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '100%', maxWidth: '350px' }} />
              </div>
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button className="btn btn-sm btn-danger" onClick={handleRefund} disabled={refunding}>
                  {refunding ? 'Processing...' : 'Submit Refund'}
                </button>
                <button className="btn btn-sm btn-secondary" onClick={() => setRefundModal({ open: false, payment: null })}>Cancel</button>
              </div>
            </div>
          )}

          {/* Add Additional Payment */}
          {b.status !== 'CANCELLED' && b.status !== 'COMPLETED' && b.status !== 'NO_SHOW' && (
            <div style={{ marginTop: '1rem', paddingTop: '1rem', borderTop: '1px solid var(--border)' }}>
              {!showAddPayment ? (
                <button className="btn btn-sm btn-secondary" onClick={() => setShowAddPayment(true)}>
                  ➕ Add Additional Payment
                </button>
              ) : (
                <div style={{ padding: '0.75rem', background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)' }}>
                  <h4 style={{ marginBottom: '0.4rem', fontSize: '0.9rem' }}>Add Additional Payment</h4>
                  {(() => {
                    const remainBalance = Math.max(0, (b.balanceDue != null) ? b.balanceDue : ((b.totalAmount || 0) - (b.collectedAmount || 0)));
                    return (
                      <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap', marginBottom: '0.6rem', padding: '0.4rem 0.6rem', background: 'var(--bg)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)' }}>
                        <span style={{ fontSize: '0.78rem', color: 'var(--text-secondary)' }}>
                          Total: <strong>₹{(b.totalAmount || 0).toLocaleString()}</strong>
                          {' ∙ '}Collected: <strong>₹{(b.collectedAmount || 0).toLocaleString()}</strong>
                          {' ∙ '}Remaining: <strong style={{ color: remainBalance > 0 ? 'var(--success, #00b894)' : 'var(--text-muted)' }}>₹{remainBalance.toLocaleString()}</strong>
                        </span>
                        <button className="btn btn-sm btn-secondary"
                          style={{ padding: '0.2rem 0.5rem', fontSize: '0.75rem' }}
                          disabled={remainBalance <= 0}
                          onClick={() => setAddPaymentForm(f => ({ ...f, amount: String(remainBalance) }))}>
                          Fill Remaining
                        </button>
                      </div>
                    );
                  })()}
                  <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: '0.5rem' }}>
                    <div>
                      <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: '0.2rem' }}>Amount (₹)</label>
                      <input type="number" value={addPaymentForm.amount} onChange={e => setAddPaymentForm(f => ({ ...f, amount: e.target.value }))}
                        placeholder="0" min="1" step="1"
                        style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '120px' }} />
                    </div>
                    <div>
                      <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: '0.2rem' }}>Method</label>
                      <select value={addPaymentForm.method} onChange={e => setAddPaymentForm(f => ({ ...f, method: e.target.value }))}
                        style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem' }}>
                        <option value="CASH">Cash</option>
                        <option value="UPI">UPI</option>
                        <option value="CARD">Card</option>
                        <option value="BANK_TRANSFER">Bank Transfer</option>
                        <option value="WALLET">Wallet</option>
                      </select>
                    </div>
                  </div>
                  <div style={{ marginBottom: '0.6rem' }}>
                    <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: '0.2rem' }}>Notes (optional)</label>
                    <input value={addPaymentForm.notes} onChange={e => setAddPaymentForm(f => ({ ...f, notes: e.target.value }))}
                      placeholder="Split payment, remaining balance..."
                      style={{ padding: '0.35rem 0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '100%', maxWidth: '300px' }} />
                  </div>
                  <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button className="btn btn-sm btn-primary" onClick={handleAddPayment} disabled={addingPayment}>
                      {addingPayment ? 'Recording...' : 'Record Payment'}
                    </button>
                    <button className="btn btn-sm btn-secondary" onClick={() => { setShowAddPayment(false); setAddPaymentForm({ amount: '', method: 'CASH', notes: '' }); }}>Cancel</button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {tab === 'eventLog' && (
        <div>
          {eventLogLoading ? (
            <p className="ab-event-log-empty">Loading event log...</p>
          ) : eventLog.length === 0 ? (
            <p className="ab-event-log-empty">No events recorded for this booking</p>
          ) : (
            <>
              {eventLog.map((evt, idx) => (
                <div key={idx} className="ab-event-log-entry">
                  <div className="ab-event-log-header">
                    <span className="ab-event-log-type">{evt.eventType?.replace(/_/g, ' ')}</span>
                    <span className="ab-event-log-time">
                      {evt.createdAt ? new Date(evt.createdAt).toLocaleString() : ''}
                    </span>
                  </div>
                  {(evt.previousStatus || evt.newStatus) && (
                    <div className="ab-event-log-status">
                      {evt.previousStatus && <span>{evt.previousStatus}</span>}
                      {evt.previousStatus && evt.newStatus && <span> → </span>}
                      {evt.newStatus && <strong>{evt.newStatus}</strong>}
                    </div>
                  )}
                  {evt.description && (
                    <div className="ab-event-log-desc">
                      {DOMPurify.sanitize(evt.description, { ALLOWED_TAGS: [] })}
                    </div>
                  )}
                  <div className="ab-event-log-meta">
                    by {evt.triggeredBy || 'system'} ({evt.triggeredByRole || 'SYSTEM'})
                    {evt.eventVersion ? ` • v${evt.eventVersion}` : ''}
                  </div>
                </div>
              ))}
              {eventLogTotal > 1 && (
                <div className="ab-event-log-pagination">
                  <button className="btn btn-secondary btn-sm" disabled={eventLogPage <= 0} onClick={() => setEventLogPage(p => p - 1)}>← Prev</button>
                  <span>Page {eventLogPage + 1} of {eventLogTotal}</span>
                  <button className="btn btn-secondary btn-sm" disabled={eventLogPage >= eventLogTotal - 1} onClick={() => setEventLogPage(p => p + 1)}>Next →</button>
                </div>
              )}
            </>
          )}

          {/* Replay button for super admin */}
          {isSuperAdmin && (
            <div className="ab-event-log-replay">
              <button className="btn btn-secondary btn-sm" onClick={handleReplayBooking} disabled={replaying}
                style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
                {replaying ? 'Rebuilding...' : '🔄 Replay Projection'}
              </button>
              <span className="replay-hint">
                Rebuild this booking's state from its event log
              </span>
            </div>
          )}
        </div>
      )}

      {editing && (
        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem', justifyContent: 'flex-end' }}>
          <button className="btn btn-primary btn-sm" onClick={handleSaveEdits} disabled={saving}>
            {saving ? 'Saving...' : 'Save Changes'}
          </button>
          <button className="btn btn-secondary btn-sm" onClick={() => setEditing(false)}>Cancel</button>
        </div>
      )}
    </>
  );
}

