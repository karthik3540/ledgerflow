package com.karthik.ledgerflow.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code ledger_entries} table.
 *
 * <p>Each financial transfer produces exactly <em>two</em> rows sharing the
 * same {@link #transactionId}: one {@link EntryType#DEBIT} on the source account
 * and one {@link EntryType#CREDIT} on the destination account.  {@link #amount}
 * is always positive; direction is expressed solely through {@link #entryType}.
 *
 * <p>{@link #accountId} is stored as a plain UUID foreign key (rather than a
 * {@code @ManyToOne}) to avoid lazy-load proxy complications inside the
 * pessimistically-locked transfer transaction.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * FK → accounts.id. Stored as a plain UUID to keep the mapping simple and
     * avoid accidental lazy-load fetches inside a locked transaction.
     */
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /**
     * Groups the debit and credit row of a single transfer. Both rows
     * produced by one {@code POST /transfers} call share the same value.
     */
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    /**
     * Transfer amount – always positive. Direction is determined by
     * {@link #entryType}.
     */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /**
     * Maps to the PostgreSQL {@code entry_type} native ENUM.
     * Hibernate 6 + PostgreSQLDialect auto-detect the native enum type and
     * handle the JDBC binding correctly when {@link EnumType#STRING} is used.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "entry_type", columnDefinition = "entry_type", nullable = false, updatable = false)
    private EntryType entryType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    protected LedgerEntry() { /* JPA */ }

    /**
     * Convenience constructor used by the service layer.
     *
     * @param accountId     the account this entry belongs to
     * @param transactionId shared UUID linking debit+credit pair
     * @param amount        always positive transfer amount
     * @param entryType     DEBIT or CREDIT
     */
    public LedgerEntry(UUID accountId, UUID transactionId, BigDecimal amount, EntryType entryType) {
        this.accountId     = accountId;
        this.transactionId = transactionId;
        this.amount        = amount;
        this.entryType     = entryType;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public UUID getId()            { return id; }
    public UUID getAccountId()     { return accountId; }
    public UUID getTransactionId() { return transactionId; }
    public BigDecimal getAmount()  { return amount; }
    public EntryType getEntryType(){ return entryType; }
    public Instant getCreatedAt()  { return createdAt; }
}
