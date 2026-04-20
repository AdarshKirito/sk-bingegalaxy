import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  getAccountPreferences,
  buildAccountPreferencesPayload,
  ACCOUNT_PREFERENCES_DEFAULTS,
  mergeSupportContact,
  CUSTOMER_SUPPORT,
  buildSupportEmailHref,
  buildSupportWhatsAppHref,
  getCallSupportHref,
  downloadBookingSummary,
  HELP_FAQS,
  MEMBER_OFFERS,
  EXPERIENCE_STEPS,
} from '../services/customerExperience';

describe('getAccountPreferences', () => {
  it('returns defaults when called with empty object', () => {
    const prefs = getAccountPreferences({});
    expect(prefs.preferredExperience).toBe(ACCOUNT_PREFERENCES_DEFAULTS.preferredExperience);
    expect(prefs.vibePreference).toBe(ACCOUNT_PREFERENCES_DEFAULTS.vibePreference);
    expect(prefs.reminderLeadDays).toBe('14');
    expect(prefs.notificationChannel).toBe('EMAIL');
    expect(prefs.receivesOffers).toBe(true);
  });

  it('returns defaults when called with no arguments', () => {
    const prefs = getAccountPreferences();
    expect(prefs.preferredExperience).toBe(ACCOUNT_PREFERENCES_DEFAULTS.preferredExperience);
  });

  it('merges user preferences over defaults', () => {
    const user = {
      preferredExperience: 'Anniversary',
      vibePreference: 'Romantic',
      reminderLeadDays: 7,
      birthdayMonth: 'March',
      birthdayDay: 15,
      notificationChannel: 'SMS',
      receivesOffers: false,
    };
    const prefs = getAccountPreferences(user);
    expect(prefs.preferredExperience).toBe('Anniversary');
    expect(prefs.vibePreference).toBe('Romantic');
    expect(prefs.reminderLeadDays).toBe('7');
    expect(prefs.birthdayMonth).toBe('March');
    expect(prefs.birthdayDay).toBe('15');
    expect(prefs.notificationChannel).toBe('SMS');
    expect(prefs.receivesOffers).toBe(false);
  });
});

describe('buildAccountPreferencesPayload', () => {
  it('converts string fields and numbers correctly', () => {
    const input = {
      preferredExperience: '  Birthday  ',
      vibePreference: '  Fun ',
      reminderLeadDays: '7',
      birthdayMonth: 'January',
      birthdayDay: '25',
      anniversaryMonth: '',
      anniversaryDay: '',
      notificationChannel: 'WHATSAPP',
      receivesOffers: true,
      weekendAlerts: false,
      conciergeSupport: true,
    };
    const payload = buildAccountPreferencesPayload(input);
    expect(payload.preferredExperience).toBe('Birthday');
    expect(payload.vibePreference).toBe('Fun');
    expect(payload.reminderLeadDays).toBe(7);
    expect(payload.birthdayMonth).toBe('January');
    expect(payload.birthdayDay).toBe(25);
    expect(payload.anniversaryMonth).toBe('');
    expect(payload.anniversaryDay).toBeNull();
    expect(payload.notificationChannel).toBe('WHATSAPP');
    expect(payload.receivesOffers).toBe(true);
    expect(payload.weekendAlerts).toBe(false);
    expect(payload.conciergeSupport).toBe(true);
  });

  it('uses default reminder lead days when empty', () => {
    const payload = buildAccountPreferencesPayload({ reminderLeadDays: '' });
    expect(payload.reminderLeadDays).toBe(14);
  });
});

describe('mergeSupportContact', () => {
  it('returns defaults when called with null', () => {
    const result = mergeSupportContact(null);
    expect(result).toEqual(CUSTOMER_SUPPORT);
  });

  it('merges provided contact over defaults', () => {
    const result = mergeSupportContact({ email: 'help@test.com', phoneRaw: '+1234567890' });
    expect(result.email).toBe('help@test.com');
    expect(result.phoneRaw).toBe('+1234567890');
    expect(result.hours).toBe(CUSTOMER_SUPPORT.hours);
  });
});

describe('buildSupportEmailHref', () => {
  it('returns empty string when no email configured', () => {
    expect(buildSupportEmailHref()).toBe('');
  });

  it('builds mailto href with booking ref', () => {
    const href = buildSupportEmailHref({
      supportContact: { email: 'help@test.com' },
      bookingRef: 'BK-001',
      customerName: 'John',
    });
    expect(href).toContain('mailto:help@test.com');
    expect(href).toContain('BK-001');
  });

  it('builds mailto href without booking ref', () => {
    const href = buildSupportEmailHref({
      supportContact: { email: 'help@test.com' },
    });
    expect(href).toContain('mailto:help@test.com');
    expect(href).toContain('not%20yet%20available');
  });
});

describe('buildSupportWhatsAppHref', () => {
  it('returns empty string when no whatsapp number', () => {
    expect(buildSupportWhatsAppHref()).toBe('');
  });

  it('builds wa.me link', () => {
    const href = buildSupportWhatsAppHref({
      supportContact: { whatsappRaw: '919876543210' },
      bookingRef: 'BK-002',
    });
    expect(href).toContain('wa.me/919876543210');
    expect(href).toContain('BK-002');
  });
});

describe('getCallSupportHref', () => {
  it('returns empty string when no phone', () => {
    expect(getCallSupportHref(null)).toBe('');
  });

  it('returns tel: link when phone is provided', () => {
    const href = getCallSupportHref({ phoneRaw: '+919876543210' });
    expect(href).toBe('tel:+919876543210');
  });
});

describe('downloadBookingSummary', () => {
  it('does nothing when booking is null', () => {
    downloadBookingSummary(null);
    // Should not throw
  });

  it('creates and clicks a download link', () => {
    const clickSpy = vi.fn();
    const createElementOrig = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const el = createElementOrig(tag);
      if (tag === 'a') {
        el.click = clickSpy;
      }
      return el;
    });

    downloadBookingSummary({
      bookingRef: 'BK-TEST-001',
      eventType: 'Birthday',
      bookingDate: '2025-01-15',
      startTime: '14:00',
      status: 'CONFIRMED',
      paymentStatus: 'SUCCESS',
      totalAmount: 5000,
      numberOfGuests: 10,
      addOns: [{ name: 'DJ', quantity: 1 }],
      specialNotes: 'Surprise party',
    });

    expect(clickSpy).toHaveBeenCalled();
    document.createElement.mockRestore();
  });
});

describe('constants are defined', () => {
  it('HELP_FAQS is a non-empty array', () => {
    expect(Array.isArray(HELP_FAQS)).toBe(true);
    expect(HELP_FAQS.length).toBeGreaterThan(0);
    expect(HELP_FAQS[0]).toHaveProperty('question');
    expect(HELP_FAQS[0]).toHaveProperty('answer');
  });

  it('MEMBER_OFFERS is a non-empty array', () => {
    expect(Array.isArray(MEMBER_OFFERS)).toBe(true);
    expect(MEMBER_OFFERS.length).toBeGreaterThan(0);
    expect(MEMBER_OFFERS[0]).toHaveProperty('title');
    expect(MEMBER_OFFERS[0]).toHaveProperty('description');
  });

  it('EXPERIENCE_STEPS is a non-empty array of strings', () => {
    expect(Array.isArray(EXPERIENCE_STEPS)).toBe(true);
    expect(EXPERIENCE_STEPS.length).toBeGreaterThan(0);
    EXPERIENCE_STEPS.forEach(s => expect(typeof s).toBe('string'));
  });
});
