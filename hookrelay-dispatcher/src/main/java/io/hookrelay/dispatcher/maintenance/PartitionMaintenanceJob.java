package io.hookrelay.dispatcher.maintenance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes a gap flagged but deliberately left open by the Phase 1 schema:
 * {@code delivery_attempt} is range-partitioned by month with only a few
 * bootstrap partitions created by the migration, and creating future ones
 * (plus dropping ones past the retention window) was called out as "a
 * scheduled job, out of scope for a schema migration." This is that job.
 *
 * <p>{@code @SchedulerLock} is what makes this safe to deploy with multiple
 * dispatcher instances: creating or dropping a partition from more than one
 * instance at the same moment isn't handled by row-level locking the way the
 * retry sweeper is (there's no natural way to split "maintain the partition
 * set" into disjoint per-instance work), so this needs genuine mutual
 * exclusion instead — ShedLock, backed by the {@code shedlock} table.
 *
 * <p>Partition names and date bounds are derived entirely from
 * {@link YearMonth}, never from external input, so building DDL via string
 * formatting here doesn't carry the SQL-injection risk it would if any of
 * this came from a request.
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private static final Pattern PARTITION_NAME = Pattern.compile("delivery_attempt_y(\\d{4})m(\\d{2})");

    private final JdbcTemplate jdbcTemplate;
    private final int monthsAhead;
    private final int retentionMonths;

    public PartitionMaintenanceJob(
            JdbcTemplate jdbcTemplate,
            @Value("${hookrelay.dispatcher.partition-maintenance.months-ahead:2}") int monthsAhead,
            @Value("${hookrelay.dispatcher.partition-maintenance.retention-months:6}") int retentionMonths) {
        this.jdbcTemplate = jdbcTemplate;
        this.monthsAhead = monthsAhead;
        this.retentionMonths = retentionMonths;
    }

    @Scheduled(cron = "${hookrelay.dispatcher.partition-maintenance.cron:0 0 3 * * *}")
    @SchedulerLock(name = "partition-maintenance", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        createUpcomingPartitions();
        dropExpiredPartitions();
    }

    private void createUpcomingPartitions() {
        YearMonth current = YearMonth.now();
        for (int i = 0; i <= monthsAhead; i++) {
            YearMonth month = current.plusMonths(i);
            String partitionName = partitionNameFor(month);
            LocalDate from = month.atDay(1);
            LocalDate to = month.plusMonths(1).atDay(1);
            jdbcTemplate.execute("""
                    create table if not exists %s partition of delivery_attempt
                    for values from ('%s') to ('%s')
                    """.formatted(partitionName, from, to));
        }
        log.debug("Ensured delivery_attempt partitions exist through {}", current.plusMonths(monthsAhead));
    }

    private void dropExpiredPartitions() {
        YearMonth cutoff = YearMonth.now().minusMonths(retentionMonths);
        List<String> partitionNames = jdbcTemplate.queryForList(
                "select inhrelid::regclass::text as name from pg_inherits where inhparent = 'delivery_attempt'::regclass",
                String.class);
        for (String name : partitionNames) {
            parsePartitionMonth(name).filter(month -> month.isBefore(cutoff)).ifPresent(month -> {
                jdbcTemplate.execute("drop table if exists " + name);
                log.info("Dropped delivery_attempt partition {} (older than {}-month retention)", name, retentionMonths);
            });
        }
    }

    private String partitionNameFor(YearMonth month) {
        return "delivery_attempt_y%04dm%02d".formatted(month.getYear(), month.getMonthValue());
    }

    private Optional<YearMonth> parsePartitionMonth(String tableName) {
        Matcher matcher = PARTITION_NAME.matcher(tableName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
        } catch (DateTimeParseException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
