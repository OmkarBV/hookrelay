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
carries a `status`/`next_attempt_at` that the retry sweeper (`RetrySweeper`,
Phase 5) polls for anything published-but-never-confirmed just as naturally
as it polls for genuine retries. The one risk this doesn't cover — the
process crashing between commit and the (fire-and-forget) Kafka publish —
is accepted deliberately: waiting on the Kafka ack before returning would
trade ingestion latency for a failure mode the sweeper already has to
handle anyway.

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

### The retry sweeper: `FOR UPDATE SKIP LOCKED`, not ShedLock

`RetrySweeper` polls `delivery WHERE status='FAILED' AND next_attempt_at <=
now()` and republishes each to Kafka. Its query
(`DeliveryRepository.lockDueForRetry`) ends in `FOR UPDATE SKIP LOCKED`,
which is what makes it safe to run this on *every* dispatcher instance
concurrently rather than needing to pick one: plain `FOR UPDATE` would make
a second instance's query **block** until the first instance's transaction
finishes (serializing all sweeping through one instance at a time, even
though nothing about the work actually requires that); no locking at all
would let two instances **both** select and republish the same row,
double-delivering it. `SKIP LOCKED` instead makes each instance's query
silently skip whatever rows another instance already has locked and grab
the next ones — every instance walks away with a disjoint batch, no
coordination required beyond what Postgres's own row locks already provide.
A dedicated test (`RetrySweeperTest.concurrentSweepsNeverPickTheSameRow`)
proves this directly: one transaction holds a lock on the only due row,
and a concurrent second transaction's `lockDueForRetry` genuinely returns
zero rows rather than blocking or double-picking.

Once a batch is locked, the sweeper doesn't flip the rows to some
in-progress status before republishing — it pushes `next_attempt_at`
forward by a short guard window (default 120s) and commits, *then*
publishes to Kafka outside the transaction. If the process crashes in that
gap, nothing is lost: the row is still `FAILED`, and once the guard window
elapses the next sweep picks it up again. The cost is a possible duplicate
attempt in that narrow crash window, which is exactly the at-least-once
behavior the whole system — and receivers, per RECEIVERS.md — already has
to tolerate.

This is also why the sweeper deliberately does **not** use ShedLock:
ShedLock enforces that a job runs on exactly one instance at a time, which
here would just throttle retry throughput to one instance's pace for no
correctness benefit — `SKIP LOCKED` already gives every instance safe,
independent work.

### ShedLock *is* used — for the one job that actually needs single execution

`PartitionMaintenanceJob` creates upcoming `delivery_attempt` partitions and
drops ones past the retention window (the task Phase 1's schema flagged as
"a scheduled job, out of scope for a schema migration"). Unlike the
sweeper's row-level work, there's no natural way to split "keep the
partition set correct" into disjoint per-instance chunks — every instance
would try to create or drop the exact same tables at the exact same moment.
That's what ShedLock is for: `@SchedulerLock` (backed by the `shedlock`
table, V5 migration) guarantees only one dispatcher instance actually runs
the job on a given schedule tick, cluster-wide.

### Backoff has jitter now; the schedule is per-endpoint configurable

