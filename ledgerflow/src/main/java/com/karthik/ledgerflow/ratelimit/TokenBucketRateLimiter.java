package com.karthik.ledgerflow.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Token-bucket rate limiter backed by Redis.
 *
 * <p>Enforces a maximum of 10 requests per second per client.
 * Tokens refill continuously at a rate of 10 tokens/second (0.01 tokens/millisecond)
 * up to a maximum bucket capacity of 10.
 *
 * <p>The check-and-decrement logic is executed as a single atomic Lua script
 * via {@link StringRedisTemplate#execute(RedisScript, List, Object...)},
 * eliminating any check-then-set race conditions.
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    public record RateLimitResult(boolean allowed, long retryAfterSeconds) {}

    public static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'last_updated')
            local tokens = tonumber(data[1])
            local last_updated = tonumber(data[2])

            if tokens == nil or last_updated == nil then
                tokens = capacity
                last_updated = now
            else
                local elapsed = math.max(0, now - last_updated)
                tokens = math.min(capacity, tokens + (elapsed * refill_rate))
                last_updated = now
            end

            if tokens >= requested then
                tokens = tokens - requested
                redis.call('HSET', key, 'tokens', tostring(tokens), 'last_updated', tostring(now))
                redis.call('EXPIRE', key, 60)
                return {1, 0}
            else
                redis.call('HSET', key, 'tokens', tostring(tokens), 'last_updated', tostring(now))
                redis.call('EXPIRE', key, 60)
                local missing = requested - tokens
                local retry_after_ms = math.ceil(missing / refill_rate)
                local retry_after_sec = math.ceil(retry_after_ms / 1000)
                if retry_after_sec < 1 then
                    retry_after_sec = 1
                end
                return {0, retry_after_sec}
            end
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> redisScript;

    // 10 requests per second
    private static final double CAPACITY = 10.0;
    private static final double REFILL_RATE_PER_MS = 10.0 / 1000.0; // 0.01 tokens / ms

    public TokenBucketRateLimiter(@Autowired(required = false) RedisConnectionFactory connectionFactory) {
        if (connectionFactory != null) {
            this.redisTemplate = new StringRedisTemplate(connectionFactory);
        } else {
            this.redisTemplate = null;
        }
        this.redisScript = RedisScript.of(LUA_SCRIPT, List.class);
    }

    /**
     * Attempts to acquire 1 token for the specified client.
     *
     * @param clientId client identifier (from X-Client-Id or remote IP)
     * @return result indicating if allowed and retry-after seconds if rejected
     */
    public RateLimitResult tryAcquire(String clientId) {
        if (redisTemplate == null) {
            // Redis not active (e.g. test profile)
            return new RateLimitResult(true, 0);
        }

        String key = "rate_limit:transfers:" + clientId;
        long now = System.currentTimeMillis();

        List<?> result = redisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(CAPACITY),
                String.valueOf(REFILL_RATE_PER_MS),
                String.valueOf(now),
                "1"
        );

        if (result == null || result.size() < 2) {
            log.warn("Rate limiter script returned unexpected result: {}", result);
            return new RateLimitResult(true, 0);
        }

        long allowed = ((Number) result.get(0)).longValue();
        long retryAfter = ((Number) result.get(1)).longValue();

        return new RateLimitResult(allowed == 1L, retryAfter);
    }

    /**
     * Resets/clears the rate-limiting key for a given client (used for testing clean slate).
     *
     * @param clientId client identifier
     */
    public void reset(String clientId) {
        if (redisTemplate != null) {
            redisTemplate.delete("rate_limit:transfers:" + clientId);
        }
    }
}
