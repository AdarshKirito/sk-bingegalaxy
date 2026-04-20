import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { adminService, bookingService } from '../services/endpoints';
import {
  createDashboardSlide,
  DASHBOARD_LAYOUT_OPTIONS,
  DASHBOARD_THEME_OPTIONS,
  normalizeDashboardExperience,
  sanitizeDashboardExperienceForSave,
} from '../services/dashboardExperience';
import {
  createAboutHighlight,
  createAboutPolicy,
  normalizeAboutExperience,
  sanitizeAboutExperienceForSave,
} from '../services/aboutExperience';
import { useBinge } from '../context/BingeContext';
import { toast } from 'react-toastify';
import { FiActivity, FiArrowRight, FiCompass, FiEdit2, FiMapPin, FiPlus, FiStar, FiToggleLeft, FiToggleRight, FiTrash2, FiUpload, FiX } from 'react-icons/fi';
import './AdminPages.css';

function StarRating({ avg, count }) {
  const rounded = Math.round((avg || 0) * 2) / 2;
  const stars = [];
  for (let i = 1; i <= 5; i++) {
    if (i <= rounded) stars.push(<FiStar key={i} style={{ fill: '#f59e0b', color: '#f59e0b', verticalAlign: '-2px' }} />);
    else if (i - 0.5 === rounded) stars.push(<FiStar key={i} style={{ fill: '#f59e0b', color: '#f59e0b', opacity: 0.5, verticalAlign: '-2px' }} />);
    else stars.push(<FiStar key={i} style={{ color: '#d1d5db', verticalAlign: '-2px' }} />);
  }
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.85rem', margin: '4px 0' }}>
      {stars} <strong style={{ marginLeft: 2 }}>{(avg || 0).toFixed(1)}</strong>
      <span style={{ color: '#888' }}>({count || 0})</span>
    </span>
  );
}

