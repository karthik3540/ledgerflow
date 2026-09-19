package com.karthik.ledgerflow.model;

/**
 * Represents the direction of a {@link LedgerEntry} within a double-entry transfer.
 *
 * <ul>
 *   <li>{@link #DEBIT}  – money leaving an account (source side of a transfer)</li>
 *   <li>{@link #CREDIT} – money entering an account (destination side of a transfer)</li>
 * </ul>
 *
 * Maps to the PostgreSQL ENUM type {@code entry_type} defined in V2 migration.
 */
public enum EntryType {
    DEBIT,
    CREDIT
}
