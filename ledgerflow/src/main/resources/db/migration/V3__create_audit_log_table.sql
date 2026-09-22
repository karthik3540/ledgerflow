-- V3__create_audit_log_table.sql
-- Audit log table to capture ledger events asynchronously consumed from RabbitMQ.

CREATE TABLE audit_log (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_transaction_id ON audit_log (transaction_id);
