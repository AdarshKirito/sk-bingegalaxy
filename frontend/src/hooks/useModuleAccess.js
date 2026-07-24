import { useEffect, useState } from 'react';
import { adminService } from '../services/endpoints';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';

// Per-binge cache so every navbar render doesn't refetch; the backend
// enforces regardless (403 "This option is disabled by Super Admin."), so a
// briefly stale menu is only cosmetic.
const cache = new Map(); // bingeId -> Set of denied module keys

/**
 * The V71 module permissions of the CURRENT admin for the SELECTED binge.
 *
 * Returns { denied, can }: `can('DISPUTES')` is false when the super-admin
 * disabled or locked that module for this admin. SUPER_ADMIN (and customers,
 * and the no-binge state) always get `can() === true` — menu hiding is a
 * courtesy; the backend interceptors are the real enforcement.
 */
export function useModuleAccess() {
  const { isAdmin, isSuperAdmin } = useAuth();
  const { selectedBinge } = useBinge();
  const bingeId = selectedBinge?.id;
  const applies = isAdmin && !isSuperAdmin && !!bingeId;

  const [denied, setDenied] = useState(() => (applies && cache.get(bingeId)) || new Set());

  useEffect(() => {
    if (!applies) { setDenied(new Set()); return; }
    if (cache.has(bingeId)) { setDenied(cache.get(bingeId)); return; }
    let cancelled = false;
    adminService.getMyModulePermissions()
      .then((res) => {
        const list = res.data?.data?.deniedModules || [];
        const set = new Set(list);
        cache.set(bingeId, set);
        if (!cancelled) setDenied(set);
      })
      .catch(() => { /* fail-open for the MENU; backend still 403s */ });
    return () => { cancelled = true; };
  }, [applies, bingeId]);

  const can = (moduleKey) => !applies || !moduleKey || !denied.has(moduleKey);
  return { denied, can };
}

/** Drop the cached permissions for a binge (e.g. after a super-admin edit). */
export function invalidateModuleAccess(bingeId) {
  if (bingeId == null) cache.clear();
  else cache.delete(bingeId);
}

export default useModuleAccess;
