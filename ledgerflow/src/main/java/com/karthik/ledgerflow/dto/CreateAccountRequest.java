package com.karthik.ledgerflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /accounts}.
 * All constraints are enforced by Spring Validation before the request
 * reaches the service layer.
 */
public record CreateAccountRequest(

        @NotBlank(message = "ownerName must not be blank")
        String ownerName,

        @NotNull(message = "initialBalance must not be null")
        @DecimalMin(value = "0", message = "initialBalance must be >= 0")
        BigDecimal initialBalance

) {}
