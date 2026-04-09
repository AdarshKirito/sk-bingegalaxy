import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { adminService, bookingService, paymentService } from '../services/endpoints';
import { toast } from 'react-toastify';
import BookingWizard from '../components/BookingWizard';
import { FiCheckCircle, FiCreditCard, FiTrendingDown, FiTrendingUp } from 'react-icons/fi';
import './AdminPages.css';

export default function AdminBookingCreate() {
  const navigate = useNavigate();
  const location = useLocation();
  const reinstateData = location.state?.reinstate || null;
  const editBookingData = location.state?.editBooking || null;

  // Price difference modal state
  const [priceDiff, setPriceDiff] = useState(null);
  const [chargeMethod, setChargeMethod] = useState('CASH');
  const [processing, setProcessing] = useState(false);

  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  const syncAdminPaymentRecord = async (booking, paymentMethod) => {
    const amount = Number(booking?.totalAmount ?? 0);
    const customerId = booking?.customerId ?? 0;

    if (!paymentMethod || !Number.isFinite(amount) || amount < 1 || !customerId) return;

    if (paymentMethod === 'CASH') {
      await adminService.recordCashPayment({
        bookingRef: booking.bookingRef,
        amount,
        customerId,
        notes: 'Recorded during admin booking creation',
      });
      return;
    }

    await adminService.addPayment({
      bookingRef: booking.bookingRef,
      amount,
      customerId,
      paymentMethod,
      notes: `${paymentMethod} payment recorded during admin booking creation`,
    });
  };

  const waitForBookingPaymentSync = async (bookingRef) => {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      try {
        const res = await bookingService.getByRef(bookingRef);
        const synced = res.data.data;
        if (synced?.paymentStatus === 'SUCCESS' && synced?.status === 'CONFIRMED') {
          return synced;
        }
      } catch (_) {
        // Ignore transient sync errors and retry briefly.
      }

      await wait(250);
    }

    return null;
  };

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
    const booking = res.data.data;
    const ref = booking?.bookingRef || 'created';
    const paymentMethod = payload.paymentMethod || 'CASH';
    const paymentLabel = paymentMethod.replace(/_/g, ' ').toLowerCase();

    try {
      await syncAdminPaymentRecord(booking, paymentMethod);
      const syncedBooking = await waitForBookingPaymentSync(ref);
      if (syncedBooking) {
        toast.success(`Booking ${ref} created and ${paymentLabel} payment recorded.`);
      } else {
        toast.info(`Booking ${ref} created and ${paymentLabel} payment recorded. Status may take a moment to refresh.`);
      }
    } catch (err) {
      toast.error(
        `Booking ${ref} was created, but the ${paymentLabel} payment record failed. Complete it from Bookings.`
      );
    }

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
      <div className="container adm-shell" style={{ maxWidth: '760px' }}>
        <div className="adm-summary">
          <div className="adm-summary-copy">
            <span className="adm-summary-title">
              {isIncrease ? 'Additional Charge Required' : (wasPaid ? 'Refund Review Required' : 'Price Reduced')}
            </span>
            <span className="adm-summary-text">The reservation total changed after your edits. Choose how to handle the difference before returning to bookings.</span>
          </div>
          <div className="adm-inline-actions">
            <span className={`adm-badge ${isIncrease ? 'adm-badge-inactive' : 'adm-badge-active'}`}>
              {isIncrease ? <FiTrendingUp /> : <FiTrendingDown />}
              {isIncrease ? 'Charge' : 'Adjustment'}
            </span>
          </div>
        </div>

        <div className="adm-card">
          <div className="adm-panel-stack">
            <div className="adm-table-wrap">
              <table className="adm-table">
                <thead>
                  <tr>
                    <th>Metric</th>
                    <th>Amount</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Previous Total</td>
                    <td>₹{priceDiff.oldTotal.toLocaleString()}</td>
                  </tr>
                  <tr>
                    <td>New Total</td>
                    <td className="highlight">₹{priceDiff.newTotal.toLocaleString()}</td>
                  </tr>
                  {wasPaid && (
                    <tr>
                      <td>Already Collected</td>
                      <td>₹{priceDiff.collectedAmount.toLocaleString()}</td>
                    </tr>
                  )}
                  <tr>
                    <td className="highlight">{isIncrease ? 'Customer Owes' : (wasPaid ? 'Refund to Customer' : 'Customer Saves')}</td>
                    <td className={`highlight ${isIncrease ? '' : 'success'}`} style={isIncrease ? { color: 'var(--danger)' } : undefined}>
                      ₹{absDiff.toLocaleString()}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            {isIncrease && (
              <div className="input-group" style={{ maxWidth: '260px' }}>
                <label><FiCreditCard style={{ marginRight: 6, verticalAlign: -2 }} />Payment Method</label>
                <select value={chargeMethod} onChange={(e) => setChargeMethod(e.target.value)}>
                  <option value="CASH">Cash</option>
                  <option value="UPI">UPI</option>
                  <option value="CARD">Card</option>
                  <option value="BANK_TRANSFER">Bank Transfer</option>
                  <option value="WALLET">Wallet</option>
                </select>
              </div>
            )}

            <div className="adm-form-actions">
              <button className="btn btn-secondary" onClick={handleSkipCharge} disabled={processing}>
                {isIncrease ? "Don't Charge" : wasPaid ? "Don't Refund" : 'Skip'}
              </button>
              <button className="btn btn-primary" onClick={handleChargeOrRefund} disabled={processing}>
                {processing
                  ? 'Processing...'
                  : isIncrease
                    ? `Charge ₹${absDiff.toLocaleString()}`
                    : wasPaid
                      ? `Refund ₹${absDiff.toLocaleString()}`
                      : <><FiCheckCircle /> OK, Got It</>}
              </button>
            </div>
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
