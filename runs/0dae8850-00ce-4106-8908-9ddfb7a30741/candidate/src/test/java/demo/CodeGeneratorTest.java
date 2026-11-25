package demo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    private static final Pattern TWELVE_BASE64URL = Pattern.compile("[A-Za-z0-9_-]{12}");
    private static final Pattern REDIRECT_ROUTE = Pattern.compile("[A-Za-z0-9_-]{3,32}");

    private final CodeGenerator generator = new SecureRandomCodeGenerator();

    @Test
    void producesExactlyTwelveCharacterCodes() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.next();
            assertNotNull(code);
            assertEquals(12, code.length(), "unexpected length for code " + code);
        }
    }

    @Test
    void usesOnlyBase64UrlCharacters() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.next();
            assertTrue(TWELVE_BASE64URL.matcher(code).matches(), "code outside base64url charset: " + code);
        }
    }

    @Test
    void containsNoPaddingOrStandardBase64Symbols() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.next();
            assertFalse(code.contains("="));
            assertFalse(code.contains("+"));
            assertFalse(code.contains("/"));
        }
    }

    @Test
    void generatedCodesAreAcceptedByRedirectRoutePattern() {
        for (int i = 0; i < 500; i++) {
            assertTrue(REDIRECT_ROUTE.matcher(generator.next()).matches());
        }
    }

    @Test
    void codesDecodeToNineRandomBytes() {
        for (int i = 0; i < 200; i++) {
            byte[] decoded = Base64.getUrlDecoder().decode(generator.next());
            assertEquals(9, decoded.length);
        }
    }

    @Test
    void sampleOfTenThousandCodesHasNoDuplicates() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = generator.next();
            assertTrue(seen.add(code), "duplicate code generated: " + code);
        }
        assertEquals(10_000, seen.size());
    }

    @Test
    void consecutiveCodesDiffer() {
        String previous = generator.next();
        for (int i = 0; i < 100; i++) {
            String current = generator.next();
            assertNotEquals(previous, current);
            previous = current;
        }
    }

    @Test
    void separateInstancesProduceDifferentCodes() {
        CodeGenerator other = new SecureRandomCodeGenerator();
        Set<String> first = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            first.add(generator.next());
        }
        for (int i = 0; i < 200; i++) {
            assertFalse(first.contains(other.next()));
        }
    }

    @Test
    void generatesDistinctValidCodesUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<List<String>> task = () -> {
                    List<String> out = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        out.add(generator.next());
                    }
                    return out;
                };
                futures.add(pool.submit(task));
            }
            Set<String> all = new HashSet<>();
            for (Future<List<String>> f : futures) {
                for (String code : f.get()) {
                    assertTrue(TWELVE_BASE64URL.matcher(code).matches(), "invalid code: " + code);
                    assertTrue(all.add(code), "duplicate under concurrency: " + code);
                }
            }
            assertEquals(threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
