package demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ShortenerTest {
 @Test void acceptsHttps(){assertEquals("example.com",Shortener.validate("https://example.com/a").getHost());}
 @Test void rejectsUnsafeScheme(){assertThrows(RuntimeException.class,()->Shortener.validate("javascript:alert(1)"));}
 @Test void rejectsCredentials(){assertThrows(RuntimeException.class,()->Shortener.validate("https://user:pass@example.com"));}
}
