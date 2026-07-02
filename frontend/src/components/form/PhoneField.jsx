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
  defaultCountry = 'IN',
  label = 'Phone',
  required = false,
  disabled = false,
  error,
  placeholder = 'Phone number',
  autoFocus = false,
  id,
  helpText,
}) {
  const handleChange = (val) => {
    // The library passes `undefined` while the user is mid-edit; normalise to ''.
    onChange?.(val || '');
  };

  return (
    <div className={`input-group phone-field ${error ? 'has-error' : ''}`}>
      <label htmlFor={id}>{label}{required ? ' *' : ''}</label>
      <PhoneInput
        id={id}
        international
        countryCallingCodeEditable={false}
        defaultCountry={defaultCountry}
        value={value || ''}
        onChange={handleChange}
        onBlur={onBlur}
        disabled={disabled}
        placeholder={placeholder}
        autoFocus={autoFocus}
        autoComplete="tel"
        smartCaret
      />
      {helpText && !error && <span className="field-hint">{helpText}</span>}
      {error && <span className="field-error">{error}</span>}
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
