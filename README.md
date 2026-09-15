# Hookrelay

A self-hosted webhook delivery gateway: producer applications POST events to
Hookrelay instead of calling customer endpoints directly, and it handles
signing, retries, per-endpoint isolation, delivery logging, and replay.

> Work in progress, built phase by phase. This README grows with each phase
> rather than being written once at the end, so it stays in sync with what
> actually exists.

## Modules

```
hookrelay-common/        shared JPA entities, repositories, Kafka message contracts, Flyway migrations
hookrelay-api/           ingestion + admin REST API (Phases 1-3)
hookrelay-dispatcher/    Kafka consumer, HTTP delivery, signing (Phase 4)
hookrelay-testreceiver/  chaos receiver for tests (not yet built)
```

Flyway migrations live under `hookrelay-common/src/main/resources/db/migration`
even though only `hookrelay-api` ever executes them
(`spring.flyway.enabled=false` in the dispatcher) — both modules need the
same schema knowledge (JPA entity mappings must match it), and dispatcher's
own tests need a real schema to run against, so the SQL files live
somewhere both classpaths reach rather than being duplicated.

## Quick start

```
docker compose up -d
./mvnw -pl hookrelay-common,hookrelay-api,hookrelay-dispatcher install -DskipTests
HOOKRELAY_JWT_SECRET=$(openssl rand -base64 64) ./mvnw -pl hookrelay-api spring-boot:run
./mvnw -pl hookrelay-dispatcher spring-boot:run   # separate deployable, separate terminal
```

The API listens on `:8081`. Bootstrap the first tenant (works exactly once):

```
curl -X POST localhost:8081/api/v1/admin/bootstrap -H 'Content-Type: application/json' \
  -d '{"tenantName":"Acme","ownerEmail":"owner@acme.example","ownerPassword":"a-strong-password"}'
```

## Design decisions

### Tenant isolation lives in one place — and needed a second fix to actually work

`TenantScopingFilter` enables a Hibernate `@Filter` per request once
authentication resolves a tenant, restricting every tenant-scoped entity's
queries to `tenant_id = :tenantId`. This alone turned out to be
**insufficient**: Hibernate does not apply enabled filters to an entity load
by primary key (`EntityManager.find()`, which is what Spring Data's
`findById` uses internally as a fast path) — only to actual HQL/Criteria
queries. Left alone, that would silently defeat isolation on exactly the
lookup every controller reaches for first.

The fix is `TenantScopedRepositoryImpl`, registered project-wide as the
default Spring Data repository base class
(`@EnableJpaRepositories(repositoryBaseClass = ...)`), which overrides
`findById`/`existsById` to go through a Criteria query instead of
`EntityManager.find()`. One line of configuration fixes it for every
repository, rather than requiring each one to remember to avoid the default
method. A cross-tenant lookup now returns 404, never 403, with no
tenant-awareness required in services or controllers.

### Ingestion auth: SHA-256, not BCrypt

API keys (`hr_live_<random>`) are 256 bits of `SecureRandom` output, not
human-chosen passwords. BCrypt's deliberate slowness defends against offline
brute-forcing of low-entropy secrets; a high-entropy random key is already
infeasible to brute-force, so BCrypt would only add ~100ms of pure overhead
to every ingested request — directly at odds with the ingestion latency
target below. Admin passwords, which *are* human-chosen and reused, use
BCrypt as normal.

### Transactional outbox, without a separate outbox table

`POST /api/v1/events` writes the `event` row and its fanned-out `delivery`
rows in one database transaction, and only publishes to Kafka after that
transaction commits (`EventIngestionService` uses `TransactionTemplate`
explicitly so this is a plain sequencing guarantee, not a callback).
Publishing *inside* the transaction would be wrong: if a later statement in
the same transaction rolled it back, a Kafka message would already exist
pointing at a delivery row that was never actually persisted.

There's no separate `outbox` table because the `delivery` row already plays
that role: it's written durably before any publish is attempted, and it
carries a `status`/`next_attempt_at` that a Phase 5 retry sweeper can poll
for anything published-but-never-confirmed just as naturally as it polls for
retries. The one risk this doesn't cover — the process crashing between
commit and the (fire-and-forget) Kafka publish — is accepted deliberately:
waiting on the Kafka ack before returning would trade ingestion latency for
a failure mode the sweeper already has to handle anyway.

### Kafka partition key: `endpointId`, not `eventId` or `deliveryId`

`webhook.deliveries` is partitioned by the target endpoint's id. That's what
gives **per-endpoint ordering** — every delivery for one endpoint lands on
the same partition and is consumed in order — while still spreading
different endpoints across partitions for parallelism. Partitioning by
`eventId` or `deliveryId` (both effectively random per message) would
scatter one endpoint's deliveries across every partition with no ordering
guarantee at all, which matters because a customer receiving `invoice.paid`
before `invoice.created` is a real correctness problem for them, not just a
cosmetic one.

