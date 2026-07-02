import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { FiEye, FiEyeOff, FiRefreshCw, FiCopy } from 'react-icons/fi';
import { authService, adminService } from '../../services/endpoints';
import { useAuth } from '../../context/AuthContext';
import { toast } from 'react-toastify';

// Allowed characters mirror the backend AdminCreateCustomerRequest password
// policy. The generated value is guaranteed to satisfy it (one lower, one upper,
// one digit, one special from the required set) so the request never 400s.
const PW_LOWER = 'abcdefghijkmnpqrstuvwxyz';
const PW_UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
const PW_DIGIT = '23456789';
const PW_SPECIAL = '@$!%*?&#';
function generateTempPassword(length = 16) {
  const all = PW_LOWER + PW_UPPER + PW_DIGIT + PW_SPECIAL;
  const pick = (set) => set[Math.floor((window.crypto?.getRandomValues(new Uint32Array(1))[0] ?? Math.random() * 2 ** 32) % set.length)];
  const chars = [pick(PW_LOWER), pick(PW_UPPER), pick(PW_DIGIT), pick(PW_SPECIAL)];
  while (chars.length < length) chars.push(pick(all));
  // Fisher–Yates shuffle so the guaranteed-class chars aren't always first.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = Math.floor((window.crypto?.getRandomValues(new Uint32Array(1))[0] ?? Math.random() * 2 ** 32) % (i + 1));
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

export default function StepCustomer({
  selectedCustomer, setSelectedCustomer,
  activeRateCodes, selectedRateCodeId, setSelectedRateCodeId,
  resolvedPricing, setResolvedPricing,
  onNext, onCancel,
}) {
  const navigate = useNavigate();
  const { isSuperAdmin } = useAuth();
  const [custQuery, setCustQuery] = useState('');
  const [custResults, setCustResults] = useState([]);
  const [custSearching, setCustSearching] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newCust, setNewCust] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '' });
  const [showPw, setShowPw] = useState(false);
  const [creating, setCreating] = useState(false);
  // After a successful create we surface the temp password to the admin (so they
  // can read it out) plus a reminder it was emailed + texted to the customer.
  const [tempNotice, setTempNotice] = useState(null);

  const inputStyle = {
    padding: '0.55rem 0.8rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)',
    background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.88rem', width: '100%',
  };
  const labelStyle = { fontWeight: 600, marginBottom: '0.4rem', display: 'block', fontSize: '0.85rem' };

  const handleCustSearch = useCallback(async (q) => {
    if (q.trim().length < 2) { setCustResults([]); return; }
    setCustSearching(true);
    try {
      const res = await authService.searchCustomers(q);
      // Server returns Page<UserDto> wrapped in ApiResponse — extract .content from the Page
      const payload = res.data.data;
      setCustResults(Array.isArray(payload) ? payload : (payload?.content ?? []));
    } catch { setCustResults([]); }
    setCustSearching(false);
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => handleCustSearch(custQuery), 400);
    return () => clearTimeout(timer);
  }, [custQuery, handleCustSearch]);

  const selectCustomer = (cust) => {
    setSelectedCustomer(cust);
    setCustQuery('');
    setCustResults([]);
    setShowCreateForm(false);
    setSelectedRateCodeId('');
    setResolvedPricing(null);
  };

  const handleGenerate = () => {
    setNewCust((c) => ({ ...c, password: generateTempPassword() }));
    setShowPw(true);
  };

  const copyTemp = async (value) => {
    try { await navigator.clipboard.writeText(value); toast.success('Temporary password copied'); }
    catch { toast.info('Copy not available — select and copy manually'); }
  };

  const handleCreateCustomer = async () => {
    if (!newCust.firstName.trim()) { toast.error('First name is required to create a customer'); return; }
    if (!newCust.email.trim()) { toast.error('Email is required to create a customer'); return; }
    if (newCust.email.trim() && !/\S+@\S+\.\S+/.test(newCust.email)) { toast.error('Please enter a valid email address'); return; }
    // The temp password is auto-generated; fall back to generating one if the
    // admin cleared the field, so we never send an empty/weak credential.
    const tempPassword = newCust.password.trim() || generateTempPassword();
    if (tempPassword.length < 10 || !/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])/.test(tempPassword)) {
      toast.error('Temporary password must be 10+ chars with upper, lower, number and a special character. Use Generate.');
      return;
    }
    setCreating(true);
    try {
      const res = await authService.adminCreateCustomer({
        firstName: newCust.firstName,
        lastName: newCust.lastName,
        email: newCust.email,
        phone: newCust.phone,
        password: tempPassword,
      });
      const created = res.data.data || res.data;
      setSelectedCustomer(created);
      setShowCreateForm(false);
      // Prefer the server-confirmed temp password; fall back to what we sent.
      setTempNotice({ email: created.email || newCust.email, password: created.temporaryPassword || tempPassword });
      setNewCust({ firstName: '', lastName: '', email: '', phone: '', password: '' });
      setShowPw(false);
      toast.success('Customer created — temporary password emailed & texted to them');
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to create customer. Please try again.');
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="booking-section">
      <h2>Select Customer</h2>
      {tempNotice && (
        <div className="card" style={{ padding: '1rem', marginBottom: '1rem', border: '1px solid var(--accent, #818cf8)', background: 'rgba(129,140,248,0.08)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '0.75rem' }}>
            <div>
              <strong>Temporary password for {tempNotice.email}</strong>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginTop: '0.4rem' }}>
                <code style={{ fontSize: '1rem', fontWeight: 700, letterSpacing: '0.05em' }}>{tempNotice.password}</code>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => copyTemp(tempNotice.password)} aria-label="Copy temporary password"><FiCopy /></button>
              </div>
              <p style={{ fontSize: '0.74rem', color: 'var(--text-muted)', marginTop: '0.5rem', lineHeight: 1.4, maxWidth: '46ch' }}>
                Also sent to the customer by email &amp; SMS. They'll be asked to change it on first login, and it only
                works for a couple of sign-ins before “Forgot password” is required. Note it down now — it won't be shown again.
              </p>
            </div>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => setTempNotice(null)} aria-label="Dismiss">✕</button>
          </div>
        </div>
      )}
      {selectedCustomer ? (
        <div className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem' }}>
          <div>
            <strong>{selectedCustomer.firstName} {selectedCustomer.lastName || ''}</strong><br />
            <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>{selectedCustomer.email}</span>
            {selectedCustomer.phone && <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}> | {selectedCustomer.phone}</span>}
          </div>
          <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
            {isSuperAdmin && <button className="btn btn-secondary btn-sm" onClick={() => navigate(`/admin/users-config/${selectedCustomer.id}`)} title="Edit customer details" style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem' }}>✏️</button>}
            <button className="btn btn-secondary btn-sm" onClick={() => { setSelectedCustomer(null); setSelectedRateCodeId(''); setResolvedPricing(null); }}>Change</button>
          </div>
        </div>
      ) : (
        <div className="card" style={{ padding: '1.25rem' }}>
          <div style={{ position: 'relative' }}>
            <input value={custQuery} onChange={e => setCustQuery(e.target.value)}
              style={inputStyle} placeholder="Search customer by name, email, or phone..." aria-label="Search customers" />
            {custSearching && <span style={{ position: 'absolute', right: '10px', top: '12px', color: 'var(--text-muted)', fontSize: '0.8rem' }}>Searching...</span>}
            {custResults.length > 0 && (
              <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)', maxHeight: '200px', overflowY: 'auto', zIndex: 10 }} role="listbox">
                {custResults.map(c => (
                  <div key={c.id} onClick={() => selectCustomer(c)} role="option" tabIndex={0}
                    onKeyDown={e => e.key === 'Enter' && selectCustomer(c)}
                    style={{ padding: '0.6rem 0.8rem', cursor: 'pointer', borderBottom: '1px solid var(--border)', fontSize: '0.85rem' }}>
                    <strong>{c.firstName} {c.lastName || ''}</strong> <span style={{ color: 'var(--text-muted)' }}>— {c.email}</span>
                    {c.phone && <span style={{ color: 'var(--text-muted)' }}> | {c.phone}</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
          <div style={{ marginTop: '0.75rem' }}>
            <button className="btn btn-secondary btn-sm" onClick={() => setShowCreateForm(!showCreateForm)}>
              {showCreateForm ? 'Cancel' : '+ Create New Customer'}
            </button>
          </div>
          {showCreateForm && (
            <div style={{ marginTop: '0.75rem', padding: '1rem', background: 'var(--bg-input)', borderRadius: 'var(--radius-sm)' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
                <div><label style={labelStyle}>First Name *</label><input style={inputStyle} value={newCust.firstName} onChange={e => setNewCust({ ...newCust, firstName: e.target.value })} aria-label="First name" /></div>
                <div><label style={labelStyle}>Last Name</label><input style={inputStyle} value={newCust.lastName} onChange={e => setNewCust({ ...newCust, lastName: e.target.value })} aria-label="Last name" /></div>
                <div><label style={labelStyle}>Email *</label><input type="email" style={inputStyle} value={newCust.email} onChange={e => setNewCust({ ...newCust, email: e.target.value })} aria-label="Email" /></div>
                <div><label style={labelStyle}>Phone</label><input style={inputStyle} value={newCust.phone} onChange={e => setNewCust({ ...newCust, phone: e.target.value })} aria-label="Phone" placeholder="For SMS delivery of the temp password" /></div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <label style={labelStyle}>Temporary Password *</label>
                  <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center' }}>
                    <input type={showPw ? 'text' : 'password'} style={{ ...inputStyle, fontFamily: 'monospace', letterSpacing: '0.04em' }}
                      value={newCust.password} onChange={e => setNewCust({ ...newCust, password: e.target.value })}
                      aria-label="Temporary password" placeholder="Click Generate" autoComplete="off" />
                    <button type="button" className="btn btn-secondary btn-sm" title={showPw ? 'Hide' : 'Show'}
                      onClick={() => setShowPw(v => !v)} style={{ flexShrink: 0 }} aria-label={showPw ? 'Hide password' : 'Show password'}>
                      {showPw ? <FiEyeOff /> : <FiEye />}
                    </button>
                    {newCust.password && (
                      <button type="button" className="btn btn-secondary btn-sm" title="Copy"
                        onClick={() => copyTemp(newCust.password)} style={{ flexShrink: 0 }} aria-label="Copy password"><FiCopy /></button>
                    )}
                    <button type="button" className="btn btn-secondary btn-sm" onClick={handleGenerate}
                      style={{ flexShrink: 0, display: 'inline-flex', alignItems: 'center', gap: '0.3rem' }}>
                      <FiRefreshCw /> Generate
                    </button>
                  </div>
                  <p style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: '0.4rem', lineHeight: 1.4 }}>
                    This is a one-time password. It's emailed &amp; texted to the customer, who is asked to change it on
                    first login. It only works for a couple of logins — after that they use “Forgot password”.
                  </p>
                </div>
              </div>
              <button className="btn btn-primary btn-sm" style={{ marginTop: '0.75rem' }} onClick={handleCreateCustomer} disabled={creating}>
                {creating ? 'Creating…' : 'Create Customer'}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Rate Code Override */}
      {selectedCustomer && activeRateCodes.length > 0 && (
        <div className="card" style={{ padding: '1rem', marginTop: '1rem' }}>
          <label style={labelStyle} htmlFor="rate-code-select">Apply Rate Code</label>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <select id="rate-code-select"
              value={selectedRateCodeId}
              onChange={e => {
                const val = e.target.value;
                setSelectedRateCodeId(val);
                if (!val) {
                  adminService.resolveCustomerPricing(selectedCustomer.id)
                    .then(res => setResolvedPricing(res.data.data || null))
                    .catch(() => setResolvedPricing(null));
                }
              }}
              style={{ ...inputStyle, maxWidth: '300px' }}>
              <option value="">Customer Default</option>
              {activeRateCodes.map(rc => (
                <option key={rc.id} value={rc.id}>{rc.name}{rc.description ? ` — ${rc.description}` : ''}</option>
              ))}
            </select>
            {resolvedPricing?.pricingSource && resolvedPricing.pricingSource !== 'DEFAULT' && (
              <span style={{ fontSize: '0.78rem', color: '#818cf8', fontWeight: 600 }}>
                {resolvedPricing.pricingSource === 'RATE_CODE' ? `📋 ${resolvedPricing.rateCodeName}` : '👤 Custom Pricing'}
              </span>
            )}
          </div>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.35rem' }}>
            Apply a rate plan for this booking. If this customer already has a custom
            price for an event or add-on, their personal price wins; the rate plan
            applies only to items without a custom override.
          </p>
        </div>
      )}

      <div className="booking-nav">
        {onCancel && <button className="btn btn-secondary" onClick={onCancel}>Cancel</button>}
        <button className="btn btn-primary" onClick={() => {
          if (!selectedCustomer) { toast.error('Please select or create a customer before proceeding'); return; }
          onNext();
        }}>
          Next: Choose Event Type
        </button>
      </div>
    </div>
  );
}
