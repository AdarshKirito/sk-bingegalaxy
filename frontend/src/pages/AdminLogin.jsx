import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import { FiShield, FiBarChart2, FiClock, FiEye, FiEyeOff } from 'react-icons/fi';
import './Auth.css';

export default function AdminLogin() {
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [showPw, setShowPw] = useState(false);
  const { adminLogin } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    const trimmedEmail = form.email.trim();
    if (!trimmedEmail) { setError('Email is required'); toast.error('Email is required'); return; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) { setError('Please enter a valid email address'); toast.error('Please enter a valid email address'); return; }
    if (!form.password) { setError('Password is required'); toast.error('Password is required'); return; }
    setLoading(true);
    try {
      await adminLogin({ email: trimmedEmail, password: form.password });
      toast.success('Welcome, Admin!');
      navigate('/admin/platform');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Admin login failed. Please check your credentials.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-shell auth-shell-login">
        <section className="auth-showcase auth-showcase-login">
          <span className="auth-kicker">Admin access</span>
          <h1>SK Binge Galaxy Management Console</h1>
          <p className="auth-lead">
            Sign in to manage bookings, view revenue reports, handle check-ins, and oversee day-to-day operations.
          </p>

          <div className="auth-showcase-card">
            <span className="auth-card-label">Admin tools</span>
            <ul className="auth-benefit-list">
              <li><FiShield /> Secure role-based access for administrators and super admins.</li>
              <li><FiBarChart2 /> Real-time dashboard with revenue, bookings, and audit reports.</li>
              <li><FiClock /> Operational day management with check-in and checkout controls.</li>
            </ul>
          </div>

          <div className="auth-stat-row" aria-hidden="true">
            <article>
              <strong>Full control</strong>
              <span>bookings &amp; payments</span>
            </article>
            <article>
              <strong>Live</strong>
              <span>operational dashboard</span>
            </article>
          </div>
        </section>

        <div className="auth-card-wrap">
          <div className="auth-card card">
            <span className="auth-card-eyebrow">Admin sign in</span>
            <h2>Admin Login</h2>
            <p className="auth-subtitle">SK Binge Galaxy Administration</p>

            {error && <div className="error-message">{error}</div>}

            <form onSubmit={handleSubmit}>
              <div className="input-group">
                <label>Email</label>
                <input type="email" required value={form.email}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                  placeholder="admin@skbingegalaxy.com" autoFocus />
              </div>
              <div className="input-group">
                <label>Password</label>
                <div className="input-password-wrap">
                  <input type={showPw ? 'text' : 'password'} required value={form.password}
                    onChange={(e) => setForm({ ...form, password: e.target.value })}
                    placeholder="••••••••" />
                  <button type="button" className="pw-toggle" onClick={() => setShowPw(v => !v)} aria-label={showPw ? 'Hide password' : 'Show password'} tabIndex={-1}>
                    {showPw ? <FiEyeOff /> : <FiEye />}
                  </button>
                </div>
              </div>
              <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
                {loading ? <><span className="btn-spinner" /> Signing in...</> : 'Sign In as Admin'}
              </button>
            </form>

            <div className="auth-links">
              <span>Need admin access? Ask a super admin to create your account.</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
