import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { FiArrowLeft } from 'react-icons/fi';
import { siteContentService } from '../services/endpoints';
import { TERMS_OF_SERVICE_SLUG, DEFAULT_TERMS_TITLE, parseTerms } from '../content/legalContent';
import SEO from '../components/SEO';
import './Auth.css';

/**
 * Public Terms of Service & Privacy page. Content is authored by a super-admin
 * via the Terms editor and stored in the site-content CMS (slug
 * `terms-of-service`), so it can change without a deploy.
 */
export default function Terms() {
  const [terms, setTerms] = useState({ title: DEFAULT_TERMS_TITLE, body: '' });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    siteContentService.getPublic(TERMS_OF_SERVICE_SLUG)
      .then((res) => {
        if (cancelled) return;
        setTerms(parseTerms(res.data?.data?.contentJson, { title: DEFAULT_TERMS_TITLE, body: '' }));
      })
      .catch(() => {
        if (!cancelled) {
          setTerms({ title: DEFAULT_TERMS_TITLE, body: 'Terms are currently unavailable. Please contact support.' });
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="auth-page">
      <SEO title="Terms & Privacy" description="SK Binge Galaxy Terms of Service and Privacy Policy." />
      <div className="auth-shell" style={{ display: 'block', maxWidth: '820px', margin: '0 auto', padding: '2rem 1rem' }}>
        <Link to="/" className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', marginBottom: '1rem' }}>
          <FiArrowLeft /> Back
        </Link>
        <div className="auth-card card" style={{ maxWidth: 'none' }}>
          <h1 style={{ marginTop: 0 }}>{terms.title || DEFAULT_TERMS_TITLE}</h1>
          {loading ? (
            <p style={{ color: 'var(--text-muted)' }}>Loading…</p>
          ) : (
            <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6, color: 'var(--text)' }}>
              {terms.body || 'No terms have been published yet.'}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
