import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
import { FiSettings, FiMail, FiLock, FiEye, FiEyeOff, FiTrash2, FiAlertTriangle } from 'react-icons/fi';
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

  // Email change
  const [emailForm, setEmailForm] = useState({ newEmail: '', currentPassword: '' });
  const [showEmailPassword, setShowEmailPassword] = useState(false);
  const [changingEmail, setChangingEmail] = useState(false);

  // Password change
  const [passwordForm, setPasswordForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [showPasswords, setShowPasswords] = useState({ current: false, new: false, confirm: false });
  const [changingPassword, setChangingPassword] = useState(false);

  // Account deletion (right-to-erasure)
  const [deleteConfirm, setDeleteConfirm] = useState('');
  const [deleting, setDeleting] = useState(false);

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

  const handleEmailChange = async (e) => {
    e.preventDefault();
    if (!emailForm.currentPassword || !emailForm.newEmail) { toast.error('Both fields are required'); return; }
    setChangingEmail(true);
    try {
      await authService.changeEmail(emailForm);
      // All sessions are revoked server-side after an email rotation, so we
      // force a re-login with the new credential — this is the same pattern
      // used by Google, GitHub, and Stripe.
      toast.success('Email changed. Please sign in with your new email.');
      setEmailForm({ newEmail: '', currentPassword: '' });
      try { await logout(); } catch { /* ignore network errors during forced logout */ }
      navigate('/login', { replace: true });
      return;
    } catch (error) {
      const msg = error.response?.data?.message || error.userMessage || 'Failed to change email';
      toast.error(msg);
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
          <h1>Account Security</h1>
          <p>Update your sign-in email and password. Both actions require your current password.</p>
        </div>
      </div>

      <div className="customer-grid" style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: '1.25rem' }}>
        {/* Change Email */}
        <section className="customer-card">
          <header className="customer-card-header">
            <h2><FiMail /> Change Email</h2>
          </header>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Current email: <strong>{customer.email || '—'}</strong>
          </p>
          <form onSubmit={handleEmailChange} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
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
                <button type="button" onClick={() => setShowEmailPassword((v) => !v)} tabIndex={-1}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showEmailPassword ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <button type="submit" className="btn btn-primary" disabled={changingEmail}>
              {changingEmail ? 'Changing…' : 'Change Email'}
            </button>
          </form>
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
                <button type="button" onClick={() => toggleShow('current')} tabIndex={-1}
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
                <button type="button" onClick={() => toggleShow('new')} tabIndex={-1}
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
                <button type="button" onClick={() => toggleShow('confirm')} tabIndex={-1}
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
