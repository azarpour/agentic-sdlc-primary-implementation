package demo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates target URLs for shortening.
 * Rules: http/https only, host required, no userinfo, length <= 4096.
 * Failures raise ResponseStatusException with status 400. The rejected URL is
 * never echoed back in the error reason.
 */
public final class UrlValidator {

    static final int MAX_LENGTH = 4096;

    private UrlValidator() {
    }

    public static URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw bad("url is required");
        }
        if (url.length() > MAX_LENGTH) {
            throw bad("url is too long");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw bad("url is malformed");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw bad("url scheme must be http or https");
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if (!normalized.equals("http") && !normalized.equals("https")) {
            throw bad("url scheme must be http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw bad("url host is required");
        }
        if (uri.getRawUserInfo() != null) {
            throw bad("url must not contain credentials");
        }
        return uri;
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
