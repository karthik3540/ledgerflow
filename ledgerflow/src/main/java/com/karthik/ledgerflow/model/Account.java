package com.karthik.ledgerflow.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code accounts} table.
 * Uses a server-side UUID primary key (Hibernate 6 / JPA 3.1
 * {@code GenerationType.UUID}) and stores a cached balance for
 * fast reads — the ledger_entries table remains the source of truth.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_name", nullable = false, length = 255)
    private String ownerName;

    /**
     * Cached balance — denormalised from ledger_entries.
     * Precision 19, scale 4 matches the NUMERIC(19,4) column.
     */
    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
    }

    // ── Getters & Setters ─────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public Instant getCreatedAt() { return createdAt; }
}
