import { useEffect, useRef } from 'react';
import { useRegisterSW } from 'virtual:pwa-register/react';

/**
 * Keeps the running app on the latest deployed build.
 *
 * With {@code registerType: 'autoUpdate'} the service worker skips waiting and claims
 * clients as soon as a new build is detected; this component makes that reach an
 * ALREADY-OPEN tab too: it polls for updates (immediately + every 15 min) and, the
 * moment a new version is available, activates it and reloads onto the fresh bundle.
 * No user action and no easy-to-miss banner — which previously left clients stranded
 * on stale, cached code after a release.
 */
export default function PWAUpdatePrompt() {
  const intervalRef = useRef(null);
  const {
    needRefresh: [needRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisteredSW(swUrl, r) {
      if (r) {
        r.update();
        intervalRef.current = setInterval(() => { r.update(); }, 15 * 60 * 1000);
      }
    },
  });

  // Apply a detected update automatically (activate new SW + reload). Guarded by
  // needRefresh so it fires once per release, not on every render.
  useEffect(() => {
    if (needRefresh) {
      updateServiceWorker(true);
    }
  }, [needRefresh, updateServiceWorker]);

  useEffect(() => () => {
    if (intervalRef.current) clearInterval(intervalRef.current);
  }, []);

  return null;
}
