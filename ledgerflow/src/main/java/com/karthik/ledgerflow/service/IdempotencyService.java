package com.karthik.ledgerflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.exception.IdempotencyConflictException;
import com.karthik.ledgerflow.model.IdempotencyKey;
import com.karthik.ledgerflow.model.IdempotencyStatus;
import com.karthik.ledgerflow.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service managing the lifecycle of idempotency keys for mutating financial operations.
 */
@Service
public class IdempotencyService {

    public record ReservationResult(
            boolean newlyReserved,
            TransferResult cachedResult
    ) {
        public static ReservationResult reserved() {
            return new ReservationResult(true, null);
        }

        public static ReservationResult completed(TransferResult cached) {
            return new ReservationResult(false, cached);
        }
    }

    private final IdempotencyReservationHelper reservationHelper;
    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(
            IdempotencyReservationHelper reservationHelper,
            IdempotencyKeyRepository repository,
            ObjectMapper objectMapper) {
        this.reservationHelper = reservationHelper;
        this.repository        = repository;
        this.objectMapper      = objectMapper;
    }

    /**
     * Atomically reserves an idempotency key with status {@code PROCESSING}.
     *
     * <ul>
     *   <li>If the key does not exist, inserts it as {@code PROCESSING} and returns newlyReserved = true.</li>
     *   <li>If the key exists and is {@code COMPLETED}, returns the cached {@link TransferResult}.</li>
     *   <li>If the key exists and is {@code PROCESSING}, throws {@link IdempotencyConflictException} (409 Conflict).</li>
     * </ul>
     *
     * @param key unique idempotency key
     * @return reservation outcome
     */
    public ReservationResult reserve(String key) {
        if (reservationHelper.tryInsertProcessing(key)) {
            return ReservationResult.reserved();
        }

        // Row already exists - inspect current status
        Optional<IdempotencyKey> existingOpt = repository.findById(key);
        if (existingOpt.isEmpty()) {
            // Edge case: concurrent failed transaction deleted the key right as we checked; retry once
            if (reservationHelper.tryInsertProcessing(key)) {
                return ReservationResult.reserved();
            }
            existingOpt = repository.findById(key);
        }

        IdempotencyKey existing = existingOpt.orElseThrow(() ->
                new IllegalStateException("Failed to determine state for idempotency key: " + key));

        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            try {
                TransferResult cached = objectMapper.readValue(
                        existing.getResponseBody(), TransferResult.class);
                return ReservationResult.completed(cached);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "Corrupt response_body stored for idempotency key: " + key, e);
            }
        }

        if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new IdempotencyConflictException(key);
        }

        throw new IllegalStateException("Unhandled idempotency status: " + existing.getStatus());
    }

    /**
     * Updates an idempotency key to {@code COMPLETED} and stores the serialized {@link TransferResult}.
     * Executes in an isolated {@code REQUIRES_NEW} transaction.
     *
     * @param key    the idempotency key
     * @param result the successful transfer result
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, TransferResult result) {
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize transfer result for idempotency storage", e);
        }

        IdempotencyKey record = repository.findById(key)
                .orElseGet(() -> new IdempotencyKey(key, IdempotencyStatus.COMPLETED));

        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setResponseBody(json);
        repository.saveAndFlush(record);
    }

    /**
     * Deletes or releases a key on transaction failure so legitimate retries with the
     * same key are not permanently blocked.
     * Executes in an isolated {@code REQUIRES_NEW} transaction.
     *
     * @param key the idempotency key to release
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        repository.deleteById(key);
        repository.flush();
    }
}
