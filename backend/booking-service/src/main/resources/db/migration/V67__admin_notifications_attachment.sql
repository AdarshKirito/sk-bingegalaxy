-- Optional media attachment (image or video) on a message. Uploaded via the
-- notification-attachment endpoint and served by MediaController's public /media/{file}.
-- attachment_type is 'image' | 'video' (drives how the client renders it).
ALTER TABLE admin_notifications
    ADD COLUMN IF NOT EXISTS attachment_url  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS attachment_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS attachment_name VARCHAR(255);
