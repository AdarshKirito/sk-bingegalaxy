import { useState } from 'react';
import { City, State } from 'country-state-city';
import { FiCrosshair, FiMapPin } from 'react-icons/fi';
import useGeolocation from '../../hooks/useGeolocation';
import './FormFields.css';

/**
 * Venue geo-coordinate editor for the admin binge form.
 *
 * Coordinates feed the customer-facing "venues near me" proximity ranking, so we
 * make them easy to set accurately without forcing a paid geocoding integration:
 *   - "Use current location" reads the admin's device GPS (handy when filling the
 *     form on-site at the venue).
 *   - "Derive from city" reuses the offline country-state-city dataset already
 *     bundled for AddressFields to drop an approximate pin from the chosen city —
 *     good enough to rank venues by metro, refine by hand if needed.
 *   - Manual entry covers everything else (e.g. pasted Google Maps coordinates).
 *
 * `value`/`onChange` carry the pair as strings (`{ latitude, longitude }`) so the
 * inputs can be cleared; the parent parses to numbers (or null) at submit time.
 * Coordinates are all-or-nothing — set both or leave both blank.
 */
export default function VenueCoordinatesField({ value, onChange, address, disabled = false }) {
  const { status, request } = useGeolocation();
  const [error, setError] = useState('');

  const latitude = value?.latitude ?? '';
  const longitude = value?.longitude ?? '';
  const locating = status === 'prompting';

  const handleUseCurrent = async () => {
    setError('');
    try {
      const c = await request();
      onChange({ latitude: String(round6(c.latitude)), longitude: String(round6(c.longitude)) });
    } catch (e) {
      setError(e?.message || 'Could not read your current location.');
    }
  };

  const deriveFromCity = () => {
    setError('');
    const country = address?.country;
    const stateName = address?.state;
    const cityName = address?.city;
    if (!country || !cityName) {
      setError('Pick a country and city in the address above first, or enter coordinates manually.');
      return;
    }
    const states = State.getStatesOfCountry(country) || [];
    const stateIso = states.find((s) => s.name === stateName)?.isoCode;
    const cities = stateIso ? City.getCitiesOfState(country, stateIso) : [];
    const match = cities.find((c) => c.name === cityName);
    if (match && match.latitude && match.longitude) {
      onChange({ latitude: String(round6(Number(match.latitude))), longitude: String(round6(Number(match.longitude))) });
    } else {
      setError('No coordinates on file for that city. Use current location or enter them manually.');
    }
  };

  const canDerive = !!(address?.country && address?.city);
  const hasValue = latitude !== '' || longitude !== '';

  return (
    <fieldset className="address-fields" disabled={disabled}>
      <legend className="address-fields-legend">Map coordinates</legend>
      <p className="address-fields-help">
        Coordinates place this venue on the customer “venues near me” discovery. Set both, or leave both blank to keep this venue out of proximity results.
      </p>

      <div className="address-grid">
        <div className="input-group">
          <label>Latitude</label>
          <input
            type="number"
            step="any"
            min="-90"
            max="90"
            inputMode="decimal"
            value={latitude}
            onChange={(e) => onChange({ latitude: e.target.value, longitude })}
            placeholder="12.9716"
          />
        </div>
        <div className="input-group">
          <label>Longitude</label>
          <input
            type="number"
            step="any"
            min="-180"
            max="180"
            inputMode="decimal"
            value={longitude}
            onChange={(e) => onChange({ latitude, longitude: e.target.value })}
            placeholder="77.5946"
          />
        </div>
      </div>

      <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginTop: '0.6rem' }}>
        <button type="button" className="btn btn-secondary btn-sm" onClick={handleUseCurrent} disabled={disabled || locating}>
          <FiCrosshair /> {locating ? 'Locating…' : 'Use current location'}
        </button>
        <button type="button" className="btn btn-secondary btn-sm" onClick={deriveFromCity} disabled={disabled || !canDerive}>
          <FiMapPin /> Derive from city
        </button>
        {hasValue && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => onChange({ latitude: '', longitude: '' })} disabled={disabled}>
            Clear
          </button>
        )}
      </div>

      {error && <span className="field-error" style={{ marginTop: '0.5rem', display: 'block' }}>{error}</span>}
    </fieldset>
  );
}

/** Round to 6 decimals (~0.11 m) — plenty precise for a venue pin, keeps payload tidy. */
function round6(n) {
  return Math.round(Number(n) * 1e6) / 1e6;
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
