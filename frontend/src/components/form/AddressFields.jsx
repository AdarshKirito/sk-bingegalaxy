import { useMemo } from 'react';
import { Country, State, City } from 'country-state-city';
import './FormFields.css';

/**
 * Production-grade structured address form widget.
 * Cascading pickers: Country -> State -> City; postal code + lines are free text.
 *
 * The shape of `value`/`onChange` matches the backend `AddressPayload`:
 *   { addressLine1, addressLine2, city, state, country, postalCode }
 *
 * `country` is stored as ISO-3166-1 alpha-2 (e.g. "IN") to match the
 * server validation regex.
 *
 * All fields are optional. Caller decides whether the address is required
 * before submit; we render only validation messages passed via `errors`.
 */
export default function AddressFields({
  value,
  onChange,
  errors = {},
  disabled = false,
  required = false,
  legend = 'Address',
  description = 'All fields optional. Pick country first to unlock states and cities.',
}) {
  const countries = useMemo(() => Country.getAllCountries(), []);
  const states = useMemo(
    () => (value?.country ? State.getStatesOfCountry(value.country) : []),
    [value?.country]
  );
  const cities = useMemo(
    () =>
      value?.country && value?.state
        ? City.getCitiesOfState(value.country, getStateIsoCode(value.state, states))
        : [],
    [value?.country, value?.state, states]
  );

  const update = (patch) => onChange?.({ ...(value || {}), ...patch });

  const handleCountryChange = (e) => {
    const country = e.target.value || '';
    update({ country, state: '', city: '' });
  };

  const handleStateChange = (e) => {
    update({ state: e.target.value || '', city: '' });
  };

  const handleCityChange = (e) => {
    update({ city: e.target.value || '' });
  };

  const star = required ? ' *' : '';

  return (
    <fieldset className="address-fields" disabled={disabled}>
      <legend className="address-fields-legend">{legend}</legend>
      {description && <p className="address-fields-help">{description}</p>}

      <div className="address-grid">
        <div className={`input-group ${errors.addressLine1 ? 'has-error' : ''}`}>
          <label>Street / Address line 1{star}</label>
          <input
            type="text"
            maxLength={200}
            autoComplete="address-line1"
            value={value?.addressLine1 || ''}
            onChange={(e) => update({ addressLine1: e.target.value })}
            placeholder="123 Galaxy Road"
          />
          {errors.addressLine1 && <span className="field-error">{errors.addressLine1}</span>}
        </div>

        <div className={`input-group ${errors.addressLine2 ? 'has-error' : ''}`}>
          <label>Address line 2</label>
          <input
            type="text"
            maxLength={200}
            autoComplete="address-line2"
            value={value?.addressLine2 || ''}
            onChange={(e) => update({ addressLine2: e.target.value })}
            placeholder="Apt, suite, landmark (optional)"
          />
          {errors.addressLine2 && <span className="field-error">{errors.addressLine2}</span>}
        </div>

        <div className={`input-group ${errors.country ? 'has-error' : ''}`}>
          <label>Country{star}</label>
          <select
            autoComplete="country"
            value={value?.country || ''}
            onChange={handleCountryChange}
          >
            <option value="">Select country</option>
            {countries.map((c) => (
              <option key={c.isoCode} value={c.isoCode}>
                {c.flag ? `${c.flag} ` : ''}{c.name}
              </option>
            ))}
          </select>
          {errors.country && <span className="field-error">{errors.country}</span>}
        </div>

        <div className={`input-group ${errors.state ? 'has-error' : ''}`}>
          <label>State / Region{star}</label>
          {states.length > 0 ? (
            <select
              autoComplete="address-level1"
              value={value?.state || ''}
              onChange={handleStateChange}
              disabled={!value?.country}
            >
              <option value="">{value?.country ? 'Select state' : 'Pick a country first'}</option>
              {states.map((s) => (
                <option key={s.isoCode} value={s.name}>{s.name}</option>
              ))}
            </select>
          ) : (
            <input
              type="text"
              maxLength={100}
              autoComplete="address-level1"
              value={value?.state || ''}
              onChange={(e) => update({ state: e.target.value })}
              placeholder={value?.country ? 'State / region' : 'Pick a country first'}
              disabled={!value?.country}
            />
          )}
          {errors.state && <span className="field-error">{errors.state}</span>}
        </div>

        <div className={`input-group ${errors.city ? 'has-error' : ''}`}>
          <label>City{star}</label>
          {cities.length > 0 ? (
            <select
              autoComplete="address-level2"
              value={value?.city || ''}
              onChange={handleCityChange}
              disabled={!value?.state}
            >
              <option value="">{value?.state ? 'Select city' : 'Pick a state first'}</option>
              {cities.map((c) => (
                <option key={`${c.name}-${c.latitude || ''}-${c.longitude || ''}`} value={c.name}>
                  {c.name}
                </option>
              ))}
            </select>
          ) : (
            <input
              type="text"
              maxLength={100}
              autoComplete="address-level2"
              value={value?.city || ''}
              onChange={(e) => update({ city: e.target.value })}
              placeholder={value?.state ? 'City' : 'Pick a state first'}
              disabled={!value?.state}
            />
          )}
          {errors.city && <span className="field-error">{errors.city}</span>}
        </div>

        <div className={`input-group ${errors.postalCode ? 'has-error' : ''}`}>
          <label>Postal / ZIP code{star}</label>
          <input
            type="text"
            maxLength={20}
            autoComplete="postal-code"
            inputMode="text"
            value={value?.postalCode || ''}
            onChange={(e) => update({ postalCode: e.target.value.toUpperCase() })}
            placeholder="560001"
          />
          {errors.postalCode && <span className="field-error">{errors.postalCode}</span>}
        </div>
      </div>
    </fieldset>
  );
}