Phase 4 shipped the bare 5s/30s/2m/10m/1h/6h/24h schedule with no jitter, as
a placeholder — this phase replaces it. Equal jitter (`delay/2 +
random(0, delay/2)`) is applied to whichever schedule is in effect, so
deliveries that all failed at the same moment (one receiver-side outage
can take down many endpoints' worth of deliveries at once) don't all retry
in lockstep and hit the recovering receiver as a thundering herd — full
jitter (`random(0, delay)`) was avoided because it can collapse a "wait an
hour" step down to almost no wait at all, defeating the schedule's intent.
`Endpoint.retryScheduleSeconds` (nullable array, V5 migration) lets a
specific endpoint override the schedule entirely; null falls back to the
default.

### Circuit breaker and auto-pause are two tiers, not one

A Resilience4j `CircuitBreaker` per endpoint (count-based sliding window
sized to the failure threshold, 100% failure-rate trigger — which is what
makes it "N *consecutive* failures" rather than a ratio over a longer
history) opens after repeated failures, stops attempting for a wait period,
then automatically lets one probe through (half-open) to check for
recovery. That's the first line of defense, handling transient trouble — a
deploy, a brief outage — without any human involved.

Auto-pause is a deliberate escalation on top, not the same mechanism: only
after the circuit has reopened `auto-pause-after-opens` times (default 3)
*without ever reaching CLOSED in between* does `EndpointCircuitBreakers`
call `EndpointPauseService` to actually pause the endpoint
(`status=PAUSED`, `pausedReason` recorded) and stop consuming any retry
capacity for it at all. Collapsing these into one tier — pausing
immediately on the first open — was considered and rejected: a paused
endpoint is skipped before the circuit breaker is ever consulted
(`DeliveryExecutionService` checks endpoint status first), so the
half-open probe the spec explicitly requires would never get a chance to
matter.

Known limitation: this state is in-memory, per-dispatcher-instance, not
shared across a multi-instance deployment. Each instance independently
decides when its view of an endpoint's circuit opens and when to escalate.
Sharing it would need an external store (Redis); not built here.

### Dead-lettering: two failure modes that redelivery can't fix

`webhook.deliveries.DLT` catches two kinds of messages, both handled by one
`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` with zero consumer
retries: malformed messages (the value doesn't deserialize —
`ErrorHandlingDeserializer` wraps the real deserializer so this fails
per-record instead of killing the whole consumer thread, which is what
happens if you point `JsonDeserializer` at a Kafka listener directly with
no wrapper) and well-formed messages whose delivery no longer exists
(`UnprocessableDeliveryTaskException`, thrown when the endpoint or event
behind a delivery was deleted after the message was published). Zero
retries at this level is deliberate: an actual delivery attempt's retry
logic already lives entirely in the `delivery` table and the sweeper — a
message that reaches this handler failed for a reason redelivery can't
address, because the data it points to doesn't exist or it never parsed.

### Delivery log pagination: keyset, not offset

`GET /api/v1/admin/deliveries` pages with an opaque cursor
(`DeliveryCursor`, base64 of the last row's `createdAt` + `id`), not a page
number. `LIMIT/OFFSET n` forces Postgres to walk and discard the first `n`
matching rows on *every* request — page 500 of a delivery log with millions
of rows means scanning and throwing away 500 pages' worth of index entries
just to find where to start, and that cost grows with how deep into the
history a page is, which is exactly where an operator investigating an
incident is most likely to be looking (old failures, not the most recent
page). A keyset cursor turns "skip ahead" into a plain indexed range
condition instead — `(created_at, id) < (cursor_created_at, cursor_id)`
(the `id` tiebreak matters whenever two rows share the same instant) — which
costs the same O(page size) no matter how deep the page is. It also avoids
Spring Data's `Page<T>` entirely (`findBy(spec, q -> q.limit(n).all())`
rather than `findAll(spec, Pageable)`): a `Page` always runs a `COUNT(*)`
query alongside the content query to report a total, which this endpoint
has no use for and which is itself needless work on a large table. The one
real cost of keyset pagination is that you can't jump to "page 12" directly
— only forward from a cursor — which is a trade this delivery log doesn't
need to make (it's investigated forward from "now" or forward from a filter,
not paged into an arbitrary offset by number).

### Replay creates a new delivery row; it never touches the original

Both replay endpoints (`POST /deliveries/{id}/replay` and the bulk
`POST /deliveries/replay`) insert a brand new `delivery` row
(`Delivery.replayOf`) rather than resetting the original's status or
attempt count. The original and every `delivery_attempt` row it
accumulated stay exactly as they were — replaying is not allowed to rewrite
history, only add a new attempt at delivering the same event. `is_replay`
and `replayed_from_delivery_id` (V6 migration) make a replay visibly
distinct from an original delivery in the log and traceable back to what it
replayed.

Bulk replay carries three separate guards, each defending against a
different failure mode: **`confirm: true`** is required because an empty
filter matches every delivery the tenant has ever had, so this stops a
missing field or a copy-pasted request from re-queuing everything by
accident; a **batch size cap** (default 500) rejects outright — rather than
silently truncating — a filter that matches more than the cap, so an
operator gets a clear "narrow your filter" error instead of quietly
replaying only part of what they thought they were replaying; and a
**per-tenant rate limit** (Resilience4j `RateLimiter`, default 5/minute)
stops the endpoint itself — which can trivially re-queue hundreds of
deliveries in one call — from becoming its own denial-of-service vector,
independent of Phase 7's general ingestion/delivery rate limiting.

### Route prefix: `/api/v1/admin/deliveries`, not the bare path

The spec names the delivery log routes as `/api/v1/deliveries/...`. Every
other admin-facing resource built so far — bootstrap, auth, applications,
endpoints — lives under `/api/v1/admin/...`, which is exactly the path
`SecurityConfig`'s admin `SecurityFilterChain` matches
(`securityMatcher("/api/v1/admin/**")`). A bare `/api/v1/deliveries` path
would match neither that chain nor the ingestion chain
(`/api/v1/events/**`) and would fall through to the default chain's
`denyAll()` — so these routes are kept under `/api/v1/admin` for
consistency with the rest of the admin surface and so the existing
authentication actually covers them.

## What's built so far

- **Phase 1** — multi-module layout, Docker Compose (Postgres/Kafka-KRaft/Redis), Flyway schema
- **Phase 2** — API-key (ingestion) and JWT (admin) auth, role/permission model, tenant isolation
- **Phase 3** — `POST /api/v1/events`: validation, idempotency, fan-out, transactional outbox publish to Kafka
- **Phase 4** — dispatcher: Stripe/Svix-style HMAC signing with secret rotation, SSRF-safe delivery on virtual threads, bounded global + per-endpoint concurrency, attempt recording
- **Phase 5** — jittered, per-endpoint-configurable retry backoff; `SKIP LOCKED` retry sweeper; per-endpoint circuit breaker escalating to auto-pause; dead-letter topic; ShedLock-guarded partition maintenance
- **Phase 6** — delivery log search (keyset pagination) and detail (full attempt history), single and bulk replay with confirm/cap/rate-limit guards, original attempt history never mutated by a replay
