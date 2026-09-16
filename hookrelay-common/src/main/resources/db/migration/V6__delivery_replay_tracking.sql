-- A replay creates a brand new delivery row rather than mutating the
-- original — the original's status, attempt_count, and delivery_attempt
-- history must stay exactly as they were, since they're the historical
-- record of what actually happened. These two columns are what let the
-- delivery log tell a replay apart from an original delivery and trace it
-- back to what it replayed.
alter table delivery add column is_replay boolean not null default false;
alter table delivery add column replayed_from_delivery_id uuid references delivery (id);

create index delivery_replayed_from_delivery_id_idx on delivery (replayed_from_delivery_id);

-- Supports the delivery log's search/filter endpoint (GET
-- /api/v1/admin/deliveries?endpointId=&status=&from=&to=), keyset-paginated
-- on (created_at, id).
create index delivery_endpoint_id_created_at_idx on delivery (endpoint_id, created_at desc, id desc);
