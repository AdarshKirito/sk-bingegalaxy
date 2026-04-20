-- Transactional outbox table for reliable Kafka publishing
CREATE TABLE IF NOT EXISTS outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    topic         VARCHAR(100)  NOT NULL,
    aggregate_key VARCHAR(30)   NOT NULL,
    payload       TEXT          NOT NULL,
    sent          BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_sent ON outbox_event (sent, created_at);

-- ShedLock table for distributed scheduler locking
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- Payment status audit trail
CREATE TABLE IF NOT EXISTS payment_status_history (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  BIGINT       NOT NULL,
    booking_ref VARCHAR(30)  NOT NULL,
    from_status VARCHAR(30),
    to_status   VARCHAR(30)  NOT NULL,
    reason      VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_psh_payment_id ON payment_status_history (payment_id);
CREATE INDEX IF NOT EXISTS idx_psh_created ON payment_status_history (created_at);
