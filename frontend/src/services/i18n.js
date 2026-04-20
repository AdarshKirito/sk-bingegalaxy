import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from '../locales/en.json';
import hi from '../locales/hi.json';
import te from '../locales/te.json';
import ta from '../locales/ta.json';

const savedLang = localStorage.getItem('lang') || 'en';

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, hi: { translation: hi }, te: { translation: te }, ta: { translation: ta } },
  lng: savedLang,
  fallbackLng: 'en',
  interpolation: { escapeValue: true },
});

/* ── Intl formatting helpers (locale-aware) ────────────── */

const LOCALE_MAP = { en: 'en-IN', hi: 'hi-IN', te: 'te-IN', ta: 'ta-IN' };

/** Get the effective BCP 47 locale tag */
function getLocale() {
  return LOCALE_MAP[i18n.language] || 'en-IN';
}

/** Format currency in INR with the active locale */
export function formatCurrency(amount) {
  return new Intl.NumberFormat(getLocale(), {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(amount ?? 0);
}

/** Format a Date or ISO string as a readable date */
export function formatDate(date, opts = {}) {
  const d = typeof date === 'string' ? new Date(date) : date;
  if (!d || isNaN(d)) return '';
  return new Intl.DateTimeFormat(getLocale(), {
    year: 'numeric', month: 'long', day: 'numeric',
    ...opts,
  }).format(d);
}

/** Format a Date or ISO string as a readable time */
export function formatTime(date, opts = {}) {
  const d = typeof date === 'string' ? new Date(date) : date;
  if (!d || isNaN(d)) return '';
  return new Intl.DateTimeFormat(getLocale(), {
    hour: '2-digit', minute: '2-digit', hour12: true,
    ...opts,
  }).format(d);
}

/** Format a number with locale-aware separators */
export function formatNumber(num, opts = {}) {
  return new Intl.NumberFormat(getLocale(), opts).format(num ?? 0);
}

export default i18n;
