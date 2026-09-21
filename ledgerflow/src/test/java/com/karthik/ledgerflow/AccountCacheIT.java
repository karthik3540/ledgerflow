package com.karthik.ledgerflow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.service.TransferExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying Redis cache-aside caching on AccountService.getAccount
 * and post-commit cache eviction inside TransferService.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class AccountCacheIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TransferExecutionService executionService;

    private static final ParameterizedTypeReference<ApiResponse<AccountResponse>> ACCOUNT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<TransferResult>> TRANSFER_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    @Test
    @DisplayName("Cache-aside: repeated GET is served from Redis (no Postgres hit); transfer evicts cache and returns new balance")
    void getAccount_cachedInRedis_andEvictedOnTransfer() {
        // ── 1. Create source (500.00) and destination (100.00) accounts ───
        CreateAccountRequest sourceReq = new CreateAccountRequest("Cache Test Source", new BigDecimal("500.00"));
        ResponseEntity<ApiResponse<AccountResponse>> createSource = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(sourceReq), ACCOUNT_RESPONSE_TYPE);
        assertThat(createSource.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sourceId = createSource.getBody().data().id();

        CreateAccountRequest destReq = new CreateAccountRequest("Cache Test Dest", new BigDecimal("100.00"));
        ResponseEntity<ApiResponse<AccountResponse>> createDest = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(destReq), ACCOUNT_RESPONSE_TYPE);
        assertThat(createDest.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID destId = createDest.getBody().data().id();

        Cache accountsCache = cacheManager.getCache("accounts");
        assertThat(accountsCache).isNotNull();

        // Ensure cache is initially clean for sourceId
        accountsCache.evict(sourceId);
        accountsCache.evict(destId);

        // ── 2. First GET: Cache MISS, must query Postgres and populate Redis ──
        ResponseEntity<ApiResponse<AccountResponse>> firstGet = restTemplate.exchange(
                "/accounts/" + sourceId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);
        assertThat(firstGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstGet.getBody().data().balance()).isEqualByComparingTo("500.00");

        // Verify Redis now holds the cached value
        Cache.ValueWrapper cachedValue = accountsCache.get(sourceId);
        assertThat(cachedValue).isNotNull();
        System.out.println("First GET: Populated Redis cache: " + cachedValue.get());

        // ── 3. Second GET: Cache HIT, served from Redis without hitting Postgres ──
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        sqlLogger.addAppender(listAppender);

        try {
            ResponseEntity<ApiResponse<AccountResponse>> secondGet = restTemplate.exchange(
                    "/accounts/" + sourceId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);
            assertThat(secondGet.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(secondGet.getBody().data().balance()).isEqualByComparingTo("500.00");

            // Filter SQL statements targeting the accounts table
            List<String> sqlQueries = listAppender.list.stream()
                    .map(ILoggingEvent::getMessage)
                    .filter(msg -> msg.toLowerCase().contains("accounts"))
                    .toList();

            System.out.println("Second GET: Hibernate SQL queries during cached read: " + sqlQueries.size());
            assertThat(sqlQueries)
                    .as("Second GET call must be served from Redis and execute ZERO SQL queries against Postgres")
                    .isEmpty();
        } finally {
            sqlLogger.detachAppender(listAppender);
        }

        // ── 4. Perform a transfer moving 150.00 from source to dest ────────
        TransferRequest transferRequest = new TransferRequest(
                sourceId,
                destId,
                new BigDecimal("150.00"),
                "cache-test-key-" + UUID.randomUUID()
        );

        ResponseEntity<ApiResponse<TransferResult>> transferRes = restTemplate.exchange(
                "/transfers", HttpMethod.POST, new HttpEntity<>(transferRequest), TRANSFER_RESPONSE_TYPE);
        assertThat(transferRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transferRes.getBody().success()).isTrue();

        // ── 5. Verify cache eviction in Redis ──────────────────────────────
        Cache.ValueWrapper afterTransferCache = accountsCache.get(sourceId);
        System.out.println("Post-transfer cache state for sourceId (expected null): " + afterTransferCache);
        assertThat(afterTransferCache)
                .as("Source account cache entry must be evicted immediately inside TransferService post-commit")
                .isNull();

        Cache.ValueWrapper afterTransferDestCache = accountsCache.get(destId);
        assertThat(afterTransferDestCache)
                .as("Destination account cache entry must be evicted immediately inside TransferService post-commit")
                .isNull();

        // ── 6. Third GET: Returns the NEW balance immediately (350.00, not stale 500.00) ──
        ResponseEntity<ApiResponse<AccountResponse>> thirdGet = restTemplate.exchange(
                "/accounts/" + sourceId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);
        assertThat(thirdGet.getStatusCode()).isEqualTo(HttpStatus.OK);

        BigDecimal finalObservedBalance = thirdGet.getBody().data().balance();
        System.out.println("Third GET (after transfer) observed balance: " + finalObservedBalance);

        assertThat(finalObservedBalance)
                .as("Third GET must return the newly updated balance immediately")
                .isEqualByComparingTo(new BigDecimal("350.00"));
    }

    /**
     * Exercises the race condition of pre-commit cache eviction:
     * 1. Create account A with balance 1000.
     * 2. GET it once to populate the cache.
     * 3. In one thread, start a transfer of 200 from A to a second account,
     *    with a small artificial delay (Thread.sleep(200)) immediately after
     *    the lock is acquired and before the balance update via postLockHook.
     * 4. While that transfer is mid-flight (inside the sleep window), fire a GET
     *    on account A from a second thread.
     * 5. Wait for the transfer to complete.
     * 6. GET account A again.
     * 7. Assert this final GET returns 800 (the correct new balance), not the
     *    stale 1000 — proving post-commit eviction timing is race-safe.
     */
    @Test
    @DisplayName("Race condition test: GET during in-flight locked transfer does not leave stale cached balance post-commit")
    void concurrentGetDuringTransfer_postCommitEvictionPreventsStaleCache() throws Exception {
        // ── 1. Create account A with balance 1000 ─────────────────────────
        CreateAccountRequest reqA = new CreateAccountRequest("Race Account A", new BigDecimal("1000.00"));
        ResponseEntity<ApiResponse<AccountResponse>> resA = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(reqA), ACCOUNT_RESPONSE_TYPE);
        assertThat(resA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID accountAId = resA.getBody().data().id();

        CreateAccountRequest reqB = new CreateAccountRequest("Race Account B", new BigDecimal("0.00"));
        ResponseEntity<ApiResponse<AccountResponse>> resB = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(reqB), ACCOUNT_RESPONSE_TYPE);
        assertThat(resB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID accountBId = resB.getBody().data().id();

        // ── 2. GET it once to populate the cache ──────────────────────────
        ResponseEntity<ApiResponse<AccountResponse>> initialGet = restTemplate.exchange(
                "/accounts/" + accountAId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);
        assertThat(initialGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initialGet.getBody().data().balance()).isEqualByComparingTo("1000.00");

        Cache accountsCache = cacheManager.getCache("accounts");
        assertThat(accountsCache).isNotNull();
        assertThat(accountsCache.get(accountAId)).isNotNull();
        System.out.println("Step 2: Account A balance cached in Redis = 1000.00");

        // ── 3. Set up test hook to insert 200ms delay after lock acquisition
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        executionService.setPostLockHook(() -> {
            try {
                System.out.println("Step 3: Transfer locked Account A & B in DB; notifying concurrent GET thread...");
                lockAcquiredLatch.countDown();
                // Sleep window: transfer holds pessimistic locks while still uncommitted
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Start transfer of 200 from A to B in background thread
            TransferRequest transferRequest = new TransferRequest(
                    accountAId,
                    accountBId,
                    new BigDecimal("200.00"),
                    "race-test-idemp-" + UUID.randomUUID()
            );

            HttpHeaders transferHeaders = new HttpHeaders();
            transferHeaders.set("X-Client-Id", "transfer-client-" + UUID.randomUUID());

            Future<ResponseEntity<ApiResponse<TransferResult>>> transferFuture = executor.submit(() ->
                    restTemplate.exchange(
                            "/transfers",
                            HttpMethod.POST,
                            new HttpEntity<>(transferRequest, transferHeaders),
                            TRANSFER_RESPONSE_TYPE
                    )
            );

            // Wait until transfer has acquired locks in Postgres and entered the delay window
            boolean locked = lockAcquiredLatch.await(5, TimeUnit.SECONDS);
            assertThat(locked).isTrue();

            // ── 4. While transfer is mid-flight (inside sleep window), fire GET on account A
            System.out.println("Step 4: Firing concurrent GET while transfer is mid-flight...");
            ResponseEntity<ApiResponse<AccountResponse>> midFlightGet = restTemplate.exchange(
                    "/accounts/" + accountAId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);
            assertThat(midFlightGet.getStatusCode()).isEqualTo(HttpStatus.OK);
            System.out.println("Step 4: Mid-flight GET observed balance = " + midFlightGet.getBody().data().balance());

            // ── 5. Wait for the transfer to complete ──────────────────────────
            ResponseEntity<ApiResponse<TransferResult>> transferResponse = transferFuture.get(5, TimeUnit.SECONDS);
            assertThat(transferResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(transferResponse.getBody().success()).isTrue();
            System.out.println("Step 5: Transfer of 200 completed and committed successfully");

            // ── 6. GET account A again ────────────────────────────────────────
            System.out.println("Step 6: Firing final GET on Account A post-transfer...");
            ResponseEntity<ApiResponse<AccountResponse>> finalGet = restTemplate.exchange(
                    "/accounts/" + accountAId, HttpMethod.GET, null, ACCOUNT_RESPONSE_TYPE);
            assertThat(finalGet.getStatusCode()).isEqualTo(HttpStatus.OK);

            BigDecimal finalBalance = finalGet.getBody().data().balance();
            System.out.println("Step 7: Final observed balance = " + finalBalance);

            // ── 7. Assert this final GET returns 800 (not the stale 1000) ─────
            assertThat(finalBalance)
                    .as("Final GET must return 800.00, proving post-commit eviction eliminated the stale cache race")
                    .isEqualByComparingTo(new BigDecimal("800.00"));

        } finally {
            // Clean up test hook and executor
            executionService.setPostLockHook(null);
            executor.shutdown();
        }
    }
}
