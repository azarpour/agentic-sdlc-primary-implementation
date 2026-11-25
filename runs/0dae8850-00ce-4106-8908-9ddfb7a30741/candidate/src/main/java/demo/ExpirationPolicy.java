package demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates optional link expiration timestamps and computes remaining lifetime.
 * A null expiresAt means the link never expires. A non-null value must be
 * strictly after the current instant (from the injected Clock), otherwise a
 * ResponseStatusException with status 400 is raised.
 */
@Component
public class ExpirationPolicy {

    private final Clock clock;

    public ExpirationPolicy(Clock clock) {
        this.clock = clock;
    }

    /** Accepts null; rejects an instant that is in the past or equal to now. */
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
