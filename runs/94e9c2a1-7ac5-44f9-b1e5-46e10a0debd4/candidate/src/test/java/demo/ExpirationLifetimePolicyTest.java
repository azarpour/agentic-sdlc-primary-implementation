package demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Focused tests for the default (30d) and maximum (90d) link lifetime.
 * Uses a fixed clock and mocked collaborators: no Spring context, Redis or database.
 * This file makes no claim about results; outcomes are only valid when actually executed.
 */
class ExpirationLifetimePolicyTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final Duration DEFAULT = Duration.ofDays(30);
    private static final Duration MAX = Duration.ofDays(90);
    private static final String URL = "https://example.com/page";
    private static final String KEY = "key-1";
    private static final String CODE = "code00000001";

    private Clock clock;
    private ShortenerProperties properties;
    private ExpirationPolicy policy;
    private LinkRepository links;
    private IdempotencyRepository idempotency;
    private RedisCacheService cache;
    private CodeGenerator generator;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private SimpleMeterRegistry registry;
    private UrlService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        properties = configured(DEFAULT, MAX);
        policy = new ExpirationPolicy(clock, properties);

        links = mock(LinkRepository.class);
        idempotency = mock(IdempotencyRepository.class);
        cache = mock(RedisCacheService.class);
        generator = mock(CodeGenerator.class);
        jdbc = mock(JdbcTemplate.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        registry = new SimpleMeterRegistry();

        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(idempotency.find(anyString(), any())).thenReturn(Optional.empty());
        when(generator.next()).thenReturn(CODE);

        service = buildService(properties, policy);
    }

    // ---------------------------------------------------------------- helpers

    private static ShortenerProperties configured(Duration defaultLifetime, Duration maxLifetime) {
        ShortenerProperties p = new ShortenerProperties();
        p.getExpiration().setDefaultLifetime(defaultLifetime);
        p.getExpiration().setMaxLifetime(maxLifetime);
        return p;
    }

    private UrlService buildService(ShortenerProperties props, ExpirationPolicy expirationPolicy) {
        return new UrlService(
                links,
                idempotency,
                cache,
                expirationPolicy,
                new AliasPolicy(props),
                generator,
                new Metrics(registry),
                props,
                clock,
                jdbc,
                txManager);
    }

    private static UrlService.CreateCommand command(String url, String alias, Instant expiresAt) {
        return new UrlService.CreateCommand(url, alias, expiresAt);
    }

    private static void assertStatus(int expected, Executable executable) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, executable);
        assertEquals(expected, ex.getStatusCode().value());
    }

    private double createCount(String outcome) {
        return registry.counter("shortener.create", "outcome", outcome).count();
    }

    // ------------------------------------------------------- policy: default

    @Test
    void defaultLifetimeIsAppliedWhenExpiresAtIsOmitted() {
        Instant resolved = policy.resolveForCreate(null);

        assertEquals(NOW.plus(DEFAULT), resolved);
        assertEquals(NOW.plus(Duration.ofHours(720)), resolved);
    }

    @Test
    void noConfigurationAndNoExpiresAtResolvesToNull() {
        ExpirationPolicy unrestricted = new ExpirationPolicy(clock);

        assertNull(unrestricted.resolveForCreate(null));
    }

    @Test
    void policyWithUnconfiguredPropertiesResolvesToNull() {
        ExpirationPolicy unrestricted = new ExpirationPolicy(clock, new ShortenerProperties());

        assertNull(unrestricted.resolveForCreate(null));
        assertEquals(NOW.plus(Duration.ofDays(365)), unrestricted.resolveForCreate(NOW.plus(Duration.ofDays(365))));
    }

    // --------------------------------------------------------- policy: maximum

    @Test
    void exactlyMaximumLifetimeIsAccepted() {
        Instant requested = NOW.plus(MAX);

        assertEquals(requested, policy.resolveForCreate(requested));
    }

    @Test
    void maximumLifetimePlusOneNanosecondIsRejectedWith400() {
        Instant requested = NOW.plus(MAX).plusNanos(1);

        assertStatus(400, () -> policy.resolveForCreate(requested));
    }

    @Test
    void farBeyondMaximumIsRejectedWith400() {
        assertStatus(400, () -> policy.resolveForCreate(NOW.plus(Duration.ofDays(365))));
    }

    @Test
    void expiresAtEqualToNowIsRejectedWith400() {
        assertStatus(400, () -> policy.resolveForCreate(NOW));
    }

    @Test
    void pastExpiresAtIsRejectedWith400() {
        assertStatus(400, () -> policy.resolveForCreate(NOW.minusSeconds(1)));
        assertStatus(400, () -> policy.resolveForCreate(NOW.minusNanos(1)));
        assertStatus(400, () -> policy.resolveForCreate(Instant.EPOCH));
    }

    @Test
    void futureExpiresAtWithinMaximumIsReturnedUnchanged() {
        Instant requested = NOW.plus(Duration.ofDays(10));

        assertEquals(requested, policy.resolveForCreate(requested));
        assertEquals(NOW.plusNanos(1), policy.resolveForCreate(NOW.plusNanos(1)));
    }

    @Test
    void maximumIsInactiveWithClockOnlyConstructor() {
        ExpirationPolicy unrestricted = new ExpirationPolicy(clock);
        Instant requested = NOW.plus(Duration.ofDays(365));

        assertEquals(requested, unrestricted.resolveForCreate(requested));
    }

    @Test
    void validateAloneNeverEnforcesTheMaximum() {
        assertDoesNotThrow(() -> policy.validate(NOW.plus(Duration.ofDays(365))));
        assertDoesNotThrow(() -> policy.validate(NOW.plus(MAX).plusNanos(1)));
        assertStatus(400, () -> policy.validate(NOW));
    }

    @Test
    void rejectionReasonsDoNotEchoTheSuppliedTimestamp() {
        Instant tooFar = NOW.plus(MAX).plusNanos(1);
        ResponseStatusException max = assertThrows(ResponseStatusException.class,
                () -> policy.resolveForCreate(tooFar));
        assertNotNull(max.getReason());
        assertFalse(max.getReason().contains(tooFar.toString()));

        Instant past = NOW.minusSeconds(12345);
        ResponseStatusException pastEx = assertThrows(ResponseStatusException.class,
                () -> policy.resolveForCreate(past));
        assertNotNull(pastEx.getReason());
        assertFalse(pastEx.getReason().contains(past.toString()));
    }

    // ------------------------------------------------------------- properties

    @Test
    void newPropertiesAreUnrestricted() {
        ShortenerProperties fresh = new ShortenerProperties();

        assertNotNull(fresh.getExpiration());
        assertNull(fresh.getExpiration().getDefaultLifetime());
        assertNull(fresh.getExpiration().getMaxLifetime());
    }

    @Test
    void settingNullExpirationGivesFreshUnrestrictedSettings() {
        ShortenerProperties p = configured(DEFAULT, MAX);
        p.setExpiration(null);

        assertNotNull(p.getExpiration());
        assertNull(p.getExpiration().getDefaultLifetime());
        assertNull(p.getExpiration().getMaxLifetime());
    }

    @Test
    void zeroAndNegativeDurationsAreRejected() {
        ShortenerProperties.Expiration expiration = new ShortenerProperties().getExpiration();

        assertThrows(IllegalArgumentException.class, () -> expiration.setDefaultLifetime(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> expiration.setDefaultLifetime(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> expiration.setMaxLifetime(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> expiration.setMaxLifetime(Duration.ofDays(-90)));
    }

    @Test
    void defaultGreaterThanMaximumFailsValidation() {
        ShortenerProperties bad = configured(Duration.ofDays(91), MAX);

        assertThrows(IllegalArgumentException.class, () -> bad.getExpiration().validate());
    }

    @Test
    void defaultGreaterThanMaximumFailsPolicyConstruction() {
        ShortenerProperties bad = configured(Duration.ofDays(91), MAX);

        assertThrows(IllegalArgumentException.class, () -> new ExpirationPolicy(clock, bad));
    }

    @Test
    void defaultEqualToMaximumIsAccepted() {
        ShortenerProperties equal = configured(MAX, MAX);

        assertDoesNotThrow(() -> equal.getExpiration().validate());
        ExpirationPolicy equalPolicy = assertDoesNotThrow(() -> new ExpirationPolicy(clock, equal));
        assertEquals(NOW.plus(MAX), equalPolicy.resolveForCreate(null));
    }

    @Test
    void validConfigurationPassesValidation() {
        assertDoesNotThrow(() -> properties.getExpiration().validate());
        assertEquals(DEFAULT, properties.getExpiration().getDefaultLifetime());
        assertEquals(MAX, properties.getExpiration().getMaxLifetime());
    }

    // ------------------------------------------------------- service: create

    @Test
    void createWithoutExpiresAtStoresDefaultLifetime() {
        UrlService.CreateResult result = service.create(command(URL, null, null), null);

        assertEquals(CODE, result.code());
        assertFalse(result.replayed());
        verify(links).insert(CODE, URL, NOW.plus(Duration.ofHours(720)));
        assertEquals(1.0, createCount("created"));
    }

    @Test
    void createWithAliasAndWithoutExpiresAtStoresDefaultLifetime() {
        UrlService.CreateResult result = service.create(command(URL, "my-alias", null), null);

        assertEquals("my-alias", result.code());
        verify(links).insert("my-alias", URL, NOW.plus(DEFAULT));
    }

    @Test
    void idempotencyRecordUsesHashOfTheOriginalCommandWithoutExpiry() {
        UrlService.CreateCommand original = command(URL, null, null);

        service.create(original, KEY);

        verify(links).insert(CODE, URL, NOW.plus(DEFAULT));
        verify(idempotency).insert(KEY, UrlService.payloadHash(command(URL, null, null)), CODE);
        // The hash must not be derived from the default-resolved expiry.
        assertNotEquals(UrlService.payloadHash(command(URL, null, NOW.plus(DEFAULT))),
                UrlService.payloadHash(original));
    }

    @Test
    void createWithExactMaximumIsStoredUnchanged() {
        Instant requested = NOW.plus(MAX);

        UrlService.CreateResult result = service.create(command(URL, null, requested), null);

        assertEquals(CODE, result.code());
        verify(links).insert(CODE, URL, requested);
    }

    @Test
    void createBeyondMaximumReturns400WithoutInsertAndCountsInvalid() {
        Instant requested = NOW.plus(MAX).plusNanos(1);

        assertStatus(400, () -> service.create(command(URL, null, requested), null));

        verifyNoInteractions(links);
        verifyNoInteractions(idempotency);
        assertEquals(1.0, createCount("invalid"));
        assertEquals(0.0, createCount("created"));
    }

    @Test
    void createBeyondMaximumWithKeyWritesNoIdempotencyRecordAndNoLink() {
        Instant requested = NOW.plus(MAX).plusNanos(1);

        assertStatus(400, () -> service.create(command(URL, null, requested), KEY));

        verifyNoInteractions(links);
        verify(idempotency, never()).insert(anyString(), anyString(), anyString());
        assertEquals(1.0, createCount("invalid"));
    }

    @Test
    void createWithFutureExpiresAtWithinMaximumIsStoredUnchanged() {
        Instant requested = NOW.plus(Duration.ofDays(10));

        service.create(command(URL, null, requested), null);

        verify(links).insert(CODE, URL, requested);
    }

    @Test
    void pastOrEqualExpiresAtStillReturns400BeforeAnyIdempotencyLookup() {
        assertStatus(400, () -> service.create(command(URL, null, NOW), KEY));
        assertStatus(400, () -> service.create(command(URL, null, NOW.minusSeconds(1)), KEY));

        verifyNoInteractions(links);
        verifyNoInteractions(idempotency);
        assertEquals(2.0, createCount("invalid"));
    }

    @Test
    void withoutLifetimeConfigurationCreateStillStoresNullExpiry() {
        ShortenerProperties plain = new ShortenerProperties();
        UrlService plainService = buildService(plain, new ExpirationPolicy(clock));

        plainService.create(command(URL, null, null), null);

        verify(links).insert(CODE, URL, null);
    }

    @Test
    void withoutLifetimeConfigurationLongExpiryIsStillAccepted() {
        ShortenerProperties plain = new ShortenerProperties();
        UrlService plainService = buildService(plain, new ExpirationPolicy(clock, plain));
        Instant requested = NOW.plus(Duration.ofDays(365));

        plainService.create(command(URL, null, requested), null);

        verify(links).insert(CODE, URL, requested);
    }

    // ------------------------------------------------- service: idempotency

    @Test
    void replayWithSameKeyAndPayloadBeyondMaximumStillReturnsOriginalCode() {
        UrlService.CreateCommand cmd = command(URL, null, NOW.plus(Duration.ofDays(100)));
        String hash = UrlService.payloadHash(cmd);
        when(idempotency.find(anyString(), any()))
                .thenReturn(Optional.of(new IdempotencyRepository.IdemRecord(KEY, hash, "orig00000001")));

        UrlService.CreateResult result = service.create(cmd, KEY);

        assertEquals("orig00000001", result.code());
        assertTrue(result.replayed());
        verifyNoInteractions(links);
        verifyNoInteractions(generator);
        verify(idempotency, never()).insert(anyString(), anyString(), anyString());
        assertEquals(1.0, createCount("replayed"));
        assertEquals(0.0, createCount("invalid"));
    }

    @Test
    void replayWithoutExpiresAtReturnsOriginalCodeAndDoesNotInsertAgain() {
        UrlService.CreateCommand cmd = command(URL, null, null);
        String hash = UrlService.payloadHash(cmd);
        when(idempotency.find(anyString(), any()))
                .thenReturn(Optional.of(new IdempotencyRepository.IdemRecord(KEY, hash, "orig00000001")));

        UrlService.CreateResult result = service.create(cmd, KEY);

        assertEquals("orig00000001", result.code());
        assertTrue(result.replayed());
        verifyNoInteractions(links);
    }

    @Test
    void sameKeyWithDifferentPayloadReturns409() {
        String otherHash = UrlService.payloadHash(command("https://other.example.com/", null, null));
        when(idempotency.find(anyString(), any()))
                .thenReturn(Optional.of(new IdempotencyRepository.IdemRecord(KEY, otherHash, "orig00000001")));

        assertStatus(409, () -> service.create(command(URL, null, null), KEY));

        verifyNoInteractions(links);
        assertEquals(1.0, createCount("conflict"));
    }

    @Test
    void sameKeyWithDifferentPayloadBeyondMaximumReturns409NotMaximum400() {
        String otherHash = UrlService.payloadHash(command("https://other.example.com/", null, null));
        when(idempotency.find(anyString(), any()))
                .thenReturn(Optional.of(new IdempotencyRepository.IdemRecord(KEY, otherHash, "orig00000001")));

        assertStatus(409, () -> service.create(command(URL, null, NOW.plus(Duration.ofDays(100))), KEY));

        verifyNoInteractions(links);
        assertEquals(1.0, createCount("conflict"));
    }

    @Test
    void maximumCheckRunsOnlyAfterIdempotencyLookupFindsNoRecord() {
        Instant requested = NOW.plus(MAX).plusNanos(1);

        assertStatus(400, () -> service.create(command(URL, null, requested), KEY));

        verify(idempotency).find(anyString(), any());
        verifyNoInteractions(links);
    }
}
