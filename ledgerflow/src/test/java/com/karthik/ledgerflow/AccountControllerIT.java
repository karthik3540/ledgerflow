package com.karthik.ledgerflow;

import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.karthik.ledgerflow.controller.AccountController}.
 *
 * <p>These tests run against the real Dockerized PostgreSQL instance.
 * Before running, ensure all containers are healthy:
 * <pre>docker compose up -d</pre>
 *
 * <p>Activate via:
 * <pre>.\mvnw.cmd test -Dtest=AccountControllerIT -Dspring.profiles.active=integration</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class AccountControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    // ── Type references for generic ApiResponse deserialization ───────────

    private static final ParameterizedTypeReference<ApiResponse<AccountResponse>> ACCOUNT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * POST /accounts creates an account, then GET /accounts/{id} returns it
     * with the same balance that was submitted.
     */
    @Test
    @DisplayName("POST /accounts creates account → GET /accounts/{id} returns matching balance")
    void createAndFetchAccount_balanceMatches() {
        // ── Arrange ───────────────────────────────────────────────────────
        BigDecimal initialBalance = new BigDecimal("350.75");
        CreateAccountRequest request = new CreateAccountRequest("Alice Ledger", initialBalance);

        // ── Act: create ───────────────────────────────────────────────────
        ResponseEntity<ApiResponse<AccountResponse>> createResponse = restTemplate.exchange(
                "/accounts",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(request),
                ACCOUNT_RESPONSE_TYPE
        );

        // ── Assert: 201 Created + body ────────────────────────────────────
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ApiResponse<AccountResponse> createBody = createResponse.getBody();
        assertThat(createBody).isNotNull();
        assertThat(createBody.success()).isTrue();

        AccountResponse created = createBody.data();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.ownerName()).isEqualTo("Alice Ledger");
        assertThat(created.balance()).isEqualByComparingTo(initialBalance);
        assertThat(created.createdAt()).isNotNull();

        // ── Act: fetch by id ──────────────────────────────────────────────
        ResponseEntity<ApiResponse<AccountResponse>> getResponse = restTemplate.exchange(
                "/accounts/" + created.id(),
                HttpMethod.GET,
                null,
                ACCOUNT_RESPONSE_TYPE
        );

        // ── Assert: 200 OK + same balance ─────────────────────────────────
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<AccountResponse> getBody = getResponse.getBody();
        assertThat(getBody).isNotNull();
        assertThat(getBody.success()).isTrue();

        AccountResponse fetched = getBody.data();
        assertThat(fetched).isNotNull();
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.ownerName()).isEqualTo("Alice Ledger");
        assertThat(fetched.balance()).isEqualByComparingTo(initialBalance);
    }

    /**
     * GET /accounts/{id} with a random UUID that does not exist in the database
     * must return HTTP 404 Not Found.
     */
    @Test
    @DisplayName("GET /accounts/{id} with unknown UUID returns 404 Not Found")
    void getUnknownAccount_returns404() {
        // ── Act ───────────────────────────────────────────────────────────
        UUID nonExistentId = UUID.randomUUID();
        ResponseEntity<ApiResponse<Void>> response = restTemplate.exchange(
                "/accounts/" + nonExistentId,
                HttpMethod.GET,
                null,
                VOID_RESPONSE_TYPE
        );

        // ── Assert ────────────────────────────────────────────────────────
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiResponse<Void> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).contains("Account not found");
    }
}
