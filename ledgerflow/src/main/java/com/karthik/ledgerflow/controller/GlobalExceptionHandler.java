package com.karthik.ledgerflow.controller;

import com.karthik.ledgerflow.dto.ApiResponse;
import com.karthik.ledgerflow.exception.IdempotencyConflictException;
import com.karthik.ledgerflow.exception.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Centralised exception handler for all REST controllers.
 *
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} → 400 Bad Request with per-field
 *       validation messages</li>
 *   <li>{@link IllegalArgumentException}        → 400 Bad Request</li>
 *   <li>{@link NoSuchElementException}          → 404 Not Found</li>
 *   <li>{@link InsufficientFundsException}      → 422 Unprocessable Entity</li>
 * </ul>
 *
 * Responses use the standard {@link ApiResponse} envelope so clients have a
 * consistent shape to parse regardless of whether the call succeeded or failed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles Bean Validation failures triggered by {@code @Valid} on request bodies.
     * Collects every field error into a map keyed by field name.
     *
     * @param ex the validation exception thrown by Spring MVC
     * @return 400 with a map of {@code fieldName → errorMessage}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            // Keep the first error per field if multiple constraints fire
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ApiResponse<Map<String, String>> body = new ApiResponse<>(
                false,
                "Validation failed",
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles explicit illegal argument errors raised by the service layer
     * (e.g. same-account transfer, non-positive amount).
     *
     * @param ex the thrown exception
     * @return 400 with the exception message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles account-not-found errors thrown by the service layer when an
     * account UUID does not exist in the database.
     *
     * @param ex the thrown exception
     * @return 404 with the exception message
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(
            NoSuchElementException ex) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles insufficient-balance errors thrown by {@code TransferService}.
     * Returns HTTP 422 Unprocessable Entity with a structured error body that
     * includes the account id, current balance, and required amount.
     *
     * @param ex the thrown exception
     * @return 422 with a map containing {@code accountId}, {@code balance}, {@code required}
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleInsufficientFunds(
            InsufficientFundsException ex) {

        Map<String, String> details = new LinkedHashMap<>();
        details.put("accountId", ex.getAccountId().toString());
        details.put("balance",   ex.getBalance().toPlainString());
        details.put("required",  ex.getRequired().toPlainString());

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiResponse<>(false, ex.getMessage(), details));
    }

    /**
     * Handles concurrent/in-flight requests for the same idempotency key.
     * Returns HTTP 409 Conflict.
     *
     * @param ex the thrown exception
     * @return 409 Conflict with error message
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyConflict(
            IdempotencyConflictException ex) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles rate-limiting violations.
     * Returns HTTP 429 Too Many Requests with a Retry-After header.
     *
     * @param ex the rate limit exception
     * @return 429 Too Many Requests with Retry-After header
     */
    @ExceptionHandler(com.karthik.ledgerflow.exception.RateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(
            com.karthik.ledgerflow.exception.RateLimitException ex) {

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiResponse.error(ex.getMessage()));
    }
}
