import { useState } from 'react';
import { Link } from 'react-router-dom';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import './Auth.css';

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await authService.forgotPassword({ email });
      setSent(true);
      toast.success('Reset instructions sent!');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Failed to send reset email. Please try again.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card card" style={{ margin: '0 auto' }}>
        <h1>Forgot Password</h1>
        <p className="auth-subtitle">We'll send you reset instructions</p>

        {error && <div className="error-message">{error}</div>}

        {sent ? (
          <div style={{ textAlign: 'center', padding: '1rem 0' }}>
            <p style={{ color: 'var(--success)', marginBottom: '1rem' }}>✓ Reset instructions sent to {email}</p>
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
              Check your email for a reset link or OTP.
            </p>
            <Link to="/reset-password" className="btn btn-primary" style={{ marginTop: '1rem' }}>
              Enter OTP
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="input-group">
              <label>Email Address</label>
              <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com" />
            </div>
            <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
              {loading ? 'Sending...' : 'Send Reset Link'}
            </button>
          </form>
        )}

        <div className="auth-links">
          <Link to="/login">Back to login</Link>
        </div>
      </div>
    </div>
  );
}
