import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
import { FiUser, FiMail, FiPhone, FiShield, FiLock, FiEye, FiEyeOff } from 'react-icons/fi';
import './AdminPages.css';

export default function AdminAccount() {
  const { user } = useAuth();
  const [profile, setProfile] = useState(user);
  const [loading, setLoading] = useState(true);
  const [passwordForm, setPasswordForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [showPasswords, setShowPasswords] = useState({ current: false, new: false, confirm: false });
  const [changingPassword, setChangingPassword] = useState(false);

  useEffect(() => {
    authService.getProfile()
      .then((res) => {
        setProfile(res.data.data || user);
      })
      .catch(() => {
        setProfile(user);
      })
      .finally(() => setLoading(false));
  }, [user]);

  const adminUser = profile || user || {};
  const displayName = [adminUser.firstName, adminUser.lastName].filter(Boolean).join(' ') || 'Admin';
  const roleLabel = adminUser.role === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin';

  const handlePasswordChange = async (e) => {
    e.preventDefault();

    if (!passwordForm.currentPassword || !passwordForm.newPassword || !passwordForm.confirmPassword) {
      toast.error('All password fields are required');
      return;
    }

    if (passwordForm.newPassword.length < 10) {
      toast.error('New password must be at least 10 characters');
      return;
    }

    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error('New password and confirmation do not match');
      return;
    }

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
      toast.success('Password changed successfully');
      setPasswordForm({ currentPassword: '', newPassword: '', confirmPassword: '' });
    } catch (error) {
      const msg = error.response?.data?.message || error.userMessage || 'Failed to change password';
      toast.error(msg);
    } finally {
      setChangingPassword(false);
    }
  };

  const toggleShow = (field) => {
    setShowPasswords((prev) => ({ ...prev, [field]: !prev[field] }));
  };

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
          <p className="adm-form-intro">View your profile details and manage your password.</p>
        </div>
      </div>

      <div className="adm-flow-hero">
        <div className="adm-form">
          <h3><FiUser style={{ marginRight: '0.5rem', verticalAlign: 'middle' }} /> Profile Details</h3>
          <div className="adm-flow-details" style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
            <div className="adm-flow-row">
              <span><FiUser style={{ marginRight: '0.35rem' }} /> Name</span>
              <strong>{displayName}</strong>
            </div>
            <div className="adm-flow-row">
              <span><FiMail style={{ marginRight: '0.35rem' }} /> Email</span>
              <strong>{adminUser.email || 'Not available'}</strong>
            </div>
            <div className="adm-flow-row">
              <span><FiPhone style={{ marginRight: '0.35rem' }} /> Phone</span>
              <strong>{adminUser.phone || 'Not set'}</strong>
            </div>
            <div className="adm-flow-row">
              <span><FiShield style={{ marginRight: '0.35rem' }} /> Role</span>
              <strong><span className="adm-badge adm-badge-active">{roleLabel}</span></strong>
            </div>
            <div className="adm-flow-row">
              <span>Status</span>
              <strong>
                <span className={`adm-badge ${adminUser.active !== false ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                  {adminUser.active !== false ? 'Active' : 'Inactive'}
                </span>
              </strong>
            </div>
          </div>
        </div>

        <div className="adm-form">
          <h3><FiLock style={{ marginRight: '0.5rem', verticalAlign: 'middle' }} /> Change Password</h3>
          <form onSubmit={handlePasswordChange} style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
            <div className="input-group">
              <label>Current Password</label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showPasswords.current ? 'text' : 'password'}
                  value={passwordForm.currentPassword}
                  onChange={(e) => setPasswordForm((p) => ({ ...p, currentPassword: e.target.value }))}
                  placeholder="Enter current password"
                  autoComplete="current-password"
                />
                <button type="button" onClick={() => toggleShow('current')} tabIndex={-1}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.current ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="input-group">
              <label>New Password</label>
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
              <label>Confirm New Password</label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showPasswords.confirm ? 'text' : 'password'}
                  value={passwordForm.confirmPassword}
                  onChange={(e) => setPasswordForm((p) => ({ ...p, confirmPassword: e.target.value }))}
                  placeholder="Re-enter new password"
                  autoComplete="new-password"
                />
                <button type="button" onClick={() => toggleShow('confirm')} tabIndex={-1}
                  style={{ position: 'absolute', right: '0.65rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: '0.25rem' }}>
                  {showPasswords.confirm ? <FiEyeOff /> : <FiEye />}
                </button>
              </div>
            </div>
            <div className="adm-form-actions" style={{ marginTop: '0.5rem' }}>
              <button type="submit" className="btn btn-primary" disabled={changingPassword}>
                {changingPassword ? 'Changing...' : 'Change Password'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
