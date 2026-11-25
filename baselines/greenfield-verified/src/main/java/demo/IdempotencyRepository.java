package demo;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL access for the idempotency_keys table. All SQL is parameterized.
 * PostgreSQL is the single source of truth for idempotency records, so
 * correctness never depends on Redis.
 *
 * Only idempotency_keys rows are ever deleted here. Links are never deleted.
 * Idempotency key values are never logged.
 */
@Repository
public class IdempotencyRepository {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRepository.class);

    /** A stored idempotency record: the key, the payload hash and the created code. */
    public record IdemRecord(String key, String payloadHash, String code) {
    }

    private static final String FIND_SQL =
            "select idem_key, payload_hash, code from idempotency_keys "
                    + "where idem_key = ? and created_at >= ?";

    private static final String INSERT_SQL =
            "insert into idempotency_keys (idem_key, payload_hash, code) values (?, ?, ?)";

    private static final String PURGE_SQL =
            "delete from idempotency_keys where created_at < ?";

    private static final String DELETE_STALE_SQL =
            "delete from idempotency_keys where idem_key = ? and created_at < ?";

    private final JdbcTemplate jdbc;
    private final ShortenerProperties properties;
    private final Clock clock;

    public IdempotencyRepository(JdbcTemplate jdbc, ShortenerProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Finds a record for the key that was created at or after notBefore.
     * Records older than notBefore (outside the retention window) count as absent.
     */
    public Optional<IdemRecord> find(String key, Instant notBefore) {
        List<IdemRecord> rows = jdbc.query(FIND_SQL,
                (rs, i) -> new IdemRecord(
                        rs.getString("idem_key"),
                        rs.getString("payload_hash"),
                        rs.getString("code")),
                key, Timestamp.from(notBefore));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Inserts a new idempotency record. A duplicate key surfaces as
     * org.springframework.dao.DuplicateKeyException, which is intentionally
     * not caught here so the caller can re-read the winner of the race and
     * either replay (same payload hash) or answer 409 (different hash).
     */
    public void insert(String key, String payloadHash, String code) {
        jdbc.update(INSERT_SQL,
                new Object[] {key, payloadHash, code},
                new int[] {Types.VARCHAR, Types.CHAR, Types.VARCHAR});
    }

    /**
     * Removes a stale record for one key (older than notBefore) so a new record
     * can be inserted under the same key once the retention window has passed.
     * Returns the number of deleted rows. Never touches the links table.
     */
    public int deleteStale(String key, Instant notBefore) {
        return jdbc.update(DELETE_STALE_SQL, key, Timestamp.from(notBefore));
    }

    /**
     * Deletes idempotency records created before the cutoff and returns the
     * number of deleted rows. Only idempotency_keys rows are deleted.
     */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update(PURGE_SQL, Timestamp.from(cutoff));
    }

    /**
     * Periodic purge of records older than shortener.idempotency.retention.
     * Failures are logged and never propagated, so the scheduler keeps running.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purgeExpired() {
        try {
            Instant cutoff = clock.instant().minus(properties.getIdempotency().getRetention());
            int removed = purgeOlderThan(cutoff);
            if (removed > 0) {
                log.info("Purged {} expired idempotency records", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Idempotency purge failed: {}", e.getClass().getSimpleName());
        }
    }
}
