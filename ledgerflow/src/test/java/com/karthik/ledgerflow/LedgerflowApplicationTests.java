package com.karthik.ledgerflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test – verifies the Spring application context loads successfully.
 * Uses the "test" profile to allow overriding datasource/broker configs.
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerflowApplicationTests {

    @Test
    void contextLoads() {
        // If this test passes, the application context assembled correctly.
    }
}
