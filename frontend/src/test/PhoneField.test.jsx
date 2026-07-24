import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import PhoneField from '../components/form/PhoneField';

// react-phone-number-input renders a country <select> whose value is the selected
// ISO country ('' / undefined = no country = "International", no +91). In
// `international` mode a selected country also pre-fills the calling code into the
// tel input. Asserting on both nails down exactly what the venue admin sees.
function countrySelectValue(container) {
  const sel = container.querySelector('select.PhoneInputCountrySelect');
  return sel ? sel.value : '(no select)';
}
function telInputValue(container) {
  const input = container.querySelector('input.PhoneInputInput');
  return input ? input.value : '(no input)';
}

describe('PhoneField default country', () => {
  it('omitting defaultCountry keeps the existing India default (other forms rely on it)', () => {
    const { container } = render(<PhoneField value="" onChange={() => {}} />);
    expect(countrySelectValue(container)).toBe('IN');
  });

  it('defaultCountry=null does not pre-fill the India (+91) calling code', () => {
    const { container } = render(
      <PhoneField value="" onChange={() => {}} defaultCountry={null} />,
    );
    // What the user actually sees is the tel input's content. It must not carry
    // the +91 prefix that the old India default produced.
    const shown = `select=${countrySelectValue(container)} input="${telInputValue(container)}"`;
    expect(shown).not.toContain('+91');
    // Stronger: the tel input must be genuinely empty — not showing ANY calling
    // code (e.g. +93 for the alphabetically-first country). If this fails, the
    // failure message prints the actual value so we know what to handle.
    expect(telInputValue(container)).toBe('');
  });

  it('an explicit country is honoured (US venue → US, not India)', () => {
    const { container } = render(
      <PhoneField value="" onChange={() => {}} defaultCountry="US" />,
    );
    expect(countrySelectValue(container)).toBe('US');
  });
});
