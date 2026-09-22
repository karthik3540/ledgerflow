package com.karthik.ledgerflow.service;

import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.exception.InsufficientFundsException;
import com.karthik.ledgerflow.model.Account;
import com.karthik.ledgerflow.model.EntryType;
import com.karthik.ledgerflow.model.LedgerEntry;
import com.karthik.ledgerflow.repository.AccountRepository;
import com.karthik.ledgerflow.repository.LedgerEntryRepository;
import com.karthik.ledgerflow.config.RabbitMQConfig;
import com.karthik.ledgerflow.event.TransactionCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Service layer for financial transfers.
 *
 * <h2>Concurrency &amp; Deadlock Prevention</h2>
 * <p>Transfers require pessimistic row-level locks ({@code SELECT ... FOR UPDATE})
 * on both account rows.  Without a consistent lock-acquisition order, two
 * concurrent transfers in opposite directions (A → B and B → A) would each
 * hold one lock and wait for the other, causing a deadlock.
 *
 * <p><strong>Lock-ordering rule:</strong> locks are always acquired in
 * <em>ascending UUID order</em>, regardless of which account is the source
 * and which is the destination.  Because both transactions acquire the locks
 * in the same order, one will always win the first lock and proceed while
 * the other waits — no circular wait can occur.
 */
@Service
public class TransferService implements LedgerService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferExecutionService executionService;
    private final IdempotencyService       idempotencyService;
    private final CacheManager             cacheManager;
    private final RabbitTemplate           rabbitTemplate;

    public TransferService(TransferExecutionService executionService,
                           IdempotencyService idempotencyService,
                           @Autowired(required = false) CacheManager cacheManager,
                           @Autowired(required = false) RabbitTemplate rabbitTemplate) {
        this.executionService   = executionService;
        this.idempotencyService = idempotencyService;
        this.cacheManager       = cacheManager;
        this.rabbitTemplate     = rabbitTemplate;
    }

    /**
     * Executes a double-entry transfer from one account to another with
     * strict idempotency enforcement and deadlock-free locking.
     *
     * <p>Idempotency reservation and completion run in isolated transactions
     * before and after the locked transfer to prevent database connection pool
     * starvation under high concurrency.
     *
     * <p>Redis cache eviction for both participating accounts is executed
     * immediately after the locked transfer transaction commits and before
     * idempotency completion, ensuring concurrent reads cannot re-cache stale
     * pre-commit balances.
     *
     * @param request validated transfer request
     * @return completed transfer result
     */
    public TransferResult transfer(TransferRequest request) {

        // ── Step 1: Basic validation ──────────────────────────────────────
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be null or blank");
        }
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException(
                    "fromAccountId and toAccountId must be different");
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }

        // ── Step 2: Idempotency check / reservation ───────────────────────
        IdempotencyService.ReservationResult reservation =
                idempotencyService.reserve(request.idempotencyKey());

        if (!reservation.newlyReserved()) {
            // Already completed – return the cached result without re-executing transfer
            return reservation.cachedResult();
        }

        TransferResult result;
        try {
            // ── Step 3: Execute locked transfer within its own transaction ───
            result = executionService.executeLockedTransfer(request);

            // ── Step 4: Evict cached balances for both accounts post-commit ──
            // At this point executeLockedTransfer has committed, so a concurrent GET
            // cannot observe or re-cache a stale pre-commit balance.
            if (cacheManager != null) {
                Cache accountsCache = cacheManager.getCache("accounts");
                if (accountsCache != null) {
                    accountsCache.evict(result.fromAccountId());
                    accountsCache.evict(result.toAccountId());
                }
            }

            // ── Step 5: Mark idempotency COMPLETED and store result ───────────
            idempotencyService.complete(request.idempotencyKey(), result);
        } catch (RuntimeException ex) {
            // Clean up the PROCESSING reservation so legitimate retries are not blocked
            idempotencyService.release(request.idempotencyKey());
            throw ex;
        }

        // ── Step 6: Publish TransactionCompletedEvent to ledger.exchange ──
        // Executed outside the idempotency try/catch block so a message broker failure
        // cannot trigger release of an already-COMPLETED idempotency record or turn an
        // already-committed transfer into a client-facing error.
        if (rabbitTemplate != null) {
            try {
                TransactionCompletedEvent event = new TransactionCompletedEvent(
                        result.transactionId(),
                        result.fromAccountId(),
                        result.toAccountId(),
                        request.amount(),
                        Instant.now()
                );
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.LEDGER_EXCHANGE,
                        RabbitMQConfig.LEDGER_ROUTING,
                        event
                );
            } catch (Exception ex) {
                log.error("Failed to publish TransactionCompletedEvent for transactionId={}", result.transactionId(), ex);
            }
        }

        return result;
    }
}
