export default function ImagePopup({ imagePopup, setImagePopup }) {
  if (!imagePopup) return null;

  const popupOverlay = {
    position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.85)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000,
    cursor: 'pointer', padding: '1rem',
  };

  return (
    <div style={popupOverlay} onClick={() => setImagePopup(null)} role="dialog" aria-label="Image viewer">
      <div style={{ maxWidth: '90vw', maxHeight: '85vh', position: 'relative', textAlign: 'center' }} onClick={e => e.stopPropagation()}>
        <button onClick={() => setImagePopup(null)} aria-label="Close image viewer"
          style={{ position: 'absolute', top: '-12px', right: '-12px', background: 'var(--danger)', color: '#fff', border: 'none', borderRadius: '50%', width: '30px', height: '30px', cursor: 'pointer', fontWeight: 700, zIndex: 2 }}>×</button>
        {imagePopup.urls.length > 1 && (
          <>
            <button onClick={() => setImagePopup(p => ({ ...p, index: (p.index - 1 + p.urls.length) % p.urls.length }))} aria-label="Previous image"
              style={{ position: 'absolute', left: '-40px', top: '50%', transform: 'translateY(-50%)', background: 'rgba(255,255,255,0.2)', color: '#fff', border: 'none', borderRadius: '50%', width: '36px', height: '36px', cursor: 'pointer', fontSize: '1.3rem', zIndex: 2 }}>‹</button>
            <button onClick={() => setImagePopup(p => ({ ...p, index: (p.index + 1) % p.urls.length }))} aria-label="Next image"
              style={{ position: 'absolute', right: '-40px', top: '50%', transform: 'translateY(-50%)', background: 'rgba(255,255,255,0.2)', color: '#fff', border: 'none', borderRadius: '50%', width: '36px', height: '36px', cursor: 'pointer', fontSize: '1.3rem', zIndex: 2 }}>›</button>
          </>
        )}
        <img src={imagePopup.urls[imagePopup.index]} alt={imagePopup.name}
          style={{ maxWidth: '90vw', maxHeight: '80vh', borderRadius: 'var(--radius-md)', objectFit: 'contain' }} />
        <p style={{ color: '#fff', marginTop: '0.5rem', fontWeight: 600 }}>{imagePopup.name} {imagePopup.urls.length > 1 ? `(${imagePopup.index + 1}/${imagePopup.urls.length})` : ''}</p>
      </div>
    </div>
  );
}
