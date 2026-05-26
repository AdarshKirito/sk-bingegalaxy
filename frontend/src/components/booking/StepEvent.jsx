import { useMemo, useState } from 'react';
import LazyImage from '../ui/LazyImage';

export default function StepEvent({
  eventTypes, form, setForm, resolvedPricing,
  setImagePopup, isAdmin,
  onNext, onBack, onCancel,
}) {
  // V55 — chip filter built from the event types we already have so we
  // don't need an extra round trip. "All" sentinel = null.
  const [activeCat, setActiveCat] = useState(null);
  const chips = useMemo(() => {
    const seen = new Map();
    eventTypes.forEach(et => {
      if (et.categoryId && et.categoryName && !seen.has(et.categoryId)) {
        seen.set(et.categoryId, et.categoryName);
      }
    });
    return Array.from(seen, ([id, name]) => ({ id, name }));
  }, [eventTypes]);
  const visible = activeCat == null ? eventTypes : eventTypes.filter(et => et.categoryId === activeCat);

  return (
    <div className="booking-section">
      <h2>Choose Event Type</h2>
      {chips.length > 0 && (
        <div className="chip-row" style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', marginBottom: '0.75rem' }}>
          <button type="button" className={`chip${activeCat == null ? ' chip-active' : ''}`}
            onClick={() => setActiveCat(null)}>All</button>
          {chips.map(c => (
            <button key={c.id} type="button" className={`chip${activeCat === c.id ? ' chip-active' : ''}`}
              onClick={() => setActiveCat(c.id)}>{c.name}</button>
          ))}
        </div>
      )}
      <div className="grid-3">
        {visible.map(et => (
          <div key={et.id}
            className={`card event-type-card ${Number(form.eventTypeId) === et.id ? 'selected' : ''}`}
            onClick={() => setForm(f => ({ ...f, eventTypeId: et.id }))}
            role="radio" aria-checked={Number(form.eventTypeId) === et.id} tabIndex={0}
            onKeyDown={e => e.key === 'Enter' && setForm(f => ({ ...f, eventTypeId: et.id }))}>
            {et.imageUrls?.length > 0 && et.imageUrls[0] && (
              <div style={{ marginBottom: '0.75rem', borderRadius: 'var(--radius-sm)', overflow: 'hidden', cursor: 'zoom-in' }}
                onClick={e => { e.stopPropagation(); setImagePopup({ urls: et.imageUrls, name: et.name, index: 0 }); }}>
                <LazyImage src={et.imageUrls[0]} alt={et.name} style={{ height: '220px', borderRadius: 'var(--radius-sm)' }} />
                {et.imageUrls.length > 1 && <div style={{ textAlign: 'center', fontSize: '0.7rem', color: 'var(--text-muted)', padding: '0.15rem' }}>{et.imageUrls.length} images — click to view</div>}
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
        {isAdmin ? (
          <button className="btn btn-secondary" onClick={onBack}>Back: Customer</button>
        ) : (
          onCancel && <button className="btn btn-secondary" onClick={onCancel}>Cancel</button>
        )}
        <button className="btn btn-primary" onClick={onNext}>
          Next: Choose Date & Time
        </button>
      </div>
    </div>
  );
}