### Idempotency: header wins, body `eventId` is the fallback

The ingestion contract accepts an optional `eventId` in the request body and
also honors an `Idempotency-Key` header; Hookrelay treats these as the same
concept expressed two ways and uses whichever is present, header taking
priority if a caller sends both. Deduplication is enforced by the database's
unique `(application_id, idempotency_key)` index, not a response cache: a
retry re-runs the same insert, hits the constraint, and the service reads
back the original event's id in a fresh transaction (Postgres aborts the
rest of a transaction after a constraint violation, so the lookup can't
share the transaction that failed).

### Endpoint secrets are encrypted, not hashed — a correction to the Phase 1 schema

The original schema stored `endpoint_secret.secret_hash`, mirroring the
api_key/admin_user pattern of storing only a one-way hash. That's wrong for
this specific secret: a hash can only ever be *compared against*, never
recovered, but HMAC-signing every delivery requires the dispatcher to
reproduce the actual secret bytes on every single request. A migration
(V4) renames the column to `secret_ciphertext` and widens it; the value is
now AES-256-GCM encrypted (`SecretEncryptionService`, shared by both
modules) rather than hashed, so it's recoverable with the server's key but
still not sitting in the database in plaintext.

### SSRF protection is a Spring bean, not a static check, so it can be tested honestly

`EndpointUrlValidator` resolves DNS and rejects private/loopback/link-local
addresses (including the `169.254.169.254` cloud metadata endpoint) — and
does so **at delivery time**, not only at endpoint registration, since DNS
can be re-pointed to an internal address at any point after an endpoint was
first validated. It's a Spring-managed component with a
`hookrelay.security.ssrf-protection.enabled` flag (default `true`
everywhere) specifically so integration tests can point deliveries at an
in-process WireMock/Testcontainers server — which is otherwise
indistinguishable from the loopback address this class exists to block —
without weakening the check any real request path uses. Production
configuration never sets it to `false`.

### Virtual threads, and why ordering still holds

The dispatcher runs on `spring.threads.virtual.enabled=true`: this workload
is almost entirely blocked-on-network-IO (HTTP deliveries, DB, Kafka), which
is exactly what virtual threads are for — a fixed platform-thread pool would
force a choice between a small pool (one slow receiver stalls everything
queued behind it) and a large one (mostly idle threads burning ~1MB of stack
each). One pinning pitfall is worth calling out explicitly: a virtual thread
stays pinned to its carrier for the duration of any `synchronized` block it
executes, so blocking IO inside one defeats the purpose entirely. Nothing on
the delivery path uses `synchronized` — the global semaphore and the
per-endpoint Resilience4j bulkhead are both `java.util.concurrent`
primitives for exactly this reason.

Per-endpoint ordering (delivery for one endpoint arrives in order) comes
from Kafka partitioning by `endpointId` *combined with* each partition being
consumed strictly sequentially — the dispatcher does the blocking HTTP call
directly on the listener thread rather than fanning it out, so "next
message" only gets polled once the current one is fully attempted and
recorded. The per-endpoint bulkhead still matters despite that: different
endpoints can hash to the same partition (accepted head-of-line blocking,
the trade-off of key-based partitioning), and a retried delivery can be
picked up by a different partition-worker or dispatcher instance later — the
bulkhead is what caps concurrent attempts at one endpoint across those
cases, not within a single partition's normal flow.

### Why 4xx (except 429) is terminal, not retried

A 4xx means the receiver looked at this exact request and rejected it —
retrying the identical request gets the identical rejection every time, so
retrying it is pure waste until the customer fixes whatever caused the
rejection (at which point Replay, not blind retry, is the recovery path).
`429` is the one exception: it's an explicit "try again, just not right
now" from the receiver, not a rejection of the request itself. 5xx and
network errors (timeout, connection refused, DNS failure) are the receiver
or network failing independently of what was sent — exactly the transient
condition retries exist for.

## What's built so far

- **Phase 1** — multi-module layout, Docker Compose (Postgres/Kafka-KRaft/Redis), Flyway schema
- **Phase 2** — API-key (ingestion) and JWT (admin) auth, role/permission model, tenant isolation
- **Phase 3** — `POST /api/v1/events`: validation, idempotency, fan-out, transactional outbox publish to Kafka
- **Phase 4** — dispatcher: Stripe/Svix-style HMAC signing with secret rotation, SSRF-safe delivery on virtual threads, bounded global + per-endpoint concurrency, attempt recording, basic (Phase-5-to-be-refined) backoff scheduling
