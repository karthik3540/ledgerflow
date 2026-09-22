package com.karthik.ledgerflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.event.TransactionCompletedEvent;
import com.karthik.ledgerflow.model.AuditLog;
import com.karthik.ledgerflow.repository.AuditLogRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that a completed transfer publishes a
 * {@link TransactionCompletedEvent} to RabbitMQ, which is asynchronously
 * consumed and persisted into the {@code audit_log} table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class AuditLogIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static final ParameterizedTypeReference<ApiResponse<AccountResponse>> ACCOUNT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<TransferResult>> TRANSFER_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    @Test
    @DisplayName("Transfer publishes TransactionCompletedEvent to RabbitMQ and listener inserts row into audit_log")
    void transfer_publishesEvent_auditLogConsumerPersistsEntry() {
        // ── 1. Create source and destination accounts ─────────────────────
        ResponseEntity<ApiResponse<AccountResponse>> sourceRes = restTemplate.exchange(
                "/accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateAccountRequest("Audit Source", new BigDecimal("1000.00"))),
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(sourceRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sourceId = sourceRes.getBody().data().id();

        ResponseEntity<ApiResponse<AccountResponse>> destRes = restTemplate.exchange(
                "/accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateAccountRequest("Audit Dest", new BigDecimal("500.00"))),
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(destRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID destId = destRes.getBody().data().id();

        // ── 2. Perform transfer of $250.00 ────────────────────────────────
        BigDecimal transferAmount = new BigDecimal("250.00");
        String idempotencyKey = "audit-test-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(sourceId, destId, transferAmount, idempotencyKey);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Id", "audit-client-" + UUID.randomUUID());

        ResponseEntity<ApiResponse<TransferResult>> transferRes = restTemplate.exchange(
                "/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TRANSFER_RESPONSE_TYPE
        );

        assertThat(transferRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransferResult result = transferRes.getBody().data();
        assertThat(result).isNotNull();
        UUID transactionId = result.transactionId();
        assertThat(transactionId).isNotNull();

        // ── 3. Poll audit_log table with Awaitility for the matching row ──
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<AuditLog> auditLogs = auditLogRepository.findByTransactionId(transactionId);
                    assertThat(auditLogs)
                            .withFailMessage("Expected 1 audit_log entry for transactionId %s, but found none yet", transactionId)
                            .hasSize(1);

                    AuditLog logEntry = auditLogs.get(0);
                    System.out.println("=== RAW AUDIT_LOG ROW FOUND ===");
                    System.out.println("ID: " + logEntry.getId());
                    System.out.println("Transaction ID: " + logEntry.getTransactionId());
                    System.out.println("Event Type: " + logEntry.getEventType());
                    System.out.println("Payload: " + logEntry.getPayload());
                    System.out.println("Received At: " + logEntry.getReceivedAt());
                    System.out.println("================================");

                    assertThat(logEntry.getTransactionId()).isEqualTo(transactionId);
                    assertThat(logEntry.getEventType()).isEqualTo("TransactionCompletedEvent");

                    TransactionCompletedEvent payloadEvent = objectMapper.readValue(
                            logEntry.getPayload(),
                            TransactionCompletedEvent.class
                    );
                    assertThat(payloadEvent.transactionId()).isEqualTo(transactionId);
                    assertThat(payloadEvent.fromAccountId()).isEqualTo(sourceId);
                    assertThat(payloadEvent.toAccountId()).isEqualTo(destId);
                    assertThat(payloadEvent.amount()).isEqualByComparingTo(transferAmount);
                });
    }
}
