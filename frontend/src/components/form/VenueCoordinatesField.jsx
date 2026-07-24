import { useEffect, useRef, useState } from 'react';
import { City, State } from 'country-state-city';
import { FiCrosshair, FiMapPin, FiSliders, FiCheckCircle } from 'react-icons/fi';
import useGeolocation from '../../hooks/useGeolocation';
import './FormFields.css';

/**
 * Venue location editor for the admin binge form — ADDRESS-FIRST.
 *
 * Admins fill the structured address (above this field); this widget turns that
 * address into the map pin that powers the customer "venues near me" proximity
 * ranking, so nobody has to hand-type latitude/longitude. Resolution order:
 *   1. Auto-derive from the chosen city as soon as a city is picked (offline
 *      country-state-city dataset — no paid geocoding API needed).
 *   2. "Use current location" reads device GPS (handy when filling on-site).
 *   3. "Enter coordinates manually" (collapsed by default) for edge cases such
 *      as pasted Google-Maps coordinates or a city missing from the dataset.
 *
 * `value`/`onChange` carry the pair as strings (`{ latitude, longitude }`) so the
 * inputs can be cleared; the parent parses to numbers (or null) at submit time.
 * Coordinates are all-or-nothing — set both or leave both blank.
 */
export default function VenueCoordinatesField({ value, onChange, address, disabled = false }) {
  const { status, request } = useGeolocation();
  const [error, setError] = useState('');
  const [showManual, setShowManual] = useState(false);
  // Remember the last city we auto-derived for, so we re-derive when the admin
  // changes the city but never clobber a value they set by hand or via GPS.
  const lastAutoCity = useRef(null);

  const latitude = value?.latitude ?? '';
  const longitude = value?.longitude ?? '';
  const locating = status === 'prompting';
  const hasValue = String(latitude).trim() !== '' && String(longitude).trim() !== '';

  const cityKey = `${address?.country || ''}|${address?.state || ''}|${address?.city || ''}`;

  // Auto-derive coordinates from the address city. Runs only when the pin is
  // currently empty (so a manual/GPS pin is never overwritten) and the city
  // actually changed since the last auto-derive.
  useEffect(() => {
    if (disabled) return;
    if (!address?.city) return;
    if (hasValue) { lastAutoCity.current = cityKey; return; }
    if (lastAutoCity.current === cityKey) return;
    const coords = deriveCityCoordinates(address);
    if (coords) {
      lastAutoCity.current = cityKey;
      onChange({ latitude: String(coords.latitude), longitude: String(coords.longitude) });
      setError('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cityKey, disabled]);

  const handleUseCurrent = async () => {
    setError('');
    try {
      const c = await request();
      onChange({ latitude: String(round6(c.latitude)), longitude: String(round6(c.longitude)) });
    } catch (e) {
      setError(e?.message || 'Could not read your current location.');
    }
  };

  const handleDeriveFromCity = () => {
    setError('');
    if (!address?.country || !address?.city) {
      setError('Pick a country and city in the address above first, or enter coordinates manually.');
      return;
    }
    const coords = deriveCityCoordinates(address);
    if (coords) {
      lastAutoCity.current = cityKey;
      onChange({ latitude: String(coords.latitude), longitude: String(coords.longitude) });
    } else {
      setError('No coordinates on file for that city. Use current location or enter them manually.');
      setShowManual(true);
    }
  };

  const canDerive = !!(address?.country && address?.city);

  return (
    <fieldset className="address-fields" disabled={disabled}>
      <legend className="address-fields-legend">Venue location on the map</legend>
      <p className="address-fields-help">
        This drops the pin used by the customer “venues near me” discovery. It is set
        automatically from the city in the address above — no need to type coordinates.
      </p>

      <div className="venue-loc-status">
        {hasValue ? (
          <span className="venue-loc-set">
            <FiCheckCircle aria-hidden="true" />
            Pinned{address?.city ? ` near ${address.city}` : ''} · {Number(latitude).toFixed(4)}, {Number(longitude).toFixed(4)}
          </span>
        ) : (
          <span className="venue-loc-unset">
            <FiMapPin aria-hidden="true" />
            No map location yet — pick a city above, use your current location, or enter it manually.
          </span>
        )}
      </div>

      <div className="venue-loc-actions">
        <button type="button" className="btn btn-secondary btn-sm" onClick={handleUseCurrent} disabled={disabled || locating}>
          <FiCrosshair /> {locating ? 'Locating…' : 'Use current location'}
        </button>
        <button type="button" className="btn btn-secondary btn-sm" onClick={handleDeriveFromCity} disabled={disabled || !canDerive}>
          <FiMapPin /> Derive from city
        </button>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => setShowManual((s) => !s)} disabled={disabled}>
          <FiSliders /> {showManual ? 'Hide manual entry' : 'Enter coordinates manually'}
        </button>
        {hasValue && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => { onChange({ latitude: '', longitude: '' }); lastAutoCity.current = null; }} disabled={disabled}>
            Clear pin
          </button>
        )}
      </div>

      {showManual && (
        <div className="address-grid" style={{ marginTop: '0.6rem' }}>
          <div className="input-group">
            <label>Latitude</label>
            <input
              type="number" step="any" min="-90" max="90" inputMode="decimal"
              value={latitude}
              onChange={(e) => onChange({ latitude: e.target.value, longitude })}
              placeholder="12.9716"
            />
          </div>
          <div className="input-group">
            <label>Longitude</label>
            <input
              type="number" step="any" min="-180" max="180" inputMode="decimal"
              value={longitude}
              onChange={(e) => onChange({ latitude, longitude: e.target.value })}
              placeholder="77.5946"
            />
          </div>
        </div>
      )}

      {error && <span className="field-error" style={{ marginTop: '0.5rem', display: 'block' }}>{error}</span>}
    </fieldset>
  );
}

/** Round to 6 decimals (~0.11 m) — plenty precise for a venue pin, keeps payload tidy. */
function round6(n) {
  return Math.round(Number(n) * 1e6) / 1e6;
}

/**
 * Resolve approximate coordinates for the address's city from the offline
 * country-state-city dataset. Robust matching, in order:
 *   1. state-scoped cities (when the state resolves to an ISO code), exact then
 *      case-insensitive name match;
 *   2. country-wide cities, exact then case-insensitive name match (covers
 *      cities picked without a matching state, or free-typed states).
 * Returns `{ latitude, longitude }` (rounded) or `null` when nothing is found or
 * the matched record has no coordinates.
 */
export function deriveCityCoordinates(address) {
  const country = address?.country;
  const stateName = address?.state;
  const cityName = address?.city;
  if (!country || !cityName) return null;

  const target = String(cityName).trim().toLowerCase();
  const pick = (list) => {
    if (!list || !list.length) return null;
    const exact = list.find((c) => c.name === cityName)
      || list.find((c) => String(c.name).trim().toLowerCase() === target);
    return exact && exact.latitude && exact.longitude ? exact : null;
  };

  // 1) state-scoped
  let match = null;
  if (stateName) {
    const states = State.getStatesOfCountry(country) || [];
    const st = states.find((s) => s.name === stateName)
      || states.find((s) => String(s.name).trim().toLowerCase() === String(stateName).trim().toLowerCase());
    if (st?.isoCode) {
      match = pick(City.getCitiesOfState(country, st.isoCode));
    }
  }
  // 2) country-wide fallback
  if (!match) {
    match = pick(City.getCitiesOfCountry(country));
  }
  if (!match) return null;
  return { latitude: round6(Number(match.latitude)), longitude: round6(Number(match.longitude)) };
}

/**
 * Validate the coordinate pair the same way the backend does. Returns a message
 * string when invalid, or '' when valid. Empty (both blank) is valid.
 */
export function validateCoordinates(value) {
  const latStr = value?.latitude ?? '';
  const lngStr = value?.longitude ?? '';
  const latBlank = String(latStr).trim() === '';
  const lngBlank = String(lngStr).trim() === '';
  if (latBlank && lngBlank) return '';
  if (latBlank !== lngBlank) return 'Set both latitude and longitude, or clear both.';
  const lat = Number(latStr);
  const lng = Number(lngStr);
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) return 'Latitude must be between -90 and 90.';
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) return 'Longitude must be between -180 and 180.';
  return '';
}
