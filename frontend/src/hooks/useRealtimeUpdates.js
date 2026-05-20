import { useEffect, useRef, useCallback, useState } from 'react';
import useBingeStore from '../stores/bingeStore';

/**
 * Hook that connects to the admin SSE (Server-Sent Events) stream,
 * scoped to the currently selected binge (multi-tenant).
 *
 * Usage:
 *   const { lastEvent, connected } = useRealtimeUpdates({ onEvent: (e) => refetchData() });
 *
 * The hook auto-reconnects with exponential back-off and re-subscribes
 * when the selected binge changes (so picking a venue after mount works
 * without a page reload).
 */
export default function useRealtimeUpdates({ onEvent, enabled = true } = {}) {
  const [connected, setConnected] = useState(false);
  const [lastEvent, setLastEvent] = useState(null);
  const sourceRef = useRef(null);
  const retryDelay = useRef(1000);
  const retryTimerRef = useRef(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  // Subscribe to the binge store so we re-connect whenever the selected
  // binge changes (e.g. user picks a venue after the hook mounted).
  const selectedBingeId = useBingeStore((s) => s.selectedBinge?.id || null);

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

    if (!selectedBingeId) {
      setConnected(false);
      return; // No binge selected — nothing to subscribe to
    }

    try {
      const url = `/api/v1/bookings/admin/events/stream?bingeId=${encodeURIComponent(selectedBingeId)}`;
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
  }, [enabled, selectedBingeId]);

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
