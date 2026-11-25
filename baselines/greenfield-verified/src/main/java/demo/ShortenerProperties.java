package demo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the shortener (prefix "shortener").
 * All values have safe defaults and are validated manually in setters.
 */
@ConfigurationProperties(prefix = "shortener")
public class ShortenerProperties {

    private List<String> reservedAliases = new ArrayList<>(List.of("api", "actuator", "health", "prometheus", "admin"));
    private Cache cache = new Cache();
    private Idempotency idempotency = new Idempotency();
    private RateLimit ratelimit = new RateLimit();

    public List<String> getReservedAliases() {
        return reservedAliases;
    }

    public void setReservedAliases(List<String> reservedAliases) {
        this.reservedAliases = reservedAliases == null ? new ArrayList<>() : new ArrayList<>(reservedAliases);
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache == null ? new Cache() : cache;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(Idempotency idempotency) {
        this.idempotency = idempotency == null ? new Idempotency() : idempotency;
    }

    public RateLimit getRatelimit() {
        return ratelimit;
    }

    public void setRatelimit(RateLimit ratelimit) {
        this.ratelimit = ratelimit == null ? new RateLimit() : ratelimit;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        return value;
    }

    /** Redirect cache settings. */
    public static class Cache {
        private Duration ttl = Duration.ofMinutes(5);

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = requirePositive(ttl, "shortener.cache.ttl");
        }
    }

    /** Idempotency record retention settings. */
    public static class Idempotency {
        private Duration retention = Duration.ofHours(24);

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = requirePositive(retention, "shortener.idempotency.retention");
        }
    }

    /** Per-client-IP fixed-window rate limit settings. */
    public static class RateLimit {
        private boolean enabled = true;
        private boolean trustForwardedHeaders = false;
        private Limit create = new Limit(30, Duration.ofMinutes(1));
        private Limit redirect = new Limit(300, Duration.ofMinutes(1));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isTrustForwardedHeaders() {
            return trustForwardedHeaders;
        }

        public void setTrustForwardedHeaders(boolean trustForwardedHeaders) {
            this.trustForwardedHeaders = trustForwardedHeaders;
        }

        public Limit getCreate() {
            return create;
        }

        public void setCreate(Limit create) {
            this.create = create == null ? new Limit(30, Duration.ofMinutes(1)) : create;
        }

        public Limit getRedirect() {
            return redirect;
        }

        public void setRedirect(Limit redirect) {
            this.redirect = redirect == null ? new Limit(300, Duration.ofMinutes(1)) : redirect;
        }

        /** Returns the limit for an operation name ("create" or "redirect"). */
        public Limit limitFor(String op) {
            return "create".equals(op) ? create : redirect;
        }
    }

    /** A request count allowed per window. */
    public static class Limit {
        private int limit;
        private Duration window;

        public Limit() {
            this(30, Duration.ofMinutes(1));
        }

        public Limit(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("rate limit must be >= 1");
            }
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = requirePositive(window, "rate limit window");
        }
    }
}
