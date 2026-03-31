import { useState, useEffect } from 'react';
import { bookingService, availabilityService, adminService } from '../services/endpoints';
import { format, addDays } from 'date-fns';

/**
 * Shared booking form core used by:
 * - BookingPage (customer flow)
 * - AdminBookingCreate (admin/walk-in flow)
 * - Reinstate flow (pre-filled duplicate)
 *
 * Props:
 * - form, setForm: controlled form state
 * - isAdmin: boolean — shows admin-only options (e.g., CASH payment)
 * - showPaymentMethod: boolean
 * - eventTypes, addOns: pre-loaded data (optional, will self-load if not provided)
 */
export default function BookingFormCore({ form, setForm, isAdmin = false }) {
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [availability, setAvailability] = useState([]);
  const [rawSlots, setRawSlots] = useState([]);
  const [bookedSlots, setBookedSlots] = useState([]);

  useEffect(() => {
    Promise.all([bookingService.getEventTypes(), bookingService.getAddOns()])
      .then(([etRes, aoRes]) => {
        setEventTypes(etRes.data.data || []);
        setAddOns(aoRes.data.data || []);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    const from = format(new Date(), 'yyyy-MM-dd');
    const to = format(addDays(new Date(), isAdmin ? 60 : 30), 'yyyy-MM-dd');
    availabilityService.getDates(from, to)
      .then(res => setAvailability(res.data.data || []))
      .catch(() => {});
  }, [isAdmin]);

  useEffect(() => {
    if (form.bookingDate) {
      availabilityService.getSlots(form.bookingDate)
        .then(res => setRawSlots(res.data.data?.availableSlots || []))
        .catch(() => {});
      const fetchBooked = isAdmin ? adminService.getBookedSlots(form.bookingDate) : bookingService.getBookedSlots(form.bookingDate);
      fetchBooked
        .then(res => setBookedSlots(res.data.data || []))
        .catch(() => setBookedSlots([]));
    }
  }, [form.bookingDate, isAdmin]);

  const selectedEvent = eventTypes.find(e => e.id === Number(form.eventTypeId));
  const minDur = selectedEvent?.minHours || 1;
  const maxDur = selectedEvent?.maxHours || 8;

  const durationSlots = (() => {
    if (!rawSlots.length || !form.durationMinutes) return [];
    const durMin = Number(form.durationMinutes);
    const isToday = form.bookingDate === format(new Date(), 'yyyy-MM-dd');
    const nowMinutes = new Date().getHours() * 60 + new Date().getMinutes();
    const bookedHalfHours = new Set();
    bookedSlots.forEach(bs => {
      const start = bs.startMinute != null ? bs.startMinute : bs.startHour * 60;
      const dur = bs.durationMinutes != null ? bs.durationMinutes : bs.durationHours * 60;
      for (let m = start; m < start + dur; m += 30) bookedHalfHours.add(Math.floor(m / 30));
    });
    const slots = [];
    for (let startMin = 0; startMin + durMin <= 24 * 60; startMin += 30) {
      if (isToday && startMin < nowMinutes + 30) continue;
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

  const toggleAddOn = (addon) => {
    const exists = form.addOns.find(a => a.addOnId === addon.id);
    if (exists) {
      setForm(f => ({ ...f, addOns: f.addOns.filter(a => a.addOnId !== addon.id) }));
    } else {
      setForm(f => ({ ...f, addOns: [...f.addOns, { addOnId: addon.id, quantity: 1, price: addon.price, name: addon.name }] }));
    }
  };

  const calculateTotal = () => {
    if (!selectedEvent) return 0;
    const base = selectedEvent.basePrice + (selectedEvent.hourlyRate * form.durationMinutes / 60);
    const addOnTotal = form.addOns.reduce((sum, a) => sum + (a.price * a.quantity), 0);
    return base + addOnTotal;
  };

  const inputStyle = {
    padding: '0.55rem 0.8rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)',
    background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.88rem', width: '100%',
  };
  const labelStyle = { fontWeight: 600, marginBottom: '0.4rem', display: 'block', fontSize: '0.85rem' };
  const sectionStyle = { marginBottom: '1.5rem' };

  return {
    eventTypes,
    addOns,
    availability,
    selectedEvent,
    minDur,
    maxDur,
    durationSlots,
    toggleAddOn,
    calculateTotal,
    inputStyle,
    labelStyle,
    sectionStyle,
  };
}
