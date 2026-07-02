// Slugs + helpers for the editable legal/terms content stored in the
// site-content CMS (auth-service). A super-admin edits these via the Terms
// editor; the customer signup, Add-Admin form and public /terms page read them.

export const TERMS_OF_SERVICE_SLUG = 'terms-of-service';
export const ADMIN_ONBOARDING_TERMS_SLUG = 'admin-onboarding-terms';

export const DEFAULT_TERMS_TITLE = 'Terms of Service & Privacy';
export const DEFAULT_ADMIN_TERMS_TITLE = 'Administrator Terms';

/**
 * Parse the CMS {@code contentJson} (a JSON string like {"title","body"}) into
 * a {title, body} object. Tolerates null (not yet authored) and plain-text
 * content (treated as the body) so the UI never throws on legacy/empty data.
 */
export function parseTerms(contentJson, fallback = { title: '', body: '' }) {
  if (contentJson == null || contentJson === '') return { ...fallback };
  try {
    const parsed = typeof contentJson === 'string' ? JSON.parse(contentJson) : contentJson;
    if (parsed && typeof parsed === 'object') {
      return {
        title: parsed.title || fallback.title,
        body: parsed.body ?? fallback.body,
      };
    }
    // JSON parsed to a primitive (e.g. a quoted string) — use it as the body.
    return { title: fallback.title, body: String(parsed) };
  } catch {
    // Not JSON — treat the raw string as the body.
    return { title: fallback.title, body: String(contentJson) };
  }
}

/** Serialize a {title, body} pair back into the CMS contentJson string. */
export function serializeTerms({ title, body }) {
  return JSON.stringify({ title: (title || '').trim(), body: body || '' });
}
