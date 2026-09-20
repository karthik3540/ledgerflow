package com.karthik.ledgerflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /transfers}.
 *
 * <p>The {@code idempotencyKey} is received and stored on the request but
 * idempotency enforcement is deferred to a future implementation.
 */
public record TransferRequest(

        @NotNull(message = "fromAccountId must not be null")
        UUID fromAccountId,

        @NotNull(message = "toAccountId must not be null")
        UUID toAccountId,

        @NotNull(message = "amount must not be null")
        @DecimalMin(value = "0", inclusive = false, message = "amount must be > 0")
        BigDecimal amount,

        @NotBlank(message = "idempotencyKey must not be blank")
        String idempotencyKey

) {}
