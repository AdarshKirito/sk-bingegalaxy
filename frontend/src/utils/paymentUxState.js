// Derive a richer customer-facing payment UX state from the existing
// (booking, payment) data shape. The backend's PaymentStatus enum is kept
// minimal on purpose (PENDING, INITIATED, SUCCESS, FAILED, REFUNDED,
// PARTIALLY_REFUNDED, PARTIALLY_PAID, DISPUTED) — adding "in-flight" enum values
// would force DB migrations, break the cross-machine invariant and ripple
// through ~300 call sites for no real gain. Production gateways (Stripe,
// Square, Adyen) follow the same pattern: small persistent enum, rich
// derived UI states.
//
// Returned `kind` values:
//   AWAITING_GATEWAY        Razorpay session created, customer hasn't paid yet
//   PROCESSING              Gateway acknowledged, awaiting our callback / capture
//   AWAITING_CONFIRMATION   Payment captured, booking still PENDING (Kafka in flight)
//   UNDER_REVIEW            Long-running INITIATED looks like a manual / fraud hold
//   FAILED_RETRYABLE        Last attempt failed; customer can retry
//   EXPIRED_SLOT_RELEASED   Booking terminal (CANCELLED/EXPIRED/NO_SHOW) but payment never settled
//   BALANCE_DUE             Already paid something, more to collect (price up / partial refund)
//   SUCCESS                 Fully settled, no balance
//   REFUNDED                Fully refunded
//   PARTIALLY_REFUNDED      Partially refunded (no balance due)
//   DISPUTED                Chargeback / bank dispute raised; funds held pending review
//   IDLE                    Fresh booking, no payment activity yet
//
// `severity` is one of: 'success' | 'info' | 'warning' | 'danger'.

const MS = { sec: 1000, min: 60_000 };

// How long an INITIATED payment may sit before we surface "under review"
// rather than "awaiting gateway" copy. Razorpay 3DS / bank flows almost
// always finish in < 2 minutes; longer than 5 means something is stuck.
const REVIEW_THRESHOLD_MS = 5 * MS.min;

// Beyond this, an INITIATED payment paired with a closed booking is
// almost certainly an abandoned session whose slot has already been
// auto-released by the slot-hold expiry scheduler.
const STALE_THRESHOLD_MS = 15 * MS.min;

function ageMs(payment) {
  if (!payment) return 0;
  const created = payment.createdAt || payment.initiatedAt || payment.updatedAt;
  if (!created) return 0;
  const t = new Date(created).getTime();
  if (!Number.isFinite(t)) return 0;
  const delta = Date.now() - t;
  return delta > 0 ? delta : 0;
}

