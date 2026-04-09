import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import './Auth.css';

export default function ResetPassword() {
  const [form, setForm] = useState({ otp: '', newPassword: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    if (!form.otp.trim()) { setError('OTP code is required'); toast.error('Please enter the OTP code'); return; }
    if (!/^\d{6,8}$/.test(form.otp.trim())) { setError('OTP must be 6–8 digits'); toast.error('OTP must be 6–8 digits'); return; }
    if (form.newPassword.length < 10) { setError('Password must be at least 10 characters'); toast.error('Password must be at least 10 characters'); return; }
    if (form.newPassword !== form.confirmPassword) {
      setError('Passwords do not match');
      toast.error('Passwords do not match');
      return;
    }
    setLoading(true);
    try {
      await authService.verifyOtp({ otp: form.otp, newPassword: form.newPassword });
      toast.success('Password reset successful!');
      navigate('/login');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Failed to reset password. Please try again.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card card" style={{ margin: '0 auto' }}>
        <h1>Reset Password</h1>
        <p className="auth-subtitle">Enter the OTP sent to your email</p>

        {error && <div className="error-message">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="input-group">
            <label>OTP Code</label>
            <input required value={form.otp}
              onChange={(e) => setForm({ ...form, otp: e.target.value })}
              placeholder="8-digit code" maxLength={8} />
          </div>
          <div className="input-group">
            <label>New Password</label>
            <input type="password" required minLength={10} value={form.newPassword}
              onChange={(e) => setForm({ ...form, newPassword: e.target.value })}
              placeholder="Min 10 characters (upper, lower, digit, special)" />
          </div>
          <div className="input-group">
            <label>Confirm Password</label>
            <input type="password" required value={form.confirmPassword}
              onChange={(e) => setForm({ ...form, confirmPassword: e.target.value })}
              placeholder="••••••••" />
          </div>
          <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
            {loading ? 'Resetting...' : 'Reset Password'}
          </button>
        </form>
      </div>
    </div>
  );
}
