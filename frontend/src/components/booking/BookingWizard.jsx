import { useState, useEffect, useRef, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { bookingService, availabilityService, adminService, slotHoldService } from '../../services/endpoints';
import { toast } from 'react-toastify';
import { format, addDays } from 'date-fns';
import { trackBookingStarted, trackBookingStepCompleted } from '../../services/analytics';
import { useBinge } from '../../context/BingeContext';
import { venueLocalTodayISO, venueLocalNowMinutes } from '../../utils/format';
import loyaltyV2 from '../../services/loyaltyV2';

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
  const { selectedBinge, selectBinge } = useBinge();

  // Refresh the selected venue from the backend on entry so an admin's change to the
  // venue timezone (or support info, etc.) propagates instead of showing the stale copy
  // cached in localStorage. Without this, the wizard's venue-time note and its venue-local
  // slot filtering keep using the timezone that was current when the venue was first picked.
  useEffect(() => {
    const id = selectedBinge?.id;
    if (!id) return;
    bookingService.getBingeById(id)
      .then((res) => {
        const fresh = res.data?.data;
        if (fresh && fresh.timezone && fresh.timezone !== selectedBinge.timezone) {
          selectBinge({ ...selectedBinge, ...fresh });
        }
      })
      .catch(() => { /* non-fatal: keep the cached venue */ });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedBinge?.id]);

  // Customer (admin only)
  const [selectedCustomer, setSelectedCustomer] = useState(null);

  // Booking data
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [availability, setAvailability] = useState([]);
  const [rawSlots, setRawSlots] = useState([]);
  const [bookedSlots, setBookedSlots] = useState([]);
  const [loading, setLoading] = useState(false);
  const submittingRef = useRef(false);
  const [error, setError] = useState('');
  const [imagePopup, setImagePopup] = useState(null);
  const [resolvedPricing, setResolvedPricing] = useState(null);
  const [activeRateCodes, setActiveRateCodes] = useState([]);
  const [selectedRateCodeId, setSelectedRateCodeId] = useState('');
  const [capacityFull, setCapacityFull] = useState(false);
  const [freeze, setFreeze] = useState(null); // { freezeUntil, reason, message }
  const [freezeNow, setFreezeNow] = useState(Date.now());
  const [rateLimitUntil, setRateLimitUntil] = useState(0); // epoch ms when booking rate-limit expires
  const [rateLimitNow, setRateLimitNow] = useState(Date.now());

  // Pre-payment slot hold: created when the customer advances past slot
  // selection, consumed server-side at booking creation via payload.holdToken.
  // While it lives, no other customer can book this slot out from under them.
  const [slotHold, setSlotHold] = useState(null); // { token, expiresAtMs }
  const [holdSecondsLeft, setHoldSecondsLeft] = useState(null);
  const slotHoldTokenRef = useRef(null);

  useEffect(() => { slotHoldTokenRef.current = slotHold?.token || null; }, [slotHold]);

  // Countdown driven off a client-side deadline (server sends secondsRemaining,
  // which is timezone-safe, unlike parsing its zone-less UTC timestamp).
  useEffect(() => {
    if (!slotHold?.expiresAtMs) { setHoldSecondsLeft(null); return undefined; }
    const tick = () => {
      const left = Math.max(0, Math.floor((slotHold.expiresAtMs - Date.now()) / 1000));
      setHoldSecondsLeft(left);
      if (left <= 0) {
        setSlotHold(null);
        toast.warn('Your slot hold expired — the slot is open to others until you confirm.');
      }
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [slotHold?.expiresAtMs]);

  // Best-effort release when the wizard unmounts without booking. Releasing a
  // hold the booking already CONVERTED is an idempotent no-op server-side.
  useEffect(() => () => {
    if (slotHoldTokenRef.current) {
      slotHoldService.release(slotHoldTokenRef.current, 'WIZARD_EXIT').catch(() => {});
    }
  }, []);

  const releaseSlotHold = async (reason) => {
    const token = slotHoldTokenRef.current;
    if (!token) return;
    setSlotHold(null);
    try { await slotHoldService.release(token, reason); } catch { /* best-effort */ }
  };

  /**
   * Acquire (or re-acquire after a slot change) the pre-payment hold.
   * Returns false only when the slot is genuinely unavailable (business
   * error) — infrastructure failures fall back to a direct booking, which
   * the server still guards with its own conflict checks.
   */
  const acquireSlotHold = async () => {
    if (isAdmin || editBookingData || reinstateData || form.recurringEnabled) return true;
    const startMin = Number(form.startTime);
    const body = {
      eventTypeId: Number(form.eventTypeId),
      bookingDate: form.bookingDate,
      startTime: `${String(Math.floor(startMin / 60)).padStart(2, '0')}:${String(startMin % 60).padStart(2, '0')}`,
      durationMinutes: Number(form.durationMinutes),
      numberOfGuests: Number(form.numberOfGuests) || 1,
      venueRoomId: form.venueRoomId ? Number(form.venueRoomId) : null,
    };
    await releaseSlotHold('RESELECTED');
    try {
      const res = await slotHoldService.create(body);
      const dto = res.data?.data;
      if (dto?.holdToken) {
        setSlotHold({
          token: dto.holdToken,
          expiresAtMs: Date.now() + Math.max(0, Number(dto.secondsRemaining) || 0) * 1000,
        });
      }
      return true;
    } catch (err) {
      const status = err.response?.status;
      const msg = err.userMessage || err.response?.data?.message || '';
      if (/active slot hold/i.test(msg)) return true; // own hold from another tab — server won't block us
      if (status && status < 500 && msg) {
        toast.error(msg.replace('CAPACITY_FULL:', ''));
        return false; // slot taken/held — stay on slot selection
      }
      return true; // hold service hiccup — proceed as a direct booking
    }
  };

  // ── Customer-only: detect existing booking-flow freeze on mount ───────────
  // Admins create bookings on behalf and aren't subject to this lock.
  useEffect(() => {
    if (isAdmin) return;
    const bingeId = selectedBinge?.id;
    if (!bingeId) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await bookingService.getMyFreezeForBinge(bingeId);
        const data = res?.data?.data || res?.data;
        if (!cancelled && data && data.freezeUntil) {
          setFreeze({
            freezeUntil: data.freezeUntil,
            reason: data.reason || '',
            message: 'Your booking flow is temporarily frozen at this binge. Please contact support to lift it immediately.',
          });
        }
      } catch {
        // 404 / no active freeze — ignore
      }
    })();
    return () => { cancelled = true; };
  }, [isAdmin, selectedBinge?.id]);

  // ── Customer-only: surface unfinished (unpaid PENDING) bookings on mount ──
  // Booking + browser-Back leaves an unpaid reservation the customer often doesn't
  // know exists; piling up more eventually hits the venue's unpaid-booking limit.
  // Industry pattern (BookMyShow "complete your payment"): show them up front with
  // Pay / Cancel actions instead of letting the customer trip the limit blind.
  const [unpaidHolds, setUnpaidHolds] = useState([]);
  useEffect(() => {
    if (isAdmin) return;
    const bingeId = selectedBinge?.id;
    if (!bingeId) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await bookingService.getMyBookings();
        const rows = Array.isArray(res?.data?.data) ? res.data.data : [];
        const holds = rows.filter((b) =>
          String(b.status).toUpperCase() === 'PENDING'
          && ['PENDING', 'FAILED'].includes(String(b.paymentStatus || 'PENDING').toUpperCase())
          && (!b.bingeId || Number(b.bingeId) === Number(bingeId)));
        if (!cancelled) setUnpaidHolds(holds);
      } catch {
        // non-fatal — the server-side limit still protects; we just lose the banner
      }
    })();
    return () => { cancelled = true; };
  }, [isAdmin, selectedBinge?.id]);

  const releaseUnpaidHold = async (bookingRef) => {
    if (!window.confirm('Cancel this unpaid booking? No payment was taken, so this is free.')) return;
    try {
      await bookingService.cancelBooking(bookingRef);
      setUnpaidHolds((prev) => prev.filter((b) => b.bookingRef !== bookingRef));
      toast.success('Unpaid booking released — no charges were made.');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Could not cancel that booking.');
    }
  };

  // Tick the countdown banner once a second when frozen
  useEffect(() => {
    if (!freeze) return undefined;
    const t = setInterval(() => setFreezeNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [freeze]);

  // Auto-clear when freeze expires client-side
  useEffect(() => {
    if (!freeze) return;
    if (new Date(freeze.freezeUntil).getTime() <= freezeNow) {
      setFreeze(null);
    }
  }, [freeze, freezeNow]);

  // Tick the rate-limit cooldown banner once a second
  useEffect(() => {
    if (!rateLimitUntil) return undefined;
    const t = setInterval(() => {
      setRateLimitNow(Date.now());
      if (Date.now() >= rateLimitUntil) {
        clearInterval(t);
        setRateLimitUntil(0);
      }
    }, 1000);
    return () => clearInterval(t);
  }, [rateLimitUntil]);
  const [venueRooms, setVenueRooms] = useState([]);
  const [surgeRules, setSurgeRules] = useState([]);
  const [loyalty, setLoyalty] = useState(null);
  const [loyaltyQuote, setLoyaltyQuote] = useState(null);
  const [redeemMax, setRedeemMax] = useState(null);
  const [taxPreview, setTaxPreview] = useState(null);
  // Native per-binge currency: the booking prices/charges in the VENUE's own currency
  // (derived from its country). There is no customer currency choice or FX conversion.
  const bingeCurrency = selectedBinge?.currency || 'INR';
  const [activeSurge, setActiveSurge] = useState(null);

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
    venueRoomId: '',
    redeemLoyaltyPoints: 0,
    recurringEnabled: false,
    recurringPattern: 'WEEKLY',
    recurringOccurrences: 4,
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
        .catch(() => toast.error('Failed to load rate codes'));
    }
  }, [isAdmin]);

  // Load resolved pricing for customers (non-admin)
  useEffect(() => {
    if (!isAdmin) {
      bookingService.getMyPricing()
        .then(res => setResolvedPricing(res.data.data || null))
        .catch(() => toast.error('Failed to load your pricing'));
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

  // When admin explicitly picks a rate code, re-resolve pricing.
  // Precedence (matches backend): customer-specific custom > admin's override rate code
  // > customer profile's rate code > default. So when a customer is selected we call the
  // customer-aware endpoint with the override; only fall back to the raw rate-code
  // endpoint when there's no customer context (walk-in booking).
  useEffect(() => {
    if (!isAdmin || !selectedRateCodeId) return;
    const req = selectedCustomer?.id
      ? adminService.resolveCustomerPricing(selectedCustomer.id, selectedRateCodeId)
      : adminService.resolveRateCodePricing(selectedRateCodeId);
    req
      .then(res => setResolvedPricing(res.data.data || null))
      .catch(() => setResolvedPricing(null));
  }, [isAdmin, selectedRateCodeId, selectedCustomer?.id]);

  // Load venue rooms
  useEffect(() => {
    bookingService.getVenueRooms()
      .then(res => setVenueRooms(res.data.data || []))
      .catch(() => { setVenueRooms([]); toast.error('Failed to load venue rooms'); });
  }, []);

  // NOTE: room availability is now derived client-side from bookedSlots (which carry
  // venueRoomId) in the room-aware slot computation below — no time-based fetch needed,
  // because the flow is date → room → time (a room is chosen BEFORE a time). See
  // computeDurationSlots / roomAvailability.

  // Load surge rules
  useEffect(() => {
    bookingService.getActiveSurgeRules()
      .then(res => setSurgeRules(res.data.data || []))
      .catch(() => { setSurgeRules([]); toast.error('Failed to load surge rules'); });
  }, []);

  // Load loyalty (customer only)
  useEffect(() => {
    if (!isAdmin) {
      loyaltyV2.getMyLegacyAccount()
        .then(data => setLoyalty(data || null))
        .catch(() => setLoyalty(null));
    }
  }, [isAdmin]);

  // Resolve which surge applies for the selected date+time — ALWAYS from the
  // server. Rules carry triggers the client cannot evaluate (occupancy %,
  // venue-clock lead times, seasonal windows, priority tie-breaks), so a
  // client-side matcher would show a price the server won't charge.
  useEffect(() => {
    if (!form.bookingDate || form.startTime === '') {
      setActiveSurge(null);
      return;
    }
    let cancelled = false;
    bookingService.quoteSurge(form.bookingDate, Number(form.startTime))
      .then((res) => { if (!cancelled) setActiveSurge(res.data?.data ?? null); })
      .catch(() => { if (!cancelled) setActiveSurge(null); });
    return () => { cancelled = true; };
  }, [form.bookingDate, form.startTime]);

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

  const durMin = Number(form.durationMinutes);
  // "Today" and "now" are resolved in the VENUE's timezone (not the browser's) so past-slot
  // filtering matches the backend, which validates venue-locally. A customer in India booking
  // a Chicago venue therefore sees exactly the slots Chicago allows.
  const isToday = form.bookingDate === venueLocalTodayISO(selectedBinge?.timezone);
  const nowMinutes = venueLocalNowMinutes(selectedBinge?.timezone);
  const roomCount = venueRooms.length; // exclusive spaces (capacity 1) → concurrency ceiling

  /**
   * Compute the bookable start-times for a given room selection, honouring the new
   * date → room → time flow:
   *  - a specific room (roomSel is a room id): a half-hour is unavailable only if THAT
   *    room already has an overlapping booking;
   *  - "any room" (roomSel falsy) at a venue WITH rooms: a half-hour is unavailable only
   *    when the number of overlapping bookings has reached the room count (all rooms full);
   *  - room-less venue (roomCount === 0): any overlapping booking blocks the half-hour.
   */
  const computeDurationSlots = (roomSel) => {
    const editRef = editBookingData?.bookingRef;
    const overlapCountByHalfHour = new Map(); // halfHourIndex -> overlapping bookings across all rooms
    const roomBookedHalfHours = new Set();    // half-hours already taken by the specific selected room
    bookedSlots.forEach(bs => {
      if (editRef && bs.bookingRef === editRef) return;
      const start = bs.startMinute != null ? bs.startMinute : 0;
      const dur = bs.durationMinutes != null ? bs.durationMinutes : ((bs.durationHours || 0) * 60);
      if (!dur) return;
      for (let m = start; m < start + dur; m += 30) {
        const idx = Math.floor(m / 30);
        overlapCountByHalfHour.set(idx, (overlapCountByHalfHour.get(idx) || 0) + 1);
        if (roomSel && bs.venueRoomId === Number(roomSel)) roomBookedHalfHours.add(idx);
      }
    });
    const isHalfHourTaken = (idx) => {
      if (roomSel) return roomBookedHalfHours.has(idx);
      if (roomCount > 0) return (overlapCountByHalfHour.get(idx) || 0) >= roomCount;
      return (overlapCountByHalfHour.get(idx) || 0) > 0;
    };
    const slots = [];
    const editStartMin = editBookingData?.startTime
      ? (() => { const p = String(editBookingData.startTime).split(':'); return parseInt(p[0],10)*60 + parseInt(p[1]||'0',10); })()
      : null;
    for (let startMin = 0; startMin + durMin <= 24 * 60; startMin += 30) {
      if (isToday && startMin < nowMinutes + 15 && startMin !== editStartMin) continue;
      let allAvailable = true;
      for (let m = startMin; m < startMin + durMin; m += 30) {
        const slot = rawSlots.find(s => {
          const sm = s.startMinute != null ? s.startMinute : 0;
          return sm === m;
        });
        if (!slot || !slot.available || isHalfHourTaken(Math.floor(m / 30))) { allAvailable = false; break; }
      }
      if (allAvailable) slots.push(startMin);
    }
    return slots;
  };

  // Times for the CURRENT room selection (drives the time grid).
  const durationSlots = computeDurationSlots(form.venueRoomId);

  // Which rooms still have at least one bookable time on this date+duration — used to
  // disable fully-booked rooms in the picker, "just like times are hidden for booked slots".
  const roomAvailability = (() => {
    const map = {};
    venueRooms.forEach(r => { map[r.id] = computeDurationSlots(r.id).length > 0; });
    return map;
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
    let subtotal = base + addOnTotal + guestCharge;
    if (activeSurge) subtotal = subtotal * activeSurge.multiplier;
    // V56: venue room price addition is applied AFTER surge (matches backend)
    if (form.venueRoomId) {
      const room = venueRooms.find(r => r.id === Number(form.venueRoomId));
      if (room?.priceAddition) subtotal += Number(room.priceAddition) || 0;
    }
    return subtotal;
  };

  const calculateLoyaltyDiscount = () => {
    if (!loyalty || !form.redeemLoyaltyPoints || form.redeemLoyaltyPoints <= 0) return 0;
    if (!loyaltyQuote?.eligible) return 0;
    return Math.min(Number(loyaltyQuote.currencyValue || 0), calculateTotal());
  };

  useEffect(() => {
    if (isAdmin || !loyalty || !selectedBinge?.id || !form.redeemLoyaltyPoints || form.redeemLoyaltyPoints <= 0) {
      setLoyaltyQuote(null);
      return;
    }
    const bookingAmount = calculateTotal();
    if (!bookingAmount || bookingAmount <= 0) {
      setLoyaltyQuote(null);
      return;
    }
    // Debounce: a slider drag fires this on every pixel, so wait for the value
    // to settle before hitting the server (the gateway rate-limits per user).
    let cancelled = false;
    const t = setTimeout(() => {
      loyaltyV2.getRedeemQuote({
        bingeId: selectedBinge.id,
        bookingAmount,
        points: Math.min(Number(form.redeemLoyaltyPoints) || 0, loyalty.currentBalance || 0),
      })
        .then((quote) => { if (!cancelled) setLoyaltyQuote(quote); })
        .catch(() => { if (!cancelled) setLoyaltyQuote(null); });
    }, 250);
    return () => { cancelled = true; clearTimeout(t); };
  }, [
    isAdmin,
    loyalty,
    selectedBinge?.id,
    form.redeemLoyaltyPoints,
    form.eventTypeId,
    form.durationMinutes,
    form.numberOfGuests,
    form.addOns,
    activeSurge,
    resolvedPricing,
  ]);

  // Redemption ceiling for the slider — how many points the customer CAN apply
  // to this booking and the venue-country per-point value. Recomputed when the
  // bill (not the chosen points) changes, so dragging the slider never refetches.
  useEffect(() => {
    if (isAdmin || !loyalty || !(loyalty.currentBalance > 0) || !selectedBinge?.id) { setRedeemMax(null); return; }
    const bookingAmount = calculateTotal();
    if (!bookingAmount || bookingAmount <= 0) { setRedeemMax(null); return; }
    let cancelled = false;
    loyaltyV2.getRedeemMax({ bingeId: selectedBinge.id, bookingAmount })
      .then((m) => { if (!cancelled) setRedeemMax(m || null); })
      .catch(() => { if (!cancelled) setRedeemMax(null); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin, loyalty, selectedBinge?.id, form.eventTypeId, form.durationMinutes,
      form.numberOfGuests, form.addOns, form.venueRoomId, activeSurge, resolvedPricing]);

  // Keep the chosen points within the ceiling if the bill shrinks (never above
  // what's redeemable). Skips while the ceiling is still loading (null).
  useEffect(() => {
    if (redeemMax == null) return;
    const max = redeemMax.eligible ? Number(redeemMax.maxPoints || 0) : 0;
    setForm((f) => (Number(f.redeemLoyaltyPoints) > max ? { ...f, redeemLoyaltyPoints: max } : f));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [redeemMax]);

  // Tax preview — fetch the server-computed tax breakdown whenever the subtotal
  // changes so StepReview can display taxes before the booking is committed.
  useEffect(() => {
    const subtotal = Math.max(0, calculateTotal() - (loyaltyQuote?.eligible ? Math.min(Number(loyaltyQuote.currencyValue || 0), calculateTotal()) : 0));
    if (!subtotal || subtotal <= 0 || !selectedBinge?.id) { setTaxPreview(null); return; }
    let cancelled = false;
    // durationMinutes lets the server quote FLAT_PER_HOUR rules (per-hour occupancy fees).
    bookingService.previewTaxes({ subtotal, durationMinutes: form.durationMinutes || undefined })
      .then(res => { if (!cancelled) setTaxPreview(res.data?.data ?? null); })
      .catch(() => { if (!cancelled) setTaxPreview(null); });
    return () => { cancelled = true; };
  }, [
    selectedBinge?.id,
    form.eventTypeId,
    form.durationMinutes,
    form.numberOfGuests,
    form.addOns,
    form.venueRoomId,
    // NB: not form.redeemLoyaltyPoints — the discount enters via loyaltyQuote
    // (debounced), so keying off it here would re-spam previewTaxes on every drag.
    activeSurge,
    resolvedPricing,
    loyaltyQuote,
  ]);

  // Earn preview — "you'll earn ~X points with this booking" (airline/hotel checkout
  // pattern). Uses the exact earn-rule math that fires at completion. For admins the
  // quote is for the SELECTED customer; guests without membership quietly show nothing.
  const [earnQuote, setEarnQuote] = useState(null);
  useEffect(() => {
    const total = calculateTotal();
    const bingeId = selectedBinge?.id;
    const forCustomer = isAdmin ? selectedCustomer?.id : undefined;
    if (!bingeId || !total || total <= 0 || (isAdmin && !forCustomer)) { setEarnQuote(null); return; }
    let cancelled = false;
    loyaltyV2.getEarnQuote({ bingeId, bookingAmount: total, customerId: forCustomer })
      .then((q) => { if (!cancelled) setEarnQuote(q && q.eligible ? q : null); })
      .catch(() => { if (!cancelled) setEarnQuote(null); });
    return () => { cancelled = true; };
  }, [
    isAdmin,
    selectedCustomer?.id,
    selectedBinge?.id,
    form.eventTypeId,
    form.durationMinutes,
    form.numberOfGuests,
    form.addOns,
    form.venueRoomId,
    activeSurge,
    resolvedPricing,
  ]);

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

  const handleJoinWaitlist = async () => {
    if (!form.eventTypeId || !form.bookingDate || form.startTime === '') {
      toast.error('Please complete event, date, and time selection first');
      return;
    }
    setLoading(true);
    try {
      const startMin = Number(form.startTime);
      const startH = Math.floor(startMin / 60);
      const startM = startMin % 60;
      await bookingService.joinWaitlist({
        eventTypeId: Number(form.eventTypeId),
        preferredDate: form.bookingDate,
        preferredStartTime: String(startH).padStart(2, '0') + ':' + String(startM).padStart(2, '0'),
        durationMinutes: Number(form.durationMinutes),
        numberOfGuests: Number(form.numberOfGuests),
      });
      toast.success('You\'ve been added to the waitlist! We\'ll notify you when a spot opens.');
      setCapacityFull(false);
      setError('');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Failed to join waitlist';
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async () => {
    if (submittingRef.current) return;
    // Block customer submissions while a booking-flow freeze is active
    if (!isAdmin && freeze && new Date(freeze.freezeUntil).getTime() > Date.now()) {
      toast.error('Your booking flow is temporarily frozen. Please contact support.');
      return;
    }
    if (!isAdmin && rateLimitUntil > Date.now()) {
      const secsLeft = Math.ceil((rateLimitUntil - Date.now()) / 1000);
      toast.error(`Booking limit active. Please wait ${secsLeft} more second${secsLeft !== 1 ? 's' : ''} before trying again.`);
      return;
    }
    if (isAdmin && !selectedCustomer) { toast.error('Please select a customer before confirming'); return; }
    if (!form.eventTypeId) { toast.error('Please select an event type'); return; }
    if (!form.bookingDate) { toast.error('Please select a booking date'); return; }
    if (form.startTime === '') { toast.error('Please select a time slot'); return; }
    if (!Number.isFinite(form.numberOfGuests) || form.numberOfGuests < 1) { toast.error('Number of guests must be at least 1'); return; }
    setError('');
    setLoading(true);
    submittingRef.current = true;
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
        venueRoomId: form.venueRoomId ? Number(form.venueRoomId) : null,
        redeemLoyaltyPoints: form.redeemLoyaltyPoints ? Number(form.redeemLoyaltyPoints) : null,
      };
      // Hand the pre-payment hold to the server so it is consumed atomically
      // with the booking (customer direct-booking flow only — admin/edit/
      // recurring payloads go to different endpoints without this field).
      if (!isAdmin && !editBookingData && !form.recurringEnabled && slotHold?.token) {
        payload.holdToken = slotHold.token;
      }
      if (isAdmin) {
        payload.customerId = selectedCustomer.id;
        payload.customerName = `${selectedCustomer.firstName} ${selectedCustomer.lastName || ''}`.trim();
        payload.customerEmail = selectedCustomer.email;
        payload.customerPhone = selectedCustomer.phone || '';
        payload.customerPhoneCountryCode = selectedCustomer.phoneCountryCode || '';
        payload.adminNotes = form.adminNotes;
        const alreadyPaid = editBookingData?.paymentStatus === 'SUCCESS' || editBookingData?.paymentStatus === 'PARTIALLY_REFUNDED';
        if (!alreadyPaid) payload.paymentMethod = form.paymentMethod;
        if (selectedRateCodeId) payload.rateCodeId = Number(selectedRateCodeId);
      }
      // Recurring booking flow
      if (form.recurringEnabled && !editBookingData) {
        const recurringPayload = {
          eventTypeId: payload.eventTypeId,
          startDate: payload.bookingDate,
          startTime: payload.startTime,
          durationMinutes: payload.durationMinutes,
          numberOfGuests: payload.numberOfGuests,
          addOns: payload.addOns,
          specialNotes: payload.specialNotes,
          pattern: form.recurringPattern,
          occurrences: Number(form.recurringOccurrences),
          venueRoomId: payload.venueRoomId,
        };
        await onSubmit(recurringPayload, { recurring: true });
      } else {
        await onSubmit(payload);
      }
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Booking failed. Please try again.';
      if (msg.startsWith('FROZEN:')) {
        // Format: FROZEN:until={epochMs}|reason={r}|message={m}
        const body = msg.substring('FROZEN:'.length);
        const parts = body.split('|');
        const get = (k) => {
          const seg = parts.find(p => p.startsWith(`${k}=`));
          return seg ? seg.substring(k.length + 1) : '';
        };
        const untilMs = Number(get('until'));
        const reason = get('reason');
        const message = get('message') || 'Your booking flow is temporarily frozen at this binge.';
        setFreeze({
          freezeUntil: Number.isFinite(untilMs) && untilMs > 0 ? new Date(untilMs).toISOString() : new Date(Date.now() + 60 * 60 * 1000).toISOString(),
          reason,
          message,
        });
        setCapacityFull(false);
        setError(message);
        toast.error(message);
      } else if (msg.startsWith('CAPACITY_FULL:')) {
        setCapacityFull(true);
        setError(msg.replace('CAPACITY_FULL:', ''));
        toast.error(msg.replace('CAPACITY_FULL:', ''));
      } else if (/slot\s+is\s+not\s+available/i.test(msg)) {
        // The slot was claimed between availability-check and create-booking
        // (race) — drop the user back to step 2 so the SmartSuggestionsPanel
        // can offer alternatives without forcing a wizard restart.
        setCapacityFull(false);
        setForm(f => ({ ...f, startTime: '' }));
        setError(msg);
        toast.error(`${msg}. See alternatives below.`);
        setStep(2);
      } else if (err.response?.status === 429) {
        // Gateway rate-limit: the api.js interceptor already showed a toast.
        // Show an inline countdown banner so the user knows exactly when they can retry.
        const nowMs = Date.now();
        const resetAtMs = err.resetAtEpoch ? err.resetAtEpoch * 1000 : null;
        const retryAfterSecs = err.retryAfterSeconds ||
          parseInt(err.response?.headers?.['retry-after'] || '60', 10);
        const until = resetAtMs && resetAtMs > nowMs
          ? resetAtMs
          : nowMs + retryAfterSecs * 1000;
        setRateLimitUntil(until);
        setRateLimitNow(nowMs);
        setCapacityFull(false);
        setError('');
      } else {
        setCapacityFull(false);
        setError(msg);
        toast.error(msg);
      }
    } finally {
      setLoading(false);
      submittingRef.current = false;
    }
  };

  const fmtTime = (m) => {
    const val = Number(m);
    if (!Number.isFinite(val) || val < 0) return '--:--';
    let h = Math.floor(val / 60) % 24;
    const min = Math.floor(val % 60);
    const period = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    return `${h}:${String(min).padStart(2, '0')} ${period}`;
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

      {freeze && (() => {
        const targetMs = new Date(freeze.freezeUntil).getTime();
        const remaining = Math.max(0, targetMs - freezeNow);
        const totalSec = Math.floor(remaining / 1000);
        const h = Math.floor(totalSec / 3600);
        const m = Math.floor((totalSec % 3600) / 60);
        const s = totalSec % 60;
        const support = selectedBinge?.supportEmail || selectedBinge?.contactEmail;
        const phone = selectedBinge?.supportPhone || selectedBinge?.contactPhone;
        return (
          <div
            role="alert"
            style={{
              border: '1px solid var(--danger, #b91c1c)',
              background: 'rgba(185,28,28,0.08)',
              padding: '1rem 1.25rem',
              borderRadius: '8px',
              marginBottom: '1rem',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '1rem', flexWrap: 'wrap' }}>
              <div>
                <strong>🔒 Booking flow temporarily frozen</strong>
                <div style={{ marginTop: '0.25rem' }}>{freeze.message}</div>
                {freeze.reason && (
                  <div style={{ marginTop: '0.25rem', fontSize: '0.9em', color: 'var(--text-secondary)' }}>
                    Reason: {freeze.reason}
                  </div>
                )}
                {(support || phone) && (
                  <div style={{ marginTop: '0.5rem', fontSize: '0.9em' }}>
                    Contact support to unfreeze immediately:
                    {support && <> &nbsp;<a href={`mailto:${support}`}>{support}</a></>}
                    {phone && <> &nbsp;<a href={`tel:${phone}`}>{phone}</a></>}
                  </div>
                )}
              </div>
              <div style={{ fontFamily: 'monospace', fontSize: '1.5em', fontWeight: 600, minWidth: '120px', textAlign: 'right' }}>
                {String(h).padStart(2, '0')}:{String(m).padStart(2, '0')}:{String(s).padStart(2, '0')}
              </div>
            </div>
          </div>
        );
      })()}

      {!isAdmin && unpaidHolds.length > 0 && (
        <div
          role="alert"
          style={{
            border: '1px solid var(--warning, #d97706)',
            background: 'rgba(217,119,6,0.08)',
            padding: '1rem 1.25rem',
            borderRadius: '8px',
            marginBottom: '1rem',
          }}
        >
          <strong>🕑 You have {unpaidHolds.length} unfinished booking{unpaidHolds.length > 1 ? 's' : ''} at this venue</strong>
          <div style={{ marginTop: '0.25rem' }}>
            A reservation is created the moment you confirm a booking — going back doesn't remove it.
            Complete the payment or cancel it (free — no payment was taken). Unpaid bookings also
            auto-release after the payment window, and too many at once will block new bookings here.
          </div>
          <div style={{ marginTop: '0.6rem', display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            {unpaidHolds.slice(0, 3).map((b) => (
              <div key={b.bookingRef} style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
                <span style={{ fontFamily: 'monospace' }}>{b.bookingRef}</span>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.9em' }}>
                  {b.bookingDate}{b.startTime ? ` · ${b.startTime}` : ''}
                </span>
                <Link to={`/payment/${b.bookingRef}`} className="btn btn-primary btn-sm">Complete payment</Link>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => releaseUnpaidHold(b.bookingRef)}>
                  Cancel (free)
                </button>
              </div>
            ))}
            {unpaidHolds.length > 3 && (
              <Link to="/my-bookings" style={{ fontSize: '0.9em' }}>View all in My Bookings →</Link>
            )}
          </div>
        </div>
      )}

      {!isAdmin && slotHold && holdSecondsLeft !== null && holdSecondsLeft > 0 && step >= 3 && (
        <div
          role="status"
          aria-live="polite"
          style={{
            border: '1px solid var(--success, #15803d)',
            background: 'rgba(21,128,61,0.08)',
            padding: '0.75rem 1.25rem',
            borderRadius: '8px',
            marginBottom: '1rem',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '1rem',
            flexWrap: 'wrap',
          }}
        >
          <span><strong>🔒 Slot reserved for you</strong> — finish booking before the hold expires.</span>
          <span style={{ fontFamily: 'monospace', fontSize: '1.2em', fontWeight: 600 }}>
            {Math.floor(holdSecondsLeft / 60)}:{String(holdSecondsLeft % 60).padStart(2, '0')}
          </span>
        </div>
      )}

      {error && <div className="error-message" role="alert">{error}</div>}

      {rateLimitUntil > rateLimitNow && (() => {
        const remaining = Math.max(0, rateLimitUntil - rateLimitNow);
        const totalSec = Math.ceil(remaining / 1000);
        const m = Math.floor(totalSec / 60);
        const s = totalSec % 60;
        return (
          <div
            role="alert"
            style={{
              border: '1px solid var(--warning, #d97706)',
              background: 'rgba(217,119,6,0.08)',
              padding: '1rem 1.25rem',
              borderRadius: '8px',
              marginBottom: '1rem',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '1rem', flexWrap: 'wrap' }}>
              <div>
                <strong>⏱ Booking request limit reached</strong>
                <div style={{ marginTop: '0.25rem' }}>
                  You've made too many booking requests in a short time. Your booking form will re-enable automatically — no need to refresh the page.
                </div>
                <div style={{ marginTop: '0.25rem', fontSize: '0.9em', color: 'var(--text-secondary)' }}>
                  This limit is specific to this venue and resets automatically.
                </div>
              </div>
              <div style={{ fontFamily: 'monospace', fontSize: '1.5em', fontWeight: 600, minWidth: '70px', textAlign: 'right' }}>
                {String(m).padStart(2, '0')}:{String(s).padStart(2, '0')}
              </div>
            </div>
          </div>
        );
      })()}

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
          isAdmin={isAdmin} currency={bingeCurrency}
          onNext={() => {
            if (!form.eventTypeId) { toast.error('Please select an event type to continue'); return; }
            trackBookingStarted(eventTypes.find(e => e.id === Number(form.eventTypeId))?.name || '');
            trackBookingStepCompleted(1, 'Event Selection');
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
          venueRooms={venueRooms} activeSurge={activeSurge}
          roomAvailability={roomAvailability} currency={bingeCurrency}
          venueTimezone={selectedBinge?.timezone}
          surgeRules={surgeRules} editBookingRef={editBookingData?.bookingRef}
          fmtTime={fmtTime} fmtDuration={fmtDuration}
          onNext={async () => {
            if (!form.bookingDate) { toast.error('Please select a date before proceeding'); return; }
            if (form.startTime === '') { toast.error('Please select a time slot before proceeding'); return; }
            // Reserve the slot for the rest of checkout — if someone else holds
            // it, surface that now instead of at the final confirm.
            const held = await acquireSlotHold();
            if (!held) return;
            trackBookingStepCompleted(2, 'Date & Time');
            setStep(3);
          }}
          onBack={() => setStep(1)}
        />
      )}

      {step === 3 && (
        <StepAddOns
          addOns={addOns} form={form} setForm={setForm} isAdmin={isAdmin}
          resolvedPricing={resolvedPricing} toggleAddOn={toggleAddOn}
          setImagePopup={setImagePopup} currency={bingeCurrency}
          onNext={() => { trackBookingStepCompleted(3, 'Add-Ons'); setStep(4); }} onBack={() => setStep(2)}
        />
      )}

      {step === 4 && (
        <StepReview
          form={form} setForm={setForm} isAdmin={isAdmin}
          selectedEvent={selectedEvent} selectedCustomer={selectedCustomer}
          resolvedPricing={resolvedPricing} editBookingData={editBookingData}
          calculateTotal={calculateTotal} calculateLoyaltyDiscount={calculateLoyaltyDiscount}
          fmtTime={fmtTime} fmtDuration={fmtDuration}
          loading={loading} onSubmit={handleSubmit} onBack={() => setStep(3)}
          capacityFull={capacityFull} onJoinWaitlist={handleJoinWaitlist}
          venueRooms={venueRooms} activeSurge={activeSurge} loyalty={loyalty}
          loyaltyQuote={loyaltyQuote} redeemMax={redeemMax} taxPreview={taxPreview}
          earnQuote={earnQuote}
          currency={bingeCurrency}
        />
      )}
    </div>
  );
}
