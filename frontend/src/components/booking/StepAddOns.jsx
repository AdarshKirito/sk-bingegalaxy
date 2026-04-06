export default function StepAddOns({
  addOns, form, setForm, isAdmin, resolvedPricing,
  toggleAddOn, setImagePopup,
  onNext, onBack,
}) {
  return (
    <div className="booking-section">
      <h2>Choose Add-Ons</h2>
      <div className="grid-3" role="group" aria-label="Available add-ons">
        {addOns.map(ao => {
          const selected = form.addOns.find(a => a.addOnId === ao.id);
          return (
            <div key={ao.id}
              className={`card addon-card ${selected ? 'selected' : ''}`}
              onClick={() => toggleAddOn(ao)}
              role="checkbox" aria-checked={!!selected} tabIndex={0}
              onKeyDown={e => e.key === 'Enter' && toggleAddOn(ao)}>
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
        <label htmlFor="special-notes">Special Notes (optional)</label>
        <textarea id="special-notes" rows={3} value={form.specialNotes}
          onChange={e => setForm(f => ({ ...f, specialNotes: e.target.value }))}
          placeholder="Any special requests or instructions..." />
      </div>

      {isAdmin && (
        <div className="input-group" style={{ marginTop: '1rem', maxWidth: '500px' }}>
          <label htmlFor="admin-notes">Admin Notes (internal)</label>
          <textarea id="admin-notes" rows={2} value={form.adminNotes}
            onChange={e => setForm(f => ({ ...f, adminNotes: e.target.value }))}
            placeholder="Internal notes (not visible to customer)..." />
        </div>
      )}

      <div className="booking-nav">
        <button className="btn btn-secondary" onClick={onBack}>Back</button>
        <button className="btn btn-primary" onClick={onNext}>
          Next: Review & Confirm
        </button>
      </div>
    </div>
  );
}
