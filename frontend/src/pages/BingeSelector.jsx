import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { useBinge } from '../context/BingeContext';
import { toast } from 'react-toastify';

export default function BingeSelector() {
  const [binges, setBinges] = useState([]);
  const [loading, setLoading] = useState(true);
  const { selectBinge } = useBinge();
  const navigate = useNavigate();

  useEffect(() => {
    (async () => {
      try {
        const res = await bookingService.getAllActiveBinges();
        setBinges(res.data.data || res.data || []);
      } catch (err) {
        toast.error('Failed to load venues');
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const handleSelect = (binge) => {
    selectBinge({ id: binge.id, name: binge.name, address: binge.address });
    toast.success(`Selected: ${binge.name}`);
    navigate('/dashboard');
  };

  if (loading) return <div className="container"><p>Loading venues...</p></div>;

  return (
    <div className="container" style={{ maxWidth: 700, margin: '2rem auto' }}>
      <h2 style={{ textAlign: 'center', color: 'var(--text)' }}>Select a Venue</h2>
      <p style={{ textAlign: 'center', color: 'var(--text-secondary)', marginBottom: '2rem' }}>Choose a venue to browse events and make bookings.</p>

      {binges.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '3rem' }}>
          <p style={{ fontSize: '1.2rem', color: 'var(--text-muted)' }}>No venues available right now. Please check back later.</p>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: '1rem' }}>
          {binges.map((b) => (
            <div key={b.id} className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }} onClick={() => handleSelect(b)}>
              <div>
                <h3 style={{ margin: 0, color: 'var(--text)' }}>{b.name}</h3>
                {b.address && <p style={{ margin: '0.25rem 0 0', color: 'var(--text-secondary)', fontSize: '0.9rem' }}>{b.address}</p>}
              </div>
              <button className="btn btn-primary btn-sm">Select</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
