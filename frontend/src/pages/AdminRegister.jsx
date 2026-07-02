import { useState, useEffect } from 'react';
import { authService, siteContentService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiUserPlus, FiShield, FiSettings, FiEye, FiEyeOff } from 'react-icons/fi';
import PhoneField, { splitPhone, validatePhone } from '../components/form/PhoneField';
import { ADMIN_ONBOARDING_TERMS_SLUG, DEFAULT_ADMIN_TERMS_TITLE, parseTerms } from '../content/legalContent';
import './Auth.css';

export default function AdminRegister() {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '', consentGiven: false });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [touched, setTouched] = useState({});
  const [showPw, setShowPw] = useState(false);
  const [showCpw, setShowCpw] = useState(false);
  const [generatedPw, setGeneratedPw] = useState('');
  const [terms, setTerms] = useState({ title: DEFAULT_ADMIN_TERMS_TITLE, body: '' });

  useEffect(() => {
    let cancelled = false;
    siteContentService.getPublic(ADMIN_ONBOARDING_TERMS_SLUG)
      .then((res) => { if (!cancelled) setTerms(parseTerms(res.data?.data?.contentJson, { title: DEFAULT_ADMIN_TERMS_TITLE, body: '' })); })
      .catch(() => { /* terms box shows a fallback note */ });
    return () => { cancelled = true; };
  }, []);

  const generatePassword = () => {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*';
    const arr = new Uint8Array(16);
    crypto.getRandomValues(arr);
    const pw = Array.from(arr, b => chars[b % chars.length]).join('');
    setForm(f => ({ ...f, password: pw, confirmPassword: pw }));
    setGeneratedPw(pw);
    setShowPw(true);
    setShowCpw(true);
  };

  const handleChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });
  const handleBlur = (field) => () => setTouched(t => ({ ...t, [field]: true }));

  const PW_REGEX = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])[A-Za-z\d@$!%*?&#.\-_~+^]{10,}$/;

  const fieldErrors = {
    firstName: touched.firstName && !form.firstName.trim() ? 'First name is required' : '',
    email: touched.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) ? 'Enter a valid email' : '',
    // validatePhone returns the error message ('' when valid) — use it directly.
    // (The previous `!validatePhone(...)` inverted this, flagging valid numbers.)
    phone: touched.phone ? validatePhone(form.phone, { required: true }) : '',
    password: touched.password ? (form.password.length < 10 ? 'Min 10 characters' : !PW_REGEX.test(form.password) ? 'Must include uppercase, lowercase, number & special character (@$!%*?&#)' : '') : '',
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
    if (form.password.length < 10) {
      const message = 'Password must be at least 10 characters long';
      setError(message);
      toast.error(message);
      return;
    }
    const PW_CHECK = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])[A-Za-z\d@$!%*?&#.\-_~+^]{10,}$/;
    if (!PW_CHECK.test(form.password)) {
      const message = 'Password must include uppercase, lowercase, number & special character (@$!%*?&#)';
      setError(message);
      toast.error(message);
      return;
    }
    const phoneParts = splitPhone(form.phone);
    const phoneErr = validatePhone(form.phone, { required: true });
    if (phoneErr) {
      setError(phoneErr);
      toast.error(phoneErr);
      return;
    }
    if (!form.consentGiven) {
      const msg = 'Please confirm the new admin accepts the administrator terms below.';
      setError(msg);
      toast.error(msg);
      return;
    }
    setLoading(true);
    try {
      const { confirmPassword, phone, ...rest } = form;
      const data = {
        ...rest,
        phone: phoneParts.phone,
        phoneCountryCode: phoneParts.phoneCountryCode,
      };
      await authService.adminRegister(data);
      toast.success('New admin account created successfully!');
      setForm({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '', consentGiven: false });
      setTouched({});
      setError('');
      setGeneratedPw('');
      setShowPw(false);
      setShowCpw(false);
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
              <PhoneField
                value={form.phone}
                onChange={(val) => setForm(f => ({ ...f, phone: val || '' }))}
                onBlur={handleBlur('phone')}
                required
                error={fieldErrors.phone}
              />
              <div className={`input-group ${fieldErrors.password ? 'has-error' : ''}`}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  Password
                  <button type="button" className="btn btn-secondary btn-sm" onClick={generatePassword}
                    style={{ fontSize: '0.7rem', padding: '0.15rem 0.5rem', lineHeight: 1.4 }}>
                    Generate
                  </button>
                </label>
                <div className="input-password-wrap">
                  <input type={showPw ? 'text' : 'password'} name="password" value={form.password} onChange={(e) => { handleChange(e); setGeneratedPw(''); }} onBlur={handleBlur('password')} required placeholder="••••••••" minLength={8} />
                  <button type="button" className="pw-toggle" onClick={() => setShowPw(v => !v)} aria-label={showPw ? 'Hide password' : 'Show password'} tabIndex={-1}>
                    {showPw ? <FiEyeOff /> : <FiEye />}
                  </button>
                </div>
                {generatedPw && (
                  <span className="field-hint" style={{ fontSize: '0.75rem', color: 'var(--success, #22c55e)', display: 'flex', alignItems: 'center', gap: '0.3rem', marginTop: '0.25rem' }}>
                    Generated — copy and share with the new admin before submitting
                  </span>
                )}
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

              <div className="input-group" style={{ marginTop: '0.25rem' }}>
                <label style={{ fontWeight: 600, marginBottom: '0.4rem' }}>{terms.title || DEFAULT_ADMIN_TERMS_TITLE}</label>
                <div style={{
                  maxHeight: '160px', overflowY: 'auto', padding: '0.7rem 0.85rem',
                  border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                  background: 'var(--bg-input)', color: 'var(--text-secondary)',
                  fontSize: '0.82rem', lineHeight: 1.5, whiteSpace: 'pre-wrap',
                }}>
                  {terms.body || 'No administrator terms have been published yet. A super-admin can add them under Terms & Legal Content.'}
                </div>
                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.55rem', fontWeight: 400, cursor: 'pointer', marginTop: '0.6rem' }}>
                  <input type="checkbox" checked={form.consentGiven}
                    onChange={(e) => setForm({ ...form, consentGiven: e.target.checked })}
                    style={{ marginTop: '0.2rem', width: 'auto' }} required />
                  <span style={{ fontSize: '0.85rem', lineHeight: 1.4 }}>
                    The new administrator has read and accepts the terms above and consents to data processing.
                  </span>
                </label>
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
