import { format } from 'date-fns';

export default function StepDateTime({
  form, setForm, isAdmin, selectedEvent,
  durationOptions, durationSlots, availability, resolvedPricing,
  fmtTime, fmtDuration,
  onNext, onBack,
}) {
  return (
    <div className="booking-section">
      <h2>Select Date & Time</h2>
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
          <button type="button" className="btn btn-secondary btn-sm" aria-label="Decrease guests"
            style={{ width: '36px', height: '36px', padding: 0, fontSize: '1.2rem', fontWeight: 700 }}
            disabled={form.numberOfGuests <= 1}
            onClick={() => setForm(f => ({ ...f, numberOfGuests: Math.max(1, f.numberOfGuests - 1) }))}>−</button>
          <span style={{ fontSize: '1.1rem', fontWeight: 700, minWidth: '28px', textAlign: 'center' }} aria-live="polite">{form.numberOfGuests}</span>
          <button type="button" className="btn btn-secondary btn-sm" aria-label="Increase guests"
            style={{ width: '36px', height: '36px', padding: 0, fontSize: '1.2rem', fontWeight: 700 }}
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
        <div>
          <h3 style={{ marginBottom: '0.75rem' }}>Available Time Slots</h3>
          {form.bookingDate ? (
            durationSlots.length > 0 ? (
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
            ) : <p style={{ color: 'var(--text-muted)' }}>No available slots for this date with {fmtDuration(form.durationMinutes)} duration</p>
          ) : <p style={{ color: 'var(--text-muted)' }}>Select a date first</p>}
        </div>
      </div>
      <div className="booking-nav">
        <button className="btn btn-secondary" onClick={onBack}>Back</button>
        <button className="btn btn-primary" onClick={onNext}>
          Next: Add-Ons
        </button>
      </div>
    </div>
  );
}
