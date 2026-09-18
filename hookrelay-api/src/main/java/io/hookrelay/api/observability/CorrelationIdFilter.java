package io.hookrelay.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id in MDC for the lifetime of every request, so every
 * log line from ingestion onward — including, once picked up from the
 * stored {@code Event}, every dispatcher log line for its deliveries — can
 * be tied back to the same request. Registered outside Spring Security
 * (see CorrelationIdFilterConfig) so it wraps both the ingestion and admin
 * filter chains identically rather than duplicating it into each.
 *
 * <p>Honors a caller-supplied {@code X-Correlation-Id} so a client that
 * already has its own tracing id can thread it through rather than getting
 * a second, unrelated one back.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
