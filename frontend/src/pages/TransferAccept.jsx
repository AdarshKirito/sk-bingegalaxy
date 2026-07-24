import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
import { FiCheckCircle, FiClock, FiHash, FiSend, FiUser, FiXCircle } from 'react-icons/fi';
import './CustomerHub.css';

/**
 * Recipient-side landing page for the 2-phase booking transfer flow.
 *
 * The recipient arrives here from the magic link in the transfer email
 * (/transfers/:token). The page is intentionally public — the 256-bit token
 * IS the bearer credential; no account or sign-in is required to accept.
 */

// Transfer timestamps are UTC LocalDateTime strings without a zone suffix.
const formatUtc = (value) => {
  if (!value) return '';
  const iso = value.endsWith('Z') ? value : `${value}Z`;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

const STATUS_NOTICES = {
  ACCEPTED: { icon: FiCheckCircle, title: 'Already accepted', text: 'This transfer has already been accepted. The booking now belongs to the recipient.' },
  DECLINED: { icon: FiXCircle, title: 'Already declined', text: 'This transfer offer was declined. The booking stays with the original owner.' },
  REVOKED: { icon: FiXCircle, title: 'Offer revoked', text: 'The sender withdrew this transfer offer. If this is unexpected, please contact them directly.' },
  EXPIRED: { icon: FiClock, title: 'Offer expired', text: 'This transfer offer was not answered in time and has expired. Ask the sender to send a new one if you still want the booking.' },
};

export default function TransferAccept() {
  const { token } = useParams();
  const [transfer, setTransfer] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [declineOpen, setDeclineOpen] = useState(false);
  const [declineReason, setDeclineReason] = useState('');
  const [outcome, setOutcome] = useState(null); // 'accepted' | 'declined'

  useEffect(() => {
    let active = true;
    bookingService.previewTransferByToken(token)
      .then((res) => { if (active) setTransfer(res.data?.data || null); })
      .catch((err) => {
        if (active) {
          setError(err.response?.status === 404
            ? 'This transfer link is invalid or no longer exists.'
            : (err.userMessage || 'Failed to load the transfer offer. Please try again.'));
        }
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token]);

  // If an action fails because the offer changed state server-side (expired,
  // revoked, already handled), re-sync so the page shows the real status
  // instead of leaving stale Accept/Decline buttons up.
  const resyncTransfer = async () => {
    try {
      const res = await bookingService.previewTransferByToken(token);
      setTransfer(res.data?.data || null);
    } catch { /* keep the current view; the toast already explained the failure */ }
  };

  const handleAccept = async () => {
    setActionLoading(true);
    try {
      const res = await bookingService.acceptTransferByToken(token);
      setTransfer(res.data?.data || transfer);
      setOutcome('accepted');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to accept the transfer.');
      await resyncTransfer();
    } finally {
      setActionLoading(false);
    }
  };

  const handleDecline = async () => {
    setActionLoading(true);
    try {
      const res = await bookingService.declineTransferByToken(token, declineReason.trim() || null);
      setTransfer(res.data?.data || transfer);
      setOutcome('declined');
      setDeclineOpen(false);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to decline the transfer.');
      await resyncTransfer();
    } finally {
      setActionLoading(false);
    }
  };

  const shell = (content) => (
    <div className="container customer-flow-shell customer-flow-shell-narrow">
      <SEO title="Booking Transfer" description="Accept or decline a booking transfer offer." />
      {content}
    </div>
  );

  if (loading) return shell(<div className="loading"><div className="spinner"></div></div>);

  if (error) {
    return shell(
      <div className="customer-flow-card customer-flow-empty">
        <h2><FiXCircle /> {error}</h2>
        <p style={{ color: 'var(--text-muted)' }}>
          If you followed a link from an email, double-check it wasn't truncated, or ask the sender for a new offer.
        </p>
        <Link to="/" className="btn btn-secondary">Go to homepage</Link>
      </div>
    );
  }

  if (outcome === 'accepted') {
    return shell(
      <div className="customer-flow-card customer-flow-empty">
        <h2><FiCheckCircle /> Transfer accepted</h2>
        <p>
          Booking <strong>{transfer?.bookingRef}</strong> is now yours. A confirmation email is on its way
          to <strong>{transfer?.toEmail}</strong>.
        </p>
        <p style={{ color: 'var(--text-muted)' }}>
          Sign in (or create an account with that email) to view and manage the booking.
        </p>
        <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'center' }}>
          <Link to="/login" className="btn btn-primary">Sign in</Link>
          <Link to="/register" className="btn btn-secondary">Create account</Link>
        </div>
      </div>
    );
  }

  if (outcome === 'declined') {
    return shell(
      <div className="customer-flow-card customer-flow-empty">
        <h2><FiXCircle /> Transfer declined</h2>
        <p>You declined the offer for booking <strong>{transfer?.bookingRef}</strong>. The booking stays with {transfer?.fromCustomerName || 'the original owner'}.</p>
        <Link to="/" className="btn btn-secondary">Go to homepage</Link>
      </div>
    );
  }

  const notice = STATUS_NOTICES[transfer?.status];
  if (notice) {
    const Icon = notice.icon;
    return shell(
      <div className="customer-flow-card customer-flow-empty">
        <h2><Icon /> {notice.title}</h2>
        <p>{notice.text}</p>
        <Link to="/" className="btn btn-secondary">Go to homepage</Link>
      </div>
    );
  }

  // PENDING — the actionable state.
  return shell(
    <div className="customer-flow-card customer-flow-stack">
      <div className="customer-flow-card-head">
        <div>
          <span className="customer-flow-section-label">Booking transfer</span>
          <h2><FiSend /> {transfer?.fromCustomerName || 'A customer'} wants to transfer a booking to you</h2>
        </div>
      </div>
      <div className="customer-flow-list">
        <div className="customer-flow-row">
          <span><FiHash /> Booking reference</span>
          <strong>{transfer?.bookingRef}</strong>
        </div>
        <div className="customer-flow-row">
          <span><FiUser /> From</span>
          <strong>{transfer?.fromCustomerName}{transfer?.fromCustomerEmail ? ` (${transfer.fromCustomerEmail})` : ''}</strong>
        </div>
        <div className="customer-flow-row">
          <span><FiUser /> To</span>
          <strong>{transfer?.toName}{transfer?.toEmail ? ` (${transfer.toEmail})` : ''}</strong>
        </div>
        {transfer?.expiresAt && (
          <div className="customer-flow-row">
            <span><FiClock /> Offer expires</span>
            <strong>{formatUtc(transfer.expiresAt)}</strong>
          </div>
        )}
      </div>
      <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
        Accepting makes this booking yours; the sender keeps it if you decline.
        If you don't recognise the sender, you can safely decline or ignore this offer.
      </p>
      {declineOpen ? (
        <>
          <label style={{ display: 'block' }}>
            <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>Reason (optional)</span>
            <textarea className="form-control" rows={3} maxLength={500}
              placeholder="Let the sender know why (optional)"
              value={declineReason}
              onChange={(e) => setDeclineReason(e.target.value)} />
          </label>
          <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end' }}>
            <button className="btn btn-secondary btn-sm" disabled={actionLoading} onClick={() => setDeclineOpen(false)}>Back</button>
            <button className="btn btn-danger btn-sm" disabled={actionLoading} onClick={handleDecline}>
              {actionLoading ? 'Declining...' : 'Confirm Decline'}
            </button>
          </div>
        </>
      ) : (
        <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end' }}>
          <button className="btn btn-secondary" disabled={actionLoading} onClick={() => setDeclineOpen(true)}>Decline</button>
          <button className="btn btn-primary" disabled={actionLoading} onClick={handleAccept}>
            {actionLoading ? 'Accepting...' : 'Accept Transfer'}
          </button>
        </div>
      )}
    </div>
  );
}
