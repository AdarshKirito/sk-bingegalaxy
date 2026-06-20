import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { useBinge } from '../context/BingeContext';
import { toast } from 'react-toastify';
import SEO from '../components/SEO';
import { FiArrowRight, FiCompass, FiCrosshair, FiMapPin, FiNavigation, FiSearch, FiStar } from 'react-icons/fi';
import useGeolocation from '../hooks/useGeolocation';
import { formatDistance } from '../services/geo';
import './CustomerHub.css';

// Radius tiers offered in the proximity banner (km). Mirrors the consumer-app
// pattern of "nothing nearby? widen the search" without free-form input.
const RADIUS_TIERS = [5, 25, 50, 100, 250];
const DEFAULT_RADIUS_KM = 50;

// Render star icons based on average rating
function StarRating({ avg, count }) {
  const rounded = Math.round((avg || 0) * 2) / 2; // round to 0.5
  const stars = [];
  for (let i = 1; i <= 5; i++) {
    if (i <= rounded) {
      stars.push(<FiStar key={i} style={{ fill: 'var(--gold, #c29e46)', color: 'var(--gold, #c29e46)', width: 14, height: 14 }} />);
    } else if (i - 0.5 === rounded) {
      stars.push(<FiStar key={i} style={{ color: 'var(--gold, #c29e46)', width: 14, height: 14 }} />);
    } else {
      stars.push(<FiStar key={i} style={{ color: 'var(--border-hover, #ccc)', width: 14, height: 14 }} />);
    }
  }
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
      {stars}
      <strong style={{ fontSize: '0.82rem', color: 'var(--text)' }}>{avg ? avg.toFixed(1) : '—'}</strong>
      <span>({count || 0} review{count === 1 ? '' : 's'})</span>
    </span>
  );
}

