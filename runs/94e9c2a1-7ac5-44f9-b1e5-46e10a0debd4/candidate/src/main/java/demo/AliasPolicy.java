package demo;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates optional custom aliases.
 * A null alias is allowed (a code is generated instead). A non-null alias must
 * match [A-Za-z0-9_-]{3,32} and must not be a reserved word (case-insensitive).
 * Violations raise ResponseStatusException with status 400.
 */
@Component
public class AliasPolicy {

    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{3,32}");

    /** Routes that must never be shadowed, regardless of configuration. */
    private static final Set<String> BASELINE_RESERVED = Set.of("api", "actuator");

    private final Set<String> reserved;

    public AliasPolicy(ShortenerProperties properties) {
        Set<String> set = new HashSet<>(BASELINE_RESERVED);
        if (properties != null && properties.getReservedAliases() != null) {
            for (String value : properties.getReservedAliases()) {
                if (value != null && !value.isBlank()) {
                    set.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.reserved = Set.copyOf(set);
    }

    public void validate(String alias) {
        if (alias == null) {
            return;
        }
        if (!ALIAS.matcher(alias).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "alias must match [A-Za-z0-9_-]{3,32}");
        }
        if (reserved.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alias is reserved");
        }
    }
}
