package com.karthik.ledgerflow.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response returned by a successful {@code POST /transfers}.
 *
 * <p>Contains the shared {@link #transactionId} that links the DEBIT and CREDIT
 * rows in {@code ledger_entries}, the post-transfer cached balances for both
 * accounts, and a human-readable {@link #status}.
 */
public record TransferResult(

        /** UUID shared by the debit and credit ledger_entry rows. */
        UUID transactionId,

        UUID fromAccountId,

        /** Cached balance of the source account after the transfer. */
        BigDecimal fromAccountNewBalance,

        UUID toAccountId,

        /** Cached balance of the destination account after the transfer. */
        BigDecimal toAccountNewBalance,

        /** Always {@code "COMPLETED"} for a synchronous transfer. */
        String status

) {}
