package com.karthik.ledgerflow.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a transfer is attempted from an account whose cached balance is
 * less than the requested amount.
 *
 * <p>Mapped to HTTP {@code 422 Unprocessable Entity} by
 * {@link com.karthik.ledgerflow.controller.GlobalExceptionHandler}.
 */
public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final BigDecimal balance;
    private final BigDecimal required;

    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal required) {
        super(String.format(
                "Insufficient funds on account %s: balance=%s, required=%s",
                accountId, balance.toPlainString(), required.toPlainString()
        ));
        this.accountId = accountId;
        this.balance   = balance;
        this.required  = required;
    }

    public UUID getAccountId() { return accountId; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getRequired() { return required; }
}
