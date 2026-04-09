import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { useBinge } from '../context/BingeContext';
import { toast } from 'react-toastify';
import SEO from '../components/SEO';
import { FiArrowRight, FiCompass, FiMapPin } from 'react-icons/fi';
import './CustomerHub.css';

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
        <div className="customer-venue-grid">
          {binges.map((binge) => (
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
    </div>
  );
}
