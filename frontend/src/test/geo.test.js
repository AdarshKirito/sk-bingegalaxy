import { describe, it, expect } from 'vitest';
import { formatDistance, hasCoordinates } from '../services/geo';

describe('formatDistance', () => {
  it('renders sub-kilometre distances in metres', () => {
    expect(formatDistance(0.3)).toBe('300 m away');
    expect(formatDistance(0.05)).toBe('50 m away');
  });

  it('renders one decimal under 10 km', () => {
    expect(formatDistance(2.34)).toBe('2.3 km away');
    expect(formatDistance(9.99)).toBe('10 km away');
  });

  it('renders whole km at and beyond 10 km', () => {
    expect(formatDistance(12.4)).toBe('12 km away');
    expect(formatDistance(127)).toBe('127 km away');
  });

  it('returns empty string for missing or invalid values', () => {
    expect(formatDistance(null)).toBe('');
    expect(formatDistance(undefined)).toBe('');
    expect(formatDistance(NaN)).toBe('');
    expect(formatDistance(Infinity)).toBe('');
  });
});

describe('hasCoordinates', () => {
  it('is true only when both coordinates are present and in range', () => {
    expect(hasCoordinates({ latitude: 12.97, longitude: 77.59 })).toBe(true);
    expect(hasCoordinates({ latitude: 0, longitude: 0 })).toBe(true);
  });

  it('is false when a coordinate is missing or out of range', () => {
    expect(hasCoordinates({ latitude: 12.97 })).toBe(false);
    expect(hasCoordinates({ longitude: 77.59 })).toBe(false);
    expect(hasCoordinates({ latitude: 95, longitude: 10 })).toBe(false);
    expect(hasCoordinates({ latitude: 10, longitude: 200 })).toBe(false);
    expect(hasCoordinates(null)).toBe(false);
    expect(hasCoordinates({})).toBe(false);
  });
});
