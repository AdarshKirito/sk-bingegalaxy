-- Site CMS content: a single row per slug holding the JSON page model.
-- Lets super-admins update the public landing page without a redeploy.
CREATE TABLE IF NOT EXISTS site_content (
    slug          VARCHAR(64)  PRIMARY KEY,
    content_json  TEXT         NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by    BIGINT
);
