import { useEffect, useState } from 'react';
import { FiStar } from 'react-icons/fi';
import { bookingService } from '../../services/endpoints';
import { formatCurrency } from '../../utils/currency';
import './RoomDetailModal.css';

function Stars({ value = 0, small = false }) {
  const full = Math.round(value);
  return (
    <span className={`rdm-stars ${small ? 'rdm-stars--sm' : ''}`} aria-label={`${value} out of 5`}>
      {[1, 2, 3, 4, 5].map((s) => (
        <FiStar key={s} className={s <= full ? 'rdm-star on' : 'rdm-star'} />
      ))}
    </span>
  );
}

/**
 * Customer-facing room/space detail modal: an image carousel (‹ › + keyboard + thumbnails),
 * the room's description, and its rating / reviews / comments (derived from real bookings in
 * that room). Optional `onSelect(roomId)` lets the customer choose the room straight from here.
 */
export default function RoomDetailModal({ room, onClose, onSelect, currency = 'INR' }) {
  const [imgIndex, setImgIndex] = useState(0);
  const [summary, setSummary] = useState(null);
  const [reviews, setReviews] = useState([]);
  const [loading, setLoading] = useState(true);

  const images = Array.isArray(room?.imageUrls) ? room.imageUrls.filter(Boolean) : [];

  useEffect(() => {
    if (!room?.id) return;
    let live = true;
    setLoading(true);
    Promise.all([
      bookingService.getRoomReviewSummary(room.id).catch(() => null),
      bookingService.getRoomReviews(room.id, 0, 20).catch(() => null),
    ]).then(([s, r]) => {
      if (!live) return;
      setSummary(s?.data?.data || null);
      setReviews(r?.data?.data?.content || []);
    }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [room?.id]);

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onClose();
      if (images.length > 1 && e.key === 'ArrowLeft') setImgIndex((i) => (i - 1 + images.length) % images.length);
      if (images.length > 1 && e.key === 'ArrowRight') setImgIndex((i) => (i + 1) % images.length);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [images.length, onClose]);

  if (!room) return null;
  const avg = Number(summary?.averageRating || 0);
  const total = Number(summary?.totalReviews || 0);

  return (
    <div className="rdm-overlay" onClick={onClose} role="dialog" aria-modal="true" aria-label={`${room.name} details`}>
      <div className="rdm-modal" onClick={(e) => e.stopPropagation()}>
        <button className="rdm-close" onClick={onClose} aria-label="Close">×</button>

        <div className="rdm-gallery">
          {images.length > 0 ? (
            <>
              <img src={images[imgIndex]} alt={`${room.name} photo ${imgIndex + 1}`} className="rdm-hero"
                onError={(e) => { e.currentTarget.style.opacity = '0.25'; }} />
              {images.length > 1 && (
                <>
                  <button className="rdm-nav rdm-prev" aria-label="Previous photo"
                    onClick={() => setImgIndex((i) => (i - 1 + images.length) % images.length)}>‹</button>
                  <button className="rdm-nav rdm-next" aria-label="Next photo"
                    onClick={() => setImgIndex((i) => (i + 1) % images.length)}>›</button>
                  <div className="rdm-counter">{imgIndex + 1} / {images.length}</div>
                </>
              )}
            </>
          ) : (
            <div className="rdm-noimg">No photos yet</div>
          )}
        </div>

        {images.length > 1 && (
          <div className="rdm-thumbs">
            {images.map((u, i) => (
              <img key={i} src={u} alt="" className={i === imgIndex ? 'rdm-thumb active' : 'rdm-thumb'}
                onClick={() => setImgIndex(i)} onError={(e) => { e.currentTarget.style.display = 'none'; }} />
            ))}
          </div>
        )}

        <div className="rdm-body">
          <div className="rdm-head">
            <div>
              <h2>{room.name}</h2>
              {room.roomType && <span className="rdm-type">{String(room.roomType).replace(/_/g, ' ')}</span>}
            </div>
            {Number(room.priceAddition) > 0 && (
              <span className="rdm-price">+{formatCurrency(Number(room.priceAddition), currency)}</span>
            )}
          </div>

          <div className="rdm-rating">
            <Stars value={avg} />
            <span className="rdm-rating-text">{total > 0 ? `${avg.toFixed(1)} · ${total} review${total > 1 ? 's' : ''}` : 'No reviews yet'}</span>
          </div>

          {room.description && <p className="rdm-desc">{room.description}</p>}

          {onSelect && (
            <button className="btn btn-primary rdm-select" onClick={() => { onSelect(room.id); onClose(); }}>
              Select this room
            </button>
          )}

          <div className="rdm-reviews">
            <h3>Reviews &amp; Comments</h3>
            {loading ? (
              <p className="rdm-muted">Loading reviews…</p>
            ) : reviews.length === 0 ? (
              <p className="rdm-muted">No reviews for this room yet — be the first after your visit.</p>
            ) : (
              reviews.map((rv) => (
                <div key={rv.id} className="rdm-review">
                  <div className="rdm-review-head">
                    <Stars value={rv.rating || 0} small />
                    <span className="rdm-review-name">{rv.customerName || 'Guest'}</span>
                    {rv.createdAt && <span className="rdm-review-date">{new Date(rv.createdAt).toLocaleDateString()}</span>}
                  </div>
                  {rv.comment && <p className="rdm-review-comment">{rv.comment}</p>}
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
