package demo.support;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import demo.CodeGenerator;
import java.util.concurrent.atomic.AtomicInteger;
@TestConfiguration
public class TestSupportConfig {
 @Bean @Primary CodeGenerator testCodeGenerator() {
  AtomicInteger sequence = new AtomicInteger();
  return () -> String.format("test%08d", sequence.incrementAndGet());
 }
}
