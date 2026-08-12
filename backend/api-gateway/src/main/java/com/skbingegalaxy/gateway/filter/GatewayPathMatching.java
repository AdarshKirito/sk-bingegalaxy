package com.skbingegalaxy.gateway.filter;

/**
 * The one way this gateway compares a request path against an allow-list.
 *
 * <p><b>Why it is shared rather than duplicated.</b> Two filters hold prefix allow-lists
 * for the same OCTO namespace, and they disagreed. {@code JwtAuthenticationFilter}
 * normalized the path and matched at a segment boundary; {@code CsrfProtectionFilter}
 * matched {@code request.getURI().getPath()} with a raw {@code startsWith}. Each was
 * defensible read on its own, and together they meant:
 *
 * <p>{@code POST /api/v1/distribution/octo/../connections} matches the machine-to-machine
 * prefix as a raw string, so CSRF was skipped — and that check runs <em>before</em> the
 * Origin check, so the request was exempt outright. The JWT filter normalized the same
 * path to {@code /api/v1/distribution/connections}, found it non-public and demanded a
 * token, so authentication still held. But holding authentication while dropping CSRF is
 * precisely the CSRF threat model: the victim is a logged-in admin whose browser attaches
 * the session itself, driven by a page the attacker controls.
 *
 * <p>The bug was not that either matcher was wrong. It was that there were two, and
 * nothing made them agree. Both now call this.
 *
 * <p><b>Never throws.</b> A path that cannot be parsed as a URI is returned unchanged
 * rather than throwing, so a malformed request cannot become a 500 from the gateway
 * itself. What that fallback means for matching is narrower than "matches nothing", and
 * deliberately so — see {@link #matchesNormalized}. Every downstream service re-enforces
 * its own rules regardless.
 */
public final class GatewayPathMatching {

    private GatewayPathMatching() {}

    /**
     * Collapse {@code ./} and {@code ../} so a crafted path cannot slip a privileged
     * segment past a segment check — e.g. {@code /api/v1/bookings/binges/../admin/x}.
     */
    public static String normalizePath(String path) {
        if (path == null) return "";
        try {
            String normalized = java.net.URI.create(path).normalize().getPath();
            return normalized == null ? path : normalized;
        } catch (RuntimeException e) {
            return path;
        }
    }

    /**
     * Segment-boundary prefix match against a NORMALIZED path.
     *
     * <p>A path matches only when it equals the prefix or continues at a separator, so
     * {@code /api/v1/bookings/binges} whitelists {@code /binges} and {@code /binges/{id}}
     * but never the sibling {@code /binges-secret}. A trailing slash on the prefix is
     * treated as a boundary, so {@code .../octo/} matches {@code .../octo/products}.
     */
    public static boolean matchesAtBoundary(String path, String prefix) {
        String base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return path.equals(base) || path.startsWith(base + "/");
    }

    /**
     * Normalize, then boundary-match — the combination every allow-list decision needs.
     *
     * <p><b>The unparseable case is handled explicitly, because the obvious answer is
     * wrong both ways.</b> {@link #normalizePath} falls back to the raw path when
     * {@code URI.create} throws, so a traversal inside an unparseable path would survive
     * and prefix-match — reopening the exact hole this class closes. Making an unparseable
     * path match nothing at all looks like the safe choice and is not: a decoded path
     * carrying a space, such as a media filename, fails {@code URI.create} legitimately,
     * and refusing to match would start demanding a JWT on genuinely public paths.
     *
     * <p>So the refusal is narrow: a surviving {@code ..} segment means the path could not
     * be resolved <em>and</em> is trying to climb, which no allow-listed prefix should ever
     * accept. Everything else keeps the raw-path fallback.
     */
    public static boolean matchesNormalized(String rawPath, String prefix) {
        String normalized = normalizePath(rawPath);
        if (containsTraversalSegment(normalized)) return false;
        return matchesAtBoundary(normalized, prefix);
    }

    /**
     * A {@code ..} PATH SEGMENT, not the substring. Checking for {@code ".."} anywhere
     * would reject a legitimate {@code /files/report..final.pdf}.
     *
     * <p>Only ever true on the raw-path fallback: a path that parsed cleanly has already
     * had its {@code ..} segments collapsed by {@link #normalizePath}.
     */
    private static boolean containsTraversalSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }
}
