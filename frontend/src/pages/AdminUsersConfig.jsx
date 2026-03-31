import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { adminService, authService } from '../services/endpoints';
import { toast } from 'react-toastify';

export default function AdminUsersConfig() {
  const { userId } = useParams();
  const navigate = useNavigate();

  /* ── shared reference data ── */
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [rateCodes, setRateCodes] = useState([]);
  const [loading, setLoading] = useState(true);

  /* ── section toggle ── */
  const [section, setSection] = useState('users'); // users | rate-codes

  /* ── users section ── */
  const [allCustomers, setAllCustomers] = useState([]);
  const [filterText, setFilterText] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [customerTab, setCustomerTab] = useState('details'); // details | pricing

  /* ── customer edit (details) ── */
  const [editForm, setEditForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [saving, setSaving] = useState(false);

  /* ── customer pricing ── */
  const [custPricing, setCustPricing] = useState(null);

  /* ── rate codes section ── */
  const [showRCForm, setShowRCForm] = useState(false);
  const [editingRC, setEditingRC] = useState(null);
  const [rcExpandedId, setRcExpandedId] = useState(null);
  const [rcForm, setRcForm] = useState({ name: '', description: '', eventPricings: [], addonPricings: [] });

  /* ── initial load ── */
  useEffect(() => {
    Promise.all([
      adminService.getAllEventTypes(),
      adminService.getAllAddOns(),
      adminService.getRateCodes(),
      authService.getAllCustomers(),
    ])
      .then(([etRes, aoRes, rcRes, custRes]) => {
        setEventTypes(etRes.data.data || []);
        setAddOns(aoRes.data.data || []);
        setRateCodes(rcRes.data.data || []);
        setAllCustomers(custRes.data.data || custRes.data || []);
      })
      .catch(() => toast.error('Failed to load data'))
      .finally(() => setLoading(false));
  }, []);

  /* ── if navigated with userId param, auto-select ── */
  useEffect(() => {
    if (userId && allCustomers.length > 0) {
      const c = allCustomers.find(c => String(c.id) === String(userId));
      if (c) selectCustomer(c);
    }
  }, [userId, allCustomers]);

  /* ── helpers ── */
  const inputStyle = { padding: '0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '100%' };
  const smallInputStyle = { ...inputStyle, width: '110px', textAlign: 'right' };
  const labelStyle = { fontWeight: 600, fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '0.3rem', display: 'block' };
  const tabBtnStyle = (active) => ({
    padding: '0.5rem 1.2rem', fontSize: '0.85rem', fontWeight: 600, cursor: 'pointer',
    border: 'none', borderBottom: active ? '2px solid var(--accent)' : '2px solid transparent',
    background: 'transparent', color: active ? 'var(--accent)' : 'var(--text-secondary)',
  });

  /* ── select customer ── */
  const selectCustomer = async (cust) => {
    setSelectedCustomer(cust);
    setCustomerTab('details');
    setEditForm({ firstName: cust.firstName || '', lastName: cust.lastName || '', email: cust.email || '', phone: cust.phone || '', password: '' });
    setShowPassword(false);
    setCustPricing(null);
    // Navigate to keep URL in sync (without reloading)
    if (String(cust.id) !== String(userId)) {
      navigate(`/admin/users-config/${cust.id}`, { replace: true });
    }
  };

  /* ── load customer pricing on demand ── */
  const loadCustomerPricing = async () => {
    if (!selectedCustomer) return;
    try {
      const res = await adminService.getCustomerPricing(selectedCustomer.id);
      const p = res.data.data || res.data;
      const epMap = {};
      (p.eventPricings || []).forEach(ep => { epMap[ep.eventTypeId] = ep; });
      const apMap = {};
      (p.addonPricings || []).forEach(ap => { apMap[ap.addOnId] = ap; });
      setCustPricing({
        rateCodeId: p.rateCodeId || '',
        rateCodeName: p.rateCodeName || '',
        eventPricings: eventTypes.map(et => {
          const ex = epMap[et.id];
          return { eventTypeId: et.id, eventTypeName: et.name, basePrice: ex ? String(ex.basePrice) : '', hourlyRate: ex ? String(ex.hourlyRate) : '', pricePerGuest: ex ? String(ex.pricePerGuest) : '' };
        }),
        addonPricings: addOns.map(a => {
          const ex = apMap[a.id];
          return { addOnId: a.id, addOnName: a.name, price: ex ? String(ex.price) : '' };
        }),
        dirty: false,
      });
    } catch { toast.error('Failed to load pricing'); }
  };

  useEffect(() => {
    if (customerTab === 'pricing' && selectedCustomer && !custPricing) {
      loadCustomerPricing();
    }
  }, [customerTab, selectedCustomer]);

  /* ── save customer details ── */
  const handleSaveDetails = async () => {
    if (!editForm.firstName.trim()) { toast.error('First name is required'); return; }
    if (!editForm.lastName.trim()) { toast.error('Last name is required'); return; }
    if (!editForm.email.trim()) { toast.error('Email is required'); return; }
    if (!editForm.phone.trim()) { toast.error('Phone is required'); return; }
    setSaving(true);
    try {
      const payload = { firstName: editForm.firstName, lastName: editForm.lastName, email: editForm.email, phone: editForm.phone };
      if (editForm.password.trim()) payload.password = editForm.password.trim();
      await authService.adminUpdateCustomer(selectedCustomer.id, payload);
      toast.success('Customer updated');
      // refresh in list
      setAllCustomers(prev => prev.map(c => c.id === selectedCustomer.id ? { ...c, ...payload } : c));
      setSelectedCustomer(prev => ({ ...prev, ...payload }));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to update');
    } finally { setSaving(false); }
  };

  /* ── save customer pricing ── */
  const handleSavePricing = async () => {
    if (!selectedCustomer || !custPricing) return;
    const payload = {
      customerId: selectedCustomer.id,
      rateCodeId: custPricing.rateCodeId || null,
      eventPricings: custPricing.eventPricings
        .filter(ep => ep.basePrice !== '' || ep.hourlyRate !== '' || ep.pricePerGuest !== '')
        .map(ep => ({ eventTypeId: ep.eventTypeId, basePrice: Number(ep.basePrice) || 0, hourlyRate: Number(ep.hourlyRate) || 0, pricePerGuest: Number(ep.pricePerGuest) || 0 })),
      addonPricings: custPricing.addonPricings
        .filter(ap => ap.price !== '')
        .map(ap => ({ addOnId: ap.addOnId, price: Number(ap.price) || 0 })),
    };
    try {
      await adminService.saveCustomerPricing(payload);
      toast.success(`Pricing saved for ${selectedCustomer.firstName} ${selectedCustomer.lastName}`);
      setCustPricing(prev => ({ ...prev, dirty: false }));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    }
  };

  /* ── rate code helpers ── */
  const reloadRateCodes = () => {
    adminService.getRateCodes().then(res => setRateCodes(res.data.data || [])).catch(() => {});
  };

  const openRCCreate = () => {
    setEditingRC(null);
    setRcForm({
      name: '', description: '',
      eventPricings: eventTypes.map(et => ({ eventTypeId: et.id, eventTypeName: et.name, basePrice: '', hourlyRate: '', pricePerGuest: '' })),
      addonPricings: addOns.map(a => ({ addOnId: a.id, addOnName: a.name, price: '' })),
    });
    setShowRCForm(true);
  };

  const openRCEdit = (rc) => {
    setEditingRC(rc);
    const epMap = {};
    (rc.eventPricings || []).forEach(ep => { epMap[ep.eventTypeId] = ep; });
    const apMap = {};
    (rc.addonPricings || []).forEach(ap => { apMap[ap.addOnId] = ap; });
    setRcForm({
      name: rc.name, description: rc.description || '',
      eventPricings: eventTypes.map(et => {
        const ex = epMap[et.id];
        return { eventTypeId: et.id, eventTypeName: et.name, basePrice: ex ? ex.basePrice : '', hourlyRate: ex ? ex.hourlyRate : '', pricePerGuest: ex ? ex.pricePerGuest : '' };
      }),
      addonPricings: addOns.map(a => {
        const ex = apMap[a.id];
        return { addOnId: a.id, addOnName: a.name, price: ex ? ex.price : '' };
      }),
    });
    setShowRCForm(true);
  };

  const handleSaveRC = async () => {
    if (!rcForm.name.trim()) { toast.error('Name is required'); return; }
    const payload = {
      name: rcForm.name.trim(), description: rcForm.description.trim(),
      eventPricings: rcForm.eventPricings
        .filter(ep => ep.basePrice !== '' || ep.hourlyRate !== '' || ep.pricePerGuest !== '')
        .map(ep => ({ eventTypeId: ep.eventTypeId, basePrice: Number(ep.basePrice) || 0, hourlyRate: Number(ep.hourlyRate) || 0, pricePerGuest: Number(ep.pricePerGuest) || 0 })),
      addonPricings: rcForm.addonPricings
        .filter(ap => ap.price !== '')
        .map(ap => ({ addOnId: ap.addOnId, price: Number(ap.price) || 0 })),
    };
    try {
      if (editingRC) {
        await adminService.updateRateCode(editingRC.id, payload);
        toast.success('Rate code updated');
      } else {
        await adminService.createRateCode(payload);
        toast.success('Rate code created');
      }
      setShowRCForm(false);
      reloadRateCodes();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    }
  };

  const handleToggleRC = async (id) => {
    try {
      await adminService.toggleRateCode(id);
      toast.success('Rate code toggled');
      reloadRateCodes();
    } catch { toast.error('Toggle failed'); }
  };

  /* ── filtered customer list ── */
  const filteredCustomers = filterText.trim()
    ? allCustomers.filter(c => {
        const q = filterText.toLowerCase();
        return (c.firstName + ' ' + c.lastName).toLowerCase().includes(q) ||
          (c.email || '').toLowerCase().includes(q) ||
          (c.phone || '').includes(q);
      })
    : allCustomers;

  if (loading) return <div className="container"><div className="loading"><div className="spinner"></div></div></div>;

  /* ═══════════════════════════════════════════════════
     RENDER
     ═══════════════════════════════════════════════════ */
  return (
    <div className="container" style={{ maxWidth: '1200px', margin: '0 auto' }}>
      <div className="page-header" style={{ marginBottom: '1rem' }}>
        <h1>Users & Config</h1>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>Manage customers, pricing, and rate codes in one place</p>
      </div>

      {/* ── top-level section tabs ── */}
      <div style={{ display: 'flex', gap: 0, borderBottom: '1px solid var(--border)', marginBottom: '1.25rem' }}>
        <button style={tabBtnStyle(section === 'users')} onClick={() => setSection('users')}>Users</button>
        <button style={tabBtnStyle(section === 'rate-codes')} onClick={() => setSection('rate-codes')}>Rate Codes</button>
      </div>

      {/* ════════════════════  USERS SECTION  ════════════════════ */}
      {section === 'users' && (
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start' }}>
          {/* ── left: customer list ── */}
          <div style={{ width: '340px', flexShrink: 0 }}>
            <div className="card" style={{ padding: '0.75rem' }}>
              <input style={{ ...inputStyle, marginBottom: '0.5rem' }} value={filterText}
                onChange={e => setFilterText(e.target.value)} placeholder="Filter users by name, email, phone..." />
              <div style={{ maxHeight: '65vh', overflowY: 'auto' }}>
                {filteredCustomers.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '1rem', color: 'var(--text-muted)', fontSize: '0.85rem' }}>No customers found</div>
                ) : filteredCustomers.map(c => (
                  <div key={c.id}
                    onClick={() => selectCustomer(c)}
                    style={{
                      padding: '0.55rem 0.65rem', cursor: 'pointer', borderRadius: 'var(--radius-sm)',
                      marginBottom: '0.25rem',
                      background: selectedCustomer?.id === c.id ? 'rgba(99,102,241,0.15)' : 'transparent',
                      borderLeft: selectedCustomer?.id === c.id ? '3px solid var(--accent)' : '3px solid transparent',
                    }}>
                    <div style={{ fontWeight: 600, fontSize: '0.85rem' }}>{c.firstName} {c.lastName}</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{c.email} • {c.phone}</div>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* ── right: customer detail / pricing ── */}
          <div style={{ flex: 1, minWidth: 0 }}>
            {!selectedCustomer ? (
              <div className="card" style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-muted)' }}>
                Select a customer from the list to view details or manage pricing.
              </div>
            ) : (
              <>
                {/* sub-tabs: Details | Pricing */}
                <div style={{ display: 'flex', gap: 0, borderBottom: '1px solid var(--border)', marginBottom: '1rem' }}>
                  <button style={tabBtnStyle(customerTab === 'details')} onClick={() => setCustomerTab('details')}>Details</button>
                  <button style={tabBtnStyle(customerTab === 'pricing')} onClick={() => setCustomerTab('pricing')}>Pricing</button>
                </div>

                {/* ── Details tab ── */}
                {customerTab === 'details' && (
                  <div className="card" style={{ padding: '1.5rem' }}>
                    <h3 style={{ margin: '0 0 1rem', fontSize: '1rem' }}>Edit Customer — {selectedCustomer.firstName} {selectedCustomer.lastName}</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                      <div>
                        <label style={labelStyle}>First Name *</label>
                        <input style={inputStyle} value={editForm.firstName} onChange={e => setEditForm({ ...editForm, firstName: e.target.value })} />
                      </div>
                      <div>
                        <label style={labelStyle}>Last Name *</label>
                        <input style={inputStyle} value={editForm.lastName} onChange={e => setEditForm({ ...editForm, lastName: e.target.value })} />
                      </div>
                      <div style={{ gridColumn: '1 / -1' }}>
                        <label style={labelStyle}>Email *</label>
                        <input type="email" style={inputStyle} value={editForm.email} onChange={e => setEditForm({ ...editForm, email: e.target.value })} />
                      </div>
                      <div style={{ gridColumn: '1 / -1' }}>
                        <label style={labelStyle}>Phone *</label>
                        <input style={inputStyle} value={editForm.phone} onChange={e => setEditForm({ ...editForm, phone: e.target.value })} />
                      </div>
                      <div style={{ gridColumn: '1 / -1' }}>
                        <label style={labelStyle}>Change Password</label>
                        <div style={{ position: 'relative' }}>
                          <input type={showPassword ? 'text' : 'password'} style={{ ...inputStyle, paddingRight: '3rem' }}
                            value={editForm.password} onChange={e => setEditForm({ ...editForm, password: e.target.value })}
                            placeholder="Enter new password to change" autoComplete="off" />
                          <button type="button" onClick={() => setShowPassword(!showPassword)}
                            style={{ position: 'absolute', right: '0.5rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', fontSize: '0.85rem', padding: '0.2rem 0.4rem' }}>
                            {showPassword ? '🙈 Hide' : '👁️ Show'}
                          </button>
                        </div>
                        <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block', marginTop: '0.3rem' }}>
                          Leave blank to keep current password
                        </span>
                      </div>
                    </div>
                    <div style={{ marginTop: '1.5rem', display: 'flex', justifyContent: 'flex-end' }}>
                      <button className="btn btn-primary" onClick={handleSaveDetails} disabled={saving}>
                        {saving ? 'Saving...' : 'Save Changes'}
                      </button>
                    </div>
                  </div>
                )}

                {/* ── Pricing tab ── */}
                {customerTab === 'pricing' && (
                  <div className="card" style={{ padding: '1.5rem' }}>
                    <h3 style={{ margin: '0 0 1rem', fontSize: '1rem' }}>Pricing — {selectedCustomer.firstName} {selectedCustomer.lastName}</h3>
                    {!custPricing ? (
                      <div style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>Loading pricing...</div>
                    ) : (
                      <>
                        {/* Rate code assignment */}
                        <div style={{ marginBottom: '1rem' }}>
                          <label style={labelStyle}>Assigned Rate Code</label>
                          <select style={{ ...inputStyle, width: '250px' }}
                            value={custPricing.rateCodeId}
                            onChange={e => setCustPricing(prev => ({ ...prev, rateCodeId: e.target.value, dirty: true }))}>
                            <option value="">None (Default Pricing)</option>
                            {rateCodes.filter(r => r.active).map(rc => <option key={rc.id} value={rc.id}>{rc.name}</option>)}
                          </select>
                          <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block', marginTop: '0.25rem' }}>
                            Customer-specific overrides below take priority over the rate code.
                          </span>
                        </div>

                        {/* Event type overrides */}
                        <h4 style={{ fontSize: '0.85rem', marginBottom: '0.5rem' }}>Event Type Overrides</h4>
                        <div style={{ overflowX: 'auto' }}>
                          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem', marginBottom: '1rem' }}>
                            <thead>
                              <tr style={{ borderBottom: '2px solid var(--border)' }}>
                                <th style={{ textAlign: 'left', padding: '0.4rem' }}>Event Type</th>
                                <th style={{ textAlign: 'right', padding: '0.4rem' }}>Base Price</th>
                                <th style={{ textAlign: 'right', padding: '0.4rem' }}>Hourly Rate</th>
                                <th style={{ textAlign: 'right', padding: '0.4rem' }}>Price/Guest</th>
                              </tr>
                            </thead>
                            <tbody>
                              {custPricing.eventPricings.map((ep, i) => (
                                <tr key={ep.eventTypeId} style={{ borderBottom: '1px solid var(--border)' }}>
                                  <td style={{ padding: '0.4rem' }}>{ep.eventTypeName}</td>
                                  <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                    <input type="number" step="0.01" style={smallInputStyle} value={ep.basePrice}
                                      onChange={e => {
                                        const arr = [...custPricing.eventPricings]; arr[i] = { ...arr[i], basePrice: e.target.value };
                                        setCustPricing(prev => ({ ...prev, eventPricings: arr, dirty: true }));
                                      }} />
                                  </td>
                                  <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                    <input type="number" step="0.01" style={smallInputStyle} value={ep.hourlyRate}
                                      onChange={e => {
                                        const arr = [...custPricing.eventPricings]; arr[i] = { ...arr[i], hourlyRate: e.target.value };
                                        setCustPricing(prev => ({ ...prev, eventPricings: arr, dirty: true }));
                                      }} />
                                  </td>
                                  <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                    <input type="number" step="0.01" style={smallInputStyle} value={ep.pricePerGuest}
                                      onChange={e => {
                                        const arr = [...custPricing.eventPricings]; arr[i] = { ...arr[i], pricePerGuest: e.target.value };
                                        setCustPricing(prev => ({ ...prev, eventPricings: arr, dirty: true }));
                                      }} />
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>

                        {/* Add-on overrides */}
                        <h4 style={{ fontSize: '0.85rem', marginBottom: '0.5rem' }}>Add-On Overrides</h4>
                        <div style={{ overflowX: 'auto' }}>
                          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem', marginBottom: '1rem' }}>
                            <thead>
                              <tr style={{ borderBottom: '2px solid var(--border)' }}>
                                <th style={{ textAlign: 'left', padding: '0.4rem' }}>Add-On</th>
                                <th style={{ textAlign: 'right', padding: '0.4rem' }}>Price</th>
                              </tr>
                            </thead>
                            <tbody>
                              {custPricing.addonPricings.map((ap, i) => (
                                <tr key={ap.addOnId} style={{ borderBottom: '1px solid var(--border)' }}>
                                  <td style={{ padding: '0.4rem' }}>{ap.addOnName}</td>
                                  <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                    <input type="number" step="0.01" style={smallInputStyle} value={ap.price}
                                      onChange={e => {
                                        const arr = [...custPricing.addonPricings]; arr[i] = { ...arr[i], price: e.target.value };
                                        setCustPricing(prev => ({ ...prev, addonPricings: arr, dirty: true }));
                                      }} />
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>

                        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
                          {custPricing.dirty && <span style={{ fontSize: '0.75rem', color: 'var(--warning)', alignSelf: 'center' }}>• unsaved changes</span>}
                          <button className="btn btn-primary" onClick={handleSavePricing} disabled={!custPricing.dirty}>Save Pricing</button>
                        </div>
                      </>
                    )}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}

      {/* ════════════════════  RATE CODES SECTION  ════════════════════ */}
      {section === 'rate-codes' && !showRCForm && (
        <div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.75rem' }}>
            <button className="btn btn-primary" onClick={openRCCreate}>+ New Rate Code</button>
          </div>
          {rateCodes.length === 0 ? (
            <div className="card" style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>No rate codes yet.</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              {rateCodes.map(rc => (
                <div key={rc.id} className="card" style={{ padding: '0.75rem 1rem', opacity: rc.active ? 1 : 0.5 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div style={{ cursor: 'pointer', flex: 1 }} onClick={() => setRcExpandedId(rcExpandedId === rc.id ? null : rc.id)}>
                      <span style={{ fontWeight: 600 }}>{rc.name}</span>
                      {rc.description && <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginLeft: '0.5rem' }}>— {rc.description}</span>}
                      <span style={{ marginLeft: '0.5rem', fontSize: '0.7rem', padding: '0.1rem 0.4rem', borderRadius: '999px', background: rc.active ? 'rgba(34,197,94,0.15)' : 'rgba(239,68,68,0.15)', color: rc.active ? '#22c55e' : '#ef4444' }}>
                        {rc.active ? 'Active' : 'Inactive'}
                      </span>
                    </div>
                    <div style={{ display: 'flex', gap: '0.35rem' }}>
                      <button className="btn btn-secondary" style={{ fontSize: '0.75rem', padding: '0.2rem 0.5rem' }} onClick={() => openRCEdit(rc)}>Edit</button>
                      <button className="btn btn-secondary" style={{ fontSize: '0.75rem', padding: '0.2rem 0.5rem' }} onClick={() => handleToggleRC(rc.id)}>
                        {rc.active ? 'Deactivate' : 'Activate'}
                      </button>
                    </div>
                  </div>
                  {rcExpandedId === rc.id && (
                    <div style={{ marginTop: '0.75rem', fontSize: '0.8rem', borderTop: '1px solid var(--border)', paddingTop: '0.5rem' }}>
                      {(rc.eventPricings || []).length > 0 && (
                        <>
                          <strong>Events:</strong>
                          <ul style={{ margin: '0.25rem 0 0.5rem 1.25rem', padding: 0 }}>
                            {rc.eventPricings.map(ep => (
                              <li key={ep.eventTypeId}>{ep.eventTypeName}: ₹{ep.basePrice} base / ₹{ep.hourlyRate}/hr / ₹{ep.pricePerGuest}/guest</li>
                            ))}
                          </ul>
                        </>
                      )}
                      {(rc.addonPricings || []).length > 0 && (
                        <>
                          <strong>Add-ons:</strong>
                          <ul style={{ margin: '0.25rem 0 0 1.25rem', padding: 0 }}>
                            {rc.addonPricings.map(ap => (
                              <li key={ap.addOnId}>{ap.addOnName}: ₹{ap.price}</li>
                            ))}
                          </ul>
                        </>
                      )}
                      {(rc.eventPricings || []).length === 0 && (rc.addonPricings || []).length === 0 && (
                        <span style={{ color: 'var(--text-muted)' }}>No custom pricing defined for this rate code.</span>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ── Rate code form (create/edit) ── */}
      {section === 'rate-codes' && showRCForm && (
        <div>
          <div className="page-header" style={{ marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1.1rem' }}>{editingRC ? 'Edit Rate Code' : 'Create Rate Code'}</h2>
          </div>
          <div className="card" style={{ padding: '1.5rem' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
              <div>
                <label style={labelStyle}>Rate Code Name *</label>
                <input style={inputStyle} value={rcForm.name} onChange={e => setRcForm({ ...rcForm, name: e.target.value })} placeholder="e.g. VIP, Corporate, Student" />
              </div>
              <div>
                <label style={labelStyle}>Description</label>
                <input style={inputStyle} value={rcForm.description} onChange={e => setRcForm({ ...rcForm, description: e.target.value })} placeholder="Optional description" />
              </div>
            </div>

            <h3 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Event Type Pricing</h3>
            <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }}>Leave blank to use default pricing.</p>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                <thead>
                  <tr style={{ borderBottom: '2px solid var(--border)' }}>
                    <th style={{ textAlign: 'left', padding: '0.5rem' }}>Event Type</th>
                    <th style={{ textAlign: 'right', padding: '0.5rem' }}>Base Price</th>
                    <th style={{ textAlign: 'right', padding: '0.5rem' }}>Hourly Rate</th>
                    <th style={{ textAlign: 'right', padding: '0.5rem' }}>Price/Guest</th>
                  </tr>
                </thead>
                <tbody>
                  {rcForm.eventPricings.map((ep, i) => {
                    const def = eventTypes.find(e => e.id === ep.eventTypeId);
                    return (
                      <tr key={ep.eventTypeId} style={{ borderBottom: '1px solid var(--border)' }}>
                        <td style={{ padding: '0.5rem' }}>
                          {ep.eventTypeName}
                          {def && <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block' }}>Default: ₹{def.basePrice} / ₹{def.hourlyRate}/hr / ₹{def.pricePerGuest}/guest</span>}
                        </td>
                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                          <input type="number" step="0.01" style={{ ...smallInputStyle, width: '120px' }} value={ep.basePrice} placeholder={def?.basePrice ?? ''}
                            onChange={e => { const arr = [...rcForm.eventPricings]; arr[i] = { ...arr[i], basePrice: e.target.value }; setRcForm({ ...rcForm, eventPricings: arr }); }} />
                        </td>
                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                          <input type="number" step="0.01" style={{ ...smallInputStyle, width: '120px' }} value={ep.hourlyRate} placeholder={def?.hourlyRate ?? ''}
                            onChange={e => { const arr = [...rcForm.eventPricings]; arr[i] = { ...arr[i], hourlyRate: e.target.value }; setRcForm({ ...rcForm, eventPricings: arr }); }} />
                        </td>
                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                          <input type="number" step="0.01" style={{ ...smallInputStyle, width: '120px' }} value={ep.pricePerGuest} placeholder={def?.pricePerGuest ?? ''}
                            onChange={e => { const arr = [...rcForm.eventPricings]; arr[i] = { ...arr[i], pricePerGuest: e.target.value }; setRcForm({ ...rcForm, eventPricings: arr }); }} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <h3 style={{ fontSize: '1rem', marginTop: '1.5rem', marginBottom: '0.75rem' }}>Add-On Pricing</h3>
            <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }}>Leave blank to use default pricing.</p>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                <thead>
                  <tr style={{ borderBottom: '2px solid var(--border)' }}>
                    <th style={{ textAlign: 'left', padding: '0.5rem' }}>Add-On</th>
                    <th style={{ textAlign: 'right', padding: '0.5rem' }}>Price</th>
                  </tr>
                </thead>
                <tbody>
                  {rcForm.addonPricings.map((ap, i) => {
                    const def = addOns.find(a => a.id === ap.addOnId);
                    return (
                      <tr key={ap.addOnId} style={{ borderBottom: '1px solid var(--border)' }}>
                        <td style={{ padding: '0.5rem' }}>
                          {ap.addOnName}
                          {def && <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block' }}>Default: ₹{def.price}</span>}
                        </td>
                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                          <input type="number" step="0.01" style={{ ...smallInputStyle, width: '120px' }} value={ap.price} placeholder={def?.price ?? ''}
                            onChange={e => { const arr = [...rcForm.addonPricings]; arr[i] = { ...arr[i], price: e.target.value }; setRcForm({ ...rcForm, addonPricings: arr }); }} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem', marginTop: '1.5rem' }}>
              <button className="btn btn-secondary" onClick={() => setShowRCForm(false)}>Cancel</button>
              <button className="btn btn-primary" onClick={handleSaveRC}>{editingRC ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
