package demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-client-IP rate limiting for create (POST /api/v1/urls) and redirect
 * (GET /{code}). Runs before the controller, so a rejected request never
 * reaches PostgreSQL.
 *
 * Rejections answer 429 with an integer Retry-After header (>= 1 second), a
 * plain text body and the shortener.ratelimit.rejected{op} metric.
 *
 * The limiter itself fails open when Redis is unavailable; this interceptor
 * additionally guards against any unexpected exception so rate limiting can
 * never cause a 5xx.
 *
 * The client IP is request.getRemoteAddr(). X-Forwarded-For is only used when
 * shortener.ratelimit.trust-forwarded-headers=true (i.e. behind a trusted proxy
 * that overwrites the header), because otherwise clients could spoof it.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    static final String OP_CREATE = "create";
    static final String OP_REDIRECT = "redirect";

    private static final String CREATE_PATH = "/api/v1/urls";
    private static final Pattern CODE_PATH = Pattern.compile("/[A-Za-z0-9_-]{3,32}");
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String BODY = "Too Many Requests";

    private final RateLimiter rateLimiter;
    private final ShortenerProperties properties;
    private final Metrics metrics;

    public RateLimitInterceptor(RateLimiter rateLimiter, ShortenerProperties properties, Metrics metrics) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!properties.getRatelimit().isEnabled()) {
            return true;
        }
        String op = operationFor(request);
        if (op == null) {
            return true;
        }

        RateLimiter.Decision decision;
        try {
            decision = rateLimiter.check(op, clientIp(request));
        } catch (RuntimeException e) {
            // Defensive fail-open: the limiter should never throw, but never let it cause a 5xx.
            log.warn("Rate limit check failed for op={}, allowing request ({})", op, e.getClass().getSimpleName());
            return true;
        }
        if (decision == null || decision.allowed()) {
            return true;
        }

        try {
            metrics.rateLimitRejected(op);
        } catch (RuntimeException ignored) {
            // Metrics must never break the request path.
        }
        reject(response, decision.retryAfterSeconds());
        return false;
    }

    /** Returns "create", "redirect" or null when the request is not rate limited. */
    private static String operationFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = pathWithinApplication(request);
        if (path == null) {
            return null;
        }
        if ("POST".equals(method) && CREATE_PATH.equals(path)) {
            return OP_CREATE;
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            // Only paths the redirect endpoint can serve are counted; others are plain 404s.
            if (CODE_PATH.matcher(path).matches()) {
                return OP_REDIRECT;
            }
        }
        return null;
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri;
    }

    private String clientIp(HttpServletRequest request) {
        if (properties.getRatelimit().isTrustForwardedHeaders()) {
            String forwarded = request.getHeader(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }

    private static void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        long retryAfter = Math.max(1L, retryAfterSeconds);
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/plain;charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
