package io.hookrelay.dispatcher.observability;

import io.hookrelay.common.delivery.DeliveryStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Centralizes the delivery-side Micrometer instrumentation the spec asks
 * for: deliveries by status, attempt latency, and queue lag are recorded
 * from single choke points (DeliveryOutcomeRecorder and
 * DeliveryTaskListener) rather than scattered across every early-return
 * branch in DeliveryExecutionService — those branches all end up calling
 * outcomeRecorder.record(...) anyway, so that is where every attempt's
 * outcome is knowable in one place.
 *
 * <p>In-flight count is the exception: it brackets exactly the HTTP call in
 * DeliveryExecutionService, since that's the concurrency this dispatcher
 * instance is actually bounding (the semaphore/bulkhead permits already in
 * place), not every delivery merely somewhere in {@code execute()}.
 */
@Component
public class DeliveryMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger inFlight = new AtomicInteger();

    public DeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("hookrelay.delivery.inflight", inFlight, AtomicInteger::get)
                .description("HTTP delivery attempts currently in progress on this dispatcher instance")
                .register(registry);
    }

    public void incrementInFlight() {
        inFlight.incrementAndGet();
    }

    public void decrementInFlight() {
        inFlight.decrementAndGet();
    }

    /**
     * @param status the delivery's final row status after this attempt (SUCCEEDED, FAILED, or EXHAUSTED)
     * @param errorType null for a successful attempt
     * @param latencyMs null for attempts that never sent a request (e.g. circuit open, rate limited, SSRF blocked)
     */
    public void recordAttempt(DeliveryStatus status, String errorType, Integer latencyMs) {
        Counter.builder("hookrelay.deliveries.total")
                .tag("status", status.name())
                .tag("error_type", errorType == null ? "none" : errorType)
                .register(registry)
                .increment();
        if (latencyMs != null) {
            Timer.builder("hookrelay.delivery.attempt.latency")
                    .tag("status", status.name())
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        }
    }

    /** How long a delivery task sat in Kafka before this dispatcher instance picked it up. */
    public void recordQueueLag(long lagMillis) {
        Timer.builder("hookrelay.dispatch.queue.lag")
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0, lagMillis), TimeUnit.MILLISECONDS);
    }
}
