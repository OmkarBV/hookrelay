-- Per-endpoint retry schedule override. NULL means "use the default
-- schedule" (5s, 30s, 2m, 10m, 1h, 6h, 24h — see RetryBackoff); a non-null
-- array replaces it entirely. Stored as a native array rather than JSON
-- since it's a flat list of integers with no nesting, and Postgres arrays
-- let the dispatcher bind it straight to an Integer[] without a parse step.
alter table endpoint add column retry_schedule_seconds integer[];

-- Set when the circuit breaker escalates repeated failures into an actual
-- pause (see DeliveryExecutionService / EndpointPauseService) rather than
-- an operator doing it by hand. Auditable: an operator looking at a paused
-- endpoint should never have to guess why.
alter table endpoint add column paused_reason text;
alter table endpoint add column paused_at timestamptz;

-- ShedLock's required table shape (net.javacrumbs.shedlock jdbc-template
-- provider). Used by PartitionMaintenanceJob, which must run exactly once
-- cluster-wide — unlike the retry sweeper, which is safe to run on every
-- dispatcher instance concurrently via SELECT ... FOR UPDATE SKIP LOCKED
-- (see RetrySweeper), creating or dropping a delivery_attempt partition is
-- not naturally partitionable per-instance the same way, so it needs real
-- mutual exclusion instead.
create table shedlock (
    name       varchar(64)  not null primary key,
    lock_until timestamptz  not null,
    locked_at  timestamptz  not null,
    locked_by  varchar(255) not null
);
