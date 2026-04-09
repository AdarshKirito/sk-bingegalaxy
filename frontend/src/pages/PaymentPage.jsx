import { useState, useEffect } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { bookingService, paymentService } from '../services/endpoints';
import { toast } from 'react-toastify';
import SEO from '../components/SEO';
import { FiAlertCircle, FiCheckCircle, FiClock, FiCreditCard, FiRefreshCw } from 'react-icons/fi';
import './CustomerHub.css';

export default function PaymentPage() {
  const { ref } = useParams();
  const navigate = useNavigate();
  const [booking, setBooking] = useState(null);
  const [paymentMethod, setPaymentMethod] = useState('UPI');
  const [payment, setPayment] = useState(null);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState(false);

  const loadData = async () => {
    setLoading(true);
    try {
      const bookRes = await bookingService.getByRef(ref);
      setBooking(bookRes.data.data);

      try {
        const payRes = await paymentService.getByBooking(ref);
        const payments = payRes.data.data || [];
        const existing =
          payments.find(p => p.status === 'SUCCESS' || p.status === 'REFUNDED' || p.status === 'PARTIALLY_REFUNDED') ||
          payments.find(p => p.status === 'INITIATED');
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
    setProcessing(true);
    try {
      const res = await paymentService.initiate({
        bookingRef: ref,
        amount: booking.totalAmount,
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
            try {
              await paymentService.callback({
                gatewayOrderId: response.razorpay_order_id,
                gatewayPaymentId: response.razorpay_payment_id,
                gatewaySignature: response.razorpay_signature,
                status: 'success',
              });
              toast.success('Payment successful!');
              await loadData();
            } catch (err) {
              toast.error(err.response?.data?.message || 'Payment verification failed. Contact support.');
            } finally {
              setProcessing(false);
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
      toast.error(err.response?.data?.message || 'Payment failed');
      setProcessing(false);
    }
  };

  if (loading) return <div className="loading"><div className="spinner"></div></div>;
  if (!booking) return <div className="container customer-flow-shell customer-flow-shell-narrow"><div className="customer-flow-card customer-flow-empty"><h2>Booking not found</h2></div></div>;

  const isSuccess = payment?.status === 'SUCCESS' || payment?.status === 'REFUNDED' || payment?.status === 'PARTIALLY_REFUNDED';
  const isInitiated = payment?.status === 'INITIATED';
  const isFailed = payment?.status === 'FAILED';
  const amountLabel = `₹${booking.totalAmount?.toLocaleString()}`;

  return (
    <div className="container customer-flow-shell customer-flow-shell-narrow">
      <SEO title="Payment" description="Review booking amount, retry pending payments, and continue payment flow for your booking." />

      <section className="customer-flow-hero">
        <div className="customer-flow-copy">
          <span className="customer-flow-kicker">Payment flow</span>
          <h1>Review the amount, choose a method, and close the booking cleanly.</h1>
          <p>Use this screen to initiate a payment, refresh an in-progress transaction, or confirm that the booking has already been paid.</p>
          <div className="customer-flow-inline">
            <Link to="/payments" className="btn btn-secondary btn-sm">Payment History</Link>
            <Link to="/my-bookings" className="btn btn-secondary btn-sm">Booking Timeline</Link>
          </div>
        </div>

        <aside className="customer-flow-summary">
          <span className="customer-flow-kicker">Booking summary</span>
          <h2>{booking.eventType?.name ?? booking.eventType}</h2>
          <p>{booking.bookingDate} at {booking.startTime}</p>
          <div className="customer-flow-badges">
            <span className="badge badge-info">Ref {ref}</span>
            <span className={`badge ${isSuccess ? 'badge-success' : isInitiated ? 'badge-warning' : isFailed ? 'badge-danger' : 'badge-info'}`}>
              {payment?.status || 'Awaiting payment'}
            </span>
          </div>
          <strong>{amountLabel}</strong>
        </aside>
      </section>

      <section className="customer-flow-card">
        <div className="customer-flow-list">
          <div className="customer-flow-row">
            <span>Event</span>
            <strong>{booking.eventType?.name ?? booking.eventType}</strong>
          </div>
          <div className="customer-flow-row">
            <span>Date</span>
            <strong>{booking.bookingDate} at {booking.startTime}</strong>
          </div>
          <div className="customer-flow-total">
            <span>Total payable</span>
            <strong className="customer-flow-amount">{amountLabel}</strong>
          </div>
        </div>
      </section>

      {isSuccess ? (
        <section className="customer-flow-card customer-flow-empty">
          <span className="customer-flow-icon"><FiCheckCircle /></span>
          <h2>Payment {payment.status === 'SUCCESS' ? 'successful' : payment.status.replace(/_/g, ' ').toLowerCase()}</h2>
          <p>Transaction: {payment.transactionId}</p>
          <div className="customer-flow-actions">
            <button className="btn btn-primary" onClick={() => navigate(`/booking/${ref}`)}>View Booking</button>
          </div>
        </section>
      ) : isInitiated ? (
        <section className="customer-flow-card customer-flow-empty">
          <span className="customer-flow-icon"><FiClock /></span>
          <h2>Payment already initiated</h2>
          <p>Transaction: {payment.transactionId}</p>
          <div className="customer-flow-actions">
            <button className="btn btn-primary" onClick={loadData} disabled={loading || processing}>
              <FiRefreshCw /> {loading ? 'Refreshing...' : 'Refresh Payment Status'}
            </button>
            <button className="btn btn-secondary btn-sm" onClick={() => setPayment(null)} disabled={processing}>
              Start Over with New Payment
            </button>
          </div>
        </section>
      ) : (
        <section className="customer-flow-card customer-flow-stack">
          {isFailed && (
            <div className="customer-flow-alert customer-flow-alert-danger">
              <strong><FiAlertCircle /> Previous payment failed</strong>
              <p>{payment.failureReason || 'Please try a new payment method or start a fresh transaction.'}</p>
            </div>
          )}

          <label className="customer-flow-select">
            <span className="customer-flow-helper">Payment method</span>
            <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)}>
              <option value="UPI">UPI</option>
              <option value="CARD">Credit/Debit Card</option>
              <option value="BANK_TRANSFER">Bank Transfer</option>
              <option value="WALLET">Wallet</option>
            </select>
          </label>

          <button className="btn btn-primary" onClick={handleInitiate} disabled={processing}>
            <FiCreditCard /> {processing ? 'Processing...' : `${isFailed ? 'Retry Payment' : 'Pay'} ${amountLabel}`}
          </button>

          <p className="customer-flow-helper">Payment status updates here after the gateway callback is received.</p>
        </section>
      )}
    </div>
  );
}
