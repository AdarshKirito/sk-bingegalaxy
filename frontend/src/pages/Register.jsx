import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import { trackSignUp, identifyUser } from '../services/analytics';
import { FiCalendar, FiEye, FiEyeOff, FiGift, FiUsers } from 'react-icons/fi';
import SEO from '../components/SEO';
import AddressFields, { EMPTY_ADDRESS, validateAddress } from '../components/form/AddressFields';
import PhoneField, { splitPhone, validatePhone } from '../components/form/PhoneField';
import './Auth.css';

export default function Register() {
  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    password: '',
    confirmPassword: '',
    address: { ...EMPTY_ADDRESS },
    consentGiven: false,
    consentMarketing: false,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [touched, setTouched] = useState({});
  const [showPw, setShowPw] = useState(false);
  const [showCpw, setShowCpw] = useState(false);
  const { register } = useAuth();

  const PW_REGEX = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])[A-Za-z\d@$!%*?&#.\-_~+^]{10,}$/;

  const fieldErrors = {
    firstName: touched.firstName && !form.firstName.trim() ? 'First name is required' : '',
    lastName: touched.lastName && !form.lastName.trim() ? 'Last name is required' : '',
    email: touched.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) ? 'Enter a valid email' : '',
    phone: touched.phone ? validatePhone(form.phone, { required: true }) : '',
    password: touched.password ? (form.password.length < 10 ? 'Min 10 characters' : !PW_REGEX.test(form.password) ? 'Must include uppercase, lowercase, number & special character' : '') : '',
    confirmPassword: touched.confirmPassword && form.password !== form.confirmPassword ? 'Passwords do not match' : '',
  };

  const handleBlur = (field) => () => setTouched(t => ({ ...t, [field]: true }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setTouched({ firstName: true, lastName: true, email: true, phone: true, password: true, confirmPassword: true });
    if (Object.values(fieldErrors).some(Boolean)) return;
    if (!form.firstName.trim() || !form.lastName.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) || !PW_REGEX.test(form.password) || form.password !== form.confirmPassword) return;
    if (validatePhone(form.phone, { required: true })) return;
    const addressErrors = validateAddress(form.address);
    if (Object.keys(addressErrors).length) {
      const msg = Object.values(addressErrors)[0];
      setError(msg);
      toast.error(msg);
      return;
    }
    if (!form.consentGiven) {
      const msg = 'Please accept the Terms of Service & Privacy Policy to create your account.';
      setError(msg);
      toast.error(msg);
      return;
    }
    setError('');
    setLoading(true);
    try {
      const phoneSplit = splitPhone(form.phone);
      const data = {
        firstName: form.firstName,
        lastName: form.lastName,
        email: form.email,
        password: form.password,
        phone: phoneSplit.phone,
        phoneCountryCode: phoneSplit.phoneCountryCode,
        addressLine1: form.address.addressLine1 || '',
        addressLine2: form.address.addressLine2 || '',
        city: form.address.city || '',
        state: form.address.state || '',
        country: form.address.country || '',
        postalCode: form.address.postalCode || '',
        consentGiven: form.consentGiven,
        consentMarketing: form.consentMarketing,
      };
      await register(data);
      trackSignUp('email');
      const u = JSON.parse(localStorage.getItem('user') || '{}');
      if (u.id) identifyUser(String(u.id), { role: u.role });
      toast.success('Account created!');
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
                <div className={`input-group ${fieldErrors.lastName ? 'has-error' : ''}`}>
                  <label>Last Name</label>
                  <input required value={form.lastName} onChange={update('lastName')} onBlur={handleBlur('lastName')} placeholder="Doe" />
                  {fieldErrors.lastName && <span className="field-error">{fieldErrors.lastName}</span>}
                </div>
              </div>
              <div className={`input-group ${fieldErrors.email ? 'has-error' : ''}`}>
                <label>Email</label>
                <input type="email" required value={form.email} onChange={update('email')} onBlur={handleBlur('email')} placeholder="you@example.com" />
                {fieldErrors.email && <span className="field-error">{fieldErrors.email}</span>}
              </div>
              <PhoneField
                label="Phone"
                required
                value={form.phone}
                onChange={(v) => { setForm({ ...form, phone: v }); setTouched(t => ({ ...t, phone: true })); }}
                error={fieldErrors.phone}
                helpText="Pick the country and we'll handle the dial-code automatically."
              />
              <AddressFields
                legend="Address (optional)"
                description="Helps us send swag, vouchers, and reminders to the right place."
                value={form.address}
                onChange={(addr) => setForm({ ...form, address: addr })}
              />
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

              <div className="input-group" style={{ marginTop: '0.25rem' }}>
                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.55rem', fontWeight: 400, cursor: 'pointer' }}>
                  <input type="checkbox" checked={form.consentGiven}
                    onChange={(e) => setForm({ ...form, consentGiven: e.target.checked })}
                    style={{ marginTop: '0.2rem', width: 'auto' }} required />
                  <span style={{ fontSize: '0.85rem', lineHeight: 1.4 }}>
                    I agree to the{' '}
                    <Link to="/terms" target="_blank" rel="noreferrer">Terms of Service &amp; Privacy Policy</Link>
                    {' '}and consent to my data being processed to operate my bookings.
                  </span>
                </label>
                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.55rem', fontWeight: 400, cursor: 'pointer', marginTop: '0.5rem' }}>
                  <input type="checkbox" checked={form.consentMarketing}
                    onChange={(e) => setForm({ ...form, consentMarketing: e.target.checked })}
                    style={{ marginTop: '0.2rem', width: 'auto' }} />
                  <span style={{ fontSize: '0.85rem', lineHeight: 1.4, color: 'var(--text-secondary)' }}>
                    Send me offers, event ideas and reminders (optional).
                  </span>
                </label>
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
