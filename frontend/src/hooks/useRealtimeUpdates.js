import { useEffect, useRef, useCallback, useState } from 'react';

/**
 * Hook that connects to the admin SSE (Server-Sent Events) stream,
 * scoped to the currently selected binge (multi-tenant).
 *
 * Usage:
 *   const { lastEvent, connected } = useRealtimeUpdates({ onEvent: (e) => refetchData() });
 *
 * The hook auto-reconnects with exponential back-off and re-subscribes
 * when the selected binge changes.
 */
export default function useRealtimeUpdates({ onEvent, enabled = true } = {}) {
  const [connected, setConnected] = useState(false);
  const [lastEvent, setLastEvent] = useState(null);
  const sourceRef = useRef(null);
  const retryDelay = useRef(1000);
  const retryTimerRef = useRef(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  // Read bingeId from localStorage (same source as api.js X-Binge-Id)
  const getBingeId = () => {
    try {
      const raw = localStorage.getItem('selectedBinge');
      return raw ? JSON.parse(raw).id : null;
    } catch { return null; }
  };

  const connect = useCallback(() => {
    if (!enabled) return;
    // Close any existing connection
    if (sourceRef.current) {
      sourceRef.current.close();
      sourceRef.current = null;
    }
    // Clear any pending retry timer
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }

    const bingeId = getBingeId();
    if (!bingeId) {
      setConnected(false);
      return; // No binge selected — nothing to subscribe to
    }

    try {
      const url = `/api/v1/bookings/admin/events/stream?bingeId=${encodeURIComponent(bingeId)}`;
      const es = new EventSource(url, { withCredentials: true });
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
        retryTimerRef.current = setTimeout(connect, delay);
      };
    } catch {
      // EventSource not supported or network down — silent fallback
      setConnected(false);
    }
  }, [enabled]);

  useEffect(() => {
    connect();
    return () => {
      if (retryTimerRef.current) {
        clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
      }
      if (sourceRef.current) {
        sourceRef.current.close();
        sourceRef.current = null;
      }
    };
  }, [connect]);

  return { connected, lastEvent };
}
