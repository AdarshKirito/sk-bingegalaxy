CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    phone VARCHAR(15),
    preferred_experience VARCHAR(100),
    vibe_preference VARCHAR(120),
    reminder_lead_days INTEGER DEFAULT 14,
    birthday_month VARCHAR(20),
    birthday_day INTEGER,
    anniversary_month VARCHAR(20),
    anniversary_day INTEGER,
    birthday_reminder_sent_year INTEGER,
    anniversary_reminder_sent_year INTEGER,
    notification_channel VARCHAR(20) DEFAULT 'EMAIL',
    receives_offers BOOLEAN DEFAULT TRUE,
    weekend_alerts BOOLEAN DEFAULT TRUE,
    concierge_support BOOLEAN DEFAULT TRUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email ON users (email);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_phone ON users (phone);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(255) NOT NULL,
    otp VARCHAR(6),
    user_id BIGINT NOT NULL REFERENCES users (id),
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_password_reset_tokens_token ON password_reset_tokens (token);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);