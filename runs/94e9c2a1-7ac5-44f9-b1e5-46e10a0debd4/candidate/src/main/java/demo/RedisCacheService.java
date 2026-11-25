package demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort Redis cache for redirect lookups.
 *
 * Key:   sl:link:{code}
 * Value: epochMillisOrNone|url   ("none" when the link never expires)
 * TTL:   min(shortener.cache.ttl, time to expiry); the put is skipped if the TTL is <= 0.
 *
 * PostgreSQL stays the source of truth. Every Redis call is wrapped in try/catch:
 * failures log a WARN (code only, never the URL or exception message), increment
 * shortener.redis.fallback{operation=cache_get|cache_put|cache_evict} and never propagate.
 */
@Component
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    static final String KEY_PREFIX = "sl:link:";
    private static final String NO_EXPIRY = "none";
    private static final char SEPARATOR = '|';

    /** A cached redirect target; expiresAt is null when the link never expires. */
    public record CachedLink(String url, Instant expiresAt) {
    }

    private final StringRedisTemplate redis;
    private final ShortenerProperties properties;
    private final Clock clock;
    private final Metrics metrics;

    public RedisCacheService(StringRedisTemplate redis, ShortenerProperties properties, Clock clock, Metrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Returns the cached link, or empty on a miss, a malformed entry or any Redis failure. */
    public Optional<CachedLink> get(String code) {
        String raw;
        try {
            raw = redis.opsForValue().get(key(code));
        } catch (RuntimeException e) {
            fallback("cache_get", code, e);
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        Optional<CachedLink> parsed = parse(raw);
        if (parsed.isEmpty()) {
            // Malformed entry: drop it and treat it as a miss.
            evict(code);
        }
        return parsed;
    }

    /** Caches the link with TTL = min(cache.ttl, time to expiry). Skipped when the TTL is <= 0. */
    public void put(String code, String url, Instant expiresAt) {
        try {
            if (url == null) {
                return;
            }
            Duration ttl = properties.getCache().getTtl();
            if (expiresAt != null) {
                Duration remaining = Duration.between(clock.instant(), expiresAt);
                if (remaining.compareTo(ttl) < 0) {
                    ttl = remaining;
                }
            }
            if (ttl.isNegative() || ttl.toMillis() <= 0) {
                return;
            }
            String value = (expiresAt == null ? NO_EXPIRY : Long.toString(expiresAt.toEpochMilli()))
                    + SEPARATOR + url;
            redis.opsForValue().set(key(code), value, ttl);
        } catch (RuntimeException e) {
            fallback("cache_put", code, e);
        }
    }

    /** Removes the cached entry. Never throws. */
    public void evict(String code) {
        try {
            redis.delete(key(code));
        } catch (RuntimeException e) {
            fallback("cache_evict", code, e);
        }
    }

    private static String key(String code) {
        return KEY_PREFIX + code;
    }

    private static Optional<CachedLink> parse(String raw) {
        int idx = raw.indexOf(SEPARATOR);
        if (idx <= 0 || idx == raw.length() - 1) {
            return Optional.empty();
        }
        String head = raw.substring(0, idx);
        String url = raw.substring(idx + 1);
        if (NO_EXPIRY.equals(head)) {
            return Optional.of(new CachedLink(url, null));
        }
        try {
            return Optional.of(new CachedLink(url, Instant.ofEpochMilli(Long.parseLong(head))));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    private void fallback(String operation, String code, RuntimeException e) {
        try {
            log.warn("Redis {} failed for code={}, falling back to PostgreSQL ({})",
                    operation, code, e.getClass().getSimpleName());
            metrics.redisFallback(operation);
        } catch (RuntimeException ignored) {
            // Logging or metrics must never break the request path.
        }
    }
}
