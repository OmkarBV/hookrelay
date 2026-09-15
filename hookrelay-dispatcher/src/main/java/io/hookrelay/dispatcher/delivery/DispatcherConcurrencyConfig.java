package io.hookrelay.dispatcher.delivery;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DispatcherConcurrencyConfig {

    /**
     * The global ceiling on concurrent in-flight HTTP deliveries. Virtual
     * threads make it cheap to have thousands of deliveries logically
     * "in progress" at once, but each one holds a real socket and consumes
     * real memory on both ends — this is a deliberate cap, not a thread-pool
     * size, precisely because virtual threads don't impose one for free.
     */
    @Bean
    public Semaphore inFlightDeliverySemaphore(@Value("${hookrelay.dispatcher.max-inflight:500}") int maxInFlight) {
        return new Semaphore(maxInFlight);
    }

    /**
     * One bulkhead per endpoint (created on demand, see BulkheadRegistry's
     * usage in DeliveryExecutionService), each capping concurrent attempts
     * at the same endpoint so retries or overlapping dispatcher instances
     * can't monopolize the global semaphore's permits on a single slow or
     * broken customer.
     */
    @Bean
    public BulkheadRegistry bulkheadRegistry(
            @Value("${hookrelay.dispatcher.per-endpoint-max-concurrent:5}") int perEndpointMaxConcurrent) {
        // A bounded wait rather than an immediate reject: each partition's
        // messages are already processed one at a time (see
        // DeliveryTaskListener), so a brief wait here just means "this
        // endpoint's other in-flight attempts need to finish first," which
        // costs nothing but a parked virtual thread. If it's still full
        // after 30s, DeliveryExecutionService records that as a retryable
        // failure rather than blocking forever.
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(perEndpointMaxConcurrent)
                .maxWaitDuration(Duration.ofSeconds(30))
                .build();
        return BulkheadRegistry.of(config);
    }
}
