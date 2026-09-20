package com.karthik.ledgerflow.repository;

import com.karthik.ledgerflow.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Account} entities.
 * All CRUD + pagination operations are provided out-of-the-box by JpaRepository.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Loads an account and immediately acquires a pessimistic write lock
     * ({@code SELECT ... FOR UPDATE}) on the underlying row.
     *
     * <p>Callers <strong>must</strong> invoke this inside an active
     * {@code @Transactional} context, and must acquire locks for all involved
     * accounts in a globally consistent order (ascending UUID) to prevent
     * deadlocks — see {@code TransferService} for the ordering logic.
     *
     * @param id account UUID
     * @return the locked account, or empty if not found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
