package demo;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Generates 12-character base64url codes from 9 random bytes
 * (9 bytes = 72 bits = exactly 12 base64 characters, no padding).
 */
@Component
public class SecureRandomCodeGenerator implements CodeGenerator {

    static final int RANDOM_BYTES = 9;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
