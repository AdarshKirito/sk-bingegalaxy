import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import { FiCalendar, FiEye, FiEyeOff, FiGift, FiUsers } from 'react-icons/fi';
import SEO from '../components/SEO';
import './Auth.css';

export default function Register() {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [touched, setTouched] = useState({});
  const [showPw, setShowPw] = useState(false);
  const [showCpw, setShowCpw] = useState(false);
  const { register } = useAuth();

  const fieldErrors = {
    firstName: touched.firstName && !form.firstName.trim() ? 'First name is required' : '',
    email: touched.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) ? 'Enter a valid email' : '',
    phone: touched.phone && !/^[6-9]\d{9}$/.test(form.phone.replace(/\D/g, '').slice(-10)) ? 'Enter a valid 10-digit phone' : '',
    password: touched.password && form.password.length < 10 ? 'Min 10 characters' : '',
    confirmPassword: touched.confirmPassword && form.password !== form.confirmPassword ? 'Passwords do not match' : '',
  };

  const handleBlur = (field) => () => setTouched(t => ({ ...t, [field]: true }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setTouched({ firstName: true, email: true, phone: true, password: true, confirmPassword: true });
    if (Object.values(fieldErrors).some(Boolean)) return;
    if (!form.firstName.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) || form.password.length < 10 || form.password !== form.confirmPassword) return;
    setError('');
    setLoading(true);
    try {
      const { confirmPassword, ...data } = form;
      await register(data);
      toast.success('Account created!');
      // PublicOnlyRoute redirects to /binges via resolveAuthenticatedLanding
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Registration failed. Please try again.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  return (
    <div className="auth-page auth-page-register">
      <SEO title="Register" description="Create your SK Binge Galaxy account and start planning private-screen experiences." />

      <div className="auth-shell auth-shell-register">
        <section className="auth-showcase auth-showcase-register">
          <span className="auth-kicker">Create your account</span>
          <h1>Start the plan before the celebration starts asking questions.</h1>
          <p className="auth-lead">
            Sign up once to manage bookings, preserve your preferences, and move from discovery to confirmation with a cleaner flow.
          </p>

          <div className="auth-showcase-grid">
            <article className="auth-mini-card">
              <FiGift />
              <strong>Celebration ready</strong>
              <span>Perfect for birthdays, anniversaries, proposals, and private surprises.</span>
            </article>
            <article className="auth-mini-card">
              <FiCalendar />
              <strong>Faster planning</strong>
              <span>Keep dates, guest details, and add-ons tied to one account.</span>
            </article>
            <article className="auth-mini-card">
              <FiUsers />
              <strong>Built for return visits</strong>
              <span>Come back to previous bookings and continue without repeating setup.</span>
            </article>
          </div>
        </section>

        <div className="auth-card-wrap">
          <div className="auth-card card auth-card-wide">
            <span className="auth-card-eyebrow">Sign up</span>
            <h2>Create Account</h2>
            <p className="auth-subtitle">Join SK Binge Galaxy and move into booking with a cleaner setup.</p>

            {error && <div className="error-message">{error}</div>}

            <form onSubmit={handleSubmit} noValidate>
              <div className="grid-2">
                <div className={`input-group ${fieldErrors.firstName ? 'has-error' : ''}`}>
                  <label>First Name</label>
                  <input required value={form.firstName} onChange={update('firstName')} onBlur={handleBlur('firstName')} placeholder="John" autoFocus />
                  {fieldErrors.firstName && <span className="field-error">{fieldErrors.firstName}</span>}
                </div>
                <div className="input-group">
                  <label>Last Name</label>
                  <input value={form.lastName} onChange={update('lastName')} placeholder="Doe" />
                </div>
              </div>
              <div className={`input-group ${fieldErrors.email ? 'has-error' : ''}`}>
                <label>Email</label>
                <input type="email" required value={form.email} onChange={update('email')} onBlur={handleBlur('email')} placeholder="you@example.com" />
                {fieldErrors.email && <span className="field-error">{fieldErrors.email}</span>}
              </div>
              <div className={`input-group ${fieldErrors.phone ? 'has-error' : ''}`}>
                <label>Phone</label>
                <input type="tel" required value={form.phone} onChange={update('phone')} onBlur={handleBlur('phone')} placeholder="+91 9876543210" />
                {fieldErrors.phone && <span className="field-error">{fieldErrors.phone}</span>}
              </div>
              <div className={`input-group ${fieldErrors.password ? 'has-error' : ''}`}>
                <label>Password</label>
                <div className="input-password-wrap">
                  <input type={showPw ? 'text' : 'password'} required minLength={10} value={form.password} onChange={update('password')} onBlur={handleBlur('password')} placeholder="Min 10 characters" />
                  <button type="button" className="pw-toggle" onClick={() => setShowPw(v => !v)} aria-label={showPw ? 'Hide password' : 'Show password'} tabIndex={-1}>
                    {showPw ? <FiEyeOff /> : <FiEye />}
                  </button>
                </div>
                {fieldErrors.password && <span className="field-error">{fieldErrors.password}</span>}
              </div>
              <div className={`input-group ${fieldErrors.confirmPassword ? 'has-error' : ''}`}>
                <label>Confirm Password</label>
                <div className="input-password-wrap">
                  <input type={showCpw ? 'text' : 'password'} required value={form.confirmPassword} onChange={update('confirmPassword')} onBlur={handleBlur('confirmPassword')} placeholder="••••••••" />
                  <button type="button" className="pw-toggle" onClick={() => setShowCpw(v => !v)} aria-label={showCpw ? 'Hide password' : 'Show password'} tabIndex={-1}>
                    {showCpw ? <FiEyeOff /> : <FiEye />}
                  </button>
                </div>
                {fieldErrors.confirmPassword && <span className="field-error">{fieldErrors.confirmPassword}</span>}
              </div>
              <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
                {loading ? <><span className="btn-spinner" /> Creating...</> : 'Create Account'}
              </button>
            </form>

            <div className="auth-links">
              <span>Already have an account? <Link to="/login">Sign in</Link></span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
