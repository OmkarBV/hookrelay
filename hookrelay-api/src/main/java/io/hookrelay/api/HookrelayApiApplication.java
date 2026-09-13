package io.hookrelay.api;

import io.hookrelay.common.tenancy.TenantScopedRepositoryImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entities and Spring Data repositories live in hookrelay-common
 * (io.hookrelay.common), a sibling package to this application
 * (io.hookrelay.api) rather than a sub-package, so they fall outside Spring
 * Boot's default component scan and must be wired in explicitly.
 *
 * <p>repositoryBaseClass swaps every repository's default implementation for
 * {@link TenantScopedRepositoryImpl}, which fixes findById/existsById to
 * honor the tenantFilter Hibernate filter (see its Javadoc for why the
 * default implementation can't be trusted to).
 *
 * <p>UserDetailsServiceAutoConfiguration is excluded because both
 * SecurityFilterChains authenticate via custom filters (API key, JWT), never
 * formLogin/httpBasic — left enabled it only generates an unused in-memory
 * user and a misleading startup log line.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan("io.hookrelay.common")
@EnableJpaRepositories(value = "io.hookrelay.common", repositoryBaseClass = TenantScopedRepositoryImpl.class)
public class HookrelayApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HookrelayApiApplication.class, args);
    }
}
