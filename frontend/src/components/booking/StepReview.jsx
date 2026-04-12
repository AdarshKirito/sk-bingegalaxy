export default function StepReview({
  form, setForm, isAdmin, selectedEvent, selectedCustomer,
  resolvedPricing, editBookingData, calculateTotal,
  fmtTime, fmtDuration,
  loading, onSubmit, onBack,
}) {
  return (
    <div className="booking-section">
      <h2>Review Your Booking</h2>
      <div className="card review-card" role="region" aria-label="Booking summary">
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
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
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
                  aria-label="Payment method"
                  style={{ padding: '0.4rem 0.6rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem' }}>
                  <option value="CASH">Cash</option>
                  <option value="UPI">UPI</option>
                  <option value="CARD">Card</option>
                  <option value="BANK_TRANSFER">Bank Transfer</option>
                  <option value="WALLET">Wallet</option>
                  <option value="COLLECT_LATER">Collect Later</option>
                </select>
              </div>
              {form.paymentMethod === 'CASH' && (
                <p style={{ fontSize: '0.8rem', color: 'var(--success)', marginTop: '0.3rem' }}>Cash payment — booking will be auto-confirmed and recorded immediately</p>
              )}
              {form.paymentMethod === 'COLLECT_LATER' && (
                <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.3rem' }}>Booking will stay pending until you record a payment from the Bookings page.</p>
              )}
              {form.paymentMethod !== 'CASH' && form.paymentMethod !== 'COLLECT_LATER' && (
                <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.3rem' }}>This records the payment immediately after the booking is created.</p>
              )}
            </>
          )
        ) : null}

        <div className="review-row total">
          <span>Estimated Total</span>
          <span aria-live="polite">₹{calculateTotal().toLocaleString()}</span>
        </div>
      </div>
      <div className="booking-nav">
        <button className="btn btn-secondary" onClick={onBack}>Back</button>
        <button className="btn btn-primary" onClick={onSubmit} disabled={loading}>
          {loading ? 'Processing...' : editBookingData ? 'Update Reservation' : 'Confirm Booking'}
        </button>
      </div>
    </div>
  );
}
