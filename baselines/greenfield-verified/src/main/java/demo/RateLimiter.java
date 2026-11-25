package demo;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window, per-operation, per-client-IP rate limiter backed by Redis.
 *
 * Key: sl:rl:{op}:{ip}:{windowStartEpochSec}
 *
 * INCR and EXPIRE run atomically in a single Lua script, so a counter can never
 * be left without an expiration (even if the client dies between the two calls).
 *
 * The limiter fails open: on any Redis failure the request is allowed, a WARN is
 * logged and shortener.redis.fallback{operation=ratelimit} is incremented.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Result of a rate-limit check. retryAfterSeconds is >= 1 when not allowed, 0 otherwise. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    static final String KEY_PREFIX = "sl:rl:";
    private static final int MAX_IP_LENGTH = 64;

    /** Atomically increments the counter and sets the expiration on first hit (or if missing). */
    private static final String SCRIPT_SOURCE =
            "local c = redis.call('INCR', KEYS[1])\n"
                    + "if c == 1 or redis.call('TTL', KEYS[1]) < 0 then\n"
                    + "  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))\n"
                    + "end\n"
                    + "return c";

    private static final DefaultRedisScript<Long> INCR_EXPIRE_SCRIPT =
            new DefaultRedisScript<>(SCRIPT_SOURCE, Long.class);

    private final StringRedisTemplate redis;
    private final ShortenerProperties properties;
    private final Clock clock;
    private final Metrics metrics;

    public RateLimiter(StringRedisTemplate redis, ShortenerProperties properties, Clock clock, Metrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Checks and counts one request for the operation ("create" or "redirect") and client IP. */
    public Decision check(String op, String clientIp) {
        ShortenerProperties.RateLimit config = properties.getRatelimit();
        if (!config.isEnabled()) {
            return allow();
        }
        try {
            ShortenerProperties.Limit limit = config.limitFor(op);
            long windowSeconds = Math.max(1L, limit.getWindow().getSeconds());
            long nowSeconds = clock.instant().getEpochSecond();
            long windowStart = (nowSeconds / windowSeconds) * windowSeconds;
            String key = KEY_PREFIX + op + ":" + sanitize(clientIp) + ":" + windowStart;

            Long count = redis.execute(INCR_EXPIRE_SCRIPT, List.of(key), Long.toString(windowSeconds));
            if (count == null) {
                return failOpen(op, null);
            }
            if (count <= limit.getLimit()) {
                return allow();
            }
            long retryAfter = Math.max(1L, windowStart + windowSeconds - nowSeconds);
            return new Decision(false, retryAfter);
        } catch (RuntimeException e) {
            return failOpen(op, e);
        }
    }

    private static Decision allow() {
        return new Decision(true, 0L);
    }

    private Decision failOpen(String op, RuntimeException e) {
        try {
            log.warn("Rate limiter unavailable for op={}, failing open ({})", op,
                    e == null ? "null-result" : e.getClass().getSimpleName());
            metrics.redisFallback("ratelimit");
        } catch (RuntimeException ignored) {
            // Logging or metrics must never break the request path.
        }
        return allow();
    }

    /** Keeps only characters valid in IPv4/IPv6 text so the key cannot be polluted by spoofed input. */
    private static String sanitize(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        String trimmed = ip.trim();
        if (trimmed.length() > MAX_IP_LENGTH) {
            trimmed = trimmed.substring(0, MAX_IP_LENGTH);
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')
                    || ch == '.' || ch == ':';
            sb.append(ok ? ch : '_');
        }
        return sb.toString();
    }
}
