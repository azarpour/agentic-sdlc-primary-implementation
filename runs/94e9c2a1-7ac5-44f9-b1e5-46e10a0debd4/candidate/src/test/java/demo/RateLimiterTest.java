package demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Unit tests for RateLimiter with a mocked StringRedisTemplate (no Redis, no Spring context).
 * Time is fixed, so window and Retry-After computations are deterministic.
 * This file makes no claim about test results; outcomes are only valid when actually executed.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RateLimiterTest {

    /** 28333333 * 60 = 1_699_999_980, so the 60s window containing NOW starts 20s earlier. */
    private static final long WINDOW_START = 1_699_999_980L;
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final String IP = "10.0.0.1";

    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;
    private ShortenerProperties properties;
    private Metrics metrics;
    private RateLimiter limiter;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger limiterLogger;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        registry = new SimpleMeterRegistry();
        metrics = new Metrics(registry);
        properties = new ShortenerProperties();
        limiter = newLimiter(NOW);

        limiterLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RateLimiter.class);
        appender = new ListAppender<>();
        appender.start();
        limiterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        limiterLogger.detachAppender(appender);
        appender.stop();
    }

    // ---------------------------------------------------------------- helpers

    private RateLimiter newLimiter(Instant now) {
        return new RateLimiter(redis, properties, Clock.fixed(now, ZoneOffset.UTC), metrics);
    }

    /** Simulates the Lua INCR+EXPIRE script with an in-memory counter per key. */
    private Map<String, AtomicLong> stubCounters() {
        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(1);
            return counters.computeIfAbsent(keys.get(0), k -> new AtomicLong()).incrementAndGet();
        });
        return counters;
    }

    private void stubFixedCount(Long value) {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(value);
    }

    private String lastKey() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(redis, atLeastOnce()).execute(any(RedisScript.class), captor.capture(), any());
        List<String> keys = captor.getValue();
        assertEquals(1, keys.size());
        return keys.get(0);
    }

    private double fallbackCount(String operation) {
        return registry.counter("shortener.redis.fallback", "operation", operation).count();
    }

    private void setLimit(String op, int limit, Duration window) {
        ShortenerProperties.Limit l = properties.getRatelimit().limitFor(op);
        l.setLimit(limit);
        l.setWindow(window);
    }

    // ------------------------------------------------------- allow / reject

    @Test
    void requestsUpToTheLimitAreAllowed() {
        setLimit("create", 3, Duration.ofMinutes(1));
        stubCounters();

        for (int i = 1; i <= 3; i++) {
            RateLimiter.Decision decision = limiter.check("create", IP);
            assertTrue(decision.allowed(), "request " + i + " should be allowed");
            assertEquals(0L, decision.retryAfterSeconds());
        }
    }

    @Test
    void requestAboveTheLimitIsRejectedWithRetryAfter() {
        setLimit("create", 3, Duration.ofMinutes(1));
        stubCounters();

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.check("create", IP).allowed());
        }
        RateLimiter.Decision rejected = limiter.check("create", IP);

        assertFalse(rejected.allowed());
        // window [1_699_999_980, 1_700_000_040) and now = 1_700_000_000 -> 40 seconds left
        assertEquals(40L, rejected.retryAfterSeconds());
    }

    @Test
    void everyRequestAfterTheLimitStaysRejectedWithinTheWindow() {
        setLimit("create", 1, Duration.ofMinutes(1));
        stubCounters();

        assertTrue(limiter.check("create", IP).allowed());
        for (int i = 0; i < 5; i++) {
            RateLimiter.Decision decision = limiter.check("create", IP);
            assertFalse(decision.allowed());
            assertTrue(decision.retryAfterSeconds() >= 1);
        }
    }

    @Test
    void countExactlyAtTheLimitIsAllowedAndOneAboveIsRejected() {
        setLimit("redirect", 10, Duration.ofMinutes(1));

        stubFixedCount(10L);
        assertTrue(limiter.check("redirect", IP).allowed());

        stubFixedCount(11L);
        assertFalse(limiter.check("redirect", IP).allowed());
    }

    @Test
    void retryAfterIsAtLeastOneSecondInTheLastSecondOfTheWindow() {
        setLimit("create", 1, Duration.ofMinutes(1));
        stubFixedCount(2L);
        RateLimiter late = newLimiter(Instant.ofEpochSecond(WINDOW_START + 59));

        RateLimiter.Decision decision = late.check("create", IP);

        assertFalse(decision.allowed());
        assertEquals(1L, decision.retryAfterSeconds());
    }

    @Test
    void retryAfterIsFullWindowAtTheStartOfTheWindow() {
        setLimit("create", 1, Duration.ofMinutes(1));
        stubFixedCount(2L);
        RateLimiter start = newLimiter(Instant.ofEpochSecond(WINDOW_START));

        RateLimiter.Decision decision = start.check("create", IP);

        assertFalse(decision.allowed());
        assertEquals(60L, decision.retryAfterSeconds());
    }

    @Test
    void retryAfterIsAlwaysAnIntegerBetweenOneAndWindowSeconds() {
        setLimit("create", 1, Duration.ofMinutes(1));
        stubFixedCount(2L);
        for (long offset = 0; offset < 60; offset++) {
            RateLimiter at = newLimiter(Instant.ofEpochSecond(WINDOW_START + offset));
            long retry = at.check("create", IP).retryAfterSeconds();
            assertEquals(60 - offset, retry);
            assertTrue(retry >= 1 && retry <= 60);
        }
    }

    // ------------------------------------------------------ keys and windows

    @Test
    void keyFollowsContractOpIpAndWindowStart() {
        stubFixedCount(1L);

        limiter.check("create", IP);

        assertEquals("sl:rl:create:10.0.0.1:" + WINDOW_START, lastKey());
        assertEquals("sl:rl:", RateLimiter.KEY_PREFIX);
    }

    @Test
    void windowSecondsIsPassedToTheScriptForExpiry() {
        setLimit("redirect", 5, Duration.ofSeconds(30));
        stubFixedCount(1L);

        limiter.check("redirect", IP);

        verify(redis).execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.eq("30"));
    }

    @Test
    void customWindowChangesTheWindowStartInTheKey() {
        setLimit("redirect", 5, Duration.ofSeconds(30));
        stubFixedCount(1L);

        limiter.check("redirect", IP);

        // 1_700_000_000 / 30 = 56666666 -> 56666666 * 30 = 1_699_999_980
        assertEquals("sl:rl:redirect:10.0.0.1:1699999980", lastKey());
    }

    @Test
    void newWindowUsesNewKeyAndResetsTheCount() {
        setLimit("create", 1, Duration.ofMinutes(1));
        Map<String, AtomicLong> counters = stubCounters();

        RateLimiter first = newLimiter(Instant.ofEpochSecond(WINDOW_START + 5));
        assertTrue(first.check("create", IP).allowed());
        assertFalse(first.check("create", IP).allowed());

        RateLimiter next = newLimiter(Instant.ofEpochSecond(WINDOW_START + 65));
        assertTrue(next.check("create", IP).allowed());

        assertEquals(2, counters.size());
        assertTrue(counters.containsKey("sl:rl:create:10.0.0.1:" + WINDOW_START));
        assertTrue(counters.containsKey("sl:rl:create:10.0.0.1:" + (WINDOW_START + 60)));
    }

    @Test
    void differentIpsHaveIndependentCounters() {
        setLimit("create", 1, Duration.ofMinutes(1));
        stubCounters();

        assertTrue(limiter.check("create", "10.0.0.1").allowed());
        assertFalse(limiter.check("create", "10.0.0.1").allowed());
        assertTrue(limiter.check("create", "10.0.0.2").allowed());
    }

    @Test
    void createAndRedirectHaveIndependentCountersAndLimits() {
        setLimit("create", 1, Duration.ofMinutes(1));
        setLimit("redirect", 3, Duration.ofMinutes(1));
        stubCounters();

        assertTrue(limiter.check("create", IP).allowed());
        assertFalse(limiter.check("create", IP).allowed());

        assertTrue(limiter.check("redirect", IP).allowed());
        assertTrue(limiter.check("redirect", IP).allowed());
        assertTrue(limiter.check("redirect", IP).allowed());
        assertFalse(limiter.check("redirect", IP).allowed());
    }

    @Test
    void ipv6AddressIsKeptWithColons() {
        stubFixedCount(1L);

        limiter.check("create", "2001:db8::1");

        assertEquals("sl:rl:create:2001:db8::1:" + WINDOW_START, lastKey());
    }

    // ---------------------------------------------------------- ip hygiene

    @Test
    void unsafeCharactersInIpAreReplacedSoKeysCannotBePolluted() {
        stubFixedCount(1L);

        limiter.check("create", "10.0.0.1/../x");

        assertEquals("sl:rl:create:10.0.0.1_..__:" + WINDOW_START, lastKey());
    }

    @Test
    void spoofedKeyDelimitersCannotCollideWithAnotherOpKey() {
        stubFixedCount(1L);

        limiter.check("create", "1 sl:rl:redirect");

        String key = lastKey();
        assertTrue(key.startsWith("sl:rl:create:"));
        assertFalse(key.contains(" "));
        assertFalse(key.contains("sl:rl:redirect"));
    }

    @Test
    void nullOrBlankIpMapsToUnknown() {
        stubFixedCount(1L);

        limiter.check("create", null);
        assertEquals("sl:rl:create:unknown:" + WINDOW_START, lastKey());

        limiter.check("redirect", "   ");
        assertEquals("sl:rl:redirect:unknown:" + WINDOW_START, lastKey());
    }

    @Test
    void overlongIpIsTruncatedToSixtyFourCharacters() {
        stubFixedCount(1L);

        limiter.check("create", "a".repeat(200));

        String expected = "sl:rl:create:" + "a".repeat(64) + ":" + WINDOW_START;
        assertEquals(expected, lastKey());
    }

    // --------------------------------------------------------- configuration

    @Test
    void disabledLimiterAllowsEverythingWithoutTouchingRedis() {
        properties.getRatelimit().setEnabled(false);

        RateLimiter.Decision decision = limiter.check("create", IP);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.retryAfterSeconds());
        verifyNoInteractions(redis);
        assertEquals(0.0, fallbackCount("ratelimit"));
    }

    @Test
    void limitIsConfigurableThroughProperties() {
        setLimit("create", 2, Duration.ofMinutes(1));
        stubCounters();

        assertTrue(limiter.check("create", IP).allowed());
        assertTrue(limiter.check("create", IP).allowed());
        assertFalse(limiter.check("create", IP).allowed());

        setLimit("create", 5, Duration.ofMinutes(1));
        assertTrue(limiter.check("create", IP).allowed());
    }

    @Test
    void defaultLimitsMatchDocumentedDefaults() {
        assertEquals(30, properties.getRatelimit().getCreate().getLimit());
        assertEquals(Duration.ofMinutes(1), properties.getRatelimit().getCreate().getWindow());
        assertEquals(300, properties.getRatelimit().getRedirect().getLimit());
        assertEquals(Duration.ofMinutes(1), properties.getRatelimit().getRedirect().getWindow());
        assertTrue(properties.getRatelimit().isEnabled());
        assertFalse(properties.getRatelimit().isTrustForwardedHeaders());
    }

    @Test
    void invalidLimitConfigurationIsRejected() {
        ShortenerProperties.Limit limit = properties.getRatelimit().getCreate();
        assertThrows(IllegalArgumentException.class, () -> limit.setLimit(0));
        assertThrows(IllegalArgumentException.class, () -> limit.setWindow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> limit.setWindow(Duration.ofSeconds(-1)));
    }

    // ------------------------------------------------------------ fail open

    @Test
    void redisFailureFailsOpenCountsFallbackAndLogsWarn() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        RateLimiter.Decision decision = assertDoesNotThrow(() -> limiter.check("create", IP));

        assertTrue(decision.allowed());
        assertEquals(0L, decision.retryAfterSeconds());
        assertEquals(1.0, fallbackCount("ratelimit"));
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    void everyFailureIncrementsTheFallbackMetric() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        limiter.check("create", IP);
        limiter.check("redirect", IP);
        limiter.check("redirect", IP);

        assertEquals(3.0, fallbackCount("ratelimit"));
    }

    @Test
    void unexpectedRuntimeExceptionAlsoFailsOpen() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertTrue(assertDoesNotThrow(() -> limiter.check("redirect", IP)).allowed());
        assertEquals(1.0, fallbackCount("ratelimit"));
    }

    @Test
    void nullScriptResultFailsOpen() {
        stubFixedCount(null);

        RateLimiter.Decision decision = limiter.check("create", IP);

        assertTrue(decision.allowed());
        assertEquals(1.0, fallbackCount("ratelimit"));
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    void successfulChecksDoNotCountAsFallback() {
        stubCounters();

        limiter.check("create", IP);

        assertEquals(0.0, fallbackCount("ratelimit"));
        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    void warnLogNeverContainsTheExceptionMessage() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("leak password=hunter2 https://example.com/secret"));

        limiter.check("create", IP);

        assertFalse(appender.list.isEmpty());
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertFalse(message.contains("hunter2"));
            assertFalse(message.contains("secret"));
            assertFalse(message.contains("example.com"));
        }
    }

    @Test
    void brokenMetricsNeverBreaksTheFailOpenPath() {
        Metrics brokenMetrics = mock(Metrics.class);
        doThrow(new IllegalStateException("metrics down")).when(brokenMetrics).redisFallback(anyString());
        RateLimiter resilient = new RateLimiter(redis, properties, Clock.fixed(NOW, ZoneOffset.UTC), brokenMetrics);
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(assertDoesNotThrow(() -> resilient.check("create", IP)).allowed());
    }

    @Test
    void recoveryAfterOutageEnforcesLimitsAgain() {
        setLimit("create", 1, Duration.ofMinutes(1));
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(1L)
                .thenReturn(2L);

        assertTrue(limiter.check("create", IP).allowed());
        assertTrue(limiter.check("create", IP).allowed());
        assertFalse(limiter.check("create", IP).allowed());
        assertEquals(1.0, fallbackCount("ratelimit"));
    }

    // ------------------------------------------------------------ decision

    @Test
    void decisionRecordExposesItsComponents() {
        RateLimiter.Decision decision = new RateLimiter.Decision(false, 7L);

        assertFalse(decision.allowed());
        assertEquals(7L, decision.retryAfterSeconds());
        assertEquals(new RateLimiter.Decision(false, 7L), decision);
    }
}
