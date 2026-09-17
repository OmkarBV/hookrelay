package io.hookrelay.api.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hookrelay.api.security.apikey.IngestionPrincipal;
import io.hookrelay.common.ratelimit.RedisTokenBucket;
import io.hookrelay.common.ratelimit.TokenBucketResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-application ingestion rate limit — a token bucket in Redis (shared
 * across however many hookrelay-api instances are running, unlike the
 * in-memory Resilience4j limiters used elsewhere in this codebase for
 * things that are deliberately per-instance), keyed by applicationId so one
 * application's burst can never consume another's allowance.
 *
 * <p>Runs after ApiKeyAuthenticationFilter specifically because it needs
 * the authenticated application's id to key the bucket — there is no
 * meaningful per-application limit to check before we know which
 * application is calling.
 */
public class IngestionRateLimitFilter extends OncePerRequestFilter {

    private final RedisTokenBucket rateLimiter;
    private final ObjectMapper objectMapper;
    private final double requestsPerSecond;
    private final double burstCapacity;

    public IngestionRateLimitFilter(
            RedisTokenBucket rateLimiter, ObjectMapper objectMapper, double requestsPerSecond, double burstCapacity) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.requestsPerSecond = requestsPerSecond;
        this.burstCapacity = burstCapacity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.getPrincipal() instanceof IngestionPrincipal principal)) {
            // Not authenticated yet (bad/missing API key) — let the request
            // proceed to whatever authorization check rejects it; there's no
            // applicationId to rate-limit by.
            chain.doFilter(request, response);
            return;
        }

        String key = "ratelimit:ingest:" + principal.applicationId();
        TokenBucketResult result = rateLimiter.tryConsume(key, burstCapacity, requestsPerSecond, 1);
        if (!result.allowed()) {
            long retryAfterSeconds = Math.max(1, result.retryAfter().toSeconds());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(new RateLimitErrorBody(
                    Instant.now(), 429, "Too Many Requests",
                    "Ingestion rate limit exceeded for this application")));
            return;
        }

        chain.doFilter(request, response);
    }

    private record RateLimitErrorBody(Instant timestamp, int status, String error, String message) {
    }
}
