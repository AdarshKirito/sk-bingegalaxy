import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { useBinge } from '../context/BingeContext';
import { toast } from 'react-toastify';
import SEO from '../components/SEO';
import { FiArrowRight, FiCompass, FiMapPin, FiSearch } from 'react-icons/fi';
import './CustomerHub.css';

export default function BingeSelector() {
  const [binges, setBinges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
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
    selectBinge({
      id: binge.id,
      name: binge.name,
      address: binge.address,
      supportEmail: binge.supportEmail,
      supportPhone: binge.supportPhone,
      supportWhatsapp: binge.supportWhatsapp,
      customerCancellationEnabled: binge.customerCancellationEnabled,
      customerCancellationCutoffMinutes: binge.customerCancellationCutoffMinutes,
    });
    toast.success(`Selected: ${binge.name}`);
    navigate('/dashboard');
  };

  const filteredBinges = useMemo(() => {
    if (!search.trim()) return binges;
    const q = search.trim().toLowerCase();
    return binges.filter((b) => (b.name || '').toLowerCase().includes(q) || (b.address || '').toLowerCase().includes(q));
  }, [binges, search]);

  if (loading) {
    return (
      <div className="container customer-flow-shell customer-flow-shell-narrow">
        <SEO title="Select Venue" description="Choose the venue that anchors your customer booking experience." />
        <div className="customer-flow-card customer-flow-empty">
          <span className="customer-flow-icon"><FiCompass /></span>
          <h2>Loading venues...</h2>
          <p>Preparing the available locations for your next private screening.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="container customer-flow-shell customer-flow-shell-narrow">
      <SEO title="Select Venue" description="Choose the venue that anchors your customer booking experience." />

      <section className="customer-flow-copy">
        <span className="customer-flow-kicker">Choose venue</span>
        <h1>Select the venue that sets the base for bookings, pricing, and support.</h1>
        <p>Once you pick a venue, the customer dashboard, booking flow, and account experience all center around that location.</p>
      </section>

      {binges.length === 0 ? (
        <div className="customer-flow-card customer-flow-empty">
          <span className="customer-flow-icon"><FiMapPin /></span>
          <h2>No venues available right now</h2>
          <p>Please check back later for active locations.</p>
        </div>
      ) : (
        <>
          {binges.length > 0 && (
            <div className="entrance-search" style={{ marginBottom: '1rem' }}>
              <FiSearch style={{ position: 'absolute', left: '1rem', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)', pointerEvents: 'none' }} />
              <input
                type="text"
                placeholder="Search venues by name or address…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                style={{ width: '100%', padding: '0.75rem 1rem 0.75rem 2.6rem', border: '1px solid rgba(var(--primary-rgb), 0.12)', borderRadius: '14px', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.92rem' }}
              />
            </div>
          )}
          {filteredBinges.length === 0 ? (
            <div className="customer-flow-card customer-flow-empty">
              <span className="customer-flow-icon"><FiSearch /></span>
              <h2>No matching venues</h2>
              <p>Try a different search term.</p>
            </div>
          ) : (
            <div className="customer-venue-grid">
              {filteredBinges.map((binge) => (
            <article key={binge.id} className="customer-venue-card" onClick={() => handleSelect(binge)}>
              <div className="customer-venue-card-copy">
                <span className="customer-flow-kicker">Venue option</span>
                <h3>{binge.name}</h3>
                <p>{binge.address || 'Private-screening location ready for bookings.'}</p>
              </div>
              <button type="button" className="btn btn-primary btn-sm">
                Select <FiArrowRight />
              </button>
            </article>
          ))}
        </div>
          )}
        </>
      )}
    </div>
  );
}
