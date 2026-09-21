package com.karthik.ledgerflow;

import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.ratelimit.TokenBucketRateLimiter;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Redis token-bucket rate limiting on POST /transfers.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Burst traffic: 20 rapid requests under 1 second from the same client exceed
 *       the 10 req/s bucket and receive HTTP 429 Too Many Requests with Retry-After header.</li>
 *   <li>Spread traffic: requests spaced over 2+ seconds all succeed with HTTP 200.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class TransferRateLimiterIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TokenBucketRateLimiter rateLimiter;

    private static final ParameterizedTypeReference<ApiResponse<AccountResponse>> ACCOUNT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<TransferResult>> TRANSFER_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private UUID sourceId;
    private UUID destId;

    @BeforeEach
    void setUp() {
        CreateAccountRequest reqA = new CreateAccountRequest("Rate Limit Source", new BigDecimal("50000.00"));
        ResponseEntity<ApiResponse<AccountResponse>> resA = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(reqA), ACCOUNT_RESPONSE_TYPE);
        sourceId = resA.getBody().data().id();

        CreateAccountRequest reqB = new CreateAccountRequest("Rate Limit Dest", new BigDecimal("0.00"));
        ResponseEntity<ApiResponse<AccountResponse>> resB = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(reqB), ACCOUNT_RESPONSE_TYPE);
        destId = resB.getBody().data().id();
    }

    @Test
    @DisplayName("Burst of 20 requests in under 1 second from same client: some receive 429 with Retry-After")
    void burstRequests_exceedRateLimit_receive429WithRetryAfter() throws Exception {
        String clientId = "burst-client-" + UUID.randomUUID();
        rateLimiter.reset(clientId);

        int totalRequests = 20;
        int threadPoolSize = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);
        List<ResponseEntity<ApiResponse<TransferResult>>> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("X-Client-Id", clientId);

                    TransferRequest body = new TransferRequest(
                            sourceId,
                            destId,
                            new BigDecimal("10.00"),
                            "burst-idemp-" + index + "-" + UUID.randomUUID()
                    );

                    ResponseEntity<ApiResponse<TransferResult>> response = restTemplate.exchange(
                            "/transfers",
                            HttpMethod.POST,
                            new HttpEntity<>(body, headers),
                            TRANSFER_RESPONSE_TYPE
                    );
                    responses.add(response);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        // Release all threads simultaneously
        startLatch.countDown();

        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        long durationMs = System.currentTimeMillis() - startTime;
        executor.shutdown();

        assertThat(finished).isTrue();

        long okCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        long rateLimitedCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS).count();

        System.out.println("==================================================");
        System.out.println("BURST RATE LIMIT TEST RESULTS:");
        System.out.println("Total requests fired: " + totalRequests + " in " + durationMs + " ms");
        System.out.println("HTTP 200 OK count: " + okCount);
        System.out.println("HTTP 429 Too Many Requests count: " + rateLimitedCount);

        for (int i = 0; i < responses.size(); i++) {
            ResponseEntity<ApiResponse<TransferResult>> res = responses.get(i);
            if (res.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                String retryAfter = res.getHeaders().getFirst("Retry-After");
                System.out.println("Request #" + (i + 1) + ": 429 Too Many Requests, Retry-After: " + retryAfter + "s");
            }
        }
        System.out.println("==================================================");

        assertThat(rateLimitedCount)
                .as("Client firing 20 requests in a burst must encounter 429 Too Many Requests")
                .isGreaterThan(0);

        // Verify that 429 responses contain a Retry-After header
        responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                .forEach(r -> {
                    assertThat(r.getHeaders().getFirst("Retry-After"))
                            .as("429 response must include Retry-After header")
                            .isNotBlank();
                });
    }

    @Test
    @DisplayName("Requests spread over 2+ seconds from same client: all succeed with 200 OK")
    void spreadRequests_withinRateLimit_allSucceed() throws Exception {
        String clientId = "spread-client-" + UUID.randomUUID();
        rateLimiter.reset(clientId);

        int totalRequests = 12;
        List<ResponseEntity<ApiResponse<TransferResult>>> responses = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Client-Id", clientId);

            TransferRequest body = new TransferRequest(
                    sourceId,
                    destId,
                    new BigDecimal("10.00"),
                    "spread-idemp-" + i + "-" + UUID.randomUUID()
            );

            ResponseEntity<ApiResponse<TransferResult>> response = restTemplate.exchange(
                    "/transfers",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    TRANSFER_RESPONSE_TYPE
            );
            responses.add(response);

            // Sleep 200ms between requests (equivalent to ~5 requests/sec, well under 10 req/s limit)
            Thread.sleep(200);
        }

        long durationMs = System.currentTimeMillis() - startTime;

        long okCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        long rateLimitedCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS).count();

        System.out.println("==================================================");
        System.out.println("SPREAD RATE LIMIT TEST RESULTS:");
        System.out.println("Total requests fired: " + totalRequests + " in " + durationMs + " ms");
        System.out.println("HTTP 200 OK count: " + okCount);
        System.out.println("HTTP 429 Too Many Requests count: " + rateLimitedCount);
        System.out.println("==================================================");

        assertThat(durationMs)
                .as("Spread requests must span over 2+ seconds")
                .isGreaterThanOrEqualTo(2000);

        assertThat(okCount)
                .as("All spread requests within limit must succeed with 200 OK")
                .isEqualTo(totalRequests);

        assertThat(rateLimitedCount)
                .as("Zero requests should be rate limited when spread out")
                .isZero();
    }
}
