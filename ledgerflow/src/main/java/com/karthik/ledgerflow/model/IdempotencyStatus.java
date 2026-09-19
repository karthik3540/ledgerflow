package com.karthik.ledgerflow.model;

/**
 * Lifecycle status for idempotency keys.
 * Matches PostgreSQL ENUM type {@code idempotency_status}.
 */
public enum IdempotencyStatus {
    PROCESSING,
    COMPLETED
}
