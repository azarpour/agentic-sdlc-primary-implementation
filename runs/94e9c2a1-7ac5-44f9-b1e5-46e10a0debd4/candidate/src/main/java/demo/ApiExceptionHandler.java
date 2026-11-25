package demo;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maps client errors to clean Spring-style JSON bodies:
 * {timestamp, status, error, message, path}.
 *
 * Bodies never contain stack traces, SQL, query strings, target URLs or
 * Idempotency-Key values. The path is the request URI without the query string.
 * Only specific exception types are handled here, so framework defaults
 * (404 for unknown routes, 405, 415, ...) keep working unchanged.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String GENERIC_SERVER_MESSAGE = "unexpected error";

    /** Handles errors raised by policies and the service (400, 404, 409, 503, ...). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex, HttpServletRequest request) {
        int code = ex.getStatusCode().value();
        String reason = ex.getReason();
        String message;
        if (reason != null && !reason.isBlank()) {
            message = reason;
        } else {
            message = defaultMessage(code);
        }
        // Never leak details for unexpected server-side statuses; 503 carries our own safe reason.
        if (code >= 500 && code != HttpStatus.SERVICE_UNAVAILABLE.value()) {
            message = GENERIC_SERVER_MESSAGE;
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON);
        HttpHeaders extra = ex.getHeaders();
        if (extra != null && !extra.isEmpty()) {
            builder.headers(extra);
        }
        return builder.body(body(code, message, request));
    }

    /** Malformed JSON, wrong field types, unparseable expiresAt or an empty body: always 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
                                                                  HttpServletRequest request) {
        return badRequest("malformed or unreadable request body", request);
    }

    /** Only Idempotency-Key is a declared header, and it is optional; kept for completeness. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex,
                                                                     HttpServletRequest request) {
        return badRequest("required request header is missing", request);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message, HttpServletRequest request) {
        int code = HttpStatus.BAD_REQUEST.value();
        return ResponseEntity.status(code)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(code, message, request));
    }

    private static Map<String, Object> body(int status, String message, HttpServletRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status);
        map.put("error", errorPhrase(status));
        map.put("message", message);
        map.put("path", pathOf(request));
        return map;
    }

    private static String errorPhrase(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }

    private static String defaultMessage(int status) {
        return errorPhrase(status).toLowerCase();
    }

    /** Request URI only; the query string (which could contain URLs or keys) is never included. */
    private static String pathOf(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String uri = request.getRequestURI();
        return uri == null ? "" : uri;
    }
}
