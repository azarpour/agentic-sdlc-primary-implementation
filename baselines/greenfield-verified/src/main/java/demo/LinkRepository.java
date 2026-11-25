package demo;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL access for the links table. All SQL is parameterized.
 * PostgreSQL is the single source of truth for links and the click counter.
 */
@Repository
public class LinkRepository {

    /** Analytics projection: code and total clicks. */
    public record Analytics(String code, long clicks) {
    }

    private static final String INSERT_SQL =
            "insert into links (code, url, expires_at) values (?, ?, ?)";

    /** Single atomic statement: only active, unexpired links are incremented. */
    private static final String INCREMENT_SQL =
            "update links set clicks = clicks + 1 "
                    + "where code = ? and active and (expires_at is null or expires_at > now()) "
                    + "returning url";

    private static final String ANALYTICS_SQL =
            "select code, clicks from links where code = ?";

    private final JdbcTemplate jdbc;

    public LinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new link. A duplicate primary key surfaces as
     * org.springframework.dao.DuplicateKeyException, which is intentionally
     * not caught here so callers can retry or map it to 409.
     */
    public void insert(String code, String url, Instant expiresAt) {
        Timestamp expires = expiresAt == null ? null : Timestamp.from(expiresAt);
        jdbc.update(INSERT_SQL,
                new Object[] {code, url, expires},
                new int[] {Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP});
    }

    /**
     * Atomically increments clicks by exactly 1 and returns the stored URL if
     * the link exists, is active and is not expired. Returns empty (and
     * increments nothing) otherwise.
     */
    public Optional<String> incrementAndGetUrl(String code) {
        List<String> rows = jdbc.query(INCREMENT_SQL, (rs, i) -> rs.getString(1), code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Returns code and click count for a known code, or empty if unknown. */
    public Optional<Analytics> findAnalytics(String code) {
        List<Analytics> rows = jdbc.query(ANALYTICS_SQL,
                (rs, i) -> new Analytics(rs.getString("code"), rs.getLong("clicks")),
                code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
