import { useEffect, useState } from 'react';
import { FiLock } from 'react-icons/fi';
import { authorityService } from '../../services/endpoints';

/**
 * Renders a lock badge for a (resourceType, resourceId) pair when the resource
 * has an active lock. Native super-admins see the lock owner + reason as
 * informational context; delegated admins see the same plus a clear "you cannot
 * modify this" cue. The component is intentionally side-effect-free for the
 * action layer — pages must read {@link useResourceLock} themselves to wire
 * "disabled" state on their action buttons.
 *
 * Usage:
 *   <LockBadge type="CURRENCY" id={c.code} />
 */
export default function LockBadge({ type, id, compact = false }) {
  const [lock] = useResourceLock(type, id);
  if (!lock) return null;
  if (compact) {
    return (
      <span
        title={`Locked by ${lock.lockedByName || ('user #' + lock.lockedBy)}: ${lock.reason}`}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 4,
          padding: '2px 6px', borderRadius: 4, fontSize: 11, fontWeight: 600,
          background: 'rgba(220,53,69,0.12)', color: '#dc3545',
          border: '1px solid rgba(220,53,69,0.35)',
        }}
      >
        <FiLock /> Locked
      </span>
    );
  }
  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        display: 'flex', gap: 8, alignItems: 'flex-start',
        background: 'rgba(220,53,69,0.08)', border: '1px solid rgba(220,53,69,0.30)',
        color: '#dc3545', borderRadius: 6, padding: '8px 12px', fontSize: 13,
      }}
    >
      <FiLock style={{ flex: '0 0 auto', marginTop: 2 }} />
      <div style={{ color: 'inherit' }}>
        <div style={{ fontWeight: 700 }}>
          Locked by {lock.lockedByName || ('user #' + lock.lockedBy)}
          {lock.lockedByEmail ? ` · ${lock.lockedByEmail}` : ''}
        </div>
        <div style={{ fontSize: 12, marginTop: 2, color: 'var(--text-muted)' }}>
          Reason: {lock.reason}
        </div>
      </div>
    </div>
  );
}

/**
 * Reactively look up the current lock (if any) for a resource. Returns a tuple
 * {@code [lock, refetch]}: the lock DTO when present (else {@code null}) and a
 * function the caller can invoke after a mutation (lock/release) to refresh
 * the snapshot. Auto-refetches on mount and when type/id changes; transient
 * failures resolve to {@code null} (advisory UI only).
 *
 * Usage:
 *   const [lock, refetch] = useResourceLock('CURRENCY', code);
 */
export function useResourceLock(type, id) {
  const [lock, setLock] = useState(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    if (!type || id == null || id === '') {
      setLock(null);
      return () => { cancelled = true; };
    }
    authorityService.lookupLock(type, String(id))
      .then(res => {
        if (cancelled) return;
        const data = res.data?.data;
        setLock(data || null);
      })
      .catch(() => { if (!cancelled) setLock(null); });
    return () => { cancelled = true; };
  }, [type, id, tick]);
  return [lock, () => setTick(t => t + 1)];
}
