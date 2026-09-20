package com.karthik.ledgerflow;

import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.repository.LedgerEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.karthik.ledgerflow.controller.TransferController}
 * and idempotency-key enforcement against the live Dockerized PostgreSQL database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class TransferControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private static final ParameterizedTypeReference<ApiResponse<AccountResponse>> ACCOUNT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<TransferResult>> TRANSFER_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * Sends the same POST /transfers request twice with the same idempotencyKey.
     * Verifies that the second call returns the identical TransferResult
     * and that funds are only transferred once.
     */
    @Test
    @DisplayName("Duplicate POST /transfers with same idempotencyKey returns cached result without moving money twice")
    void duplicateTransfer_returnsCachedResult_doesNotDoubleSpend() {
        // ── 1. Create source account ($500.00) ─────────────────────────────
        CreateAccountRequest sourceReq = new CreateAccountRequest("Alice Source", new BigDecimal("500.00"));
        ResponseEntity<ApiResponse<AccountResponse>> sourceRes = restTemplate.exchange(
                "/accounts",
                HttpMethod.POST,
                new HttpEntity<>(sourceReq),
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(sourceRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sourceId = sourceRes.getBody().data().id();

        // ── 2. Create destination account ($100.00) ────────────────────────
        CreateAccountRequest destReq = new CreateAccountRequest("Bob Dest", new BigDecimal("100.00"));
        ResponseEntity<ApiResponse<AccountResponse>> destRes = restTemplate.exchange(
                "/accounts",
                HttpMethod.POST,
                new HttpEntity<>(destReq),
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(destRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID destId = destRes.getBody().data().id();

        // ── 3. First transfer: transfer $150.00 with unique idempotencyKey ─
        String idempotencyKey = "idemp-" + UUID.randomUUID();
        TransferRequest transferReq = new TransferRequest(
                sourceId,
                destId,
                new BigDecimal("150.00"),
                idempotencyKey
        );

        ResponseEntity<ApiResponse<TransferResult>> firstCall = restTemplate.exchange(
                "/transfers",
                HttpMethod.POST,
                new HttpEntity<>(transferReq),
                TRANSFER_RESPONSE_TYPE
        );

        assertThat(firstCall.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstCall.getBody()).isNotNull();
        assertThat(firstCall.getBody().success()).isTrue();

        TransferResult firstResult = firstCall.getBody().data();
        assertThat(firstResult).isNotNull();
        assertThat(firstResult.transactionId()).isNotNull();
        assertThat(firstResult.fromAccountNewBalance()).isEqualByComparingTo("350.00");
        assertThat(firstResult.toAccountNewBalance()).isEqualByComparingTo("250.00");
        assertThat(firstResult.status()).isEqualTo("COMPLETED");

        // ── 4. Second transfer: send identical payload and key ─────────────
        ResponseEntity<ApiResponse<TransferResult>> secondCall = restTemplate.exchange(
                "/transfers",
                HttpMethod.POST,
                new HttpEntity<>(transferReq),
                TRANSFER_RESPONSE_TYPE
        );

        assertThat(secondCall.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondCall.getBody()).isNotNull();
        assertThat(secondCall.getBody().success()).isTrue();

        TransferResult secondResult = secondCall.getBody().data();
        assertThat(secondResult).isNotNull();
        // Transaction ID and balances in response must be identical to the first response
        assertThat(secondResult.transactionId()).isEqualTo(firstResult.transactionId());
        assertThat(secondResult.fromAccountNewBalance()).isEqualByComparingTo("350.00");
        assertThat(secondResult.toAccountNewBalance()).isEqualByComparingTo("250.00");

        // ── 5. Verify actual balances in the database ─────────────────────
        ResponseEntity<ApiResponse<AccountResponse>> checkSource = restTemplate.exchange(
                "/accounts/" + sourceId,
                HttpMethod.GET,
                null,
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(checkSource.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Balance must STILL be 350.00, NOT 200.00
        assertThat(checkSource.getBody().data().balance()).isEqualByComparingTo("350.00");

        ResponseEntity<ApiResponse<AccountResponse>> checkDest = restTemplate.exchange(
                "/accounts/" + destId,
                HttpMethod.GET,
                null,
                ACCOUNT_RESPONSE_TYPE
        );
        assertThat(checkDest.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Balance must STILL be 250.00, NOT 400.00
        assertThat(checkDest.getBody().data().balance()).isEqualByComparingTo("250.00");
    }

    /**
     * Calling POST /transfers without an idempotencyKey must be rejected with 400 Bad Request.
     */
    @Test
    @DisplayName("POST /transfers with null or blank idempotencyKey returns 400 Bad Request")
    void missingIdempotencyKey_returns400() {
        TransferRequest badReq = new TransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50.00"),
                ""
        );

        ResponseEntity<ApiResponse<Void>> response = restTemplate.exchange(
                "/transfers",
                HttpMethod.POST,
                new HttpEntity<>(badReq),
                VOID_RESPONSE_TYPE
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
    }

    /**
     * Proves that the pessimistic locking strategy preserves consistency under real concurrency.
     * Fires 50 concurrent transfers of 100.00 from Account A (10000.00) to Account B (0.00)
     * using a 20-thread fixed pool.
     * Verifies that final balances are exactly 5000.00 each and exactly 100 ledger entry rows exist.
     */
    @Test
    @DisplayName("50 concurrent transfers of 100 from A(10000) to B(0) result in A=5000, B=5000 and 100 ledger entries")
    void concurrentTransfers_lockingStrategyMaintainsConsistency() throws Exception {
        // ── 1. Create two accounts: A (10000.00) and B (0.00) ──────────────
        CreateAccountRequest reqA = new CreateAccountRequest("Concurrent Account A", new BigDecimal("10000.00"));
        ResponseEntity<ApiResponse<AccountResponse>> resA = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(reqA), ACCOUNT_RESPONSE_TYPE);
        assertThat(resA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID accountAId = resA.getBody().data().id();

        CreateAccountRequest reqB = new CreateAccountRequest("Concurrent Account B", new BigDecimal("0.00"));
        ResponseEntity<ApiResponse<AccountResponse>> resB = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(reqB), ACCOUNT_RESPONSE_TYPE);
        assertThat(resB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID accountBId = resB.getBody().data().id();

        // ── 2. Fire 50 concurrent transfers using 20 threads ──────────────
        int totalTransfers = 50;
        int threadPoolSize = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalTransfers);
        List<ResponseEntity<ApiResponse<TransferResult>>> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalTransfers; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TransferRequest request = new TransferRequest(
                            accountAId,
                            accountBId,
                            new BigDecimal("100.00"),
                            "conc-key-" + index + "-" + UUID.randomUUID()
                    );
                    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                    headers.set("X-Client-Id", "load-test-client-" + index);
                    ResponseEntity<ApiResponse<TransferResult>> res = restTemplate.exchange(
                            "/transfers",
                            HttpMethod.POST,
                            new HttpEntity<>(request, headers),
                            TRANSFER_RESPONSE_TYPE
                    );
                    responses.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads concurrently
        startLatch.countDown();

        // ── 3. Wait for all 50 transfers to complete ──────────────────────
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(finished).as("All 50 concurrent transfers should finish within 60s").isTrue();

        assertThat(responses).hasSize(totalTransfers);
        for (ResponseEntity<ApiResponse<TransferResult>> res : responses) {
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().success()).isTrue();
        }

        // ── 4. Verify observed account balances ────────────────────────────
        ResponseEntity<ApiResponse<AccountResponse>> finalA = restTemplate.exchange(
                "/accounts/" + accountAId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);
        ResponseEntity<ApiResponse<AccountResponse>> finalB = restTemplate.exchange(
                "/accounts/" + accountBId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);

        BigDecimal observedBalanceA = finalA.getBody().data().balance();
        BigDecimal observedBalanceB = finalB.getBody().data().balance();

        System.out.println("==================================================");
        System.out.println("CONCURRENCY TEST OBSERVED FINAL BALANCES:");
        System.out.println("Account A Final Balance: " + observedBalanceA);
        System.out.println("Account B Final Balance: " + observedBalanceB);
        System.out.println("==================================================");

        assertThat(observedBalanceA).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(observedBalanceB).isEqualByComparingTo(new BigDecimal("5000.00"));

        // ── 5. Verify ledger_entries: 50 debits + 50 credits = 100 rows ────
        long debitEntries = ledgerEntryRepository.countByAccountId(accountAId);
        long creditEntries = ledgerEntryRepository.countByAccountId(accountBId);
        long totalEntries = ledgerEntryRepository.countByAccountIdIn(List.of(accountAId, accountBId));

        System.out.println("Account A Debits Count: " + debitEntries);
        System.out.println("Account B Credits Count: " + creditEntries);
        System.out.println("Total Ledger Entries for Accounts: " + totalEntries);
        System.out.println("==================================================");

        assertThat(debitEntries).isEqualTo(50);
        assertThat(creditEntries).isEqualTo(50);
        assertThat(totalEntries).isEqualTo(100);
    }
}
