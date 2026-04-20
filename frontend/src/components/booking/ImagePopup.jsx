import { useEffect } from 'react';

export default function ImagePopup({ imagePopup, setImagePopup }) {
  useEffect(() => {
    if (!imagePopup) return;
    const handleKey = (e) => { if (e.key === 'Escape') setImagePopup(null); };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [imagePopup, setImagePopup]);

  if (!imagePopup) return null;

  const popupOverlay = {
    position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.92)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000,
    cursor: 'pointer', padding: '0.75rem',
  };

  return (
    <div style={popupOverlay} onClick={() => setImagePopup(null)} role="dialog" aria-label="Image viewer">
      <div style={{ maxWidth: '96vw', maxHeight: '94vh', position: 'relative', textAlign: 'center' }} onClick={e => e.stopPropagation()}>
        <button onClick={() => setImagePopup(null)} aria-label="Close image viewer"
          style={{ position: 'absolute', top: '-16px', right: '-16px', background: 'var(--danger)', color: '#fff', border: 'none', borderRadius: '50%', width: '40px', height: '40px', cursor: 'pointer', fontWeight: 700, fontSize: '1.25rem', zIndex: 2, boxShadow: '0 2px 8px rgba(0,0,0,0.3)' }}>×</button>
        {imagePopup.urls.length > 1 && (
          <>
            <button onClick={() => setImagePopup(p => ({ ...p, index: (p.index - 1 + p.urls.length) % p.urls.length }))} aria-label="Previous image"
              style={{ position: 'absolute', left: '-50px', top: '50%', transform: 'translateY(-50%)', background: 'rgba(255,255,255,0.25)', color: '#fff', border: 'none', borderRadius: '50%', width: '48px', height: '48px', cursor: 'pointer', fontSize: '1.6rem', zIndex: 2, backdropFilter: 'blur(4px)', boxShadow: '0 2px 8px rgba(0,0,0,0.2)' }}>‹</button>
            <button onClick={() => setImagePopup(p => ({ ...p, index: (p.index + 1) % p.urls.length }))} aria-label="Next image"
              style={{ position: 'absolute', right: '-50px', top: '50%', transform: 'translateY(-50%)', background: 'rgba(255,255,255,0.25)', color: '#fff', border: 'none', borderRadius: '50%', width: '48px', height: '48px', cursor: 'pointer', fontSize: '1.6rem', zIndex: 2, backdropFilter: 'blur(4px)', boxShadow: '0 2px 8px rgba(0,0,0,0.2)' }}>›</button>
          </>
        )}
        <img src={imagePopup.urls[imagePopup.index]} alt={imagePopup.name}
          style={{ maxWidth: '95vw', maxHeight: '90vh', borderRadius: 'var(--radius-lg, 12px)', objectFit: 'contain', boxShadow: '0 8px 32px rgba(0,0,0,0.4)' }} />
        <p style={{ color: '#fff', marginTop: '0.65rem', fontWeight: 600, fontSize: '1rem' }}>{imagePopup.name} {imagePopup.urls.length > 1 ? `(${imagePopup.index + 1}/${imagePopup.urls.length})` : ''}</p>
      </div>
    </div>
  );
}
