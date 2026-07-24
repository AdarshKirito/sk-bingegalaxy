import { create } from 'zustand';
import type { Binge } from '../types';

/**
 * Canonical selected-binge shape.
 *
 * Different entry points (customer venue selector, platform dashboard, admin
 * entrance, binge management) fetch venues with different levels of detail and
 * used to persist whatever subset they happened to have. Pages that later read
 * `selectedBinge` (support contacts, venue timezone, cancellation policy)
 * silently misbehaved depending on which door the user came in through.
 *
 * Normalising at the store's single entry point guarantees one shape
 * everywhere, and dropping unknown fields keeps localStorage from accumulating
 * whatever extra payload a list endpoint happens to return.
 */
export function normalizeSelectedBinge(binge: Partial<Binge> & { id: Binge['id'] }): Binge {
  return {
    id: binge.id,
    name: binge.name ?? '',
    address: binge.address,
    addressLine1: binge.addressLine1,
    addressLine2: binge.addressLine2,
    city: binge.city,
    state: binge.state,
    country: binge.country,
    postalCode: binge.postalCode,
    latitude: binge.latitude,
    longitude: binge.longitude,
    supportEmail: binge.supportEmail,
    supportPhone: binge.supportPhone,
    supportPhoneCountryCode: binge.supportPhoneCountryCode,
    supportWhatsapp: binge.supportWhatsapp,
    supportWhatsappCountryCode: binge.supportWhatsappCountryCode,
    supportPhoneIsWhatsapp: binge.supportPhoneIsWhatsapp,
    customerCancellationEnabled: binge.customerCancellationEnabled,
    customerCancellationCutoffMinutes: binge.customerCancellationCutoffMinutes,
    maxConcurrentBookings: binge.maxConcurrentBookings,
    // Venue-local time governs slot arithmetic; fall back to the platform's
    // home timezone rather than persisting "no timezone".
    timezone: binge.timezone || 'Asia/Kolkata',
    // Every price for this venue is denominated in ITS currency — pages must
    // format money with this, never a hardcoded symbol or the browser locale.
    currency: (binge.currency || 'INR').toUpperCase(),
  };
}

interface BingeState {
  selectedBinge: Binge | null;
  selectBinge: (binge: Partial<Binge> & { id: Binge['id'] }) => void;
  clearBinge: () => void;
}

const useBingeStore = create<BingeState>((set) => ({
  selectedBinge: (() => {
    try {
      const stored = localStorage.getItem('selectedBinge');
      return stored ? JSON.parse(stored) : null;
    } catch { return null; }
  })(),

  selectBinge: (binge) => {
    const normalized = normalizeSelectedBinge(binge);
    localStorage.setItem('selectedBinge', JSON.stringify(normalized));
    set({ selectedBinge: normalized });
    // SEC-009: a binge switch changes the tenant behind identical API URLs —
    // any runtime API cache left by an older service-worker build must go.
    try {
      if (typeof caches !== 'undefined') void caches.delete('api-cache');
    } catch { /* best-effort */ }
  },

  clearBinge: () => {
    localStorage.removeItem('selectedBinge');
    set({ selectedBinge: null });
    try {
      if (typeof caches !== 'undefined') void caches.delete('api-cache');
    } catch { /* best-effort */ }
  },
}));

export default useBingeStore;
