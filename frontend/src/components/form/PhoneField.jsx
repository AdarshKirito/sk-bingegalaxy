import PhoneInput, {
  isValidPhoneNumber,
  parsePhoneNumber,
  getCountryCallingCode,
} from 'react-phone-number-input';
import 'react-phone-number-input/style.css';
import './FormFields.css';

/**
 * Production-grade phone input with country picker.
 *
 * Wraps `react-phone-number-input` and exposes a controlled value of the
 * full E.164 string (e.g. "+919876543210"). Internal helpers split this back
 * into the pieces the backend expects on submit:
 *   - `phoneCountryCode`: "+91"
 *   - `phone`: national subscriber digits ("9876543210")
 *
 * Pass `defaultCountry="IN"` to bias the picker for first-time users.
 */
export default function PhoneField({
  value,
  onChange,
  onBlur,
  // No default value on purpose. A JS default parameter would also fire when a
  // caller passes `undefined`, which is exactly how "no country chosen yet" would
  // arrive — and it would silently snap the picker back to India (+91). Instead:
  //   - prop omitted        → undefined → falls back to 'IN' below (existing forms)
  //   - prop = 'US' / 'GB'  → that country
  //   - prop = null / ''    → NO default country (empty picker, no +91) — used by
  //                           the venue form until a country is selected
  defaultCountry,
  label = 'Phone',
  required = false,
  disabled = false,
  error,
  placeholder = 'Phone number',
  autoFocus = false,
  id,
  helpText,
}) {
  const resolvedDefaultCountry =
    defaultCountry === undefined ? 'IN' : (defaultCountry || undefined);

  const handleChange = (val) => {
    // The library passes `undefined` while the user is mid-edit; normalise to ''.
    onChange?.(val || '');
  };

  // Stable ids so error/hint text is programmatically associated with the
  // input (aria-describedby) and screen readers announce the invalid state.
  const fieldId = id || 'phone-field';
  const errorId = `${fieldId}-error`;
  const hintId = `${fieldId}-hint`;

  return (
    <div className={`input-group phone-field ${error ? 'has-error' : ''}`}>
      <label htmlFor={fieldId}>{label}{required ? ' *' : ''}</label>
      <PhoneInput
        id={fieldId}
        international
        countryCallingCodeEditable={false}
        defaultCountry={resolvedDefaultCountry}
        value={value || ''}
        onChange={handleChange}
        onBlur={onBlur}
        disabled={disabled}
        placeholder={placeholder}
        autoFocus={autoFocus}
        autoComplete="tel"
        smartCaret
        aria-required={required || undefined}
        aria-invalid={!!error}
        aria-describedby={error ? errorId : (helpText ? hintId : undefined)}
      />
      {helpText && !error && <span className="field-hint" id={hintId}>{helpText}</span>}
      {error && <span className="field-error" id={errorId} role="alert">{error}</span>}
    </div>
  );
}

/**
 * Split an E.164 phone string into the `phoneCountryCode` and `phone` pair
 * the backend DTOs expect. Returns nulls when the value is unparseable.
 */
export function splitPhone(e164Value) {
  if (!e164Value) return { phoneCountryCode: '', phone: '' };
  try {
    const parsed = parsePhoneNumber(e164Value);
    if (!parsed) return { phoneCountryCode: '', phone: '' };
    return {
      phoneCountryCode: '+' + getCountryCallingCode(parsed.country || 'IN'),
      phone: parsed.nationalNumber || '',
    };
  } catch {
    return { phoneCountryCode: '', phone: '' };
  }
}

/** Combine a backend-shaped `{phoneCountryCode, phone}` pair into the E.164 string the input expects. */
export function joinPhone(phoneCountryCode, phone) {
  if (!phoneCountryCode || !phone) return '';
  return `${phoneCountryCode}${phone}`;
}

/**
 * Returns a human-readable error message when the value is not a valid
 * international number, or empty string when valid (or when value is empty
 * and `required` is false).
 */
export function validatePhone(value, { required = false } = {}) {
  if (!value) return required ? 'Phone is required' : '';
  if (!isValidPhoneNumber(value)) return 'Enter a valid phone number for the selected country';
  return '';
}
