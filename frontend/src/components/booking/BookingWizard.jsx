import { useState, useEffect } from 'react';
import { bookingService, availabilityService, adminService } from '../../services/endpoints';
import { toast } from 'react-toastify';
import { format, addDays } from 'date-fns';

import ImagePopup from './ImagePopup';
import StepCustomer from './StepCustomer';
import StepEvent from './StepEvent';
import StepDateTime from './StepDateTime';
import StepAddOns from './StepAddOns';
import StepReview from './StepReview';
import '../../pages/BookingPage.css';

export { default as ImagePopup } from './ImagePopup';
export { default as StepCustomer } from './StepCustomer';
export { default as StepEvent } from './StepEvent';
export { default as StepDateTime } from './StepDateTime';
export { default as StepAddOns } from './StepAddOns';
export { default as StepReview } from './StepReview';

/**
 * Shared booking wizard used by both Customer and Admin.
 *
 * Props:
 *  - isAdmin        : boolean — shows customer search (step 0), CASH payment, admin notes
 *  - reinstateData   : object | null — pre-fill data for reinstate flow
 *  - editBookingData : object | null — pre-fill data for edit-reservation flow
 *  - prefillData      : object | null — pre-fill data for repeat booking flow
 *  - onSubmit        : async (payload) => void — called with the final booking payload
 *  - onCancel        : () => void — called when user clicks Cancel
 */
