-- Per-binge CMS content. One row per (binge, slug) pair, holding the JSON
-- page model. Lets the admin who owns a binge override the platform-wide
-- account-page CMS document with venue-specific FAQ, member offers, etc.
-- Mirrors the auth-service `site_content` table but is binge-scoped.
CREATE TABLE IF NOT EXISTS binge_site_content (
    binge_id      BIGINT       NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    content_json  TEXT         NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by    BIGINT,
    PRIMARY KEY (binge_id, slug),
    CONSTRAINT fk_binge_site_content_binge
        FOREIGN KEY (binge_id) REFERENCES binges(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_binge_site_content_slug ON binge_site_content(slug);