export default function BingeManagement() {
  const emptyForm = {
    name: '',
    address: '',
    supportEmail: '',
    supportPhone: '',
    supportWhatsapp: '',
    customerCancellationEnabled: true,
    customerCancellationCutoffMinutes: 180,
    maxConcurrentBookings: '',
  };
  const [binges, setBinges] = useState([]);
  const [reviewSummaries, setReviewSummaries] = useState({});
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [dashboardEditor, setDashboardEditor] = useState({ open: false, binge: null });
  const [dashboardForm, setDashboardForm] = useState(() => normalizeDashboardExperience(null));
  const [dashboardLoading, setDashboardLoading] = useState(false);
  const [dashboardSaving, setDashboardSaving] = useState(false);
  const [dashboardEventTypes, setDashboardEventTypes] = useState([]);
  const [aboutEditor, setAboutEditor] = useState({ open: false, binge: null });
  const [aboutForm, setAboutForm] = useState(() => normalizeAboutExperience(null));
  const [aboutLoading, setAboutLoading] = useState(false);
  const [aboutSaving, setAboutSaving] = useState(false);
  const [tierEditor, setTierEditor] = useState({ open: false, binge: null });
  const [tierRows, setTierRows] = useState([]);
  const [tierLoading, setTierLoading] = useState(false);
  const [tierSaving, setTierSaving] = useState(false);
  const { clearBinge, selectBinge, selectedBinge } = useBinge();
  const navigate = useNavigate();

  const fetchBinges = async () => {
    try {
      const res = await adminService.getAdminBinges();
      const list = res.data.data || res.data || [];
      setBinges(list);
      // Fetch review summaries
      const summaries = {};
      await Promise.allSettled(
        list.map(async (b) => {
          try {
            const r = await bookingService.getBingeReviewSummary(b.id);
            summaries[b.id] = r.data.data || r.data || {};
          } catch { summaries[b.id] = {}; }
        })
      );
      setReviewSummaries(summaries);
    } catch (err) {
      toast.error('Failed to load binges');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchBinges(); }, []);

  const resetForm = () => {
    setForm(emptyForm);
    setShowForm(false);
    setEditId(null);
  };

  const resetDashboardEditor = () => {
    setDashboardEditor({ open: false, binge: null });
    setDashboardForm(normalizeDashboardExperience(null));
    setDashboardLoading(false);
    setDashboardSaving(false);
  };

  const resetAboutEditor = () => {
    setAboutEditor({ open: false, binge: null });
    setAboutForm(normalizeAboutExperience(null));
    setAboutLoading(false);
    setAboutSaving(false);
  };

  const handleOpenTierEditor = async (binge) => {
    setTierEditor({ open: true, binge });
    setTierLoading(true);
    try {
      const res = await adminService.getCancellationTiers(binge.id);
      const tiers = res.data.data || res.data || [];
      setTierRows(tiers.length ? tiers.map(t => ({ hoursBeforeStart: t.hoursBeforeStart, refundPercentage: t.refundPercentage, label: t.label || '' })) : [{ hoursBeforeStart: 48, refundPercentage: 100, label: 'Full refund' }, { hoursBeforeStart: 24, refundPercentage: 50, label: 'Half refund' }, { hoursBeforeStart: 0, refundPercentage: 0, label: 'No refund' }]);
    } catch {
      setTierRows([{ hoursBeforeStart: 48, refundPercentage: 100, label: 'Full refund' }, { hoursBeforeStart: 24, refundPercentage: 50, label: 'Half refund' }, { hoursBeforeStart: 0, refundPercentage: 0, label: 'No refund' }]);
    } finally {
      setTierLoading(false);
    }
  };

  const handleSaveTiers = async () => {
    setTierSaving(true);
    try {
      await adminService.saveCancellationTiers(tierEditor.binge.id, { tiers: tierRows });
      toast.success('Cancellation tiers saved');
      setTierEditor({ open: false, binge: null });
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save tiers');
    } finally {
      setTierSaving(false);
    }
  };

  const openCreateForm = () => {
    setEditId(null);
    setForm(emptyForm);
    setShowForm(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.name.trim()) { toast.error('Venue name is required'); return; }
    try {
      if (editId) {
        await adminService.updateBinge(editId, form);
        toast.success('Binge updated');
      } else {
        await adminService.createBinge(form);
        toast.success('Binge created');
      }
      resetForm();
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to save binge');
    }
  };

  const handleEdit = (b) => {
    setEditId(b.id);
    setForm({
      name: b.name,
      address: b.address || '',
      supportEmail: b.supportEmail || '',
      supportPhone: b.supportPhone || '',
      supportWhatsapp: b.supportWhatsapp || '',
      customerCancellationEnabled: b.customerCancellationEnabled !== false,
      customerCancellationCutoffMinutes: b.customerCancellationCutoffMinutes ?? 180,
      maxConcurrentBookings: b.maxConcurrentBookings ?? '',
    });
    setShowForm(true);
  };

  const handleOpenDashboardEditor = async (binge) => {
    setDashboardEditor({ open: true, binge });
    setDashboardLoading(true);
    try {
      const [dashRes, etRes] = await Promise.all([
        adminService.getBingeDashboardExperience(binge.id),
        adminService.getAllEventTypes().catch(() => ({ data: { data: [] } })),
      ]);
      setDashboardForm(normalizeDashboardExperience(dashRes.data.data || dashRes.data || null));
      setDashboardEventTypes((etRes.data.data || etRes.data || []).filter((e) => e.active !== false));
    } catch (err) {
      toast.error(err.userMessage || 'Failed to load customer dashboard setup');
      setDashboardEditor({ open: false, binge: null });
    } finally {
      setDashboardLoading(false);
    }
  };

  const handleOpenAboutEditor = async (binge) => {
    setAboutEditor({ open: true, binge });
    setAboutLoading(true);
    try {
      const res = await adminService.getBingeAboutExperience(binge.id);
      setAboutForm(normalizeAboutExperience(res.data.data || res.data || null));
    } catch (err) {
      toast.error(err.userMessage || 'Failed to load customer about page content');
      setAboutEditor({ open: false, binge: null });
    } finally {
      setAboutLoading(false);
    }
  };

  const updateDashboardField = (field, value) => {
    setDashboardForm((prev) => ({ ...prev, [field]: value }));
  };

  const updateDashboardSlide = (index, field, value) => {
    setDashboardForm((prev) => ({
      ...prev,
      slides: prev.slides.map((slide, slideIndex) => (
        slideIndex === index ? { ...slide, [field]: value } : slide
      )),
    }));
  };

  const handleSlideImageUpload = async (index, file) => {
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    try {
      const res = await adminService.uploadMedia(formData);
      const url = res.data?.data?.url;
      if (url) {
        updateDashboardSlide(index, 'imageUrl', url);
        toast.success('Image uploaded');
      }
    } catch (err) {
      toast.error(err.userMessage || 'Image upload failed');
    }
  };

  const addDashboardSlide = () => {
    setDashboardForm((prev) => {
      if (prev.slides.length >= 6) return prev;
      return { ...prev, slides: [...prev.slides, createDashboardSlide()] };
    });
  };

  const removeDashboardSlide = (index) => {
    setDashboardForm((prev) => ({
      ...prev,
      slides: prev.slides.filter((_, slideIndex) => slideIndex !== index),
    }));
  };

  const handleSaveDashboardExperience = async (event) => {
    event.preventDefault();
    if (!dashboardEditor.binge) return;

    setDashboardSaving(true);
    try {
      await adminService.updateBingeDashboardExperience(
        dashboardEditor.binge.id,
        sanitizeDashboardExperienceForSave(dashboardForm),
      );
      toast.success('Customer dashboard setup updated');
      resetDashboardEditor();
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to save customer dashboard setup');
    } finally {
      setDashboardSaving(false);
    }
  };

  const updateAboutField = (field, value) => {
    setAboutForm((prev) => ({ ...prev, [field]: value }));
  };

  const updateAboutHighlight = (index, field, value) => {
    setAboutForm((prev) => ({
      ...prev,
      highlights: prev.highlights.map((item, itemIndex) => (itemIndex === index ? { ...item, [field]: value } : item)),
    }));
  };

  const updateAboutPolicy = (index, field, value) => {
    setAboutForm((prev) => ({
      ...prev,
      policies: prev.policies.map((item, itemIndex) => (itemIndex === index ? { ...item, [field]: value } : item)),
    }));
  };

  const updateHouseRule = (index, value) => {
    setAboutForm((prev) => ({
      ...prev,
      houseRules: prev.houseRules.map((item, itemIndex) => (itemIndex === index ? value : item)),
    }));
  };

  const handleSaveAboutExperience = async (event) => {
    event.preventDefault();
    if (!aboutEditor.binge) return;

    setAboutSaving(true);
    try {
      await adminService.updateBingeAboutExperience(aboutEditor.binge.id, sanitizeAboutExperienceForSave(aboutForm));
      toast.success('Customer about page updated');
      resetAboutEditor();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to save customer about page');
    } finally {
      setAboutSaving(false);
    }
  };

  const handleToggle = async (id) => {
    try {
      await adminService.toggleBinge(id);
      toast.success('Binge status toggled');
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to toggle binge');
    }
  };

  const handleDelete = async (binge) => {
    if (binge.active) {
      toast.error('Deactivate the binge before deleting it');
      return;
    }
    if (!confirm(`Delete "${binge.name}" permanently? This cannot be undone.`)) return;

    try {
      await adminService.deleteBinge(binge.id);
      if (selectedBinge?.id === binge.id) clearBinge();
      if (editId === binge.id) resetForm();
      toast.success('Binge deleted');
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to delete binge');
    }
  };

  const handleSelect = (binge) => {
    selectBinge({
      id: binge.id,
      name: binge.name,
      address: binge.address,
      supportEmail: binge.supportEmail,
      supportPhone: binge.supportPhone,
      supportWhatsapp: binge.supportWhatsapp,
      customerCancellationEnabled: binge.customerCancellationEnabled,
      customerCancellationCutoffMinutes: binge.customerCancellationCutoffMinutes,
      maxConcurrentBookings: binge.maxConcurrentBookings,
    });
    toast.success(`Entered: ${binge.name}`);
    navigate('/admin/dashboard');
  };

  const activeCount = binges.filter((binge) => binge.active).length;
  const inactiveCount = binges.length - activeCount;

  if (loading) {
    return (
      <div className="container adm-shell adm-flow-shell">
        <div className="adm-flow-card adm-flow-empty">
          <span className="adm-empty-icon"><FiCompass /></span>
          <h3>Loading your venues...</h3>
          <p>Preparing the admin workspaces connected to each binge.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="container adm-shell adm-flow-shell">
      <section className="adm-flow-hero">
        <div className="adm-flow-copy">
          <span className="adm-kicker"><FiMapPin /> Venue management</span>
          <h1>Select the binge that anchors your admin workspace.</h1>
          <p>Choose a venue to enter its dashboard, or create a new binge to expand your booking operations without leaving this control panel.</p>
          <div className="adm-flow-badges">
            <span className="adm-badge adm-badge-info">{binges.length} total venues</span>
            <span className={`adm-badge ${activeCount ? 'adm-badge-active' : 'adm-badge-inactive'}`}>{activeCount} active</span>
            <span className={`adm-badge ${inactiveCount ? 'adm-badge-inactive' : 'adm-badge-info'}`}>{inactiveCount} inactive</span>
          </div>
        </div>

        <div className="adm-flow-card adm-flow-summary">
          <span className="adm-kicker"><FiActivity /> Workspace pulse</span>
          <div className="adm-flow-stack">
            <div className="adm-flow-row">
              <span>Ready-to-manage venues</span>
              <strong>{activeCount}</strong>
            </div>
            <div className="adm-flow-row">
              <span>Needs activation</span>
              <strong>{inactiveCount}</strong>
            </div>
            <div className="adm-flow-row">
              <span>Next step</span>
              <strong>{activeCount ? 'Enter dashboard' : 'Create or reactivate'}</strong>
            </div>
          </div>
          <p className="adm-flow-helper">Active venues can be opened immediately. Inactive ones stay editable until you are ready to bring them back into rotation.</p>
          <div className="adm-flow-actions">
            <button
              type="button"
              className="btn btn-primary"
              aria-pressed={showForm}
              onClick={() => (showForm ? resetForm() : openCreateForm())}
            >
              {showForm ? <><FiX /> Cancel</> : <><FiPlus /> Create Binge</>}
            </button>
          </div>
        </div>
      </section>

      {showForm && (
        <section className="adm-form adm-flow-card">
          <h3>{editId ? 'Edit binge details' : 'Create a new binge'}</h3>
          <p className="adm-form-intro">Update the venue name and address shown throughout the admin console and the customer booking flow.</p>
          <form onSubmit={handleSubmit}>
            <div className="adm-grid-2">
              <div className="input-group">
                <label>Binge Name *</label>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="e.g., Downtown Arena" />
              </div>
              <div className="input-group">
                <label>Address</label>
                <input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} placeholder="123 Main St, City" />
              </div>
              <div className="input-group">
                <label>Support Email</label>
                <input value={form.supportEmail} onChange={(e) => setForm({ ...form, supportEmail: e.target.value })} placeholder="support@venue.com" />
              </div>
              <div className="input-group">
                <label>Support Phone</label>
                <input value={form.supportPhone} onChange={(e) => setForm({ ...form, supportPhone: e.target.value })} placeholder="+91 9876543210" />
              </div>
              <div className="input-group">
                <label>Support WhatsApp (digits only)</label>
                <input value={form.supportWhatsapp} onChange={(e) => setForm({ ...form, supportWhatsapp: e.target.value })} placeholder="919876543210" />
              </div>
              <div className="input-group">
                <label>Customer Cancellation</label>
                <select value={form.customerCancellationEnabled ? 'enabled' : 'disabled'} onChange={(e) => setForm({ ...form, customerCancellationEnabled: e.target.value === 'enabled' })}>
                  <option value="enabled">Enabled</option>
                  <option value="disabled">Disabled</option>
                </select>
              </div>
              <div className="input-group">
                <label>Cancellation Cutoff (minutes before start)</label>
                <input type="number" min="0" value={form.customerCancellationCutoffMinutes} onChange={(e) => setForm({ ...form, customerCancellationCutoffMinutes: Number(e.target.value || 0) })} placeholder="180" />
              </div>
              <div className="input-group">
                <label>Max Concurrent Bookings per Slot</label>
                <input type="number" min="1" value={form.maxConcurrentBookings} onChange={(e) => setForm({ ...form, maxConcurrentBookings: e.target.value === '' ? '' : Number(e.target.value) })} placeholder="Unlimited" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Leave empty for unlimited capacity</span>
              </div>
            </div>
            <div className="adm-form-actions">
              <button type="button" className="btn btn-secondary" onClick={resetForm}>Cancel</button>
              <button type="submit" className="btn btn-primary">{editId ? 'Update Binge' : 'Create Binge'}</button>
            </div>
          </form>
        </section>
      )}

      {dashboardEditor.open && (
        <section className="adm-form adm-dashboard-editor">
          <div className="adm-dashboard-editor-top">
            <div>
              <h3>Customer dashboard design</h3>
              <p className="adm-form-intro">
                Adjust the experiences section shown on the customer dashboard for <strong>{dashboardEditor.binge?.name}</strong>.
                Switch between a carousel and a grid, then write the slides you want customers to notice first.
              </p>
            </div>
            <div className="adm-flow-actions">
              <button type="button" className="btn btn-secondary" onClick={resetDashboardEditor}>Close</button>
            </div>
          </div>

          {dashboardLoading ? (
            <div className="adm-dashboard-empty">
              <h3>Loading current setup...</h3>
              <p>Fetching the latest customer dashboard content for this venue.</p>
            </div>
          ) : (
            <form onSubmit={handleSaveDashboardExperience}>
              <div className="adm-grid-2">
                <div className="input-group">
                  <label>Section eyebrow</label>
                  <input
                    value={dashboardForm.sectionEyebrow}
                    onChange={(e) => updateDashboardField('sectionEyebrow', e.target.value)}
                    placeholder="Explore Experiences"
                  />
                </div>
                <div className="input-group">
                  <label>Layout</label>
                  <select
                    value={dashboardForm.layout}
                    onChange={(e) => updateDashboardField('layout', e.target.value)}
                  >
                    {DASHBOARD_LAYOUT_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>{option.label}</option>
                    ))}
                  </select>
                  <div className="adm-hint">Carousel turns this section into a focused, swipeable spotlight.</div>
                </div>
              </div>

              <div className="input-group">
                <label>Section title</label>
                <input
                  value={dashboardForm.sectionTitle}
                  onChange={(e) => updateDashboardField('sectionTitle', e.target.value)}
                  placeholder="Pick a setup that matches the mood"
                />
              </div>

              <div className="input-group">
                <label>Section subtitle</label>
                <textarea
                  className="adm-textarea"
                  rows={3}
                  value={dashboardForm.sectionSubtitle}
                  onChange={(e) => updateDashboardField('sectionSubtitle', e.target.value)}
                  placeholder="Optional supporting line below the section title"
                />
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>Slides</h4>
                  <p className="adm-hint">Add up to 6 curated slides. Leave this empty to keep the live event cards as the fallback.</p>
                </div>
                <div className="adm-flow-actions">
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={() => setDashboardForm((prev) => ({ ...prev, slides: [] }))}
                  >
                    Use event card fallback
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    onClick={addDashboardSlide}
                    disabled={dashboardForm.slides.length >= 6}
                  >
                    Add Slide
                  </button>
                </div>
              </div>

              {dashboardForm.slides.length === 0 ? (
                <div className="adm-dashboard-empty">
                  <h3>No custom slides yet</h3>
                  <p>The customer portal will keep using the existing experience cards until you save at least one slide.</p>
                </div>
              ) : (
                <div className="adm-dashboard-slides">
                  {dashboardForm.slides.map((slide, index) => (
                    <article key={`${dashboardEditor.binge?.id || 'binge'}-${index}`} className="adm-dashboard-slide">
                      <div className="adm-dashboard-slide-head">
                        <div>
                          <strong>Slide {index + 1}</strong>
                          <div className="adm-dashboard-slide-meta">
                            <span className="adm-badge adm-badge-info">{slide.theme}</span>
                            <span className="adm-hint">{slide.badge || 'Featured badge'}</span>
                          </div>
                        </div>
                        <button type="button" className="btn btn-secondary btn-sm" onClick={() => removeDashboardSlide(index)}>
                          Remove
                        </button>
                      </div>

                      <div className="adm-grid-2">
                        <div className="input-group">
                          <label>Badge</label>
                          <input
                            value={slide.badge}
                            onChange={(e) => updateDashboardSlide(index, 'badge', e.target.value)}
                            placeholder="Featured"
                          />
                        </div>
                        <div className="input-group">
                          <label>Theme</label>
                          <select
                            value={slide.theme}
                            onChange={(e) => updateDashboardSlide(index, 'theme', e.target.value)}
                          >
                            {DASHBOARD_THEME_OPTIONS.map((option) => (
                              <option key={option.value} value={option.value}>{option.label}</option>
                            ))}
                          </select>
                        </div>
                      </div>

                      <div className="input-group">
                        <label>Headline</label>
                        <input
                          value={slide.headline}
                          onChange={(e) => updateDashboardSlide(index, 'headline', e.target.value)}
                          placeholder="Date-night takeover"
                        />
                      </div>

                      <div className="input-group">
                        <label>Description</label>
                        <textarea
                          className="adm-textarea"
                          rows={3}
                          value={slide.description}
                          onChange={(e) => updateDashboardSlide(index, 'description', e.target.value)}
                          placeholder="Describe the feeling or setup you want to surface in the customer portal"
                        />
                      </div>

                      <div className="input-group">
                        <label>Button label</label>
                        <input
                          value={slide.ctaLabel}
                          onChange={(e) => updateDashboardSlide(index, 'ctaLabel', e.target.value)}
                          placeholder="Open Booking"
                        />
                      </div>

                      <div className="input-group">
                        <label>Link to Event</label>
                        <select
                          value={slide.linkedEventTypeId || ''}
                          onChange={(e) => updateDashboardSlide(index, 'linkedEventTypeId', e.target.value ? Number(e.target.value) : null)}
                        >
                          <option value="">None — opens generic booking</option>
                          {dashboardEventTypes.map((et) => (
                            <option key={et.id} value={et.id}>{et.name} (Rs {et.basePrice})</option>
                          ))}
                        </select>
                        <p className="adm-hint">When linked, clicking this slide pre-selects the event in the booking flow.</p>
                      </div>

                      <div className="input-group">
                        <label>Slide Image</label>
                        <div className="adm-slide-image-upload">
                          {slide.imageUrl ? (
                            <div className="adm-slide-image-preview">
                              <img src={slide.imageUrl} alt={`Slide ${index + 1}`} />
                              <button type="button" className="btn btn-secondary btn-sm" onClick={() => updateDashboardSlide(index, 'imageUrl', '')}>
                                <FiX /> Remove
                              </button>
                            </div>
                          ) : (
                            <label className="adm-slide-image-dropzone">
                              <FiUpload />
                              <span>Upload image (max 5 MB)</span>
                              <input
                                type="file"
                                accept="image/jpeg,image/png,image/webp,image/gif"
                                style={{ display: 'none' }}
                                onChange={(e) => handleSlideImageUpload(index, e.target.files[0])}
                              />
                            </label>
                          )}
                          <input
                            value={slide.imageUrl || ''}
                            onChange={(e) => updateDashboardSlide(index, 'imageUrl', e.target.value)}
                            placeholder="Or paste an image URL"
                            className="adm-slide-image-url-input"
                          />
                        </div>
                      </div>
                    </article>
                  ))}
                </div>
              )}

              <div className="adm-form-actions">
                <button type="button" className="btn btn-secondary" onClick={resetDashboardEditor}>Cancel</button>
                <button type="submit" className="btn btn-primary" disabled={dashboardSaving}>
                  {dashboardSaving ? 'Saving...' : 'Save Customer Dashboard'}
                </button>
              </div>
            </form>
          )}
        </section>
      )}

      {aboutEditor.open && (
        <section className="adm-form adm-dashboard-editor">
          <div className="adm-dashboard-editor-top">
            <div>
              <h3>Customer about page</h3>
              <p className="adm-form-intro">
                Configure the content customers see in the About page for <strong>{aboutEditor.binge?.name}</strong>.
                Add highlights, rules, and policies to set clear expectations before checkout.
              </p>
            </div>
            <div className="adm-flow-actions">
              <button type="button" className="btn btn-secondary" onClick={resetAboutEditor}>Close</button>
            </div>
          </div>

          {aboutLoading ? (
            <div className="adm-dashboard-empty">
              <h3>Loading current content...</h3>
              <p>Fetching the latest About page setup for this venue.</p>
            </div>
          ) : (
            <form onSubmit={handleSaveAboutExperience}>
              <div className="adm-grid-2">
                <div className="input-group">
                  <label>Section eyebrow</label>
                  <input value={aboutForm.sectionEyebrow} onChange={(e) => updateAboutField('sectionEyebrow', e.target.value)} />
                </div>
                <div className="input-group">
                  <label>Section title</label>
                  <input value={aboutForm.sectionTitle} onChange={(e) => updateAboutField('sectionTitle', e.target.value)} />
                </div>
              </div>

              <div className="input-group">
                <label>Section subtitle</label>
                <textarea className="adm-textarea" rows={2} value={aboutForm.sectionSubtitle} onChange={(e) => updateAboutField('sectionSubtitle', e.target.value)} />
              </div>

              <div className="adm-grid-2">
                <div className="input-group">
                  <label>Hero title</label>
                  <input value={aboutForm.heroTitle} onChange={(e) => updateAboutField('heroTitle', e.target.value)} />
                </div>
                <div className="input-group">
                  <label>Contact heading</label>
                  <input value={aboutForm.contactHeading} onChange={(e) => updateAboutField('contactHeading', e.target.value)} />
                </div>
              </div>

              <div className="input-group">
                <label>Hero description</label>
                <textarea className="adm-textarea" rows={4} value={aboutForm.heroDescription} onChange={(e) => updateAboutField('heroDescription', e.target.value)} />
              </div>

              <div className="input-group">
                <label>Contact description</label>
                <textarea className="adm-textarea" rows={3} value={aboutForm.contactDescription} onChange={(e) => updateAboutField('contactDescription', e.target.value)} />
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>Highlights</h4>
                  <p className="adm-hint">Show what makes this binge stand out for customers.</p>
                </div>
                <div className="adm-flow-actions">
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, highlights: [...prev.highlights, createAboutHighlight()].slice(0, 8) }))}>
                    Add Highlight
                  </button>
                </div>
              </div>
              <div className="adm-dashboard-slides">
                {aboutForm.highlights.map((item, index) => (
                  <article key={`about-highlight-${index}`} className="adm-dashboard-slide">
                    <div className="adm-dashboard-slide-head">
                      <strong>Highlight {index + 1}</strong>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, highlights: prev.highlights.filter((_, i) => i !== index) }))}>Remove</button>
                    </div>
                    <div className="input-group">
                      <label>Title</label>
                      <input value={item.title} onChange={(e) => updateAboutHighlight(index, 'title', e.target.value)} />
                    </div>
                    <div className="input-group">
                      <label>Description</label>
                      <textarea className="adm-textarea" rows={3} value={item.description} onChange={(e) => updateAboutHighlight(index, 'description', e.target.value)} />
                    </div>
                  </article>
                ))}
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>House rules</h4>
                  <p className="adm-hint">Customers see these as numbered rules before booking.</p>
                </div>
                <div className="adm-flow-actions">
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, houseRules: [...prev.houseRules, ''].slice(0, 12) }))}>
                    Add Rule
                  </button>
                </div>
              </div>
              <div className="input-group">
                <label>Rules section title</label>
                <input value={aboutForm.houseRulesTitle} onChange={(e) => updateAboutField('houseRulesTitle', e.target.value)} />
              </div>
              <div className="adm-dashboard-slides">
                {aboutForm.houseRules.map((rule, index) => (
                  <article key={`about-rule-${index}`} className="adm-dashboard-slide">
                    <div className="adm-dashboard-slide-head">
                      <strong>Rule {index + 1}</strong>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, houseRules: prev.houseRules.filter((_, i) => i !== index) }))}>Remove</button>
                    </div>
                    <div className="input-group">
                      <label>Rule text</label>
                      <textarea className="adm-textarea" rows={2} value={rule} onChange={(e) => updateHouseRule(index, e.target.value)} />
                    </div>
                  </article>
                ))}
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>Policies</h4>
                  <p className="adm-hint">Capture booking, cancellation, and compliance information clearly.</p>
                </div>
                <div className="adm-flow-actions">
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, policies: [...prev.policies, createAboutPolicy()].slice(0, 8) }))}>
                    Add Policy
                  </button>
                </div>
              </div>
              <div className="input-group">
                <label>Policies section title</label>
                <input value={aboutForm.policyTitle} onChange={(e) => updateAboutField('policyTitle', e.target.value)} />
              </div>
              <div className="adm-dashboard-slides">
                {aboutForm.policies.map((item, index) => (
                  <article key={`about-policy-${index}`} className="adm-dashboard-slide">
                    <div className="adm-dashboard-slide-head">
                      <strong>Policy {index + 1}</strong>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, policies: prev.policies.filter((_, i) => i !== index) }))}>Remove</button>
                    </div>
                    <div className="input-group">
                      <label>Policy title</label>
                      <input value={item.title} onChange={(e) => updateAboutPolicy(index, 'title', e.target.value)} />
                    </div>
                    <div className="input-group">
                      <label>Policy description</label>
                      <textarea className="adm-textarea" rows={3} value={item.description} onChange={(e) => updateAboutPolicy(index, 'description', e.target.value)} />
                    </div>
                  </article>
                ))}
              </div>

              <div className="adm-form-actions">
                <button type="button" className="btn btn-secondary" onClick={resetAboutEditor}>Cancel</button>
                <button type="submit" className="btn btn-primary" disabled={aboutSaving}>
                  {aboutSaving ? 'Saving...' : 'Save About Page'}
                </button>
              </div>
            </form>
          )}
        </section>
      )}

      {tierEditor.open && (
        <section className="adm-form adm-flow-card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
            <div>
              <h3>Cancellation refund tiers</h3>
              <p className="adm-form-intro">
                Define tiered refund percentages for <strong>{tierEditor.binge?.name}</strong>. Each row sets the refund a customer gets when cancelling at least N hours before the booking starts. The first matching tier (highest hours threshold) applies.
              </p>
            </div>
            <button type="button" className="btn btn-secondary" onClick={() => setTierEditor({ open: false, binge: null })}>Close</button>
          </div>
          {tierLoading ? <p>Loading...</p> : (
            <>
              <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Hours before start</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Refund %</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Label</th>
                    <th style={{ width: '60px', borderBottom: '1px solid var(--border)' }} />
                  </tr>
                </thead>
                <tbody>
                  {tierRows.map((row, i) => (
                    <tr key={i}>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <input type="number" min="0" style={{ width: '80px' }} value={row.hoursBeforeStart} onChange={(e) => setTierRows(prev => prev.map((r, j) => j === i ? { ...r, hoursBeforeStart: Number(e.target.value) } : r))} />
                      </td>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <input type="number" min="0" max="100" style={{ width: '80px' }} value={row.refundPercentage} onChange={(e) => setTierRows(prev => prev.map((r, j) => j === i ? { ...r, refundPercentage: Number(e.target.value) } : r))} />
                      </td>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <input value={row.label} onChange={(e) => setTierRows(prev => prev.map((r, j) => j === i ? { ...r, label: e.target.value } : r))} placeholder="e.g. Full refund" />
                      </td>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <button type="button" className="btn btn-secondary btn-sm" onClick={() => setTierRows(prev => prev.filter((_, j) => j !== i))}>
                          <FiTrash2 />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setTierRows(prev => [...prev, { hoursBeforeStart: 0, refundPercentage: 0, label: '' }])}>
                  <FiPlus /> Add Tier
                </button>
                <button type="button" className="btn btn-primary" disabled={tierSaving} onClick={handleSaveTiers}>
                  {tierSaving ? 'Saving...' : 'Save Tiers'}
                </button>
              </div>
            </>
          )}
        </section>
      )}

      {binges.length === 0 ? (
        <div className="adm-empty adm-flow-card adm-flow-empty">
          <span className="adm-empty-icon"><FiMapPin /></span>
          <h3>No binges yet</h3>
          <p>Create your first venue above to get started with bookings and management.</p>
          {!showForm && (
            <button type="button" className="btn btn-primary" onClick={openCreateForm}>
              <FiPlus /> Create your first binge
            </button>
          )}
        </div>
      ) : (
        <div className="adm-venue-grid">
          {binges.map((b) => (
            <article key={b.id} className={`adm-venue-card${b.active ? '' : ' inactive'}`}>
              <div className="adm-venue-card-top">
                <span className="adm-kicker">Venue option</span>
                <span className={`adm-badge ${b.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                  {b.active ? 'Active' : 'Inactive'}
                </span>
              </div>

              <div className="adm-venue-card-copy">
                <h3>{b.name}</h3>
                <p>{b.address || 'Add an address so the venue reads clearly across staff and customer workflows.'}</p>
                {reviewSummaries[b.id]?.averageRating > 0 && (
                  <StarRating avg={reviewSummaries[b.id].averageRating} count={reviewSummaries[b.id].totalReviews} />
                )}
              </div>

              <p className="adm-venue-card-note">
                {b.active
                  ? 'Dashboard, bookings, availability, and reporting tools are ready for this venue.'
                  : 'Reactivate this venue whenever you want it back in the live admin and booking rotation.'}
              </p>

              <div className="adm-venue-actions">
                {b.active && (
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => handleSelect(b)}>
                    Enter <FiArrowRight />
                  </button>
                )}
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleOpenDashboardEditor(b)}>
                  Dashboard design
                </button>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleOpenAboutEditor(b)}>
                  About page
                </button>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleOpenTierEditor(b)}>
                  Cancellation tiers
                </button>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleEdit(b)}>
                  <FiEdit2 style={{ marginRight: 3 }} /> Edit
                </button>
                <button type="button" className={`btn btn-sm ${b.active ? 'btn-danger' : ''}`}
                  style={!b.active ? { background: 'var(--success)', color: '#fff' } : undefined}
                  onClick={() => handleToggle(b.id)}>
                  {b.active ? <><FiToggleLeft style={{ marginRight: 3 }} /> Deactivate</> : <><FiToggleRight style={{ marginRight: 3 }} /> Activate</>}
                </button>
                {!b.active && (
                  <button type="button" className="btn adm-danger-btn btn-sm" onClick={() => handleDelete(b)}>
                    <FiTrash2 style={{ marginRight: 3 }} /> Delete
                  </button>
                )}
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
