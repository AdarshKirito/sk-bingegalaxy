import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { authService, adminService } from '../../services/endpoints';
import { useAuth } from '../../context/AuthContext';
import { toast } from 'react-toastify';

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

  const handleCreateCustomer = async () => {
    if (!newCust.firstName.trim()) { toast.error('First name is required to create a customer'); return; }
    if (!newCust.email.trim()) { toast.error('Email is required to create a customer'); return; }
    if (newCust.email.trim() && !/\S+@\S+\.\S+/.test(newCust.email)) { toast.error('Please enter a valid email address'); return; }
    if (!newCust.password.trim()) { toast.error('Password is required so customer can sign in'); return; }
    if (newCust.password.length < 10) { toast.error('Password must be at least 10 characters'); return; }
    if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])/.test(newCust.password)) { toast.error('Password must include uppercase, lowercase, number, and special character'); return; }
    try {
      const res = await authService.adminCreateCustomer({
        firstName: newCust.firstName,
        lastName: newCust.lastName,
        email: newCust.email,
        phone: newCust.phone,
        password: newCust.password,
      });
      const created = res.data.data || res.data;
      setSelectedCustomer(created);
      setShowCreateForm(false);
      setNewCust({ firstName: '', lastName: '', email: '', phone: '', password: '' });
      toast.success('Customer created successfully');
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to create customer. Please try again.');
    }
  };

  return (
    <div className="booking-section">
      <h2>Select Customer</h2>
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
                <div><label style={labelStyle}>Phone</label><input style={inputStyle} value={newCust.phone} onChange={e => setNewCust({ ...newCust, phone: e.target.value })} aria-label="Phone" /></div>
                <div style={{ gridColumn: '1 / -1' }}><label style={labelStyle}>Password *</label><input type="password" style={inputStyle} value={newCust.password} onChange={e => setNewCust({ ...newCust, password: e.target.value })} aria-label="Password" placeholder="Minimum 10 characters" /></div>
              </div>
              <button className="btn btn-primary btn-sm" style={{ marginTop: '0.75rem' }} onClick={handleCreateCustomer}>Create Customer</button>
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
            Override pricing for this booking. Event and add-on prices will update in subsequent steps.
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
