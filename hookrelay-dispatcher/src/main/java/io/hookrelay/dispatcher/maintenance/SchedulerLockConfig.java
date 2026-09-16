package io.hookrelay.dispatcher.maintenance;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Backs {@code @SchedulerLock} with the {@code shedlock} table (V5
 * migration) so a job annotated with it runs on exactly one dispatcher
 * instance at a time cluster-wide — unlike the retry sweeper, which is
 * deliberately safe (and intended) to run on every instance concurrently via
 * {@code FOR UPDATE SKIP LOCKED} instead. See PartitionMaintenanceJob for
 * the one job that actually needs this.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
