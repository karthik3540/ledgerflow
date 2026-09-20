package com.karthik.ledgerflow.controller;

import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for financial transfers.
 *
 * <ul>
 *   <li>{@code POST /transfers} – execute a double-entry transfer between two accounts</li>
 * </ul>
 *
 * Validation errors are handled centrally by {@link GlobalExceptionHandler}.
 * Insufficient-funds errors (HTTP 422) are also handled there.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /transfers
     *
     * <p>Executes a double-entry transfer.  On success returns HTTP 200 with the
     * shared {@code transactionId}, new balances for both accounts, and status
     * {@code "COMPLETED"}.
     *
     * <p>Possible error responses:
     * <ul>
     *   <li>400 Bad Request  – validation failure (same accounts, amount ≤ 0, etc.)</li>
     *   <li>404 Not Found    – either account UUID does not exist</li>
     *   <li>422 Unprocessable Entity – source account has insufficient balance</li>
     * </ul>
     *
     * @param request validated transfer payload
     * @return 200 OK with transfer result
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TransferResult>> transfer(
            @RequestBody @Valid TransferRequest request) {

        TransferResult result = transferService.transfer(request);
        return ResponseEntity.ok(ApiResponse.ok("Transfer completed successfully", result));
    }
}
