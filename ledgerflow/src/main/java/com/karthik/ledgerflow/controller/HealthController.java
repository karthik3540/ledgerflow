package com.karthik.ledgerflow.controller;

import com.karthik.ledgerflow.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health-check controller – confirms the application is running.
 * Replace or extend this with domain-specific controllers.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * GET /api/health
     * Returns a simple status payload.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        Map<String, String> payload = Map.of(
                "status", "UP",
                "service", "ledgerflow"
        );
        return ResponseEntity.ok(ApiResponse.ok("LedgerFlow is running", payload));
    }
}
