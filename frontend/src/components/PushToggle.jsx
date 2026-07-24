import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { FiBell, FiBellOff, FiLoader, FiSend } from 'react-icons/fi';
import {
  PUSH_STATE,
  isPushSupported,
  getPushState,
  enablePush,
  disablePush,
  syncPushSubscription,
} from '../services/push';
import { notificationService } from '../services/endpoints';
import './PushToggle.css';

/**
 * Small opt-in control for browser push notifications. Renders nothing when push is
 * unsupported or the server has no VAPID keys, so it silently no-ops on browsers /
 * deployments that can't deliver push. Shared by the admin bell and the customer inbox.
 */
export default function PushToggle({ compact = false }) {
  const [state, setState] = useState(null); // null = still resolving
  const [busy, setBusy] = useState(false);
  const [hidden, setHidden] = useState(!isPushSupported());

  useEffect(() => {
    let alive = true;
    if (!isPushSupported()) { setHidden(true); return undefined; }
    getPushState().then((s) => {
      if (!alive) return;
      setState(s);
      // If already subscribed, make sure the backend still has this endpoint (it may have
      // been pruned on a redeploy or after a delivery failure). Best-effort + silent.
      if (s === PUSH_STATE.SUBSCRIBED) syncPushSubscription();
    });
    return () => { alive = false; };
  }, []);

  const handleEnable = useCallback(async () => {
    setBusy(true);
    try {
      const result = await enablePush();
      setState(result);
      if (result === PUSH_STATE.SUBSCRIBED) {
        toast.success('🔔 Push notifications enabled');
      } else if (result === PUSH_STATE.DENIED) {
        toast.info('Notifications are blocked. Enable them in your browser settings.');
      } else if (result === PUSH_STATE.DISABLED_SERVER) {
        toast.info('Push notifications are not available right now.');
        setHidden(true);
      }
    } catch (e) {
      toast.error('Could not enable push notifications.');
    } finally {
      setBusy(false);
    }
  }, []);

  const handleTest = useCallback(async () => {
    setBusy(true);
    try {
      const res = await notificationService.sendTestPush();
      const devices = res?.data?.data?.devices ?? 0;
      if (devices > 0) toast.success('Test notification sent — check your device.');
      else toast.info('No active subscription on this device — try turning push off and on again.');
    } catch {
      toast.error('Could not send a test notification.');
    } finally {
      setBusy(false);
    }
  }, []);

  const handleDisable = useCallback(async () => {
    setBusy(true);
    try {
      const result = await disablePush();
      setState(result);
      toast.info('Push notifications turned off');
    } catch {
      toast.error('Could not turn off push notifications.');
    } finally {
      setBusy(false);
    }
  }, []);

  if (hidden || state === PUSH_STATE.UNSUPPORTED) return null;
  if (state === null) return null; // resolving — don't flash

  const cls = `push-toggle${compact ? ' push-toggle-compact' : ''}`;

  if (state === PUSH_STATE.DENIED) {
    return (
      <div className={`${cls} push-toggle-denied`} title="Enable notifications in your browser's site settings">
        <FiBellOff aria-hidden="true" />
        <span>Notifications blocked in browser</span>
      </div>
    );
  }

  if (state === PUSH_STATE.SUBSCRIBED) {
    return (
      <span className="push-toggle-group">
        <button type="button" className={`${cls} push-toggle-on`} onClick={handleDisable} disabled={busy}
          title="Turn off push notifications on this device">
          {busy ? <FiLoader className="push-spin" aria-hidden="true" /> : <FiBell aria-hidden="true" />}
          <span>{compact ? 'Push on' : 'Push notifications on'}</span>
        </button>
        <button type="button" className={`${cls} push-toggle-test`} onClick={handleTest} disabled={busy}
          title="Send yourself a test notification">
          <FiSend aria-hidden="true" />
          <span>{compact ? 'Test' : 'Send test'}</span>
        </button>
      </span>
    );
  }

  // DEFAULT — supported, not yet subscribed
  return (
    <button type="button" className={`${cls} push-toggle-off`} onClick={handleEnable} disabled={busy}>
      {busy ? <FiLoader className="push-spin" aria-hidden="true" /> : <FiBell aria-hidden="true" />}
      <span>{compact ? 'Enable push' : 'Enable push notifications'}</span>
    </button>
  );
}
