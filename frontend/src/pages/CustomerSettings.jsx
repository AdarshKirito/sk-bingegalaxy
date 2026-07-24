import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
import { FiSettings, FiMail, FiLock, FiEye, FiEyeOff, FiTrash2, FiAlertTriangle, FiMapPin, FiPhone } from 'react-icons/fi';
import AddressFields, { EMPTY_ADDRESS, validateAddress } from '../components/form/AddressFields';
import PhoneField, { joinPhone, splitPhone, validatePhone } from '../components/form/PhoneField';
import './CustomerHub.css';

/**
 * Customer Settings — credential controls (email + password) for the
 * authenticated customer. Profile / preferences live in `/account`.
 *
 * Both flows require the current password before any change is committed,
 * matching the pattern used by Google, GitHub, and Stripe customer portals:
 * a sensitive credential rotation must be re-authenticated even inside an
 * already-authenticated session.
 */
export default function CustomerSettings() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  // Email change — verified 2-step flow (password re-auth → 6-digit code sent
  // to the NEW inbox → confirm). Same bar as Google/GitHub/Stripe portals.
  const [emailForm, setEmailForm] = useState({ newEmail: '', currentPassword: '', otp: '', codeSent: false });
  const [showEmailPassword, setShowEmailPassword] = useState(false);
  const [changingEmail, setChangingEmail] = useState(false);

  // Password change
  const [passwordForm, setPasswordForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [showPasswords, setShowPasswords] = useState({ current: false, new: false, confirm: false });
  const [changingPassword, setChangingPassword] = useState(false);

  // Account deletion (right-to-erasure)
  const [deleteConfirm, setDeleteConfirm] = useState('');
  const [deleting, setDeleting] = useState(false);

  // Contact & address (self-service profile fields). The profile PUT is a
  // full-document update that requires firstName/phone (and rejects phone
  // CHANGES — those go through the verified flow), so we keep the loaded
  // profile around and echo the identity/contact fields back unchanged.
  const [profile, setProfile] = useState(null);
  const [contactForm, setContactForm] = useState({ firstName: '', lastName: '', address: { ...EMPTY_ADDRESS } });
  const [savingContact, setSavingContact] = useState(false);
  // Verified phone change (re-auth with current password, like email changes)
  const [phoneForm, setPhoneForm] = useState({ phone: '', currentPassword: '' });
  const [showPhonePassword, setShowPhonePassword] = useState(false);
  const [changingPhone, setChangingPhone] = useState(false);

  useEffect(() => {
    authService.getProfile()
      .then((res) => {
        const p = res.data?.data || res.data || {};
        setProfile(p);
        setContactForm({
          firstName: p.firstName || '',
          lastName: p.lastName || '',
          address: {
            addressLine1: p.addressLine1 || '',
            addressLine2: p.addressLine2 || '',
            city: p.city || '',
            state: p.state || '',
            country: p.country || '',
            postalCode: p.postalCode || '',
          },
        });
        setPhoneForm((f) => ({ ...f, phone: joinPhone(p.phoneCountryCode, p.phone) }));
      })
      .catch(() => { /* profile fetch failure — fields start blank */ });
  }, []);

  const handleSaveContact = async (e) => {
    e.preventDefault();
    if (!profile) { toast.error('Profile still loading — try again in a moment'); return; }
    if (!contactForm.firstName.trim()) { toast.error('First name is required'); return; }
    const addressErrors = validateAddress(contactForm.address);
    if (Object.keys(addressErrors).length) { toast.error(Object.values(addressErrors)[0]); return; }
    setSavingContact(true);
    try {
      const res = await authService.updateProfile({
        firstName: contactForm.firstName.trim(),
        lastName: contactForm.lastName.trim(),
        // Phone echoed UNCHANGED — the endpoint requires it and rejects phone
        // edits (those go through the verified change-phone flow below).
        phone: profile.phone,
        phoneCountryCode: profile.phoneCountryCode,
        addressLine1: contactForm.address.addressLine1 || '',
        addressLine2: contactForm.address.addressLine2 || '',
        city: contactForm.address.city || '',
        state: contactForm.address.state || '',
        country: contactForm.address.country || '',
        postalCode: contactForm.address.postalCode || '',
      });
      const updated = res.data?.data;
      if (updated) setProfile(updated);
      toast.success('Profile saved');
    } catch (error) {
      toast.error(error.response?.data?.message || error.userMessage || 'Failed to save profile');
    } finally {
      setSavingContact(false);
    }
  };

  const handlePhoneChange = async (e) => {
    e.preventDefault();
    const phoneError = validatePhone(phoneForm.phone, { required: true });
    if (phoneError) { toast.error(phoneError); return; }
    if (!phoneForm.currentPassword) { toast.error('Current password is required'); return; }
    setChangingPhone(true);
    try {
      const split = splitPhone(phoneForm.phone);
      await authService.changePhone(split.phone, split.phoneCountryCode, phoneForm.currentPassword);
      toast.success('Phone number updated');
      setPhoneForm((f) => ({ ...f, currentPassword: '' }));
      // Keep the echoed profile in sync — the address form re-sends the phone
      // unchanged, and a stale value would be rejected as a phone "change".
      setProfile((p) => (p ? { ...p, phone: split.phone, phoneCountryCode: split.phoneCountryCode } : p));
    } catch (error) {
      toast.error(error.response?.data?.message || error.userMessage || 'Failed to update phone');
    } finally {
      setChangingPhone(false);
    }
  };

  const customer = user || {};
  const toggleShow = (k) => setShowPasswords((p) => ({ ...p, [k]: !p[k] }));

  const handleDeleteAccount = async (e) => {
    e.preventDefault();
    if (deleteConfirm.trim().toUpperCase() !== 'DELETE') {
      toast.error('Type DELETE to confirm account deletion');
      return;
    }
    setDeleting(true);
    try {
      await authService.requestAccountDeletion();
      // The account is deactivated and all sessions are revoked server-side, so
      // force a local logout and bounce to home — the session is already dead.
      toast.success('Your account has been scheduled for deletion. You have been signed out.');
      try { await logout(); } catch { /* session already revoked server-side */ }
      navigate('/', { replace: true });
    } catch (error) {
      toast.error(error.response?.data?.message || error.userMessage || 'Failed to submit deletion request');
      setDeleting(false);
    }
  };

  const handleRequestEmailCode = async (e) => {
    e?.preventDefault();
    if (!/\S+@\S+\.\S+/.test(emailForm.newEmail)) { toast.error('Enter a valid new email address'); return; }
    if (!emailForm.currentPassword) { toast.error('Enter your current password to authorise the change'); return; }
    setChangingEmail(true);
    try {
      await authService.requestEmailChange(emailForm.newEmail.trim(), emailForm.currentPassword);
      setEmailForm((f) => ({ ...f, codeSent: true }));
      toast.success('Verification code sent to the new address — check that inbox.');
    } catch (error) {
      toast.error(error.response?.data?.message || error.userMessage || 'Could not start the email change');
    } finally {
      setChangingEmail(false);
    }
  };

  const handleConfirmEmailCode = async (e) => {
    e?.preventDefault();
    if (!/^\d{6}$/.test(emailForm.otp.trim())) { toast.error('Enter the 6-digit code from the email'); return; }
    setChangingEmail(true);
    try {
      await authService.confirmEmailChange(emailForm.otp.trim());
      toast.success('Email changed and verified. Other sessions were signed out for safety.');
      setEmailForm({ newEmail: '', currentPassword: '', otp: '', codeSent: false });
    } catch (error) {
      toast.error(error.response?.data?.message || error.userMessage || 'Could not confirm the code');
    } finally {
      setChangingEmail(false);
    }
  };

  const handlePasswordChange = async (e) => {
    e.preventDefault();
    if (!passwordForm.currentPassword || !passwordForm.newPassword || !passwordForm.confirmPassword) {
      toast.error('All password fields are required');
      return;
    }
    if (passwordForm.newPassword.length < 10) { toast.error('New password must be at least 10 characters'); return; }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) { toast.error('New password and confirmation do not match'); return; }
    const passwordRegex = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])/;
    if (!passwordRegex.test(passwordForm.newPassword)) {
      toast.error('Password must contain uppercase, lowercase, number, and special character');
      return;
    }
    setChangingPassword(true);
    try {
      await authService.changePassword({
        currentPassword: passwordForm.currentPassword,
        newPassword: passwordForm.newPassword,
      });
      toast.success('Password changed. Please sign in again.');
      setPasswordForm({ currentPassword: '', newPassword: '', confirmPassword: '' });
      try { await logout(); } catch { /* ignore */ }
      navigate('/login', { replace: true });
      return;
    } catch (error) {
      const msg = error.response?.data?.message || error.userMessage || 'Failed to change password';
      toast.error(msg);
    } finally {
      setChangingPassword(false);
    }
  };

  return (
    <div className="container customer-hub">
      <SEO title="Settings" description="Manage your email and password securely." />

      <div className="customer-hero">
        <div className="customer-hero-copy">
          <span className="customer-kicker"><FiSettings /> Settings</span>
          <h1>Account Settings</h1>
          <p>Manage your contact details, address, sign-in email and password. Sensitive changes require your current password.</p>
        </div>
      </div>

      {/* Contact & address */}
      <div className="customer-grid" style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: '1.25rem', marginBottom: '1.25rem' }}>
        <section className="customer-card">
          <header className="customer-card-header">
            <h2><FiMapPin /> Profile &amp; Address</h2>
          </header>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Your name appears on bookings and invoices; the address powers billing and
            jurisdiction-correct taxes on your receipts.
          </p>
          <form onSubmit={handleSaveContact} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '0.85rem' }}>
              <div className="input-group">
                <label>First name *</label>
                <input value={contactForm.firstName} onChange={(e) => setContactForm((f) => ({ ...f, firstName: e.target.value }))} required />
              </div>
              <div className="input-group">
                <label>Last name</label>
                <input value={contactForm.lastName} onChange={(e) => setContactForm((f) => ({ ...f, lastName: e.target.value }))} />
              </div>
            </div>
            <AddressFields
              legend=""
              description="Pick country first to unlock states and cities."
              value={contactForm.address}
              onChange={(addr) => setContactForm((f) => ({ ...f, address: addr }))}
            />
            <button type="submit" className="btn btn-primary" disabled={savingContact} style={{ alignSelf: 'flex-start' }}>
              {savingContact ? 'Saving…' : 'Save Profile'}
            </button>
          </form>
        </section>

        <section className="customer-card">
          <header className="customer-card-header">
            <h2><FiPhone /> Phone Number</h2>
          </header>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Used for booking notifications (SMS/WhatsApp). Changing it requires your current password.
          </p>
          <form onSubmit={handlePhoneChange} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
            <PhoneField
              label="Phone"
              required
              value={phoneForm.phone}
              onChange={(v) => setPhoneForm((f) => ({ ...f, phone: v }))}
            />
            <div className="input-group">
              <label>Current password</label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showPhonePassword ? 'text' : 'password'}
                  value={phoneForm.currentPassword}
                  onChange={(e) => setPhoneForm((f) => ({ ...f, currentPassword: e.target.value }))}
                  autoComplete="current-password"
                  required
                />
                <button type="button" onClick={() => setShowPhonePassword((v) => !v)}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPhonePassword ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <button type="submit" className="btn btn-primary" disabled={changingPhone} style={{ alignSelf: 'flex-start' }}>
              {changingPhone ? 'Updating…' : 'Update Phone'}
            </button>
          </form>
        </section>
      </div>

      <div className="customer-grid" style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: '1.25rem' }}>
        {/* Change Email — verified 2-step flow */}
        <section className="customer-card">
          <header className="customer-card-header">
            <h2><FiMail /> Change Email</h2>
          </header>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Current email: <strong>{customer.email || '—'}</strong>. A 6-digit code goes to the{' '}
            <strong>new</strong> address — nothing changes until you enter it, proving you own that inbox.
          </p>
          {!emailForm.codeSent ? (
            <form onSubmit={handleRequestEmailCode} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
              <div className="input-group">
                <label>New email</label>
                <input type="email" value={emailForm.newEmail} onChange={(e) => setEmailForm((f) => ({ ...f, newEmail: e.target.value }))} placeholder="you@example.com" autoComplete="email" required />
              </div>
              <div className="input-group">
                <label>Current password</label>
                <div style={{ position: 'relative' }}>
                  <input
                    type={showEmailPassword ? 'text' : 'password'}
                    value={emailForm.currentPassword}
                    onChange={(e) => setEmailForm((f) => ({ ...f, currentPassword: e.target.value }))}
                    autoComplete="current-password"
                    required
                  />
                  <button type="button" onClick={() => setShowEmailPassword((v) => !v)}
                    style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                    {showEmailPassword ? <FiEyeOff /> : <FiEye />}
                  </button>
                </div>
              </div>
              <button type="submit" className="btn btn-primary" disabled={changingEmail}>
                {changingEmail ? 'Sending…' : 'Send Verification Code'}
              </button>
            </form>
          ) : (
            <form onSubmit={handleConfirmEmailCode} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
              <div className="input-group">
                <label>6-digit code (sent to {emailForm.newEmail})</label>
                <input inputMode="numeric" maxLength={6} value={emailForm.otp}
                  onChange={(e) => setEmailForm((f) => ({ ...f, otp: e.target.value.replace(/\D/g, '') }))} required />
              </div>
              <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                <button type="submit" className="btn btn-primary" disabled={changingEmail}>
                  {changingEmail ? 'Confirming…' : 'Confirm & Switch Email'}
                </button>
                <button type="button" className="btn btn-secondary" disabled={changingEmail} onClick={handleRequestEmailCode}>
                  Resend Code
                </button>
                <button type="button" className="btn btn-secondary" disabled={changingEmail}
                  onClick={() => setEmailForm({ newEmail: '', currentPassword: '', otp: '', codeSent: false })}>
                  Cancel
                </button>
              </div>
            </form>
          )}
        </section>

        {/* Change Password */}
        <section className="customer-card">
          <header className="customer-card-header">
            <h2><FiLock /> Change Password</h2>
          </header>
          <form onSubmit={handlePasswordChange} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
            <div className="input-group">
              <label>Current password</label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showPasswords.current ? 'text' : 'password'}
                  value={passwordForm.currentPassword}
                  onChange={(e) => setPasswordForm((p) => ({ ...p, currentPassword: e.target.value }))}
                  autoComplete="current-password"
                />
                <button type="button" onClick={() => toggleShow('current')}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.current ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="input-group">
              <label>New password</label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showPasswords.new ? 'text' : 'password'}
                  value={passwordForm.newPassword}
                  onChange={(e) => setPasswordForm((p) => ({ ...p, newPassword: e.target.value }))}
                  placeholder="Min 10 chars, upper+lower+digit+special"
                  autoComplete="new-password"
                />
                <button type="button" onClick={() => toggleShow('new')}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.new ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="input-group">
              <label>Confirm new password</label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showPasswords.confirm ? 'text' : 'password'}
                  value={passwordForm.confirmPassword}
                  onChange={(e) => setPasswordForm((p) => ({ ...p, confirmPassword: e.target.value }))}
                  autoComplete="new-password"
                />
                <button type="button" onClick={() => toggleShow('confirm')}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.confirm ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <button type="submit" className="btn btn-primary" disabled={changingPassword}>
              {changingPassword ? 'Changing…' : 'Change Password'}
            </button>
          </form>
        </section>
      </div>

      {/* Danger Zone — right-to-erasure (DPDP Act 2023 / GDPR) */}
      <section
        className="customer-card"
        style={{ marginTop: '1.25rem', border: '1px solid #ef4444' }}
      >
        <header className="customer-card-header">
          <h2 style={{ color: '#ef4444' }}><FiAlertTriangle /> Delete Account</h2>
        </header>
        <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
          This permanently closes your account. You will be signed out immediately, and all
          your personal data will be permanently anonymized within 30 days as required by law.
          <strong> This cannot be undone.</strong> Bookings already completed are retained in
          anonymized form for legal and accounting purposes.
        </p>
        <form onSubmit={handleDeleteAccount} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
          <div className="input-group">
            <label>Type <strong>DELETE</strong> to confirm</label>
            <input
              type="text"
              value={deleteConfirm}
              onChange={(e) => setDeleteConfirm(e.target.value)}
              placeholder="DELETE"
              autoComplete="off"
              aria-label="Type DELETE to confirm account deletion"
            />
          </div>
          <button
            type="submit"
            className="btn btn-danger"
            disabled={deleting || deleteConfirm.trim().toUpperCase() !== 'DELETE'}
            style={{ alignSelf: 'flex-start' }}
          >
            <FiTrash2 style={{ verticalAlign: '-2px', marginRight: 6 }} />
            {deleting ? 'Submitting…' : 'Permanently delete my account'}
          </button>
        </form>
      </section>
    </div>
  );
}
