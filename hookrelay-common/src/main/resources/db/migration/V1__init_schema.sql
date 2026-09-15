-- Hookrelay core schema.
--
-- Tenancy: every table that stores customer data carries a tenant_id (or is
-- reachable from one via a single-hop foreign key). Application code enforces
-- isolation in one place (see hookrelay-api's TenantContext / repository base
-- layer, added in Phase 2) rather than repeating a WHERE tenant_id = ? clause
-- in every query; the FKs here exist to make cross-tenant rows impossible to
-- create by mistake, not to be the isolation mechanism themselves.

create extension if not exists pgcrypto;

-- =========================================================================
-- Tenant
-- =========================================================================
create table tenant (
    id          uuid primary key default gen_random_uuid(),
    name        varchar(255) not null,
    status      varchar(20)  not null default 'ACTIVE',
    created_at  timestamptz  not null default now(),
    constraint tenant_status_check check (status in ('ACTIVE', 'SUSPENDED'))
);

-- =========================================================================
-- Application — a producer app that sends events into Hookrelay
-- =========================================================================
create table application (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenant (id),
    name          varchar(255) not null,
    api_key_hash  varchar(64)  not null,
    status        varchar(20)  not null default 'ACTIVE',
    created_at    timestamptz  not null default now(),
    constraint application_status_check check (status in ('ACTIVE', 'DISABLED')),
    constraint application_api_key_hash_unique unique (api_key_hash)
);

create index application_tenant_id_idx on application (tenant_id);

-- =========================================================================
-- Endpoint — a customer's receiving URL
-- =========================================================================
create table endpoint (
    id                 uuid primary key default gen_random_uuid(),
    application_id     uuid not null references application (id),
    url                text not null,
    description        varchar(500),
    status             varchar(20)  not null default 'ACTIVE',
    rate_limit_per_sec integer      not null default 10,
    timeout_ms         integer      not null default 10000,
    created_at         timestamptz  not null default now(),
    constraint endpoint_status_check check (status in ('ACTIVE', 'PAUSED', 'DISABLED')),
    constraint endpoint_rate_limit_positive check (rate_limit_per_sec > 0),
    constraint endpoint_timeout_positive check (timeout_ms > 0)
);

create index endpoint_application_id_idx on endpoint (application_id);

-- =========================================================================
-- Endpoint secret — multiple rows per endpoint for zero-downtime rotation.
-- During a rotation window an endpoint has one ACTIVE and one ROTATING
-- secret; the dispatcher signs with every non-retired secret so receivers
-- can move to the new key on their own schedule (see Phase 4 / RECEIVERS.md).
-- =========================================================================
create table endpoint_secret (
    id           uuid primary key default gen_random_uuid(),
    endpoint_id  uuid not null references endpoint (id),
    secret_hash  varchar(64) not null,
    status       varchar(20) not null default 'ACTIVE',
    created_at   timestamptz not null default now(),
    constraint endpoint_secret_status_check check (status in ('ACTIVE', 'ROTATING', 'RETIRED'))
);

create index endpoint_secret_endpoint_id_idx on endpoint_secret (endpoint_id);
-- Enforced in Phase 4 application logic rather than a DB constraint: at most
-- one ACTIVE row and at most one ROTATING row per endpoint at a time (a plain
-- unique index on (endpoint_id, status) would also block ever having two
-- RETIRED rows, which is normal history we want to keep).

-- =========================================================================
-- Endpoint subscription — which event types an endpoint wants
-- =========================================================================
create table endpoint_subscription (
    endpoint_id  uuid not null references endpoint (id),
    event_type   varchar(255) not null,
    primary key (endpoint_id, event_type)
);

-- =========================================================================
-- Event — an ingested event, owned by the producer application
-- =========================================================================
create table event (
    id                  uuid primary key default gen_random_uuid(),
    application_id      uuid not null references application (id),
    event_type          varchar(255) not null,
    payload             jsonb not null,
    idempotency_key     varchar(255),
    received_at         timestamptz not null default now(),
    payload_size_bytes  integer not null,
    constraint event_payload_size_positive check (payload_size_bytes >= 0)
);

