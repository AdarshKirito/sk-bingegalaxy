import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
import { FiUser, FiMail, FiPhone, FiShield, FiLock, FiEye, FiEyeOff, FiEdit2, FiSave, FiX } from 'react-icons/fi';
import './AdminPages.css';

const EMPTY_PROFILE_FORM = {
  firstName: '',
  lastName: '',
  phone: '',
  phoneCountryCode: '+91',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  country: '',
  postalCode: '',
};

const profileFormFromUser = (u) => ({
  firstName: u?.firstName || '',
  lastName: u?.lastName || '',
  phone: u?.phone || '',
  phoneCountryCode: u?.phoneCountryCode || '+91',
  addressLine1: u?.addressLine1 || '',
  addressLine2: u?.addressLine2 || '',
  city: u?.city || '',
  state: u?.state || '',
  country: u?.country || '',
  postalCode: u?.postalCode || '',
});

export default function AdminAccount() {
  const { user, setUser, logout } = useAuth();
  const navigate = useNavigate();
  const [profile, setProfile] = useState(user);
  const [loading, setLoading] = useState(true);

  // Profile edit
  const [editing, setEditing] = useState(false);
  const [profileForm, setProfileForm] = useState(EMPTY_PROFILE_FORM);
  const [savingProfile, setSavingProfile] = useState(false);

  // Email change
  const [emailForm, setEmailForm] = useState({ newEmail: '', currentPassword: '' });
  const [showEmailPassword, setShowEmailPassword] = useState(false);
  const [changingEmail, setChangingEmail] = useState(false);

  // Password change
  const [passwordForm, setPasswordForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [showPasswords, setShowPasswords] = useState({ current: false, new: false, confirm: false });
  const [changingPassword, setChangingPassword] = useState(false);

  useEffect(() => {
    authService.getProfile()
      .then((res) => {
        const fetched = res.data.data || user;
        setProfile(fetched);
        setProfileForm(profileFormFromUser(fetched));
      })
      .catch(() => {
        setProfile(user);
        setProfileForm(profileFormFromUser(user));
      })
      .finally(() => setLoading(false));
  }, [user]);

  const adminUser = profile || user || {};
  const displayName = [adminUser.firstName, adminUser.lastName].filter(Boolean).join(' ') || 'Admin';
  const roleLabel = adminUser.role === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin';

  const handleProfileSave = async (e) => {
    e.preventDefault();
    if (!profileForm.firstName.trim()) { toast.error('First name is required'); return; }
    if (!/^\d{4,15}$/.test(profileForm.phone)) { toast.error('Phone must be 4-15 digits'); return; }
    if (!/^\+\d{1,4}$/.test(profileForm.phoneCountryCode)) { toast.error("Phone country code must look like '+91'"); return; }

    setSavingProfile(true);
    try {
      const res = await authService.updateProfile(profileForm);
      const updated = res.data.data;
      setProfile(updated);
      setProfileForm(profileFormFromUser(updated));
      setUser({ ...(user || {}), ...updated });
      toast.success('Profile updated');
      setEditing(false);
    } catch (error) {
      const msg = error.response?.data?.message || error.userMessage || 'Failed to update profile';
      toast.error(msg);
    } finally {
      setSavingProfile(false);
    }
  };

  const handleEmailChange = async (e) => {
    e.preventDefault();
    if (!emailForm.currentPassword || !emailForm.newEmail) { toast.error('Both fields are required'); return; }
    setChangingEmail(true);
    try {
      await authService.changeEmail(emailForm);
      // Backend revokes every active session after an email rotation, so the
      // current JWT will start failing on the next refresh. Force a clean
      // re-login with the new credential — standard production behaviour.
      toast.success('Email changed. Please sign in with your new email.');
      setEmailForm({ newEmail: '', currentPassword: '' });
      try { await logout(); } catch { /* ignore */ }
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

  const toggleShow = (field) => setShowPasswords((prev) => ({ ...prev, [field]: !prev[field] }));
  const setFormField = (k, v) => setProfileForm((p) => ({ ...p, [k]: v }));

  if (loading) {
    return (
      <div className="container adm-shell">
        <SEO title="Admin Account" description="Manage your admin account and security settings." />
        <div className="loading"><div className="spinner"></div></div>
      </div>
    );
  }

  return (
    <div className="container adm-shell">
      <SEO title="Admin Account" description="Manage your admin account and security settings." />

      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiUser /> Account</span>
          <h1>My Account</h1>
          <p className="adm-form-intro">View and update your profile, email, and password.</p>
        </div>
      </div>

      <div className="adm-flow-hero">
        {/* Profile */}
        <div className="adm-form">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
            <h3 style={{ margin: 0 }}><FiUser style={{ marginRight: '0.5rem', verticalAlign: 'middle' }} /> Profile Details</h3>
            {!editing ? (
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setEditing(true)}>
                <FiEdit2 /> Edit
              </button>
            ) : (
              <button type="button" className="btn btn-text btn-sm" onClick={() => { setEditing(false); setProfileForm(profileFormFromUser(adminUser)); }}>
                <FiX /> Cancel
              </button>
            )}
          </div>

          {!editing ? (
            <div className="adm-flow-details" style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
              <div className="adm-flow-row"><span><FiUser style={{ marginRight: '0.35rem' }} /> Name</span><strong>{displayName}</strong></div>
              <div className="adm-flow-row"><span><FiMail style={{ marginRight: '0.35rem' }} /> Email</span><strong>{adminUser.email || 'Not available'}</strong></div>
              <div className="adm-flow-row"><span><FiPhone style={{ marginRight: '0.35rem' }} /> Phone</span><strong>{adminUser.phone ? `${adminUser.phoneCountryCode || ''} ${adminUser.phone}` : 'Not set'}</strong></div>
              <div className="adm-flow-row"><span><FiShield style={{ marginRight: '0.35rem' }} /> Role</span><strong><span className="adm-badge adm-badge-active">{roleLabel}</span></strong></div>
              <div className="adm-flow-row"><span>Status</span><strong>
                <span className={`adm-badge ${adminUser.active !== false ? 'adm-badge-active' : 'adm-badge-inactive'}`}>{adminUser.active !== false ? 'Active' : 'Inactive'}</span>
              </strong></div>
            </div>
          ) : (
            <form onSubmit={handleProfileSave} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.85rem' }}>
                <div className="input-group"><label>First name *</label><input value={profileForm.firstName} onChange={(e) => setFormField('firstName', e.target.value)} maxLength={50} required /></div>
                <div className="input-group"><label>Last name</label><input value={profileForm.lastName} onChange={(e) => setFormField('lastName', e.target.value)} maxLength={50} /></div>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', gap: '0.85rem' }}>
                <div className="input-group"><label>Country code *</label><input value={profileForm.phoneCountryCode} onChange={(e) => setFormField('phoneCountryCode', e.target.value)} placeholder="+91" required /></div>
                <div className="input-group"><label>Phone *</label><input value={profileForm.phone} onChange={(e) => setFormField('phone', e.target.value)} placeholder="9876543210" required /></div>
              </div>
              <div className="input-group"><label>Address line 1</label><input value={profileForm.addressLine1} onChange={(e) => setFormField('addressLine1', e.target.value)} maxLength={200} /></div>
              <div className="input-group"><label>Address line 2</label><input value={profileForm.addressLine2} onChange={(e) => setFormField('addressLine2', e.target.value)} maxLength={200} /></div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '0.85rem' }}>
                <div className="input-group"><label>City</label><input value={profileForm.city} onChange={(e) => setFormField('city', e.target.value)} maxLength={100} /></div>
                <div className="input-group"><label>State</label><input value={profileForm.state} onChange={(e) => setFormField('state', e.target.value)} maxLength={100} /></div>
                <div className="input-group"><label>Country (ISO-2)</label><input value={profileForm.country} onChange={(e) => setFormField('country', e.target.value.toUpperCase())} maxLength={2} placeholder="IN" /></div>
                <div className="input-group"><label>Postal code</label><input value={profileForm.postalCode} onChange={(e) => setFormField('postalCode', e.target.value)} maxLength={20} /></div>
              </div>
              <div className="adm-form-actions" style={{ marginTop: '0.5rem' }}>
                <button type="submit" className="btn btn-primary" disabled={savingProfile}>
                  <FiSave /> {savingProfile ? 'Saving…' : 'Save Profile'}
                </button>
              </div>
            </form>
          )}
        </div>

        {/* Change Email */}
        <div className="adm-form">
          <h3><FiMail style={{ marginRight: '0.5rem', verticalAlign: 'middle' }} /> Change Email</h3>
          <p className="adm-form-intro" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Changing your email signs you out of other devices and requires re-verification.
          </p>
          <form onSubmit={handleEmailChange} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
            <div className="input-group"><label>New email</label><input type="email" value={emailForm.newEmail} onChange={(e) => setEmailForm((f) => ({ ...f, newEmail: e.target.value }))} placeholder="you@example.com" autoComplete="email" required /></div>
            <div className="input-group">
              <label>Current password</label>
              <div style={{ position: 'relative' }}>
                <input type={showEmailPassword ? 'text' : 'password'} value={emailForm.currentPassword} onChange={(e) => setEmailForm((f) => ({ ...f, currentPassword: e.target.value }))} autoComplete="current-password" required />
                <button type="button" onClick={() => setShowEmailPassword((v) => !v)} tabIndex={-1}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showEmailPassword ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="adm-form-actions" style={{ marginTop: '0.5rem' }}>
              <button type="submit" className="btn btn-primary" disabled={changingEmail}>{changingEmail ? 'Changing…' : 'Change Email'}</button>
            </div>
          </form>
        </div>

        {/* Change Password */}
        <div className="adm-form">
          <h3><FiLock style={{ marginRight: '0.5rem', verticalAlign: 'middle' }} /> Change Password</h3>
          <form onSubmit={handlePasswordChange} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
            <div className="input-group">
              <label>Current Password</label>
              <div style={{ position: 'relative' }}>
                <input type={showPasswords.current ? 'text' : 'password'} value={passwordForm.currentPassword} onChange={(e) => setPasswordForm((p) => ({ ...p, currentPassword: e.target.value }))} placeholder="Enter current password" autoComplete="current-password" />
                <button type="button" onClick={() => toggleShow('current')} tabIndex={-1}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.current ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="input-group">
              <label>New Password</label>
              <div style={{ position: 'relative' }}>
                <input type={showPasswords.new ? 'text' : 'password'} value={passwordForm.newPassword} onChange={(e) => setPasswordForm((p) => ({ ...p, newPassword: e.target.value }))} placeholder="Min 10 chars, upper+lower+digit+special" autoComplete="new-password" />
                <button type="button" onClick={() => toggleShow('new')} tabIndex={-1}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.new ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="input-group">
              <label>Confirm New Password</label>
              <div style={{ position: 'relative' }}>
                <input type={showPasswords.confirm ? 'text' : 'password'} value={passwordForm.confirmPassword} onChange={(e) => setPasswordForm((p) => ({ ...p, confirmPassword: e.target.value }))} placeholder="Re-enter new password" autoComplete="new-password" />
                <button type="button" onClick={() => toggleShow('confirm')} tabIndex={-1}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.confirm ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="adm-form-actions" style={{ marginTop: '0.5rem' }}>
              <button type="submit" className="btn btn-primary" disabled={changingPassword}>{changingPassword ? 'Changing...' : 'Change Password'}</button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
