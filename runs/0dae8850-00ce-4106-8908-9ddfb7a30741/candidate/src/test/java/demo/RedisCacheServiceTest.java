package demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for RedisCacheService with a mocked StringRedisTemplate (no Redis, no Spring context).
 * This file makes no claim about test results; outcomes are only valid when actually executed.
 */
class RedisCacheServiceTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final String CODE = "abc123";
    private static final String KEY = "sl:link:abc123";
    private static final String URL = "https://example.com/page?x=1";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private SimpleMeterRegistry registry;
    private ShortenerProperties properties;
    private RedisCacheService cache;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger serviceLogger;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        registry = new SimpleMeterRegistry();
        properties = new ShortenerProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        cache = new RedisCacheService(redis, properties, clock, new Metrics(registry));

        serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RedisCacheService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
    }

    private double fallbackCount(String operation) {
        return registry.counter("shortener.redis.fallback", "operation", operation).count();
    }

    // ------------------------------------------------------------------ get

    @Test
    void getMissReturnsEmpty() {
        when(ops.get(KEY)).thenReturn(null);

        assertTrue(cache.get(CODE).isEmpty());
        assertEquals(0.0, fallbackCount("cache_get"));
    }

    @Test
    void getHitWithoutExpiryParsesUrlAndNullExpiry() {
        when(ops.get(KEY)).thenReturn("none|" + URL);

        Optional<RedisCacheService.CachedLink> result = cache.get(CODE);

        assertTrue(result.isPresent());
        assertEquals(URL, result.get().url());
        assertNull(result.get().expiresAt());
    }

    @Test
    void getHitWithExpiryParsesEpochMillis() {
        Instant expiresAt = NOW.plusSeconds(600);
        when(ops.get(KEY)).thenReturn(expiresAt.toEpochMilli() + "|" + URL);

        Optional<RedisCacheService.CachedLink> result = cache.get(CODE);

        assertTrue(result.isPresent());
        assertEquals(URL, result.get().url());
        assertEquals(expiresAt, result.get().expiresAt());
    }

    @Test
    void getKeepsPipeCharactersInsideTheUrl() {
        String url = "https://example.com/?a=b|c|d";
        when(ops.get(KEY)).thenReturn("none|" + url);

        Optional<RedisCacheService.CachedLink> result = cache.get(CODE);

        assertTrue(result.isPresent());
        assertEquals(url, result.get().url());
    }

    @Test
    void getMalformedEntryWithoutSeparatorIsMissAndEvicted() {
        when(ops.get(KEY)).thenReturn("garbage");

        assertTrue(cache.get(CODE).isEmpty());
        verify(redis).delete(KEY);
    }

    @Test
    void getMalformedEntryWithNonNumericExpiryIsMissAndEvicted() {
        when(ops.get(KEY)).thenReturn("abc|" + URL);

        assertTrue(cache.get(CODE).isEmpty());
        verify(redis).delete(KEY);
    }

    @Test
    void getMalformedEntryWithEmptyUrlIsMissAndEvicted() {
        when(ops.get(KEY)).thenReturn("none|");

        assertTrue(cache.get(CODE).isEmpty());
        verify(redis).delete(KEY);
    }

    @Test
    void getMalformedEntryWithEmptyExpiryIsMissAndEvicted() {
        when(ops.get(KEY)).thenReturn("|" + URL);

        assertTrue(cache.get(CODE).isEmpty());
        verify(redis).delete(KEY);
    }

    @Test
    void getWhenRedisThrowsReturnsEmptyCountsFallbackAndLogsWarn() {
        when(ops.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        Optional<RedisCacheService.CachedLink> result = assertDoesNotThrow(() -> cache.get(CODE));

        assertTrue(result.isEmpty());
        assertEquals(1.0, fallbackCount("cache_get"));
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    void warnLogContainsCodeButNeverTheUrlOrExceptionMessage() {
        when(ops.get(KEY)).thenThrow(new RuntimeException("leak https://example.com/secret-path"));

        cache.get(CODE);

        assertFalse(appender.list.isEmpty());
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertTrue(message.contains(CODE), "log should reference the code");
            assertFalse(message.contains("secret-path"), "log must not contain exception message");
            assertFalse(message.contains("example.com"), "log must not contain URLs");
        }
    }

    // ------------------------------------------------------------------ put

    @Test
    void putWithoutExpiryUsesConfiguredTtlAndNoneMarker() {
        cache.put(CODE, URL, null);

        verify(ops).set(KEY, "none|" + URL, Duration.ofMinutes(5));
    }

    @Test
    void putUsesConfiguredCacheTtlWhenCustomized() {
        properties.getCache().setTtl(Duration.ofSeconds(10));

        cache.put(CODE, URL, null);

        verify(ops).set(KEY, "none|" + URL, Duration.ofSeconds(10));
    }

    @Test
    void putWithExpirySoonerThanTtlBoundsTtlByTimeToExpiry() {
        Instant expiresAt = NOW.plusSeconds(30);

        cache.put(CODE, URL, expiresAt);

        verify(ops).set(KEY, expiresAt.toEpochMilli() + "|" + URL, Duration.ofSeconds(30));
    }

    @Test
    void putWithExpiryLaterThanTtlKeepsConfiguredTtl() {
        Instant expiresAt = NOW.plus(Duration.ofHours(2));

        cache.put(CODE, URL, expiresAt);

        verify(ops).set(KEY, expiresAt.toEpochMilli() + "|" + URL, Duration.ofMinutes(5));
    }

    @Test
    void putIsSkippedWhenAlreadyExpired() {
        cache.put(CODE, URL, NOW.minusSeconds(5));

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertEquals(0.0, fallbackCount("cache_put"));
    }

    @Test
    void putIsSkippedWhenExpiryEqualsNow() {
        cache.put(CODE, URL, NOW);

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void putIsSkippedWhenRemainingTimeIsBelowOneMillisecond() {
        cache.put(CODE, URL, NOW.plusNanos(500_000));

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void putWithNullUrlIsSkipped() {
        cache.put(CODE, null, null);

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertEquals(0.0, fallbackCount("cache_put"));
    }

    @Test
    void putWhenRedisThrowsDoesNotPropagateAndCountsFallback() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(eq(KEY), anyString(), any(Duration.class));

        assertDoesNotThrow(() -> cache.put(CODE, URL, null));

        assertEquals(1.0, fallbackCount("cache_put"));
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    }

    // ----------------------------------------------------------------- evict

    @Test
    void evictDeletesTheCacheKey() {
        cache.evict(CODE);

        verify(redis).delete(KEY);
        assertEquals(0.0, fallbackCount("cache_evict"));
    }

    @Test
    void evictWhenRedisThrowsDoesNotPropagateAndCountsFallback() {
        doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyString());

        assertDoesNotThrow(() -> cache.evict(CODE));

        assertEquals(1.0, fallbackCount("cache_evict"));
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    }

    // --------------------------------------------------- metrics robustness

    @Test
    void brokenMetricsNeverBreaksTheRequestPath() {
        Metrics brokenMetrics = mock(Metrics.class);
        doThrow(new IllegalStateException("metrics down")).when(brokenMetrics).redisFallback(anyString());
        RedisCacheService resilient = new RedisCacheService(
                redis, properties, Clock.fixed(NOW, ZoneOffset.UTC), brokenMetrics);
        when(ops.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(eq(KEY), anyString(), any(Duration.class));
        doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyString());

        assertTrue(assertDoesNotThrow(() -> resilient.get(CODE)).isEmpty());
        assertDoesNotThrow(() -> resilient.put(CODE, URL, null));
        assertDoesNotThrow(() -> resilient.evict(CODE));
    }

    @Test
    void cacheKeyPrefixMatchesContract() {
        assertEquals("sl:link:", RedisCacheService.KEY_PREFIX);
    }
}
