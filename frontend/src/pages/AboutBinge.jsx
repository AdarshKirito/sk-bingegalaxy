import { useEffect, useMemo, useState, useCallback } from 'react';
import { FiCheckCircle, FiChevronDown, FiInfo, FiMapPin, FiMessageCircle, FiShield, FiStar } from 'react-icons/fi';
import { bookingService } from '../services/endpoints';
import { normalizeAboutExperience } from '../services/aboutExperience';
import useBingeStore from '../stores/bingeStore';
import DOMPurify from 'dompurify';
import './AboutBinge.css';

function StarRating({ rating, size = '1rem' }) {
  return (
    <span className="about-review-stars" aria-label={`${rating} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map(s => (
        <span key={s} style={{ color: s <= rating ? '#f59e0b' : 'var(--border)', fontSize: size }}>★</span>
      ))}
    </span>
  );
}

function RatingBar({ star, count, total }) {
  const pct = total > 0 ? Math.round((count / total) * 100) : 0;
  return (
    <div className="about-review-bar-row">
      <span className="about-review-bar-label">{star}★</span>
      <div className="about-review-bar-track">
        <div className="about-review-bar-fill" style={{ width: `${pct}%` }} />
      </div>
      <span className="about-review-bar-count">{count}</span>
    </div>
  );
}

export default function AboutBinge() {
  const { selectedBinge } = useBingeStore();
  const [loading, setLoading] = useState(true);
  const [aboutConfig, setAboutConfig] = useState(() => normalizeAboutExperience(null));
  const [reviewSummary, setReviewSummary] = useState(null);
  const [reviews, setReviews] = useState([]);
  const [reviewPage, setReviewPage] = useState(0);
  const [reviewTotalPages, setReviewTotalPages] = useState(0);
  const [reviewsLoading, setReviewsLoading] = useState(false);

  useEffect(() => {
    const load = async () => {
      if (!selectedBinge?.id) {
        setAboutConfig(normalizeAboutExperience(null));
        setLoading(false);
        return;
      }
      setLoading(true);
      try {
        const [aboutRes, summaryRes] = await Promise.allSettled([
          bookingService.getBingeAboutExperience(selectedBinge.id),
          bookingService.getBingeReviewSummary(selectedBinge.id),
        ]);
        setAboutConfig(normalizeAboutExperience(aboutRes.status === 'fulfilled' ? (aboutRes.value.data?.data || aboutRes.value.data || null) : null));
        if (summaryRes.status === 'fulfilled') setReviewSummary(summaryRes.value.data?.data || summaryRes.value.data || null);
      } catch {
        setAboutConfig(normalizeAboutExperience(null));
      } finally {
        setLoading(false);
      }
    };

    load();
  }, [selectedBinge?.id]);

  const fetchReviews = useCallback(async (page = 0) => {
    if (!selectedBinge?.id) return;
    setReviewsLoading(true);
    try {
      const res = await bookingService.getBingeReviews(selectedBinge.id, page, 5);
      const d = res.data?.data || res.data;
      const content = d?.content || [];
      setReviews(prev => page === 0 ? content : [...prev, ...content]);
      setReviewPage(page);
      setReviewTotalPages(d?.totalPages || 0);
    } catch { /* ignore */ }
    finally { setReviewsLoading(false); }
  }, [selectedBinge?.id]);

  useEffect(() => {
    if (selectedBinge?.id) fetchReviews(0);
  }, [selectedBinge?.id, fetchReviews]);

  const supportItems = useMemo(() => {
    const items = [];
    if (selectedBinge?.supportEmail) {
      items.push({
        label: 'Email',
        value: selectedBinge.supportEmail,
        href: `mailto:${selectedBinge.supportEmail}`,
      });
    }
    if (selectedBinge?.supportPhone) {
      items.push({
        label: 'Phone',
        value: selectedBinge.supportPhone,
        href: `tel:${selectedBinge.supportPhone.replace(/\s+/g, '')}`,
      });
    }
    if (selectedBinge?.supportWhatsapp) {
      items.push({
        label: 'WhatsApp',
        value: selectedBinge.supportWhatsapp,
        href: `https://wa.me/${selectedBinge.supportWhatsapp.replace(/\D+/g, '')}`,
      });
    }
    return items;
  }, [selectedBinge]);

  if (loading) {
    return (
      <div className="container about-binge-page">
        <section className="about-binge-loading card">
          <h2>Loading binge guide...</h2>
          <p>Preparing venue details, rules, and policies.</p>
        </section>
      </div>
    );
  }

  return (
    <div className="container about-binge-page">
      <section className="about-binge-hero card">
        <span className="about-binge-kicker">
          <FiInfo /> {aboutConfig.sectionEyebrow}
        </span>
        <h1>{aboutConfig.sectionTitle}</h1>
        {aboutConfig.sectionSubtitle && <p className="about-binge-subtitle">{aboutConfig.sectionSubtitle}</p>}

        <div className="about-binge-hero-panel">
          <div>
            <span className="about-binge-mini-kicker">{selectedBinge?.name || 'Selected binge'}</span>
            <h2>{aboutConfig.heroTitle}</h2>
            <p>{aboutConfig.heroDescription}</p>
          </div>
          <div className="about-binge-hero-meta">
            <span><FiMapPin /> {selectedBinge?.address || 'Address will appear when configured by admin.'}</span>
            <span><FiShield /> Rules and policies are managed by venue admin in real time.</span>
          </div>
        </div>
      </section>

      <section className="about-binge-grid">
        <article className="card about-binge-panel">
          <div className="about-binge-panel-head">
            <h3><FiStar /> {aboutConfig.highlightsTitle}</h3>
          </div>
          <div className="about-binge-highlights">
            {aboutConfig.highlights.map((item, index) => (
              <div key={`highlight-${index}`} className="about-binge-highlight-item">
                <h4>{item.title}</h4>
                <p>{item.description}</p>
              </div>
            ))}
          </div>
        </article>

        <article className="card about-binge-panel">
          <div className="about-binge-panel-head">
            <h3><FiCheckCircle /> {aboutConfig.houseRulesTitle}</h3>
          </div>
          <ol className="about-binge-rules">
            {aboutConfig.houseRules.map((rule, index) => (
              <li key={`rule-${index}`}>{rule}</li>
            ))}
          </ol>
        </article>
      </section>

      <section className="card about-binge-panel">
        <div className="about-binge-panel-head">
          <h3><FiShield /> {aboutConfig.policyTitle}</h3>
        </div>
        <div className="about-binge-policies">
          {aboutConfig.policies.map((policy, index) => (
            <article key={`policy-${index}`} className="about-binge-policy-item">
              <h4>{policy.title}</h4>
              <p>{policy.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="card about-binge-panel">
        <div className="about-binge-panel-head">
          <h3><FiInfo /> {aboutConfig.contactHeading}</h3>
        </div>
        <p className="about-binge-contact-description">{aboutConfig.contactDescription}</p>
        {supportItems.length > 0 ? (
          <div className="about-binge-support-links">
            {supportItems.map((item) => (
              <a key={item.label} href={item.href} target={item.label === 'WhatsApp' ? '_blank' : undefined} rel="noreferrer" className="btn btn-secondary btn-sm">
                {item.label}: {item.value}
              </a>
            ))}
          </div>
        ) : (
          <p className="about-binge-support-empty">Support channels will appear here once configured by the admin.</p>
        )}
      </section>

      {/* ─── Customer Reviews ─── */}
      {(reviewSummary?.totalReviews > 0 || reviews.length > 0) && (
        <section className="card about-binge-panel about-reviews-section">
          <div className="about-binge-panel-head">
            <h3><FiMessageCircle /> Customer Reviews</h3>
          </div>

          {reviewSummary && (
            <div className="about-reviews-summary">
              <div className="about-reviews-score">
                <span className="about-reviews-avg">{reviewSummary.averageRating?.toFixed(1) || '0.0'}</span>
                <StarRating rating={Math.round(reviewSummary.averageRating || 0)} size="1.15rem" />
                <span className="about-reviews-total">{reviewSummary.totalReviews} review{reviewSummary.totalReviews !== 1 ? 's' : ''}</span>
              </div>
              <div className="about-reviews-distribution">
                {[5, 4, 3, 2, 1].map(star => (
                  <RatingBar key={star} star={star} count={reviewSummary.ratingDistribution?.[star] || 0} total={reviewSummary.totalReviews} />
                ))}
              </div>
            </div>
          )}

          {reviews.length > 0 && (
            <div className="about-reviews-list">
              {reviews.map((r, idx) => (
                <article key={r.id || idx} className="about-review-card">
                  <div className="about-review-card-header">
                    <span className="about-review-avatar">{(r.customerName || 'C').charAt(0).toUpperCase()}</span>
                    <div className="about-review-meta">
                      <span className="about-review-name">{r.customerName || 'Customer'}</span>
                      <span className="about-review-date">
                        {r.createdAt ? new Date(r.createdAt).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' }) : ''}
                      </span>
                    </div>
                    <StarRating rating={r.rating} size="0.9rem" />
                  </div>
                  {r.comment && (
                    <p className="about-review-comment" dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(r.comment) }} />
                  )}
                  {r.eventTypeName && (
                    <span className="about-review-event-tag">{r.eventTypeName}</span>
                  )}
                </article>
              ))}
            </div>
          )}

          {reviewPage + 1 < reviewTotalPages && (
            <button className="btn btn-secondary about-reviews-load-more" onClick={() => fetchReviews(reviewPage + 1)} disabled={reviewsLoading}>
              {reviewsLoading ? 'Loading...' : <><FiChevronDown /> Load More Reviews</>}
            </button>
          )}
        </section>
      )}
    </div>
  );
}
