package demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for UrlService with mocked collaborators (no Spring context, no database, no Redis).
 * Test outcomes are only valid when they are actually executed; this file makes no claim about results.
 */
class UrlServiceTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final String URL = "https://example.com/page";
    private static final String KEY = "key-1";

    private LinkRepository links;
    private IdempotencyRepository idempotency;
    private RedisCacheService cache;
    private CodeGenerator generator;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private SimpleMeterRegistry registry;
    private ShortenerProperties properties;
    private Clock clock;
    private UrlService service;

    @BeforeEach
    void setUp() {
        links = mock(LinkRepository.class);
        idempotency = mock(IdempotencyRepository.class);
        cache = mock(RedisCacheService.class);
        generator = mock(CodeGenerator.class);
        jdbc = mock(JdbcTemplate.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        registry = new SimpleMeterRegistry();
        properties = new ShortenerProperties();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(idempotency.find(anyString(), any())).thenReturn(Optional.empty());

        service = new UrlService(
                links,
                idempotency,
                cache,
                new ExpirationPolicy(clock),
                new AliasPolicy(properties),
                generator,
                new Metrics(registry),
                properties,
                clock,
                jdbc,
                txManager);
    }

    // ---------------------------------------------------------------- helpers

    private static void assertStatus(int expected, Executable executable) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, executable);
        assertEquals(expected, ex.getStatusCode().value());
    }

    private double createCount(String outcome) {
        return registry.counter("shortener.create", "outcome", outcome).count();
    }

    private double redirectCount(String result) {
        return registry.counter("shortener.redirect", "result", result).count();
    }

    private static UrlService.CreateCommand command(String url, String alias, Instant expiresAt) {
        return new UrlService.CreateCommand(url, alias, expiresAt);
    }

    private static DuplicateKeyException dup() {
        return new DuplicateKeyException("duplicate");
    }

    // ------------------------------------------------------ create: generated

    @Test
    void createWithGeneratedCodeInsertsOnce() {
        when(generator.next()).thenReturn("code00000001");

        UrlService.CreateResult result = service.create(command(URL, null, null), null);

        assertEquals("code00000001", result.code());
        assertFalse(result.replayed());
        verify(links, times(1)).insert("code00000001", URL, null);
        verifyNoInteractions(idempotency);
        assertEquals(1.0, createCount("created"));
    }

    @Test
    void createPassesExpirationToRepository() {
        Instant expiresAt = NOW.plusSeconds(3600);
        when(generator.next()).thenReturn("code00000001");

        service.create(command(URL, null, expiresAt), null);

        verify(links).insert("code00000001", URL, expiresAt);
    }

    @Test
    void collisionOnFirstAttemptSucceedsOnSecondWithFreshCode() {
        when(generator.next()).thenReturn("collide00001", "fresh0000002");
        doThrow(dup()).when(links).insert(eq("collide00001"), anyString(), any());

        UrlService.CreateResult result = service.create(command(URL, null, null), null);

        assertEquals("fresh0000002", result.code());
        assertFalse(result.replayed());
        verify(generator, times(2)).next();
        verify(links).insert("collide00001", URL, null);
        verify(links).insert("fresh0000002", URL, null);
        assertEquals(1.0, createCount("created"));
        assertEquals(0.0, createCount("exhausted"));
    }

    @Test
    void collisionsExhaustedAfterFiveAttemptsReturn503() {
        when(generator.next()).thenReturn("code00000001", "code00000002", "code00000003",
                "code00000004", "code00000005", "code00000006");
        doThrow(dup()).when(links).insert(anyString(), anyString(), any());

        assertStatus(503, () -> service.create(command(URL, null, null), null));

        assertEquals(5, UrlService.MAX_CODE_ATTEMPTS);
        verify(generator, times(5)).next();
        verify(links, times(5)).insert(anyString(), anyString(), any());
        assertEquals(1.0, createCount("exhausted"));
        assertEquals(0.0, createCount("created"));
    }

    @Test
    void successOnFifthAttemptStillCreates() {
        when(generator.next()).thenReturn("code00000001", "code00000002", "code00000003",
                "code00000004", "code00000005");
        for (String c : List.of("code00000001", "code00000002", "code00000003", "code00000004")) {
            doThrow(dup()).when(links).insert(eq(c), anyString(), any());
        }

        UrlService.CreateResult result = service.create(command(URL, null, null), null);

        assertEquals("code00000005", result.code());
        verify(links, times(5)).insert(anyString(), anyString(), any());
    }

    // ---------------------------------------------------------- create: alias

    @Test
    void createWithAliasUsesAliasAsCodeAndNeverGeneratesOne() {
        UrlService.CreateResult result = service.create(command(URL, "my-alias", null), null);

        assertEquals("my-alias", result.code());
        assertFalse(result.replayed());
        verify(links).insert("my-alias", URL, null);
        verifyNoInteractions(generator);
    }

    @Test
    void duplicateAliasReturns409WithSingleAttempt() {
        doThrow(dup()).when(links).insert(eq("taken"), anyString(), any());

        assertStatus(409, () -> service.create(command(URL, "taken", null), null));

        verify(links, times(1)).insert(eq("taken"), anyString(), any());
        verifyNoInteractions(generator);
        assertEquals(1.0, createCount("conflict"));
    }

    @Test
    void invalidAliasReturns400AndInsertsNothing() {
        assertStatus(400, () -> service.create(command(URL, "a b", null), null));
        assertStatus(400, () -> service.create(command(URL, "ab", null), null));
        assertStatus(400, () -> service.create(command(URL, "a".repeat(33), null), null));

        verifyNoInteractions(links);
        assertEquals(3.0, createCount("invalid"));
    }

    @Test
    void reservedAliasReturns400() {
        assertStatus(400, () -> service.create(command(URL, "api", null), null));
        assertStatus(400, () -> service.create(command(URL, "ACTUATOR", null), null));

        verifyNoInteractions(links);
    }

    // ------------------------------------------------- create: other validation

    @Test
    void invalidUrlsReturn400AndInsertNothing() {
        assertStatus(400, () -> service.create(command("javascript:alert(1)", null, null), null));
        assertStatus(400, () -> service.create(command("https://user:pass@example.com", null, null), null));
        assertStatus(400, () -> service.create(command("https:///nohost", null, null), null));
        assertStatus(400, () -> service.create(command(null, null, null), null));
        assertStatus(400, () -> service.create(command("https://example.com/" + "a".repeat(4100), null, null), null));

        verifyNoInteractions(links);
        verifyNoInteractions(generator);
    }

    @Test
    void pastOrEqualExpirationReturns400() {
        assertStatus(400, () -> service.create(command(URL, null, NOW.minusSeconds(1)), null));
        assertStatus(400, () -> service.create(command(URL, null, NOW), null));

        verifyNoInteractions(links);
    }

    @Test
    void nullCommandReturns400() {
        assertStatus(400, () -> service.create(null, null));
        verifyNoInteractions(links);
    }

    @Test
    void malformedIdempotencyKeyReturns400BeforeAnyDatabaseWork() {
        assertStatus(400, () -> service.create(command(URL, null, null), ""));
        assertStatus(400, () -> service.create(command(URL, null, null), "bad key with spaces"));
        assertStatus(400, () -> service.create(command(URL, null, null), "k".repeat(129)));

        verifyNoInteractions(links);
        verifyNoInteractions(idempotency);
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void newIdempotencyKeyStoresRecordWithPayloadHashInsideTheCreate() {
        when(generator.next()).thenReturn("code00000001");
        UrlService.CreateCommand cmd = command(URL, null, null);

        UrlService.CreateResult result = service.create(cmd, KEY);

        assertEquals("code00000001", result.code());
        assertFalse(result.replayed());
        verify(links).insert("code00000001", URL, null);
        verify(idempotency).insert(KEY, UrlService.payloadHash(cmd), "code00000001");
    }

    @Test
    void sameKeyAndSamePayloadReplaysOriginalCodeWithoutSecondInsert() {
        UrlService.CreateCommand cmd = command(URL, null, null);
        String hash = UrlService.payloadHash(cmd);
        when(idempotency.find(eq(KEY), any()))
                .thenReturn(Optional.of(new IdempotencyRepository.IdemRecord(KEY, hash, "original0001")));

        UrlService.CreateResult result = service.create(cmd, KEY);

        assertEquals("original0001", result.code());
        assertTrue(result.replayed());
        verifyNoInteractions(links);
        verifyNoInteractions(generator);
        verify(idempotency, never()).insert(anyString(), anyString(), anyString());
        assertEquals(1.0, createCount("replayed"));
    }

    @Test
    void replayAcceptsPaddedCharHashFromDatabase() {
        UrlService.CreateCommand cmd = command(URL, "my-alias", NOW.plusSeconds(60));
        String hash = UrlService.payloadHash(cmd);
        when(idempotency.find(eq(KEY), any()))
                .thenReturn(Optional.of(new IdempotencyRepository.IdemRecord(KEY, hash + "  ", "my-alias")));

        UrlService.CreateResult result = service.create(cmd, KEY);

        assertTrue(result.replayed());
        assertEquals("my-alias", result.code());
    }

    @Test
    void sameKeyWithDifferentPayloadReturns409AndInsertsNothing() {
        String otherHash = UrlService.payloadHash(command("https://other.example.com/", null, null));
        when(idempotency.find(eq(KEY), any()))
                .thenReturn(Optional.of(new IdempotencyRepository.IdemRecord(KEY, otherHash, "original0001")));

        assertStatus(409, () -> service.create(command(URL, null, null), KEY));

        verifyNoInteractions(links);
        verify(idempotency, never()).insert(anyString(), anyString(), anyString());
        assertEquals(1.0, createCount("conflict"));
    }

    @Test
    void differentAliasOrExpirationChangesThePayloadHash() {
        String base = UrlService.payloadHash(command(URL, null, null));
        assertNotEquals(base, UrlService.payloadHash(command(URL, "alias1", null)));
        assertNotEquals(base, UrlService.payloadHash(command(URL, null, NOW.plusSeconds(60))));
        assertNotEquals(base, UrlService.payloadHash(command("https://example.com/other", null, null)));
        assertEquals(base, UrlService.payloadHash(command(URL, null, null)));
        assertEquals(64, base.length());
        assertTrue(base.matches("[0-9a-f]{64}"));
    }

    @Test
    void idempotencyLookupUsesRetentionWindowCutoff() {
        when(generator.next()).thenReturn("code00000001");

        service.create(command(URL, null, null), KEY);

        ArgumentCaptor<Instant> notBefore = ArgumentCaptor.forClass(Instant.class);
        verify(idempotency).find(eq(KEY), notBefore.capture());
        assertEquals(NOW.minus(Duration.ofHours(24)), notBefore.getValue());
    }

    @Test
    void idempotencyRaceSameHashReplaysWinnerCode() {
        UrlService.CreateCommand cmd = command(URL, null, null);
        String hash = UrlService.payloadHash(cmd);
        when(generator.next()).thenReturn("loser0000001");
        doThrow(dup()).when(links).insert(eq("loser0000001"), anyString(), any());
        when(idempotency.find(eq(KEY), any())).thenReturn(
                Optional.empty(),
                Optional.of(new IdempotencyRepository.IdemRecord(KEY, hash, "winner000001")));

        UrlService.CreateResult result = service.create(cmd, KEY);

        assertEquals("winner000001", result.code());
        assertTrue(result.replayed());
        verify(links, times(1)).insert(anyString(), anyString(), any());
    }

    @Test
    void idempotencyRaceDifferentHashReturns409() {
        String otherHash = UrlService.payloadHash(command("https://other.example.com/", null, null));
        when(generator.next()).thenReturn("loser0000001");
        doThrow(dup()).when(links).insert(eq("loser0000001"), anyString(), any());
        when(idempotency.find(eq(KEY), any())).thenReturn(
                Optional.empty(),
                Optional.of(new IdempotencyRepository.IdemRecord(KEY, otherHash, "winner000001")));

        assertStatus(409, () -> service.create(command(URL, null, null), KEY));

        verify(links, times(1)).insert(anyString(), anyString(), any());
    }

    @Test
    void codeCollisionWithIdempotencyKeyRetriesAndStoresRecordForSuccessfulCode() {
        UrlService.CreateCommand cmd = command(URL, null, null);
        when(generator.next()).thenReturn("collide00001", "fresh0000002");
        doThrow(dup()).when(links).insert(eq("collide00001"), anyString(), any());

        UrlService.CreateResult result = service.create(cmd, KEY);

        assertEquals("fresh0000002", result.code());
        assertFalse(result.replayed());
        verify(idempotency).insert(KEY, UrlService.payloadHash(cmd), "fresh0000002");
        verify(idempotency, never()).insert(eq(KEY), anyString(), eq("collide00001"));
    }

    @Test
    void aliasConflictWithIdempotencyKeyAndNoWinnerReturns409() {
        doThrow(dup()).when(links).insert(eq("taken"), anyString(), any());

        assertStatus(409, () -> service.create(command(URL, "taken", null), KEY));
    }

    // --------------------------------------------------------------- redirect

    @Test
    void redirectOnCacheMissReturnsUrlAndIncrementsOnce() {
        when(links.incrementAndGetUrl("abc123")).thenReturn(Optional.of(URL));

        String target = service.redirect("abc123");

        assertEquals(URL, target);
        verify(links, times(1)).incrementAndGetUrl("abc123");
        assertEquals(1.0, redirectCount("found"));
        assertEquals(0.0, redirectCount("not_found"));
    }

    @Test
    void redirectOnCacheMissPopulatesCacheWithoutExpiry() throws Exception {
        when(links.incrementAndGetUrl("abc123")).thenReturn(Optional.of(URL));
        stubExpiryLookup("abc123", null);

        service.redirect("abc123");

        verify(cache).put("abc123", URL, null);
    }

    @Test
    void redirectOnCacheMissPopulatesCacheWithStoredExpiry() throws Exception {
        Instant expiresAt = NOW.plusSeconds(600);
        when(links.incrementAndGetUrl("abc123")).thenReturn(Optional.of(URL));
        stubExpiryLookup("abc123", Timestamp.from(expiresAt));

        service.redirect("abc123");

        verify(cache).put("abc123", URL, expiresAt);
    }

    private void stubExpiryLookup(String code, Timestamp value) {
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getTimestamp(1)).thenReturn(value);
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), org.mockito.Mockito.<RowMapper<Object>>any(), eq(code));
    }

    @Test
    void redirectNeverBreaksWhenCachePopulationLookupFails() {
        when(links.incrementAndGetUrl("abc123")).thenReturn(Optional.of(URL));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("boom"))
                .when(jdbc).query(anyString(), org.mockito.Mockito.<RowMapper<Object>>any(), eq("abc123"));

        assertEquals(URL, service.redirect("abc123"));
        verify(cache, never()).put(anyString(), anyString(), any());
    }

    @Test
    void redirectUnknownCodeReturns404AndDoesNotCacheOrEvict() {
        when(links.incrementAndGetUrl("missing1")).thenReturn(Optional.empty());

        assertStatus(404, () -> service.redirect("missing1"));

        verify(cache, never()).put(anyString(), anyString(), any());
        verify(cache, never()).evict(anyString());
        assertEquals(1.0, redirectCount("not_found"));
        assertEquals(0.0, redirectCount("found"));
    }

    @Test
    void redirectNullCodeReturns404WithoutTouchingDatabase() {
        assertStatus(404, () -> service.redirect(null));
        verifyNoInteractions(links);
    }

    @Test
    void cacheHitWithValidEntryStillRunsAtomicIncrement() {
        when(cache.get("abc123")).thenReturn(
                Optional.of(new RedisCacheService.CachedLink(URL, NOW.plusSeconds(60))));
        when(links.incrementAndGetUrl("abc123")).thenReturn(Optional.of(URL));

        assertEquals(URL, service.redirect("abc123"));

        verify(links, times(1)).incrementAndGetUrl("abc123");
        verify(cache, never()).put(anyString(), anyString(), any());
    }

    @Test
    void cacheHitWithExpiredEntryEvictsReturns404AndDoesNotIncrement() {
        when(cache.get("abc123")).thenReturn(
                Optional.of(new RedisCacheService.CachedLink(URL, NOW.minusSeconds(1))));

        assertStatus(404, () -> service.redirect("abc123"));

        verify(cache).evict("abc123");
        verifyNoInteractions(links);
        assertEquals(1.0, redirectCount("not_found"));
    }

    @Test
    void cacheEntryExpiringExactlyNowIsTreatedAsExpired() {
        when(cache.get("abc123")).thenReturn(
                Optional.of(new RedisCacheService.CachedLink(URL, NOW)));

        assertStatus(404, () -> service.redirect("abc123"));

        verify(cache).evict("abc123");
        verifyNoInteractions(links);
    }

    @Test
    void cacheHitButDatabaseSaysGoneEvictsAndReturns404() {
        when(cache.get("abc123")).thenReturn(
                Optional.of(new RedisCacheService.CachedLink(URL, null)));
        when(links.incrementAndGetUrl("abc123")).thenReturn(Optional.empty());

        assertStatus(404, () -> service.redirect("abc123"));

        verify(cache).evict("abc123");
        verify(cache, never()).put(anyString(), anyString(), any());
    }

    @Test
    void redirectUsesPostgresWhenCacheIsUnavailable() {
        // RedisCacheService never throws; an outage simply looks like a cache miss.
        when(cache.get("abc123")).thenReturn(Optional.empty());
        when(links.incrementAndGetUrl("abc123")).thenReturn(Optional.of(URL));

        assertEquals(URL, service.redirect("abc123"));
    }

    // -------------------------------------------------------------- analytics

    @Test
    void analyticsReturnsCodeAndClicksForKnownCode() {
        when(links.findAnalytics("abc123")).thenReturn(Optional.of(new LinkRepository.Analytics("abc123", 7)));

        LinkRepository.Analytics analytics = service.analytics("abc123");

        assertEquals("abc123", analytics.code());
        assertEquals(7L, analytics.clicks());
    }

    @Test
    void analyticsUnknownCodeReturns404() {
        when(links.findAnalytics("missing1")).thenReturn(Optional.empty());

        assertStatus(404, () -> service.analytics("missing1"));
    }

    @Test
    void analyticsNullCodeReturns404() {
        assertStatus(404, () -> service.analytics(null));
        verifyNoInteractions(links);
    }
}
