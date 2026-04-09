-- Allow null phone for Google OAuth users who haven't completed their profile yet
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
