package demo;

import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer for the URL shortener. Contains no business logic; every rule
 * (validation, idempotency, collision handling, caching, analytics) lives in UrlService.
 *
 * Contracts:
 *   POST /api/v1/urls                  -> 201 {code, shortPath}
 *   GET  /{code}                       -> 302 Location + Cache-Control: no-store, empty body
 *   GET  /api/v1/urls/{code}/analytics -> 200 {code, clicks} or 404
 *
 * Errors are raised as ResponseStatusException by the service and policies and are
 * rendered by the framework (or ApiExceptionHandler) without stack traces or SQL.
 */
@RestController
public class UrlController {

    /** Request body of POST /api/v1/urls. alias and expiresAt are optional. */
    public record CreateRequest(String url, String alias, Instant expiresAt) {
    }

    /** Response body of POST /api/v1/urls: exactly the keys code and shortPath. */
    public record CreateResponse(String code, String shortPath) {
    }

    private final UrlService service;

    public UrlController(UrlService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/v1/urls", produces = "application/json")
    public ResponseEntity<CreateResponse> create(
            @RequestBody CreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UrlService.CreateCommand command =
                new UrlService.CreateCommand(request.url(), request.alias(), request.expiresAt());
        UrlService.CreateResult result = service.create(command, idempotencyKey);
        // A replay returns the same status and body as the original creation.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateResponse(result.code(), "/" + result.code()));
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        String target = service.redirect(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @GetMapping(path = "/api/v1/urls/{code}/analytics", produces = "application/json")
    public LinkRepository.Analytics analytics(@PathVariable("code") String code) {
        return service.analytics(code);
    }
}
