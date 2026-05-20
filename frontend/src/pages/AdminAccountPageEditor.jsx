import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { toast } from 'react-toastify';
import {
  FiArrowLeft,
  FiPlus,
  FiSave,
  FiTrash2,
  FiRotateCcw,
  FiHelpCircle,
  FiList,
  FiGift,
  FiLifeBuoy,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { siteContentService, bookingService } from '../services/endpoints';
import {
  ACCOUNT_PAGE_CMS_SLUG,
  defaultAccountPageContent,
  mergeAccountPageContent,
} from '../content/accountPageDefaults';
import './AdminAccountPageEditor.css';

// ── Tiny helpers ──────────────────────────────────────────────────────────────

/** Immutable array element update */
const updateAt = (arr, index, patch) =>
  arr.map((item, i) => (i === index ? { ...item, ...patch } : item));

/** Immutable array element removal */
const removeAt = (arr, index) => arr.filter((_, i) => i !== index);

// ─────────────────────────────────────────────────────────────────────────────

const TABS = [
  { id: 'faq',     label: 'FAQ',             icon: <FiHelpCircle /> },
  { id: 'steps',   label: 'How it Works',    icon: <FiList /> },
  { id: 'offers',  label: 'Benefits & Offers', icon: <FiGift /> },
  { id: 'support', label: 'Support & Policy', icon: <FiLifeBuoy /> },
];

export default function AdminAccountPageEditor() {
  const confirm = useConfirm();
  // When mounted under /admin/binges/:bingeId/account-page-editor we operate
  // in per-binge mode and use the binge-scoped endpoints. Otherwise we edit
  // the platform-wide super-admin document via siteContentService.
  const { bingeId: bingeIdParam } = useParams();
  const bingeId = bingeIdParam ? Number(bingeIdParam) : null;
  const isBingeMode = Number.isFinite(bingeId) && bingeId > 0;

  const [content, setContent] = useState(defaultAccountPageContent);
  const [loading, setLoading]   = useState(true);
  const [saving, setSaving]     = useState(false);
  const [tab, setTab]           = useState('faq');

  // ── Load stored CMS doc ───────────────────────────────────────────────
  useEffect(() => {
    setLoading(true);
    if (isBingeMode) {
      // Per-binge mode: load BOTH the binge override and the global doc.
      // Binge override (if present) wins; otherwise we prefill from global so
      // the admin starts from the platform-wide baseline rather than bundled
      // defaults — saves them re-typing what super-admin already authored.
      Promise.allSettled([
        bookingService.getBingeSiteContent(bingeId, ACCOUNT_PAGE_CMS_SLUG),
        siteContentService.getPublic(ACCOUNT_PAGE_CMS_SLUG),
      ])
        .then(([bingeRes, globalRes]) => {
          const bingeRaw = bingeRes.status === 'fulfilled' ? bingeRes.value?.data?.data?.contentJson : null;
          const globalRaw = globalRes.status === 'fulfilled' ? globalRes.value?.data?.data?.contentJson : null;
          const raw = bingeRaw || globalRaw;
          if (raw) {
            try {
              const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
              setContent(mergeAccountPageContent(parsed));
            } catch { /* keep defaults */ }
          }
        })
        .finally(() => setLoading(false));
    } else {
      siteContentService
        .getPublic(ACCOUNT_PAGE_CMS_SLUG)
        .then((res) => {
          const raw = res?.data?.data?.contentJson;
          if (raw) {
            try {
              const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
              setContent(mergeAccountPageContent(parsed));
            } catch {
              // Stored JSON is corrupt — fall back to defaults silently.
            }
          }
        })
        .catch(() => { /* network error — defaults stay */ })
        .finally(() => setLoading(false));
    }
  }, [isBingeMode, bingeId]);

  // ── Save ──────────────────────────────────────────────────────────────
  const onSave = async () => {
    setSaving(true);
    try {
      const payload = JSON.stringify(content);
      if (isBingeMode) {
        await bookingService.upsertBingeSiteContent(bingeId, ACCOUNT_PAGE_CMS_SLUG, payload);
      } else {
        await siteContentService.upsert(ACCOUNT_PAGE_CMS_SLUG, payload);
      }
      toast.success(
        isBingeMode
          ? 'Binge account page content saved. Customers viewing this venue will see the changes on their next page load.'
          : 'Account page content saved. Customers will see the changes on their next page load.',
      );
    } catch (err) {
      toast.error(err?.response?.data?.message || err?.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  // ── Reset to defaults ─────────────────────────────────────────────────
  const onReset = async () => {
    const ok = await confirm({
      title: 'Reset account page to bundled defaults?',
      message: 'All FAQs, sections and policies will be replaced with the shipped defaults. Your unsaved edits will be lost. You still need to click Save to publish the reset.',
      confirmLabel: 'Reset to defaults',
      variant: 'danger',
    });
    if (!ok) return;
    setContent(structuredClone(defaultAccountPageContent));
    toast.info('Reverted to defaults — click Save to persist.');
  };

  // ── FAQ helpers ───────────────────────────────────────────────────────
  const addFaq = () =>
    setContent((c) => ({
      ...c,
      faqs: [...c.faqs, { question: '', answer: '' }],
    }));

  const updateFaq = (index, field, value) =>
    setContent((c) => ({ ...c, faqs: updateAt(c.faqs, index, { [field]: value }) }));

  const removeFaq = (index) =>
    setContent((c) => ({ ...c, faqs: removeAt(c.faqs, index) }));

  const moveFaq = (index, dir) => {
    const target = index + dir;
    if (target < 0 || target >= content.faqs.length) return;
    const next = [...content.faqs];
    [next[index], next[target]] = [next[target], next[index]];
    setContent((c) => ({ ...c, faqs: next }));
  };

  // ── How it works step helpers ─────────────────────────────────────────
  const addStep = () =>
    setContent((c) => ({ ...c, howItWorksSteps: [...c.howItWorksSteps, ''] }));

  const updateStep = (index, value) =>
    setContent((c) => ({
      ...c,
      howItWorksSteps: c.howItWorksSteps.map((s, i) => (i === index ? value : s)),
    }));

  const removeStep = (index) =>
    setContent((c) => ({ ...c, howItWorksSteps: removeAt(c.howItWorksSteps, index) }));

  const moveStep = (index, dir) => {
    const target = index + dir;
    if (target < 0 || target >= content.howItWorksSteps.length) return;
    const next = [...content.howItWorksSteps];
    [next[index], next[target]] = [next[target], next[index]];
    setContent((c) => ({ ...c, howItWorksSteps: next }));
  };

  // ── Member offer helpers ──────────────────────────────────────────────
  const addOffer = () =>
    setContent((c) => ({
      ...c,
      memberOffers: [...c.memberOffers, { title: '', description: '' }],
    }));

  const updateOffer = (index, field, value) =>
    setContent((c) => ({
      ...c,
      memberOffers: updateAt(c.memberOffers, index, { [field]: value }),
    }));

  const removeOffer = (index) =>
    setContent((c) => ({ ...c, memberOffers: removeAt(c.memberOffers, index) }));

  const moveOffer = (index, dir) => {
    const target = index + dir;
    if (target < 0 || target >= content.memberOffers.length) return;
    const next = [...content.memberOffers];
    [next[index], next[target]] = [next[target], next[index]];
    setContent((c) => ({ ...c, memberOffers: next }));
  };

  // ─────────────────────────────────────────────────────────────────────
  if (loading) {
    return (
      <div className="container">
        <SEO title="Account Page Editor" description="Edit customer account page content." />
        <div className="acpe-loading">Loading current content…</div>
      </div>
    );
  }

  return (
    <div className="container acpe-root">
      <SEO
        title={isBingeMode ? 'Binge Account Page Editor' : 'Account Page Editor'}
        description="Edit FAQ, How it Works steps, member offers, and support policy text for the customer account page."
      />

      {/* ── Header ────────────────────────────────────────── */}
      <div className="acpe-header">
        <Link
          to={isBingeMode ? '/admin/binges' : '/admin/platform'}
          className="acpe-back"
        >
          <FiArrowLeft /> {isBingeMode ? 'Manage Binges' : 'Admin Console'}
        </Link>
        <div className="acpe-header-copy">
          <h1>{isBingeMode ? 'Binge Account Page Editor' : 'Account Page Editor'}</h1>
          <p>
            {isBingeMode
              ? `Manage the FAQ, "How it works" steps, member benefit cards, and support policy text shown to customers viewing this binge (id ${bingeId}). When a value here is empty, customers see the platform-wide default.`
              : 'Manage the FAQ, "How it works" steps, member benefit cards, and support policy text shown to every customer on their Account Center page and Dashboard.'}
          </p>
        </div>
        <div className="acpe-header-actions">
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onReset}
            disabled={saving}
          >
            <FiRotateCcw /> Reset defaults
          </button>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={onSave}
            disabled={saving}
          >
            <FiSave /> {saving ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </div>

      {/* ── Tabs ───────────────────────────────────────────────────────── */}
      <div className="acpe-tabs">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            className={`acpe-tab${tab === t.id ? ' acpe-tab-active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.icon} {t.label}
          </button>
        ))}
      </div>

      <div className="acpe-panel">

        {/* ══════════════════════ FAQ TAB ════════════════════════════════ */}
        {tab === 'faq' && (
          <section className="acpe-section">
            <div className="acpe-section-head">
              <div>
                <h2>Frequently Asked Questions</h2>
                <p className="acpe-hint">
                  Shown on the customer Account Center and Dashboard pages. At least one FAQ is required.
                </p>
              </div>
              <button type="button" className="btn btn-secondary btn-sm" onClick={addFaq}>
                <FiPlus /> Add question
              </button>
            </div>

            {content.faqs.length === 0 && (
              <p className="acpe-empty">No questions yet. Click "Add question" to add the first one.</p>
            )}

            <div className="acpe-item-list">
              {content.faqs.map((faq, i) => (
                <article key={i} className="acpe-item card">
                  <div className="acpe-item-header">
                    <span className="acpe-item-num">Q{i + 1}</span>
                    <div className="acpe-item-actions">
                      <button
                        type="button"
                        className="acpe-icon-btn"
                        onClick={() => moveFaq(i, -1)}
                        disabled={i === 0}
                        aria-label="Move up"
                        title="Move up"
                      >▲</button>
                      <button
                        type="button"
                        className="acpe-icon-btn"
                        onClick={() => moveFaq(i, 1)}
                        disabled={i === content.faqs.length - 1}
                        aria-label="Move down"
                        title="Move down"
                      >▼</button>
                      <button
                        type="button"
                        className="acpe-icon-btn acpe-icon-btn-danger"
                        onClick={() => removeFaq(i)}
                        aria-label="Remove"
                        title="Remove"
                      ><FiTrash2 /></button>
                    </div>
                  </div>
                  <label className="acpe-field">
                    <span>Question</span>
                    <input
                      type="text"
                      maxLength={200}
                      value={faq.question}
                      onChange={(e) => updateFaq(i, 'question', e.target.value)}
                      placeholder="e.g. Can I reschedule a booking?"
                    />
                  </label>
                  <label className="acpe-field">
                    <span>Answer</span>
                    <textarea
                      rows={3}
                      maxLength={600}
                      value={faq.answer}
                      onChange={(e) => updateFaq(i, 'answer', e.target.value)}
                      placeholder="Full answer visible to customers…"
                    />
                  </label>
                </article>
              ))}
            </div>
          </section>
        )}

        {/* ════════════════════ HOW IT WORKS TAB ════════════════════════ */}
        {tab === 'steps' && (
          <section className="acpe-section">
            <div className="acpe-section-head">
              <div>
                <h2>How It Works — Steps</h2>
                <p className="acpe-hint">
                  Ordered list shown in the "How it works" box on the Account Center and Dashboard.
                  At least one step is required.
                </p>
              </div>
              <button type="button" className="btn btn-secondary btn-sm" onClick={addStep}>
                <FiPlus /> Add step
              </button>
            </div>

            {content.howItWorksSteps.length === 0 && (
              <p className="acpe-empty">No steps yet. Click "Add step" to add the first one.</p>
            )}

            <div className="acpe-item-list">
              {content.howItWorksSteps.map((step, i) => (
                <article key={i} className="acpe-item card">
                  <div className="acpe-item-header">
                    <span className="acpe-item-num">Step {i + 1}</span>
                    <div className="acpe-item-actions">
                      <button
                        type="button"
                        className="acpe-icon-btn"
                        onClick={() => moveStep(i, -1)}
                        disabled={i === 0}
                        aria-label="Move up"
                        title="Move up"
                      >▲</button>
                      <button
                        type="button"
                        className="acpe-icon-btn"
                        onClick={() => moveStep(i, 1)}
                        disabled={i === content.howItWorksSteps.length - 1}
                        aria-label="Move down"
                        title="Move down"
                      >▼</button>
                      <button
                        type="button"
                        className="acpe-icon-btn acpe-icon-btn-danger"
                        onClick={() => removeStep(i)}
                        aria-label="Remove"
                        title="Remove"
                      ><FiTrash2 /></button>
                    </div>
                  </div>
                  <label className="acpe-field">
                    <span>Step text</span>
                    <textarea
                      rows={2}
                      maxLength={300}
                      value={step}
                      onChange={(e) => updateStep(i, e.target.value)}
                      placeholder="Describe this step in plain language…"
                    />
                  </label>
                </article>
              ))}
            </div>
          </section>
        )}

        {/* ══════════════════ MEMBER OFFERS TAB ═════════════════════════ */}
        {tab === 'offers' && (
          <section className="acpe-section">
            <div className="acpe-section-head">
              <div>
                <h2>Benefits &amp; Retention Cards</h2>
                <p className="acpe-hint">
                  Cards shown in the "Benefits and retention" section on the Account Center and
                  Dashboard. Each card has a title and a short description.
                </p>
              </div>
              <button type="button" className="btn btn-secondary btn-sm" onClick={addOffer}>
                <FiPlus /> Add card
              </button>
            </div>

            {content.memberOffers.length === 0 && (
              <p className="acpe-empty">No benefit cards yet. Click "Add card" to add one.</p>
            )}

            <div className="acpe-item-list">
              {content.memberOffers.map((offer, i) => (
                <article key={i} className="acpe-item card">
                  <div className="acpe-item-header">
                    <span className="acpe-item-num">Card {i + 1}</span>
                    <div className="acpe-item-actions">
                      <button
                        type="button"
                        className="acpe-icon-btn"
                        onClick={() => moveOffer(i, -1)}
                        disabled={i === 0}
                        aria-label="Move up"
                        title="Move up"
                      >▲</button>
                      <button
                        type="button"
                        className="acpe-icon-btn"
                        onClick={() => moveOffer(i, 1)}
                        disabled={i === content.memberOffers.length - 1}
                        aria-label="Move down"
                        title="Move down"
                      >▼</button>
                      <button
                        type="button"
                        className="acpe-icon-btn acpe-icon-btn-danger"
                        onClick={() => removeOffer(i)}
                        aria-label="Remove"
                        title="Remove"
                      ><FiTrash2 /></button>
                    </div>
                  </div>
                  <label className="acpe-field">
                    <span>Title</span>
                    <input
                      type="text"
                      maxLength={80}
                      value={offer.title}
                      onChange={(e) => updateOffer(i, 'title', e.target.value)}
                      placeholder="e.g. Member Benefits"
                    />
                  </label>
                  <label className="acpe-field">
                    <span>Description</span>
                    <textarea
                      rows={3}
                      maxLength={400}
                      value={offer.description}
                      onChange={(e) => updateOffer(i, 'description', e.target.value)}
                      placeholder="Short description shown inside the benefit card…"
                    />
                  </label>
                </article>
              ))}
            </div>
          </section>
        )}

        {/* ═══════════════════ SUPPORT & POLICY TAB ════════════════════ */}
        {tab === 'support' && (
          <section className="acpe-section">
            <div className="acpe-section-head">
              <div>
                <h2>Support &amp; Policy Text</h2>
                <p className="acpe-hint">
                  These strings appear in the Contact / Support card on the customer Account
                  Center page and in the platform entrance "About" section. All three fields
                  are required.
                </p>
              </div>
            </div>

            <div className="acpe-policy-grid">
              <label className="acpe-field">
                <span>Support hours</span>
                <input
                  type="text"
                  maxLength={80}
                  value={content.supportHours}
                  onChange={(e) =>
                    setContent((c) => ({ ...c, supportHours: e.target.value }))
                  }
                  placeholder="e.g. 9:00 AM to 10:00 PM IST"
                />
                <small className="acpe-field-note">
                  Shown as "Support window: …" and "Support Hours: …" on the Account Center,
                  Dashboard, and platform entrance page.
                </small>
              </label>

              <label className="acpe-field">
                <span>Cancellation policy text</span>
                <textarea
                  rows={3}
                  maxLength={400}
                  value={content.cancellationPolicy}
                  onChange={(e) =>
                    setContent((c) => ({ ...c, cancellationPolicy: e.target.value }))
                  }
                  placeholder="e.g. Cancellation requests are easiest to resolve before the event date…"
                />
                <small className="acpe-field-note">
                  Shown in the Contact / Support card beneath the customer's contact details.
                </small>
              </label>

              <label className="acpe-field">
                <span>Payment help policy text</span>
                <textarea
                  rows={3}
                  maxLength={400}
                  value={content.paymentHelpPolicy}
                  onChange={(e) =>
                    setContent((c) => ({ ...c, paymentHelpPolicy: e.target.value }))
                  }
                  placeholder="e.g. Payment help is available for pending, failed, and refund scenarios…"
                />
                <small className="acpe-field-note">
                  Shown below the cancellation policy text in the same Contact / Support card.
                </small>
              </label>
            </div>

            {/* ── Help and Trust panel (Dashboard) ─────────────────────── */}
            <div className="acpe-section-head" style={{ marginTop: '2rem' }}>
              <div>
                <h2>Help &amp; Trust panel (Dashboard)</h2>
                <p className="acpe-hint">
                  Heading + supporting bullet points shown beneath the
                  customer Dashboard hero. Use the literal token{' '}
                  <code>{'{hours}'}</code> in any bullet to splice in the
                  support hours from the field above. Up to 6 bullets — keep
                  them short and reassuring.
                </p>
              </div>
            </div>

            <div className="acpe-policy-grid">
              <label className="acpe-field">
                <span>Help &amp; Trust heading</span>
                <input
                  type="text"
                  maxLength={120}
                  value={content.helpAndTrustHeading || ''}
                  onChange={(e) =>
                    setContent((c) => ({ ...c, helpAndTrustHeading: e.target.value }))
                  }
                  placeholder="Support is visible before anything goes wrong"
                />
              </label>

              {(content.helpAndTrustPoints || []).map((point, idx) => (
                <label key={idx} className="acpe-field">
                  <span>Bullet {idx + 1}</span>
                  <textarea
                    rows={2}
                    maxLength={300}
                    value={point}
                    onChange={(e) => {
                      const next = [...(content.helpAndTrustPoints || [])];
                      next[idx] = e.target.value;
                      setContent((c) => ({ ...c, helpAndTrustPoints: next }));
                    }}
                  />
                  <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.35rem' }}>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => {
                        const next = [...(content.helpAndTrustPoints || [])];
                        next.splice(idx, 1);
                        setContent((c) => ({ ...c, helpAndTrustPoints: next.length ? next : c.helpAndTrustPoints }));
                      }}
                      disabled={(content.helpAndTrustPoints || []).length <= 1}
                    >
                      Remove
                    </button>
                  </div>
                </label>
              ))}

              {(content.helpAndTrustPoints || []).length < 6 && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  style={{ alignSelf: 'flex-start' }}
                  onClick={() =>
                    setContent((c) => ({
                      ...c,
                      helpAndTrustPoints: [...(c.helpAndTrustPoints || []), ''],
                    }))
                  }
                >
                  + Add bullet
                </button>
              )}
            </div>
          </section>
        )}

      </div>

      {/* ── Sticky save bar ────────────────────────────────────────────────── */}
      <div className="acpe-save-bar">
        <span className="acpe-save-bar-note">
          Changes are applied globally — every customer will see them on their next page load.
        </span>
        <button
          type="button"
          className="btn btn-primary"
          onClick={onSave}
          disabled={saving}
        >
          <FiSave /> {saving ? 'Saving…' : 'Save all changes'}
        </button>
      </div>
    </div>
  );
}
