package demo;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class AliasPolicyTest {

    private static final List<String> RESERVED = List.of("api", "actuator", "health", "prometheus", "admin");

    /** Builds the policy with an explicit reserved-alias list so the test does not depend on defaults. */
    private static AliasPolicy policy(Collection<String> reserved) {
        try {
            ShortenerProperties props = new ShortenerProperties();
            for (Method m : ShortenerProperties.class.getMethods()) {
                if (m.getName().equals("setReservedAliases") && m.getParameterCount() == 1) {
                    Class<?> type = m.getParameterTypes()[0];
                    Object arg = Set.class.isAssignableFrom(type)
                            ? new LinkedHashSet<>(reserved)
                            : new ArrayList<>(reserved);
                    m.invoke(props, arg);
                }
            }
            return new AliasPolicy(props);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertBadRequest(AliasPolicy policy, String alias) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> policy.validate(alias));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void nullAliasIsAllowed() {
        assertDoesNotThrow(() -> policy(RESERVED).validate(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-link", "my_link", "A1b2C3", "___", "---", "Promo2025"})
    void acceptsValidAliases(String alias) {
        assertDoesNotThrow(() -> policy(RESERVED).validate(alias));
    }

    @Test
    void acceptsMinimumLengthOfThree() {
        assertDoesNotThrow(() -> policy(RESERVED).validate("abc"));
    }

    @Test
    void acceptsMaximumLengthOfThirtyTwo() {
        String alias = "a".repeat(32);
        assertEquals(32, alias.length());
        assertDoesNotThrow(() -> policy(RESERVED).validate(alias));
    }

    @Test
    void rejectsLengthTwo() {
        assertBadRequest(policy(RESERVED), "ab");
    }

    @Test
    void rejectsLengthThirtyThree() {
        assertBadRequest(policy(RESERVED), "a".repeat(33));
    }

    @Test
    void rejectsEmptyAlias() {
        assertBadRequest(policy(RESERVED), "");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "has space",
            "dot.dot",
            "slash/slash",
            "a$b",
            "semi;colon",
            "ab\u00e9c",
            "query?x",
            "abc\n",
            "%2e%2e"
    })
    void rejectsIllegalCharacters(String alias) {
        assertBadRequest(policy(RESERVED), alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "actuator", "health", "prometheus", "admin"})
    void rejectsReservedAliases(String alias) {
        assertBadRequest(policy(RESERVED), alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"API", "Api", "ACTUATOR", "Actuator", "HeAlTh", "Admin"})
    void reservedAliasesAreCaseInsensitive(String alias) {
        assertBadRequest(policy(RESERVED), alias);
    }

    @Test
    void reservedListIsConfigurable() {
        AliasPolicy custom = policy(List.of("secret"));
        assertBadRequest(custom, "secret");
        assertBadRequest(custom, "SECRET");
        assertDoesNotThrow(() -> custom.validate("public"));
    }

    @Test
    void aliasContainingReservedWordAsSubstringIsAllowed() {
        assertDoesNotThrow(() -> policy(RESERVED).validate("api-docs"));
        assertDoesNotThrow(() -> policy(RESERVED).validate("myadmin"));
    }
}
