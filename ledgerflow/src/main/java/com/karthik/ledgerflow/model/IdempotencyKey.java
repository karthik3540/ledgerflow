package com.karthik.ledgerflow.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * JPA entity mapped to the {@code idempotency_keys} table.
 *
 * <p>Stores request fingerprints and cached response bodies to guarantee
 * exactly-once semantics for mutating API operations.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey implements Persistable<String> {

    @Id
    @Column(name = "key", length = 255, nullable = false)
    private String key;

    @Transient
    private boolean isNew = true;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "idempotency_status", nullable = false)
    private IdempotencyStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyKey() { /* JPA */ }

    public IdempotencyKey(String key, IdempotencyStatus status) {
        this.key = key;
        this.status = status;
    }

    public IdempotencyKey(String key, IdempotencyStatus status, String responseBody) {
        this.key = key;
        this.status = status;
        this.responseBody = responseBody;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getKey() {
        return key;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
