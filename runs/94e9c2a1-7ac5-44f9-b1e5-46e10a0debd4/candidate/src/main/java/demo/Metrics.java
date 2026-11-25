package demo;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over Micrometer counters used across the shortener.
 *
 * Counters:
 *   shortener.create{outcome}
 *   shortener.redirect{result}
 *   shortener.ratelimit.rejected{op}
 *   shortener.redis.fallback{operation}
 *
 * Tag values are expected to be low-cardinality constants (never user input).
 */
@Component
public class Metrics {

    static final String CREATE = "shortener.create";
    static final String REDIRECT = "shortener.redirect";
    static final String RATE_LIMIT_REJECTED = "shortener.ratelimit.rejected";
    static final String REDIS_FALLBACK = "shortener.redis.fallback";

    private final MeterRegistry registry;

    public Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Counts a create attempt, e.g. outcome = created, replayed, conflict, exhausted, invalid. */
    public void create(String outcome) {
        increment(CREATE, "outcome", outcome);
    }

    /** Counts a redirect, e.g. result = hit, not_found. */
    public void redirect(String result) {
        increment(REDIRECT, "result", result);
    }

    /** Counts a request rejected by the rate limiter, e.g. op = create or redirect. */
    public void rateLimitRejected(String op) {
        increment(RATE_LIMIT_REJECTED, "op", op);
    }

    /** Counts a Redis failure that fell back to PostgreSQL or failed open. */
    public void redisFallback(String operation) {
        increment(REDIS_FALLBACK, "operation", operation);
    }

    private void increment(String name, String tagKey, String tagValue) {
        String value = (tagValue == null || tagValue.isBlank()) ? "unknown" : tagValue;
        registry.counter(name, tagKey, value).increment();
    }
}
