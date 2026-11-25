package demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core use cases: create, redirect and analytics.
 *
 * PostgreSQL is the single source of truth for links, idempotency records and the
 * atomic click counter. Redis is only a best-effort cache and never affects correctness.
 *
 * Idempotency key values and target URLs are never logged.
 */
@Service
public class UrlService {

    static final int MAX_CODE_ATTEMPTS = 5;

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /** Input of a create request; alias and expiresAt are optional. */
    public record CreateCommand(String url, String alias, Instant expiresAt) {
    }

    /** Result of a create request; replayed is true when an idempotent replay was served. */
    public record CreateResult(String code, boolean replayed) {
    }

    private record ExpiryLookup(boolean found, Instant expiresAt) {
    }

    private final LinkRepository links;
    private final IdempotencyRepository idempotency;
    private final RedisCacheService cache;
    private final ExpirationPolicy expirationPolicy;
    private final AliasPolicy aliasPolicy;
    private final CodeGenerator codeGenerator;
    private final Metrics metrics;
    private final ShortenerProperties properties;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public UrlService(LinkRepository links,
                      IdempotencyRepository idempotency,
                      RedisCacheService cache,
                      ExpirationPolicy expirationPolicy,
                      AliasPolicy aliasPolicy,
                      CodeGenerator codeGenerator,
                      Metrics metrics,
                      ShortenerProperties properties,
                      Clock clock,
                      JdbcTemplate jdbc,
                      PlatformTransactionManager transactionManager) {
        this.links = links;
        this.idempotency = idempotency;
        this.cache = cache;
        this.expirationPolicy = expirationPolicy;
        this.aliasPolicy = aliasPolicy;
        this.codeGenerator = codeGenerator;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.jdbc = jdbc;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // Every attempt runs in its own independent transaction.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx = template;
    }

    // ---------------------------------------------------------------- create

