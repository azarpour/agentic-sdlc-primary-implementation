package demo;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ExpirationPolicyTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

    private static ExpirationPolicy policyAt(Instant now) {
        return new ExpirationPolicy(Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void assertBadRequest(ExpirationPolicy policy, Instant expiresAt) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> policy.validate(expiresAt));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void nullExpirationIsAllowed() {
        assertDoesNotThrow(() -> policyAt(NOW).validate(null));
    }

    @Test
    void futureExpirationIsAccepted() {
        ExpirationPolicy policy = policyAt(NOW);
        assertDoesNotThrow(() -> policy.validate(NOW.plusSeconds(60)));
        assertDoesNotThrow(() -> policy.validate(NOW.plus(Duration.ofDays(365))));
    }

    @Test
    void expirationOneNanosecondInTheFutureIsAccepted() {
        assertDoesNotThrow(() -> policyAt(NOW).validate(NOW.plusNanos(1)));
    }

    @Test
    void expirationEqualToNowIsRejected() {
        assertBadRequest(policyAt(NOW), NOW);
    }

    @Test
    void expirationOneNanosecondInThePastIsRejected() {
        assertBadRequest(policyAt(NOW), NOW.minusNanos(1));
    }

    @Test
    void pastExpirationIsRejected() {
        ExpirationPolicy policy = policyAt(NOW);
        assertBadRequest(policy, NOW.minusSeconds(1));
        assertBadRequest(policy, NOW.minus(Duration.ofDays(30)));
        assertBadRequest(policy, Instant.EPOCH);
    }

    @Test
    void validationUsesTheInjectedClock() {
        Instant candidate = NOW.plusSeconds(3600);
        assertDoesNotThrow(() -> policyAt(NOW).validate(candidate));
        assertBadRequest(policyAt(NOW.plusSeconds(7200)), candidate);
        assertBadRequest(policyAt(candidate), candidate);
    }

    @Test
    void timeToExpiryIsEmptyWhenNoExpirationIsSet() {
        Optional<Duration> ttl = policyAt(NOW).timeToExpiry(null);
        assertNotNull(ttl);
        assertTrue(ttl.isEmpty());
    }

    @Test
    void timeToExpiryIsDistanceFromClockNowForFutureInstants() {
        ExpirationPolicy policy = policyAt(NOW);
        Optional<Duration> ttl = policy.timeToExpiry(NOW.plus(Duration.ofMinutes(10)));
        assertTrue(ttl.isPresent());
        assertEquals(Duration.ofMinutes(10), ttl.get());

        Optional<Duration> oneSecond = policy.timeToExpiry(NOW.plusSeconds(1));
        assertTrue(oneSecond.isPresent());
        assertEquals(Duration.ofSeconds(1), oneSecond.get());
    }

    @Test
    void timeToExpiryShrinksAsTheClockAdvances() {
        Instant expiresAt = NOW.plus(Duration.ofHours(1));
        Duration early = policyAt(NOW).timeToExpiry(expiresAt).orElseThrow();
        Duration later = policyAt(NOW.plus(Duration.ofMinutes(45))).timeToExpiry(expiresAt).orElseThrow();
        assertEquals(Duration.ofHours(1), early);
        assertEquals(Duration.ofMinutes(15), later);
        assertTrue(later.compareTo(early) < 0);
    }

    @Test
    void timeToExpiryIsNeverPositiveForExpiredInstants() {
        ExpirationPolicy policy = policyAt(NOW);
        for (Instant expired : new Instant[] {NOW, NOW.minusSeconds(1), NOW.minus(Duration.ofDays(1))}) {
            Optional<Duration> ttl = policy.timeToExpiry(expired);
            assertNotNull(ttl);
            ttl.ifPresent(d -> assertFalse(d.isNegative() == false && !d.isZero(),
                    "expired instant must not yield a positive time to expiry: " + d));
        }
    }
}
