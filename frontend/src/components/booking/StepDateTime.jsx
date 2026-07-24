import { useState } from 'react';
import { format } from 'date-fns';
import SlotSuggestionsPanel from './SlotSuggestionsPanel';
import VenueTimeNote from '../VenueTimeNote';
import RoomDetailModal from './RoomDetailModal';
import { venueLocalTodayISO } from '../../utils/format';
import { formatCurrency } from '../../utils/currency';

export default function StepDateTime({
  form, setForm, isAdmin, selectedEvent,
  durationOptions, durationSlots, availability, resolvedPricing,
  venueRooms, activeSurge, roomAvailability, currency = 'INR',
  venueTimezone,
  surgeRules, editBookingRef,
  fmtTime, fmtDuration,
  onNext, onBack,
}) {
  const money = (v) => formatCurrency(v, currency);
  const [detailRoom, setDetailRoom] = useState(null); // room whose photos/reviews modal is open
  // Find the next date (after the current selection) that still has slots free —
  // we use this to power the "Try {next date}" button on the empty-state message.
  // Note: real availability for a given date depends on duration, existing bookings,
  // and "today" filtering, so this is a best-effort hint based on `fullyBlocked` only.
  const nextOpenDate = (() => {
    if (!form.bookingDate) return null;
    const sorted = (availability || []).filter(d => !d.fullyBlocked).map(d => d.date).sort();
    const idx = sorted.indexOf(form.bookingDate);
    if (idx < 0 || idx >= sorted.length - 1) return null;
    return sorted[idx + 1];
  })();

  const isTodaySelected = form.bookingDate === venueLocalTodayISO(venueTimezone);
  return (
    <div className="booking-section">
      <h2>Select Date & Time</h2>
      {venueTimezone && (
        <div style={{ marginBottom: '1rem' }}>
          <VenueTimeNote timezone={venueTimezone} variant="banner" />
        </div>
      )}
      <div className="input-group" style={{ maxWidth: '300px', marginBottom: '1.25rem' }}>
        <label htmlFor="duration-select">Duration</label>
        <select id="duration-select" value={form.durationMinutes} onChange={e => setForm(f => ({ ...f, durationMinutes: Number(e.target.value), startTime: '' }))}>
          {durationOptions.map(m => <option key={m} value={m}>{fmtDuration(m)}</option>)}
        </select>
      </div>
      <div className="input-group" style={{ maxWidth: '300px', marginBottom: '1.25rem' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          Number of Guests
        </label>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          {(() => {
            const minG = Number.isFinite(Number(selectedEvent?.minGuests)) && selectedEvent?.minGuests != null
              ? Number(selectedEvent.minGuests) : 1;
            const maxG = Number.isFinite(Number(selectedEvent?.maxGuests)) && selectedEvent?.maxGuests != null
              ? Number(selectedEvent.maxGuests) : 100;
            return (<>
              <button type="button" className="btn btn-secondary btn-sm" aria-label="Decrease guests"
                style={{ width: '36px', height: '36px', padding: 0, fontSize: '1.2rem', fontWeight: 700 }}
                disabled={form.numberOfGuests <= minG}
                onClick={() => setForm(f => ({ ...f, numberOfGuests: Math.max(minG, f.numberOfGuests - 1) }))}>−</button>
              <span style={{ fontSize: '1.1rem', fontWeight: 700, minWidth: '28px', textAlign: 'center' }} aria-live="polite">{form.numberOfGuests}</span>
              <button type="button" className="btn btn-secondary btn-sm" aria-label="Increase guests"
                style={{ width: '36px', height: '36px', padding: 0, fontSize: '1.2rem', fontWeight: 700 }}
                disabled={form.numberOfGuests >= maxG}
                onClick={() => setForm(f => ({ ...f, numberOfGuests: Math.min(maxG, f.numberOfGuests + 1) }))}>+</button>
              {(selectedEvent?.minGuests != null || selectedEvent?.maxGuests != null) && (
                <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                  ({selectedEvent?.minGuests ?? 1}–{selectedEvent?.maxGuests ?? '∞'})
                </span>
              )}
            </>);
          })()}
        </div>
        {(() => {
          const rep = resolvedPricing?.eventPricings?.find(ep => ep.eventTypeId === selectedEvent?.id);
          const ppg = rep ? rep.pricePerGuest : selectedEvent?.pricePerGuest;
          return ppg > 0 && form.numberOfGuests > 1 ? (
            <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.35rem' }}>
              Additional guest charge: {money(ppg)} × {form.numberOfGuests - 1} = {money((form.numberOfGuests - 1) * ppg)}
            </p>
          ) : null;
        })()}
      </div>
      {/* Flow: Date → Room/Space → Time. Times depend on the chosen room. */}
      <div style={{ marginBottom: '1.5rem' }}>
        <h3 style={{ marginBottom: '0.75rem' }}>1 · Select a Date</h3>
        <div className="date-grid" role="listbox" aria-label="Available dates">
          {availability.filter(d => !d.fullyBlocked).map(d => (
            <button key={d.date}
              className={`date-btn ${form.bookingDate === d.date ? 'selected' : ''}`}
              aria-selected={form.bookingDate === d.date}
              onClick={() => setForm(f => ({ ...f, bookingDate: d.date, startTime: '' }))}>
              {format(new Date(d.date + 'T00:00:00'), 'MMM dd, EEE')}
            </button>
          ))}
        </div>
      </div>

      {form.bookingDate && venueRooms.length > 0 && (
        <div className="room-section" style={{ marginBottom: '1.5rem' }}>
          <h3 style={{ marginBottom: '0.35rem' }}>2 · Select Room / Space</h3>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: 0, marginBottom: '0.75rem' }}>
            Available times depend on the room. Choose “Any available” and we’ll assign the first free room automatically.
          </p>
          <div className="room-grid" role="listbox" aria-label="Available rooms">
            <button
              className={`room-card ${!form.venueRoomId ? 'selected' : ''}`}
              aria-selected={!form.venueRoomId}
              onClick={() => setForm(f => ({ ...f, venueRoomId: '', startTime: '' }))}>
              Any available
            </button>
            {venueRooms.map(room => {
              // A room is unavailable when it has zero bookable times on this date+duration —
              // hidden/disabled "just like times are for booked slots".
              const isAvailable = !roomAvailability || roomAvailability[room.id] !== false;
              const priceAdd = Number(room.priceAddition || 0);
              const thumb = Array.isArray(room.imageUrls) && room.imageUrls.length > 0 ? room.imageUrls[0] : null;
              const hasDetails = (Array.isArray(room.imageUrls) && room.imageUrls.length > 0) || !!room.description;
              return (
                <div key={room.id} className="room-card-wrap" style={{ position: 'relative' }}>
                  <button
                    className={`room-card ${form.venueRoomId === room.id ? 'selected' : ''} ${!isAvailable ? 'room-card-full' : ''}`}
                    style={{ width: '100%', height: '100%' }}
                    aria-selected={form.venueRoomId === room.id}
                    disabled={!isAvailable}
                    onClick={() => setForm(f => ({ ...f, venueRoomId: room.id, startTime: '' }))}>
                    {thumb && (
                      <img src={thumb} alt="" className="room-card-thumb"
                        onError={(e) => { e.currentTarget.style.display = 'none'; }} />
                    )}
                    <span className="room-card-name">{room.name}</span>
                    <span className="room-card-detail">
                      {room.roomType?.replace(/_/g, ' ')}
                    </span>
                    {priceAdd > 0 && (
                      <span className="room-card-price">+{money(priceAdd)}</span>
                    )}
                    {!isAvailable && <span className="room-card-full-label">Fully booked</span>}
                  </button>
                  {/* Details trigger sits OUTSIDE the select button (valid HTML) and opens the
                      photos + description + reviews modal without selecting the room. */}
                  <button type="button" className="room-card-info-btn"
                    title="View photos, description & reviews"
                    aria-label={`View details for ${room.name}`}
                    onClick={(e) => { e.stopPropagation(); setDetailRoom(room); }}
                    style={{
                      position: 'absolute', top: 6, right: 6, zIndex: 2,
                      width: 26, height: 26, borderRadius: '50%', border: 'none',
                      background: 'rgba(0,0,0,0.55)', color: '#fff', cursor: 'pointer',
                      fontSize: '0.9rem', lineHeight: 1, display: 'flex',
                      alignItems: 'center', justifyContent: 'center',
                    }}>
                    {hasDetails ? '⤢' : 'ⓘ'}
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {detailRoom && (
        <RoomDetailModal
          room={detailRoom}
          currency={currency}
          onClose={() => setDetailRoom(null)}
          onSelect={(roomId) => setForm(f => ({ ...f, venueRoomId: roomId, startTime: '' }))}
        />
      )}

      {form.bookingDate && (
        <div>
          <h3 style={{ marginBottom: '0.75rem' }}>
            {venueRooms.length > 0 ? '3 · ' : '2 · '}Select a Time
            {venueRooms.length > 0 && (
              <span style={{ fontSize: '0.85rem', fontWeight: 400, color: 'var(--text-muted)' }}>
                {' '}— {form.venueRoomId
                  ? (venueRooms.find(r => r.id === form.venueRoomId)?.name || 'selected room')
                  : 'any available room'}
              </span>
            )}
          </h3>
          {durationSlots.length > 0 ? (
            <div className="slot-grid" role="listbox" aria-label="Available time slots">
              {durationSlots.map(startMin => (
                <button key={startMin}
                  className={`slot-btn ${form.startTime === startMin ? 'selected' : ''}`}
                  aria-selected={form.startTime === startMin}
                  onClick={() => setForm(f => ({ ...f, startTime: startMin }))}>
                  {fmtTime(startMin)} – {fmtTime(startMin + Number(form.durationMinutes))}
                </button>
              ))}
            </div>
          ) : (
            <div className="slot-empty-state" role="status" style={{
              padding: '1rem 1.1rem', borderRadius: '10px',
              border: '1px dashed var(--border, #d1d5db)',
              background: 'var(--primary-bg, rgba(99,102,241,0.04))',
              color: 'var(--text-secondary, #555)', fontSize: '0.9rem',
              lineHeight: 1.5, display: 'flex', flexDirection: 'column', gap: '0.5rem',
            }}>
              <strong style={{ color: 'var(--text-primary, #222)' }}>
                No {fmtDuration(form.durationMinutes)} slots available on {format(new Date(form.bookingDate + 'T00:00:00'), 'EEE, MMM dd')}
                {form.venueRoomId && venueRooms.length > 0 ? ` in ${venueRooms.find(r => r.id === form.venueRoomId)?.name || 'this room'}` : ''}
              </strong>
              <span>
                {form.venueRoomId
                  ? 'This room is fully booked for the duration you selected. Try “Any available”, another room, a shorter duration, or a different date.'
                  : isTodaySelected
                    ? 'Today is mostly booked or past business hours for the duration you selected. Try a shorter duration or pick the next available date.'
                    : 'This date is fully booked for the duration you selected. Try a different date or a shorter duration.'}
              </span>
              <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginTop: '0.25rem' }}>
                {Number(form.durationMinutes) > (isAdmin ? 30 : 120) && (
                  <button type="button" className="btn btn-sm btn-secondary"
                    onClick={() => setForm(f => ({ ...f, durationMinutes: Number(f.durationMinutes) - 30, startTime: '' }))}>
                    Try {fmtDuration(Number(form.durationMinutes) - 30)}
                  </button>
                )}
                {nextOpenDate && (
                  <button type="button" className="btn btn-sm btn-primary"
                    onClick={() => setForm(f => ({ ...f, bookingDate: nextOpenDate, startTime: '' }))}>
                    Try {format(new Date(nextOpenDate + 'T00:00:00'), 'EEE, MMM dd')}
                  </button>
                )}
              </div>
              {!form.venueRoomId && (
                <SlotSuggestionsPanel
                  desiredDate={form.bookingDate}
                  desiredStartMin={form.startTime}
                  durationMinutes={form.durationMinutes}
                  availability={availability}
                  surgeRules={surgeRules}
                  isAdmin={isAdmin}
                  editBookingRef={editBookingRef}
                  fmtTime={fmtTime}
                  fmtDuration={fmtDuration}
                  onPick={(date, startMin) => setForm(f => ({ ...f, bookingDate: date, startTime: startMin }))}
                />
              )}
            </div>
          )}
        </div>
      )}

      {activeSurge && (
        <div className="surge-banner">
          <span className="surge-banner-icon">{Number(activeSurge.multiplier) < 1 ? '🌙' : '⚡'}</span>
          <div>
            <strong className="surge-banner-title">
              {activeSurge.label || (Number(activeSurge.multiplier) < 1 ? 'Off-peak Discount' : 'Peak Pricing')}
            </strong>
            <p className="surge-banner-sub">
              {activeSurge.multiplier}× multiplier applies to this time slot
              {Number(activeSurge.multiplier) < 1 ? ' — you save on this slot' : ''}
            </p>
          </div>
        </div>
      )}

      <div className="booking-nav">
        <button className="btn btn-secondary" onClick={onBack}>Back</button>
        <button className="btn btn-primary" onClick={onNext}>
          Next: Add-Ons
        </button>
      </div>
    </div>
  );
}
