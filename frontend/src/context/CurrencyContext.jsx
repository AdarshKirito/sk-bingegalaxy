import { createContext, useContext, useEffect, useMemo, useState, useCallback } from 'react';
import { currencyService } from '../services/endpoints';

/**
 * Global currency state for the SPA.
 *
 * Shape of `currencies` items (matches CurrencyRateDto):
 *   { code, name, symbol, rateToBase, decimalDigits, active, base, manualOverride }
 *
 * `rateToBase` means: 1 INR == rateToBase * 1 unit-of-this-currency.
 * Display conversion:   display = inrAmount * rateToBase
 * Charge conversion:    inr     = displayAmount / rateToBase
 *
 * The selected code is persisted in localStorage so it survives reloads,
 * and a sensible default is auto-detected from the browser locale on first visit.
 */

const STORAGE_KEY = 'skbg.currency.code';
const BASE_CODE = 'INR';
const FALLBACK_CURRENCIES = [
  { code: 'INR', name: 'Indian Rupee', symbol: '₹', rateToBase: 1, decimalDigits: 2, active: true, base: true },
];

// Map common locales / regions to a sensible default currency. Only used on
// the first visit before the user has explicitly chosen.
const REGION_TO_CCY = {
  IN: 'INR', US: 'USD', GB: 'GBP', AE: 'AED', SG: 'SGD', AU: 'AUD',
  CA: 'CAD', JP: 'JPY', CN: 'CNY', CH: 'CHF', SA: 'SAR',
  // Eurozone
  DE: 'EUR', FR: 'EUR', IT: 'EUR', ES: 'EUR', NL: 'EUR', IE: 'EUR',
  PT: 'EUR', BE: 'EUR', AT: 'EUR', GR: 'EUR', FI: 'EUR',
};

function detectDefaultCurrency() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) return stored;
    const lang = (navigator.language || 'en-IN').toUpperCase();
    const region = lang.split('-')[1] || lang.split('_')[1] || 'IN';
    return REGION_TO_CCY[region] || BASE_CODE;
  } catch {
    return BASE_CODE;
  }
}

const CurrencyContext = createContext({
  currencies: FALLBACK_CURRENCIES,
  selectedCode: BASE_CODE,
  setSelectedCode: () => {},
  selected: FALLBACK_CURRENCIES[0],
  baseCode: BASE_CODE,
  loading: false,
  refresh: () => Promise.resolve(),
  convertFromBase: (v) => v,
  convertToBase: (v) => v,
  formatMoney: (v) => `₹${Number(v || 0).toLocaleString()}`,
});

export function CurrencyProvider({ children }) {
  const [currencies, setCurrencies] = useState(FALLBACK_CURRENCIES);
  const [selectedCode, setSelectedCodeRaw] = useState(detectDefaultCurrency);
  const [loading, setLoading] = useState(true);

  const setSelectedCode = useCallback((code) => {
    if (!code) return;
    const upper = String(code).toUpperCase();
    setSelectedCodeRaw(upper);
    try { localStorage.setItem(STORAGE_KEY, upper); } catch { /* noop */ }
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const res = await currencyService.listActive();
      const list = Array.isArray(res?.data?.data) ? res.data.data : [];
      if (list.length > 0) setCurrencies(list);
    } catch (e) {
      // Network errors during initial load — keep fallback so SPA never breaks.
      // The UI still renders prices in INR (base) until rates are fetched.
       
      console.warn('[currency] Failed to load FX rates — falling back to INR', e?.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  // If the saved currency is no longer active server-side, fall back to base.
  useEffect(() => {
    if (currencies.length === 0) return;
    const found = currencies.find((c) => c.code === selectedCode);
    if (!found) setSelectedCodeRaw(BASE_CODE);
  }, [currencies, selectedCode]);

  const selected = useMemo(
    () => currencies.find((c) => c.code === selectedCode) || currencies.find((c) => c.code === BASE_CODE) || FALLBACK_CURRENCIES[0],
    [currencies, selectedCode]
  );

  const convertFromBase = useCallback((inrAmount) => {
    const n = Number(inrAmount);
    if (!Number.isFinite(n)) return 0;
    if (!selected || selected.code === BASE_CODE) return n;
    const rate = Number(selected.rateToBase) || 1;
    return n * rate;
  }, [selected]);

  const convertToBase = useCallback((displayAmount) => {
    const n = Number(displayAmount);
    if (!Number.isFinite(n)) return 0;
    if (!selected || selected.code === BASE_CODE) return n;
    const rate = Number(selected.rateToBase) || 1;
    return rate === 0 ? n : n / rate;
  }, [selected]);

  const formatMoney = useCallback((inrAmount, opts = {}) => {
    const { showCode = false, signed = false, withSymbol = true } = opts;
    const display = convertFromBase(inrAmount);
    const digits = selected?.decimalDigits ?? 2;
    let formatted;
    try {
      formatted = display.toLocaleString(undefined, {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      });
    } catch {
      formatted = display.toFixed(digits);
    }
    const symbol = withSymbol ? (selected?.symbol || '') : '';
    const code = showCode ? ` ${selected?.code || ''}` : '';
    const sign = signed && display > 0 ? '+' : '';
    return `${sign}${symbol}${formatted}${code}`.trim();
  }, [convertFromBase, selected]);

  const value = useMemo(() => ({
    currencies,
    selectedCode: selected?.code || BASE_CODE,
    setSelectedCode,
    selected,
    baseCode: BASE_CODE,
    loading,
    refresh,
    convertFromBase,
    convertToBase,
    formatMoney,
  }), [currencies, selected, setSelectedCode, loading, refresh, convertFromBase, convertToBase, formatMoney]);

  return <CurrencyContext.Provider value={value}>{children}</CurrencyContext.Provider>;
}

export function useCurrency() {
  return useContext(CurrencyContext);
}

/** Convenience hook: returns just the formatter so components don't pull the whole ctx. */
export function useFormatMoney() {
  return useContext(CurrencyContext).formatMoney;
}
