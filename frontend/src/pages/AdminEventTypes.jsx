import { useState, useEffect } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiPackage, FiPlus, FiEdit2, FiToggleLeft, FiToggleRight, FiTrash2, FiX, FiImage } from 'react-icons/fi';
import './AdminPages.css';

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
      toast.error(err.userMessage || err.response?.data?.message || 'Failed');
    }
  };

  const handleDeleteET = async (et) => {
    if (et.active) {
      toast.error('Deactivate the event type before deleting it');
      return;
    }
    if (!confirm(`Delete event type "${et.name}" permanently? This cannot be undone.`)) return;

    try {
      await adminService.deleteEventType(et.id);
      toast.success('Event type deleted');
      fetchAll();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to delete event type.');
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
      toast.error(err.userMessage || err.response?.data?.message || 'Failed');
    }
  };

  const handleDeleteAO = async (ao) => {
    if (ao.active) {
      toast.error('Deactivate the add-on before deleting it');
      return;
    }
    if (!confirm(`Delete add-on "${ao.name}" permanently? This cannot be undone.`)) return;

    try {
      await adminService.deleteAddOn(ao.id);
      toast.success('Add-on deleted');
      fetchAll();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to delete add-on.');
    }
  };

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiPackage /> Catalog</span>
          <h1>Event Types & Add-Ons</h1>
          <p>Manage pricing, packages, and available add-ons for your venue.</p>
        </div>
      </div>

      {/* Tabs */}
      <div className="adm-toolbar">
        <div className="adm-tabs">
          <button className={`adm-tab${tab === 'eventTypes' ? ' active' : ''}`}
            onClick={() => setTab('eventTypes')}>Event Types</button>
          <button className={`adm-tab${tab === 'addOns' ? ' active' : ''}`}
            onClick={() => setTab('addOns')}>Add-Ons</button>
        </div>
        <div className="adm-toolbar-right">
          {tab === 'eventTypes'
            ? <button className="btn btn-primary btn-sm" onClick={openCreateET}><FiPlus /> New Event Type</button>
            : <button className="btn btn-primary btn-sm" onClick={openCreateAO}><FiPlus /> New Add-On</button>}
        </div>
      </div>

      {loading ? (
        <div className="loading"><div className="spinner"></div></div>
      ) : tab === 'eventTypes' ? (
        eventTypes.length === 0 ? (
          <div className="adm-empty">
            <span className="adm-empty-icon"><FiPackage /></span>
            <h3>No event types yet</h3>
            <p>Create your first event type to start accepting bookings.</p>
          </div>
        ) : (
          <div className="adm-grid-3">
            {eventTypes.map(et => (
              <div key={et.id} className={`adm-item${et.active ? '' : ' inactive'}`}>
                {et.imageUrls?.length > 0 && et.imageUrls[0] && (
                  <>
                    <img src={et.imageUrls[0]} alt={et.name} className="adm-item-img" />
                    {et.imageUrls.length > 1 && <div className="adm-item-img-more">+{et.imageUrls.length - 1} more</div>}
                  </>
                )}
                <div className="adm-item-body">
                  <span className="adm-item-name">{et.name}</span>
                  {!et.active && <span className="adm-badge adm-badge-inactive">Inactive</span>}
                  {et.description && <span className="adm-item-desc">{et.description}</span>}
                  <div className="adm-item-meta">
                    <span>Base: <strong>₹{Number(et.basePrice).toLocaleString()}</strong></span>
                    <span>Hourly: <strong>₹{Number(et.hourlyRate).toLocaleString()}/hr</strong></span>
                    {Number(et.pricePerGuest) > 0 && <span>Per Guest: <strong>₹{Number(et.pricePerGuest).toLocaleString()}</strong></span>}
                    <span>Duration: <strong>{et.minHours}–{et.maxHours} hrs</strong></span>
                  </div>
                </div>
                <div className="adm-item-footer">
                  <button className="btn btn-sm btn-secondary" onClick={() => openEditET(et)}><FiEdit2 style={{ marginRight: 3 }} /> Edit</button>
                  <button className={`btn btn-sm ${et.active ? 'btn-danger' : 'btn-primary'}`}
                    onClick={() => handleToggleET(et)}>
                    {et.active ? <><FiToggleLeft style={{ marginRight: 3 }} /> Deactivate</> : <><FiToggleRight style={{ marginRight: 3 }} /> Activate</>}
                  </button>
                  {!et.active && (
                    <button className="btn adm-danger-btn btn-sm" onClick={() => handleDeleteET(et)}>
                      <FiTrash2 style={{ marginRight: 3 }} /> Delete
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )
      ) : (
        addOns.length === 0 ? (
          <div className="adm-empty">
            <span className="adm-empty-icon"><FiPackage /></span>
            <h3>No add-ons yet</h3>
            <p>Create add-ons like decorations, beverages, or photography.</p>
          </div>
        ) : (
          <div className="adm-grid-3">
            {addOns.map(ao => (
              <div key={ao.id} className={`adm-item${ao.active ? '' : ' inactive'}`}>
                {ao.imageUrls?.length > 0 && ao.imageUrls[0] && (
                  <>
                    <img src={ao.imageUrls[0]} alt={ao.name} className="adm-item-img" style={{ height: 120 }} />
                    {ao.imageUrls.length > 1 && <div className="adm-item-img-more">+{ao.imageUrls.length - 1} more</div>}
                  </>
                )}
                <div className="adm-item-body">
                  <span className="adm-item-name">{ao.name}</span>
                  <div style={{ display: 'flex', gap: '0.35rem', flexWrap: 'wrap' }}>
                    <span className="adm-badge adm-badge-info">{ao.category}</span>
                    {!ao.active && <span className="adm-badge adm-badge-inactive">Inactive</span>}
                  </div>
                  {ao.description && <span className="adm-item-desc">{ao.description}</span>}
                  <span style={{ marginTop: '0.4rem', fontWeight: 700, fontSize: '1.1rem', color: 'var(--text)' }}>₹{Number(ao.price).toLocaleString()}</span>
                </div>
                <div className="adm-item-footer">
                  <button className="btn btn-sm btn-secondary" onClick={() => openEditAO(ao)}><FiEdit2 style={{ marginRight: 3 }} /> Edit</button>
                  <button className={`btn btn-sm ${ao.active ? 'btn-danger' : 'btn-primary'}`}
                    onClick={() => handleToggleAO(ao)}>
                    {ao.active ? <><FiToggleLeft style={{ marginRight: 3 }} /> Deactivate</> : <><FiToggleRight style={{ marginRight: 3 }} /> Activate</>}
                  </button>
                  {!ao.active && (
                    <button className="btn adm-danger-btn btn-sm" onClick={() => handleDeleteAO(ao)}>
                      <FiTrash2 style={{ marginRight: 3 }} /> Delete
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )
      )}

      {/* Modal */}
      {modal.open && (
        <div className="adm-modal-overlay" onClick={(e) => e.target === e.currentTarget && setModal({ open: false })}>
          <div className="adm-modal">
            <h3>
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
                  <textarea className="adm-textarea" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })}
                    placeholder="A short description..." rows={2} />
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
                  <span className="adm-hint">Charged for each additional guest (2nd guest onward)</span>
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
                  <label><FiImage style={{ marginRight: 4, verticalAlign: -2 }} />Image URLs</label>
                  {form.imageUrls.map((url, i) => (
                    <div key={i} className="adm-img-row">
                      <input value={url} onChange={e => { const urls = [...form.imageUrls]; urls[i] = e.target.value; setForm({ ...form, imageUrls: urls }); }}
                        placeholder="https://example.com/image.jpg" />
                      {form.imageUrls.length > 1 && <button type="button" className="btn btn-sm btn-danger" onClick={() => { const urls = form.imageUrls.filter((_, j) => j !== i); setForm({ ...form, imageUrls: urls }); }} style={{ padding: '0.2rem 0.5rem' }}><FiX /></button>}
                    </div>
                  ))}
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => setForm({ ...form, imageUrls: [...form.imageUrls, ''] })} style={{ marginTop: '0.25rem' }}><FiPlus /> Add Image</button>
                  {form.imageUrls.filter(u => u.trim()).length > 0 && (
                    <div className="adm-img-previews">
                      {form.imageUrls.filter(u => u.trim()).map((url, i) => (
                        <img key={i} src={url} alt={`Preview ${i+1}`} />
                      ))}
                    </div>
                  )}
                </div>
                <div className="adm-modal-actions">
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
                  <textarea className="adm-textarea" value={addonForm.description} onChange={e => setAddonForm({ ...addonForm, description: e.target.value })}
                    placeholder="A short description..." rows={2} />
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
                  <label><FiImage style={{ marginRight: 4, verticalAlign: -2 }} />Image URLs</label>
                  {addonForm.imageUrls.map((url, i) => (
                    <div key={i} className="adm-img-row">
                      <input value={url} onChange={e => { const urls = [...addonForm.imageUrls]; urls[i] = e.target.value; setAddonForm({ ...addonForm, imageUrls: urls }); }}
                        placeholder="https://example.com/image.jpg" />
                      {addonForm.imageUrls.length > 1 && <button type="button" className="btn btn-sm btn-danger" onClick={() => { const urls = addonForm.imageUrls.filter((_, j) => j !== i); setAddonForm({ ...addonForm, imageUrls: urls }); }} style={{ padding: '0.2rem 0.5rem' }}><FiX /></button>}
                    </div>
                  ))}
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => setAddonForm({ ...addonForm, imageUrls: [...addonForm.imageUrls, ''] })} style={{ marginTop: '0.25rem' }}><FiPlus /> Add Image</button>
                  {addonForm.imageUrls.filter(u => u.trim()).length > 0 && (
                    <div className="adm-img-previews">
                      {addonForm.imageUrls.filter(u => u.trim()).map((url, i) => (
                        <img key={i} src={url} alt={`Preview ${i+1}`} />
                      ))}
                    </div>
                  )}
                </div>
                <div className="adm-modal-actions">
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
