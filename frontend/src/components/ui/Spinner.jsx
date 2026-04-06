export default function Spinner({ size = 36, text = 'Loading...' }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', padding: '2rem' }} role="status">
      <div style={{ textAlign: 'center', color: 'var(--text-secondary)' }}>
        <div style={{
          width: `${size}px`, height: `${size}px`,
          border: '3px solid var(--border)', borderTopColor: 'var(--primary)',
          borderRadius: '50%', animation: 'spin 0.8s linear infinite',
          margin: '0 auto 0.75rem',
        }} />
        {text && <span>{text}</span>}
      </div>
    </div>
  );
}
