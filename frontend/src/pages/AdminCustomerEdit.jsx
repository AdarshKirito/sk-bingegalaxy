import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { authService, adminService } from '../services/endpoints';
import { useAuth } from '../context/AuthContext';
import useBingeStore from '../stores/bingeStore';
import loyaltyV2 from '../services/loyaltyV2';
import { toast } from 'react-toastify';
import AddressFields, { EMPTY_ADDRESS, validateAddress } from '../components/form/AddressFields';
import PhoneField, { joinPhone, splitPhone, validatePhone } from '../components/form/PhoneField';

export default function AdminCustomerEdit() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { isSuperAdmin } = useAuth();
  const { selectedBinge } = useBingeStore();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loyalty, setLoyalty] = useState(null);
  const [ledger, setLedger] = useState([]);
  const [adjustForm, setAdjustForm] = useState({ points: '', description: '' });
  const [adjusting, setAdjusting] = useState(false);
  // Binge-admin goodwill (service recovery): budget + grant form
  const [goodwillBudget, setGoodwillBudget] = useState(null);
  const [goodwillForm, setGoodwillForm] = useState({ points: '', reason: '', bookingRef: '' });
  const [granting, setGranting] = useState(false);
  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    password: '',
    address: { ...EMPTY_ADDRESS },
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
          phone: joinPhone(c.phoneCountryCode, c.phone),
          password: '',
          address: {
            addressLine1: c.addressLine1 || '',
            addressLine2: c.addressLine2 || '',
            city: c.city || '',
            state: c.state || '',
            country: c.country || '',
            postalCode: c.postalCode || '',
          },
        });
      })
      .catch(() => toast.error('Failed to load customer'))
      .finally(() => setLoading(false));
    // Loyalty snapshot + ledger are super-admin reads (403 for binge admins,
    // who get the goodwill panel instead).
    if (isSuperAdmin) {
      adminService.getCustomerLoyalty(id)
        .then(res => setLoyalty(res.data.data || res.data))
        .catch(() => {}); // may not have loyalty yet
      loyaltyV2.getCustomerLedger(id, { size: 15 })
        .then(page => setLedger(page?.content || []))
        .catch(() => {});
    }
    // Binge admins with the goodwill permission can sanction points from here.
    if (!isSuperAdmin && selectedBinge?.id) {
      loyaltyV2.getGoodwillBudget(selectedBinge.id)
        .then(setGoodwillBudget)
        .catch(() => setGoodwillBudget(null));
    }
  }, [id, isSuperAdmin, selectedBinge?.id]);

  const refreshLoyalty = useCallback(() => {
    if (!isSuperAdmin) return;
    adminService.getCustomerLoyalty(id)
      .then(res => setLoyalty(res.data.data || res.data)).catch(() => {});
    loyaltyV2.getCustomerLedger(id, { size: 15 })
      .then(page => setLedger(page?.content || [])).catch(() => {});
  }, [id, isSuperAdmin]);

  const handleSave = async () => {
    if (!form.firstName.trim()) { toast.error('First name is required'); return; }
    if (!form.lastName.trim()) { toast.error('Last name is required'); return; }
    if (!form.email.trim()) { toast.error('Email is required'); return; }
    if (!/\S+@\S+\.\S+/.test(form.email.trim())) { toast.error('Please enter a valid email address'); return; }
    const phoneError = validatePhone(form.phone, { required: true });
    if (phoneError) { toast.error(phoneError); return; }
    const addressErrors = validateAddress(form.address);
    if (Object.keys(addressErrors).length) { toast.error(Object.values(addressErrors)[0]); return; }

    setSaving(true);
    try {
      const phoneSplit = splitPhone(form.phone);
      const payload = {
        firstName: form.firstName,
        lastName: form.lastName,
        email: form.email,
        phone: phoneSplit.phone,
        phoneCountryCode: phoneSplit.phoneCountryCode,
        addressLine1: form.address.addressLine1 || '',
        addressLine2: form.address.addressLine2 || '',
        city: form.address.city || '',
        state: form.address.state || '',
        country: form.address.country || '',
        postalCode: form.address.postalCode || '',
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
      refreshLoyalty();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to adjust points');
    } finally {
      setAdjusting(false);
    }
  };

  const handleGrantGoodwill = async () => {
    const pts = parseInt(goodwillForm.points, 10);
    if (!pts || pts <= 0) { toast.error('Points must be a positive number'); return; }
    if (!goodwillForm.reason.trim()) { toast.error('A reason is required for goodwill credits'); return; }
    setGranting(true);
    try {
      await loyaltyV2.grantGoodwill(selectedBinge.id, {
        customerId: Number(id),
        points: pts,
        reason: goodwillForm.reason.trim(),
        bookingRef: goodwillForm.bookingRef.trim() || null,
      });
      setGoodwillForm({ points: '', reason: '', bookingRef: '' });
      toast.success(`Credited ${pts.toLocaleString()} goodwill points`);
      loyaltyV2.getGoodwillBudget(selectedBinge.id).then(setGoodwillBudget).catch(() => {});
    } catch (err) {
      toast.error(err.response?.data?.message || 'Goodwill credit failed');
    } finally {
      setGranting(false);
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
            <PhoneField
              label="Phone *"
              required
              value={form.phone}
              onChange={(v) => setForm({ ...form, phone: v })}
            />
          </div>
          <div style={{ gridColumn: '1 / -1' }}>
            <AddressFields
              legend="Address"
              description="All fields optional. Pick country first to unlock states and cities."
              value={form.address}
              onChange={(addr) => setForm({ ...form, address: addr })}
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

      {/* Loyalty Management — super admin: full wallet ops; binge admin: goodwill sanction */}
      <div className="card" style={{ padding: '1.5rem', marginTop: '1.5rem' }}>
        <h2 style={{ fontSize: '1.1rem', marginBottom: '1rem' }}>
          Loyalty
          {loyalty?.memberNumber && (
            <span style={{ fontSize: '0.8rem', fontWeight: 500, color: 'var(--text-muted)', marginLeft: 8 }}>
              Member #{loyalty.memberNumber}
            </span>
          )}
        </h2>

        {isSuperAdmin && (loyalty ? (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem', marginBottom: '1.5rem' }}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--primary-text)' }}>{(loyalty.pointsBalance ?? loyalty.currentBalance ?? 0).toLocaleString()}</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Balance</div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--primary-text)' }}>{loyalty.tierCode || loyalty.tierLevel || 'BRONZE'}</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Tier</div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--primary-text)' }}>{(loyalty.pointsEarnedLifetime ?? loyalty.totalPointsEarned ?? 0).toLocaleString()}</div>
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
            {ledger.length > 0 && (
              <div style={{ marginTop: '1rem' }}>
                <h3 style={{ fontSize: '0.9rem', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Recent activity</h3>
                <div style={{ maxHeight: '220px', overflowY: 'auto' }}>
                  {ledger.map((t) => (
                    <div key={t.id} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, padding: '0.4rem 0', borderBottom: '1px solid var(--border)', fontSize: '0.85rem' }}>
                      <span style={{ color: 'var(--text-secondary)' }}>
                        {t.description || t.reasonCode || t.entryType}
                        <span style={{ opacity: 0.6 }}> · {t.entryType}{t.bookingRef ? ` · ${t.bookingRef}` : ''}</span>
                      </span>
                      <span style={{ fontWeight: 600, whiteSpace: 'nowrap', color: t.pointsDelta > 0 ? 'var(--success)' : 'var(--danger)' }}>
                        {t.pointsDelta > 0 ? '+' : ''}{Number(t.pointsDelta).toLocaleString()}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        ) : (
          <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>No loyalty account yet — points will be created on their first booking.</p>
        ))}

        {!isSuperAdmin && (
          goodwillBudget?.enabled ? (
            <>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '0.75rem' }}>
                Service recovery: credit points to this customer after a disappointing experience at{' '}
                <strong>{selectedBinge?.name || 'your venue'}</strong>. Remaining budget this month:{' '}
                <strong>{Number(goodwillBudget.remaining).toLocaleString()}</strong> of{' '}
                {Number(goodwillBudget.monthlyCapPoints).toLocaleString()} points.
              </p>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr 1fr auto', gap: '0.75rem', alignItems: 'end' }}>
                <div>
                  <label style={labelStyle}>Points</label>
                  <input type="number" min="1" style={inputStyle} placeholder="e.g. 500"
                    value={goodwillForm.points} onChange={e => setGoodwillForm(prev => ({ ...prev, points: e.target.value }))} />
                </div>
                <div>
                  <label style={labelStyle}>Reason *</label>
                  <input style={inputStyle} placeholder="e.g. Projector failure during event"
                    value={goodwillForm.reason} onChange={e => setGoodwillForm(prev => ({ ...prev, reason: e.target.value }))} />
                </div>
                <div>
                  <label style={labelStyle}>Booking ref</label>
                  <input style={inputStyle} placeholder="optional"
                    value={goodwillForm.bookingRef} onChange={e => setGoodwillForm(prev => ({ ...prev, bookingRef: e.target.value }))} />
                </div>
                <button className="btn btn-primary" disabled={granting} onClick={handleGrantGoodwill}>
                  {granting ? 'Crediting...' : 'Credit points'}
                </button>
              </div>
            </>
          ) : (
            <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
              Goodwill credits are not enabled for your venue. A super admin can grant the permission
              (with a monthly budget) in the Loyalty Center → Binges tab.
            </p>
          )
        )}
      </div>
    </div>
  );
}
