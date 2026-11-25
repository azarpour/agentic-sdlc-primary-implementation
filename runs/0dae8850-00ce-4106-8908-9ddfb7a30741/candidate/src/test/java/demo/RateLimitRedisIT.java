package demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test of the real RateLimiter against a real Redis (Testcontainers).
 * No Postgres and no Spring context. The clock is fixed, so the window-start key stays constant;
 * recovery therefore proves that the Lua INCR+EXPIRE script set a real TTL that Redis enforced.
 * This file makes no claim about test results; outcomes are only valid when actually executed.
 */
@Testcontainers
class RateLimitRedisIT {

    private static final int LIMIT = 2;
    private static final long WINDOW_SECONDS = 3L;
    /** Not aligned to the window start, so retryAfter is shorter than the full window. */
    private static final long NOW_SECONDS = 1_700_000_000L;
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    private ShortenerProperties properties;
    private SimpleMeterRegistry registry;
    private RateLimiter limiter;
    private String ip;
    private long windowStart;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        properties = new ShortenerProperties();
        properties.getRatelimit().getCreate().setLimit(LIMIT);
        properties.getRatelimit().getCreate().setWindow(Duration.ofSeconds(WINDOW_SECONDS));
        properties.getRatelimit().getRedirect().setLimit(LIMIT);
        properties.getRatelimit().getRedirect().setWindow(Duration.ofSeconds(WINDOW_SECONDS));

        registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW_SECONDS), ZoneOffset.UTC);
        limiter = new RateLimiter(template, properties, clock, new Metrics(registry));

        int n = IP_SEQUENCE.incrementAndGet();
        ip = "10.1." + (n / 250) + "." + ((n % 250) + 1);
        windowStart = (NOW_SECONDS / WINDOW_SECONDS) * WINDOW_SECONDS;
    }

    @AfterEach
    void cleanUp() {
        Set<String> keys = template.keys("sl:rl:*:" + ip + ":*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    private String key(String op, String clientIp) {
        return "sl:rl:" + op + ":" + clientIp + ":" + windowStart;
    }

    private void assertNoFallback() {
        assertThat(registry.counter("shortener.redis.fallback", "operation", "ratelimit").count()).isEqualTo(0.0);
    }

    private void awaitKeyGone(String key) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(WINDOW_SECONDS + 5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(template.hasKey(key)).isFalse());
    }

    @Test
    void rejectsRequestsAboveTheLimitWithRetryAfter() {
        for (int i = 1; i <= LIMIT; i++) {
            RateLimiter.Decision decision = limiter.check("create", ip);
            assertThat(decision.allowed()).as("request " + i).isTrue();
            assertThat(decision.retryAfterSeconds()).isEqualTo(0L);
        }

        RateLimiter.Decision rejected = limiter.check("create", ip);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, WINDOW_SECONDS);

        for (int i = 0; i < 3; i++) {
            RateLimiter.Decision again = limiter.check("create", ip);
            assertThat(again.allowed()).isFalse();
            assertThat(again.retryAfterSeconds()).isBetween(1L, WINDOW_SECONDS);
        }
        assertNoFallback();
    }

    @Test
    void keyFollowsContractAndCountsRequestsAboveTheLimit() {
        for (int i = 0; i < 4; i++) {
            limiter.check("create", ip);
        }

        Set<String> keys = template.keys("sl:rl:create:" + ip + ":*");
        assertThat(keys).containsExactly(key("create", ip));
        assertThat(template.opsForValue().get(key("create", ip))).isEqualTo("4");
        assertNoFallback();
    }

    @Test
    void luaScriptSetsRealTtlAndRedisExpiresTheKey() {
        String key = key("create", ip);

        assertThat(limiter.check("create", ip).allowed()).isTrue();

        Long ttl = template.getExpire(key);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(WINDOW_SECONDS);

        awaitKeyGone(key);
        assertThat(template.hasKey(key)).isFalse();
        assertNoFallback();
    }

    @Test
    void recoversAfterTheKeyExpiresAndCountsFromOneAgain() {
        String key = key("create", ip);

        for (int i = 0; i < LIMIT; i++) {
            assertThat(limiter.check("create", ip).allowed()).isTrue();
        }
        assertThat(limiter.check("create", ip).allowed()).isFalse();

        awaitKeyGone(key);

        RateLimiter.Decision first = limiter.check("create", ip);
        assertThat(first.allowed()).isTrue();
        assertThat(first.retryAfterSeconds()).isEqualTo(0L);
        assertThat(template.opsForValue().get(key)).isEqualTo("1");
        Long ttl = template.getExpire(key);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(WINDOW_SECONDS);

        assertThat(limiter.check("create", ip).allowed()).isTrue();
        RateLimiter.Decision rejectedAgain = limiter.check("create", ip);
        assertThat(rejectedAgain.allowed()).isFalse();
        assertThat(rejectedAgain.retryAfterSeconds()).isBetween(1L, WINDOW_SECONDS);
        assertNoFallback();
    }

    @Test
    void otherIpAndOtherOperationKeepIndependentCounters() {
        String otherIp = ip + "1";
        try {
            for (int i = 0; i < LIMIT; i++) {
                assertThat(limiter.check("create", ip).allowed()).isTrue();
            }
            assertThat(limiter.check("create", ip).allowed()).isFalse();

            RateLimiter.Decision otherClient = limiter.check("create", otherIp);
            assertThat(otherClient.allowed()).isTrue();
            assertThat(template.opsForValue().get(key("create", otherIp))).isEqualTo("1");

            RateLimiter.Decision redirect = limiter.check("redirect", ip);
            assertThat(redirect.allowed()).isTrue();
            assertThat(redirect.retryAfterSeconds()).isEqualTo(0L);
            assertThat(template.opsForValue().get(key("redirect", ip))).isEqualTo("1");
            assertThat(template.opsForValue().get(key("create", ip))).isEqualTo(String.valueOf(LIMIT + 1));
            assertNoFallback();
        } finally {
            template.delete(key("create", otherIp));
        }
    }
}
