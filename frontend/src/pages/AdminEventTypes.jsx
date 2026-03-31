import { useState, useEffect } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';

const ADDON_CATEGORIES = ['DECORATION', 'BEVERAGE', 'PHOTOGRAPHY', 'EFFECT', 'FOOD', 'EXPERIENCE'];

const emptyEventType = { name: '', description: '', basePrice: '', hourlyRate: '', pricePerGuest: '', minHours: 1, maxHours: 8, imageUrls: [''] };
const emptyAddOn = { name: '', description: '', price: '', category: 'DECORATION', imageUrls: [''] };

export default function AdminEventTypes() {
  const [tab, setTab] = useState('eventTypes');
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  const [loading, setLoading] = useState(true);

  // Modal state
  const [modal, setModal] = useState({ open: false, mode: 'create', item: null });
  const [form, setForm] = useState(emptyEventType);
  const [addonForm, setAddonForm] = useState(emptyAddOn);
  const [saving, setSaving] = useState(false);

  const fetchAll = () => {
    setLoading(true);
    Promise.all([adminService.getAllEventTypes(), adminService.getAllAddOns()])
      .then(([etRes, aoRes]) => {
        setEventTypes(etRes.data.data || []);
        setAddOns(aoRes.data.data || []);
      })
      .catch(() => toast.error('Failed to load data'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchAll(); }, []);

  // ── Event Type handlers ──────────────────────────────────

  const openCreateET = () => {
    setForm(emptyEventType);
    setModal({ open: true, mode: 'create', item: null, type: 'et' });
  };

  const openEditET = (et) => {
    setForm({
      name: et.name,
      description: et.description || '',
      basePrice: et.basePrice,
      hourlyRate: et.hourlyRate,
      pricePerGuest: et.pricePerGuest || '',
      minHours: et.minHours,
      maxHours: et.maxHours,
      imageUrls: et.imageUrls?.length ? [...et.imageUrls] : [''],
    });
    setModal({ open: true, mode: 'edit', item: et, type: 'et' });
  };

  const handleSaveET = async (e) => {
    e.preventDefault();
    if (!form.name.trim()) { toast.error('Event type name is required.'); return; }
    if (!form.basePrice || Number(form.basePrice) < 0) { toast.error('Base price must be a positive number.'); return; }
    if (!form.hourlyRate || Number(form.hourlyRate) < 0) { toast.error('Hourly rate must be a positive number.'); return; }
    if (Number(form.minHours) >= Number(form.maxHours)) { toast.error('Min hours must be less than max hours.'); return; }
    setSaving(true);
    try {
      const payload = {
        ...form,
        basePrice: Number(form.basePrice),
        hourlyRate: Number(form.hourlyRate),
        pricePerGuest: Number(form.pricePerGuest) || 0,
        minHours: Number(form.minHours),
        maxHours: Number(form.maxHours),
        imageUrls: form.imageUrls.filter(u => u.trim()),
      };
      if (modal.mode === 'create') {
        await adminService.createEventType(payload);
        toast.success('Event type created');
      } else {
        await adminService.updateEventType(modal.item.id, payload);
        toast.success('Event type updated');
      }
      setModal({ open: false });
      fetchAll();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save event type.');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleET = async (et) => {
    if (!confirm(`${et.active ? 'Deactivate' : 'Activate'} "${et.name}"?`)) return;
    try {
      await adminService.toggleEventType(et.id);
      toast.success(`Event type ${et.active ? 'deactivated' : 'activated'}`);
      fetchAll();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  // ── Add-On handlers ──────────────────────────────────────

  const openCreateAO = () => {
    setAddonForm(emptyAddOn);
    setModal({ open: true, mode: 'create', item: null, type: 'ao' });
  };

  const openEditAO = (ao) => {
    setAddonForm({
      name: ao.name,
      description: ao.description || '',
      price: ao.price,
      category: ao.category,
      imageUrls: ao.imageUrls?.length ? [...ao.imageUrls] : [''],
    });
    setModal({ open: true, mode: 'edit', item: ao, type: 'ao' });
  };

  const handleSaveAO = async (e) => {
    e.preventDefault();
    if (!addonForm.name.trim()) { toast.error('Add-on name is required.'); return; }
    if (!addonForm.price || Number(addonForm.price) < 0) { toast.error('Price must be a positive number.'); return; }
    setSaving(true);
    try {
      const payload = { ...addonForm, price: Number(addonForm.price), imageUrls: addonForm.imageUrls.filter(u => u.trim()) };
      if (modal.mode === 'create') {
        await adminService.createAddOn(payload);
        toast.success('Add-on created');
      } else {
        await adminService.updateAddOn(modal.item.id, payload);
        toast.success('Add-on updated');
      }
      setModal({ open: false });
      fetchAll();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save add-on.');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleAO = async (ao) => {
    if (!confirm(`${ao.active ? 'Deactivate' : 'Activate'} "${ao.name}"?`)) return;
    try {
      await adminService.toggleAddOn(ao.id);
      toast.success(`Add-on ${ao.active ? 'deactivated' : 'activated'}`);
      fetchAll();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  return (
    <div className="container">
      <div className="page-header">
        <h1>Event Types & Add-Ons</h1>
        <p>Manage pricing and available packages</p>
      </div>

      {/* Tabs */}
      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1.5rem' }}>
        <button className={`btn ${tab === 'eventTypes' ? 'btn-primary' : 'btn-secondary'} btn-sm`}
          onClick={() => setTab('eventTypes')}>Event Types</button>
        <button className={`btn ${tab === 'addOns' ? 'btn-primary' : 'btn-secondary'} btn-sm`}
          onClick={() => setTab('addOns')}>Add-Ons</button>
      </div>

      {loading ? (
        <div className="loading"><div className="spinner"></div></div>
      ) : tab === 'eventTypes' ? (
        <>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '1rem' }}>
            <button className="btn btn-primary btn-sm" onClick={openCreateET}>+ New Event Type</button>
          </div>
          <div className="grid-3">
            {eventTypes.map(et => (
              <div key={et.id} className="card" style={{ opacity: et.active ? 1 : 0.6 }}>
                {et.imageUrls?.length > 0 && et.imageUrls[0] && (
                  <div style={{ marginBottom: '0.75rem', borderRadius: 'var(--radius-sm)', overflow: 'hidden', maxHeight: '150px' }}>
                    <img src={et.imageUrls[0]} alt={et.name} style={{ width: '100%', height: '150px', objectFit: 'cover' }} />
                    {et.imageUrls.length > 1 && <div style={{ textAlign: 'center', fontSize: '0.7rem', color: 'var(--text-muted)', padding: '0.2rem' }}>+{et.imageUrls.length - 1} more</div>}
                  </div>
                )}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <div>
                    <p style={{ fontWeight: 700, fontSize: '1rem' }}>{et.name}</p>
                    {!et.active && <span className="badge badge-danger" style={{ marginBottom: '0.25rem' }}>Inactive</span>}
                    <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>{et.description}</p>
                  </div>
                </div>
                <div style={{ marginTop: '0.75rem', fontSize: '0.85rem' }}>
                  <p>Base: <strong>₹{Number(et.basePrice).toLocaleString()}</strong></p>
                  <p>Hourly: <strong>₹{Number(et.hourlyRate).toLocaleString()}/hr</strong></p>
                  {Number(et.pricePerGuest) > 0 && <p>Per Guest: <strong>₹{Number(et.pricePerGuest).toLocaleString()}</strong></p>}
                  <p>Duration: <strong>{et.minHours}–{et.maxHours} hrs</strong></p>
                </div>
                <div style={{ display: 'flex', gap: '0.4rem', marginTop: '0.75rem' }}>
                  <button className="btn btn-sm btn-secondary" onClick={() => openEditET(et)}>Edit</button>
                  <button className={`btn btn-sm ${et.active ? 'btn-danger' : 'btn-primary'}`}
                    onClick={() => handleToggleET(et)}>{et.active ? 'Deactivate' : 'Activate'}</button>
                </div>
              </div>
            ))}
            {eventTypes.length === 0 && <p style={{ color: 'var(--text-muted)' }}>No event types found</p>}
          </div>
        </>
      ) : (
        <>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '1rem' }}>
            <button className="btn btn-primary btn-sm" onClick={openCreateAO}>+ New Add-On</button>
          </div>
          <div className="grid-3">
            {addOns.map(ao => (
              <div key={ao.id} className="card" style={{ opacity: ao.active ? 1 : 0.6 }}>
                {ao.imageUrls?.length > 0 && ao.imageUrls[0] && (
                  <div style={{ marginBottom: '0.75rem', borderRadius: 'var(--radius-sm)', overflow: 'hidden', maxHeight: '120px' }}>
                    <img src={ao.imageUrls[0]} alt={ao.name} style={{ width: '100%', height: '120px', objectFit: 'cover' }} />
                    {ao.imageUrls.length > 1 && <div style={{ textAlign: 'center', fontSize: '0.7rem', color: 'var(--text-muted)', padding: '0.2rem' }}>+{ao.imageUrls.length - 1} more</div>}
                  </div>
                )}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <div>
                    <p style={{ fontWeight: 700, fontSize: '1rem' }}>{ao.name}</p>
                    <span className="badge badge-info" style={{ fontSize: '0.7rem' }}>{ao.category}</span>
                    {!ao.active && <span className="badge badge-danger" style={{ marginLeft: '0.3rem', fontSize: '0.7rem' }}>Inactive</span>}
                    <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>{ao.description}</p>
                  </div>
                </div>
                <p style={{ marginTop: '0.5rem', fontWeight: 700 }}>₹{Number(ao.price).toLocaleString()}</p>
                <div style={{ display: 'flex', gap: '0.4rem', marginTop: '0.75rem' }}>
                  <button className="btn btn-sm btn-secondary" onClick={() => openEditAO(ao)}>Edit</button>
                  <button className={`btn btn-sm ${ao.active ? 'btn-danger' : 'btn-primary'}`}
                    onClick={() => handleToggleAO(ao)}>{ao.active ? 'Deactivate' : 'Activate'}</button>
                </div>
              </div>
            ))}
            {addOns.length === 0 && <p style={{ color: 'var(--text-muted)' }}>No add-ons found</p>}
          </div>
        </>
      )}

      {/* Modal */}
      {modal.open && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', display: 'flex',
          alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: '1rem',
        }}>
          <div className="card" style={{ width: '100%', maxWidth: '480px', maxHeight: '90vh', overflowY: 'auto' }}>
            <h3 style={{ marginBottom: '1.25rem' }}>
              {modal.mode === 'create' ? 'Create' : 'Edit'} {modal.type === 'et' ? 'Event Type' : 'Add-On'}
            </h3>

            {modal.type === 'et' ? (
              <form onSubmit={handleSaveET}>
                <div className="input-group">
                  <label>Name *</label>
                  <input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Birthday Party" />
                </div>
                <div className="input-group">
                  <label>Description</label>
                  <textarea value={form.description} onChange={e => setForm({ ...form, description: e.target.value })}
                    placeholder="A short description..." rows={2} style={{ width: '100%', resize: 'vertical', padding: '0.5rem', background: 'var(--bg-input)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)', color: 'var(--text)' }} />
                </div>
                <div className="grid-2">
                  <div className="input-group">
                    <label>Base Price (₹) *</label>
                    <input type="number" required min="0" step="0.01" value={form.basePrice}
                      onChange={e => setForm({ ...form, basePrice: e.target.value })} placeholder="1000" />
                  </div>
                  <div className="input-group">
                    <label>Hourly Rate (₹) *</label>
                    <input type="number" required min="0" step="0.01" value={form.hourlyRate}
                      onChange={e => setForm({ ...form, hourlyRate: e.target.value })} placeholder="500" />
                  </div>
                </div>
                <div className="input-group">
                  <label>Price Per Guest (₹)</label>
                  <input type="number" min="0" step="0.01" value={form.pricePerGuest}
                    onChange={e => setForm({ ...form, pricePerGuest: e.target.value })} placeholder="0 (extra guest surcharge)" />
                  <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Charged for each additional guest (2nd guest onward)</span>
                </div>
                <div className="grid-2">
                  <div className="input-group">
                    <label>Min Hours</label>
                    <input type="number" min="1" value={form.minHours}
                      onChange={e => setForm({ ...form, minHours: e.target.value })} />
                  </div>
                  <div className="input-group">
                    <label>Max Hours</label>
                    <input type="number" min="1" max="24" value={form.maxHours}
                      onChange={e => setForm({ ...form, maxHours: e.target.value })} />
                  </div>
                </div>
                <div className="input-group">
                  <label>Image URLs</label>
                  {form.imageUrls.map((url, i) => (
                    <div key={i} style={{ display: 'flex', gap: '0.3rem', marginBottom: '0.3rem', alignItems: 'center' }}>
                      <input value={url} onChange={e => { const urls = [...form.imageUrls]; urls[i] = e.target.value; setForm({ ...form, imageUrls: urls }); }}
                        placeholder="https://example.com/image.jpg" style={{ flex: 1 }} />
                      {form.imageUrls.length > 1 && <button type="button" className="btn btn-sm btn-danger" onClick={() => { const urls = form.imageUrls.filter((_, j) => j !== i); setForm({ ...form, imageUrls: urls }); }} style={{ padding: '0.2rem 0.5rem' }}>×</button>}
                    </div>
                  ))}
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => setForm({ ...form, imageUrls: [...form.imageUrls, ''] })} style={{ marginTop: '0.25rem' }}>+ Add Image</button>
                  {form.imageUrls.filter(u => u.trim()).length > 0 && (
                    <div style={{ display: 'flex', gap: '0.3rem', marginTop: '0.5rem', flexWrap: 'wrap' }}>
                      {form.imageUrls.filter(u => u.trim()).map((url, i) => (
                        <img key={i} src={url} alt={`Preview ${i+1}`} style={{ height: '60px', borderRadius: 'var(--radius-sm)', objectFit: 'cover' }} />
                      ))}
                    </div>
                  )}
                </div>
                <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
                  <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
                    {saving ? 'Saving...' : 'Save'}
                  </button>
                  <button type="button" className="btn btn-secondary btn-sm" onClick={() => setModal({ open: false })}>Cancel</button>
                </div>
              </form>
            ) : (
              <form onSubmit={handleSaveAO}>
                <div className="input-group">
                  <label>Name *</label>
                  <input required value={addonForm.name} onChange={e => setAddonForm({ ...addonForm, name: e.target.value })} placeholder="Rose Decoration" />
                </div>
                <div className="input-group">
                  <label>Description</label>
                  <textarea value={addonForm.description} onChange={e => setAddonForm({ ...addonForm, description: e.target.value })}
                    placeholder="A short description..." rows={2} style={{ width: '100%', resize: 'vertical', padding: '0.5rem', background: 'var(--bg-input)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)', color: 'var(--text)' }} />
                </div>
                <div className="grid-2">
                  <div className="input-group">
                    <label>Price (₹) *</label>
                    <input type="number" required min="0" step="0.01" value={addonForm.price}
                      onChange={e => setAddonForm({ ...addonForm, price: e.target.value })} placeholder="500" />
                  </div>
                  <div className="input-group">
                    <label>Category *</label>
                    <select value={addonForm.category} onChange={e => setAddonForm({ ...addonForm, category: e.target.value })}>
                      {ADDON_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                  </div>
                </div>
                <div className="input-group">
                  <label>Image URLs</label>
                  {addonForm.imageUrls.map((url, i) => (
                    <div key={i} style={{ display: 'flex', gap: '0.3rem', marginBottom: '0.3rem', alignItems: 'center' }}>
                      <input value={url} onChange={e => { const urls = [...addonForm.imageUrls]; urls[i] = e.target.value; setAddonForm({ ...addonForm, imageUrls: urls }); }}
                        placeholder="https://example.com/image.jpg" style={{ flex: 1 }} />
                      {addonForm.imageUrls.length > 1 && <button type="button" className="btn btn-sm btn-danger" onClick={() => { const urls = addonForm.imageUrls.filter((_, j) => j !== i); setAddonForm({ ...addonForm, imageUrls: urls }); }} style={{ padding: '0.2rem 0.5rem' }}>×</button>}
                    </div>
                  ))}
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => setAddonForm({ ...addonForm, imageUrls: [...addonForm.imageUrls, ''] })} style={{ marginTop: '0.25rem' }}>+ Add Image</button>
                  {addonForm.imageUrls.filter(u => u.trim()).length > 0 && (
                    <div style={{ display: 'flex', gap: '0.3rem', marginTop: '0.5rem', flexWrap: 'wrap' }}>
                      {addonForm.imageUrls.filter(u => u.trim()).map((url, i) => (
                        <img key={i} src={url} alt={`Preview ${i+1}`} style={{ height: '60px', borderRadius: 'var(--radius-sm)', objectFit: 'cover' }} />
                      ))}
                    </div>
                  )}
                </div>
                <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
                  <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
                    {saving ? 'Saving...' : 'Save'}
                  </button>
                  <button type="button" className="btn btn-secondary btn-sm" onClick={() => setModal({ open: false })}>Cancel</button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
