package com.karthik.ledgerflow.ratelimit;

import com.karthik.ledgerflow.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor that applies token-bucket rate limiting to requests.
 *
 * <p>Identifies clients via the {@code X-Client-Id} HTTP header, falling back to
 * {@code X-Forwarded-For} or the remote IP address if the header is absent or blank.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final TokenBucketRateLimiter rateLimiter;

    public RateLimitInterceptor(TokenBucketRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String clientId = extractClientId(request);
            TokenBucketRateLimiter.RateLimitResult result = rateLimiter.tryAcquire(clientId);

            if (!result.allowed()) {
                throw new RateLimitException(
                        "Rate limit exceeded: 10 requests/sec limit per client",
                        result.retryAfterSeconds()
                );
            }
        }
        return true;
    }

    /**
     * Extracts client identity from X-Client-Id, falling back to X-Forwarded-For or remote IP.
     *
     * @param request the current HTTP request
     * @return client identifier string
     */
    public String extractClientId(HttpServletRequest request) {
        String clientId = request.getHeader("X-Client-Id");
        if (clientId != null && !clientId.isBlank()) {
            return clientId.trim();
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }

        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "unknown";
    }
}
