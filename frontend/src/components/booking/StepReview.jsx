import { formatCurrency } from '../../utils/currency';

export default function StepReview({
  form, setForm, isAdmin, selectedEvent, selectedCustomer,
  resolvedPricing, editBookingData, calculateTotal, calculateLoyaltyDiscount,
  fmtTime, fmtDuration,
  loading, onSubmit, onBack,
  capacityFull, onJoinWaitlist,
  venueRooms, activeSurge, loyalty, loyaltyQuote, redeemMax,
  taxPreview, earnQuote,
  // Native per-binge currency: every price here is in the venue's own currency; the
  // customer neither chooses nor converts it.
  currency = 'INR',
}) {
  const money = (v) => formatCurrency(v, currency);
  const selectedRoom = venueRooms?.find(r => r.id === form.venueRoomId);
  const loyaltyDiscount = calculateLoyaltyDiscount ? calculateLoyaltyDiscount() : 0;
  const subtotal = calculateTotal();
  const taxAmount = taxPreview?.totalTax ? Number(taxPreview.totalTax) : 0;
  const finalTotal = Math.max(0, subtotal - loyaltyDiscount + taxAmount);
  const perBookingTotal = Math.max(0, finalTotal);
  const recurringTotal = form.recurringEnabled ? perBookingTotal * Number(form.recurringOccurrences || 1) : perBookingTotal;

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

        {selectedRoom && (
          <div className="review-row">
            <span>Room / Space</span>
            <span>{selectedRoom.name} ({selectedRoom.roomType?.replace(/_/g, ' ')})</span>
          </div>
        )}
        {selectedRoom && Number(selectedRoom.priceAddition) > 0 && (
          <div className="review-row" style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
            <span>Room Charge ({selectedRoom.name})</span>
            <span>+{money(Number(selectedRoom.priceAddition))}</span>
          </div>
        )}

        {(() => {
          const rep = resolvedPricing?.eventPricings?.find(ep => ep.eventTypeId === selectedEvent?.id);
          const ppg = rep ? rep.pricePerGuest : selectedEvent?.pricePerGuest;
          return ppg > 0 && form.numberOfGuests > 1 ? (
            <div className="review-row" style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
              <span>Guest Charge ({form.numberOfGuests - 1} extra × {money(ppg)})</span>
              <span>{money((form.numberOfGuests - 1) * ppg)}</span>
            </div>
          ) : null;
        })()}

        {activeSurge && (
          <div className="review-row review-surge-row">
            <span>⚡ {activeSurge.label || 'Peak Pricing'}</span>
            <span>{activeSurge.multiplier}× multiplier</span>
          </div>
        )}

        {form.addOns.length > 0 && (
          <div className="review-row">
            <span>Add-Ons</span>
            <span>{form.addOns.map(a => a.name).join(', ')}</span>
          </div>
        )}
        {form.specialNotes && <div className="review-row"><span>Notes</span><span>{form.specialNotes}</span></div>}
        {isAdmin && form.adminNotes && <div className="review-row"><span>Admin Notes</span><span>{form.adminNotes}</span></div>}

        <hr style={{ borderColor: 'var(--border)', margin: '1rem 0' }} />

        {/* Loyalty Points Redemption — flexible slider: the customer chooses
            exactly how many points to apply, up to what's redeemable on this
            booking (venue-country point value, capped by the binge's max %). */}
        {!isAdmin && loyalty && loyalty.currentBalance > 0 && (() => {
          const loadingMax = redeemMax == null;
          const maxPts = redeemMax?.eligible ? Number(redeemMax.maxPoints || 0) : 0;
          const ppcu = Number(redeemMax?.pointsPerCurrencyUnit || 0);
          const minNeeded = Number(redeemMax?.minRedemptionPoints || 0);
          const chosen = Math.min(Number(form.redeemLoyaltyPoints) || 0, maxPts);
          const clamp = (n) => Math.max(0, Math.min(Number(n) || 0, maxPts));
          const setPts = (n) => setForm(f => ({ ...f, redeemLoyaltyPoints: clamp(n) }));
          return (
            <div className="loyalty-redeem-card">
              <div className="loyalty-redeem-header">
                <strong className="loyalty-redeem-title">Loyalty Points</strong>
                <span className="loyalty-redeem-balance">
                  {loyalty.tierLevel} · {loyalty.currentBalance.toLocaleString()} pts available
                </span>
              </div>

              {loadingMax ? (
                <p className="loyalty-redeem-hint">Checking how many points you can use…</p>
              ) : maxPts > 0 ? (
                <>
                  <input
                    type="range"
                    min={0}
                    max={maxPts}
                    step={1}
                    value={chosen}
                    onChange={(e) => setPts(e.target.value)}
                    className="loyalty-redeem-slider"
                    aria-label="Points to redeem"
                    aria-valuetext={`${chosen.toLocaleString()} of ${maxPts.toLocaleString()} points`}
                  />
                  <div className="loyalty-redeem-scale">
                    <span>0</span>
                    <span>Max {maxPts.toLocaleString()} pts</span>
                  </div>
                  <div className="loyalty-redeem-controls">
                    <label htmlFor="redeem-pts">Use</label>
                    <input
                      id="redeem-pts"
                      type="number"
                      min={0}
                      max={maxPts}
                      value={form.redeemLoyaltyPoints || ''}
                      onChange={(e) => setPts(e.target.value)}
                      placeholder="0"
                      className="loyalty-redeem-input"
                    />
                    <span className="loyalty-redeem-unit">pts</span>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => setPts(maxPts)}>
                      Use max
                    </button>
                    {chosen > 0 && (
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setPts(0)}>
                        Clear
                      </button>
                    )}
                  </div>
                  {loyaltyDiscount > 0 ? (
                    <p className="loyalty-redeem-discount">
                      −{money(loyaltyDiscount)} off · {(loyaltyQuote?.pointsToBurn || chosen).toLocaleString()} pts applied
                    </p>
                  ) : (
                    ppcu > 0 && (
                      <p className="loyalty-redeem-hint">
                        {ppcu.toLocaleString()} pts = {money(1)} at this venue
                        {minNeeded > 0 ? ` · min ${minNeeded.toLocaleString()} pts` : ''}
                      </p>
                    )
                  )}
                </>
              ) : (
                <p className="loyalty-redeem-hint">
                  {minNeeded > 0
                    ? `You need at least ${minNeeded.toLocaleString()} points, on a large enough booking, to redeem here.`
                    : 'Points can’t be applied to this booking.'}
                </p>
              )}
            </div>
          );
        })()}

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
                <p style={{ fontSize: '0.8rem', color: 'var(--success-text)', marginTop: '0.3rem' }}>Cash payment — booking will be auto-confirmed and recorded immediately</p>
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

        {(loyaltyDiscount > 0 || taxAmount > 0) && (
          <div className="review-row" style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
            <span>Subtotal</span>
            <span>{money(subtotal)}</span>
          </div>
        )}
        {loyaltyDiscount > 0 && (
          <div className="review-row" style={{ color: 'var(--success-text)', fontSize: '0.9rem' }}>
            <span>Loyalty Discount</span>
            <span>− {money(loyaltyDiscount)}</span>
          </div>
        )}
        {taxAmount > 0 && (
          <div className="review-row" style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
            <span>Tax</span>
            <span>+ {money(taxAmount)}</span>
          </div>
        )}
        <div className="review-row total">
          <span>{taxAmount > 0 ? 'Total (incl. tax)' : 'Estimated Total'}</span>
          <span aria-live="polite">{money(finalTotal)}</span>
        </div>

        {earnQuote?.eligible && earnQuote.points > 0 && (
          <div className="review-row" style={{ color: '#059669', fontSize: '0.9rem', border: 'none' }}>
            <span>⭐ {isAdmin ? 'Customer earns' : "You'll earn"} ~{Number(earnQuote.points).toLocaleString()} points
              {earnQuote.tierCode ? ` (${earnQuote.tierCode} tier)` : ''}</span>
            <span title="Points are credited after the visit completes.">
              {earnQuote.qualifyingCredits > 0 ? `+${Number(earnQuote.qualifyingCredits).toLocaleString()} tier credits` : ''}
            </span>
          </div>
        )}

        {/* Currency is fixed to this venue's country currency — no customer choice.
            A foreign customer's own bank/card converts from their home currency at charge time. */}
        {!isAdmin && (
          <p className="review-row" style={{ color: 'var(--text-muted)', fontSize: '0.8rem', border: 'none' }}>
            <span>Charged in {currency}. Your bank converts from your card's currency.</span>
          </p>
        )}
      </div>

      {/* Recurring Booking Option */}
      {!isAdmin && !editBookingData && (
        <div className="recurring-card">
          <div className="recurring-header">
            <label className="recurring-toggle">
              <input
                type="checkbox"
                checked={form.recurringEnabled}
                onChange={(e) => setForm(f => ({ ...f, recurringEnabled: e.target.checked }))}
              />
              <span className="recurring-toggle-label">🔁 Make this a recurring booking</span>
            </label>
          </div>
          {form.recurringEnabled && (
            <div className="recurring-controls">
              <div className="recurring-field">
                <label>Frequency</label>
                <select
                  value={form.recurringPattern}
                  onChange={(e) => setForm(f => ({ ...f, recurringPattern: e.target.value }))}
                  className="recurring-select"
                >
                  <option value="WEEKLY">Every week</option>
                  <option value="BIWEEKLY">Every 2 weeks</option>
                  <option value="MONTHLY">Every month</option>
                </select>
              </div>
              <div className="recurring-field">
                <label>Number of bookings</label>
                <div className="recurring-stepper">
                  <button type="button" className="btn btn-secondary btn-sm"
                    onClick={() => setForm(f => ({ ...f, recurringOccurrences: Math.max(2, (f.recurringOccurrences || 4) - 1) }))}>−</button>
                  <span className="recurring-count">{form.recurringOccurrences || 4}</span>
                  <button type="button" className="btn btn-secondary btn-sm"
                    onClick={() => setForm(f => ({ ...f, recurringOccurrences: Math.min(12, (f.recurringOccurrences || 4) + 1) }))}>+</button>
                </div>
              </div>
              <p className="recurring-summary">
                {form.recurringOccurrences || 4} bookings × {money(perBookingTotal)} each = <strong>{money(recurringTotal)} est. total</strong>
              </p>
              <p className="recurring-note">
                Each booking is created independently. Dates without availability will be skipped automatically.
              </p>
            </div>
          )}
        </div>
      )}

      {capacityFull && (
        <div className="waitlist-card">
          <p className="waitlist-card-title">This slot is fully booked</p>
          <p className="waitlist-card-sub">Join the waitlist and we'll notify you as soon as a spot opens up.</p>
          <button className="btn btn-primary" onClick={onJoinWaitlist} disabled={loading}>
            {loading ? 'Joining...' : 'Join Waitlist'}
          </button>
        </div>
      )}
      <div className="booking-nav">
        <button className="btn btn-secondary" onClick={onBack}>Back</button>
        <button className="btn btn-primary" onClick={onSubmit} disabled={loading || capacityFull}>
          {loading ? 'Processing...' : editBookingData ? 'Update Reservation' : form.recurringEnabled ? `Book ${form.recurringOccurrences || 4} Sessions` : 'Confirm Booking'}
        </button>
      </div>
    </div>
  );
}
