import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { adminService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiTrash2 } from 'react-icons/fi';
import './AdminPages.css';

export default function AdminRateCodes() {
  const [searchParams] = useSearchParams();
  const [rateCodes, setRateCodes] = useState([]);
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState(null);
  const [expandedId, setExpandedId] = useState(null);
  const [saving, setSaving] = useState(false);

  const [form, setForm] = useState({ name: '', description: '', eventPricings: [], addonPricings: [] });

  const load = () => {
    Promise.all([adminService.getRateCodes(), adminService.getAllEventTypes(), adminService.getAllAddOns()])
      .then(([rcRes, etRes, aoRes]) => {
        setRateCodes(toArray(rcRes.data?.data));
        setEventTypes(toArray(etRes.data?.data));
        setAddOns(toArray(aoRes.data?.data));
      })
      .catch(() => toast.error('Failed to load data'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  /* Auto-expand rate code from URL param (e.g. ?expand=5) */
  useEffect(() => {
    const expandParam = searchParams.get('expand');
    if (expandParam && rateCodes.length > 0) {
      const id = Number(expandParam);
      if (rateCodes.some(rc => rc.id === id)) {
        setExpandedId(id);
        setTimeout(() => {
          document.getElementById(`rc-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }, 100);
      }
    }
  }, [searchParams, rateCodes]);

  const openCreate = () => {
    setEditing(null);
    setForm({
      name: '', description: '',
      eventPricings: eventTypes.map(et => ({ eventTypeId: et.id, eventTypeName: et.name, basePrice: '', hourlyRate: '', pricePerGuest: '' })),
      addonPricings: addOns.map(a => ({ addOnId: a.id, addOnName: a.name, price: '' })),
    });
    setShowForm(true);
  };

  const openEdit = (rc) => {
    setEditing(rc);
    const epMap = {};
    (rc.eventPricings || []).forEach(ep => { epMap[ep.eventTypeId] = ep; });
    const apMap = {};
    (rc.addonPricings || []).forEach(ap => { apMap[ap.addOnId] = ap; });

    setForm({
      name: rc.name,
      description: rc.description || '',
      eventPricings: eventTypes.map(et => {
        const existing = epMap[et.id];
        return { eventTypeId: et.id, eventTypeName: et.name,
          basePrice: existing ? existing.basePrice : '', hourlyRate: existing ? existing.hourlyRate : '',
          pricePerGuest: existing ? existing.pricePerGuest : '' };
      }),
      addonPricings: addOns.map(a => {
        const existing = apMap[a.id];
        return { addOnId: a.id, addOnName: a.name, price: existing ? existing.price : '' };
      }),
    });
    setShowForm(true);
  };

  const handleSave = async () => {
    if (saving) return;
    if (!form.name.trim()) { toast.error('Name is required'); return; }
    setSaving(true);
    const payload = {
      name: form.name.trim(),
      description: form.description.trim(),
      eventPricings: form.eventPricings
        .filter(ep => ep.basePrice !== '' || ep.hourlyRate !== '' || ep.pricePerGuest !== '')
        .map(ep => ({ eventTypeId: ep.eventTypeId, basePrice: Number(ep.basePrice) || 0, hourlyRate: Number(ep.hourlyRate) || 0, pricePerGuest: Number(ep.pricePerGuest) || 0 })),
      addonPricings: form.addonPricings
        .filter(ap => ap.price !== '')
        .map(ap => ({ addOnId: ap.addOnId, price: Number(ap.price) || 0 })),
    };
    try {
      if (editing) {
        await adminService.updateRateCode(editing.id, payload);
        toast.success('Rate code updated');
      } else {
        await adminService.createRateCode(payload);
        toast.success('Rate code created');
      }
      setShowForm(false);
      load();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handleToggle = async (id) => {
    try {
      await adminService.toggleRateCode(id);
      toast.success('Rate code toggled');
      load();
    } catch (err) { toast.error(err.userMessage || 'Toggle failed'); }
  };

  const handleDelete = async (rateCode) => {
    if (rateCode.active) {
      toast.error('Deactivate the rate code before deleting it');
      return;
    }
    if (!confirm(`Delete rate code "${rateCode.name}" permanently? This cannot be undone.`)) return;

    try {
      await adminService.deleteRateCode(rateCode.id);
      toast.success('Rate code deleted');
      load();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Delete failed');
    }
  };

  const inputStyle = { padding: '0.5rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.85rem', width: '100%' };
  const labelStyle = { fontWeight: 600, fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '0.3rem', display: 'block' };
  const smallInputStyle = { ...inputStyle, width: '120px', textAlign: 'right' };

  if (loading) return <div className="container"><div className="loading"><div className="spinner"></div></div></div>;

  if (showForm) {
    return (
      <div className="container" style={{ maxWidth: '900px', margin: '0 auto' }}>
        <div className="page-header" style={{ marginBottom: '1rem' }}>
          <h1>{editing ? 'Edit Rate Code' : 'Create Rate Code'}</h1>
        </div>
        <div className="card" style={{ padding: '1.5rem' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
            <div>
              <label style={labelStyle}>Rate Code Name *</label>
              <input style={inputStyle} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="e.g. VIP, Corporate, Student" />
            </div>
            <div>
              <label style={labelStyle}>Description</label>
              <input style={inputStyle} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} placeholder="Optional description" />
            </div>
          </div>

          <h3 style={{ fontSize: '1rem', marginBottom: '0.75rem', color: 'var(--text)' }}>Event Type Pricing</h3>
          <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }}>Leave blank to use default pricing for that event type.</p>
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
                {form.eventPricings.map((ep, i) => {
                  const def = eventTypes.find(e => e.id === ep.eventTypeId);
                  return (
                    <tr key={ep.eventTypeId} style={{ borderBottom: '1px solid var(--border)' }}>
                      <td style={{ padding: '0.5rem' }}>
                        {ep.eventTypeName}
                        {def && <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block' }}>Default: ₹{def.basePrice} / ₹{def.hourlyRate}/hr / ₹{def.pricePerGuest}/guest</span>}
                      </td>
                      <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                        <input type="number" step="0.01" style={smallInputStyle} value={ep.basePrice}
                          placeholder={def?.basePrice ?? ''}
                          onChange={e => { const arr = [...form.eventPricings]; arr[i] = { ...arr[i], basePrice: e.target.value }; setForm({ ...form, eventPricings: arr }); }} />
                      </td>
                      <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                        <input type="number" step="0.01" style={smallInputStyle} value={ep.hourlyRate}
                          placeholder={def?.hourlyRate ?? ''}
                          onChange={e => { const arr = [...form.eventPricings]; arr[i] = { ...arr[i], hourlyRate: e.target.value }; setForm({ ...form, eventPricings: arr }); }} />
                      </td>
                      <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                        <input type="number" step="0.01" style={smallInputStyle} value={ep.pricePerGuest}
                          placeholder={def?.pricePerGuest ?? ''}
                          onChange={e => { const arr = [...form.eventPricings]; arr[i] = { ...arr[i], pricePerGuest: e.target.value }; setForm({ ...form, eventPricings: arr }); }} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <h3 style={{ fontSize: '1rem', marginTop: '1.5rem', marginBottom: '0.75rem', color: 'var(--text)' }}>Add-On Pricing</h3>
          <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }}>Leave blank to use default pricing for that add-on.</p>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
              <thead>
                <tr style={{ borderBottom: '2px solid var(--border)' }}>
                  <th style={{ textAlign: 'left', padding: '0.5rem' }}>Add-On</th>
                  <th style={{ textAlign: 'right', padding: '0.5rem' }}>Price</th>
                </tr>
              </thead>
              <tbody>
                {form.addonPricings.map((ap, i) => {
                  const def = addOns.find(a => a.id === ap.addOnId);
                  return (
                    <tr key={ap.addOnId} style={{ borderBottom: '1px solid var(--border)' }}>
                      <td style={{ padding: '0.5rem' }}>
                        {ap.addOnName}
                        {def && <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block' }}>Default: ₹{def.price}</span>}
                      </td>
                      <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                        <input type="number" step="0.01" style={smallInputStyle} value={ap.price}
                          placeholder={def?.price ?? ''}
                          onChange={e => { const arr = [...form.addonPricings]; arr[i] = { ...arr[i], price: e.target.value }; setForm({ ...form, addonPricings: arr }); }} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div style={{ display: 'flex', gap: '0.75rem', marginTop: '1.5rem', justifyContent: 'flex-end' }}>
            <button className="btn btn-secondary" onClick={() => setShowForm(false)}>Cancel</button>
            <button className="btn btn-secondary" onClick={() => setForm({
              name: '', description: '',
              eventPricings: eventTypes.map(et => ({ eventTypeId: et.id, eventTypeName: et.name, basePrice: '', hourlyRate: '', pricePerGuest: '' })),
              addonPricings: addOns.map(a => ({ addOnId: a.id, addOnName: a.name, price: '' })),
            })}>Clear Form</button>
            <button className="btn btn-primary" onClick={handleSave} disabled={saving}>{saving ? 'Saving...' : editing ? 'Update' : 'Create'}</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="container" style={{ maxWidth: '1000px', margin: '0 auto' }}>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h1>Rate Codes</h1>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>Named pricing templates — assign to customers for custom rates</p>
        </div>
        <button className="btn btn-primary" onClick={openCreate}>+ New Rate Code</button>
      </div>

      {rateCodes.length === 0 ? (
        <div className="card" style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>
          No rate codes yet. Create one to get started.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
          {rateCodes.map(rc => (
            <div key={rc.id} id={`rc-${rc.id}`} className="card" style={{ padding: '1rem', border: expandedId === rc.id ? '1px solid var(--primary)' : undefined }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                    <h3 style={{ margin: 0, fontSize: '1rem' }}>{rc.name}</h3>
                    <span style={{
                      fontSize: '0.7rem', padding: '0.15rem 0.5rem', borderRadius: '999px',
                      background: rc.active ? 'rgba(16,185,129,0.15)' : 'rgba(239,68,68,0.15)',
                      color: rc.active ? '#10b981' : '#ef4444',
                    }}>{rc.active ? 'Active' : 'Inactive'}</span>
                  </div>
                  {rc.description && <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', margin: '0.25rem 0 0' }}>{rc.description}</p>}
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
                    {(rc.eventPricings || []).length} event pricing{(rc.eventPricings || []).length !== 1 ? 's' : ''}
                    {' • '}{(rc.addonPricings || []).length} add-on pricing{(rc.addonPricings || []).length !== 1 ? 's' : ''}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                  <button className="btn btn-secondary" style={{ fontSize: '0.8rem', padding: '0.3rem 0.6rem' }}
                    onClick={() => setExpandedId(expandedId === rc.id ? null : rc.id)}>
                    {expandedId === rc.id ? '▲ Collapse' : '▼ Details'}
                  </button>
                  <button className="btn btn-secondary" style={{ fontSize: '0.8rem', padding: '0.3rem 0.6rem' }} onClick={() => openEdit(rc)}>Edit</button>
                  <button className="btn btn-secondary" style={{ fontSize: '0.8rem', padding: '0.3rem 0.6rem' }} onClick={() => handleToggle(rc.id)}>
                    {rc.active ? 'Deactivate' : 'Activate'}
                  </button>
                  {!rc.active && (
                    <button className="btn adm-danger-btn" style={{ fontSize: '0.8rem', padding: '0.3rem 0.6rem' }} onClick={() => handleDelete(rc)}>
                      <FiTrash2 /> Delete
                    </button>
                  )}
                </div>
              </div>

              {expandedId === rc.id && (
                <div style={{ marginTop: '1rem', borderTop: '1px solid var(--border)', paddingTop: '0.75rem' }}>
                  {(rc.eventPricings || []).length > 0 && (
                    <>
                      <h4 style={{ fontSize: '0.85rem', marginBottom: '0.5rem' }}>Event Type Pricing</h4>
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem', marginBottom: '0.75rem' }}>
                        <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                          <th style={{ textAlign: 'left', padding: '0.3rem' }}>Event</th>
                          <th style={{ textAlign: 'right', padding: '0.3rem' }}>Base</th>
                          <th style={{ textAlign: 'right', padding: '0.3rem' }}>Hourly</th>
                          <th style={{ textAlign: 'right', padding: '0.3rem' }}>Per Guest</th>
                        </tr></thead>
                        <tbody>
                          {rc.eventPricings.map(ep => (
                            <tr key={ep.eventTypeId} style={{ borderBottom: '1px solid var(--border)' }}>
                              <td style={{ padding: '0.3rem' }}>{ep.eventTypeName}</td>
                              <td style={{ padding: '0.3rem', textAlign: 'right' }}>₹{ep.basePrice}</td>
                              <td style={{ padding: '0.3rem', textAlign: 'right' }}>₹{ep.hourlyRate}/hr</td>
                              <td style={{ padding: '0.3rem', textAlign: 'right' }}>₹{ep.pricePerGuest}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </>
                  )}
                  {(rc.addonPricings || []).length > 0 && (
                    <>
                      <h4 style={{ fontSize: '0.85rem', marginBottom: '0.5rem' }}>Add-On Pricing</h4>
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem' }}>
                        <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                          <th style={{ textAlign: 'left', padding: '0.3rem' }}>Add-On</th>
                          <th style={{ textAlign: 'right', padding: '0.3rem' }}>Price</th>
                        </tr></thead>
                        <tbody>
                          {rc.addonPricings.map(ap => (
                            <tr key={ap.addOnId} style={{ borderBottom: '1px solid var(--border)' }}>
                              <td style={{ padding: '0.3rem' }}>{ap.addOnName}</td>
                              <td style={{ padding: '0.3rem', textAlign: 'right' }}>₹{ap.price}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </>
                  )}
                  {(rc.eventPricings || []).length === 0 && (rc.addonPricings || []).length === 0 && (
                    <p style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>No custom pricing set — all items use default pricing.</p>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
