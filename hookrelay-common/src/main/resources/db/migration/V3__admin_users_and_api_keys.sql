-- Admin users authenticate with email/password to receive a JWT (see
-- AdminAuthController / JwtService). Unlike API keys, this password is a
-- human-chosen, comparatively low-entropy secret entered interactively at
-- login time (not on every request), so it is hashed with BCrypt, whose
-- deliberate slowness is exactly the defense a password needs and whose cost
-- is paid once per login rather than once per ingested event.
create table admin_user (
    id             uuid primary key default gen_random_uuid(),
    tenant_id      uuid not null references tenant (id),
    email          varchar(255) not null,
    password_hash  varchar(255) not null,
    role           varchar(20)  not null,
    status         varchar(20)  not null default 'ACTIVE',
    created_at     timestamptz  not null default now(),
    constraint admin_user_role_check check (role in ('OWNER', 'DEVELOPER', 'VIEWER')),
    constraint admin_user_status_check check (status in ('ACTIVE', 'DISABLED')),
    constraint admin_user_email_unique unique (email)
);

create index admin_user_tenant_id_idx on admin_user (tenant_id);

-- application.api_key_hash (added in V1) assumed one key per application.
-- Phase 2 requires supporting multiple active keys per application so a key
-- can be rotated without a delivery gap — the same reason endpoint_secret is
-- its own table instead of a column on endpoint. Replace the column with a
-- proper child table.
alter table application drop constraint application_api_key_hash_unique;
alter table application drop column api_key_hash;

-- Ingestion API keys are high-entropy random tokens (see ApiKeyService), not
-- passwords, so they are hashed with SHA-256 rather than BCrypt: a 256-bit
-- random key already makes offline brute-force infeasible, so BCrypt's
-- deliberate slowness buys no additional security here while adding ~100ms
-- of latency to every ingested request — directly at odds with the p99 <
-- 50ms ingestion target from Phase 3.
create table api_key (
    id              uuid primary key default gen_random_uuid(),
    application_id  uuid not null references application (id),
    tenant_id       uuid not null references tenant (id),
    key_hash        varchar(64) not null,
    status          varchar(20) not null default 'ACTIVE',
    created_at      timestamptz not null default now(),
    revoked_at      timestamptz,
    constraint api_key_status_check check (status in ('ACTIVE', 'REVOKED')),
    constraint api_key_key_hash_unique unique (key_hash)
);

create index api_key_application_id_idx on api_key (application_id);
create index api_key_tenant_id_idx on api_key (tenant_id);
