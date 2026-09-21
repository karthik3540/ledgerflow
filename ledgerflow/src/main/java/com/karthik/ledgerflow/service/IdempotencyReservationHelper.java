package com.karthik.ledgerflow.service;

import com.karthik.ledgerflow.model.IdempotencyKey;
import com.karthik.ledgerflow.model.IdempotencyStatus;
import com.karthik.ledgerflow.repository.IdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helper to perform atomic insert of idempotency keys in an isolated transaction.
 *
 * <p>Uses {@code Propagation.REQUIRES_NEW} so that if a concurrent insert triggers
 * a unique-constraint violation, only this isolated transaction rolls back and the
 * outer caller's transaction is not marked rollback-only.
 */
@Component
public class IdempotencyReservationHelper {

    private final IdempotencyKeyRepository repository;

    public IdempotencyReservationHelper(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Atomically attempts to insert a row with status {@code PROCESSING}.
     *
     * @param key the idempotency key
     * @return true if inserted, false if the key already exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsertProcessing(String key) {
        return repository.insertIfNotExists(key) > 0;
    }
}
