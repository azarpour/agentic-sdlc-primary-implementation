package demo;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Application wiring: configuration properties, scheduling (idempotency purge),
 * a replaceable UTC Clock and rate-limit interceptor registration.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ShortenerProperties.class)
public class AppConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Registers the rate-limit interceptor only for create (POST /api/v1/urls)
     * and redirect (GET /{code}) paths. Analytics and actuator are not limited.
     * The interceptor itself decides by HTTP method which operation applies.
     */
    @Bean
    public WebMvcConfigurer rateLimitWebMvcConfigurer(ObjectProvider<RateLimitInterceptor> interceptorProvider) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                RateLimitInterceptor interceptor = interceptorProvider.getIfAvailable();
                if (interceptor == null) {
                    return;
                }
                registry.addInterceptor(interceptor)
                        .addPathPatterns("/api/v1/urls", "/{code}")
                        .excludePathPatterns("/actuator/**");
            }
        };
    }
}
