import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'react-toastify';
import { FiArrowLeft, FiPlus, FiSave, FiTrash2, FiUpload, FiEye, FiRotateCcw } from 'react-icons/fi';
import SEO from '../components/SEO';
import { siteContentService } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { HOME_CMS_SLUG, defaultHomeContent, mergeHomeContent } from '../content/homeDefaults';
import './AdminHomeEditor.css';

// Convert a File picker selection into a base64 data URI so admins can paste
// images that aren't yet hosted somewhere. Capped at 1.5 MB so a single
// gallery upload can't blow the JSON column. Real production should plug
// this into S3 / CDN.
async function fileToDataUri(file) {
  if (!file) return null;
  if (file.size > 1.5 * 1024 * 1024) {
    throw new Error('Image too large. Use < 1.5 MB or paste a CDN URL instead.');
  }
  return await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

function setPath(obj, path, value) {
  const next = structuredClone(obj);
  const keys = path.split('.');
  let cursor = next;
  for (let i = 0; i < keys.length - 1; i += 1) {
    const key = keys[i];
    if (cursor[key] === undefined || cursor[key] === null) cursor[key] = {};
    cursor = cursor[key];
  }
  cursor[keys[keys.length - 1]] = value;
  return next;
}

export default function AdminHomeEditor() {
  const confirm = useConfirm();
  const [content, setContent] = useState(defaultHomeContent);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [tab, setTab] = useState('hero');

  useEffect(() => {
    setLoading(true);
    siteContentService.getPublic(HOME_CMS_SLUG).then(res => {
      const raw = res?.data?.data?.contentJson;
      if (raw) {
        try {
          const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
          setContent(mergeHomeContent(parsed));
        } catch (err) {
          console.warn('Could not parse stored content, falling back to defaults', err);
        }
      }
    }).catch(() => { /* ignore — defaults stay */ })
      .finally(() => setLoading(false));
  }, []);

  const onField = (path, value) => setContent(prev => setPath(prev, path, value));

  const onSave = async () => {
    setSaving(true);
    try {
      const json = JSON.stringify(content);
      await siteContentService.upsert(HOME_CMS_SLUG, json);
      toast.success('Home page updated. Visitors will see it on their next load.');
    } catch (err) {
      const msg = err?.response?.data?.message || err?.message || 'Save failed';
      toast.error(msg);
    } finally {
      setSaving(false);
    }
  };

  const onResetDefaults = async () => {
    const ok = await confirm({
      title: 'Reset home content to bundled defaults?',
      message: 'All hero, sections and gallery fields will be replaced with the shipped defaults. Your unsaved edits will be lost. You still need to click Save to publish the reset.',
      confirmLabel: 'Reset to defaults',
      variant: 'danger',
    });
    if (!ok) return;
    setContent(structuredClone(defaultHomeContent));
    toast.info('Reverted to defaults — remember to Save to publish.');
  };

  const galleryAdd = () => onField('gallery', [...(content.gallery || []), { url: '', caption: '' }]);
  const galleryUpdate = (idx, key, value) => {
    const next = [...(content.gallery || [])];
    next[idx] = { ...next[idx], [key]: value };
    onField('gallery', next);
  };
  const galleryRemove = (idx) => onField('gallery', (content.gallery || []).filter((_, i) => i !== idx));
  const galleryMove = (idx, delta) => {
    const list = [...(content.gallery || [])];
    const target = idx + delta;
    if (target < 0 || target >= list.length) return;
    [list[idx], list[target]] = [list[target], list[idx]];
    onField('gallery', list);
  };
  const galleryUpload = async (idx, file) => {
    try {
      const data = await fileToDataUri(file);
      if (data) galleryUpdate(idx, 'url', data);
    } catch (err) {
      toast.error(err.message);
    }
  };

  // ---- Big banner carousel (Amazon-style hero showcase) ----
  const banner = content.bannerCarousel || { enabled: true, autoplayMs: 6000, slides: [] };
  const bannerField = (key, value) => onField(`bannerCarousel.${key}`, value);
  const bannerSlideUpdate = (idx, key, value) => {
    const next = [...(banner.slides || [])];
    next[idx] = { ...next[idx], [key]: value };
    bannerField('slides', next);
  };
  const bannerSlideAdd = () => bannerField('slides', [...(banner.slides || []), {
    url: '', kicker: '', title: '', subtitle: '', ctaLabel: '', ctaHref: '/binges', align: 'left',
  }]);
  const bannerSlideRemove = (idx) => bannerField('slides', (banner.slides || []).filter((_, i) => i !== idx));
  const bannerSlideMove = (idx, delta) => {
    const list = [...(banner.slides || [])];
    const target = idx + delta;
    if (target < 0 || target >= list.length) return;
    [list[idx], list[target]] = [list[target], list[idx]];
    bannerField('slides', list);
  };
  const bannerSlideUpload = async (idx, file) => {
    try {
      const data = await fileToDataUri(file);
      if (data) bannerSlideUpdate(idx, 'url', data);
    } catch (err) {
      toast.error(err.message);
    }
  };

  const updateListItem = (path, idx, key, value) => {
    const list = [...(content[path]?.items || content[path] || [])];
    list[idx] = { ...list[idx], [key]: value };
    if (Array.isArray(content[path])) onField(path, list);
    else onField(`${path}.items`, list);
  };
  const addListItem = (path, template) => {
    const current = content[path]?.items ?? content[path] ?? [];
    const next = [...current, template];
    if (Array.isArray(content[path])) onField(path, next);
    else onField(`${path}.items`, next);
  };
  const removeListItem = (path, idx) => {
    const current = content[path]?.items ?? content[path] ?? [];
    const next = current.filter((_, i) => i !== idx);
    if (Array.isArray(content[path])) onField(path, next);
    else onField(`${path}.items`, next);
  };

  const tabs = useMemo(() => ([
    { id: 'hero', label: 'Hero & CTAs' },
    { id: 'gallery', label: 'Hero Banner' },
    { id: 'features', label: 'Features' },
    { id: 'signature', label: 'Signature Moments' },
    { id: 'process', label: 'Booking Process' },
    { id: 'packages', label: 'Packages' },
    { id: 'final', label: 'Closing CTA' },
  ]), []);

  if (loading) {
    return <div className="container"><p style={{ padding: '3rem 0' }}>Loading editor…</p></div>;
  }

  return (
    <div className="container admin-home-editor">
      <SEO title="Edit Home Page" description="Super-admin editor for the public landing page." />

      <header className="admin-home-head">
        <div>
          <Link to="/admin/platform" className="admin-home-back"><FiArrowLeft /> Back to entrance</Link>
          <h1>Edit the public Home page</h1>
          <p>Anything you change here is published instantly to every unauthenticated visitor. Use this for seasonal campaigns, new packages, or fresh photography.</p>
        </div>
        <div className="admin-home-head-actions">
          <Link to="/" className="btn btn-secondary"><FiEye /> Preview Home</Link>
          <button type="button" className="btn btn-secondary" onClick={onResetDefaults}><FiRotateCcw /> Reset</button>
          <button type="button" className="btn btn-primary" onClick={onSave} disabled={saving}>
            <FiSave /> {saving ? 'Saving…' : 'Save & Publish'}
          </button>
        </div>
      </header>

      <nav className="admin-home-tabs" role="tablist">
        {tabs.map(t => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            className={`admin-home-tab ${tab === t.id ? 'is-active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <section className="admin-home-card">
        {tab === 'hero' && (
          <div className="admin-home-form">
            <h2>Hero block</h2>
            <label>Kicker (small label above headline)
              <input value={content.hero.kicker} onChange={e => onField('hero.kicker', e.target.value)} />
            </label>
            <div className="admin-home-grid-3">
              <label>Headline (start)
                <input value={content.hero.headline} onChange={e => onField('hero.headline', e.target.value)} />
              </label>
              <label>Headline (highlight)
                <input value={content.hero.headlineHighlight} onChange={e => onField('hero.headlineHighlight', e.target.value)} />
              </label>
              <label>Headline (end)
                <input value={content.hero.headlineSuffix} onChange={e => onField('hero.headlineSuffix', e.target.value)} />
              </label>
            </div>
            <label>Description
              <textarea rows={3} value={content.hero.description} onChange={e => onField('hero.description', e.target.value)} />
            </label>
            <div className="admin-home-grid-2">
              <label>Primary CTA label
                <input value={content.hero.primaryCtaLabel} onChange={e => onField('hero.primaryCtaLabel', e.target.value)} />
              </label>
              <label>Primary CTA link
                <input value={content.hero.primaryCtaHref} onChange={e => onField('hero.primaryCtaHref', e.target.value)} />
              </label>
              <label>Secondary CTA label
                <input value={content.hero.secondaryCtaLabel} onChange={e => onField('hero.secondaryCtaLabel', e.target.value)} />
              </label>
              <label>Secondary CTA link
                <input value={content.hero.secondaryCtaHref} onChange={e => onField('hero.secondaryCtaHref', e.target.value)} />
              </label>
            </div>

            <h3>Proof strip</h3>
            <p className="admin-home-helper">Small stat row under the CTAs (e.g. "500+ celebrations hosted").</p>
            {(content.proofStrip || []).map((item, idx) => (
              <div key={idx} className="admin-home-row">
                <input placeholder="Value (e.g. 500+)" value={item.value || ''} onChange={e => {
                  const next = [...content.proofStrip]; next[idx] = { ...next[idx], value: e.target.value }; onField('proofStrip', next);
                }} />
                <input placeholder="Label" value={item.label || ''} onChange={e => {
                  const next = [...content.proofStrip]; next[idx] = { ...next[idx], label: e.target.value }; onField('proofStrip', next);
                }} />
                <button type="button" className="btn-icon" onClick={() => onField('proofStrip', content.proofStrip.filter((_, i) => i !== idx))} aria-label="Remove"><FiTrash2 /></button>
              </div>
            ))}
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => onField('proofStrip', [...(content.proofStrip || []), { value: '', label: '' }])}><FiPlus /> Add stat</button>

            <h3 style={{ marginTop: '1.5rem' }}>Marquee strip</h3>
            <p className="admin-home-helper">Comma-separated list of phrases that scroll under the hero.</p>
            <input
              value={(content.marquee || []).join(', ')}
              onChange={e => onField('marquee', e.target.value.split(',').map(s => s.trim()).filter(Boolean))}
            />
          </div>
        )}

        {tab === 'gallery' && (
          <div className="admin-home-form">
            <h2>Hero banner carousel</h2>
            <p className="admin-home-helper">Big Amazon-style full-width banner shown right under the hero. Each slide is a large photograph with overlay headline, kicker, subtitle, and CTA. Use 16:9 photography (≥ 1920×1080) for the cleanest look.</p>

            <div className="admin-home-grid-3">
              <label>Show banner?
                <select value={banner.enabled === false ? 'off' : 'on'} onChange={e => bannerField('enabled', e.target.value === 'on')}>
                  <option value="on">Yes — show on home page</option>
                  <option value="off">No — hide banner</option>
                </select>
              </label>
              <label>Autoplay interval (ms)
                <input
                  type="number"
                  min={2000}
                  step={500}
                  value={banner.autoplayMs ?? 6000}
                  onChange={e => bannerField('autoplayMs', Math.max(2000, Number(e.target.value) || 6000))}
                />
              </label>
            </div>

            {(banner.slides || []).map((slide, idx) => (
              <div key={`bs-${idx}`} className="admin-home-gallery-row">
                <div className="admin-home-gallery-thumb">
                  {slide.url
                    ? <img src={slide.url} alt={slide.title || `Banner ${idx + 1}`} />
                    : <div className="admin-home-gallery-empty">No image</div>}
                </div>
                <div className="admin-home-gallery-fields">
                  <label>Image URL or data URI
                    <input value={slide.url || ''} onChange={e => bannerSlideUpdate(idx, 'url', e.target.value)} placeholder="https://… or data:image/png;base64,…" />
                  </label>
                  <div className="admin-home-grid-2">
                    <label>Kicker (small label)
                      <input value={slide.kicker || ''} onChange={e => bannerSlideUpdate(idx, 'kicker', e.target.value)} placeholder="e.g. Birthday rooms" />
                    </label>
                    <label>Text alignment
                      <select value={slide.align || 'left'} onChange={e => bannerSlideUpdate(idx, 'align', e.target.value)}>
                        <option value="left">Left</option>
                        <option value="center">Center</option>
                        <option value="right">Right</option>
                      </select>
                    </label>
                  </div>
                  <label>Headline
                    <input value={slide.title || ''} onChange={e => bannerSlideUpdate(idx, 'title', e.target.value)} placeholder="Make the candle-blow moment cinematic." />
                  </label>
                  <label>Subtitle
                    <textarea rows={2} value={slide.subtitle || ''} onChange={e => bannerSlideUpdate(idx, 'subtitle', e.target.value)} />
                  </label>
                  <div className="admin-home-grid-2">
                    <label>CTA button label
                      <input value={slide.ctaLabel || ''} onChange={e => bannerSlideUpdate(idx, 'ctaLabel', e.target.value)} placeholder="Reserve a room" />
                    </label>
                    <label>CTA link
                      <input value={slide.ctaHref || ''} onChange={e => bannerSlideUpdate(idx, 'ctaHref', e.target.value)} placeholder="/binges" />
                    </label>
                  </div>
                  <div className="admin-home-row-actions">
                    <label className="btn btn-secondary btn-sm" style={{ cursor: 'pointer' }}>
                      <FiUpload /> Upload
                      <input type="file" accept="image/*" hidden onChange={e => bannerSlideUpload(idx, e.target.files?.[0])} />
                    </label>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => bannerSlideMove(idx, -1)} disabled={idx === 0}>↑</button>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => bannerSlideMove(idx, 1)} disabled={idx === (banner.slides || []).length - 1}>↓</button>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => bannerSlideRemove(idx)}><FiTrash2 /> Remove</button>
                  </div>
                </div>
              </div>
            ))}
            <button type="button" className="btn btn-primary btn-sm" onClick={bannerSlideAdd}><FiPlus /> Add banner slide</button>
          </div>
        )}

        {tab === 'features' && (
          <div className="admin-home-form">
            <h2>"Why" features section</h2>
            <label>Kicker
              <input value={content.features.kicker} onChange={e => onField('features.kicker', e.target.value)} />
            </label>
            <label>Title
              <input value={content.features.title} onChange={e => onField('features.title', e.target.value)} />
            </label>
            <label>Description
              <textarea rows={2} value={content.features.description} onChange={e => onField('features.description', e.target.value)} />
            </label>
            <h3>Feature cards</h3>
            {(content.features.items || []).map((item, idx) => (
              <div key={idx} className="admin-home-row admin-home-row-grid">
                <input placeholder="Title" value={item.title || ''} onChange={e => updateListItem('features', idx, 'title', e.target.value)} />
                <input placeholder="Description" value={item.description || ''} onChange={e => updateListItem('features', idx, 'description', e.target.value)} />
                <select value={item.icon || 'star'} onChange={e => updateListItem('features', idx, 'icon', e.target.value)}>
                  {['film','calendar','shield','camera','gift','heart','star','briefcase','smile','users','award','clock','mappin','check'].map(n => <option key={n} value={n}>{n}</option>)}
                </select>
                <button type="button" className="btn-icon" onClick={() => removeListItem('features', idx)}><FiTrash2 /></button>
              </div>
            ))}
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => addListItem('features', { title: '', description: '', icon: 'star' })}><FiPlus /> Add feature</button>
          </div>
        )}

        {tab === 'signature' && (
          <div className="admin-home-form">
            <h2>Signature moments</h2>
            <label>Kicker
              <input value={content.signature.kicker} onChange={e => onField('signature.kicker', e.target.value)} />
            </label>
            <label>Section title
              <input value={content.signature.title} onChange={e => onField('signature.title', e.target.value)} />
            </label>
            {(content.signature.items || []).map((item, idx) => (
              <div key={idx} className="admin-home-row admin-home-row-grid">
                <input placeholder="Eyebrow" value={item.eyebrow || ''} onChange={e => updateListItem('signature', idx, 'eyebrow', e.target.value)} />
                <input placeholder="Title" value={item.title || ''} onChange={e => updateListItem('signature', idx, 'title', e.target.value)} />
                <input placeholder="Description" value={item.description || ''} onChange={e => updateListItem('signature', idx, 'description', e.target.value)} />
                <input placeholder="Accent label" value={item.accent || ''} onChange={e => updateListItem('signature', idx, 'accent', e.target.value)} />
                <button type="button" className="btn-icon" onClick={() => removeListItem('signature', idx)}><FiTrash2 /></button>
              </div>
            ))}
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => addListItem('signature', { eyebrow: '', title: '', description: '', accent: '' })}><FiPlus /> Add moment</button>
          </div>
        )}

        {tab === 'process' && (
          <div className="admin-home-form">
            <h2>Booking process steps</h2>
            <label>Kicker
              <input value={content.process.kicker} onChange={e => onField('process.kicker', e.target.value)} />
            </label>
            <label>Title
              <input value={content.process.title} onChange={e => onField('process.title', e.target.value)} />
            </label>
            {(content.process.items || []).map((item, idx) => (
              <div key={idx} className="admin-home-row admin-home-row-grid">
                <input placeholder="Number (e.g. 01)" value={item.number || ''} onChange={e => updateListItem('process', idx, 'number', e.target.value)} />
                <input placeholder="Title" value={item.title || ''} onChange={e => updateListItem('process', idx, 'title', e.target.value)} />
                <input placeholder="Description" value={item.description || ''} onChange={e => updateListItem('process', idx, 'description', e.target.value)} />
                <button type="button" className="btn-icon" onClick={() => removeListItem('process', idx)}><FiTrash2 /></button>
              </div>
            ))}
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => addListItem('process', { number: '', title: '', description: '' })}><FiPlus /> Add step</button>
          </div>
        )}

        {tab === 'packages' && (
          <div className="admin-home-form">
            <h2>Indicative packages</h2>
            <label>Kicker
              <input value={content.packages.kicker} onChange={e => onField('packages.kicker', e.target.value)} />
            </label>
            <label>Title
              <input value={content.packages.title} onChange={e => onField('packages.title', e.target.value)} />
            </label>
            <label>Description
              <textarea rows={2} value={content.packages.description} onChange={e => onField('packages.description', e.target.value)} />
            </label>
            {(content.packages.items || []).map((item, idx) => (
              <div key={idx} className="admin-home-row admin-home-row-grid">
                <input placeholder="Package name" value={item.name || ''} onChange={e => updateListItem('packages', idx, 'name', e.target.value)} />
                <input placeholder="Price (₹4,999)" value={item.price || ''} onChange={e => updateListItem('packages', idx, 'price', e.target.value)} />
                <input placeholder="Note" value={item.note || ''} onChange={e => updateListItem('packages', idx, 'note', e.target.value)} />
                <select value={item.icon || 'star'} onChange={e => updateListItem('packages', idx, 'icon', e.target.value)}>
                  {['film','calendar','shield','camera','gift','heart','star','briefcase','smile','users','award'].map(n => <option key={n} value={n}>{n}</option>)}
                </select>
                <button type="button" className="btn-icon" onClick={() => removeListItem('packages', idx)}><FiTrash2 /></button>
              </div>
            ))}
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => addListItem('packages', { name: '', price: '', note: '', icon: 'star' })}><FiPlus /> Add package</button>
          </div>
        )}

        {tab === 'final' && (
          <div className="admin-home-form">
            <h2>Closing CTA</h2>
            <label>Kicker
              <input value={content.finalCta.kicker} onChange={e => onField('finalCta.kicker', e.target.value)} />
            </label>
            <label>Title
              <input value={content.finalCta.title} onChange={e => onField('finalCta.title', e.target.value)} />
            </label>
            <label>Description
              <textarea rows={3} value={content.finalCta.description} onChange={e => onField('finalCta.description', e.target.value)} />
            </label>
          </div>
        )}
      </section>

      <footer className="admin-home-foot">
        <button type="button" className="btn btn-primary" onClick={onSave} disabled={saving}>
          <FiSave /> {saving ? 'Saving…' : 'Save & Publish'}
        </button>
      </footer>
    </div>
  );
}
