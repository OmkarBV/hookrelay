package io.hookrelay.dispatcher.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enabled by default; set {@code hookrelay.dispatcher.scheduling.enabled=false}
 * to turn off the retry sweeper and partition-maintenance job entirely —
 * used by integration tests that construct Delivery/Endpoint fixtures
 * directly and don't want either job firing against a Testcontainers
 * database that may already be torn down by the time it runs.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "hookrelay.dispatcher.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