export function derivePaymentUx({ booking, payment, hasOutstandingBalance }) {
  const paymentState = String(payment?.status || booking?.paymentStatus || 'PENDING').toUpperCase();
  const bookingState = String(booking?.status || 'PENDING').toUpperCase();
  const isBookingClosed = bookingState === 'CANCELLED'
    || bookingState === 'NO_SHOW'
    || bookingState === 'EXPIRED';

  // Highest priority: outstanding balance dominates regardless of status.
  if (hasOutstandingBalance && (paymentState === 'SUCCESS'
      || paymentState === 'PARTIALLY_REFUNDED'
      || paymentState === 'PARTIALLY_PAID')) {
    return {
      kind: 'BALANCE_DUE',
      severity: 'warning',
      title: 'Outstanding balance to settle',
      body: 'Part of this booking has been paid, but a balance remains. Please pay the remaining amount to fully settle the reservation.',
      canRetry: true,
      stage: 'paying',
    };
  }

  // Booking already closed but payment never settled — slot was released.
  if (isBookingClosed
      && (paymentState === 'INITIATED' || paymentState === 'PENDING' || paymentState === 'FAILED')) {
    return {
      kind: 'EXPIRED_SLOT_RELEASED',
      severity: 'danger',
      title: 'This booking has expired and the slot was released',
      body: bookingState === 'CANCELLED'
        ? 'The booking was cancelled before payment completed, so the slot is no longer reserved. If you were charged, contact support — any captured amount will be refunded automatically.'
        : 'Your slot reservation expired before payment completed. The slot has been released for other customers. Please start a new booking to continue.',
      canRetry: false,
      stage: 'closed',
    };
  }

  // Payment captured by gateway, but booking-service has not yet flipped
  // PENDING -> CONFIRMED. Caused by Kafka lag / consumer down. Customer
  // should NOT see "all done" until the booking actually flips.
  if (paymentState === 'SUCCESS' && bookingState === 'PENDING') {
    return {
      kind: 'AWAITING_CONFIRMATION',
      severity: 'info',
      title: 'Payment received — finalising your booking',
      body: 'We have received your payment. Your booking will be confirmed within a minute. You can stay on this page or come back later — your slot is safe.',
      canRetry: false,
      stage: 'confirming',
    };
  }

  if (paymentState === 'INITIATED') {
    const ms = ageMs(payment);
    if (ms > STALE_THRESHOLD_MS) {
      return {
        kind: 'EXPIRED_SLOT_RELEASED',
        severity: 'danger',
        title: 'Payment session timed out',
        body: 'Your payment session has been open too long and the slot may have been released. Please start a new payment attempt or pick another slot.',
        canRetry: true,
        stage: 'closed',
      };
    }
    if (ms > REVIEW_THRESHOLD_MS) {
      return {
        kind: 'UNDER_REVIEW',
        severity: 'warning',
        title: 'Payment is being verified',
        body: 'Your bank or payment provider is still processing this payment. This can happen with 3D-Secure, manual review or slow networks. No action is needed — we will update this page as soon as the result is in. If you cancelled the gateway window by mistake, you can retry.',
        canRetry: true,
        stage: 'processing',
      };
    }
    return {
      kind: 'AWAITING_GATEWAY',
      severity: 'info',
      title: 'Awaiting payment',
      body: 'Complete the payment in the provider window. If you closed it by mistake, you can retry — only the latest successful attempt is counted.',
      canRetry: true,
      stage: 'paying',
    };
  }

  if (paymentState === 'FAILED') {
    return {
      kind: 'FAILED_RETRYABLE',
      severity: 'danger',
      title: 'The last payment attempt did not complete',
      body: payment?.failureReason
        ? `Reason: ${payment.failureReason}. You can retry with the same method or switch to another one.`
        : 'You can retry with the same method or switch to another one for a cleaner attempt.',
      canRetry: !isBookingClosed,
      stage: 'paying',
    };
  }

  if (paymentState === 'REFUNDED') {
    return {
      kind: 'REFUNDED',
      severity: 'info',
      title: 'Payment refunded',
      body: 'This booking has been fully refunded. The amount may take 3-5 business days to appear in your account, depending on your bank.',
      canRetry: false,
      stage: 'closed',
    };
  }

  if (paymentState === 'PARTIALLY_REFUNDED') {
    return {
      kind: 'PARTIALLY_REFUNDED',
      severity: 'info',
      title: 'Partial refund processed',
      body: 'A portion of this payment has been refunded. The remaining amount remains captured against this booking.',
      canRetry: false,
      stage: 'closed',
    };
  }

  // Chargeback / bank dispute raised (Razorpay dispute.created). The money is
  // held by the gateway pending resolution — the customer must NOT be invited to
  // pay again (that's exactly what the IDLE fallback would wrongly do).
  if (paymentState === 'DISPUTED') {
    return {
      kind: 'DISPUTED',
      severity: 'warning',
      title: 'A payment dispute is being reviewed',
      body: 'A chargeback or dispute has been raised with your bank for this payment. The amount is on hold with the payment provider while it is investigated — please do not pay again. We will update this booking once the dispute is resolved. If you raised this in error, contact support.',
      canRetry: false,
      stage: 'processing',
    };
  }

  if (paymentState === 'SUCCESS') {
    return {
      kind: 'SUCCESS',
      severity: 'success',
      title: 'Payment captured for this reservation',
      body: 'You can move directly to the booking confirmation page or review the full payment trail from the payments hub.',
      canRetry: false,
      stage: 'done',
    };
  }

  // PENDING / unknown — fresh booking, payment hasn't been attempted yet.
  return {
    kind: 'IDLE',
    severity: 'info',
    title: 'Ready to pay',
    body: 'Choose a method and continue when ready. This screen keeps the booking details, amount and transaction status together so you do not have to cross-check another page before paying.',
    canRetry: true,
    stage: 'paying',
  };
}

export const PAYMENT_UX_BANNER_CLASS = {
  success: 'customer-flow-note-success',
  info: 'customer-flow-note-info',
  warning: 'customer-flow-note-warning',
  danger: 'customer-flow-note-danger',
};
