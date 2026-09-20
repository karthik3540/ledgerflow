package com.karthik.ledgerflow.controller;

import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import com.karthik.ledgerflow.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for account management.
 *
 * <ul>
 *   <li>{@code POST  /accounts}      – create an account</li>
 *   <li>{@code GET   /accounts/{id}} – fetch an account by UUID</li>
 * </ul>
 *
 * Validation errors are handled centrally by {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * POST /accounts
     * Creates a new account and returns HTTP 201 with the created account.
     *
     * @param request validated creation payload
     * @return 201 Created with the account details
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> createAccount(
            @RequestBody @Valid CreateAccountRequest request) {

        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created successfully", response));
    }

    /**
     * GET /accounts/{id}
     * Returns the account with the given UUID, or 404 if not found.
     *
     * @param id account UUID path variable
     * @return 200 OK with account, or 404 Not Found
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> getAccount(
            @PathVariable UUID id) {

        return accountService.getAccount(id)
                .<ResponseEntity<ApiResponse<AccountResponse>>>map(account ->
                        ResponseEntity.ok(ApiResponse.ok(account)))
                .orElseGet(() ->
                        ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(ApiResponse.error("Account not found: " + id)));
    }
}
