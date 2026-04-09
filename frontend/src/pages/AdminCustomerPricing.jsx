import { useState, useEffect } from 'react';
import { adminService, authService } from '../services/endpoints';
import { toast } from 'react-toastify';

export default function AdminCustomerPricing() {
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [rateCodes, setRateCodes] = useState([]);
  const [loading, setLoading] = useState(true);

  // Customer search
  const [query, setQuery] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [searching, setSearching] = useState(false);

  // Selected customers with their pricing cards
  const [selectedCustomers, setSelectedCustomers] = useState([]);
  const [expandedIds, setExpandedIds] = useState(new Set());

  // Bulk actions
  const [bulkRateCodeId, setBulkRateCodeId] = useState('');
  const [selectAll, setSelectAll] = useState(false);
  const [checkedIds, setCheckedIds] = useState(new Set());

  useEffect(() => {
    Promise.all([adminService.getAllEventTypes(), adminService.getAllAddOns(), adminService.getRateCodes()])
      .then(([etRes, aoRes, rcRes]) => {
        setEventTypes(etRes.data.data || []);
        setAddOns(aoRes.data.data || []);
        setRateCodes(rcRes.data.data || []);
      })
      .catch(() => toast.error('Failed to load data'))
      .finally(() => setLoading(false));
  }, []);

  const searchCustomers = async () => {
    if (!query.trim()) return;
    setSearching(true);
    try {
      const res = await authService.searchCustomers(query.trim());
      // Server returns Page<UserDto> — extract .content
      const payload = res.data.data;
      setSearchResults(Array.isArray(payload) ? payload : (payload?.content ?? []));
    } catch { toast.error('Search failed'); }
    finally { setSearching(false); }
  };

  const addCustomer = async (cust) => {
    if (selectedCustomers.find(c => c.id === cust.id)) { toast.info('Customer already added'); return; }
    try {
      const pricingRes = await adminService.getCustomerPricing(cust.id);
      const pricing = pricingRes.data.data || pricingRes.data;
      const epMap = {};
      (pricing.eventPricings || []).forEach(ep => { epMap[ep.eventTypeId] = ep; });
      const apMap = {};
      (pricing.addonPricings || []).forEach(ap => { apMap[ap.addOnId] = ap; });

      setSelectedCustomers(prev => [...prev, {
        ...cust,
        rateCodeId: pricing.rateCodeId || '',
        rateCodeName: pricing.rateCodeName || '',
        eventPricings: eventTypes.map(et => {
          const ex = epMap[et.id];
          return { eventTypeId: et.id, eventTypeName: et.name, basePrice: ex ? String(ex.basePrice) : '', hourlyRate: ex ? String(ex.hourlyRate) : '', pricePerGuest: ex ? String(ex.pricePerGuest) : '' };
        }),
        addonPricings: addOns.map(a => {
          const ex = apMap[a.id];
          return { addOnId: a.id, addOnName: a.name, price: ex ? String(ex.price) : '' };
        }),
        dirty: false,
      }]);
      setExpandedIds(prev => new Set([...prev, cust.id]));
    } catch { toast.error('Failed to load pricing'); }
  };

  const removeCustomer = (id) => {
    setSelectedCustomers(prev => prev.filter(c => c.id !== id));
    setExpandedIds(prev => { const n = new Set(prev); n.delete(id); return n; });
    setCheckedIds(prev => { const n = new Set(prev); n.delete(id); return n; });
  };

  const toggleExpand = (id) => {
    setExpandedIds(prev => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  };

  const toggleCheck = (id) => {
    setCheckedIds(prev => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  };

  const handleSelectAll = () => {
    if (selectAll) { setCheckedIds(new Set()); } else { setCheckedIds(new Set(selectedCustomers.map(c => c.id))); }
    setSelectAll(!selectAll);
  };

  const updateCustField = (custId, field, value) => {
    setSelectedCustomers(prev => prev.map(c => c.id === custId ? { ...c, [field]: value, dirty: true } : c));
  };

  const updateCustEventPricing = (custId, idx, field, value) => {
    setSelectedCustomers(prev => prev.map(c => {
      if (c.id !== custId) return c;
      const arr = [...c.eventPricings];
      arr[idx] = { ...arr[idx], [field]: value };
      return { ...c, eventPricings: arr, dirty: true };
    }));
  };

  const updateCustAddonPricing = (custId, idx, field, value) => {
    setSelectedCustomers(prev => prev.map(c => {
      if (c.id !== custId) return c;
      const arr = [...c.addonPricings];
      arr[idx] = { ...arr[idx], [field]: value };
      return { ...c, addonPricings: arr, dirty: true };
    }));
  };

  const saveCustomerPricing = async (cust) => {
    const payload = {
      customerId: cust.id,
      rateCodeId: cust.rateCodeId || null,
      eventPricings: cust.eventPricings
        .filter(ep => ep.basePrice !== '' || ep.hourlyRate !== '' || ep.pricePerGuest !== '')
        .map(ep => ({ eventTypeId: ep.eventTypeId, basePrice: Number(ep.basePrice) || 0, hourlyRate: Number(ep.hourlyRate) || 0, pricePerGuest: Number(ep.pricePerGuest) || 0 })),
      addonPricings: cust.addonPricings
        .filter(ap => ap.price !== '')
        .map(ap => ({ addOnId: ap.addOnId, price: Number(ap.price) || 0 })),
    };
    try {
      await adminService.saveCustomerPricing(payload);
      toast.success(`Pricing saved for ${cust.firstName} ${cust.lastName}`);
      setSelectedCustomers(prev => prev.map(c => c.id === cust.id ? { ...c, dirty: false } : c));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    }
  };

  const handleBulkAssign = async () => {
    const ids = [...checkedIds];
    if (ids.length === 0) { toast.error('Select at least one customer'); return; }
    try {
      await adminService.bulkAssignRateCode({ customerIds: ids, rateCodeId: bulkRateCodeId || null });
      toast.success(`Rate code assigned to ${ids.length} customer(s)`);
      // Update local state
      const rc = rateCodes.find(r => r.id === Number(bulkRateCodeId));
      setSelectedCustomers(prev => prev.map(c => {
        if (!ids.includes(c.id)) return c;
        return { ...c, rateCodeId: bulkRateCodeId || '', rateCodeName: rc ? rc.name : '', dirty: true };
      }));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Bulk assign failed');
    }
  };

  const inputStyle = { padding: '0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '100%' };
  const smallInputStyle = { ...inputStyle, width: '110px', textAlign: 'right' };
  const labelStyle = { fontWeight: 600, fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '0.3rem', display: 'block' };

  if (loading) return <div className="container"><div className="loading"><div className="spinner"></div></div></div>;

  return (
    <div className="container" style={{ maxWidth: '1100px', margin: '0 auto' }}>
      <div className="page-header" style={{ marginBottom: '1.5rem' }}>
        <h1>Customer Pricing</h1>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>Search for customers, manage their pricing individually or in bulk</p>
      </div>

      {/* Search bar */}
      <div className="card" style={{ padding: '1rem', marginBottom: '1rem' }}>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <input style={{ ...inputStyle, flex: 1 }} value={query} onChange={e => setQuery(e.target.value)}
            placeholder="Search customers by name, email, or phone..."
            onKeyDown={e => e.key === 'Enter' && searchCustomers()} />
          <button className="btn btn-primary" onClick={searchCustomers} disabled={searching}>
            {searching ? 'Searching...' : 'Search'}
          </button>
        </div>
        {searchResults.length > 0 && (
          <div style={{ marginTop: '0.75rem', maxHeight: '200px', overflowY: 'auto', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)' }}>
            {searchResults.map(c => (
              <div key={c.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0.5rem 0.75rem', borderBottom: '1px solid var(--border)', cursor: 'pointer' }}
                onClick={() => addCustomer(c)}>
                <div>
                  <span style={{ fontWeight: 600 }}>{c.firstName} {c.lastName}</span>
                  <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginLeft: '0.5rem' }}>{c.email} • {c.phone}</span>
                </div>
                <span style={{ fontSize: '0.75rem', color: 'var(--accent)' }}>+ Add</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Bulk actions */}
      {selectedCustomers.length > 1 && (
        <div className="card" style={{ padding: '0.75rem 1rem', marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.85rem', cursor: 'pointer' }}>
            <input type="checkbox" checked={selectAll} onChange={handleSelectAll} /> Select All
          </label>
          <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>{checkedIds.size} selected</span>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
            <select style={{ ...inputStyle, width: '200px' }} value={bulkRateCodeId} onChange={e => setBulkRateCodeId(e.target.value)}>
              <option value="">No Rate Code</option>
              {rateCodes.filter(r => r.active).map(rc => <option key={rc.id} value={rc.id}>{rc.name}</option>)}
            </select>
            <button className="btn btn-primary" style={{ fontSize: '0.8rem', whiteSpace: 'nowrap' }} onClick={handleBulkAssign}>
              Bulk Assign Rate Code
            </button>
          </div>
        </div>
      )}

      {/* Customer cards */}
      {selectedCustomers.length === 0 ? (
        <div className="card" style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>
          Search and add customers above to manage their pricing.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
          {selectedCustomers.map(cust => (
            <div key={cust.id} className="card" style={{ padding: '1rem', border: cust.dirty ? '1px solid var(--warning)' : undefined }}>
              {/* Card header */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                  {selectedCustomers.length > 1 && (
                    <input type="checkbox" checked={checkedIds.has(cust.id)} onChange={() => toggleCheck(cust.id)} />
                  )}
                  <div>
                    <span style={{ fontWeight: 600 }}>{cust.firstName} {cust.lastName}</span>
                    <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginLeft: '0.5rem' }}>{cust.email}</span>
                    {cust.rateCodeName && (
                      <span style={{ marginLeft: '0.5rem', fontSize: '0.7rem', padding: '0.1rem 0.4rem', borderRadius: '999px', background: 'rgba(99,102,241,0.15)', color: '#818cf8' }}>
                        {cust.rateCodeName}
                      </span>
                    )}
                    {cust.dirty && <span style={{ marginLeft: '0.5rem', fontSize: '0.7rem', color: 'var(--warning)' }}>• unsaved</span>}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: '0.4rem' }}>
                  <button className="btn btn-secondary" style={{ fontSize: '0.75rem', padding: '0.25rem 0.5rem' }}
                    onClick={() => toggleExpand(cust.id)}>
                    {expandedIds.has(cust.id) ? '▲ Minimize' : '▼ Expand'}
                  </button>
                  <button className="btn btn-primary" style={{ fontSize: '0.75rem', padding: '0.25rem 0.5rem' }}
                    onClick={() => saveCustomerPricing(cust)} disabled={!cust.dirty}>Save</button>
                  <button className="btn btn-secondary" style={{ fontSize: '0.75rem', padding: '0.25rem 0.5rem', color: '#ef4444' }}
                    onClick={() => removeCustomer(cust.id)}>✕</button>
                </div>
              </div>

              {/* Expanded pricing form */}
              {expandedIds.has(cust.id) && (
                <div style={{ marginTop: '1rem', borderTop: '1px solid var(--border)', paddingTop: '0.75rem' }}>
                  {/* Rate code assignment */}
                  <div style={{ marginBottom: '1rem' }}>
                    <label style={labelStyle}>Assigned Rate Code</label>
                    <select style={{ ...inputStyle, width: '250px' }} value={cust.rateCodeId}
                      onChange={e => updateCustField(cust.id, 'rateCodeId', e.target.value)}>
                      <option value="">None (Default Pricing)</option>
                      {rateCodes.filter(r => r.active).map(rc => <option key={rc.id} value={rc.id}>{rc.name}</option>)}
                    </select>
                    <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block', marginTop: '0.25rem' }}>
                      Customer-specific overrides below take priority over the rate code.
                    </span>
                  </div>

                  {/* Event pricing */}
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
                        {cust.eventPricings.map((ep, i) => {
                          const def = eventTypes.find(e => e.id === ep.eventTypeId);
                          return (
                            <tr key={ep.eventTypeId} style={{ borderBottom: '1px solid var(--border)' }}>
                              <td style={{ padding: '0.4rem' }}>
                                {ep.eventTypeName}
                                {def && <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', display: 'block' }}>Default: ₹{def.basePrice} / ₹{def.hourlyRate}/hr</span>}
                              </td>
                              <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                <input type="number" step="0.01" style={smallInputStyle} value={ep.basePrice}
                                  placeholder={def?.basePrice ?? ''} onChange={e => updateCustEventPricing(cust.id, i, 'basePrice', e.target.value)} />
                              </td>
                              <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                <input type="number" step="0.01" style={smallInputStyle} value={ep.hourlyRate}
                                  placeholder={def?.hourlyRate ?? ''} onChange={e => updateCustEventPricing(cust.id, i, 'hourlyRate', e.target.value)} />
                              </td>
                              <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                <input type="number" step="0.01" style={smallInputStyle} value={ep.pricePerGuest}
                                  placeholder={def?.pricePerGuest ?? ''} onChange={e => updateCustEventPricing(cust.id, i, 'pricePerGuest', e.target.value)} />
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>

                  {/* Add-on pricing */}
                  <h4 style={{ fontSize: '0.85rem', marginBottom: '0.5rem' }}>Add-On Overrides</h4>
                  <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem' }}>
                      <thead>
                        <tr style={{ borderBottom: '2px solid var(--border)' }}>
                          <th style={{ textAlign: 'left', padding: '0.4rem' }}>Add-On</th>
                          <th style={{ textAlign: 'right', padding: '0.4rem' }}>Price</th>
                        </tr>
                      </thead>
                      <tbody>
                        {cust.addonPricings.map((ap, i) => {
                          const def = addOns.find(a => a.id === ap.addOnId);
                          return (
                            <tr key={ap.addOnId} style={{ borderBottom: '1px solid var(--border)' }}>
                              <td style={{ padding: '0.4rem' }}>
                                {ap.addOnName}
                                {def && <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', display: 'block' }}>Default: ₹{def.price}</span>}
                              </td>
                              <td style={{ padding: '0.4rem', textAlign: 'right' }}>
                                <input type="number" step="0.01" style={smallInputStyle} value={ap.price}
                                  placeholder={def?.price ?? ''} onChange={e => updateCustAddonPricing(cust.id, i, 'price', e.target.value)} />
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>

                  <div style={{ marginTop: '0.75rem', textAlign: 'right' }}>
                    <button className="btn btn-primary" onClick={() => saveCustomerPricing(cust)} disabled={!cust.dirty}>
                      Save Pricing for {cust.firstName}
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
