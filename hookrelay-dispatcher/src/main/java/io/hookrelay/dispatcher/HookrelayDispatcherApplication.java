package io.hookrelay.dispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Deliberately does not set a custom repositoryBaseClass the way
 * hookrelay-api does. The tenantFilter Hibernate filter is only ever enabled
 * by hookrelay-api's TenantScopingFilter (a servlet Filter that exists only
 * in that module, scoped to one HTTP request); the dispatcher runs no such
 * filter and never enables it, so every query here already sees every
 * tenant's rows by default — exactly what a background worker needs, and
 * why TenantScopedRepositoryImpl's findById fix (needed only when a filter
 * is actually enabled) doesn't apply here.
 *
 * <p>{@code @EnableScheduling} deliberately does not live here — see
 * SchedulingConfig, which gates it behind a property so integration tests
 * that construct their own Delivery/Endpoint fixtures directly can disable
 * the retry sweeper and partition-maintenance job without them firing
 * against a Testcontainers database that's already been torn down.
 */
@SpringBootApplication
@ComponentScan({"io.hookrelay.dispatcher", "io.hookrelay.common"})
@EntityScan("io.hookrelay.common")
@EnableJpaRepositories("io.hookrelay.common")
public class HookrelayDispatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(HookrelayDispatcherApplication.class, args);
    }
}
