package demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates optional link expiration timestamps and computes remaining lifetime.
 * A null expiresAt means the link never expires unless a default lifetime is
 * configured (see resolveForCreate). A non-null value must be strictly after the
 * current instant (from the injected Clock), otherwise a ResponseStatusException
 * with status 400 is raised.
 *
 * Default and maximum lifetimes are optional (null means unrestricted). They are
 * only applied by resolveForCreate, so validate(Instant) stays unchanged.
 */
@Component
public class ExpirationPolicy {

    private final Clock clock;
    private final Duration defaultLifetime;
    private final Duration maxLifetime;

    /** Unrestricted policy: no default lifetime and no maximum. */
    public ExpirationPolicy(Clock clock) {
        this.clock = clock;
        this.defaultLifetime = null;
        this.maxLifetime = null;
    }

    /** Policy configured from shortener.expiration.*; a null or unset configuration is unrestricted. */
    @Autowired
    public ExpirationPolicy(Clock clock, ShortenerProperties properties) {
        this.clock = clock;
        ShortenerProperties.Expiration expiration = properties == null ? null : properties.getExpiration();
        if (expiration == null) {
            this.defaultLifetime = null;
            this.maxLifetime = null;
        } else {
            // Fail fast on an invalid configuration (default longer than maximum).
            expiration.validate();
            this.defaultLifetime = expiration.getDefaultLifetime();
            this.maxLifetime = expiration.getMaxLifetime();
        }
    }

    /** Accepts null; rejects an instant that is in the past or equal to now. Never applies the maximum. */
    public void validate(Instant expiresAt) {
        if (expiresAt == null) {
            return;
        }
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
        }
    }

    /**
     * Resolves the expiry to store for a new link, using a single clock reading.
     * A supplied value must be in the future and, when a maximum is configured, not
     * later than now + maximum (exactly the boundary is accepted). A supplied value is
     * returned unchanged. When omitted, now + default lifetime is returned, or null
     * if no default is configured. Error reasons never echo the input.
     */
    public Instant resolveForCreate(Instant requested) {
        Instant now = clock.instant();
        if (requested != null) {
            if (!requested.isAfter(now)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
            }
            if (maxLifetime != null && requested.isAfter(now.plus(maxLifetime))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expiresAt exceeds the maximum allowed lifetime");
            }
            return requested;
        }
        if (defaultLifetime != null) {
            return now.plus(defaultLifetime);
        }
        return null;
    }

    /**
     * Remaining time until expiry. Empty when the link never expires. When the
     * expiry has already passed, returns a zero duration (never negative), so
     * callers can treat a non-positive value as expired.
     */
    public Optional<Duration> timeToExpiry(Instant expiresAt) {
        if (expiresAt == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        if (remaining.isNegative()) {
            return Optional.of(Duration.ZERO);
        }
        return Optional.of(remaining);
    }
}
