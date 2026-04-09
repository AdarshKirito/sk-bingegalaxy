import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { adminService, authService } from '../services/endpoints';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import {
  FiChevronDown,
  FiChevronUp,
  FiCreditCard,
  FiEye,
  FiEyeOff,
  FiMapPin,
  FiPlus,
  FiSave,
  FiSearch,
  FiSettings,
  FiShield,
  FiTag,
  FiTrash2,
  FiUser,
  FiUsers,
} from 'react-icons/fi';
import './AdminPages.css';

export default function AdminUsersConfig() {
  const { userId } = useParams();
  const navigate = useNavigate();
  const { isSuperAdmin } = useAuth();

  /* ── shared reference data ── */
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [rateCodes, setRateCodes] = useState([]);
  const [loading, setLoading] = useState(true);

  /* ── section toggle ── */
  const [section, setSection] = useState('users'); // users | admins | rate-codes

  /* ── users section ── */
  const [allCustomers, setAllCustomers] = useState([]);
  const [filterText, setFilterText] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [customerTab, setCustomerTab] = useState('details'); // details | pricing

  /* ── admins section (super admin only) ── */
  const [allAdmins, setAllAdmins] = useState([]);
  const [adminFilterText, setAdminFilterText] = useState('');
  const [selectedAdmin, setSelectedAdmin] = useState(null);
  const [adminEditForm, setAdminEditForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '' });
  const [adminShowPassword, setAdminShowPassword] = useState(false);
  const [adminSaving, setAdminSaving] = useState(false);
  const [adminBinges, setAdminBinges] = useState([]);
  const [adminTab, setAdminTab] = useState('details'); // details | binges

  /* ── delete confirmation ── */
  const [deleteConfirm, setDeleteConfirm] = useState(null); // { id, name, role }

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

  const buildEmptyCustomerPricing = () => ({
    rateCodeId: '',
    rateCodeName: '',
    scopedProfile: false,
    eventPricings: eventTypes.map((eventType) => ({
      eventTypeId: eventType.id,
      eventTypeName: eventType.name,
      basePrice: '',
      hourlyRate: '',
      pricePerGuest: '',
    })),
    addonPricings: addOns.map((addOn) => ({
      addOnId: addOn.id,
      addOnName: addOn.name,
      price: '',
    })),
    dirty: false,
  });

  const hydrateCustomerPricing = (pricing) => {
    const epMap = {};
    (pricing.eventPricings || []).forEach((eventPricing) => { epMap[eventPricing.eventTypeId] = eventPricing; });
    const apMap = {};
    (pricing.addonPricings || []).forEach((addonPricing) => { apMap[addonPricing.addOnId] = addonPricing; });

    return {
      rateCodeId: pricing.rateCodeId || '',
      rateCodeName: pricing.rateCodeName || '',
      scopedProfile: Boolean(pricing.scopedProfile),
      eventPricings: eventTypes.map((eventType) => {
        const existing = epMap[eventType.id];
        return {
          eventTypeId: eventType.id,
          eventTypeName: eventType.name,
          basePrice: existing ? String(existing.basePrice) : '',
          hourlyRate: existing ? String(existing.hourlyRate) : '',
          pricePerGuest: existing ? String(existing.pricePerGuest) : '',
        };
      }),
      addonPricings: addOns.map((addOn) => {
        const existing = apMap[addOn.id];
        return {
          addOnId: addOn.id,
          addOnName: addOn.name,
          price: existing ? String(existing.price) : '',
        };
      }),
      dirty: false,
    };
  };

  /* ── initial load ── */
  useEffect(() => {
    const promises = [
      adminService.getAllEventTypes(),
      adminService.getAllAddOns(),
      adminService.getRateCodes(),
      authService.getAllCustomers(),
    ];
    if (isSuperAdmin) promises.push(authService.getAllAdmins());
    Promise.all(promises)
      .then(([etRes, aoRes, rcRes, custRes, adminRes]) => {
        setEventTypes(etRes.data.data || []);
        setAddOns(aoRes.data.data || []);
        setRateCodes(rcRes.data.data || []);
        const custData = custRes.data.data;
        setAllCustomers(Array.isArray(custData) ? custData : custData?.content || []);
        if (adminRes) setAllAdmins(adminRes.data.data || adminRes.data || []);
      })
      .catch(() => toast.error('Failed to load data'))
      .finally(() => setLoading(false));
  }, [isSuperAdmin]);

  /* ── if navigated with userId param, auto-select ── */
  useEffect(() => {
    if (userId && allCustomers.length > 0) {
      const c = allCustomers.find(c => String(c.id) === String(userId));
      if (c) selectCustomer(c);
    }
  }, [userId, allCustomers]);

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
      setCustPricing(hydrateCustomerPricing(p));
    } catch {
      toast.error('Failed to load pricing');
    }
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
      setCustPricing(prev => ({ ...prev, dirty: false, scopedProfile: true }));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    }
  };

  const handleDeletePricing = async () => {
    if (!selectedCustomer || !custPricing?.scopedProfile) return;
    if (!confirm(`Delete the pricing profile for "${selectedCustomer.firstName} ${selectedCustomer.lastName}" in this binge?`)) return;

    try {
      await adminService.deleteCustomerPricing(selectedCustomer.id);
      toast.success(`Pricing deleted for ${selectedCustomer.firstName} ${selectedCustomer.lastName}`);
      await loadCustomerPricing();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Delete failed');
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
    } catch (err) { toast.error(err.userMessage || 'Toggle failed'); }
  };

  const handleDeleteRC = async (rateCode) => {
    if (rateCode.active) {
      toast.error('Deactivate the rate code before deleting it');
      return;
    }
    if (!confirm(`Delete rate code "${rateCode.name}" permanently? This cannot be undone.`)) return;

    try {
      await adminService.deleteRateCode(rateCode.id);
      toast.success('Rate code deleted');
      reloadRateCodes();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Delete failed');
    }
  };

  /* ── delete user (super admin) ── */
  const handleDeleteUser = async () => {
    if (!deleteConfirm) return;
    try {
      await authService.deleteUser(deleteConfirm.id);
      toast.success(`${deleteConfirm.name} deleted`);
      if (deleteConfirm.role === 'admin') {
        setAllAdmins(prev => prev.filter(a => a.id !== deleteConfirm.id));
        if (selectedAdmin?.id === deleteConfirm.id) { setSelectedAdmin(null); setAdminBinges([]); }
      } else {
        setAllCustomers(prev => prev.filter(c => c.id !== deleteConfirm.id));
        if (selectedCustomer?.id === deleteConfirm.id) setSelectedCustomer(null);
      }
    } catch (err) {
      toast.error(err.response?.data?.message || 'Delete failed');
    } finally {
      setDeleteConfirm(null);
    }
  };

  /* ── select admin ── */
  const selectAdmin = async (admin) => {
    setSelectedAdmin(admin);
    setAdminTab('details');
    setAdminEditForm({ firstName: admin.firstName || '', lastName: admin.lastName || '', email: admin.email || '', phone: admin.phone || '', password: '' });
    setAdminShowPassword(false);
    setAdminBinges([]);
    // Pre-load binges
    try {
      const res = await adminService.getBingesByAdmin(admin.id);
      setAdminBinges(res.data.data || res.data || []);
    } catch { /* ignore – binges are optional */ }
  };

  /* ── save admin details ── */
  const handleSaveAdmin = async () => {
    if (!selectedAdmin) return;
    setAdminSaving(true);
    try {
      const payload = { firstName: adminEditForm.firstName, lastName: adminEditForm.lastName, email: adminEditForm.email, phone: adminEditForm.phone };
      if (adminEditForm.password?.trim()) payload.password = adminEditForm.password;
      const res = await authService.updateAdmin(selectedAdmin.id, payload);
      const updated = res.data.data || res.data;
      setAllAdmins(prev => prev.map(a => a.id === updated.id ? updated : a));
      setSelectedAdmin(updated);
      toast.success('Admin updated');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Update failed');
    } finally {
      setAdminSaving(false);
    }
  };

  /* ── filtered admin list ── */
  const filteredAdmins = adminFilterText.trim()
    ? allAdmins.filter(a => {
        const q = adminFilterText.toLowerCase();
        return (a.firstName + ' ' + a.lastName).toLowerCase().includes(q) ||
          (a.email || '').toLowerCase().includes(q) ||
          (a.phone || '').includes(q);
      })
    : allAdmins;

  /* ── filtered customer list ── */
  const filteredCustomers = filterText.trim()
    ? allCustomers.filter(c => {
        const q = filterText.toLowerCase();
        return (c.firstName + ' ' + c.lastName).toLowerCase().includes(q) ||
          (c.email || '').toLowerCase().includes(q) ||
          (c.phone || '').includes(q);
      })
    : allCustomers;

  if (loading) {
    return <div className="container"><div className="loading"><div className="spinner"></div></div></div>;
  }

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiSettings /> Admin workspace</span>
          <h1>Users & Config</h1>
          <p>Manage customers, admin access, and pricing templates in one premium control surface.</p>
        </div>
      </div>

      <div className="adm-tabs">
        <button className={`adm-tab${section === 'users' ? ' active' : ''}`} onClick={() => setSection('users')}>
          <FiUsers /> Users
        </button>
        {isSuperAdmin && (
          <button className={`adm-tab${section === 'admins' ? ' active' : ''}`} onClick={() => setSection('admins')}>
            <FiShield /> Admins
          </button>
        )}
        <button className={`adm-tab${section === 'rate-codes' ? ' active' : ''}`} onClick={() => setSection('rate-codes')}>
          <FiTag /> Rate Codes
        </button>
      </div>

      {section === 'users' && (
        <div className="adm-split-layout adm-split-layout-wide">
          <aside className="adm-panel-stack">
            <div className="adm-card adm-list-card">
              <div className="input-group" style={{ marginBottom: '0.75rem' }}>
                <label><FiSearch style={{ marginRight: 6, verticalAlign: -2 }} />Find customer</label>
                <input
                  value={filterText}
                  onChange={(e) => setFilterText(e.target.value)}
                  placeholder="Filter users by name, email, or phone..."
                />
              </div>
              <div className="adm-list">
                {filteredCustomers.length === 0 ? (
                  <div className="adm-empty compact">
                    <span className="adm-empty-icon"><FiUsers /></span>
                    <h3>No customers found</h3>
                    <p>Try a different name, email, or phone number.</p>
                  </div>
                ) : filteredCustomers.map((customer) => (
                  <button
                    type="button"
                    key={customer.id}
                    className={`adm-list-item${selectedCustomer?.id === customer.id ? ' active' : ''}`}
                    onClick={() => selectCustomer(customer)}
                  >
                    <span className="adm-list-item-title">{customer.firstName} {customer.lastName}</span>
                    <span className="adm-list-item-meta">{customer.email || 'No email'}</span>
                    <span className="adm-list-item-meta">{customer.phone || 'No phone'}</span>
                  </button>
                ))}
              </div>
            </div>
          </aside>

          <section className="adm-panel-stack">
            {!selectedCustomer ? (
              <div className="adm-empty">
                <span className="adm-empty-icon"><FiUser /></span>
                <h3>Select a customer</h3>
                <p>Choose a customer from the left to edit account details or pricing overrides.</p>
              </div>
            ) : (
              <>
                <div className="adm-summary">
                  <div className="adm-summary-copy">
                    <span className="adm-summary-title">{selectedCustomer.firstName} {selectedCustomer.lastName}</span>
                    <span className="adm-summary-text">
                      {selectedCustomer.email || 'No email'}
                      {selectedCustomer.phone ? ` • ${selectedCustomer.phone}` : ''}
                    </span>
                  </div>
                  <div className="adm-inline-actions">
                    <span className="adm-badge adm-badge-info"><FiUser /> Customer</span>
                    {custPricing?.dirty && <span className="adm-badge adm-badge-info"><FiCreditCard /> Unsaved pricing</span>}
                  </div>
                </div>

                <div className="adm-subtabs">
                  <button className={`adm-subtab${customerTab === 'details' ? ' active' : ''}`} onClick={() => setCustomerTab('details')}>
                    Details
                  </button>
                  <button className={`adm-subtab${customerTab === 'pricing' ? ' active' : ''}`} onClick={() => setCustomerTab('pricing')}>
                    Pricing
                  </button>
                </div>

                {customerTab === 'details' && (
                  <div className="adm-form">
                    <h3>Edit Customer</h3>
                    <div className="adm-field-grid">
                      <div className="input-group">
                        <label>First Name *</label>
                        <input value={editForm.firstName} onChange={(e) => setEditForm({ ...editForm, firstName: e.target.value })} />
                      </div>
                      <div className="input-group">
                        <label>Last Name *</label>
                        <input value={editForm.lastName} onChange={(e) => setEditForm({ ...editForm, lastName: e.target.value })} />
                      </div>
                      <div className="input-group adm-field-span">
                        <label>Email *</label>
                        <input type="email" value={editForm.email} onChange={(e) => setEditForm({ ...editForm, email: e.target.value })} />
                      </div>
                      <div className="input-group adm-field-span">
                        <label>Phone *</label>
                        <input value={editForm.phone} onChange={(e) => setEditForm({ ...editForm, phone: e.target.value })} />
                      </div>
                      <div className="input-group adm-field-span">
                        <label>Change Password</label>
                        <div className="adm-password-wrap">
                          <input
                            type={showPassword ? 'text' : 'password'}
                            value={editForm.password}
                            onChange={(e) => setEditForm({ ...editForm, password: e.target.value })}
                            placeholder="Enter a new password to reset the account"
                            autoComplete="off"
                          />
                          <button type="button" className="adm-password-toggle" onClick={() => setShowPassword(!showPassword)}>
                            {showPassword ? <><FiEyeOff /> Hide</> : <><FiEye /> Show</>}
                          </button>
                        </div>
                        <span className="adm-hint">Leave blank to keep the existing password unchanged.</span>
                      </div>
                    </div>
                    <div className="adm-form-actions">
                      {isSuperAdmin && (
                        <button
                          className="btn adm-danger-btn push-left"
                          onClick={() => setDeleteConfirm({ id: selectedCustomer.id, name: `${selectedCustomer.firstName} ${selectedCustomer.lastName}`, role: 'customer' })}
                        >
                          <FiTrash2 /> Delete Account
                        </button>
                      )}
                      <button className="btn btn-primary" onClick={handleSaveDetails} disabled={saving}>
                        <FiSave /> {saving ? 'Saving...' : 'Save Changes'}
                      </button>
                    </div>
                  </div>
                )}

                {customerTab === 'pricing' && (
                  <div className="adm-card">
                    <div className="adm-summary" style={{ marginBottom: '1rem' }}>
                      <div className="adm-summary-copy">
                        <span className="adm-summary-title">Customer Pricing</span>
                        <span className="adm-summary-text">Override event and add-on pricing for this customer.</span>
                      </div>
                      {custPricing?.dirty && <span className="adm-badge adm-badge-info"><FiCreditCard /> Unsaved changes</span>}
                    </div>

                    {!custPricing ? (
                      <div className="adm-empty compact">
                        <span className="adm-empty-icon"><FiCreditCard /></span>
                        <h3>Loading pricing</h3>
                        <p>Preparing the customer pricing profile.</p>
                      </div>
                    ) : (
                      <div className="adm-panel-stack">
                        <div className="input-group" style={{ maxWidth: '320px' }}>
                          <label>Assigned Rate Code</label>
                          <select
                            value={custPricing.rateCodeId}
                            onChange={(e) => setCustPricing((prev) => ({ ...prev, rateCodeId: e.target.value, dirty: true }))}
                          >
                            <option value="">None (Default Pricing)</option>
                            {rateCodes.filter((rateCode) => rateCode.active).map((rateCode) => (
                              <option key={rateCode.id} value={rateCode.id}>{rateCode.name}</option>
                            ))}
                          </select>
                          <span className="adm-hint">Customer-specific overrides below take priority over the assigned rate code.</span>
                        </div>

                        <div>
                          <h4 style={{ marginBottom: '0.5rem' }}>Event Type Overrides</h4>
                          <div className="adm-table-wrap">
                            <table className="adm-table">
                              <thead>
                                <tr>
                                  <th>Event Type</th>
                                  <th>Base Price</th>
                                  <th>Hourly Rate</th>
                                  <th>Price / Guest</th>
                                </tr>
                              </thead>
                              <tbody>
                                {custPricing.eventPricings.map((eventPricing, index) => {
                                  const defaultEvent = eventTypes.find((eventType) => eventType.id === eventPricing.eventTypeId);
                                  return (
                                    <tr key={eventPricing.eventTypeId}>
                                      <td>
                                        {eventPricing.eventTypeName}
                                        {defaultEvent && (
                                          <span className="adm-table-note">
                                            Default: ₹{defaultEvent.basePrice} base / ₹{defaultEvent.hourlyRate}/hr / ₹{defaultEvent.pricePerGuest}/guest
                                          </span>
                                        )}
                                      </td>
                                      <td>
                                        <input
                                          type="number"
                                          step="0.01"
                                          className="input-compact"
                                          value={eventPricing.basePrice}
                                          placeholder={defaultEvent?.basePrice ?? ''}
                                          onChange={(e) => {
                                            const next = [...custPricing.eventPricings];
                                            next[index] = { ...next[index], basePrice: e.target.value };
                                            setCustPricing((prev) => ({ ...prev, eventPricings: next, dirty: true }));
                                          }}
                                        />
                                      </td>
                                      <td>
                                        <input
                                          type="number"
                                          step="0.01"
                                          className="input-compact"
                                          value={eventPricing.hourlyRate}
                                          placeholder={defaultEvent?.hourlyRate ?? ''}
                                          onChange={(e) => {
                                            const next = [...custPricing.eventPricings];
                                            next[index] = { ...next[index], hourlyRate: e.target.value };
                                            setCustPricing((prev) => ({ ...prev, eventPricings: next, dirty: true }));
                                          }}
                                        />
                                      </td>
                                      <td>
                                        <input
                                          type="number"
                                          step="0.01"
                                          className="input-compact"
                                          value={eventPricing.pricePerGuest}
                                          placeholder={defaultEvent?.pricePerGuest ?? ''}
                                          onChange={(e) => {
                                            const next = [...custPricing.eventPricings];
                                            next[index] = { ...next[index], pricePerGuest: e.target.value };
                                            setCustPricing((prev) => ({ ...prev, eventPricings: next, dirty: true }));
                                          }}
                                        />
                                      </td>
                                    </tr>
                                  );
                                })}
                              </tbody>
                            </table>
                          </div>
                        </div>

                        <div>
                          <h4 style={{ marginBottom: '0.5rem' }}>Add-On Overrides</h4>
                          <div className="adm-table-wrap">
                            <table className="adm-table">
                              <thead>
                                <tr>
                                  <th>Add-On</th>
                                  <th>Price</th>
                                </tr>
                              </thead>
                              <tbody>
                                {custPricing.addonPricings.map((addonPricing, index) => {
                                  const defaultAddon = addOns.find((addOn) => addOn.id === addonPricing.addOnId);
                                  return (
                                    <tr key={addonPricing.addOnId}>
                                      <td>
                                        {addonPricing.addOnName}
                                        {defaultAddon && <span className="adm-table-note">Default: ₹{defaultAddon.price}</span>}
                                      </td>
                                      <td>
                                        <input
                                          type="number"
                                          step="0.01"
                                          className="input-compact"
                                          value={addonPricing.price}
                                          placeholder={defaultAddon?.price ?? ''}
                                          onChange={(e) => {
                                            const next = [...custPricing.addonPricings];
                                            next[index] = { ...next[index], price: e.target.value };
                                            setCustPricing((prev) => ({ ...prev, addonPricings: next, dirty: true }));
                                          }}
                                        />
                                      </td>
                                    </tr>
                                  );
                                })}
                              </tbody>
                            </table>
                          </div>
                        </div>

                        <div className="adm-form-actions">
                          {custPricing.scopedProfile ? (
                            <button className="btn adm-danger-btn push-left" onClick={handleDeletePricing}>
                              <FiTrash2 /> Delete Pricing Profile
                            </button>
                          ) : (
                            <span className="adm-hint push-left">Only pricing saved in this binge can be deleted here.</span>
                          )}
                          {custPricing.dirty && <span className="adm-hint">Unsaved changes are ready to save.</span>}
                          <button className="btn btn-primary" onClick={handleSavePricing} disabled={!custPricing.dirty}>
                            <FiSave /> Save Pricing
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </>
            )}
          </section>
        </div>
      )}

      {section === 'admins' && isSuperAdmin && (
        <div className="adm-split-layout">
          <aside className="adm-panel-stack">
            <div className="adm-card adm-list-card">
              <div className="input-group" style={{ marginBottom: '0.75rem' }}>
                <label><FiSearch style={{ marginRight: 6, verticalAlign: -2 }} />Find admin</label>
                <input value={adminFilterText} onChange={(e) => setAdminFilterText(e.target.value)} placeholder="Filter admins by name or email..." />
              </div>
              <div className="adm-list compact">
                {filteredAdmins.length === 0 ? (
                  <div className="adm-empty compact">
                    <span className="adm-empty-icon"><FiShield /></span>
                    <h3>No admins found</h3>
                    <p>Try a different filter.</p>
                  </div>
                ) : filteredAdmins.map((admin) => (
                  <button
                    type="button"
                    key={admin.id}
                    className={`adm-list-item${selectedAdmin?.id === admin.id ? ' active' : ''}`}
                    onClick={() => selectAdmin(admin)}
                  >
                    <span className="adm-list-item-title">{admin.firstName} {admin.lastName}</span>
                    <span className="adm-list-item-meta">{admin.email || 'No email'}</span>
                    <span className={`adm-list-item-status ${admin.active ? 'success' : 'danger'}`}>
                      {admin.active ? 'Active' : 'Inactive'} • {admin.role === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin'}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          </aside>

          <section className="adm-panel-stack">
            {!selectedAdmin ? (
              <div className="adm-empty">
                <span className="adm-empty-icon"><FiShield /></span>
                <h3>Select an admin</h3>
                <p>Choose an admin profile to update access details or review their venues.</p>
              </div>
            ) : (
              <>
                <div className="adm-summary">
                  <div className="adm-summary-copy">
                    <span className="adm-summary-title">{selectedAdmin.firstName} {selectedAdmin.lastName}</span>
                    <span className="adm-summary-text">
                      {selectedAdmin.email || 'No email'} • Joined {selectedAdmin.createdAt ? new Date(selectedAdmin.createdAt).toLocaleDateString() : '—'}
                    </span>
                  </div>
                  <div className="adm-inline-actions">
                    <span className={`adm-badge ${selectedAdmin.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                      {selectedAdmin.active ? 'Active' : 'Inactive'}
                    </span>
                    <span className="adm-badge adm-badge-info">{selectedAdmin.role === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin'}</span>
                    <button
                      className="btn adm-danger-btn adm-compact-btn"
                      onClick={() => setDeleteConfirm({ id: selectedAdmin.id, name: `${selectedAdmin.firstName} ${selectedAdmin.lastName}`, role: 'admin' })}
                    >
                      <FiTrash2 /> Delete
                    </button>
                  </div>
                </div>

                <div className="adm-subtabs">
                  <button className={`adm-subtab${adminTab === 'details' ? ' active' : ''}`} onClick={() => setAdminTab('details')}>
                    Details
                  </button>
                  <button className={`adm-subtab${adminTab === 'binges' ? ' active' : ''}`} onClick={() => setAdminTab('binges')}>
                    Binges ({adminBinges.length})
                  </button>
                </div>

                {adminTab === 'details' && (
                  <div className="adm-form">
                    <h3>Edit Admin Profile</h3>
                    <div className="adm-field-grid">
                      <div className="input-group">
                        <label>First Name</label>
                        <input value={adminEditForm.firstName} onChange={(e) => setAdminEditForm({ ...adminEditForm, firstName: e.target.value })} />
                      </div>
                      <div className="input-group">
                        <label>Last Name</label>
                        <input value={adminEditForm.lastName} onChange={(e) => setAdminEditForm({ ...adminEditForm, lastName: e.target.value })} />
                      </div>
                      <div className="input-group">
                        <label>Email</label>
                        <input type="email" value={adminEditForm.email} onChange={(e) => setAdminEditForm({ ...adminEditForm, email: e.target.value })} />
                      </div>
                      <div className="input-group">
                        <label>Phone</label>
                        <input value={adminEditForm.phone} onChange={(e) => setAdminEditForm({ ...adminEditForm, phone: e.target.value })} />
                      </div>
                      <div className="input-group adm-field-span">
                        <label>Reset Password</label>
                        <div className="adm-password-wrap">
                          <input
                            type={adminShowPassword ? 'text' : 'password'}
                            value={adminEditForm.password}
                            onChange={(e) => setAdminEditForm({ ...adminEditForm, password: e.target.value })}
                            placeholder="Leave blank to keep current password"
                          />
                          <button type="button" className="adm-password-toggle" onClick={() => setAdminShowPassword(!adminShowPassword)}>
                            {adminShowPassword ? <><FiEyeOff /> Hide</> : <><FiEye /> Show</>}
                          </button>
                        </div>
                        <span className="adm-hint">Only fill this in when you need to reset the admin password.</span>
                      </div>
                    </div>
                    <div className="adm-form-actions">
                      <button className="btn btn-primary" onClick={handleSaveAdmin} disabled={adminSaving}>
                        <FiSave /> {adminSaving ? 'Saving...' : 'Save Changes'}
                      </button>
                    </div>
                  </div>
                )}

                {adminTab === 'binges' && (
                  adminBinges.length === 0 ? (
                    <div className="adm-empty compact">
                      <span className="adm-empty-icon"><FiMapPin /></span>
                      <h3>No binges yet</h3>
                      <p>This admin has not created any venues.</p>
                    </div>
                  ) : (
                    <div className="adm-panel-stack">
                      {adminBinges.map((binge) => (
                        <div key={binge.id} className="adm-mini-card">
                          <div className="adm-inline-actions" style={{ justifyContent: 'space-between' }}>
                            <div>
                              <div className="adm-mini-card-title">{binge.name}</div>
                              {binge.address && <div className="adm-mini-card-meta">{binge.address}</div>}
                            </div>
                            <span className={`adm-badge ${binge.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                              {binge.active ? 'Active' : 'Inactive'}
                            </span>
                          </div>
                          <div className="adm-mini-card-meta">
                            Created {binge.createdAt ? new Date(binge.createdAt).toLocaleDateString() : '—'}
                            {binge.operationalDate ? ` • Op. Date: ${binge.operationalDate}` : ''}
                          </div>
                        </div>
                      ))}
                    </div>
                  )
                )}
              </>
            )}
          </section>
        </div>
      )}

      {deleteConfirm && (
        <div className="adm-modal-overlay" onClick={(e) => e.target === e.currentTarget && setDeleteConfirm(null)}>
          <div className="adm-modal" style={{ maxWidth: '440px', textAlign: 'center' }}>
            <h3 style={{ color: 'var(--danger)' }}>Delete Account</h3>
            <p style={{ marginBottom: '1.5rem', fontSize: '0.9rem' }}>
              Are you sure you want to permanently delete <strong>{deleteConfirm.name}</strong>'s {deleteConfirm.role} account? This action cannot be undone.
            </p>
            <div className="adm-modal-actions" style={{ justifyContent: 'center' }}>
              <button className="btn btn-secondary" onClick={() => setDeleteConfirm(null)}>Cancel</button>
              <button className="btn adm-danger-btn" onClick={handleDeleteUser}><FiTrash2 /> Delete</button>
            </div>
          </div>
        </div>
      )}

      {section === 'rate-codes' && !showRCForm && (
        <div className="adm-panel-stack">
          <div className="adm-toolbar">
            <div className="adm-header-copy">
              <span className="adm-kicker"><FiTag /> Pricing templates</span>
              <h1 style={{ fontSize: '1.45rem' }}>Rate Codes</h1>
              <p>Named pricing templates that can be assigned to customer accounts.</p>
            </div>
            <button className="btn btn-primary" onClick={openRCCreate}><FiPlus /> New Rate Code</button>
          </div>

          {rateCodes.length === 0 ? (
            <div className="adm-empty">
              <span className="adm-empty-icon"><FiTag /></span>
              <h3>No rate codes yet</h3>
              <p>Create pricing templates for VIP, corporate, or custom account tiers.</p>
            </div>
          ) : (
            <div className="adm-panel-stack">
              {rateCodes.map((rateCode) => (
                <div key={rateCode.id} className="adm-card" style={!rateCode.active ? { opacity: 0.58 } : undefined}>
                  <div className="adm-toolbar">
                    <div className="adm-summary-copy">
                      <span className="adm-summary-title">{rateCode.name}</span>
                      {rateCode.description && <span className="adm-summary-text">{rateCode.description}</span>}
                      <div className="adm-inline-actions">
                        <span className={`adm-badge ${rateCode.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                          {rateCode.active ? 'Active' : 'Inactive'}
                        </span>
                        <span className="adm-badge adm-badge-info">
                          {(rateCode.eventPricings || []).length} event pricing{(rateCode.eventPricings || []).length !== 1 ? 's' : ''}
                          {' • '}
                          {(rateCode.addonPricings || []).length} add-on pricing{(rateCode.addonPricings || []).length !== 1 ? 's' : ''}
                        </span>
                      </div>
                    </div>
                    <div className="adm-inline-actions">
                      <button className="btn btn-secondary adm-compact-btn" onClick={() => setRcExpandedId(rcExpandedId === rateCode.id ? null : rateCode.id)}>
                        {rcExpandedId === rateCode.id ? <><FiChevronUp /> Hide</> : <><FiChevronDown /> Details</>}
                      </button>
                      <button className="btn btn-secondary adm-compact-btn" onClick={() => openRCEdit(rateCode)}>Edit</button>
                      <button className="btn btn-secondary adm-compact-btn" onClick={() => handleToggleRC(rateCode.id)}>
                        {rateCode.active ? 'Deactivate' : 'Activate'}
                      </button>
                      {!rateCode.active && (
                        <button className="btn adm-danger-btn adm-compact-btn" onClick={() => handleDeleteRC(rateCode)}>
                          <FiTrash2 /> Delete
                        </button>
                      )}
                    </div>
                  </div>

                  {rcExpandedId === rateCode.id && (
                    <div className="adm-panel-stack" style={{ marginTop: '1rem' }}>
                      {(rateCode.eventPricings || []).length > 0 && (
                        <div className="adm-table-wrap">
                          <table className="adm-table">
                            <thead>
                              <tr>
                                <th>Event</th>
                                <th>Base</th>
                                <th>Hourly</th>
                                <th>Per Guest</th>
                              </tr>
                            </thead>
                            <tbody>
                              {rateCode.eventPricings.map((eventPricing) => (
                                <tr key={eventPricing.eventTypeId}>
                                  <td>{eventPricing.eventTypeName}</td>
                                  <td>₹{eventPricing.basePrice}</td>
                                  <td>₹{eventPricing.hourlyRate}/hr</td>
                                  <td>₹{eventPricing.pricePerGuest}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}

                      {(rateCode.addonPricings || []).length > 0 && (
                        <div className="adm-table-wrap">
                          <table className="adm-table">
                            <thead>
                              <tr>
                                <th>Add-On</th>
                                <th>Price</th>
                              </tr>
                            </thead>
                            <tbody>
                              {rateCode.addonPricings.map((addonPricing) => (
                                <tr key={addonPricing.addOnId}>
                                  <td>{addonPricing.addOnName}</td>
                                  <td>₹{addonPricing.price}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}

                      {(rateCode.eventPricings || []).length === 0 && (rateCode.addonPricings || []).length === 0 && (
                        <span className="adm-hint">No custom pricing defined for this rate code.</span>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {section === 'rate-codes' && showRCForm && (
        <div className="adm-panel-stack">
          <div className="adm-summary">
            <div className="adm-summary-copy">
              <span className="adm-summary-title">{editingRC ? 'Edit Rate Code' : 'Create Rate Code'}</span>
              <span className="adm-summary-text">Build reusable pricing templates and leave blanks where defaults should apply.</span>
            </div>
          </div>

          <div className="adm-form">
            <div className="adm-field-grid">
              <div className="input-group">
                <label>Rate Code Name *</label>
                <input value={rcForm.name} onChange={(e) => setRcForm({ ...rcForm, name: e.target.value })} placeholder="e.g. VIP, Corporate, Student" />
              </div>
              <div className="input-group">
                <label>Description</label>
                <input value={rcForm.description} onChange={(e) => setRcForm({ ...rcForm, description: e.target.value })} placeholder="Optional description" />
              </div>
            </div>

            <div style={{ marginTop: '1.5rem' }}>
              <h3 style={{ marginBottom: '0.5rem' }}>Event Type Pricing</h3>
              <p className="adm-hint" style={{ marginBottom: '0.75rem' }}>Leave blank to use default event pricing.</p>
              <div className="adm-table-wrap">
                <table className="adm-table">
                  <thead>
                    <tr>
                      <th>Event Type</th>
                      <th>Base Price</th>
                      <th>Hourly Rate</th>
                      <th>Price / Guest</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rcForm.eventPricings.map((eventPricing, index) => {
                      const defaultEvent = eventTypes.find((eventType) => eventType.id === eventPricing.eventTypeId);
                      return (
                        <tr key={eventPricing.eventTypeId}>
                          <td>
                            {eventPricing.eventTypeName}
                            {defaultEvent && (
                              <span className="adm-table-note">
                                Default: ₹{defaultEvent.basePrice} / ₹{defaultEvent.hourlyRate}/hr / ₹{defaultEvent.pricePerGuest}/guest
                              </span>
                            )}
                          </td>
                          <td>
                            <input
                              type="number"
                              step="0.01"
                              className="input-compact"
                              value={eventPricing.basePrice}
                              placeholder={defaultEvent?.basePrice ?? ''}
                              onChange={(e) => {
                                const next = [...rcForm.eventPricings];
                                next[index] = { ...next[index], basePrice: e.target.value };
                                setRcForm({ ...rcForm, eventPricings: next });
                              }}
                            />
                          </td>
                          <td>
                            <input
                              type="number"
                              step="0.01"
                              className="input-compact"
                              value={eventPricing.hourlyRate}
                              placeholder={defaultEvent?.hourlyRate ?? ''}
                              onChange={(e) => {
                                const next = [...rcForm.eventPricings];
                                next[index] = { ...next[index], hourlyRate: e.target.value };
                                setRcForm({ ...rcForm, eventPricings: next });
                              }}
                            />
                          </td>
                          <td>
                            <input
                              type="number"
                              step="0.01"
                              className="input-compact"
                              value={eventPricing.pricePerGuest}
                              placeholder={defaultEvent?.pricePerGuest ?? ''}
                              onChange={(e) => {
                                const next = [...rcForm.eventPricings];
                                next[index] = { ...next[index], pricePerGuest: e.target.value };
                                setRcForm({ ...rcForm, eventPricings: next });
                              }}
                            />
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            <div style={{ marginTop: '1.5rem' }}>
              <h3 style={{ marginBottom: '0.5rem' }}>Add-On Pricing</h3>
              <p className="adm-hint" style={{ marginBottom: '0.75rem' }}>Leave blank to use default add-on pricing.</p>
              <div className="adm-table-wrap">
                <table className="adm-table">
                  <thead>
                    <tr>
                      <th>Add-On</th>
                      <th>Price</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rcForm.addonPricings.map((addonPricing, index) => {
                      const defaultAddon = addOns.find((addOn) => addOn.id === addonPricing.addOnId);
                      return (
                        <tr key={addonPricing.addOnId}>
                          <td>
                            {addonPricing.addOnName}
                            {defaultAddon && <span className="adm-table-note">Default: ₹{defaultAddon.price}</span>}
                          </td>
                          <td>
                            <input
                              type="number"
                              step="0.01"
                              className="input-compact"
                              value={addonPricing.price}
                              placeholder={defaultAddon?.price ?? ''}
                              onChange={(e) => {
                                const next = [...rcForm.addonPricings];
                                next[index] = { ...next[index], price: e.target.value };
                                setRcForm({ ...rcForm, addonPricings: next });
                              }}
                            />
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="adm-form-actions">
              <button className="btn btn-secondary" onClick={() => setShowRCForm(false)}>Cancel</button>
              <button className="btn btn-primary" onClick={handleSaveRC}><FiSave /> {editingRC ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
