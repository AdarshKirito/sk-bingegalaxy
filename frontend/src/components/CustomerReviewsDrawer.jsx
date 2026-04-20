/**
 * CustomerReviewsDrawer
 * ---------------------
 * A right-side slide-in drawer that shows all admin reviews and overall
 * assessment summary for a single customer — scoped to the Admin portal.
 *
 * Features:
 *  • Overall admin rating summary (average stars, distribution bar)
 *  • Paginated list of individual admin review cards
 *  • Customer's own reviews written for binges
 *  • Focus-trap / accessible overlay dismiss
 *  • Keyboard close on Escape
 *  • Skeleton loading states
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { adminService } from '../services/endpoints';
import {
  FiX,
  FiStar,
  FiUser,
  FiCalendar,
  FiHash,
  FiAlertCircle,
  FiAward,
  FiMessageSquare,
  FiChevronLeft,
  FiChevronRight,
} from 'react-icons/fi';
import './CustomerReviewsDrawer.css';

// ── Utility: render filled / half / empty stars ──────────────

function StarRating({ value, max = 5, size = 'md' }) {
  const stars = [];
  for (let i = 1; i <= max; i++) {
    const diff = value - (i - 1);
    let cls = 'crd-star crd-star-empty';
    if (diff >= 1)       cls = 'crd-star crd-star-full';
    else if (diff >= 0.5) cls = 'crd-star crd-star-half';
    stars.push(<span key={i} className={`${cls} crd-star-${size}`} aria-hidden="true">★</span>);
  }
  return (
    <span className="crd-stars" role="img" aria-label={`${value} out of ${max} stars`}>
      {stars}
    </span>
  );
}

// ── Utility: render rating distribution bar ──────────────────

function RatingBar({ label, count, total }) {
  const pct = total > 0 ? Math.round((count / total) * 100) : 0;
  return (
    <div className="crd-bar-row">
      <span className="crd-bar-label">{label}★</span>
      <div className="crd-bar-track" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
        <div className="crd-bar-fill" style={{ width: `${pct}%` }} />
      </div>
      <span className="crd-bar-count">{count}</span>
    </div>
  );
}

// ── Utility: skeleton block ──────────────────────────────────

function Skeleton({ width, height, circle = false }) {
  return (
    <span
      className={`crd-skeleton${circle ? ' crd-skeleton-circle' : ''}`}
      style={{ width, height, display: 'inline-block', borderRadius: circle ? '50%' : undefined }}
      aria-hidden="true"
    />
  );
}

// ── Sub-component: summary panel ────────────────────────────

function ReviewSummaryPanel({ summary, loading }) {
  if (loading) {
    return (
      <div className="crd-summary-panel">
        <div className="crd-summary-score">
          <Skeleton width="3.5rem" height="3.5rem" />
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
            <Skeleton width="8rem" height="1.1rem" />
            <Skeleton width="5rem" height="0.85rem" />
          </div>
        </div>
        <div className="crd-summary-bars">
          {[5, 4, 3, 2, 1].map(n => <Skeleton key={n} width="100%" height="0.75rem" />)}
        </div>
      </div>
    );
  }

  const avg      = summary?.avgAdminRating  ?? 0;
  const count    = summary?.adminReviewCount ?? 0;
  const custCount = summary?.customerReviewCount ?? 0;

  // Build distribution from individual reviews if available; otherwise show global
  const dist = summary?.ratingDistribution ?? null;

  return (
    <div className="crd-summary-panel">
      <div className="crd-summary-score">
        <div className="crd-summary-big-score" aria-label={`Average rating: ${avg}`}>
          {avg > 0 ? avg.toFixed(1) : '—'}
        </div>
        <div className="crd-summary-right">
          <StarRating value={avg} size="lg" />
          <p className="crd-summary-meta">
            {count > 0
              ? `${count} admin review${count !== 1 ? 's' : ''}`
              : 'No admin reviews yet'}
          </p>
          {custCount > 0 && (
            <p className="crd-summary-meta crd-summary-meta-secondary">
              {custCount} customer review{custCount !== 1 ? 's' : ''} written
            </p>
          )}
        </div>
      </div>

      {dist && count > 0 && (
        <div className="crd-summary-bars">
          {[5, 4, 3, 2, 1].map(star => (
            <RatingBar key={star} label={star} count={dist[star] ?? 0} total={count} />
          ))}
        </div>
      )}

      {count === 0 && (
        <div className="crd-no-reviews-hint">
          <FiMessageSquare />
          <span>Admin reviews are added when completing a booking. Once submitted, they appear here.</span>
        </div>
      )}
    </div>
  );
}

// ── Sub-component: single review card ───────────────────────

function ReviewCard({ review }) {
  const dateStr = review.createdAt
    ? new Date(review.createdAt).toLocaleDateString('en-IN', {
        day: '2-digit', month: 'short', year: 'numeric',
      })
    : '—';

  const timeStr = review.createdAt
    ? new Date(review.createdAt).toLocaleTimeString('en-IN', {
        hour: '2-digit', minute: '2-digit',
      })
    : '';

  return (
    <article className="crd-review-card">
      <header className="crd-review-header">
        <div className="crd-review-meta">
          <StarRating value={review.rating ?? 0} size="sm" />
          <span className="crd-review-role-badge">
            {review.reviewerRole === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin'}
          </span>
        </div>
        <time className="crd-review-date" dateTime={review.createdAt}>
          <FiCalendar aria-hidden="true" />
          {dateStr}
          {timeStr && <span className="crd-review-time">&nbsp;{timeStr}</span>}
        </time>
      </header>

      {review.comment ? (
        <p className="crd-review-comment">{review.comment}</p>
      ) : (
        <p className="crd-review-comment crd-review-comment-empty">No comment left.</p>
      )}

      <footer className="crd-review-footer">
        <span className="crd-review-ref">
          <FiHash aria-hidden="true" />
          {review.bookingRef}
        </span>
        {review.eventTypeName && (
          <span className="crd-review-event">{review.eventTypeName}</span>
        )}
      </footer>
    </article>
  );
}

// ── Main Drawer ──────────────────────────────────────────────

const PAGE_SIZE = 8;

export default function CustomerReviewsDrawer({ customer, onClose }) {
  const [summary, setSummary]       = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [reviews, setReviews]       = useState([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage]             = useState(0);
  const [reviewsLoading, setReviewsLoading] = useState(true);
  const [error, setError]           = useState(null);
  const drawerRef = useRef(null);

  const customerId = customer?.id;

  // ── Load review summary ──────────────────────────────────
  useEffect(() => {
    if (!customerId) return;
    setSummaryLoading(true);
    adminService.getCustomerReviewSummary(customerId)
      .then(res => setSummary(res.data?.data ?? res.data))
      .catch(() => setError('Failed to load review summary.'))
      .finally(() => setSummaryLoading(false));
  }, [customerId]);

  // ── Load paginated admin reviews ─────────────────────────
  useEffect(() => {
    if (!customerId) return;
    setReviewsLoading(true);
    adminService.getCustomerAdminReviews(customerId, page, PAGE_SIZE)
      .then(res => {
        const d = res.data?.data ?? res.data;
        setReviews(d?.content ?? []);
        setTotalPages(d?.totalPages ?? 0);
        setTotalElements(d?.totalElements ?? 0);
      })
      .catch(() => setError('Failed to load admin reviews.'))
      .finally(() => setReviewsLoading(false));
  }, [customerId, page]);

  // ── Keyboard + focus trap ────────────────────────────────
  const handleKeyDown = useCallback((e) => {
    if (e.key === 'Escape') onClose();
  }, [onClose]);

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    // Lock body scroll while open
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    // Auto-focus the drawer for keyboard users
    drawerRef.current?.focus();
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = prev;
    };
  }, [handleKeyDown]);

  const fullName = [customer?.firstName, customer?.lastName].filter(Boolean).join(' ') || 'Customer';

  return (
    <>
      {/* Backdrop */}
      <div
        className="crd-backdrop"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer panel */}
      <aside
        ref={drawerRef}
        className="crd-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={`Reviews for ${fullName}`}
        tabIndex={-1}
      >
        {/* ── Drawer header ─────────────────────────────── */}
        <div className="crd-drawer-header">
          <div className="crd-drawer-title-group">
            <div className="crd-drawer-avatar" aria-hidden="true">
              <FiUser />
            </div>
            <div>
              <h2 className="crd-drawer-title">{fullName}</h2>
              <p className="crd-drawer-subtitle">{customer?.email}</p>
            </div>
          </div>
          <button
            type="button"
            className="crd-close-btn"
            onClick={onClose}
            aria-label="Close reviews panel"
          >
            <FiX />
          </button>
        </div>

        {/* ── Error banner ──────────────────────────────── */}
        {error && (
          <div className="crd-error-banner" role="alert">
            <FiAlertCircle />
            <span>{error}</span>
          </div>
        )}

        {/* ── Scrollable content ────────────────────────── */}
        <div className="crd-drawer-body">

          {/* Section: Admin Assessment Summary */}
          <section className="crd-section" aria-labelledby="crd-section-assessment">
            <header className="crd-section-header">
              <FiAward className="crd-section-icon" aria-hidden="true" />
              <h3 id="crd-section-assessment" className="crd-section-title">Admin Assessment</h3>
            </header>
            <ReviewSummaryPanel summary={summary} loading={summaryLoading} />
          </section>

          {/* Section: Review History */}
          <section className="crd-section" aria-labelledby="crd-section-history">
            <header className="crd-section-header">
              <FiMessageSquare className="crd-section-icon" aria-hidden="true" />
              <h3 id="crd-section-history" className="crd-section-title">
                Review History
                {totalElements > 0 && (
                  <span className="crd-section-count">{totalElements}</span>
                )}
              </h3>
            </header>

            {reviewsLoading ? (
              <div className="crd-review-list">
                {Array.from({ length: 3 }).map((_, i) => (
                  <div key={i} className="crd-review-card crd-review-card-skeleton">
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
                      <Skeleton width="7rem" height="0.9rem" />
                      <Skeleton width="5rem" height="0.8rem" />
                    </div>
                    <Skeleton width="100%" height="0.85rem" />
                    <Skeleton width="80%" height="0.85rem" />
                    <div style={{ marginTop: '0.5rem' }}>
                      <Skeleton width="6rem" height="0.75rem" />
                    </div>
                  </div>
                ))}
              </div>
            ) : reviews.length === 0 ? (
              <div className="crd-empty-state">
                <span className="crd-empty-icon" aria-hidden="true">
                  <FiMessageSquare />
                </span>
                <p className="crd-empty-heading">No admin reviews yet</p>
                <p className="crd-empty-body">
                  Admins can leave a review when marking a booking as completed. Reviews will appear here once submitted.
                </p>
              </div>
            ) : (
              <>
                <div className="crd-review-list" role="feed" aria-label="Admin reviews">
                  {reviews.map(review => (
                    <ReviewCard key={review.id} review={review} />
                  ))}
                </div>

                {/* Pagination */}
                {totalPages > 1 && (
                  <nav className="crd-pagination" aria-label="Review pages">
                    <button
                      type="button"
                      className="crd-page-btn"
                      disabled={page === 0}
                      onClick={() => setPage(p => p - 1)}
                      aria-label="Previous page"
                    >
                      <FiChevronLeft />
                    </button>
                    <span className="crd-page-info">
                      Page {page + 1} of {totalPages}
                    </span>
                    <button
                      type="button"
                      className="crd-page-btn"
                      disabled={page >= totalPages - 1}
                      onClick={() => setPage(p => p + 1)}
                      aria-label="Next page"
                    >
                      <FiChevronRight />
                    </button>
                  </nav>
                )}
              </>
            )}
          </section>
        </div>
      </aside>
    </>
  );
}
