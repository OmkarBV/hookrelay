package io.hookrelay.dispatcher.observability;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Spring Boot has no built-in Kafka health indicator (unlike DataSource and
 * Redis, which are auto-configured). This one asks the broker to describe
 * the cluster with a short timeout — if that doesn't come back, this
 * dispatcher instance can't consume delivery tasks, which is exactly what a
 * readiness check should report.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            String clusterId = admin.describeCluster().clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Health.up().withDetail("clusterId", clusterId).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (ExecutionException | TimeoutException e) {
            return Health.down(e).build();
        }
    }
}
