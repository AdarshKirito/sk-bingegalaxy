CREATE TABLE IF NOT EXISTS blocked_dates (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT,
    blocked_date DATE NOT NULL,
    reason VARCHAR(255),
    blocked_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_blocked_dates_date ON blocked_dates (blocked_date);

CREATE TABLE IF NOT EXISTS blocked_slots (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT,
    slot_date DATE NOT NULL,
    start_hour INTEGER NOT NULL,
    end_hour INTEGER NOT NULL,
    reason VARCHAR(255),
    blocked_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_blocked_slot ON blocked_slots (slot_date, start_hour);