create index event_application_id_idx on event (application_id);
create index event_application_id_event_type_idx on event (application_id, event_type);

-- A NULL idempotency_key means "no idempotency requested"; Postgres unique
-- indexes already treat NULLs as distinct from each other, so this one
-- constraint both enforces the required uniqueness and allows callers that
-- omit the key to submit freely.
create unique index event_application_id_idempotency_key_uk
    on event (application_id, idempotency_key);

-- =========================================================================
-- Delivery — one row per (event, subscribed endpoint) fan-out target
-- =========================================================================
create table delivery (
    id               uuid primary key default gen_random_uuid(),
    event_id         uuid not null references event (id),
    endpoint_id      uuid not null references endpoint (id),
    status           varchar(20) not null default 'PENDING',
    attempt_count    integer     not null default 0,
    next_attempt_at  timestamptz,
    last_error       text,
    created_at       timestamptz not null default now(),
    completed_at     timestamptz,
    version          bigint      not null default 0,
    constraint delivery_status_check
        check (status in ('PENDING', 'DELIVERING', 'SUCCEEDED', 'FAILED', 'EXHAUSTED')),
    constraint delivery_attempt_count_non_negative check (attempt_count >= 0)
);

create index delivery_event_id_idx on delivery (event_id);
create index delivery_endpoint_id_idx on delivery (endpoint_id);

-- The retry sweeper's core query is:
--   SELECT ... FROM delivery WHERE status = 'FAILED' AND next_attempt_at <= now()
--   FOR UPDATE SKIP LOCKED
-- This composite index makes that a direct index range scan instead of a
-- full-table scan, which matters once delivery_attempt (and delivery) are in
-- the tens of millions of rows.
create index delivery_status_next_attempt_at_idx on delivery (status, next_attempt_at);

-- =========================================================================
-- Delivery attempt — append-only log of every HTTP attempt made.
-- This is the highest-volume table in the system, so it is range-partitioned
-- by month on attempted_at. Partitioning here buys two things a plain index
-- can't: old partitions can be dropped in O(1) to implement retention
-- (instead of a slow row-by-row DELETE), and the query planner can prune
-- whole partitions for time-bounded delivery-log queries (Phase 6).
-- attempted_at must be part of the primary key because Postgres requires
-- every unique/primary key on a partitioned table to include the partition
-- key column.
-- =========================================================================
create table delivery_attempt (
    id                       uuid        not null default gen_random_uuid(),
    delivery_id              uuid        not null references delivery (id),
    attempt_number           integer     not null,
    request_headers          jsonb,
    response_status          integer,
    -- Response bodies are truncated to 4KB by the dispatcher before this row
    -- is written (see Phase 4); this column is not itself size-limited by
    -- the DB because that truncation is a deliberate application-level
    -- policy, not a storage constraint to enforce twice.
    response_body_truncated  text,
    latency_ms               integer,
    error_type               varchar(50),
    attempted_at             timestamptz not null default now(),
    primary key (id, attempted_at)
) partition by range (attempted_at);

create index delivery_attempt_delivery_id_idx on delivery_attempt (delivery_id);

-- Bootstrap partitions covering the current and next 2 months. Operators are
-- expected to run a scheduled job (documented in README.md) that creates the
-- next partition ahead of time and drops partitions past the retention
-- window — that job is out of scope for a schema migration.
create table delivery_attempt_y2026m09 partition of delivery_attempt
    for values from ('2026-09-01') to ('2026-10-01');
create table delivery_attempt_y2026m10 partition of delivery_attempt
    for values from ('2026-10-01') to ('2026-11-01');
create table delivery_attempt_y2026m11 partition of delivery_attempt
    for values from ('2026-11-01') to ('2026-12-01');
