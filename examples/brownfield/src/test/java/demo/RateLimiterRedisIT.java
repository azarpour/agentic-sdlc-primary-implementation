package demo;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class RateLimiterRedisIT {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static final long NOW = 1700000000L;
    SimpleMeterRegistry registry;
    String ip;
    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }
    @AfterAll static void close() { if (factory != null) factory.destroy(); }
    @BeforeEach void setup() { registry = new SimpleMeterRegistry(); ip = "2001:db8:" + UUID.randomUUID().toString().replace("-", ""); }
    @AfterEach void checkFallback() { try { assertEquals(0.0, registry.counter("shortener.redis.fallback", "operation", "ratelimit").count()); } finally { registry.close(); } }
    RateLimiter limiter(int limit, int seconds) {
        var props = new ShortenerProperties();
        for (String op : List.of("create", "redirect")) {
            props.getRatelimit().limitFor(op).setLimit(limit);
            props.getRatelimit().limitFor(op).setWindow(Duration.ofSeconds(seconds));
        }
        return new RateLimiter(redis, props, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), new Metrics(registry));
    }
    String key(String op, String address, int seconds) { return RateLimiter.KEY_PREFIX + op + ":" + address + ":" + (NOW / seconds * seconds); }
    @Test void concurrentAtomicity() throws Exception {
        var limiter = limiter(5,60);
        var pool = Executors.newFixedThreadPool(20);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i=0;i<20;i++) futures.add(pool.submit(() -> { start.await(); return limiter.check("create",ip).allowed(); }));
            start.countDown();
            int allowed=0;
            for (var f:futures) if(f.get(10,TimeUnit.SECONDS)) allowed++;
            assertEquals(5,allowed);
            assertEquals("20",redis.opsForValue().get(key("create",ip,60)));
        } finally { pool.shutdownNow(); }
    }
    @Test void atomicTtl() {
        assertTrue(limiter(5,60).check("create",ip).allowed());
        Long ttl=redis.getExpire(key("create",ip,60),TimeUnit.MILLISECONDS);
        assertNotNull(ttl); assertTrue(ttl>0 && ttl<=60000);
    }
    @Test void rejectsExcess() {
        var l=limiter(5,60);
        for(int i=0;i<5;i++) assertTrue(l.check("create",ip).allowed());
        for(int i=0;i<4;i++) { var d=l.check("create",ip); assertFalse(d.allowed()); assertTrue(d.retryAfterSeconds()>=1 && d.retryAfterSeconds()<=60); }
    }
    @Test void expires() {
        assertTrue(limiter(1,1).check("create",ip).allowed());
        await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.FALSE.equals(redis.hasKey(key("create",ip,1))));
    }
    @Test void recoversAfterExpiry() {
        var l=limiter(1,1); assertTrue(l.check("create",ip).allowed());
        await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.FALSE.equals(redis.hasKey(key("create",ip,1))));
        assertTrue(l.check("create",ip).allowed()); assertEquals("1",redis.opsForValue().get(key("create",ip,1)));
    }
    @Test void independentKeys() {
        var l=limiter(1,60); assertTrue(l.check("create",ip).allowed()); assertFalse(l.check("create",ip).allowed());
        assertTrue(l.check("redirect",ip).allowed()); assertTrue(l.check("create",ip+"a").allowed());
        assertEquals("2",redis.opsForValue().get(key("create",ip,60)));
        assertEquals("1",redis.opsForValue().get(key("redirect",ip,60)));
        assertEquals("1",redis.opsForValue().get(key("create",ip+"a",60)));
    }
}
