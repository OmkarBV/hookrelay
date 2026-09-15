-- Phase 1 satisfied tenant scoping by reachability (endpoint -> application ->
-- tenant, etc.), which is enough to make cross-tenant joins impossible but is
-- awkward to enforce as a single, uniform Hibernate filter condition (it would
-- need a correlated subquery per table). Denormalizing tenant_id onto every
-- tenant-owned table turns tenant isolation into a plain `tenant_id = ?`
-- predicate everywhere, which is what lets Phase 2 enforce it in exactly one
-- place (a Hibernate @Filter enabled per request) instead of writing that
-- join by hand in every repository method — see TenantFilterInterceptor.
--
-- These tables are empty in every environment this schema has been deployed
-- to so far, so the columns are added NOT NULL directly rather than going
-- through the usual add-nullable / backfill / set-not-null dance a live
-- table with existing rows would require.

alter table endpoint add column tenant_id uuid not null references tenant (id);
create index endpoint_tenant_id_idx on endpoint (tenant_id);

alter table endpoint_secret add column tenant_id uuid not null references tenant (id);
create index endpoint_secret_tenant_id_idx on endpoint_secret (tenant_id);

alter table event add column tenant_id uuid not null references tenant (id);
create index event_tenant_id_idx on event (tenant_id);

alter table delivery add column tenant_id uuid not null references tenant (id);
create index delivery_tenant_id_idx on delivery (tenant_id);

alter table delivery_attempt add column tenant_id uuid not null references tenant (id);
create index delivery_attempt_tenant_id_idx on delivery_attempt (tenant_id);

-- endpoint_subscription is a pure join table (endpoint_id, event_type) with no
-- identity of its own and is never queried directly outside the context of
-- its owning, already-filtered endpoint, so it does not need its own
-- tenant_id column.
