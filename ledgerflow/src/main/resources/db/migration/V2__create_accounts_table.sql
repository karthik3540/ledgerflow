-- V2__create_accounts_table.sql
-- Double-entry ledger schema: accounts, ledger_entries, idempotency_keys.

-- ── Custom ENUM types ──────────────────────────────────────────────────────
CREATE TYPE entry_type         AS ENUM ('DEBIT', 'CREDIT');
CREATE TYPE idempotency_status AS ENUM ('PROCESSING', 'COMPLETED');

-- ── accounts ──────────────────────────────────────────────────────────────
-- balance is a cached/denormalised value only; ledger_entries is the source
-- of truth. UUID generated server-side via gen_random_uuid().
CREATE TABLE accounts (
    id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name  VARCHAR(255)   NOT NULL,
    balance     NUMERIC(19, 4) NOT NULL    DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL    DEFAULT NOW()
);

-- ── ledger_entries ─────────────────────────────────────────────────────────
-- Each transfer produces exactly two rows (one DEBIT, one CREDIT) sharing the
-- same transaction_id.  amount is always positive.
CREATE TABLE ledger_entries (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     UUID           NOT NULL REFERENCES accounts(id),
    transaction_id UUID           NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    entry_type     entry_type     NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_entries_account_id     ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);

-- ── idempotency_keys ───────────────────────────────────────────────────────
-- Stores in-flight and completed request fingerprints to guarantee exactly-
-- once semantics on API mutations.
CREATE TABLE idempotency_keys (
    key           VARCHAR(255)       PRIMARY KEY,
    status        idempotency_status NOT NULL,
    response_body JSONB,
    created_at    TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);