export default function BingeSelector() {
  const [binges, setBinges] = useState([]);
  const [reviewSummaries, setReviewSummaries] = useState({});
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');

  // Proximity state. mode === 'nearby' shows the distance-ranked list; 'all' is the
  // alphabetical fallback. coords are held in memory for the session only (we never
  // persist precise location to disk).
  const [mode, setMode] = useState('all');
  const [coords, setCoords] = useState(null);
  const [radiusKm, setRadiusKm] = useState(DEFAULT_RADIUS_KM);
  const [nearby, setNearby] = useState([]);
  const [nearbyLoading, setNearbyLoading] = useState(false);

  // Coarse/fast positioning is right for discovery — network/wifi fix in ~1s,
  // block-level accuracy, no GPS battery hit (the admin venue-pin editor uses the
  // hook's high-accuracy default instead).
  const { status: geoStatus, request: requestLocation } = useGeolocation({ enableHighAccuracy: false });
  const { selectBinge } = useBinge();
  const navigate = useNavigate();

  // Monotonic token so out-of-order proximity responses (rapid radius changes /
  // double-clicks) can't overwrite a newer result. Only the latest request applies.
  const requestSeqRef = useRef(0);
  // Mirror of reviewSummaries for reads inside callbacks, so loadNearby doesn't have
  // to depend on the summaries state (which would recreate it after every fetch).
  const reviewSummariesRef = useRef({});

  const fetchSummaries = useCallback(async (list) => {
    const summaries = {};
    await Promise.allSettled(
      list.map(async (b) => {
        try {
          const r = await bookingService.getBingeReviewSummary(b.id);
          summaries[b.id] = r.data.data || r.data || {};
        } catch { summaries[b.id] = {}; }
      })
    );
    setReviewSummaries((prev) => {
      const next = { ...prev, ...summaries };
      reviewSummariesRef.current = next;
      return next;
    });
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const res = await bookingService.getAllActiveBinges();
        const bingeList = res.data.data || res.data || [];
        setBinges(bingeList);
        await fetchSummaries(bingeList);
      } catch (err) {
        toast.error('Failed to load venues');
      } finally {
        setLoading(false);
      }
    })();
  }, [fetchSummaries]);

  // How many of the loaded venues are actually geocoded — gates the location CTA so
  // we never invite a customer to "find venues near me" when none can be ranked.
  const geocodedCount = useMemo(
    () => binges.filter((b) => b.latitude != null && b.longitude != null).length,
    [binges]
  );

  const loadNearby = useCallback(async (lat, lng, radius) => {
    const seq = ++requestSeqRef.current;
    setNearbyLoading(true);
    try {
      const res = await bookingService.getNearbyBinges(lat, lng, { radiusKm: radius, limit: 50 });
      if (seq !== requestSeqRef.current) return; // a newer request superseded this one
      const list = res.data.data || res.data || [];
      setNearby(list);
      setMode('nearby');
      // Backfill summaries for any nearby venue we haven't fetched yet (defensive;
      // nearby ⊆ all, so these are usually already cached).
      const missing = list.filter((b) => !reviewSummariesRef.current[b.id]);
      if (missing.length) fetchSummaries(missing);
    } catch (err) {
      if (seq !== requestSeqRef.current) return; // stale failure — newer request owns the UI
      toast.error(err.userMessage || 'Could not load nearby venues. Showing all venues.');
      setMode('all');
    } finally {
      if (seq === requestSeqRef.current) setNearbyLoading(false);
    }
  }, [fetchSummaries]);

  const handleUseLocation = useCallback(async () => {
    try {
      const c = await requestLocation();
      setCoords(c);
      await loadNearby(c.latitude, c.longitude, radiusKm);
    } catch (err) {
      // useGeolocation rejects with a friendly message for denied/unavailable/timeout.
      if (err && err.message && !String(err.message).includes('in-flight')) {
        toast.info(err.message);
      }
    }
  }, [requestLocation, loadNearby, radiusKm]);

  const handleRadiusChange = useCallback((nextRadius) => {
    setRadiusKm(nextRadius);
    if (coords) loadNearby(coords.latitude, coords.longitude, nextRadius);
  }, [coords, loadNearby]);

  const showAllVenues = useCallback(() => {
    setMode('all');
    setSearch('');
  }, []);

  const handleSelect = (binge) => {
    // The store normalises to the canonical selected-binge shape.
    selectBinge(binge);
    toast.success(`Selected: ${binge.name}`);
    navigate('/dashboard');
  };

  // The active source list depends on mode; both are then text-filtered.
  const sourceList = mode === 'nearby' ? nearby : binges;
  const filteredBinges = useMemo(() => {
    if (!search.trim()) return sourceList;
    const q = search.trim().toLowerCase();
    return sourceList.filter(
      (b) => (b.name || '').toLowerCase().includes(q) || (b.address || '').toLowerCase().includes(q)
    );
  }, [sourceList, search]);

  const locating = geoStatus === 'prompting' || nearbyLoading;
  // Distinguish the two ways the grid can be empty so we don't tell a searching
  // customer "no venues within X km" when it was their search term that filtered
  // everything out. Only a genuinely empty proximity result is a radius problem.
  const nearbyEmptyByRadius = mode === 'nearby' && nearby.length === 0;

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
          {/* ── Location discovery bar ── */}
          <div className="venue-locate-bar">
            <div className="venue-locate-copy">
              <span className="venue-locate-icon"><FiNavigation /></span>
              <div>
                <strong>{mode === 'nearby' ? 'Showing venues near you' : 'Find venues near you'}</strong>
                <p>
                  {mode === 'nearby'
                    ? `Sorted by distance${coords ? ` within ${radiusKm} km` : ''}.`
                    : geocodedCount > 0
                      ? 'Use your current location to see the closest venues first.'
                      : 'Browse all available venues below.'}
                </p>
              </div>
            </div>
            <div className="venue-locate-actions">
              {mode === 'nearby' ? (
                <>
                  <label className="venue-radius-select">
                    <span>Radius</span>
                    <select
                      value={radiusKm}
                      onChange={(e) => handleRadiusChange(Number(e.target.value))}
                      disabled={locating}
                      aria-label="Search radius in kilometres"
                    >
                      {RADIUS_TIERS.map((r) => (
                        <option key={r} value={r}>{r} km</option>
                      ))}
                    </select>
                  </label>
                  <button type="button" className="btn btn-secondary btn-sm" onClick={showAllVenues} disabled={locating}>
                    Show all venues
                  </button>
                </>
              ) : (
                geocodedCount > 0 && (
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    onClick={handleUseLocation}
                    disabled={locating}
                  >
                    <FiCrosshair /> {locating ? 'Locating…' : 'Use my location'}
                  </button>
                )
              )}
            </div>
          </div>

          <div className="entrance-search" style={{ marginBottom: '1rem', position: 'relative' }}>
            <FiSearch style={{ position: 'absolute', left: '1rem', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)', pointerEvents: 'none' }} />
            <input
              type="text"
              placeholder="Search venues by name or address…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ width: '100%', padding: '0.75rem 1rem 0.75rem 2.6rem', border: '1px solid rgba(var(--primary-rgb), 0.12)', borderRadius: '14px', background: 'var(--bg-input)', color: 'var(--text)', fontSize: '0.92rem' }}
            />
          </div>

          {filteredBinges.length === 0 ? (
            <div className="customer-flow-card customer-flow-empty">
              <span className="customer-flow-icon">{nearbyEmptyByRadius ? <FiNavigation /> : <FiSearch />}</span>
              <h2>{nearbyEmptyByRadius ? `No venues within ${radiusKm} km` : 'No matching venues'}</h2>
              <p>
                {nearbyEmptyByRadius
                  ? 'Try widening the search radius, or browse every venue.'
                  : 'Try a different search term.'}
              </p>
              {(nearbyEmptyByRadius || mode === 'nearby') && (
                <div style={{ display: 'flex', gap: '0.6rem', justifyContent: 'center', flexWrap: 'wrap', marginTop: '0.5rem' }}>
                  {nearbyEmptyByRadius && radiusKm < RADIUS_TIERS[RADIUS_TIERS.length - 1] && (
                    <button
                      type="button"
                      className="btn btn-primary btn-sm"
                      onClick={() => handleRadiusChange(RADIUS_TIERS.find((r) => r > radiusKm) || radiusKm)}
                      disabled={locating}
                    >
                      Widen radius
                    </button>
                  )}
                  <button type="button" className="btn btn-secondary btn-sm" onClick={showAllVenues}>
                    Show all venues
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="customer-venue-grid">
              {/* The inner button is each card's accessible action (focusable,
                  named, native Enter/Space); the card onClick is a pointer
                  convenience that enlarges the hit target without nesting
                  interactive elements. */}
              {filteredBinges.map((binge) => (
                <article key={binge.id} className="customer-venue-card" onClick={() => handleSelect(binge)}>
                  <div className="customer-venue-card-copy">
                    <span className="customer-flow-kicker">Venue option</span>
                    <h3>{binge.name}</h3>
                    <p>{binge.address || 'Private-screening location ready for bookings.'}</p>
                    <div className="customer-venue-meta">
                      <StarRating
                        avg={reviewSummaries[binge.id]?.averageRating}
                        count={reviewSummaries[binge.id]?.totalReviews}
                      />
                      {binge.distanceKm != null && (
                        <span className="venue-distance-badge">
                          <FiNavigation aria-hidden="true" /> {formatDistance(binge.distanceKm)}
                        </span>
                      )}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    aria-label={`Select ${binge.name}`}
                    onClick={(e) => { e.stopPropagation(); handleSelect(binge); }}
                  >
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
