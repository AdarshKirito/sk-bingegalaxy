CREATE TABLE IF NOT EXISTS rate_code_change_log (
    id                      BIGSERIAL       PRIMARY KEY,
    customer_id             BIGINT          NOT NULL,
    binge_id                BIGINT,
    previous_rate_code_id   BIGINT,
    previous_rate_code_name VARCHAR(100),
    new_rate_code_id        BIGINT,
    new_rate_code_name      VARCHAR(100),
    change_type             VARCHAR(30),
    changed_by_admin_id     BIGINT,
    changed_at              TIMESTAMP       DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rccl_customer ON rate_code_change_log (customer_id);
CREATE INDEX IF NOT EXISTS idx_rccl_binge    ON rate_code_change_log (binge_id);
