package com.karthik.ledgerflow;

import com.karthik.ledgerflow.config.RabbitMQConfig;
import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.model.Account;
import com.karthik.ledgerflow.model.IdempotencyKey;
import com.karthik.ledgerflow.model.IdempotencyStatus;
import com.karthik.ledgerflow.repository.AccountRepository;
import com.karthik.ledgerflow.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration test verifying that a message broker outage during event publishing
 * does not compromise the completed transfer or release the COMPLETED idempotency record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class TransferRabbitMQFailureIT {

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AccountRepository accountRepository;

    private static final ParameterizedTypeReference<ApiResponse<AccountResponse>> ACCOUNT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<TransferResult>> TRANSFER_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    @Test
    @DisplayName("Transfer succeeds and remains COMPLETED when RabbitTemplate throws on publish, and retry returns cached result")
    void transfer_whenRabbitPublishFails_transferSucceedsAndIdempotencyRecordRemainsCompleted() {
        // ── 1. Configure Mock to throw AmqpException on convertAndSend ────────
        doThrow(new AmqpException("Simulated broker outage during event publish"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // ── 2. Create source ($1000.00) and destination ($500.00) accounts ────
        ResponseEntity<ApiResponse<AccountResponse>> sourceRes = restTemplate.exchange(
                "/accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateAccountRequest("RabbitFail Source", new BigDecimal("1000.00"))),
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(sourceRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sourceId = sourceRes.getBody().data().id();

        ResponseEntity<ApiResponse<AccountResponse>> destRes = restTemplate.exchange(
                "/accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateAccountRequest("RabbitFail Dest", new BigDecimal("500.00"))),
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(destRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID destId = destRes.getBody().data().id();

        // ── 3. Execute transfer of $200.00 ────────────────────────────────────
        String idempotencyKey = "rabbit-fail-test-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(sourceId, destId, new BigDecimal("200.00"), idempotencyKey);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Id", "rabbit-fail-client-" + UUID.randomUUID());

        ResponseEntity<ApiResponse<TransferResult>> transferRes = restTemplate.exchange(
                "/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TRANSFER_RESPONSE_TYPE
        );

        // Assert that HTTP 200 was returned with the expected balances
        assertThat(transferRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transferRes.getBody()).isNotNull();
        TransferResult result = transferRes.getBody().data();
        assertThat(result).isNotNull();
        assertThat(result.fromAccountNewBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(result.toAccountNewBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(result.status()).isEqualTo("COMPLETED");

        // Verify that publish was attempted
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(
                eq(RabbitMQConfig.LEDGER_EXCHANGE),
                eq(RabbitMQConfig.LEDGER_ROUTING),
                any(Object.class)
        );

        // ── 4. Assert idempotency record is COMPLETED and NOT deleted ─────────
        Optional<IdempotencyKey> keyOpt = idempotencyKeyRepository.findById(idempotencyKey);
        assertThat(keyOpt).isPresent();
        assertThat(keyOpt.get().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);

        // ── 5. Retry with the identical idempotencyKey ────────────────────────
        ResponseEntity<ApiResponse<TransferResult>> retryRes = restTemplate.exchange(
                "/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TRANSFER_RESPONSE_TYPE
        );

        assertThat(retryRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retryRes.getBody()).isNotNull();
        TransferResult retryResult = retryRes.getBody().data();
        assertThat(retryResult.transactionId()).isEqualTo(result.transactionId());
        assertThat(retryResult.fromAccountNewBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(retryResult.toAccountNewBalance()).isEqualByComparingTo(new BigDecimal("700.00"));

        // Confirm database balances were not deducted twice
        Account actualSource = accountRepository.findById(sourceId).orElseThrow();
        Account actualDest = accountRepository.findById(destId).orElseThrow();
        assertThat(actualSource.getBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(actualDest.getBalance()).isEqualByComparingTo(new BigDecimal("700.00"));

        System.out.println("=== TEST SUCCESSFUL: Transfer succeeded despite RabbitMQ failure ===");
        System.out.println("HTTP Status: " + transferRes.getStatusCode());
        System.out.println("Idempotency Record Status: " + keyOpt.get().getStatus());
        System.out.println("Original Transaction ID: " + result.transactionId());
        System.out.println("Retry Transaction ID: " + retryResult.transactionId());
        System.out.println("Final Source Balance: " + actualSource.getBalance());
        System.out.println("Final Destination Balance: " + actualDest.getBalance());
        System.out.println("====================================================================");
    }
}
