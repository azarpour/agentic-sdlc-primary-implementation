package demo;
import demo.support.TestSupportConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSupportConfig.class)
@Testcontainers
class ShortenerIT {
 @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");
 @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername); r.add("spring.datasource.password",db::getPassword);
  r.add("spring.data.redis.host",redis::getHost); r.add("spring.data.redis.port",()->redis.getMappedPort(6379));
 }
 @Autowired MockMvc mvc;
 @Test void createRedirectAnalyticsAndReplay() throws Exception {
  String body="{\"url\":\"https://example.com\",\"alias\":\"integrationAlias\"}";
  for(int i=0;i<2;i++) mvc.perform(post("/api/v1/urls").contentType("application/json").header("Idempotency-Key","integration-key").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("integrationAlias"));
  mvc.perform(get("/integrationAlias")).andExpect(status().isFound()).andExpect(header().string("Location","https://example.com"));
  mvc.perform(get("/api/v1/urls/integrationAlias/analytics")).andExpect(status().isOk()).andExpect(jsonPath("$.clicks").value(1));
  mvc.perform(post("/api/v1/urls").contentType("application/json").content(body)).andExpect(status().isConflict());
  mvc.perform(post("/api/v1/urls").contentType("application/json").header("Idempotency-Key","integration-key").content("{\"url\":\"https://other.example\"}")).andExpect(status().isConflict());
 }
 @Test void rejectsInvalidAndMissing() throws Exception {
  mvc.perform(post("/api/v1/urls").contentType("application/json").content("{\"url\":\"file:///etc/passwd\"}")).andExpect(status().isBadRequest());
  mvc.perform(get("/missingCode")).andExpect(status().isNotFound());
 }
}
