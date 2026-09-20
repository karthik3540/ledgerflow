package com.karthik.ledgerflow.exception;

/**
 * Thrown when a request arrives with an idempotency key that is currently
 * being processed by another in-flight transaction.
 *
 * Mapped to HTTP 409 Conflict by GlobalExceptionHandler.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String key;

    public IdempotencyConflictException(String key) {
        super("A request with idempotency key '" + key + "' is currently being processed");
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
