import { useCurrency } from '../context/CurrencyContext';
import './CurrencySwitcher.css';

/**
 * Global currency picker. Renders as a compact <select> styled to match the
 * existing top-bar controls. Reads/writes via {@link useCurrency}.
 */
export default function CurrencySwitcher({ compact = false, ariaLabel = 'Currency' }) {
  const { currencies, selectedCode, setSelectedCode, loading } = useCurrency();

  if (loading && (!currencies || currencies.length <= 1)) {
    return null;
  }

  return (
    <select
      className={`currency-switcher${compact ? ' currency-switcher-compact' : ''}`}
      value={selectedCode}
      onChange={(e) => setSelectedCode(e.target.value)}
      aria-label={ariaLabel}
      title={ariaLabel}
    >
      {currencies.map((c) => (
        <option key={c.code} value={c.code}>
          {c.symbol} {c.code}
        </option>
      ))}
    </select>
  );
}
