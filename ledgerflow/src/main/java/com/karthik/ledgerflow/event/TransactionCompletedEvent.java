package com.karthik.ledgerflow.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published to {@code ledger.exchange} when a money transfer
 * completes successfully.
 */
public record TransactionCompletedEvent(
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        Instant completedAt
) {}
