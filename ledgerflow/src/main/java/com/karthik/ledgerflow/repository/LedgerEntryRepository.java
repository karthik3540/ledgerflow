package com.karthik.ledgerflow.repository;

import com.karthik.ledgerflow.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link LedgerEntry} entities.
 * Entries are append-only; no update or delete operations are exposed.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    long countByAccountId(UUID accountId);

    long countByAccountIdIn(Collection<UUID> accountIds);
}
