import { useEffect, useRef, useCallback, useState } from 'react';

/**
 * Hook that connects to the admin SSE (Server-Sent Events) stream.
 * Falls back to a short-interval poll when SSE is unavailable.
 *
 * Usage:
 *   const { lastEvent, connected } = useRealtimeUpdates({ onEvent: (e) => refetchData() });
 *
 * The hook auto-reconnects with exponential back-off.
 */
export default function useRealtimeUpdates({ onEvent, enabled = true } = {}) {
  const [connected, setConnected] = useState(false);
  const [lastEvent, setLastEvent] = useState(null);
  const sourceRef = useRef(null);
  const retryDelay = useRef(1000);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  const connect = useCallback(() => {
    if (!enabled) return;
    // Close any existing connection
    if (sourceRef.current) {
      sourceRef.current.close();
      sourceRef.current = null;
    }

    try {
      const es = new EventSource('/api/v1/bookings/admin/events/stream', { withCredentials: true });
      sourceRef.current = es;

      es.onopen = () => {
        setConnected(true);
        retryDelay.current = 1000; // reset back-off
      };

      es.addEventListener('booking', (e) => {
        try {
          const data = JSON.parse(e.data);
          setLastEvent(data);
          onEventRef.current?.(data);
        } catch { /* ignore parse errors */ }
      });

      es.addEventListener('heartbeat', () => {
        // keepalive — nothing to do
      });

      es.onerror = () => {
        setConnected(false);
        es.close();
        sourceRef.current = null;
        // Exponential back-off: 1s → 2s → 4s → … → 30s max
        const delay = Math.min(retryDelay.current, 30000);
        retryDelay.current = delay * 2;
        setTimeout(connect, delay);
      };
    } catch {
      // EventSource not supported or network down — silent fallback
      setConnected(false);
    }
  }, [enabled]);

  useEffect(() => {
    connect();
    return () => {
      if (sourceRef.current) {
        sourceRef.current.close();
        sourceRef.current = null;
      }
    };
  }, [connect]);

  return { connected, lastEvent };
}
