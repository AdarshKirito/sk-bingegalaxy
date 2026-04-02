import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { adminService, paymentService } from '../services/endpoints';
import { toast } from 'react-toastify';
import BookingWizard from '../components/BookingWizard';

export default function AdminBookingCreate() {
  const navigate = useNavigate();
  const location = useLocation();
  const reinstateData = location.state?.reinstate || null;
  const editBookingData = location.state?.editBooking || null;

  // Price difference modal state
  const [priceDiff, setPriceDiff] = useState(null);
  const [chargeMethod, setChargeMethod] = useState('CASH');
  const [processing, setProcessing] = useState(false);

  const handleSubmit = async (payload) => {
    if (editBookingData?.bookingRef) {
      // Check if date or time actually changed
      const origParts = String(editBookingData.startTime).split(':');
      const origStartMin = parseInt(origParts[0],10)*60 + parseInt(origParts[1]||'0',10);
      const newParts = String(payload.startTime).split(':');
      const newStartMin = parseInt(newParts[0],10)*60 + parseInt(newParts[1]||'0',10);
      const dateChanged = payload.bookingDate !== editBookingData.bookingDate;
      const timeChanged = newStartMin !== origStartMin;
      const durationChanged = Number(payload.durationMinutes) !== Number(editBookingData.durationMinutes);
      const eventChanged = Number(payload.eventTypeId) !== Number(editBookingData.eventTypeId);

      // Detect add-on changes
      const oldAddOns = (editBookingData.addOns || []).map(a => `${a.addOnId}:${a.quantity}`).sort().join(',');
      const newAddOns = (payload.addOns || []).map(a => `${a.addOnId}:${a.quantity}`).sort().join(',');
      const addOnsChanged = oldAddOns !== newAddOns;
      const guestsChanged = Number(payload.numberOfGuests || 1) !== Number(editBookingData.numberOfGuests || 1);

      const somethingChanged = dateChanged || timeChanged || durationChanged || eventChanged || addOnsChanged || guestsChanged;

      if (!somethingChanged) {
        // Nothing changed — just update notes / customer info in place
        try {
          await adminService.updateBooking(editBookingData.bookingRef, {
            adminNotes: payload.adminNotes || editBookingData.adminNotes,
            specialNotes: payload.specialNotes,
            customerName: payload.customerName,
            customerEmail: payload.customerEmail,
            customerPhone: payload.customerPhone,
          });
          toast.success('Reservation updated (no reschedule needed)');
          navigate('/admin/bookings');
        } catch (err) {
          toast.error(err.userMessage || 'Update failed');
        }
        return;
      }

      // Something changed — use in-place update with pricing recalculation
      try {
        const updatePayload = {
          customerName: payload.customerName,
          customerEmail: payload.customerEmail,
          customerPhone: payload.customerPhone,
          specialNotes: payload.specialNotes,
          adminNotes: payload.adminNotes || editBookingData.adminNotes,
        };

        if (eventChanged) updatePayload.eventTypeId = Number(payload.eventTypeId);
        if (durationChanged) updatePayload.durationMinutes = Number(payload.durationMinutes);
        if (dateChanged) updatePayload.bookingDate = payload.bookingDate;
        if (timeChanged) updatePayload.startTime = payload.startTime;
        if (guestsChanged) updatePayload.numberOfGuests = Number(payload.numberOfGuests || 1);
        if (addOnsChanged) {
          updatePayload.addOns = (payload.addOns || []).map(a => ({
            addOnId: a.addOnId,
            quantity: a.quantity || 1,
          }));
        }

        const res = await adminService.updateBooking(editBookingData.bookingRef, updatePayload);
        const updated = res.data.data;

        // Check for price difference after update
        const oldTotal = editBookingData.totalAmount || 0;
        const newTotal = updated?.totalAmount ?? oldTotal;
        const diff = newTotal - oldTotal;

        if (Math.abs(diff) > 0.01) {
          // Show charge/refund modal — always ask admin what to do
          setPriceDiff({
            oldTotal,
            newTotal,
            diff,
            bookingRef: editBookingData.bookingRef,
            customerId: editBookingData.customerId || 0,
            paymentStatus: editBookingData.paymentStatus,
            collectedAmount: editBookingData.collectedAmount || 0,
          });
          return;
        }

        toast.success('Reservation updated — pricing recalculated');
        navigate('/admin/bookings');
      } catch (err) {
        toast.error(err.response?.data?.message || err.userMessage || 'Update failed');
      }
      return;
    }
    const res = await adminService.adminCreateBooking(payload);
    const ref = res.data.data?.bookingRef || 'created';

    toast.success(`Booking ${ref} created successfully!`);
    navigate('/admin/bookings');
  };

  // ── Handle charging / refunding the price difference ──────
  const handleChargeOrRefund = async () => {
    if (!priceDiff) return;
    const wasPaid = priceDiff.paymentStatus === 'SUCCESS'
                 || priceDiff.paymentStatus === 'PARTIALLY_REFUNDED';
    setProcessing(true);
    try {
      if (priceDiff.diff > 0) {
        // Price increased — collect additional payment
        await adminService.addPayment({
          bookingRef: priceDiff.bookingRef,
          amount: priceDiff.diff,
          customerId: priceDiff.customerId,
          paymentMethod: chargeMethod,
          notes: `Additional charge: price updated ₹${priceDiff.oldTotal.toLocaleString()} → ₹${priceDiff.newTotal.toLocaleString()}`,
        });
        toast.success(`Additional ₹${priceDiff.diff.toLocaleString()} collected (${chargeMethod})`);
      } else if (wasPaid) {
        // Price decreased & customer already paid — issue refund
        const payRes = await paymentService.getByBooking(priceDiff.bookingRef);
        const payments = payRes.data.data || [];
        const refundable = payments.find(p => p.status === 'SUCCESS' || p.status === 'PARTIALLY_REFUNDED');
        if (!refundable) {
          toast.error('No refundable payment found. Issue refund manually from the Payment tab.');
          navigate('/admin/bookings');
          return;
        }
        const refundAmt = Math.abs(priceDiff.diff);
        const maxRefundable = refundable.remainingRefundable ?? refundable.amount;
        if (refundAmt > maxRefundable) {
          toast.error(`Refund ₹${refundAmt.toLocaleString()} exceeds refundable ₹${maxRefundable.toLocaleString()}. Adjust manually from the Payment tab.`);
          navigate('/admin/bookings');
          return;
        }
        await adminService.initiateRefund({
          paymentId: refundable.id,
          amount: refundAmt,
          reason: `Price reduced: ₹${priceDiff.oldTotal.toLocaleString()} → ₹${priceDiff.newTotal.toLocaleString()}`,
        });
        toast.success(`Refund of ₹${refundAmt.toLocaleString()} initiated`);
      } else {
        // Price decreased but not paid yet — just inform
        toast.success(`New total is ₹${priceDiff.newTotal.toLocaleString()} (reduced by ₹${Math.abs(priceDiff.diff).toLocaleString()})`);
      }
    } catch (err) {
      toast.error(err.response?.data?.message || 'Payment action failed');
    }
    setProcessing(false);
    navigate('/admin/bookings');
  };

  const handleSkipCharge = () => {
    toast.info('Reservation updated — no payment adjustment made');
    navigate('/admin/bookings');
  };

  // ── Price difference modal ────────────────────────────────
  if (priceDiff) {
    const isIncrease = priceDiff.diff > 0;
    const absDiff = Math.abs(priceDiff.diff);
    const wasPaid = priceDiff.paymentStatus === 'SUCCESS'
                 || priceDiff.paymentStatus === 'PARTIALLY_REFUNDED';
    return (
      <div className="container" style={{ maxWidth: '500px', margin: '3rem auto' }}>
        <div className="card" style={{ padding: '2rem' }}>
          <h2 style={{ marginBottom: '1rem', textAlign: 'center' }}>
            {isIncrease ? '💰 Additional Charge Required' : (wasPaid ? '💳 Refund Due' : '📉 Price Reduced')}
          </h2>
          <p style={{ textAlign: 'center', color: 'var(--text-secondary)', marginBottom: '1.5rem' }}>
            The reservation price has changed after your edits.
          </p>

          <div style={{ background: 'var(--bg-input)', borderRadius: 'var(--radius-sm)', padding: '1rem', marginBottom: '1.5rem' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
              <span style={{ color: 'var(--text-secondary)' }}>Previous Total</span>
              <span>₹{priceDiff.oldTotal.toLocaleString()}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
              <span style={{ color: 'var(--text-secondary)' }}>New Total</span>
              <span style={{ fontWeight: 700 }}>₹{priceDiff.newTotal.toLocaleString()}</span>
            </div>
            {wasPaid && (
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Already Collected</span>
                <span>₹{priceDiff.collectedAmount.toLocaleString()}</span>
              </div>
            )}
            <div style={{ display: 'flex', justifyContent: 'space-between', paddingTop: '0.5rem', borderTop: '1px solid var(--border)', fontWeight: 700, fontSize: '1.1rem' }}>
              <span style={{ color: isIncrease ? 'var(--danger, #e74c3c)' : 'var(--success, #00b894)' }}>
                {isIncrease ? 'Customer Owes' : (wasPaid ? 'Refund to Customer' : 'New Savings')}
              </span>
              <span style={{ color: isIncrease ? 'var(--danger, #e74c3c)' : 'var(--success, #00b894)' }}>
                ₹{absDiff.toLocaleString()}
              </span>
            </div>
          </div>

          {isIncrease && (
            <div style={{ marginBottom: '1rem' }}>
              <label style={{ display: 'block', fontSize: '0.85rem', fontWeight: 600, marginBottom: '0.3rem' }}>
                Payment Method
              </label>
              <select value={chargeMethod} onChange={e => setChargeMethod(e.target.value)}
                style={{ padding: '0.5rem 0.8rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '100%' }}>
                <option value="CASH">Cash</option>
                <option value="UPI">UPI</option>
                <option value="CARD">Card</option>
                <option value="BANK_TRANSFER">Bank Transfer</option>
                <option value="WALLET">Wallet</option>
              </select>
            </div>
          )}

          <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'center' }}>
            <button className="btn btn-primary" onClick={handleChargeOrRefund} disabled={processing}
              style={{ minWidth: '160px' }}>
              {processing ? 'Processing...'
                : isIncrease ? `Charge ₹${absDiff.toLocaleString()}`
                : wasPaid ? `Refund ₹${absDiff.toLocaleString()}`
                : 'OK, Got It'}
            </button>
            <button className="btn btn-secondary" onClick={handleSkipCharge} disabled={processing}>
              {isIncrease ? "Don't Charge" : wasPaid ? "Don't Refund" : 'Skip'}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <BookingWizard
      isAdmin={true}
      reinstateData={reinstateData}
      editBookingData={editBookingData}
      onSubmit={handleSubmit}
      onCancel={() => navigate('/admin/bookings')}
    />
  );
}
