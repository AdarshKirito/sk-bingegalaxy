import { useState, useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { bookingService, paymentService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { trackPaymentStarted, trackPaymentCompleted, trackPaymentFailed } from '../services/analytics';
import SEO from '../components/SEO';
import {
  FiAlertCircle,
  FiArrowRight,
  FiCalendar,
  FiCheckCircle,
  FiClock,
  FiCreditCard,
  FiHash,
  FiLayers,
  FiMail,
  FiMapPin,
  FiRefreshCw,
  FiShield,
  FiUsers,
} from 'react-icons/fi';
import useBingeStore from '../stores/bingeStore';
import './CustomerHub.css';

const formatAmount = (value) => `₹${Number(value || 0).toLocaleString()}`;

const formatLabel = (value, fallback = 'Pending') => {
  if (!value) return fallback;
  return String(value)
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (char) => char.toUpperCase());
};

const formatDuration = (booking) => {
  const minutes = booking?.durationMinutes || (booking?.durationHours ? booking.durationHours * 60 : 0);
  if (!minutes) return 'Not set';

  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;

  if (hours > 0 && remainingMinutes > 0) return `${hours}hr ${remainingMinutes}m`;
  if (hours > 0) return `${hours}hr`;
  return `${remainingMinutes}m`;
};

export default function PaymentPage() {
  const { ref } = useParams();
  const { selectedBinge } = useBingeStore();
  const [booking, setBooking] = useState(null);
  const [paymentMethod, setPaymentMethod] = useState('UPI');
  const [payment, setPayment] = useState(null);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState(false);
  const paymentCallbackRef = useRef(false);

  // ── Derived financial state ──────────────────────────────
  // balanceDue: prefer the server-computed field; fall back to totalAmount − collectedAmount.
  // This stays accurate after admin price changes and partial refunds without requiring
  // an extra round-trip.
  const balanceDue = booking
    ? (booking.balanceDue != null
        ? Number(booking.balanceDue)
        : Math.max(0, Number(booking.totalAmount || 0) - Number(booking.collectedAmount || 0)))
    : 0;
  const hasOutstandingBalance = balanceDue > 0.01;
  // Amount the customer should pay on the NEXT transaction: the remaining balance when
  // something was already collected, otherwise the full booking total.
  const amountToPay = hasOutstandingBalance ? balanceDue : Number(booking?.totalAmount || 0);

  const loadData = async () => {
    setLoading(true);
    try {
      const bookRes = await bookingService.getByRef(ref);
      setBooking(bookRes.data.data);

      try {
        const payRes = await paymentService.getByBooking(ref);
        const payments = toArray(payRes.data?.data);
        const sortedPayments = [...payments].sort(
          (left, right) => new Date(right.createdAt || right.updatedAt || right.paidAt || 0) - new Date(left.createdAt || left.updatedAt || left.paidAt || 0)
        );
        const existing =
          sortedPayments.find((entry) => entry.status === 'SUCCESS' || entry.status === 'REFUNDED' || entry.status === 'PARTIALLY_REFUNDED') ||
          sortedPayments.find((entry) => entry.status === 'INITIATED') ||
          sortedPayments.find((entry) => entry.status === 'FAILED');
        setPayment(existing || null);
      } catch {
        setPayment(null);
      }
    } catch {
      toast.error('Booking not found');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [ref]);

  const loadRazorpayScript = () =>
    new Promise((resolve) => {
      if (window.Razorpay) { resolve(true); return; }
      const script = document.createElement('script');
      script.src = 'https://checkout.razorpay.com/v1/checkout.js';
      script.onload = () => resolve(true);
      script.onerror = () => resolve(false);
      document.body.appendChild(script);
    });

  const handleInitiate = async () => {
    if (!amountToPay || amountToPay <= 0) {
      toast.error('Booking amount is invalid. Please contact support.');
      return;
    }
    setProcessing(true);
    trackPaymentStarted(ref, amountToPay);
    try {
      const res = await paymentService.initiate({
        bookingRef: ref,
        amount: amountToPay,
        paymentMethod,
      });
      const payData = res.data.data;
      setPayment(payData);

      if (payData.razorpayKeyId && payData.gatewayOrderId?.startsWith('order_')) {
        const loaded = await loadRazorpayScript();
        if (!loaded) {
          toast.error('Could not load payment gateway. Please try again.');
          setProcessing(false);
          return;
        }

        const rzp = new window.Razorpay({
          key: payData.razorpayKeyId,
          amount: Math.round(payData.amount * 100), // rupees → paise
          currency: payData.currency || 'INR',
          name: 'SK Binge Galaxy',
          description: `Booking ${ref}`,
          order_id: payData.gatewayOrderId,
          handler: async (response) => {
            if (paymentCallbackRef.current) return;
            paymentCallbackRef.current = true;
            try {
              await paymentService.callback({
                gatewayOrderId: response.razorpay_order_id,
                gatewayPaymentId: response.razorpay_payment_id,
                gatewaySignature: response.razorpay_signature,
                status: 'success',
              });
              toast.success('Payment successful!');
              trackPaymentCompleted(ref, amountToPay, paymentMethod);
              await loadData();
            } catch (err) {
              trackPaymentFailed(ref, err.response?.data?.message || 'verification_error');
              const status = err.response?.status;
              const serverMsg = err.response?.data?.message;
              if (!err.response) {
                toast.error('Network error during payment verification. Your payment may have succeeded — please refresh.');
              } else if (status === 403) {
                toast.error('Payment signature invalid. Contact support with ref: ' + ref);
              } else {
                toast.error(serverMsg || 'Payment verification failed. Contact support.');
              }
            } finally {
              setProcessing(false);
              paymentCallbackRef.current = false;
            }
          },
          modal: {
            ondismiss: () => setProcessing(false),
          },
          prefill: {
            name: booking.customerName,
            email: booking.customerEmail,
          },
          theme: { color: '#e50914' },
        });

        rzp.on('payment.failed', (response) => {
          toast.error('Payment failed: ' + (response.error?.description || 'Try again.'));
          setProcessing(false);
          loadData();
        });

        rzp.open();
        // don't setProcessing(false) here — modal is still open
      } else {
        // Simulation mode or no Razorpay key configured
        toast.info('Payment initiated. Complete it through the payment provider flow, then refresh the status here.');
        setProcessing(false);
      }
    } catch (err) {
      const status = err.response?.status;
      const serverMsg = err.response?.data?.message;
      if (!err.response) {
        toast.error('Network error — check your connection and try again.');
      } else if (status === 409) {
        toast.error(serverMsg || 'Payment already completed for this booking.');
        await loadData();
      } else if (status === 403) {
        toast.error(serverMsg || 'Not authorised to initiate this payment.');
      } else if (status >= 500) {
        toast.error('Server error — please try again later or contact support.');
      } else {
        toast.error(serverMsg || 'Payment failed');
      }
      setProcessing(false);
    }
  };

  if (loading) return <div className="loading"><div className="spinner"></div></div>;
  if (!booking) return <div className="container customer-flow-shell customer-flow-shell-narrow"><div className="customer-flow-card customer-flow-empty"><h2>Booking not found</h2></div></div>;

  const eventLabel = booking.eventType?.name ?? booking.eventType ?? 'Private screening';
  const venueLabel = selectedBinge?.name || booking.venueName || booking.bingeName || 'Selected venue';
  const paymentState = String(payment?.status || booking.paymentStatus || 'PENDING').toUpperCase();
  const paymentStatusLabel = formatLabel(paymentState, 'Awaiting Payment');
  // isBalanceDue: payment was made but admin later increased the price OR a partial refund
  // created an open gap.  The customer needs a Pay button even though the gateway considers
  // the original transaction "successful".
  const isBalanceDue = hasOutstandingBalance
    && (paymentState === 'SUCCESS' || paymentState === 'PARTIALLY_REFUNDED' || paymentState === 'PARTIALLY_PAID');
  // isSuccess is only true when the reservation is genuinely settled — no open balance.
  const isSuccess = !isBalanceDue
    && (paymentState === 'SUCCESS' || paymentState === 'REFUNDED' || paymentState === 'PARTIALLY_REFUNDED');
  const isInitiated = !isBalanceDue && paymentState === 'INITIATED';
  const isFailed = !isBalanceDue && paymentState === 'FAILED';
  const amountLabel = formatAmount(booking.totalAmount);
  const balanceDueLabel = formatAmount(balanceDue);
  const durationLabel = formatDuration(booking);
  const addOnsLabel = (booking.addOns || []).map((item) => item.name ?? item.addOnName).filter(Boolean).join(', ');
  const bookingDetails = [
    { icon: <FiLayers />, label: 'Event', value: eventLabel },
    { icon: <FiCalendar />, label: 'Date', value: booking.bookingDate || 'Not set' },
    { icon: <FiClock />, label: 'Start time', value: booking.startTime || 'Not set' },
    { icon: <FiHash />, label: 'Duration', value: durationLabel },
    { icon: <FiUsers />, label: 'Guests', value: `${booking.numberOfGuests || 1} guest${Number(booking.numberOfGuests || 1) === 1 ? '' : 's'}` },
    { icon: <FiMapPin />, label: 'Venue', value: venueLabel },
  ];
  const summaryFacts = [
    { label: 'Total booking amount', value: amountLabel },
    ...(isBalanceDue
      ? [
          { label: 'Amount paid', value: formatAmount(booking.collectedAmount || 0) },
          { label: 'Balance due', value: balanceDueLabel },
        ]
      : [{ label: 'Status', value: paymentStatusLabel }]),
    { label: 'Booking ref', value: ref, mono: true },
    { label: 'Transaction', value: payment?.transactionId || payment?.gatewayOrderId || (isSuccess ? 'Recorded on booking' : 'Created after initiation'), mono: true },
  ];
  const breakdownRows = [
    { label: 'Base amount', value: formatAmount(booking.baseAmount ?? booking.totalAmount ?? 0) },
    { label: 'Add-on amount', value: formatAmount(booking.addOnAmount || 0) },
  ];

  if ((booking.guestAmount || 0) > 0) {
    breakdownRows.push({ label: 'Guest charge', value: formatAmount(booking.guestAmount || 0) });
  }
  if (isBalanceDue) {
    breakdownRows.push({ label: 'Already paid', value: formatAmount(booking.collectedAmount || 0) });
    breakdownRows.push({ label: 'Balance due', value: balanceDueLabel });
  }

  const actionFacts = [
    { label: 'Method in focus', value: formatLabel(payment?.paymentMethod || paymentMethod, formatLabel(paymentMethod)) },
    { label: 'Receipt email', value: booking.customerEmail || 'Linked to your account' },
    { label: 'Booking status', value: formatLabel(booking.status, 'Pending') },
    { label: 'Customer name', value: booking.customerName || 'Primary account holder' },
  ];
  const paymentBannerClass = isBalanceDue
    ? 'customer-flow-note-warning'
    : isSuccess
      ? 'customer-flow-note-success'
      : isInitiated
        ? 'customer-flow-note-warning'
        : isFailed
          ? 'customer-flow-note-danger'
          : 'customer-flow-note-info';
  const paymentBannerTitle = isBalanceDue
    ? `Outstanding balance of ${balanceDueLabel}`
    : isSuccess
      ? paymentState === 'SUCCESS'
        ? 'Payment captured for this reservation'
        : `Payment marked ${paymentStatusLabel.toLowerCase()}`
      : isInitiated
        ? 'Payment handoff already started'
        : isFailed
          ? 'The last payment attempt did not complete'
          : 'Choose a method and continue when ready';
  const paymentBannerBody = isBalanceDue
    ? `You have paid ${formatAmount(booking.collectedAmount || 0)} out of ${amountLabel}. Please pay the remaining ${balanceDueLabel} to fully settle this reservation. This can happen when an admin adjusts the booking price or a partial refund is applied.`
    : isSuccess
      ? 'You can move directly to the booking confirmation page or review the full payment trail from the payments hub.'
      : isInitiated
        ? 'If you just completed the provider step, refresh once. Start a new payment only if the previous handoff was abandoned.'
        : isFailed
          ? (payment?.failureReason || 'Retry with the same method or switch to another one for a cleaner attempt.')
          : 'This screen keeps the booking details, amount, and transaction status together so you do not have to cross-check another page before paying.';
  const nextSteps = isSuccess
    ? [
        {
          title: 'Open the booking confirmation',
          body: 'Use the confirmation page as the clean handoff from payment into the final reservation summary.',
        },
        {
          title: 'Keep the reference visible',
          body: `Your booking reference is ${ref}. It is the fastest way to identify this reservation later.`,
        },
        {
          title: 'Use My Bookings for follow-up',
          body: 'Future changes, support conversations, and reminders are easier from the timeline view.',
        },
      ]
    : isInitiated
      ? [
          {
            title: 'Finish the provider step',
            body: 'Complete the open transaction in the payment app or gateway window before starting anything new.',
          },
          {
            title: 'Refresh this screen once',
            body: 'The booking updates after the callback lands. A single refresh is usually enough to confirm the new state.',
          },
          {
            title: 'Only restart when the first attempt is abandoned',
            body: 'That keeps the transaction trail cleaner and avoids confusion around duplicate pending attempts.',
          },
        ]
      : isFailed
        ? [
            {
              title: 'Pick the retry route',
              body: 'If the bank or wallet blocked the first attempt, switch methods before creating the next transaction.',
            },
            {
              title: 'Create one fresh payment',
              body: 'A new initiation creates a clean payment record against the same booking reference.',
            },
            {
              title: 'Return to confirmation after success',
              body: 'Once the payment lands, the booking summary becomes the next stable page in the flow.',
            },
          ]
        : [
            {
              title: 'Review the reservation details',
              body: 'Check the event, date, time, guests, and add-ons here before you hand off to the gateway.',
            },
            {
              title: 'Choose a payment method and pay',
              body: 'The payment window opens after initiation and returns the result back to this screen.',
            },
            {
              title: 'Use the confirmation page next',
              body: 'After payment succeeds, the booking confirmation page becomes the best page for final review.',
            },
          ];
  const stages = [
    {
      title: 'Reservation',
      caption: `Booking ${ref} is already created.`,
      icon: <FiHash />,
      state: 'complete',
    },
    {
      title: 'Payment',
      caption: isSuccess
        ? 'Captured and synced.'
        : isBalanceDue
          ? `Balance of ${balanceDueLabel} outstanding.`
          : isInitiated
            ? 'In progress with the provider.'
            : isFailed
              ? 'Needs a retry.'
              : 'Waiting for checkout.',
      icon: <FiCreditCard />,
      state: isSuccess ? 'complete' : 'active',
    },
    {
      title: 'Confirmation',
      caption: isSuccess ? 'Ready for final review.' : 'Unlocks after successful payment.',
      icon: isSuccess ? <FiCheckCircle /> : <FiArrowRight />,
      state: isSuccess ? 'active' : 'pending',
    },
  ];

  return (
    <div className="container customer-flow-shell customer-flow-shell-wide">
      <SEO title="Payment" description="Review booking amount, retry pending payments, and continue payment flow for your booking." />

      <section className="customer-flow-hero">
        <div className="customer-flow-copy">
          <span className="customer-flow-kicker">Payment desk</span>
          <h1>Finish this reservation with a clearer payment handoff and a cleaner final review.</h1>
          <p>This page now keeps the booking blueprint, payment state, and next actions in one place so you do not have to bounce between screens to understand what is happening.</p>
          <div className="customer-flow-stagebar">
            {stages.map((stage) => (
              <article key={stage.title} className={`customer-flow-stage customer-flow-stage--${stage.state}`}>
                <div className="customer-flow-stage-top">
                  <span className="customer-flow-stage-icon">{stage.icon}</span>
                  <strong>{stage.title}</strong>
                </div>
                <small>{stage.caption}</small>
              </article>
            ))}
          </div>
          <div className="customer-flow-inline">
            <Link to="/payments" className="btn btn-secondary btn-sm">Payment History</Link>
            <Link to="/my-bookings" className="btn btn-secondary btn-sm">Booking Timeline</Link>
          </div>
        </div>

        <aside className="customer-flow-summary">
          <span className="customer-flow-kicker">Live payment state</span>
          <h2>{eventLabel}</h2>
          <p>{booking.bookingDate} at {booking.startTime}</p>
          <div className="customer-flow-badges">
            <span className="badge badge-info">Ref {ref}</span>
            <span className={`badge ${isSuccess ? 'badge-success' : isInitiated ? 'badge-warning' : isFailed ? 'badge-danger' : 'badge-info'}`}>
              {paymentStatusLabel}
            </span>
          </div>
          <strong>{amountLabel}</strong>
          <div className="customer-flow-fact-grid">
            {summaryFacts.map((fact) => (
              <div key={fact.label} className="customer-flow-fact">
                <span>{fact.label}</span>
                <strong className={fact.mono ? 'customer-flow-mono' : ''}>{fact.value}</strong>
              </div>
            ))}
          </div>
        </aside>
      </section>

      <section className="customer-flow-grid">
        <article className="customer-flow-card customer-flow-stack">
          <div className="customer-flow-card-head">
            <div>
              <span className="customer-flow-section-label">Reservation snapshot</span>
              <h2>Everything tied to this payment is visible up front.</h2>
              <p>Review the reservation details before sending the customer into the gateway flow.</p>
            </div>
            <span className="customer-booking-ref">{ref}</span>
          </div>

          <div className="customer-flow-detail-grid">
            {bookingDetails.map((detail) => (
              <div key={detail.label} className="customer-flow-detail-item">
                <span>{detail.icon}{detail.label}</span>
                <strong>{detail.value}</strong>
              </div>
            ))}
          </div>

          {addOnsLabel && (
            <div className="customer-flow-note customer-flow-note-info">
              <strong><FiLayers /> Add-ons already included</strong>
              <p>{addOnsLabel}</p>
            </div>
          )}

          <div className="customer-flow-note customer-flow-note-info">
            <strong><FiMail /> Receipt destination</strong>
            <p>{booking.customerEmail || 'Payment updates stay tied to the account email on this reservation.'}</p>
          </div>

          <div className="customer-flow-list">
            {breakdownRows.map((row) => (
              <div key={row.label} className="customer-flow-row">
                <span>{row.label}</span>
                <strong>{row.value}</strong>
              </div>
            ))}
            <div className="customer-flow-total">
              <span>{isBalanceDue ? 'Balance due' : 'Total payable'}</span>
              <strong className="customer-flow-amount">{isBalanceDue ? balanceDueLabel : amountLabel}</strong>
            </div>
          </div>
        </article>

        <article className="customer-flow-card customer-flow-stack">
          <div className="customer-flow-card-head">
            <div>
              <span className="customer-flow-section-label">Payment action</span>
              <h2>Make the next move without second-guessing the status.</h2>
              <p>The actions below adapt to the current payment state instead of leaving the screen looking identical in every scenario.</p>
            </div>
            <strong className="customer-flow-amount">{isBalanceDue ? balanceDueLabel : amountLabel}</strong>
          </div>

          <div className={`customer-flow-note ${paymentBannerClass}`}>
            <strong>{isBalanceDue ? <FiAlertCircle /> : isSuccess ? <FiCheckCircle /> : isInitiated ? <FiClock /> : isFailed ? <FiAlertCircle /> : <FiCreditCard />}{paymentBannerTitle}</strong>
            <p>{paymentBannerBody}</p>
          </div>

          {isFailed && payment?.failureReason && (
            <div className="customer-flow-note customer-flow-note-danger">
              <strong><FiAlertCircle /> Failure detail</strong>
              <p>{payment.failureReason}</p>
            </div>
          )}

          {!isSuccess && !isInitiated && (
            <label className="customer-flow-select">
              <span className="customer-flow-helper">Payment method</span>
              <select value={paymentMethod} onChange={(event) => setPaymentMethod(event.target.value)}>
                <option value="UPI">UPI</option>
                <option value="CARD">Credit/Debit Card</option>
                <option value="BANK_TRANSFER">Bank Transfer</option>
                <option value="WALLET">Wallet</option>
              </select>
            </label>
          )}

          <div className="customer-flow-fact-grid">
            {actionFacts.map((fact) => (
              <div key={fact.label} className="customer-flow-fact">
                <span>{fact.label}</span>
                <strong>{fact.value}</strong>
              </div>
            ))}
          </div>

          {isSuccess ? (
            <div className="customer-flow-actions customer-flow-actions-left">
              <Link className="btn btn-primary" to={`/booking/${ref}`}>Open Booking Summary <FiArrowRight /></Link>
              <Link className="btn btn-secondary" to="/payments">View Payment History</Link>
            </div>
          ) : isBalanceDue ? (
            <div className="customer-flow-actions customer-flow-actions-left">
              <button className="btn btn-primary" onClick={handleInitiate} disabled={processing}>
                <FiCreditCard /> {processing ? 'Processing...' : `Pay Outstanding Balance ${balanceDueLabel}`}
              </button>
              <Link className="btn btn-secondary" to={`/booking/${ref}`}>View Booking</Link>
            </div>
          ) : isInitiated ? (
            <div className="customer-flow-actions customer-flow-actions-left">
              <button className="btn btn-primary" onClick={loadData} disabled={loading || processing}>
                <FiRefreshCw /> {loading ? 'Refreshing...' : 'Refresh Payment Status'}
              </button>
              <button className="btn btn-secondary" onClick={() => setPayment(null)} disabled={processing}>
                Start Fresh Payment
              </button>
              <Link className="btn btn-secondary" to={`/booking/${ref}`}>Review Booking</Link>
            </div>
          ) : (
            <div className="customer-flow-actions customer-flow-actions-left">
              <button className="btn btn-primary" onClick={handleInitiate} disabled={processing}>
                <FiCreditCard /> {processing ? 'Processing...' : `${isFailed ? 'Retry Payment' : 'Pay'} ${amountLabel}`}
              </button>
              <Link className="btn btn-secondary" to={`/booking/${ref}`}>Review Booking</Link>
            </div>
          )}

          <p className="customer-flow-helper">Payment status refreshes here after the gateway callback is received by the booking system.</p>
        </article>
      </section>

      <section className="customer-flow-grid">
        <article className="customer-flow-card customer-flow-card-span customer-flow-stack">
          <div className="customer-flow-card-head">
            <div>
              <span className="customer-flow-section-label">What happens next</span>
              <h2>Keep the last part of the flow predictable.</h2>
              <p>The screen now makes the immediate next steps explicit instead of hiding them inside a generic payment state message.</p>
            </div>
            <span className="customer-flow-section-label"><FiShield /> One page for payment clarity</span>
          </div>

          <div className="customer-flow-step-list">
            {nextSteps.map((step, index) => (
              <div key={step.title} className="customer-flow-step">
                <span className="customer-flow-step-index">{index + 1}</span>
                <div className="customer-flow-step-copy">
                  <strong>{step.title}</strong>
                  <p>{step.body}</p>
                </div>
              </div>
            ))}
          </div>
        </article>
      </section>
    </div>
  );
}
