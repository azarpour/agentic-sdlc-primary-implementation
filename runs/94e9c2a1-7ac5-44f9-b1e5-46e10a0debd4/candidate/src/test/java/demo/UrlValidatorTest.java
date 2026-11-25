package demo;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class UrlValidatorTest {

    private static final String BASE = "https://example.com/";

    private static void assertBadRequest(String url) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> UrlValidator.validate(url));
        assertEquals(400, ex.getStatusCode().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",
            "https://example.com/a?b=c#d",
            "https://example.com:8443/path",
            "http://127.0.0.1/x",
            "https://sub.example.co.uk/a_b-c"
    })
    void acceptsValidHttpAndHttpsUrls(String url) {
        URI uri = UrlValidator.validate(url);
        assertEquals(url, uri.toString());
        assertNotNull(uri.getHost());
    }

    @Test
    void returnsParsedHost() {
        assertEquals("example.com", UrlValidator.validate("https://example.com/a").getHost());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "ftp://example.com/file",
            "file:///etc/passwd",
            "data:text/html,hi",
            "mailto:a@example.com"
    })
    void rejectsNonHttpSchemes(String url) {
        assertBadRequest(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://",
            "https:///path",
            "/relative/path",
            "//example.com/x",
            "example.com"
    })
    void rejectsMissingSchemeOrHost(String url) {
        assertBadRequest(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:pass@example.com",
            "https://user@example.com",
            "http://admin:secret@example.com/path"
    })
    void rejectsUserInfoCredentials(String url) {
        assertBadRequest(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://exa mple.com",
            "ht!tp://example.com",
            "https://example.com/a b",
            "",
            " "
    })
    void rejectsMalformedUrls(String url) {
        assertBadRequest(url);
    }

    @Test
    void rejectsNullUrl() {
        assertBadRequest(null);
    }

    @Test
    void acceptsUrlOfExactlyMaxLength() {
        String url = BASE + "a".repeat(4096 - BASE.length());
        assertEquals(4096, url.length());
        assertDoesNotThrow(() -> UrlValidator.validate(url));
    }

    @Test
    void rejectsUrlLongerThanMaxLength() {
        String url = BASE + "a".repeat(4097 - BASE.length());
        assertEquals(4097, url.length());
        assertBadRequest(url);
    }

    @Test
    void legacyShortenerValidateStillDelegates() {
        assertEquals("example.com", Shortener.validate("https://example.com/a").getHost());
        assertThrows(RuntimeException.class, () -> Shortener.validate("javascript:alert(1)"));
        assertThrows(RuntimeException.class, () -> Shortener.validate("https://user:pass@example.com"));
    }
}
