import { useEffect, useState } from 'react';
import { bookingService, siteContentService } from '../services/endpoints';
import {
  ACCOUNT_PAGE_CMS_SLUG,
  defaultAccountPageContent,
  mergeAccountPageContent,
} from '../content/accountPageDefaults';

// Per-session cache so the Dashboard, Account Center, and the four Help pages
// don't each refetch the same CMS document. Keyed by binge id ('global' when
// no venue is selected).
const cache = new Map();

/**
 * Resolves the customer-facing account/help content with the same precedence
 * the Account Page Editor uses when saving:
 *
 *   1. Per-binge override (admin-authored via /admin/binges/:id/account-page-editor)
 *   2. Platform-wide document (super-admin, /admin/account-page-editor)
 *   3. Bundled defaults
 *
 * This is the missing read-side of the CMS: the editor wrote per-binge and
 * global documents, but customer pages used to render hardcoded constants —
 * so nothing an admin saved ever reached a customer.
 *
 * Returns `{ content, loading }`; `content` always has the full shape of
 * `defaultAccountPageContent`, so callers never need null checks.
 */
export function useAccountPageContent(bingeId) {
  const key = bingeId ? String(bingeId) : 'global';
  const [content, setContent] = useState(() => cache.get(key) || defaultAccountPageContent);
  const [loading, setLoading] = useState(!cache.has(key));

  useEffect(() => {
    let cancelled = false;
    if (cache.has(key)) {
      setContent(cache.get(key));
      setLoading(false);
      return undefined;
    }
    setLoading(true);
    // Defensive: a failed (or absent) CMS fetch must degrade to the bundled
    // defaults, never blank panels or a crashed page.
    const safeLoad = (fn) => {
      try {
        return Promise.resolve(fn());
      } catch {
        return Promise.reject(new Error('content load unavailable'));
      }
    };
    const loads = [safeLoad(() => siteContentService.getPublic(ACCOUNT_PAGE_CMS_SLUG))];
    if (bingeId) {
      loads.push(safeLoad(() => bookingService.getBingeSiteContent(bingeId, ACCOUNT_PAGE_CMS_SLUG)));
    }
    Promise.allSettled(loads).then(([globalRes, bingeRes]) => {
      if (cancelled) return;
      const parse = (res) => {
        const raw = res?.status === 'fulfilled' ? res.value?.data?.data?.contentJson : null;
        if (!raw) return null;
        try {
          return typeof raw === 'string' ? JSON.parse(raw) : raw;
        } catch {
          return null;
        }
      };
      const merged = mergeAccountPageContent(parse(bingeRes) || parse(globalRes));
      cache.set(key, merged);
      setContent(merged);
      setLoading(false);
    });
    return () => { cancelled = true; };
  }, [key, bingeId]);

  return { content, loading };
}

/**
 * Splices the CMS support-hours value into any bullet containing the literal
 * `{hours}` token (documented in the editor's Help & Trust section).
 */
export function spliceHours(text, hours) {
  return (text || '').replaceAll('{hours}', hours || defaultAccountPageContent.supportHours);
}

/** Test/editor hook: drop the per-session cache (e.g. after a save). */
export function invalidateAccountPageContent(bingeId) {
  if (bingeId === undefined) cache.clear();
  else cache.delete(bingeId ? String(bingeId) : 'global');
}