    public CreateResult create(CreateCommand command, String idempotencyKey) {
        if (command == null) {
            metrics.create("invalid");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        try {
            UrlValidator.validate(command.url());
            aliasPolicy.validate(command.alias());
            expirationPolicy.validate(command.expiresAt());
            validateIdempotencyKey(idempotencyKey);
        } catch (ResponseStatusException e) {
            metrics.create("invalid");
            throw e;
        }

        // The payload hash always uses the original request, so replays and conflicts are unaffected.
        String hash = payloadHash(command);
        Instant notBefore = clock.instant().minus(properties.getIdempotency().getRetention());

        if (idempotencyKey != null) {
            Optional<IdempotencyRepository.IdemRecord> existing = idempotency.find(idempotencyKey, notBefore);
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), hash);
            }
        }

        // Only a new link is subject to the default and maximum lifetime.
        CreateCommand effective;
        try {
            Instant resolved = expirationPolicy.resolveForCreate(command.expiresAt());
            effective = new CreateCommand(command.url(), command.alias(), resolved);
        } catch (ResponseStatusException e) {
            metrics.create("invalid");
            throw e;
        }

        if (effective.alias() != null) {
            return createWithAlias(effective, idempotencyKey, hash, notBefore);
        }
        return createWithGeneratedCode(effective, idempotencyKey, hash, notBefore);
    }

    private CreateResult createWithAlias(CreateCommand command, String key, String hash, Instant notBefore) {
        String alias = command.alias();
        try {
            insertAttempt(alias, command, key, hash, notBefore);
            metrics.create("created");
            return new CreateResult(alias, false);
        } catch (DuplicateKeyException e) {
            // Either the alias is taken, or a concurrent request with the same key won the race.
            if (key != null) {
                Optional<IdempotencyRepository.IdemRecord> winner = idempotency.find(key, notBefore);
                if (winner.isPresent()) {
                    return resolveExisting(winner.get(), hash);
                }
            }
            metrics.create("conflict");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "alias is already in use");
        }
    }

    private CreateResult createWithGeneratedCode(CreateCommand command, String key, String hash, Instant notBefore) {
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.next();
            try {
                insertAttempt(code, command, key, hash, notBefore);
                metrics.create("created");
                return new CreateResult(code, false);
            } catch (DuplicateKeyException e) {
                // The failed transaction is rolled back. It may be an idempotency race or a code collision.
                if (key != null) {
                    Optional<IdempotencyRepository.IdemRecord> winner = idempotency.find(key, notBefore);
                    if (winner.isPresent()) {
                        return resolveExisting(winner.get(), hash);
                    }
                }
                // Otherwise a genuine code collision: retry with a fresh code in a new transaction.
            }
        }
        metrics.create("exhausted");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "unable to allocate a short code, try again later");
    }

    /** Inserts the link and (optionally) the idempotency record in one separate transaction. */
    private void insertAttempt(String code, CreateCommand command, String key, String hash, Instant notBefore) {
        tx.executeWithoutResult(status -> {
            links.insert(code, command.url(), command.expiresAt());
            if (key != null) {
                // A record older than the retention window counts as absent; free the key for reuse.
                idempotency.deleteStale(key, notBefore);
                idempotency.insert(key, hash, code);
            }
        });
    }

    private CreateResult resolveExisting(IdempotencyRepository.IdemRecord record, String hash) {
        String stored = record.payloadHash() == null ? "" : record.payloadHash().trim();
        if (stored.equals(hash)) {
            metrics.create("replayed");
            return new CreateResult(record.code(), true);
        }
        metrics.create("conflict");
        throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency key was already used with a different payload");
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null) {
            return;
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Idempotency-Key header");
        }
    }

    /** SHA-256 hex of url, alias-or-empty and expiresAt-or-empty joined by newlines. */
    static String payloadHash(CreateCommand command) {
        String material = command.url()
                + "\n" + (command.alias() == null ? "" : command.alias())
                + "\n" + (command.expiresAt() == null ? "" : command.expiresAt().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // -------------------------------------------------------------- redirect

    /**
     * Resolves a code to its target URL and atomically counts the click.
     * Throws 404 (identical body) for unknown, inactive or expired codes; a 404 never increments.
     */
    public String redirect(String code) {
        if (code == null) {
            metrics.redirect("not_found");
            throw notFound();
        }

        Optional<RedisCacheService.CachedLink> cached = cache.get(code);
        if (cached.isPresent()) {
            Instant cachedExpiry = cached.get().expiresAt();
            if (cachedExpiry != null && !cachedExpiry.isAfter(clock.instant())) {
                cache.evict(code);
                metrics.redirect("not_found");
                throw notFound();
            }
        }

        // PostgreSQL stays authoritative: the atomic increment always runs.
        Optional<String> url = links.incrementAndGetUrl(code);
        if (url.isEmpty()) {
            if (cached.isPresent()) {
                cache.evict(code);
            }
            metrics.redirect("not_found");
            throw notFound();
        }

        if (cached.isEmpty()) {
            populateCache(code, url.get());
        }
        metrics.redirect("found");
        return url.get();
    }

    /** Populates the cache after a successful redirect; any failure only skips caching. */
    private void populateCache(String code, String url) {
        try {
            ExpiryLookup lookup = lookupExpiry(code);
            if (lookup.found()) {
                cache.put(code, url, lookup.expiresAt());
            }
        } catch (RuntimeException e) {
            // Caching is best-effort and must never break a redirect.
        }
    }

    private ExpiryLookup lookupExpiry(String code) {
        List<ExpiryLookup> rows = jdbc.query(
                "select expires_at from links where code = ?",
                (rs, i) -> {
                    Timestamp ts = rs.getTimestamp(1);
                    return new ExpiryLookup(true, ts == null ? null : ts.toInstant());
                },
                code);
        return rows.isEmpty() ? new ExpiryLookup(false, null) : rows.get(0);
    }

    // ------------------------------------------------------------- analytics

    public LinkRepository.Analytics analytics(String code) {
        if (code == null) {
            throw notFound();
        }
        return links.findAnalytics(code).orElseThrow(UrlService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }
}
