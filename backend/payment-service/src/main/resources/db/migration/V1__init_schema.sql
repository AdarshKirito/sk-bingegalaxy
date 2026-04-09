CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    booking_ref VARCHAR(255) NOT NULL,
    customer_id BIGINT NOT NULL,
    transaction_id VARCHAR(255) NOT NULL,
    gateway_order_id VARCHAR(255),
    gateway_payment_id VARCHAR(255),
    amount NUMERIC(10, 2) NOT NULL,
    gateway_fee NUMERIC(10, 2),
    tax NUMERIC(10, 2),
    payment_method VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    currency VARCHAR(255),
    gateway_response VARCHAR(255),
    failure_reason VARCHAR(255),
    paid_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_booking_ref ON payments (booking_ref);
CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_transaction_id ON payments (transaction_id);
CREATE INDEX IF NOT EXISTS idx_payment_customer_id ON payments (customer_id);

CREATE TABLE IF NOT EXISTS refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments (id),
    amount NUMERIC(10, 2) NOT NULL,
    reason VARCHAR(255),
    gateway_refund_id VARCHAR(255),
    status VARCHAR(255) NOT NULL,
    gateway_response VARCHAR(255),
    failure_reason VARCHAR(255),
    initiated_by VARCHAR(255),
    refunded_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_refund_payment_id ON refunds (payment_id);
CREATE INDEX IF NOT EXISTS idx_refund_gateway_refund_id ON refunds (gateway_refund_id);