export default function BookingWizard({ isAdmin = false, reinstateData = null, editBookingData = null, prefillData = null, initialEventTypeId = null, onSubmit, onCancel }) {
  const firstStep = isAdmin ? 0 : 1;
  const [step, setStep] = useState(firstStep);

  // Customer (admin only)
  const [selectedCustomer, setSelectedCustomer] = useState(null);

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
    eventTypeId: initialEventTypeId || '',
    bookingDate: '',
    startTime: '',
    durationMinutes: 120,
    numberOfGuests: 1,
    addOns: [],
    specialNotes: '',
    adminNotes: '',
    paymentMethod: isAdmin ? 'CASH' : 'UPI',
  });

  // Pre-fill from reinstate/edit data
  useEffect(() => {
    const data = reinstateData || editBookingData || prefillData;
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
      let startMin = '';
      if (data.startTime) {
        const parts = String(data.startTime).split(':');
        const h = parseInt(parts[0], 10);
        const m = parseInt(parts[1] || '0', 10);
        if (!isNaN(h)) startMin = h * 60 + (isNaN(m) ? 0 : m);
      }
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
      if (isAdmin && data.customerId) setStep(1);
      if (!isAdmin && !editBookingData && !reinstateData && eventId) setStep(2);
    }
  }, [reinstateData, editBookingData, prefillData, isAdmin]);

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
        .catch(() => {});
    }
  }, [isAdmin]);

  // Load resolved pricing for admin when customer is selected
  useEffect(() => {
    if (isAdmin && selectedCustomer?.id && !selectedRateCodeId) {
      adminService.resolveCustomerPricing(selectedCustomer.id)
        .then(res => {
          setResolvedPricing(res.data.data || null);
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

  // Load availability (once, before step 2 is reached or when on step 2)
  useEffect(() => {
    if (availability.length > 0) return;
    if (step > 2) return;
    const from = format(new Date(), 'yyyy-MM-dd');
    const to = format(addDays(new Date(), isAdmin ? 60 : 30), 'yyyy-MM-dd');
    availabilityService.getDates(from, to)
      .then(res => setAvailability(res.data.data || []))
      .catch(() => toast.error('Failed to load available dates. Please try again.'));
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

  // Duration options
  const durationOptions = (() => {
    const opts = [];
    if (isAdmin) {
      for (let m = 30; m <= 720; m += 30) opts.push(m);
    } else {
      for (let m = 120; m <= 720; m += 30) opts.push(m);
    }
    return opts;
  })();

  // Duration-based time slot generation
  const durationSlots = (() => {
    if (!rawSlots.length || !form.durationMinutes) return [];
    const durMin = Number(form.durationMinutes);
    const isToday = form.bookingDate === format(new Date(), 'yyyy-MM-dd');
    const nowMinutes = new Date().getHours() * 60 + new Date().getMinutes();
    const bookedHalfHours = new Set();
    const editRef = editBookingData?.bookingRef;
    bookedSlots.forEach(bs => {
      if (editRef && bs.bookingRef === editRef) return;
      const start = bs.startMinute != null ? bs.startMinute : ((bs.startHour || 0) * 60);
      const dur = bs.durationMinutes != null ? bs.durationMinutes : ((bs.durationHours || 0) * 60);
      if (!dur) return;
      for (let m = start; m < start + dur; m += 30) bookedHalfHours.add(Math.floor(m / 30));
    });
    const slots = [];
    const editStartMin = editBookingData?.startTime
      ? (() => { const p = String(editBookingData.startTime).split(':'); return parseInt(p[0],10)*60 + parseInt(p[1]||'0',10); })()
      : null;
    for (let startMin = 0; startMin + durMin <= 24 * 60; startMin += 30) {
      if (isToday && startMin < nowMinutes + 15 && startMin !== editStartMin) continue;
      let allAvailable = true;
      for (let m = startMin; m < startMin + durMin; m += 30) {
        const slot = rawSlots.find(s => {
          const sm = s.startMinute != null ? s.startMinute : s.startHour * 60;
          return sm === m;
        });
        if (!slot || !slot.available || bookedHalfHours.has(Math.floor(m / 30))) { allAvailable = false; break; }
      }
      if (allAvailable) slots.push(startMin);
    }
    return slots;
  })();

  const calculateTotal = () => {
    if (!selectedEvent) return 0;
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

  const handleSubmit = async () => {
    if (isAdmin && !selectedCustomer) { toast.error('Please select a customer before confirming'); return; }
    if (!form.eventTypeId) { toast.error('Please select an event type'); return; }
    if (!form.bookingDate) { toast.error('Please select a booking date'); return; }
    if (form.startTime === '') { toast.error('Please select a time slot'); return; }
    if (form.numberOfGuests < 1) { toast.error('Number of guests must be at least 1'); return; }
    setError('');
    setLoading(true);
    try {
      const startMin = Number(form.startTime);
      const startH = Math.floor(startMin / 60);
      const startM = startMin % 60;
      const durMin = Number(form.durationMinutes);
      const payload = {
        eventTypeId: Number(form.eventTypeId),
        bookingDate: form.bookingDate,
        startTime: String(startH).padStart(2, '0') + ':' + String(startM).padStart(2, '0'),
        durationMinutes: durMin,
        durationHours: Math.floor(durMin / 60),
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
        const alreadyPaid = editBookingData?.paymentStatus === 'SUCCESS' || editBookingData?.paymentStatus === 'PARTIALLY_REFUNDED';
        if (!alreadyPaid) payload.paymentMethod = form.paymentMethod;
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

  const fmtTime = (m) => {
    const val = Number(m);
    if (!Number.isFinite(val) || val < 0) return '--:--';
    const h = Math.floor(val / 60) % 24;
    const min = Math.floor(val % 60);
    return String(h).padStart(2, '0') + ':' + String(min).padStart(2, '0');
  };

  const fmtDuration = (m) => {
    const val = Number(m);
    if (!Number.isFinite(val) || val <= 0) return '0m';
    const h = Math.floor(val / 60);
    const min = Math.floor(val % 60);
    if (h > 0 && min > 0) return `${h}hr ${min}m`;
    if (h > 0) return `${h}hr`;
    return `${min}m`;
  };

  const totalSteps = isAdmin ? 5 : 4;
  const dots = isAdmin ? [0, 1, 2, 3, 4] : [1, 2, 3, 4];
  const stepLabels = isAdmin
    ? { 0: 'Customer', 1: 'Event', 2: 'Date & Time', 3: 'Add-Ons', 4: 'Review' }
    : { 1: 'Event', 2: 'Date & Time', 3: 'Add-Ons', 4: 'Review' };

  const stepNumber = (s) => isAdmin ? s + 1 : s;
  const dotClass = (s) => s < step ? 'completed' : s === step ? 'active' : '';

  return (
    <div className="container booking-page">
      <div className="page-header">
        <h1>{editBookingData ? 'Edit Reservation' : isAdmin ? 'Book Walk-In Experience' : 'Book Your Experience'}</h1>
        <p>Step {isAdmin ? step + 1 : step} of {totalSteps}</p>
      </div>

      <nav className="booking-steps" aria-label="Booking progress">
        {dots.map((s, i) => (
          <div key={s} className="step-item">
            {i > 0 && <div className={`step-connector ${s <= step ? 'active' : ''}`} />}
            <div className="step-dot-wrapper">
              <div className={`step-dot ${dotClass(s)}`}
                   title={stepLabels[s]} aria-current={step === s ? 'step' : undefined}>
                {s < step ? '✓' : stepNumber(s)}
              </div>
              <span className={`step-label ${dotClass(s)}`}>{stepLabels[s]}</span>
            </div>
          </div>
        ))}
      </nav>

      {error && <div className="error-message" role="alert">{error}</div>}

      <ImagePopup imagePopup={imagePopup} setImagePopup={setImagePopup} />

      {isAdmin && step === 0 && (
        <StepCustomer
          selectedCustomer={selectedCustomer} setSelectedCustomer={setSelectedCustomer}
          activeRateCodes={activeRateCodes} selectedRateCodeId={selectedRateCodeId}
          setSelectedRateCodeId={setSelectedRateCodeId}
          resolvedPricing={resolvedPricing} setResolvedPricing={setResolvedPricing}
          onNext={() => setStep(1)} onCancel={onCancel}
        />
      )}

      {step === 1 && (
        <StepEvent
          eventTypes={eventTypes} form={form} setForm={setForm}
          resolvedPricing={resolvedPricing} setImagePopup={setImagePopup}
          isAdmin={isAdmin}
          onNext={() => {
            if (!form.eventTypeId) { toast.error('Please select an event type to continue'); return; }
            setStep(2);
          }}
          onBack={() => setStep(0)} onCancel={onCancel}
        />
      )}

      {step === 2 && (
        <StepDateTime
          form={form} setForm={setForm} isAdmin={isAdmin} selectedEvent={selectedEvent}
          durationOptions={durationOptions} durationSlots={durationSlots}
          availability={availability} resolvedPricing={resolvedPricing}
          fmtTime={fmtTime} fmtDuration={fmtDuration}
          onNext={() => {
            if (!form.bookingDate) { toast.error('Please select a date before proceeding'); return; }
            if (form.startTime === '') { toast.error('Please select a time slot before proceeding'); return; }
            setStep(3);
          }}
          onBack={() => setStep(1)}
        />
      )}

      {step === 3 && (
        <StepAddOns
          addOns={addOns} form={form} setForm={setForm} isAdmin={isAdmin}
          resolvedPricing={resolvedPricing} toggleAddOn={toggleAddOn}
          setImagePopup={setImagePopup}
          onNext={() => setStep(4)} onBack={() => setStep(2)}
        />
      )}

      {step === 4 && (
        <StepReview
          form={form} setForm={setForm} isAdmin={isAdmin}
          selectedEvent={selectedEvent} selectedCustomer={selectedCustomer}
          resolvedPricing={resolvedPricing} editBookingData={editBookingData}
          calculateTotal={calculateTotal} fmtTime={fmtTime} fmtDuration={fmtDuration}
          loading={loading} onSubmit={handleSubmit} onBack={() => setStep(3)}
        />
      )}
    </div>
  );
}
