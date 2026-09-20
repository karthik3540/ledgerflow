package com.karthik.ledgerflow.dto;

import com.karthik.ledgerflow.model.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of an {@link Account} returned by the API.
 * Created via the static factory {@link #from(Account)} to keep
 * mapping logic close to the DTO definition.
 */
public record AccountResponse(
        UUID id,
        String ownerName,
        BigDecimal balance,
        Instant createdAt
) {
    /**
     * Maps an {@link Account} entity to its API representation.
     *
     * @param account the persisted account entity
     * @return immutable response record
     */
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerName(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }
}
