import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import {
  captureAttribution,
  getAttribution,
  attributionPayload,
  clearAttribution,
  ATTRIBUTION_WINDOW_DAYS,
} from '../utils/attribution';

describe('attribution capture (distribution G-B)', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    // Required, not defensive: `vi.spyOn(Date, 'now')` in the window tests survives
    // useRealTimers, and a leaked clock makes a LATER test's fresh capture look 30 days
    // old. That failure points at the payload builder, which is not where the bug is.
    vi.restoreAllMocks();
  });

  it('captures utm_source from the landing URL', () => {
    const a = captureAttribution('?utm_source=Google&utm_medium=ttd&sk_click=abc123');
    expect(a.source).toBe('google');
    expect(a.ref).toBe('abc123');
  });

  it('canonicalises the source to lowercase, matching the DB CHECK', () => {
    // ck_booking_attribution_source_canonical rejects mixed case outright rather than
    // lower()-ing at read time, so the client must not send it.
    expect(captureAttribution('?utm_source=  GOOGLE_Things_To_Do ').source)
      .toBe('google_things_to_do');
  });

  it('a direct visit does NOT overwrite an earlier referral', () => {
    captureAttribution('?utm_source=google&sk_click=abc');
    // Last NON-DIRECT touch wins: typing the URL to come back is not a competing claim.
    const after = captureAttribution('?somethingElse=1');
    expect(after.source).toBe('google');
  });

  it('a different campaign DOES overwrite the previous one', () => {
    captureAttribution('?utm_source=google');
    expect(captureAttribution('?utm_source=viator').source).toBe('viator');
  });

  it('records an unrecognised source verbatim', () => {
    // Discarding unknown sources would throw away the first data about a new channel.
    expect(captureAttribution('?utm_source=brand_new_channel').source)
      .toBe('brand_new_channel');
  });

  it('truncates over-long values to the column widths', () => {
    const a = captureAttribution(`?utm_source=${'x'.repeat(200)}&sk_click=${'y'.repeat(500)}`);
    expect(a.source).toHaveLength(64);
    expect(a.ref).toHaveLength(128);
  });

  it('treats a capture older than the window as absent', () => {
    captureAttribution('?utm_source=google');
    const past = Date.now() + (ATTRIBUTION_WINDOW_DAYS + 1) * 24 * 60 * 60 * 1000;
    vi.spyOn(Date, 'now').mockReturnValue(past);
    expect(getAttribution()).toBeNull();
  });

  it('treats a future capture time as expired rather than valid forever', () => {
    // The timestamp is the visitor's own clock and cannot be trusted.
    window.sessionStorage.setItem(
      'skbg.attribution',
      JSON.stringify({
        source: 'google',
        ref: null,
        capturedAt: new Date(Date.now() + 86_400_000).toISOString(),
      })
    );
    expect(getAttribution()).toBeNull();
  });

  it('builds a spreadable payload, empty when there is nothing to report', () => {
    expect(attributionPayload()).toEqual({});
    captureAttribution('?utm_source=google&sk_click=abc');
    const payload = attributionPayload();
    expect(payload.attributionSource).toBe('google');
    expect(payload.attributionRef).toBe('abc');
    expect(payload.attributionCapturedAt).toBeTruthy();
  });

  it('omits attributionRef entirely when there is no click id', () => {
    captureAttribution('?utm_source=google');
    expect(attributionPayload()).not.toHaveProperty('attributionRef');
  });

  it('never throws when sessionStorage is unavailable', () => {
    const spy = vi.spyOn(window.sessionStorage.__proto__, 'setItem')
      .mockImplementation(() => { throw new Error('QuotaExceeded'); });
    // Analytics must never break the page it is measuring.
    expect(() => captureAttribution('?utm_source=google')).not.toThrow();
    spy.mockRestore();
  });

  it('clearAttribution removes the capture', () => {
    captureAttribution('?utm_source=google');
    clearAttribution();
    expect(getAttribution()).toBeNull();
  });
});
