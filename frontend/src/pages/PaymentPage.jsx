import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { bookingService, paymentService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiCreditCard } from 'react-icons/fi';

export default function PaymentPage() {
  const { ref } = useParams();
  const navigate = useNavigate();
  const [booking, setBooking] = useState(null);
  const [paymentMethod, setPaymentMethod] = useState('UPI');
  const [payment, setPayment] = useState(null);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState(false);

  useEffect(() => {
    const loadData = async () => {
      try {
        const bookRes = await bookingService.getByRef(ref);
        setBooking(bookRes.data.data);

        // Check for existing payment — prevents duplicate initiation
        try {
          const payRes = await paymentService.getByBooking(ref);
          const payments = payRes.data.data || [];
          // Priority: terminal success states first, then in-flight INITIATED
          const existing =
            payments.find(p => p.status === 'SUCCESS' || p.status === 'REFUNDED' || p.status === 'PARTIALLY_REFUNDED') ||
            payments.find(p => p.status === 'INITIATED');
          if (existing) setPayment(existing);
          // FAILED: fall through — show fresh payment form
        } catch { /* no payment record yet */ }
      } catch {
        toast.error('Booking not found');
      } finally {
        setLoading(false);
      }
    };
    loadData();
  }, [ref]);

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
      toast.info('Payment initiated — simulating...');

      // Auto-simulate (dev mode)
      setTimeout(async () => {
        try {
          const simRes = await paymentService.simulate(payData.transactionId);
          setPayment(simRes.data.data);
          toast.success('Payment successful!');
          setTimeout(() => navigate(`/booking/${ref}`), 1500);
        } catch (err) {
          toast.error(err.response?.data?.message || 'Payment simulation failed');
        }
        setProcessing(false);
      }, 2000);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Payment failed');
      setProcessing(false);
    }
  };

  const handleSimulate = async () => {
    if (!payment?.transactionId) return;
    setProcessing(true);
    try {
      const simRes = await paymentService.simulate(payment.transactionId);
      setPayment(simRes.data.data);
      toast.success('Payment successful!');
      setTimeout(() => navigate(`/booking/${ref}`), 1500);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Simulation failed');
    }
    setProcessing(false);
  };

  if (loading) return <div className="loading"><div className="spinner"></div></div>;
  if (!booking) return <div className="container"><p>Booking not found</p></div>;

  const isSuccess = payment?.status === 'SUCCESS' || payment?.status === 'REFUNDED' || payment?.status === 'PARTIALLY_REFUNDED';
  const isInitiated = payment?.status === 'INITIATED';
  const isFailed = payment?.status === 'FAILED';

  return (
    <div className="container" style={{ maxWidth: '550px', margin: '0 auto' }}>
      <div className="page-header" style={{ textAlign: 'center' }}>
        <FiCreditCard style={{ fontSize: '2.5rem', color: 'var(--primary)', marginBottom: '0.5rem' }} />
        <h1>Payment</h1>
        <p>Booking: {ref}</p>
      </div>

      {/* Booking summary */}
      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
          <span style={{ color: 'var(--text-secondary)' }}>Event</span>
          <span>{booking.eventType?.name ?? booking.eventType}</span>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
          <span style={{ color: 'var(--text-secondary)' }}>Date</span>
          <span>{booking.bookingDate} at {booking.startTime}</span>
        </div>
        <hr style={{ borderColor: 'var(--border)', margin: '0.75rem 0' }} />
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '1.3rem', fontWeight: 700 }}>
          <span>Total</span>
          <span style={{ color: 'var(--primary-light)' }}>₹{booking.totalAmount?.toLocaleString()}</span>
        </div>
      </div>

      {/* ── Payment status states ── */}
      {isSuccess ? (
        <div className="card" style={{ textAlign: 'center', padding: '1.5rem' }}>
          <div style={{ fontSize: '2.5rem', marginBottom: '0.5rem' }}>✓</div>
          <p style={{ color: 'var(--success, #00b894)', fontWeight: 600, fontSize: '1.15rem', marginBottom: '0.5rem' }}>
            Payment {payment.status === 'SUCCESS' ? 'Successful' : payment.status.replace(/_/g, ' ')}
          </p>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', marginBottom: '1rem' }}>
            Transaction: {payment.transactionId}
          </p>
          <button className="btn btn-primary" onClick={() => navigate(`/booking/${ref}`)}>
            View Booking
          </button>
        </div>

      ) : isInitiated ? (
        <div className="card" style={{ textAlign: 'center', padding: '1.5rem' }}>
          <div style={{ fontSize: '1.5rem', marginBottom: '0.5rem' }}>⏳</div>
          <p style={{ color: 'var(--warning, #fdcb6e)', fontWeight: 600, fontSize: '1.05rem', marginBottom: '0.5rem' }}>
            Payment Already Initiated
          </p>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', marginBottom: '1rem' }}>
            Transaction: {payment.transactionId}
          </p>
          <button className="btn btn-primary" style={{ width: '100%', marginBottom: '0.5rem' }}
            onClick={handleSimulate} disabled={processing}>
            {processing ? 'Processing...' : 'Complete Payment (Simulate)'}
          </button>
          <button className="btn btn-secondary btn-sm" onClick={() => setPayment(null)}>
            Start Over with New Payment
          </button>
        </div>

      ) : isFailed ? (
        <div>
          <div className="card" style={{ textAlign: 'center', padding: '1rem', marginBottom: '1.5rem', borderColor: 'var(--danger, #e74c3c)' }}>
            <div style={{ fontSize: '1.5rem', marginBottom: '0.25rem' }}>❌</div>
            <p style={{ color: 'var(--danger, #e74c3c)', fontWeight: 600, marginBottom: '0.25rem' }}>Previous Payment Failed</p>
            {payment.failureReason && (
              <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>{payment.failureReason}</p>
            )}
          </div>
          <p style={{ color: 'var(--text-secondary)', marginBottom: '1rem', fontSize: '0.9rem' }}>Try a new payment:</p>
          <div className="input-group">
            <label>Payment Method</label>
            <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)}>
              <option value="UPI">UPI</option>
              <option value="CARD">Credit/Debit Card</option>
              <option value="BANK_TRANSFER">Bank Transfer</option>
              <option value="WALLET">Wallet</option>
            </select>
          </div>
          <button className="btn btn-primary" style={{ width: '100%', marginTop: '1rem' }}
            onClick={handleInitiate} disabled={processing}>
            {processing ? 'Processing...' : `Retry Payment ₹${booking.totalAmount?.toLocaleString()}`}
          </button>
        </div>

      ) : (
        /* Fresh payment form */
        <>
          <div className="input-group">
            <label>Payment Method</label>
            <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)}>
              <option value="UPI">UPI</option>
              <option value="CARD">Credit/Debit Card</option>
              <option value="BANK_TRANSFER">Bank Transfer</option>
              <option value="WALLET">Wallet</option>
            </select>
          </div>

          <button className="btn btn-primary" style={{ width: '100%', marginTop: '1rem' }}
            onClick={handleInitiate} disabled={processing}>
            {processing ? 'Processing...' : `Pay ₹${booking.totalAmount?.toLocaleString()}`}
          </button>

          <p style={{ textAlign: 'center', marginTop: '1rem', color: 'var(--text-muted)', fontSize: '0.8rem' }}>
            In development mode, payment will be auto-simulated.
          </p>
        </>
      )}
    </div>
  );
}
