import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { authService, adminService } from '../services/endpoints';
import { toast } from 'react-toastify';

export default function AdminCustomerEdit() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loyalty, setLoyalty] = useState(null);
  const [adjustForm, setAdjustForm] = useState({ points: '', description: '' });
  const [adjusting, setAdjusting] = useState(false);
  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    password: '',
  });
  const [showPassword, setShowPassword] = useState(false);

  useEffect(() => {
    authService.getCustomerById(id)
      .then(res => {
        const c = res.data.data || res.data;
        setForm({
          firstName: c.firstName || '',
          lastName: c.lastName || '',
          email: c.email || '',
          phone: c.phone || '',
          password: c.password || '',
        });
      })
      .catch(() => toast.error('Failed to load customer'))
      .finally(() => setLoading(false));
    adminService.getCustomerLoyalty(id)
      .then(res => setLoyalty(res.data.data || res.data))
      .catch(() => {}); // may not have loyalty yet
  }, [id]);

  const handleSave = async () => {
    if (!form.firstName.trim()) { toast.error('First name is required'); return; }
    if (!form.lastName.trim()) { toast.error('Last name is required'); return; }
    if (!form.email.trim()) { toast.error('Email is required'); return; }
    if (!/\S+@\S+\.\S+/.test(form.email.trim())) { toast.error('Please enter a valid email address'); return; }
    if (!form.phone.trim()) { toast.error('Phone is required'); return; }
    if (!/^[6-9]\d{9}$/.test(form.phone.trim())) { toast.error('Phone must be a valid 10-digit Indian number'); return; }

    setSaving(true);
    try {
      const payload = {
        firstName: form.firstName,
        lastName: form.lastName,
        email: form.email,
        phone: form.phone,
      };
      if (form.password.trim()) {
        if (form.password.trim().length < 10) { toast.error('Password must be at least 10 characters'); setSaving(false); return; }
        if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])/.test(form.password.trim())) { toast.error('Password must include uppercase, lowercase, number & special character'); setSaving(false); return; }
        payload.password = form.password.trim();
      }
      await authService.adminUpdateCustomer(id, payload);
      toast.success('Customer updated successfully');
      navigate(-1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to update customer');
    } finally {
      setSaving(false);
    }
  };

  const handleAdjustPoints = async () => {
    const pts = parseInt(adjustForm.points, 10);
    if (!pts || pts === 0) { toast.error('Points must be a non-zero number'); return; }
    if (!adjustForm.description.trim()) { toast.error('Description is required'); return; }
    setAdjusting(true);
    try {
      const res = await adminService.adjustLoyaltyPoints(id, { points: pts, description: adjustForm.description.trim() });
      setLoyalty(res.data.data || res.data);
      setAdjustForm({ points: '', description: '' });
      toast.success(`${pts > 0 ? 'Added' : 'Deducted'} ${Math.abs(pts)} points`);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to adjust points');
    } finally {
      setAdjusting(false);
    }
  };

  const inputStyle = {
    padding: '0.6rem 0.8rem',
    borderRadius: 'var(--radius-sm)',
    border: '1px solid var(--border)',
    background: 'var(--bg-input)',
    color: 'var(--text)',
    fontSize: '0.9rem',
    width: '100%',
  };

  const labelStyle = {
    fontWeight: 600,
    marginBottom: '0.4rem',
    display: 'block',
    fontSize: '0.85rem',
    color: 'var(--text-secondary)',
  };

  if (loading) {
    return (
      <div className="container">
        <div className="loading"><div className="spinner"></div></div>
      </div>
    );
  }

  return (
    <div className="container" style={{ maxWidth: '600px', margin: '0 auto' }}>
      <div className="page-header" style={{ marginBottom: '1.5rem' }}>
        <h1>Edit Customer</h1>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
          Update customer details — changes will reflect across all bookings
        </p>
      </div>

      <div className="card" style={{ padding: '1.5rem' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
          <div>
            <label style={labelStyle}>First Name *</label>
            <input
              style={inputStyle}
              value={form.firstName}
              onChange={e => setForm({ ...form, firstName: e.target.value })}
              placeholder="First name"
            />
          </div>
          <div>
            <label style={labelStyle}>Last Name *</label>
            <input
              style={inputStyle}
              value={form.lastName}
              onChange={e => setForm({ ...form, lastName: e.target.value })}
              placeholder="Last name"
            />
          </div>
          <div style={{ gridColumn: '1 / -1' }}>
            <label style={labelStyle}>Email *</label>
            <input
              type="email"
              style={inputStyle}
              value={form.email}
              onChange={e => setForm({ ...form, email: e.target.value })}
              placeholder="Email address"
            />
          </div>
          <div style={{ gridColumn: '1 / -1' }}>
            <label style={labelStyle}>Phone *</label>
            <input
              style={inputStyle}
              value={form.phone}
              onChange={e => setForm({ ...form, phone: e.target.value })}
              placeholder="Phone number"
            />
          </div>
          <div style={{ gridColumn: '1 / -1' }}>
            <label style={labelStyle}>Change Password</label>
            <div style={{ position: 'relative' }}>
              <input
                type={showPassword ? 'text' : 'password'}
                style={{ ...inputStyle, paddingRight: '3rem' }}
                value={form.password}
                onChange={e => setForm({ ...form, password: e.target.value })}
                placeholder="Enter new password to change"
                autoComplete="off"
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                style={{
                  position: 'absolute', right: '0.5rem', top: '50%', transform: 'translateY(-50%)',
                  background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)',
                  fontSize: '0.85rem', padding: '0.2rem 0.4rem',
                }}
              >
                {showPassword ? '🙈 Hide' : '👁️ Show'}
              </button>
            </div>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block', marginTop: '0.3rem' }}>
              Stored passwords are encrypted and cannot be displayed. Enter a new password to reset it, or leave blank to keep unchanged.
            </span>
          </div>
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', marginTop: '1.5rem', justifyContent: 'flex-end' }}>
          <button className="btn btn-secondary" onClick={() => navigate(-1)}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </div>

      {/* Loyalty Management */}
      <div className="card" style={{ padding: '1.5rem', marginTop: '1.5rem' }}>
        <h2 style={{ fontSize: '1.1rem', marginBottom: '1rem' }}>Loyalty Points</h2>
        {loyalty ? (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem', marginBottom: '1.5rem' }}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--primary)' }}>{loyalty.currentBalance?.toLocaleString() ?? 0}</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Balance</div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--primary)' }}>{loyalty.tierLevel || 'BRONZE'}</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Tier</div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--primary)' }}>{loyalty.totalPointsEarned?.toLocaleString() ?? 0}</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Lifetime</div>
              </div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr auto', gap: '0.75rem', alignItems: 'end' }}>
              <div>
                <label style={labelStyle}>Points (+/-)</label>
                <input type="number" style={inputStyle} placeholder="+100 or -50"
                  value={adjustForm.points} onChange={e => setAdjustForm(prev => ({ ...prev, points: e.target.value }))} />
              </div>
              <div>
                <label style={labelStyle}>Reason</label>
                <input style={inputStyle} placeholder="e.g. Goodwill bonus, correction"
                  value={adjustForm.description} onChange={e => setAdjustForm(prev => ({ ...prev, description: e.target.value }))} />
              </div>
              <button className="btn btn-primary" disabled={adjusting} onClick={handleAdjustPoints}>
                {adjusting ? 'Adjusting...' : 'Adjust'}
              </button>
            </div>
            {loyalty.recentTransactions?.length > 0 && (
              <div style={{ marginTop: '1rem' }}>
                <h3 style={{ fontSize: '0.9rem', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Recent Transactions</h3>
                <div style={{ maxHeight: '200px', overflowY: 'auto' }}>
                  {loyalty.recentTransactions.map((t, i) => (
                    <div key={i} style={{ display: 'flex', justifyContent: 'space-between', padding: '0.4rem 0', borderBottom: '1px solid var(--border)', fontSize: '0.85rem' }}>
                      <span>{t.description || t.type}</span>
                      <span style={{ fontWeight: 600, color: t.points > 0 ? 'var(--success)' : 'var(--danger)' }}>
                        {t.points > 0 ? '+' : ''}{t.points}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        ) : (
          <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>No loyalty account yet — points will be created on their first booking.</p>
        )}
      </div>
    </div>
  );
}
