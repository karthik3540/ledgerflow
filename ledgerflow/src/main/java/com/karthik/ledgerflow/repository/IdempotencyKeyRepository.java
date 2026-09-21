package com.karthik.ledgerflow.repository;

import com.karthik.ledgerflow.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link IdempotencyKey} entities.
 */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Atomically inserts a row with status PROCESSING if the key does not exist.
     * Uses PostgreSQL ON CONFLICT (key) DO NOTHING so the insert-if-absent step
     * is atomic under concurrent requests without throwing constraint exceptions.
     *
     * @param key idempotency key
     * @return 1 if row was inserted, 0 if row already exists
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (key, status, created_at) VALUES (:key, 'PROCESSING'\\:\\:idempotency_status, NOW()) ON CONFLICT (key) DO NOTHING", nativeQuery = true)
    int insertIfNotExists(@Param("key") String key);
}
