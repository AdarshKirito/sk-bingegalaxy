import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'react-toastify';
import { FiArrowLeft, FiSave, FiExternalLink } from 'react-icons/fi';
import SEO from '../components/SEO';
import { siteContentService } from '../services/endpoints';
import {
  TERMS_OF_SERVICE_SLUG,
  ADMIN_ONBOARDING_TERMS_SLUG,
  DEFAULT_TERMS_TITLE,
  DEFAULT_ADMIN_TERMS_TITLE,
  parseTerms,
  serializeTerms,
} from '../content/legalContent';

const SECTIONS = [
  {
    slug: TERMS_OF_SERVICE_SLUG,
    heading: 'Customer Terms of Service & Privacy',
    blurb: 'Shown to customers at sign-up and on the public /terms page. Paste your full Terms of Service and Privacy Policy here.',
    defaultTitle: DEFAULT_TERMS_TITLE,
  },
  {
    slug: ADMIN_ONBOARDING_TERMS_SLUG,
    heading: 'Administrator Onboarding Terms',
    blurb: 'Shown in the “Add Admin” form. The new administrator must accept these terms before the account is created.',
    defaultTitle: DEFAULT_ADMIN_TERMS_TITLE,
  },
];

/**
 * Super-admin editor for the two legal/terms documents stored in the
 * site-content CMS. Plain textarea (paste-friendly) per the user's request.
 */
export default function AdminTermsEditor() {
  const [docs, setDocs] = useState({}); // slug -> { title, body }
  const [loading, setLoading] = useState(true);
  const [savingSlug, setSavingSlug] = useState('');

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled(SECTIONS.map((s) => siteContentService.getPublic(s.slug)))
      .then((results) => {
        if (cancelled) return;
        const next = {};
        results.forEach((res, i) => {
          const s = SECTIONS[i];
          const raw = res.status === 'fulfilled' ? res.value?.data?.data?.contentJson : null;
          next[s.slug] = parseTerms(raw, { title: s.defaultTitle, body: '' });
        });
        setDocs(next);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const patch = (slug, field, value) =>
    setDocs((d) => ({ ...d, [slug]: { ...d[slug], [field]: value } }));

  const save = async (section) => {
    const doc = docs[section.slug] || { title: section.defaultTitle, body: '' };
    if (!doc.body.trim()) { toast.error('Content cannot be empty'); return; }
    setSavingSlug(section.slug);
    try {
      await siteContentService.upsert(section.slug, serializeTerms(doc));
      toast.success(`${section.heading} saved`);
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save. Please try again.');
    } finally {
      setSavingSlug('');
    }
  };

  return (
    <div style={{ maxWidth: '900px', margin: '0 auto', padding: '1.5rem 1rem' }}>
      <SEO title="Terms Editor" description="Edit legal terms and onboarding agreements." />
      <Link to="/admin/platform" className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', marginBottom: '1rem' }}>
        <FiArrowLeft /> Back to console
      </Link>
      <h1 style={{ marginTop: 0 }}>Terms &amp; Legal Content</h1>
      <p style={{ color: 'var(--text-muted)', marginTop: '-0.25rem' }}>
        Paste and publish the terms customers and new administrators must accept.{' '}
        <Link to="/terms" target="_blank" rel="noreferrer" style={{ display: 'inline-flex', alignItems: 'center', gap: '0.25rem' }}>
          View public page <FiExternalLink size={13} />
        </Link>
      </p>

      {loading ? (
        <p style={{ color: 'var(--text-muted)' }}>Loading…</p>
      ) : (
        SECTIONS.map((section) => {
          const doc = docs[section.slug] || { title: section.defaultTitle, body: '' };
          return (
            <div key={section.slug} className="card" style={{ padding: '1.25rem', marginBottom: '1.25rem' }}>
              <h2 style={{ marginTop: 0, fontSize: '1.05rem' }}>{section.heading}</h2>
              <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem', marginTop: '-0.25rem' }}>{section.blurb}</p>
              <label style={{ fontWeight: 600, fontSize: '0.82rem', display: 'block', marginBottom: '0.3rem' }}>Heading</label>
              <input
                value={doc.title}
                onChange={(e) => patch(section.slug, 'title', e.target.value)}
                placeholder={section.defaultTitle}
                style={{ width: '100%', padding: '0.55rem 0.8rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', marginBottom: '0.75rem' }}
              />
              <label style={{ fontWeight: 600, fontSize: '0.82rem', display: 'block', marginBottom: '0.3rem' }}>Content</label>
              <textarea
                value={doc.body}
                onChange={(e) => patch(section.slug, 'body', e.target.value)}
                rows={12}
                placeholder="Paste the terms text here…"
                style={{ width: '100%', padding: '0.7rem 0.8rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text)', fontFamily: 'inherit', lineHeight: 1.5, resize: 'vertical' }}
              />
              <div style={{ marginTop: '0.75rem' }}>
                <button className="btn btn-primary btn-sm" disabled={savingSlug === section.slug}
                  onClick={() => save(section)} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem' }}>
                  <FiSave /> {savingSlug === section.slug ? 'Saving…' : 'Save & Publish'}
                </button>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}
