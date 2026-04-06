import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import { FiEye, FiEyeOff } from 'react-icons/fi';
import './Auth.css';

export default function Register() {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [touched, setTouched] = useState({});
  const [showPw, setShowPw] = useState(false);
  const [showCpw, setShowCpw] = useState(false);
  const { register } = useAuth();
  const navigate = useNavigate();

  const fieldErrors = {
    firstName: touched.firstName && !form.firstName.trim() ? 'First name is required' : '',
    email: touched.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) ? 'Enter a valid email' : '',
    phone: touched.phone && !/^[6-9]\d{9}$/.test(form.phone.replace(/\D/g, '').slice(-10)) ? 'Enter a valid 10-digit phone' : '',
    password: touched.password && form.password.length < 6 ? 'Min 6 characters' : '',
    confirmPassword: touched.confirmPassword && form.password !== form.confirmPassword ? 'Passwords do not match' : '',
  };

  const handleBlur = (field) => () => setTouched(t => ({ ...t, [field]: true }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setTouched({ firstName: true, email: true, phone: true, password: true, confirmPassword: true });
    if (Object.values(fieldErrors).some(Boolean)) return;
    if (!form.firstName.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) || form.password.length < 6 || form.password !== form.confirmPassword) return;
    setError('');
    setLoading(true);
    try {
      const { confirmPassword, ...data } = form;
      await register(data);
      toast.success('Account created!');
      navigate('/dashboard');
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
    <div className="auth-page">
      <div className="auth-card card">
        <h1>Create Account</h1>
        <p className="auth-subtitle">Join SK Binge Galaxy today</p>

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
              <input type={showPw ? 'text' : 'password'} required minLength={6} value={form.password} onChange={update('password')} onBlur={handleBlur('password')} placeholder="Min 6 characters" />
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
  );
}
