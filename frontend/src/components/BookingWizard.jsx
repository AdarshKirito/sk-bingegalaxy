import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { bookingService, availabilityService, adminService, authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { format, addDays } from 'date-fns';
import '../pages/BookingPage.css';

/**
 * Shared booking wizard used by both Customer and Admin.
 *
 * Props:
 *  - isAdmin        : boolean — shows customer search (step 0), CASH payment, admin notes
 *  - reinstateData   : object | null — pre-fill data for reinstate flow
 *  - editBookingData : object | null — pre-fill data for edit-reservation flow
 *  - onSubmit        : async (payload) => void — called with the final booking payload
 *  - onCancel        : () => void — called when user clicks Cancel
 */
export default function BookingWizard({ isAdmin = false, reinstateData = null, editBookingData = null, onSubmit, onCancel }) {
  const navigate = useNavigate();
  // Steps: admin has 0–4 (0 = customer), customer has 1–4
  const firstStep = isAdmin ? 0 : 1;
  const [step, setStep] = useState(firstStep);

  // Customer search (admin only)
  const [custQuery, setCustQuery] = useState('');
  const [custResults, setCustResults] = useState([]);
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newCust, setNewCust] = useState({ firstName: '', lastName: '', email: '', phone: '' });
  const [custSearching, setCustSearching] = useState(false);

  // Booking data
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [availability, setAvailability] = useState([]);
  const [rawSlots, setRawSlots] = useState([]);
  const [bookedSlots, setBookedSlots] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [imagePopup, setImagePopup] = useState(null);
  const [resolvedPricing, setResolvedPricing] = useState(null);
  const [activeRateCodes, setActiveRateCodes] = useState([]);
  const [selectedRateCodeId, setSelectedRateCodeId] = useState('');

  const [form, setForm] = useState({
    eventTypeId: '',
    bookingDate: '',
    startTime: '',       // now stores minutes from midnight (e.g. 600 = 10:00, 630 = 10:30)
    durationMinutes: 120, // default 2 hours = 120 minutes
    numberOfGuests: 1,
    addOns: [],
    specialNotes: '',
    adminNotes: '',
    paymentMethod: isAdmin ? 'CASH' : 'UPI',
  });

  // Pre-fill from reinstate data
  useEffect(() => {
    const data = reinstateData || editBookingData;
    if (data) {
      if (data.customerId && isAdmin) {
        setSelectedCustomer({
          id: data.customerId,
          firstName: data.customerName?.split(' ')[0] || '',
          lastName: data.customerName?.split(' ').slice(1).join(' ') || '',
          email: data.customerEmail || '',
          phone: data.customerPhone || '',
        });
      }
      const eventId = data.eventTypeId || data.eventType?.id || '';
      // Parse startTime (e.g. "08:00" or "08:30:00") to get minutes from midnight
      let startMin = '';
      if (data.startTime) {
        const parts = String(data.startTime).split(':');
        const h = parseInt(parts[0], 10);
        const m = parseInt(parts[1] || '0', 10);
        if (!isNaN(h)) startMin = h * 60 + (isNaN(m) ? 0 : m);
      }
      // Resolve durationMinutes from either field
      const durMin = data.durationMinutes || (data.durationHours ? data.durationHours * 60 : 120);
      setForm(f => ({
        ...f,
        eventTypeId: eventId ? Number(eventId) : '',
        bookingDate: data.bookingDate || f.bookingDate,
        startTime: startMin !== '' ? startMin : f.startTime,
        durationMinutes: durMin,
        numberOfGuests: data.numberOfGuests || 1,
        specialNotes: data.specialNotes || '',
        adminNotes: data.adminNotes || '',
        addOns: (data.addOns || []).map(a => ({
          addOnId: a.addOnId || a.id,
          quantity: a.quantity || 1,
          price: a.price || 0,
          name: a.name || '',
        })),
      }));
      // If customer already selected, jump to step 1
      if (isAdmin && data.customerId) {
        setStep(1);
      }
    }
  }, [reinstateData, editBookingData, isAdmin]);

  // Load event types + addons
  useEffect(() => {
    Promise.all([bookingService.getEventTypes(), bookingService.getAddOns()])
      .then(([etRes, aoRes]) => {
        setEventTypes(etRes.data.data || []);
        setAddOns(aoRes.data.data || []);
      })
      .catch(() => toast.error('Failed to load booking options'));
  }, []);

  // Load active rate codes (admin only)
  useEffect(() => {
    if (isAdmin) {
      adminService.getActiveRateCodes()
        .then(res => setActiveRateCodes(res.data.data || []))
        .catch(() => {});
    }
  }, [isAdmin]);

  // Load resolved pricing for customers (non-admin)
  useEffect(() => {
    if (!isAdmin) {
      bookingService.getMyPricing()
        .then(res => setResolvedPricing(res.data.data || null))
        .catch(() => {}); // fallback to default
    }
  }, [isAdmin]);

  // Load resolved pricing for admin when customer is selected (and no rate code override)
  useEffect(() => {
    if (isAdmin && selectedCustomer?.id && !selectedRateCodeId) {
      adminService.resolveCustomerPricing(selectedCustomer.id)
        .then(res => {
          setResolvedPricing(res.data.data || null);
          // Auto-select the customer's assigned rate code if they have one
          if (res.data.data?.pricingSource === 'RATE_CODE' && res.data.data?.rateCodeName) {
            const match = activeRateCodes.find(rc => rc.name === res.data.data.rateCodeName);
            if (match) setSelectedRateCodeId(String(match.id));
          }
        })
        .catch(() => setResolvedPricing(null));
    }
  }, [isAdmin, selectedCustomer]);

  // When admin explicitly selects a rate code, re-resolve pricing
  useEffect(() => {
    if (!isAdmin || !selectedRateCodeId) return;
    adminService.resolveRateCodePricing(selectedRateCodeId)
      .then(res => setResolvedPricing(res.data.data || null))
      .catch(() => setResolvedPricing(null));
  }, [isAdmin, selectedRateCodeId]);

  // Load availability when step 2 reached (or ahead of time)
  useEffect(() => {
    if (step >= 2 || availability.length > 0) return; // load once
    const from = format(new Date(), 'yyyy-MM-dd');
    const to = format(addDays(new Date(), isAdmin ? 60 : 30), 'yyyy-MM-dd');
    availabilityService.getDates(from, to)
      .then(res => setAvailability(res.data.data || []))
      .catch(() => toast.error('Failed to load available dates. Please try again.'));
  }, [step, isAdmin, availability.length]);

  // Also load when we reach step 2 if not loaded
  useEffect(() => {
    if (step === 2 && availability.length === 0) {
      const from = format(new Date(), 'yyyy-MM-dd');
      const to = format(addDays(new Date(), isAdmin ? 60 : 30), 'yyyy-MM-dd');
      availabilityService.getDates(from, to)
        .then(res => setAvailability(res.data.data || []))
        .catch(() => toast.error('Failed to load available dates. Please try again.'));
    }
  }, [step, isAdmin, availability.length]);

  // Load slots when date changes
  useEffect(() => {
    if (form.bookingDate) {
      availabilityService.getSlots(form.bookingDate)
        .then(res => setRawSlots(res.data.data?.availableSlots || []))
        .catch(() => toast.error('Failed to load time slots for the selected date.'));
      const slotsApi = isAdmin ? adminService.getBookedSlots : bookingService.getBookedSlots;
      slotsApi(form.bookingDate)
        .then(res => setBookedSlots(res.data.data || []))
        .catch(() => setBookedSlots([]));
    }
  }, [form.bookingDate, isAdmin]);

  const selectedEvent = eventTypes.find(e => e.id === Number(form.eventTypeId));

  // Duration options: admin 30min–12hr, user 2hr–12hr (in 30-min steps)
  const durationOptions = (() => {
    const opts = [];
    if (isAdmin) {
      for (let m = 30; m <= 720; m += 30) opts.push(m); // 30, 60, 90 ... 720
    } else {
      for (let m = 120; m <= 720; m += 30) opts.push(m); // 120, 150, 180 ... 720
    }
    return opts;
  })();

  // Duration-based time slot generation (30-min granularity)
  const durationSlots = (() => {
    if (!rawSlots.length || !form.durationMinutes) return [];
    const durMin = Number(form.durationMinutes);
    const isToday = form.bookingDate === format(new Date(), 'yyyy-MM-dd');
    const nowMinutes = new Date().getHours() * 60 + new Date().getMinutes();
    // Build set of booked 30-min indices (minute / 30)
    const bookedHalfHours = new Set();
    const editRef = editBookingData?.bookingRef;
    bookedSlots.forEach(bs => {
      if (editRef && bs.bookingRef === editRef) return;
      const start = bs.startMinute != null ? bs.startMinute : bs.startHour * 60;
      const dur = bs.durationMinutes != null ? bs.durationMinutes : bs.durationHours * 60;
      for (let m = start; m < start + dur; m += 30) bookedHalfHours.add(Math.floor(m / 30));
    });
    const slots = [];
    // The currently-edited booking's start time (always include it)
    const editStartMin = editBookingData?.startTime
      ? (() => { const p = String(editBookingData.startTime).split(':'); return parseInt(p[0],10)*60 + parseInt(p[1]||'0',10); })()
      : null;
    // Iterate in 30-min steps
    for (let startMin = 0; startMin + durMin <= 24 * 60; startMin += 30) {
      // Allow the edit booking's original time even if it's past
      if (isToday && startMin < nowMinutes + 30 && startMin !== editStartMin) continue; // must be at least 30 min from now
      let allAvailable = true;
      for (let m = startMin; m < startMin + durMin; m += 30) {
        const slot = rawSlots.find(s => {
          const sm = s.startMinute != null ? s.startMinute : s.startHour * 60;
          return sm === m;
        });
        if (!slot || !slot.available || bookedHalfHours.has(Math.floor(m / 30))) { allAvailable = false; break; }
      }
      if (allAvailable) slots.push(startMin); // startMin = minutes from midnight
    }
    return slots;
  })();

  const calculateTotal = () => {
    if (!selectedEvent) return 0;
    // Use resolved pricing if available
    const rp = resolvedPricing?.eventPricings?.find(ep => ep.eventTypeId === Number(form.eventTypeId));
    const basePrice = rp ? rp.basePrice : selectedEvent.basePrice;
    const hourlyRate = rp ? rp.hourlyRate : selectedEvent.hourlyRate;
    const pricePerGuest = rp ? rp.pricePerGuest : (selectedEvent.pricePerGuest || 0);
    const base = basePrice + (hourlyRate * form.durationMinutes / 60);
    const addOnTotal = form.addOns.reduce((sum, a) => {
      const rap = resolvedPricing?.addonPricings?.find(ap => ap.addOnId === a.addOnId);
      const unitPrice = rap ? rap.price : a.price;
      return sum + (unitPrice * a.quantity);
    }, 0);
    const guestCharge = pricePerGuest * Math.max(form.numberOfGuests - 1, 0);
    return base + addOnTotal + guestCharge;
  };

  const toggleAddOn = (addon) => {
    const exists = form.addOns.find(a => a.addOnId === addon.id);
    if (exists) {
      setForm(f => ({ ...f, addOns: f.addOns.filter(a => a.addOnId !== addon.id) }));
    } else {
      const rap = resolvedPricing?.addonPricings?.find(ap => ap.addOnId === addon.id);
      const price = rap ? rap.price : addon.price;
      setForm(f => ({ ...f, addOns: [...f.addOns, { addOnId: addon.id, quantity: 1, price, name: addon.name }] }));
    }
  };

  // Customer search (admin)
  const handleCustSearch = useCallback(async (q) => {
    if (q.trim().length < 2) { setCustResults([]); return; }
    setCustSearching(true);
    try {
      const res = await authService.searchCustomers(q);
      setCustResults(res.data.data || res.data || []);
    } catch { setCustResults([]); }
    setCustSearching(false);
  }, []);

  useEffect(() => {
    if (!isAdmin) return;
    const timer = setTimeout(() => handleCustSearch(custQuery), 400);
    return () => clearTimeout(timer);
  }, [custQuery, handleCustSearch, isAdmin]);

  const selectCustomer = (cust) => {
    setSelectedCustomer(cust);
    setCustQuery('');
    setCustResults([]);
    setShowCreateForm(false);
    setSelectedRateCodeId('');
    setResolvedPricing(null);
  };

  const handleCreateCustomer = async () => {
    if (!newCust.firstName.trim()) { toast.error('First name is required to create a customer'); return; }
    if (!newCust.email.trim()) { toast.error('Email is required to create a customer'); return; }
    if (newCust.email.trim() && !/\S+@\S+\.\S+/.test(newCust.email)) { toast.error('Please enter a valid email address'); return; }
    try {
      const res = await authService.adminCreateCustomer({
        firstName: newCust.firstName,
        lastName: newCust.lastName,
        email: newCust.email,
        phone: newCust.phone,
        password: 'Temp@1234',
      });
      const created = res.data.data || res.data;
      setSelectedCustomer(created);
      setShowCreateForm(false);
      setNewCust({ firstName: '', lastName: '', email: '', phone: '' });
      toast.success('Customer created successfully');
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to create customer. Please try again.');
    }
  };

  const handleSubmit = async () => {
    if (isAdmin && !selectedCustomer) { toast.error('Please select a customer before confirming'); return; }
    if (!form.eventTypeId) { toast.error('Please select an event type'); return; }
    if (!form.bookingDate) { toast.error('Please select a booking date'); return; }
    if (form.startTime === '') { toast.error('Please select a time slot'); return; }
    if (form.numberOfGuests < 1) { toast.error('Number of guests must be at least 1'); return; }
    setError('');
    setLoading(true);
    try {
      const startMin = Number(form.startTime); // minutes from midnight
      const startH = Math.floor(startMin / 60);
      const startM = startMin % 60;
      const durMin = Number(form.durationMinutes);
      const payload = {
        eventTypeId: Number(form.eventTypeId),
        bookingDate: form.bookingDate,
        startTime: String(startH).padStart(2, '0') + ':' + String(startM).padStart(2, '0'),
        durationMinutes: durMin,
        durationHours: Math.floor(durMin / 60), // backward compat
        numberOfGuests: Number(form.numberOfGuests),
        addOns: form.addOns.map(a => ({ addOnId: a.addOnId, quantity: a.quantity })),
        specialNotes: form.specialNotes,
      };
      if (isAdmin) {
        payload.customerId = selectedCustomer.id;
        payload.customerName = `${selectedCustomer.firstName} ${selectedCustomer.lastName || ''}`.trim();
        payload.customerEmail = selectedCustomer.email;
        payload.customerPhone = selectedCustomer.phone || '';
        payload.adminNotes = form.adminNotes;
        // Don't resend paymentMethod when editing an already-paid booking
        const alreadyPaid = editBookingData?.paymentStatus === 'SUCCESS' || editBookingData?.paymentStatus === 'PARTIALLY_REFUNDED';
        if (!alreadyPaid) {
          payload.paymentMethod = form.paymentMethod;
        }
      }
      await onSubmit(payload);
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Booking failed. Please try again.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  // Helper: format minutes from midnight as HH:MM
  const fmtTime = (m) => {
    const h = Math.floor(m / 60);
    const min = m % 60;
    return String(h).padStart(2, '0') + ':' + String(min).padStart(2, '0');
  };
  // Helper: format duration in minutes as human-readable
  const fmtDuration = (m) => {
    const h = Math.floor(m / 60);
    const min = m % 60;
    if (h > 0 && min > 0) return `${h}hr ${min}m`;
    if (h > 0) return `${h}hr`;
    return `${min}m`;
  };

  const popupOverlay = {
    position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.85)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000,
    cursor: 'pointer', padding: '1rem',
  };

  const inputStyle = {
    padding: '0.55rem 0.8rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)',
    background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.88rem', width: '100%',
  };
  const labelStyle = { fontWeight: 600, marginBottom: '0.4rem', display: 'block', fontSize: '0.85rem' };

  const totalSteps = isAdmin ? 5 : 4; // admin: 0-4, customer: 1-4
  const dots = isAdmin ? [0, 1, 2, 3, 4] : [1, 2, 3, 4];
  const stepLabels = isAdmin
    ? { 0: 'Customer', 1: 'Event', 2: 'Date & Time', 3: 'Add-Ons', 4: 'Review' }
    : { 1: 'Event', 2: 'Date & Time', 3: 'Add-Ons', 4: 'Review' };

  return (
    <div className="container booking-page">
      <div className="page-header">
        <h1>{editBookingData ? 'Edit Reservation' : isAdmin ? 'Book Walk-In Experience' : 'Book Your Experience'}</h1>
        <p>Step {isAdmin ? step + 1 : step} of {totalSteps}</p>
      </div>

      <div className="booking-steps">
        {dots.map(s => (
          <div key={s} className={`step-dot ${step >= s ? 'active' : ''}`}
               title={stepLabels[s]}>
            {isAdmin ? s + 1 : s}
          </div>
        ))}
      </div>

      {error && <div className="error-message">{error}</div>}

      {/* Image Popup Carousel */}
      {imagePopup && (
        <div style={popupOverlay} onClick={() => setImagePopup(null)}>
          <div style={{ maxWidth: '90vw', maxHeight: '85vh', position: 'relative', textAlign: 'center' }} onClick={e => e.stopPropagation()}>
            <button onClick={() => setImagePopup(null)}
              style={{ position: 'absolute', top: '-12px', right: '-12px', background: 'var(--danger)', color: '#fff', border: 'none', borderRadius: '50%', width: '30px', height: '30px', cursor: 'pointer', fontWeight: 700, zIndex: 2 }}>×</button>
            {imagePopup.urls.length > 1 && (
              <>
                <button onClick={() => setImagePopup(p => ({ ...p, index: (p.index - 1 + p.urls.length) % p.urls.length }))}
                  style={{ position: 'absolute', left: '-40px', top: '50%', transform: 'translateY(-50%)', background: 'rgba(255,255,255,0.2)', color: '#fff', border: 'none', borderRadius: '50%', width: '36px', height: '36px', cursor: 'pointer', fontSize: '1.3rem', zIndex: 2 }}>‹</button>
                <button onClick={() => setImagePopup(p => ({ ...p, index: (p.index + 1) % p.urls.length }))}
                  style={{ position: 'absolute', right: '-40px', top: '50%', transform: 'translateY(-50%)', background: 'rgba(255,255,255,0.2)', color: '#fff', border: 'none', borderRadius: '50%', width: '36px', height: '36px', cursor: 'pointer', fontSize: '1.3rem', zIndex: 2 }}>›</button>
              </>
            )}
            <img src={imagePopup.urls[imagePopup.index]} alt={imagePopup.name}
              style={{ maxWidth: '90vw', maxHeight: '80vh', borderRadius: 'var(--radius-md)', objectFit: 'contain' }} />
            <p style={{ color: '#fff', marginTop: '0.5rem', fontWeight: 600 }}>{imagePopup.name} {imagePopup.urls.length > 1 ? `(${imagePopup.index + 1}/${imagePopup.urls.length})` : ''}</p>
          </div>
        </div>
      )}

      {/* ── STEP 0: Customer (Admin only) ── */}
      {isAdmin && step === 0 && (
        <div className="booking-section">
          <h2>Select Customer</h2>
          {selectedCustomer ? (
            <div className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem' }}>
              <div>
                <strong>{selectedCustomer.firstName} {selectedCustomer.lastName || ''}</strong><br />
                <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>{selectedCustomer.email}</span>
                {selectedCustomer.phone && <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}> | {selectedCustomer.phone}</span>}
              </div>
              <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                <button className="btn btn-secondary btn-sm" onClick={() => navigate(`/admin/users-config/${selectedCustomer.id}`)} title="Edit customer details" style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem' }}>✏️</button>
                <button className="btn btn-secondary btn-sm" onClick={() => { setSelectedCustomer(null); setSelectedRateCodeId(''); setResolvedPricing(null); }}>Change</button>
              </div>
            </div>
          ) : (
            <div className="card" style={{ padding: '1.25rem' }}>
              <div style={{ position: 'relative' }}>
                <input value={custQuery} onChange={e => setCustQuery(e.target.value)}
                  style={inputStyle} placeholder="Search customer by name, email, or phone..." />
                {custSearching && <span style={{ position: 'absolute', right: '10px', top: '12px', color: 'var(--text-muted)', fontSize: '0.8rem' }}>Searching...</span>}
                {custResults.length > 0 && (
                  <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)', maxHeight: '200px', overflowY: 'auto', zIndex: 10 }}>
                    {custResults.map(c => (
                      <div key={c.id} onClick={() => selectCustomer(c)}
                        style={{ padding: '0.6rem 0.8rem', cursor: 'pointer', borderBottom: '1px solid var(--border)', fontSize: '0.85rem' }}>
                        <strong>{c.firstName} {c.lastName || ''}</strong> <span style={{ color: 'var(--text-muted)' }}>— {c.email}</span>
                        {c.phone && <span style={{ color: 'var(--text-muted)' }}> | {c.phone}</span>}
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <div style={{ marginTop: '0.75rem' }}>
                <button className="btn btn-secondary btn-sm" onClick={() => setShowCreateForm(!showCreateForm)}>
                  {showCreateForm ? 'Cancel' : '+ Create New Customer'}
                </button>
              </div>
              {showCreateForm && (
                <div style={{ marginTop: '0.75rem', padding: '1rem', background: 'var(--bg-input)', borderRadius: 'var(--radius-sm)' }}>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
                    <div><label style={labelStyle}>First Name *</label><input style={inputStyle} value={newCust.firstName} onChange={e => setNewCust({ ...newCust, firstName: e.target.value })} /></div>
                    <div><label style={labelStyle}>Last Name</label><input style={inputStyle} value={newCust.lastName} onChange={e => setNewCust({ ...newCust, lastName: e.target.value })} /></div>
                    <div><label style={labelStyle}>Email *</label><input type="email" style={inputStyle} value={newCust.email} onChange={e => setNewCust({ ...newCust, email: e.target.value })} /></div>
                    <div><label style={labelStyle}>Phone</label><input style={inputStyle} value={newCust.phone} onChange={e => setNewCust({ ...newCust, phone: e.target.value })} /></div>
                  </div>
                  <button className="btn btn-primary btn-sm" style={{ marginTop: '0.75rem' }} onClick={handleCreateCustomer}>Create Customer</button>
                </div>
              )}
            </div>
          )}

          {/* Rate Code Override (visible when customer is selected) */}
          {selectedCustomer && activeRateCodes.length > 0 && (
            <div className="card" style={{ padding: '1rem', marginTop: '1rem' }}>
              <label style={labelStyle}>Apply Rate Code</label>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                <select
                  value={selectedRateCodeId}
                  onChange={e => {
                    const val = e.target.value;
                    setSelectedRateCodeId(val);
                    if (!val) {
                      // Reset to customer's own pricing
                      adminService.resolveCustomerPricing(selectedCustomer.id)
                        .then(res => setResolvedPricing(res.data.data || null))
                        .catch(() => setResolvedPricing(null));
                    }
                  }}
                  style={{ ...inputStyle, maxWidth: '300px' }}>
                  <option value="">Customer Default</option>
                  {activeRateCodes.map(rc => (
                    <option key={rc.id} value={rc.id}>{rc.name}{rc.description ? ` — ${rc.description}` : ''}</option>
                  ))}
                </select>
                {resolvedPricing?.pricingSource && resolvedPricing.pricingSource !== 'DEFAULT' && (
                  <span style={{ fontSize: '0.78rem', color: '#818cf8', fontWeight: 600 }}>
                    {resolvedPricing.pricingSource === 'RATE_CODE' ? `📋 ${resolvedPricing.rateCodeName}` : '👤 Custom Pricing'}
                  </span>
                )}
              </div>
              <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.35rem' }}>
                Override pricing for this booking. Event and add-on prices will update in subsequent steps.
              </p>
            </div>
          )}

          <div className="booking-nav">
            {onCancel && <button className="btn btn-secondary" onClick={onCancel}>Cancel</button>}
            <button className="btn btn-primary" onClick={() => {
              if (!selectedCustomer) { toast.error('Please select or create a customer before proceeding'); return; }
              setStep(1);
            }}>
              Next: Choose Event Type
            </button>
          </div>
        </div>
      )}

      {/* ── STEP 1: Event Type ── */}
      {step === 1 && (
        <div className="booking-section">
          <h2>Choose Event Type</h2>
          <div className="grid-3">
            {eventTypes.map(et => (
              <div key={et.id}
                className={`card event-type-card ${form.eventTypeId == et.id ? 'selected' : ''}`}
                onClick={() => setForm(f => ({ ...f, eventTypeId: et.id }))}>
                {et.imageUrls?.length > 0 && et.imageUrls[0] && (
                  <div style={{ marginBottom: '0.75rem', borderRadius: 'var(--radius-sm)', overflow: 'hidden', cursor: 'zoom-in' }}
                    onClick={e => { e.stopPropagation(); setImagePopup({ urls: et.imageUrls, name: et.name, index: 0 }); }}>
                    <img src={et.imageUrls[0]} alt={et.name} style={{ width: '100%', height: '140px', objectFit: 'cover' }} />
                    {et.imageUrls.length > 1 && <div style={{ textAlign: 'center', fontSize: '0.7rem', color: 'var(--text-muted)', padding: '0.15rem' }}>📸 {et.imageUrls.length} images — click to view</div>}
                  </div>
                )}
                <h3>{et.name}</h3>
                <p>{et.description}</p>
                {(() => {
                  const rp = resolvedPricing?.eventPricings?.find(ep => ep.eventTypeId === et.id);
                  const bp = rp ? rp.basePrice : et.basePrice;
                  const hr = rp ? rp.hourlyRate : et.hourlyRate;
                  const isCustom = rp && rp.source !== 'DEFAULT';
                  return (
                    <p className="et-price">
                      ₹{bp?.toLocaleString()} + ₹{hr}/hr
                      {isCustom && <span style={{ fontSize: '0.7rem', marginLeft: '0.4rem', color: '#818cf8' }}>({rp.source === 'RATE_CODE' ? resolvedPricing.rateCodeName : 'Custom'})</span>}
                    </p>
                  );
                })()}
              </div>
            ))}
          </div>
          <div className="booking-nav">
            {isAdmin && <button className="btn btn-secondary" onClick={() => setStep(0)}>Back: Customer</button>}
            {!isAdmin && onCancel && <button className="btn btn-secondary" onClick={onCancel}>Cancel</button>}
            <button className="btn btn-primary" onClick={() => {
              if (!form.eventTypeId) { toast.error('Please select an event type to continue'); return; }
              setStep(2);
            }}>
              Next: Choose Date & Time
            </button>
          </div>
        </div>
      )}

      {/* ── STEP 2: Date & Time ── */}
      {step === 2 && (
        <div className="booking-section">
          <h2>Select Date & Time</h2>
          <div className="input-group" style={{ maxWidth: '300px', marginBottom: '1.25rem' }}>
            <label>Duration</label>
            <select value={form.durationMinutes} onChange={e => setForm(f => ({ ...f, durationMinutes: Number(e.target.value), startTime: '' }))}>
              {durationOptions.map(m => <option key={m} value={m}>{fmtDuration(m)}</option>)}
            </select>
          </div>
          <div className="input-group" style={{ maxWidth: '300px', marginBottom: '1.25rem' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
              Number of Guests
            </label>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
              <button type="button" className="btn btn-secondary btn-sm" style={{ width: '36px', height: '36px', padding: 0, fontSize: '1.2rem', fontWeight: 700 }}
                disabled={form.numberOfGuests <= 1}
                onClick={() => setForm(f => ({ ...f, numberOfGuests: Math.max(1, f.numberOfGuests - 1) }))}>−</button>
              <span style={{ fontSize: '1.1rem', fontWeight: 700, minWidth: '28px', textAlign: 'center' }}>{form.numberOfGuests}</span>
              <button type="button" className="btn btn-secondary btn-sm" style={{ width: '36px', height: '36px', padding: 0, fontSize: '1.2rem', fontWeight: 700 }}
                disabled={form.numberOfGuests >= 100}
                onClick={() => setForm(f => ({ ...f, numberOfGuests: Math.min(100, f.numberOfGuests + 1) }))}>+</button>
            </div>
            {(() => {
              const rep = resolvedPricing?.eventPricings?.find(ep => ep.eventTypeId === selectedEvent?.id);
              const ppg = rep ? rep.pricePerGuest : selectedEvent?.pricePerGuest;
              return ppg > 0 && form.numberOfGuests > 1 ? (
                <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.35rem' }}>
                  Additional guest charge: ₹{ppg.toLocaleString()} × {form.numberOfGuests - 1} = ₹{((form.numberOfGuests - 1) * ppg).toLocaleString()}
                </p>
              ) : null;
            })()}
          </div>
          <div className="grid-2">
            <div>
              <h3 style={{ marginBottom: '0.75rem' }}>Available Dates</h3>
              <div className="date-grid">
                {availability.filter(d => !d.fullyBlocked).map(d => (
                  <button key={d.date}
                    className={`date-btn ${form.bookingDate === d.date ? 'selected' : ''}`}
                    onClick={() => setForm(f => ({ ...f, bookingDate: d.date, startTime: '' }))}>
                    {format(new Date(d.date + 'T00:00:00'), 'MMM dd, EEE')}
                  </button>
                ))}
              </div>
            </div>
            <div>
              <h3 style={{ marginBottom: '0.75rem' }}>Available Time Slots</h3>
              {form.bookingDate ? (
                durationSlots.length > 0 ? (
                  <div className="slot-grid">
                    {durationSlots.map(startMin => (
                      <button key={startMin}
                        className={`slot-btn ${form.startTime === startMin ? 'selected' : ''}`}
                        onClick={() => setForm(f => ({ ...f, startTime: startMin }))}>
                        {fmtTime(startMin)} – {fmtTime(startMin + Number(form.durationMinutes))}
                      </button>
                    ))}
                  </div>
                ) : <p style={{ color: 'var(--text-muted)' }}>No available slots for this date with {fmtDuration(form.durationMinutes)} duration</p>
              ) : <p style={{ color: 'var(--text-muted)' }}>Select a date first</p>}
            </div>
          </div>
          <div className="booking-nav">
            <button className="btn btn-secondary" onClick={() => setStep(1)}>Back</button>
            <button className="btn btn-primary" onClick={() => {
              if (!form.bookingDate) { toast.error('Please select a date before proceeding'); return; }
              if (form.startTime === '') { toast.error('Please select a time slot before proceeding'); return; }
              setStep(3);
            }}>
              Next: Add-Ons
            </button>
          </div>
        </div>
      )}

      {/* ── STEP 3: Add-Ons + Notes ── */}
      {step === 3 && (
        <div className="booking-section">
          <h2>Choose Add-Ons</h2>
          <div className="grid-3">
            {addOns.map(ao => {
              const selected = form.addOns.find(a => a.addOnId === ao.id);
              return (
                <div key={ao.id}
                  className={`card addon-card ${selected ? 'selected' : ''}`}
                  onClick={() => toggleAddOn(ao)}>
                  {ao.imageUrls?.length > 0 && ao.imageUrls[0] && (
                    <div style={{ marginBottom: '0.5rem', borderRadius: 'var(--radius-sm)', overflow: 'hidden', cursor: 'zoom-in' }}
                      onClick={e => { e.stopPropagation(); setImagePopup({ urls: ao.imageUrls, name: ao.name, index: 0 }); }}>
                      <img src={ao.imageUrls[0]} alt={ao.name} style={{ width: '100%', height: '100px', objectFit: 'cover' }} />
                      {ao.imageUrls.length > 1 && <div style={{ textAlign: 'center', fontSize: '0.65rem', color: 'var(--text-muted)' }}>📸 {ao.imageUrls.length} images</div>}
                    </div>
                  )}
                  <h4>{ao.name}</h4>
                  <p>{ao.description}</p>
                  {(() => {
                    const rap = resolvedPricing?.addonPricings?.find(ap => ap.addOnId === ao.id);
                    const ap = rap ? rap.price : ao.price;
                    const isCustom = rap && rap.source !== 'DEFAULT';
                    return (
                      <p className="addon-price">
                        ₹{ap?.toLocaleString()}
                        {isCustom && <span style={{ fontSize: '0.7rem', marginLeft: '0.4rem', color: '#818cf8' }}>({rap.source === 'RATE_CODE' ? resolvedPricing.rateCodeName : 'Custom'})</span>}
                      </p>
                    );
                  })()}
                  {selected && <span className="badge badge-success">Added</span>}
                </div>
              );
            })}
          </div>

          <div className="input-group" style={{ marginTop: '1.5rem', maxWidth: '500px' }}>
            <label>Special Notes (optional)</label>
            <textarea rows={3} value={form.specialNotes}
              onChange={e => setForm(f => ({ ...f, specialNotes: e.target.value }))}
              placeholder="Any special requests or instructions..." />
          </div>

          {isAdmin && (
            <div className="input-group" style={{ marginTop: '1rem', maxWidth: '500px' }}>
              <label>Admin Notes (internal)</label>
              <textarea rows={2} value={form.adminNotes}
                onChange={e => setForm(f => ({ ...f, adminNotes: e.target.value }))}
                placeholder="Internal notes (not visible to customer)..." />
            </div>
          )}

          <div className="booking-nav">
            <button className="btn btn-secondary" onClick={() => setStep(2)}>Back</button>
            <button className="btn btn-primary" onClick={() => setStep(4)}>
              Next: Review & Confirm
            </button>
          </div>
        </div>
      )}

      {/* ── STEP 4: Review & Confirm ── */}
      {step === 4 && (
        <div className="booking-section">
          <h2>Review Your Booking</h2>
          <div className="card review-card">
            {isAdmin && selectedCustomer && (
              <div className="review-row">
                <span>Customer</span>
                <span>{selectedCustomer.firstName} {selectedCustomer.lastName || ''} ({selectedCustomer.email})</span>
              </div>
            )}
            <div className="review-row"><span>Event Type</span><span>{selectedEvent?.name}</span></div>
            <div className="review-row"><span>Date</span><span>{form.bookingDate}</span></div>
            <div className="review-row">
              <span>Time</span>
              <span>{fmtTime(Number(form.startTime))} – {fmtTime(Number(form.startTime) + Number(form.durationMinutes))}</span>
            </div>
            <div className="review-row"><span>Duration</span><span>{fmtDuration(form.durationMinutes)}</span></div>
            <div className="review-row">
              <span style={{ display: 'flex', alignItems: 'center', gap: '0.3rem' }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                Guests
              </span>
              <span>{form.numberOfGuests}</span>
            </div>
            {(() => {
              const rep = resolvedPricing?.eventPricings?.find(ep => ep.eventTypeId === selectedEvent?.id);
              const ppg = rep ? rep.pricePerGuest : selectedEvent?.pricePerGuest;
              return ppg > 0 && form.numberOfGuests > 1 ? (
                <div className="review-row" style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                  <span>Guest Charge ({form.numberOfGuests - 1} extra × ₹{ppg.toLocaleString()})</span>
                  <span>₹{((form.numberOfGuests - 1) * ppg).toLocaleString()}</span>
                </div>
              ) : null;
            })()}
            {form.addOns.length > 0 && (
              <div className="review-row">
                <span>Add-Ons</span>
                <span>{form.addOns.map(a => a.name).join(', ')}</span>
              </div>
            )}
            {form.specialNotes && <div className="review-row"><span>Notes</span><span>{form.specialNotes}</span></div>}
            {isAdmin && form.adminNotes && <div className="review-row"><span>Admin Notes</span><span>{form.adminNotes}</span></div>}

            {/* Payment Method / Payment Status */}
            <hr style={{ borderColor: 'var(--border)', margin: '1rem 0' }} />
            {isAdmin ? (
              editBookingData && (editBookingData.paymentStatus === 'SUCCESS' || editBookingData.paymentStatus === 'PARTIALLY_REFUNDED') ? (
                <div className="review-row" style={{ alignItems: 'center' }}>
                  <span>Payment Status</span>
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.2rem' }}>
                    <span className={`badge ${editBookingData.paymentStatus === 'SUCCESS' ? 'badge-success' : 'badge-info'}`}>
                      {editBookingData.paymentStatus === 'SUCCESS' ? '\u2713 Paid' : '\u21a9 Partially Refunded'}
                    </span>
                    <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                      Manage payments from the Payment tab after saving.
                    </span>
                  </div>
                </div>
              ) : (
                <>
                  <div className="review-row" style={{ alignItems: 'center' }}>
                    <span>Payment Method</span>
                    <select value={form.paymentMethod} onChange={e => setForm(f => ({ ...f, paymentMethod: e.target.value }))}
                      style={{ padding: '0.4rem 0.6rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem' }}>
                      <option value="CASH">Cash</option>
                      <option value="UPI">UPI</option>
                      <option value="CARD">Card</option>
                      <option value="BANK_TRANSFER">Bank Transfer</option>
                      <option value="WALLET">Wallet</option>
                    </select>
                  </div>
                  {form.paymentMethod === 'CASH' && (
                    <p style={{ fontSize: '0.8rem', color: 'var(--success)', marginTop: '0.3rem' }}>Cash payment \u2014 booking will be auto-confirmed</p>
                  )}
                </>
              )
            ) : null}

            <hr style={{ borderColor: 'var(--border)', margin: '1rem 0' }} />
            <div className="review-row total">
              <span>Estimated Total</span>
              <span>₹{calculateTotal().toLocaleString()}</span>
            </div>
          </div>
          <div className="booking-nav">
            <button className="btn btn-secondary" onClick={() => setStep(3)}>Back</button>
            <button className="btn btn-primary" onClick={handleSubmit} disabled={loading}>
              {loading ? 'Processing...' : editBookingData ? 'Update Reservation' : 'Confirm Booking'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
