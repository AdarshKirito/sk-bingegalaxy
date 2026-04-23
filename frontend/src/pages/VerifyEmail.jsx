import { useEffect, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import SEO from '../components/SEO';

/**
 * Verify the current user's email address. Supports both flows:
 *  - ?token=xxx  → immediate link-based verification
 *  - otherwise   → OTP form; user types the 6-digit code emailed to them
 */
export default function VerifyEmail() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [email, setEmail] = useState(params.get('email') || '');
  const [otp, setOtp] = useState('');
  const [busy, setBusy] = useState(false);
  const [verified, setVerified] = useState(false);

  useEffect(() => {
    const token = params.get('token');
    if (!token) return;
    (async () => {
      setBusy(true);
      try {
        await authService.verifyEmail({ token });
        setVerified(true);
        toast.success('Email verified!');
      } catch (err) {
        toast.error(err.response?.data?.message || 'Verification link is invalid or expired');
      } finally { setBusy(false); }
    })();
  }, [params]);

  const submit = async (e) => {
    e.preventDefault();
    if (!email.trim() || !otp.trim()) return toast.error('Email and code are required');
    setBusy(true);
    try {
      await authService.verifyEmail({ email: email.trim(), otp: otp.trim() });
      setVerified(true);
      toast.success('Email verified!');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Invalid or expired code');
    } finally { setBusy(false); }
  };

  const resend = async () => {
    try {
      await authService.resendVerification();
      toast.success('Verification email sent');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to resend');
    }
  };

  return (
    <div className="page-shell" style={{ maxWidth: 520, margin: '0 auto', padding: 24 }}>
      <SEO title="Verify email" description="Confirm your email address." />
      <h1>Verify your email</h1>

      {verified ? (
        <div>
          <p style={{ color: 'green' }}>✅ Your email has been verified.</p>
          <button className="btn btn-primary" onClick={() => navigate('/')}>Continue</button>
        </div>
      ) : (
        <>
          <p>Enter the 6-digit code we emailed to you.</p>
          <form onSubmit={submit}>
            <div className="input-group">
              <label>Email</label>
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <div className="input-group">
              <label>Code</label>
              <input
                type="text"
                inputMode="numeric"
                maxLength={6}
                value={otp}
                onChange={(e) => setOtp(e.target.value)}
                placeholder="123456"
                required
              />
            </div>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Verifying…' : 'Verify'}
            </button>
          </form>
          <p style={{ marginTop: 16 }}>
            Didn't receive it?{' '}
            <button type="button" className="link-button" onClick={resend}>Resend</button>
          </p>
        </>
      )}
    </div>
  );
}
