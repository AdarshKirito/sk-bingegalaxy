import { useState } from 'react';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiUserPlus, FiShield, FiSettings, FiEye, FiEyeOff } from 'react-icons/fi';
import './Auth.css';

export default function AdminRegister() {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [touched, setTouched] = useState({});
  const [showPw, setShowPw] = useState(false);
  const [showCpw, setShowCpw] = useState(false);

  const handleChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });
  const handleBlur = (field) => () => setTouched(t => ({ ...t, [field]: true }));

  const fieldErrors = {
    firstName: touched.firstName && !form.firstName.trim() ? 'First name is required' : '',
    email: touched.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) ? 'Enter a valid email' : '',
    phone: touched.phone && !/^[6-9]\d{9}$/.test(form.phone) ? 'Enter a valid 10-digit phone' : '',
    password: touched.password && form.password.length < 8 ? 'Min 8 characters' : '',
    confirmPassword: touched.confirmPassword && form.password !== form.confirmPassword ? 'Passwords do not match' : '',
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setTouched({ firstName: true, email: true, phone: true, password: true, confirmPassword: true });
    setError('');
    if (form.password !== form.confirmPassword) {
      const message = 'Passwords do not match';
      setError(message);
      toast.error(message);
      return;
    }
    if (form.password.length < 8) {
      const message = 'Password must be at least 8 characters long';
      setError(message);
      toast.error(message);
      return;
    }
    if (!/^[6-9]\d{9}$/.test(form.phone)) {
      const message = 'Enter a valid 10-digit Indian phone number';
      setError(message);
      toast.error(message);
      return;
    }
    setLoading(true);
    try {
      const { confirmPassword, ...data } = form;
      await authService.adminRegister(data);
      toast.success('New admin account created successfully!');
      setForm({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
      setTouched({});
      setError('');
    } catch (err) {
      const message = err.userMessage || 'Registration failed';
      setError(message);
      toast.error(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-shell auth-shell-register">
        <section className="auth-showcase auth-showcase-register">
          <span className="auth-kicker">Admin management</span>
          <h1>Expand your operations team.</h1>
          <p className="auth-lead">
            Add new administrators to help manage bookings, handle check-ins, and keep the venue running smoothly.
          </p>

          <div className="auth-showcase-grid">
            <article className="auth-mini-card">
              <FiUserPlus />
              <strong>Onboard staff</strong>
              <span>Create admin accounts for team members who manage day-to-day operations.</span>
            </article>
            <article className="auth-mini-card">
              <FiShield />
              <strong>Role-based access</strong>
              <span>Admins get access to bookings, check-ins, and reports without super-admin privileges.</span>
            </article>
            <article className="auth-mini-card">
              <FiSettings />
              <strong>Controlled access</strong>
              <span>Only super admins can create new admin accounts — keeping your system secure.</span>
            </article>
          </div>
        </section>

        <div className="auth-card-wrap">
          <div className="auth-card card auth-card-wide">
            <span className="auth-card-eyebrow">New admin</span>
            <h2>Add New Admin</h2>
            <p className="auth-subtitle">Create a new administrator account</p>

            {error && <div className="error-message">{error}</div>}

            <form onSubmit={handleSubmit} noValidate>
              <div className="grid-2">
                <div className={`input-group ${fieldErrors.firstName ? 'has-error' : ''}`}>
                  <label>First Name</label>
                  <input name="firstName" value={form.firstName} onChange={handleChange} onBlur={handleBlur('firstName')} required minLength={2} placeholder="John" autoFocus />
                  {fieldErrors.firstName && <span className="field-error">{fieldErrors.firstName}</span>}
                </div>
                <div className="input-group">
                  <label>Last Name</label>
                  <input name="lastName" value={form.lastName} onChange={handleChange} required placeholder="Doe" />
                </div>
              </div>
              <div className={`input-group ${fieldErrors.email ? 'has-error' : ''}`}>
                <label>Email</label>
                <input type="email" name="email" value={form.email} onChange={handleChange} onBlur={handleBlur('email')} required placeholder="admin@example.com" />
                {fieldErrors.email && <span className="field-error">{fieldErrors.email}</span>}
              </div>
              <div className={`input-group ${fieldErrors.phone ? 'has-error' : ''}`}>
                <label>Phone</label>
                <input name="phone" value={form.phone} onChange={handleChange} onBlur={handleBlur('phone')} required pattern="[6-9][0-9]{9}" title="Enter a valid 10-digit Indian phone number" placeholder="9876543210" />
                {fieldErrors.phone && <span className="field-error">{fieldErrors.phone}</span>}
              </div>
              <div className={`input-group ${fieldErrors.password ? 'has-error' : ''}`}>
                <label>Password</label>
                <div className="input-password-wrap">
                  <input type={showPw ? 'text' : 'password'} name="password" value={form.password} onChange={handleChange} onBlur={handleBlur('password')} required placeholder="••••••••" minLength={8} />
                  <button type="button" className="pw-toggle" onClick={() => setShowPw(v => !v)} aria-label={showPw ? 'Hide password' : 'Show password'} tabIndex={-1}>
                    {showPw ? <FiEyeOff /> : <FiEye />}
                  </button>
                </div>
                {fieldErrors.password && <span className="field-error">{fieldErrors.password}</span>}
              </div>
              <div className={`input-group ${fieldErrors.confirmPassword ? 'has-error' : ''}`}>
                <label>Confirm Password</label>
                <div className="input-password-wrap">
                  <input type={showCpw ? 'text' : 'password'} name="confirmPassword" value={form.confirmPassword} onChange={handleChange} onBlur={handleBlur('confirmPassword')} required placeholder="••••••••" />
                  <button type="button" className="pw-toggle" onClick={() => setShowCpw(v => !v)} aria-label={showCpw ? 'Hide password' : 'Show password'} tabIndex={-1}>
                    {showCpw ? <FiEyeOff /> : <FiEye />}
                  </button>
                </div>
                {fieldErrors.confirmPassword && <span className="field-error">{fieldErrors.confirmPassword}</span>}
              </div>
              <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
                {loading ? <><span className="btn-spinner" /> Creating...</> : 'Create Admin Account'}
              </button>
            </form>

            <div className="auth-links">
              <span>Super admin access is required to create additional admin accounts.</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
