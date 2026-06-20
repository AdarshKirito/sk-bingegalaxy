import { useCallback, useRef, useState } from 'react';

/**
 * Thin, permission-aware wrapper around the browser Geolocation API.
 *
 * Mirrors how consumer apps (Uber Eats, DoorDash) request location: nothing fires
 * until the user explicitly asks ("use my location"), the request is one-shot, and
 * every failure mode is surfaced as a friendly status rather than an unhandled throw.
 *
 * Returns:
 *   - status:   'idle' | 'prompting' | 'granted' | 'denied' | 'unavailable' | 'error'
 *   - coords:   { latitude, longitude, accuracy } | null
 *   - error:    human-readable message | null
 *   - request(): Promise<coords> — call from a user gesture; rejects on failure
 *   - reset():   clear coords/status back to idle
 *
 * Security/privacy notes:
 *   - Geolocation only resolves on secure origins (https / localhost); on insecure
 *     origins the browser reports it as unavailable, which we surface cleanly.
 *   - We never persist precise coordinates here — the caller decides what (if
 *     anything) to cache. Coordinates are kept in memory for the session only.
 */
export default function useGeolocation({ enableHighAccuracy = true, timeout = 10000, maximumAge = 60000 } = {}) {
  const [status, setStatus] = useState('idle');
  const [coords, setCoords] = useState(null);
  const [error, setError] = useState(null);
  // Guard against overlapping requests (double-clicks): only one in-flight at a time.
  const inFlight = useRef(false);

  const request = useCallback(() => {
    return new Promise((resolve, reject) => {
      if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
        setStatus('unavailable');
        setError('Location is not supported on this device or browser.');
        reject(new Error('geolocation-unavailable'));
        return;
      }
      if (inFlight.current) {
        reject(new Error('geolocation-in-flight'));
        return;
      }
      inFlight.current = true;
      setStatus('prompting');
      setError(null);

      navigator.geolocation.getCurrentPosition(
        (position) => {
          inFlight.current = false;
          const next = {
            latitude: position.coords.latitude,
            longitude: position.coords.longitude,
            accuracy: position.coords.accuracy,
          };
          setCoords(next);
          setStatus('granted');
          resolve(next);
        },
        (err) => {
          inFlight.current = false;
          let nextStatus = 'error';
          let message = 'Could not determine your location. Please try again.';
          if (err && err.code === err.PERMISSION_DENIED) {
            nextStatus = 'denied';
            message = 'Location permission was denied. You can still browse all venues below.';
          } else if (err && err.code === err.POSITION_UNAVAILABLE) {
            nextStatus = 'unavailable';
            message = 'Your location is currently unavailable. Showing all venues instead.';
          } else if (err && err.code === err.TIMEOUT) {
            nextStatus = 'error';
            message = 'Locating you took too long. Please try again.';
          }
          setStatus(nextStatus);
          setError(message);
          reject(err instanceof Error ? err : new Error(message));
        },
        { enableHighAccuracy, timeout, maximumAge }
      );
    });
  }, [enableHighAccuracy, timeout, maximumAge]);

  const reset = useCallback(() => {
    setStatus('idle');
    setCoords(null);
    setError(null);
  }, []);

  return { status, coords, error, request, reset };
}
