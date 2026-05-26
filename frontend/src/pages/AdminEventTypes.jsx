import { useState, useEffect } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiPackage, FiPlus, FiEdit2, FiToggleLeft, FiToggleRight, FiTrash2, FiX, FiImage } from 'react-icons/fi';
import './AdminPages.css';

const emptyEventType = { name: '', description: '', basePrice: '', hourlyRate: '', pricePerGuest: '', minHours: 1, maxHours: 8, minGuests: '', maxGuests: '', categoryId: '', imageUrls: [''] };
const emptyAddOn = { name: '', description: '', price: '', categoryId: '', stockPerDay: '', advanceNoticeMinutes: '', imageUrls: [''] };
const emptyCategory = { name: '', description: '', imageUrl: '', sortOrder: 0 };

export default function AdminEventTypes() {
  const [tab, setTab] = useState('eventTypes');
  const [eventTypes, setEventTypes] = useState([]);
  const [addOns, setAddOns] = useState([]);
  // V55 — category taxonomy. Loaded eagerly so dropdowns are populated
  // whenever the user opens an event-type / add-on modal.
  const [eventCategories, setEventCategories] = useState([]);
  const [addOnCategories, setAddOnCategories] = useState([]);
  const [loading, setLoading] = useState(true);

  // Modal state
  const [modal, setModal] = useState({ open: false, mode: 'create', item: null });
  const [form, setForm] = useState(emptyEventType);
  const [addonForm, setAddonForm] = useState(emptyAddOn);
  const [categoryForm, setCategoryForm] = useState(emptyCategory);
  const [saving, setSaving] = useState(false);

  const fetchAll = () => {
    setLoading(true);
    Promise.all([
      adminService.getAllEventTypes(),
      adminService.getAllAddOns(),
      adminService.getEventCategories(),
      adminService.getAddOnCategories(),
    ])
      .then(([etRes, aoRes, ecRes, acRes]) => {
        setEventTypes(toArray(etRes.data?.data));
        setAddOns(toArray(aoRes.data?.data));
        setEventCategories(toArray(ecRes.data?.data));
        setAddOnCategories(toArray(acRes.data?.data));
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
      minGuests: et.minGuests ?? '',
      maxGuests: et.maxGuests ?? '',
      categoryId: et.categoryId ?? '',
      imageUrls: et.imageUrls?.length ? [...et.imageUrls] : [''],
    });
    setModal({ open: true, mode: 'edit', item: et, type: 'et' });
  };

  const handleSaveET = async (e) => {
    e.preventDefault();
    if (!form.name.trim()) { toast.error('Event type name is required.'); return; }
    if (!form.basePrice || isNaN(Number(form.basePrice)) || Number(form.basePrice) < 0) { toast.error('Base price must be a valid positive number.'); return; }
    if (!form.hourlyRate || isNaN(Number(form.hourlyRate)) || Number(form.hourlyRate) < 0) { toast.error('Hourly rate must be a valid positive number.'); return; }
    if (Number(form.minHours) >= Number(form.maxHours)) { toast.error('Min hours must be less than max hours.'); return; }
    if (form.minGuests !== '' && form.maxGuests !== '' && Number(form.minGuests) > Number(form.maxGuests)) {
      toast.error('Min guests must be ≤ max guests.'); return;
    }
    if (form.minGuests !== '' && Number(form.minGuests) < 0) { toast.error('Min guests cannot be negative.'); return; }
    if (form.maxGuests !== '' && Number(form.maxGuests) < 0) { toast.error('Max guests cannot be negative.'); return; }
    setSaving(true);
    try {
      const payload = {
        ...form,
        basePrice: Number(form.basePrice),
        hourlyRate: Number(form.hourlyRate),
        pricePerGuest: Number(form.pricePerGuest) || 0,
        minHours: Number(form.minHours),
        maxHours: Number(form.maxHours),
        minGuests: form.minGuests === '' ? null : Number(form.minGuests),
        maxGuests: form.maxGuests === '' ? null : Number(form.maxGuests),
        categoryId: form.categoryId === '' ? null : Number(form.categoryId),
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
      categoryId: ao.categoryId ?? '',
      stockPerDay: ao.stockPerDay ?? '',
      advanceNoticeMinutes: ao.advanceNoticeMinutes ?? '',
      imageUrls: ao.imageUrls?.length ? [...ao.imageUrls] : [''],
    });
    setModal({ open: true, mode: 'edit', item: ao, type: 'ao' });
  };

  const handleSaveAO = async (e) => {
    e.preventDefault();
    if (!addonForm.name.trim()) { toast.error('Add-on name is required.'); return; }
    if (!addonForm.price || Number(addonForm.price) < 0) { toast.error('Price must be a positive number.'); return; }
    if (addonForm.categoryId === '' || addonForm.categoryId == null) { toast.error('Please select a category.'); return; }
    setSaving(true);
    try {
      if (addonForm.stockPerDay !== '' && Number(addonForm.stockPerDay) < 0) { toast.error('Stock per day cannot be negative.'); setSaving(false); return; }
      if (addonForm.advanceNoticeMinutes !== '' && Number(addonForm.advanceNoticeMinutes) < 0) { toast.error('Advance notice cannot be negative.'); setSaving(false); return; }
      const payload = {
        ...addonForm,
        price: Number(addonForm.price),
        categoryId: addonForm.categoryId === '' ? null : Number(addonForm.categoryId),
        stockPerDay: addonForm.stockPerDay === '' ? null : Number(addonForm.stockPerDay),
        advanceNoticeMinutes: addonForm.advanceNoticeMinutes === '' ? null : Number(addonForm.advanceNoticeMinutes),
        imageUrls: addonForm.imageUrls.filter(u => u.trim()),
      };
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

  // ── Category handlers (V55) ──────────────────────────────
  // `kind` is 'event' or 'addon'. Same CRUD shape on the backend so we
  // route through a single openCreate/openEdit/handleSave pair to keep
  // the page lean.
  const catApi = (kind) => kind === 'event'
    ? { create: adminService.createEventCategory, update: adminService.updateEventCategory,
        toggle: adminService.toggleEventCategory, remove: adminService.deleteEventCategory }
    : { create: adminService.createAddOnCategory, update: adminService.updateAddOnCategory,
        toggle: adminService.toggleAddOnCategory, remove: adminService.deleteAddOnCategory };

  const openCreateCategory = (kind) => {
    setCategoryForm(emptyCategory);
    setModal({ open: true, mode: 'create', item: null, type: 'cat', kind });
  };

  const openEditCategory = (c, kind) => {
    setCategoryForm({
      name: c.name,
      description: c.description || '',
      imageUrl: c.imageUrl || '',
      sortOrder: c.sortOrder ?? 0,
    });
    setModal({ open: true, mode: 'edit', item: c, type: 'cat', kind });
  };

  const handleSaveCategory = async (e) => {
    e.preventDefault();
    if (!categoryForm.name.trim()) { toast.error('Category name is required.'); return; }
    setSaving(true);
    try {
      const payload = {
        name: categoryForm.name.trim(),
        description: categoryForm.description?.trim() || null,
        imageUrl: categoryForm.imageUrl?.trim() || null,
        sortOrder: Number(categoryForm.sortOrder) || 0,
      };
      const api = catApi(modal.kind);
      if (modal.mode === 'create') {
        await api.create(payload);
        toast.success('Category created');
      } else {
        await api.update(modal.item.id, payload);
        toast.success('Category updated');
      }
      setModal({ open: false });
      fetchAll();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save category.');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleCategory = async (c, kind) => {
    if (!confirm(`${c.active ? 'Deactivate' : 'Activate'} category "${c.name}"?`)) return;
    try {
      await catApi(kind).toggle(c.id);
      toast.success(`Category ${c.active ? 'deactivated' : 'activated'}`);
      fetchAll();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed');
    }
  };

  const handleDeleteCategory = async (c, kind) => {
    if (c.active) { toast.error('Deactivate the category before deleting it'); return; }
    if (!confirm(`Delete category "${c.name}" permanently? Existing items become uncategorized.`)) return;
    try {
      await catApi(kind).remove(c.id);
      toast.success('Category deleted');
      fetchAll();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to delete category.');
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
          <button className={`adm-tab${tab === 'eventCats' ? ' active' : ''}`}
            onClick={() => setTab('eventCats')}>Event Categories</button>
          <button className={`adm-tab${tab === 'addonCats' ? ' active' : ''}`}
            onClick={() => setTab('addonCats')}>Add-On Categories</button>
        </div>
        <div className="adm-toolbar-right">
          {tab === 'eventTypes' && <button className="btn btn-primary btn-sm" onClick={openCreateET}><FiPlus /> New Event Type</button>}
          {tab === 'addOns'     && <button className="btn btn-primary btn-sm" onClick={openCreateAO}><FiPlus /> New Add-On</button>}
          {tab === 'eventCats'  && <button className="btn btn-primary btn-sm" onClick={() => openCreateCategory('event')}><FiPlus /> New Event Category</button>}
          {tab === 'addonCats'  && <button className="btn btn-primary btn-sm" onClick={() => openCreateCategory('addon')}><FiPlus /> New Add-On Category</button>}
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
                  <div style={{ display: 'flex', gap: '0.35rem', flexWrap: 'wrap' }}>
                    {et.categoryName && <span className="adm-badge adm-badge-info">{et.categoryName}</span>}
                    {!et.active && <span className="adm-badge adm-badge-inactive">Inactive</span>}
                  </div>
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
      ) : tab === 'addOns' ? (
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
                    <span className="adm-badge adm-badge-info">{ao.categoryName || '—'}</span>
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
      ) : (
        // Categories tabs (event + add-on) — V55 taxonomy management.
        // Same card UI as event types/add-ons for visual consistency.
        (tab === 'eventCats' ? eventCategories : addOnCategories).length === 0 ? (
          <div className="adm-empty">
            <span className="adm-empty-icon"><FiPackage /></span>
            <h3>No categories yet</h3>
            <p>Categories drive the filter chips on the customer wizard. Globals (🌐) are managed by super-admins.</p>
          </div>
        ) : (
          <div className="adm-grid-3">
            {(tab === 'eventCats' ? eventCategories : addOnCategories).map(c => (
              <div key={c.id} className={`adm-item${c.active ? '' : ' inactive'}`}>
                {c.imageUrl && (
                  <img src={c.imageUrl} alt={c.name} className="adm-item-img" style={{ height: 120 }} />
                )}
                <div className="adm-item-body">
                  <span className="adm-item-name">{c.global ? '🌐 ' : ''}{c.name}</span>
                  <div style={{ display: 'flex', gap: '0.35rem', flexWrap: 'wrap' }}>
                    <span className="adm-badge adm-badge-info">sort {c.sortOrder}</span>
                    {c.global && <span className="adm-badge adm-badge-info">global</span>}
                    {!c.active && <span className="adm-badge adm-badge-inactive">Inactive</span>}
                  </div>
                  {c.description && <span className="adm-item-desc">{c.description}</span>}
                </div>
                <div className="adm-item-footer">
                  {!c.global && (
                    <button className="btn btn-sm btn-secondary" onClick={() => openEditCategory(c, tab === 'eventCats' ? 'event' : 'addon')}><FiEdit2 style={{ marginRight: 3 }} /> Edit</button>
                  )}
                  {!c.global && (
                    <button className={`btn btn-sm ${c.active ? 'btn-danger' : 'btn-primary'}`}
                      onClick={() => handleToggleCategory(c, tab === 'eventCats' ? 'event' : 'addon')}>
                      {c.active ? <><FiToggleLeft style={{ marginRight: 3 }} /> Deactivate</> : <><FiToggleRight style={{ marginRight: 3 }} /> Activate</>}
                    </button>
                  )}
                  {!c.global && !c.active && (
                    <button className="btn adm-danger-btn btn-sm" onClick={() => handleDeleteCategory(c, tab === 'eventCats' ? 'event' : 'addon')}>
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
              {modal.mode === 'create' ? 'Create' : 'Edit'}{' '}
              {modal.type === 'et' ? 'Event Type' : modal.type === 'ao' ? 'Add-On'
                : modal.type === 'cat' ? (modal.kind === 'event' ? 'Event Category' : 'Add-On Category')
                : 'Item'}
            </h3>

            {modal.type === 'cat' ? (
              <form onSubmit={handleSaveCategory}>
                <div className="input-group">
                  <label>Name *</label>
                  <input required value={categoryForm.name} maxLength={80}
                    onChange={e => setCategoryForm({ ...categoryForm, name: e.target.value })}
                    placeholder="e.g. Birthdays" />
                </div>
                <div className="input-group">
                  <label>Description <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>({categoryForm.description.length}/500)</span></label>
                  <textarea className="adm-textarea" value={categoryForm.description} maxLength={500}
                    onChange={e => setCategoryForm({ ...categoryForm, description: e.target.value })}
                    placeholder="Optional — shown as a tooltip on the wizard chip." rows={2} />
                </div>
                <div className="grid-2">
                  <div className="input-group">
                    <label>Sort order</label>
                    <input type="number" min="0" value={categoryForm.sortOrder}
                      onChange={e => setCategoryForm({ ...categoryForm, sortOrder: e.target.value })} />
                    <span className="adm-hint">Lower = earlier in the chip row.</span>
                  </div>
                  <div className="input-group">
                    <label>Image URL</label>
                    <input value={categoryForm.imageUrl} maxLength={1000}
                      onChange={e => setCategoryForm({ ...categoryForm, imageUrl: e.target.value })}
                      placeholder="https://…" />
                  </div>
                </div>
                {categoryForm.imageUrl?.trim() && (
                  <div className="adm-img-previews">
                    <img src={categoryForm.imageUrl} alt="Preview" />
                  </div>
                )}
                <div className="adm-modal-actions">
                  <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
                    {saving ? 'Saving…' : 'Save'}
                  </button>
                  <button type="button" className="btn btn-secondary btn-sm" onClick={() => setModal({ open: false })}>Cancel</button>
                </div>
              </form>
            ) : modal.type === 'et' ? (
              <form onSubmit={handleSaveET}>
                <div className="input-group">
                  <label>Name *</label>
                  <input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Birthday Party" />
                </div>
                <div className="input-group">
                  <label>Description <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>({form.description.length}/500)</span></label>
                  <textarea className="adm-textarea" value={form.description} maxLength={500} onChange={e => setForm({ ...form, description: e.target.value })}
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
                <div className="grid-2">
                  <div className="input-group">
                    <label>Min Guests</label>
                    <input type="number" min="0" value={form.minGuests}
                      onChange={e => setForm({ ...form, minGuests: e.target.value })}
                      placeholder="blank = no minimum" />
                  </div>
                  <div className="input-group">
                    <label>Max Guests</label>
                    <input type="number" min="0" value={form.maxGuests}
                      onChange={e => setForm({ ...form, maxGuests: e.target.value })}
                      placeholder="blank = no maximum" />
                  </div>
                </div>
                <div className="input-group">
                  <label>Category</label>
                  <select value={form.categoryId}
                    onChange={e => setForm({ ...form, categoryId: e.target.value })}>
                    <option value="">— Uncategorized —</option>
                    {eventCategories.filter(c => c.active).map(c => (
                      <option key={c.id} value={c.id}>
                        {c.global ? '🌐 ' : ''}{c.name}
                      </option>
                    ))}
                  </select>
                  <span className="adm-hint">Filter chip on the customer wizard. Manage categories in the Categories tab.</span>
                </div>
                <div className="input-group">
                  <label><FiImage style={{ marginRight: 4, verticalAlign: -2 }} />Image URLs</label>
                  {form.imageUrls.map((url, i) => (
                    <div key={i} className="adm-img-row">
                      <input value={url} maxLength={1000} onChange={e => { const urls = [...form.imageUrls]; urls[i] = e.target.value; setForm({ ...form, imageUrls: urls }); }}
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
                  <label>Description <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>({addonForm.description.length}/300)</span></label>
                  <textarea className="adm-textarea" value={addonForm.description} maxLength={300} onChange={e => setAddonForm({ ...addonForm, description: e.target.value })}
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
                    <select value={addonForm.categoryId}
                      onChange={e => setAddonForm({ ...addonForm, categoryId: e.target.value })} required>
                      <option value="">— Select a category —</option>
                      {addOnCategories.filter(c => c.active).map(c => (
                        <option key={c.id} value={c.id}>
                          {c.global ? '🌐 ' : ''}{c.name}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
                <div className="input-group">
                  <span className="adm-hint">Pick from global categories (🌐) or any you've created for this binge under the Categories tab.</span>
                </div>
                <div className="grid-2">
                  <div className="input-group">
                    <label>Stock per day</label>
                    <input type="number" min="0" value={addonForm.stockPerDay}
                      onChange={e => setAddonForm({ ...addonForm, stockPerDay: e.target.value })}
                      placeholder="blank = unlimited" />
                    <span className="adm-hint">Max units bookable per day across all bookings</span>
                  </div>
                  <div className="input-group">
                    <label>Advance notice (minutes)</label>
                    <input type="number" min="0" value={addonForm.advanceNoticeMinutes}
                      onChange={e => setAddonForm({ ...addonForm, advanceNoticeMinutes: e.target.value })}
                      placeholder="blank = no minimum" />
                    <span className="adm-hint">Minimum lead time before the booking start</span>
                  </div>
                </div>
                <div className="input-group">
                  <label><FiImage style={{ marginRight: 4, verticalAlign: -2 }} />Image URLs</label>
                  {addonForm.imageUrls.map((url, i) => (
                    <div key={i} className="adm-img-row">
                      <input value={url} maxLength={1000} onChange={e => { const urls = [...addonForm.imageUrls]; urls[i] = e.target.value; setAddonForm({ ...addonForm, imageUrls: urls }); }}
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
