package com.karthik.ledgerflow.dto;

/**
 * Generic API response envelope.
 * Wrap all controller responses in this to ensure a consistent JSON shape.
 *
 * @param <T> payload type
 */
public record ApiResponse<T>(boolean success, String message, T data) {

    /** Success response with data. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", data);
    }

    /** Success response with a custom message. */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    /** Error response without data. */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