/** Resolve the chosen state-name back to the ISO code that the cities lookup expects. */
function getStateIsoCode(stateName, states) {
  if (!stateName) return '';
  const match = states.find((s) => s.name === stateName);
  return match ? match.isoCode : stateName;
}

export const EMPTY_ADDRESS = Object.freeze({
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  country: '',
  postalCode: '',
});

/**
 * Validate an address payload using the same rules as the backend.
 * Returns an object whose keys are field names and whose values are
 * human-readable error messages. Empty object => valid.
 *
 * Pass `required: true` to additionally enforce non-empty country/city/postal
 * (line1 and state remain optional even when required, to keep parity with
 * customer-facing flows where some users only provide partial postal info).
 */
export function validateAddress(value, { required = false } = {}) {
  const errors = {};
  const v = value || {};
  if (required) {
    if (!v.addressLine1?.trim()) errors.addressLine1 = 'Street is required';
    if (!v.city?.trim()) errors.city = 'City is required';
    if (!v.country?.trim()) errors.country = 'Country is required';
    if (!v.postalCode?.trim()) errors.postalCode = 'Postal code is required';
  }
  if (v.country && !/^[A-Z]{2}$/.test(v.country)) {
    errors.country = 'Country must be an ISO-3166-1 alpha-2 code';
  }
  if (v.postalCode && !/^[A-Za-z0-9 \-]{3,20}$/.test(v.postalCode)) {
    errors.postalCode = 'Postal code must be 3-20 alphanumeric characters';
  }
  if (v.addressLine1 && v.addressLine1.length > 200) errors.addressLine1 = 'Max 200 characters';
  if (v.addressLine2 && v.addressLine2.length > 200) errors.addressLine2 = 'Max 200 characters';
  if (v.city && v.city.length > 100) errors.city = 'Max 100 characters';
  if (v.state && v.state.length > 100) errors.state = 'Max 100 characters';
  return errors;
}
