package io.hookrelay.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryPublisher;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import io.hookrelay.common.delivery.DueDeliveryRef;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.event.Event;
import io.hookrelay.common.event.EventRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetrySweeperTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("hookrelay.security.secret-encryption-key", () -> "2P0OfgmRP7wHAAILerwZoJCC92TIw0RjZ4l0kXhIT/k=");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.properties.request.timeout.ms", () -> "500");
        registry.add("spring.kafka.admin.properties.default.api.timeout.ms", () -> "1000");
        registry.add("spring.kafka.admin.properties.retries", () -> "0");
        registry.add("spring.kafka.admin.fail-fast", () -> "false");
        // The sweeper itself is @Scheduled; disable the automatic trigger so
        // each test controls exactly when sweep() runs.
        registry.add("hookrelay.dispatcher.scheduling.enabled", () -> "false");
    }

    @Autowired
    private RetrySweeper retrySweeper;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private EndpointRepository endpointRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private DeliveryPublisher deliveryPublisher;

    private Delivery seedDueFailedDelivery() {
        Tenant tenant = tenantRepository.save(new Tenant("Sweeper Test Tenant"));
        Application application = applicationRepository.save(new Application(tenant, "Sweeper Test App"));
        Endpoint endpoint = endpointRepository.save(new Endpoint(application, "https://example.com/hook", "test"));
        Event event = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
        Delivery delivery = new Delivery(event, endpoint);
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setNextAttemptAt(Instant.now().minusSeconds(5)); // already due
        return deliveryRepository.save(delivery);
    }

    @Test
    void sweepsDueFailedDeliveriesAndPushesGuardWindowForward() {
        Delivery delivery = seedDueFailedDelivery();

        retrySweeper.sweep();

        verify(deliveryPublisher, times(1)).publish(delivery.getId(), delivery.getEndpoint().getId());
        Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        // Still FAILED (the sweeper doesn't change status), but nextAttemptAt
        // has been pushed into the future so it isn't immediately re-swept.
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(reloaded.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(60));
    }

    @Test
    void doesNotSweepDeliveriesNotYetDue() {
        Tenant tenant = tenantRepository.save(new Tenant("Sweeper Test Tenant 2"));
        Application application = applicationRepository.save(new Application(tenant, "App"));
        Endpoint endpoint = endpointRepository.save(new Endpoint(application, "https://example.com/hook", "test"));
        Event event = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
        Delivery notYetDue = new Delivery(event, endpoint);
        notYetDue.setStatus(DeliveryStatus.FAILED);
        notYetDue.setNextAttemptAt(Instant.now().plusSeconds(3600));
        deliveryRepository.save(notYetDue);

        retrySweeper.sweep();

        verify(deliveryPublisher, times(0)).publish(any(), any());
    }

    /**
     * The actual point of FOR UPDATE SKIP LOCKED: two concurrent sweeps must
     * never both grab the same row. One transaction locks the due row and
     * holds the lock open; a concurrent lockDueForRetry from a second
     * "instance" (a second thread with its own transaction) must see zero
     * rows — not block, not double-pick — proving the skip-locked query
     * actually skips locked rows rather than merely reading stale data.
     */
    @Test
    void concurrentSweepsNeverPickTheSameRow() throws InterruptedException {
        seedDueFailedDelivery();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch holdLock = new CountDownLatch(1);
        CountDownLatch secondAttemptDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> txTemplate.execute(status -> {
            List<DueDeliveryRef> locked = deliveryRepository.lockDueForRetry(10);
            assertThat(locked).hasSize(1);
            holdLock.countDown();
            awaitQuietly(secondAttemptDone, 5);
            return null;
        }));

        holdLock.await(5, TimeUnit.SECONDS);
        List<DueDeliveryRef> secondAttempt = txTemplate.execute(status -> deliveryRepository.lockDueForRetry(10));
        secondAttemptDone.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(secondAttempt).isEmpty();
    }

    private void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
