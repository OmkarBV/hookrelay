package io.hookrelay.api.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link CorrelationIdFilter} at the servlet container level with
 * the highest possible precedence, so it runs before Spring Security's own
 * filter chain (registered around {@code SecurityProperties.DEFAULT_FILTER_ORDER})
 * rather than after it — a plain {@code @Component} filter with no explicit
 * order isn't guaranteed to run first, and this one needs to wrap
 * authentication and rate limiting too, not just the controller.
 */
@Configuration
public class CorrelationIdFilterConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
