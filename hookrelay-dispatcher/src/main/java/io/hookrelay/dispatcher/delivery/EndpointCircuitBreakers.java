package io.hookrelay.dispatcher.delivery;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One Resilience4j CircuitBreaker per endpoint, keyed by endpoint id. A
 * count-based sliding window sized exactly to the failure threshold with a
 * 100% failure-rate trigger is what makes this "N consecutive failures," not
 * a ratio over a longer history — any success within the window means the
 * last N calls weren't all failures, so it won't open.
 *
 * <p>Two tiers, matching the spec's two distinct asks: the circuit breaker
 * itself handles the *first* line of defense — stop attempting for a wait
 * period, then let exactly one probe through (half-open) to check for
 * recovery, automatically, with no human involved. That's for transient
 * trouble (a deploy, a brief outage). Auto-pause is the escalation for
 * genuinely dead endpoints: only after the circuit has reopened
 * {@code autoPauseAfterOpens} times *without ever reaching CLOSED in
 * between* does this stop trying automatically and hand it to a human — if
 * every open immediately paused the endpoint, the half-open probe this
 * class is required to perform would never get a chance to matter, since a
 * paused endpoint is skipped before the circuit breaker is ever consulted.
 *
 * <p>This state is in-memory and per-dispatcher-instance, not shared across
 * a multi-instance deployment — a known limitation (see README): each
 * instance decides independently when to open its view of an endpoint's
 * circuit and when to escalate to a pause. A shared circuit state would need
 * a store like Redis; not built here.
 */
@Component
public class EndpointCircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(EndpointCircuitBreakers.class);

    private final CircuitBreakerRegistry registry;
    private final EndpointPauseService pauseService;
    private final int autoPauseAfterOpens;
    private final ConcurrentHashMap<UUID, AtomicInteger> consecutiveOpenCounts = new ConcurrentHashMap<>();

    public EndpointCircuitBreakers(
            EndpointPauseService pauseService,
            MeterRegistry meterRegistry,
            @Value("${hookrelay.dispatcher.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${hookrelay.dispatcher.circuit-breaker.wait-duration-seconds:300}") long waitDurationSeconds,
            @Value("${hookrelay.dispatcher.circuit-breaker.auto-pause-after-opens:3}") int autoPauseAfterOpens) {
        this.pauseService = pauseService;
        this.autoPauseAfterOpens = autoPauseAfterOpens;

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(failureThreshold)
                .minimumNumberOfCalls(failureThreshold)
                .failureRateThreshold(100.0f)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationSeconds))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);

        // This registry is built manually (not via the Resilience4j Spring
        // Boot starter's autoconfigured one), so its metrics aren't bound to
        // Micrometer automatically the way application.yml-declared
        // instances would be — this does that binding explicitly. It
        // subscribes to the registry's own entry-added/removed events, so
        // per-endpoint breakers created later (the common case, since these
        // are created lazily on first delivery) are picked up too.
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(this.registry).bindTo(meterRegistry);

        // Fires exactly once per endpoint, at first-creation time — the
        // right place to wire a per-breaker listener without re-registering
        // (and thus double-firing) it on every later lookup of the same key.
        this.registry.getEventPublisher().onEntryAdded(event -> {
            CircuitBreaker breaker = event.getAddedEntry();
            UUID endpointId = UUID.fromString(breaker.getName());
            breaker.getEventPublisher().onStateTransition(stateEvent -> {
                CircuitBreaker.State to = stateEvent.getStateTransition().getToState();
                if (to == CircuitBreaker.State.OPEN) {
                    onOpened(endpointId);
                } else if (to == CircuitBreaker.State.CLOSED) {
                    consecutiveOpenCounts.remove(endpointId);
                }
            });
        });
    }

    public CircuitBreaker forEndpoint(UUID endpointId) {
        return registry.circuitBreaker(endpointId.toString());
    }

    private void onOpened(UUID endpointId) {
        int opens = consecutiveOpenCounts.computeIfAbsent(endpointId, id -> new AtomicInteger()).incrementAndGet();
        log.warn("Circuit breaker opened for endpoint {} (consecutive open #{})", endpointId, opens);
        if (opens >= autoPauseAfterOpens) {
            pauseService.autoPause(endpointId, "Circuit breaker reopened " + opens
                    + " times without recovering — endpoint auto-paused pending investigation");
            consecutiveOpenCounts.remove(endpointId);
        }
    }
}
