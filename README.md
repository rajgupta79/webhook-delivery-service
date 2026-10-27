# Webhook Delivery Service

A fault-tolerant webhook delivery platform built with **Spring Boot**, **PostgreSQL**, and **Redis**.
You hand it an event for a customer endpoint; it guarantees the event is accepted durably,
delivered at least once, retried with backoff on failure, cryptographically signed, rate-limited,
and dead-lettered for manual replay if it ultimately can't be delivered.

> Looking for the deep dive? See **[PROJECT_HANDBOOK.md](./PROJECT_HANDBOOK.md)** — a from-zero
> guide covering every file, every function, the full event lifecycle, and an interview Q&A bank.

---

## Features

- **Durable ingest** — events land in PostgreSQL (source of truth) before anything else, so a crash never loses an accepted event.
- **Idempotent ingest** — a `(endpointId, Idempotency-Key)` unique constraint means the same event is stored once, even under concurrent duplicate requests.
- **At-least-once delivery** — events are delivered with `X-Event-Id` + HMAC signature so consumers can dedupe.
- **Exponential backoff + jitter** — transient failures retry up to 6 times with full-jitter backoff (2s base, 5min cap).
- **Circuit breaker** — per-endpoint CLOSED → OPEN → HALF-OPEN with single-probe recovery, so one broken customer can't drain the worker pool.
- **Per-endpoint rate limiting** — Redis-backed, configurable per second.
- **Lease-based claiming** — a conditional SQL update ensures exactly one worker delivers each event, even across many instances.
- **Crash recovery** — a background job reclaims stuck/leased events and re-queues events that missed enqueue (e.g. Redis was down).
- **Dead-letter + replay** — permanently failed events are stored with a reason and can be replayed individually or in bulk.
- **Zero-downtime secret rotation** — sign with both old and new secrets during a grace window.
- **Observability** — Prometheus metrics (queue depth, delivery latency, outcomes) + Grafana + a dashboard API.

---

## Architecture

```
  producer ──HTTP POST──► IngestController ──► IngestService
                                                   │  1. save to Postgres (durable)
                                                   │  2. push id to Redis queue
                                                   ▼
                              Redis "webhook:queue"  ◄── RetryPromoter (moves due retries)
                                                   │
                                                   ▼
                           DeliveryWorker × N ──► DeliveryService
                                rate-limit? circuit-open? → defer
                                claim (lease) → HTTP POST (signed) → customer
                                   2xx DELIVERED · 4xx DEAD · 5xx/timeout retry/backoff
                                                   │
                   RecoveryJob (30s): reclaim stuck/leased events
                                                   ▼
                        PostgreSQL (truth)      Redis (queue, rate, breaker)
```

- **PostgreSQL** is the durable source of truth (events, endpoints, delivery attempts).
- **Redis** is the fast transport: a list of ready event IDs and a sorted set of scheduled retries.
- Two schedulers keep things flowing: `RetryPromoter` (1s) promotes due retries, `RecoveryJob` (30s) is the crash safety net.

---

## Tech stack

Java 21 · Spring Boot 4.1.1 · Spring Data JPA / Hibernate · PostgreSQL 16 · Redis 7 ·
Micrometer + Prometheus · Grafana · Lombok · Testcontainers + JUnit 5 · k6

**Ports:** app `8080` · management/metrics `8081` · Postgres `5432` · Redis `6379` · Prometheus `9090` · Grafana `3000`

---

## Quick start

**Prerequisites:** Java 21, Docker Desktop. (k6 optional, for load testing.)

```bash
# 1. Start infrastructure (Postgres, Redis, Prometheus, Grafana)
docker compose up -d

# 2. Build
./mvnw -DskipTests package          # Windows: .\mvnw.cmd -DskipTests package

# 3. Run (defaults to 4 delivery workers)
java -jar target/webhook-0.0.1-SNAPSHOT.jar
#    tune workers:
java -jar target/webhook-0.0.1-SNAPSHOT.jar --webhook.workers=50
```

### Try it

```bash
# Create an endpoint (returns the endpoint, including its signing secret)
curl -X POST http://localhost:8080/endpoints \
  -H "Content-Type: application/json" \
  -d '{"url":"http://localhost:8080/test/ok"}'

# Send an event (use the id from the previous response)
curl -X POST http://localhost:8080/endpoints/1/events \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-key-1" \
  -d '{"hello":"world"}'

# Check the dashboard summary
curl http://localhost:8080/api/dashboard/summary
```

---

## API reference

### Ingest
| Method | Path | Description |
|---|---|---|
| `POST` | `/endpoints/{endpointId}/events` | Ingest an event. Header `Idempotency-Key` required. Returns `202` + event id. |

### Endpoints
| Method | Path | Description |
|---|---|---|
| `POST` | `/endpoints` | Create an endpoint from `{"url": "..."}`. Auto-generates a signing secret. |
| `POST` | `/endpoints/{id}/rotate-secret?graceSeconds=86400` | Rotate the secret; old secret stays valid during the grace window. |
| `PUT`  | `/endpoints/{id}/rate-limit?perSecond=100` | Set per-second rate limit (1–1000). |
| `GET`  | `/endpoints/{id}/health` | Circuit-breaker state for the endpoint. |

### Events
| Method | Path | Description |
|---|---|---|
| `GET` | `/events/{id}` | Fetch an event. |
| `GET` | `/events/{id}/attempts` | List delivery attempts for an event. |

### Dead letters & replay
| Method | Path | Description |
|---|---|---|
| `GET`  | `/dead-letters?page=0&size=20` | List dead events, newest first. |
| `POST` | `/events/{id}/replay` | Replay one dead event. |
| `POST` | `/endpoints/{endpointId}/replay-dead` | Replay up to 500 dead events for an endpoint. |

### Dashboard
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/dashboard/summary` | Counts by status, queue depth, delayed size, endpoint count. |
| `GET` | `/api/dashboard/endpoints` | Endpoints with circuit state (no secrets exposed). |
| `GET` | `/api/dashboard/events?status=&limit=50` | Recent events, optionally filtered. |

### Observability
| Method | Path | Description |
|---|---|---|
| `GET` | `http://localhost:8081/actuator/prometheus` | Prometheus metrics. |
| `GET` | `http://localhost:8081/actuator/health` | Health check. |

---

## Webhook signatures

Each delivery is signed so consumers can verify authenticity and integrity:

```
X-Event-Id: 42
X-Webhook-Signature: t=1700000000,v1=<hex HMAC-SHA256 of "t.payload">
```

The signature is `HMAC-SHA256(secret, "<timestamp>.<payload>")`. Verify by recomputing it,
comparing in constant time, and rejecting timestamps outside a tolerance window (replay
protection). During secret rotation, multiple `v1=` values are sent — any valid one matches.
See `SignatureVerifier` for a reference implementation.

---

## Testing

```bash
docker compose up -d postgres redis   # integration tests need these (Testcontainers needs Docker)
./mvnw test                            # Windows: .\mvnw.cmd test
```

- `SignatureVerifierTest` — pure unit tests (no infra).
- `WebhookApplicationTests` — Spring context boot (needs Postgres + Redis).
- `DeliveryFlowTest` — full end-to-end via Testcontainers: deliver + verify, idempotency, retry-then-deliver, dead-letter + replay.

---

## Load testing

```bash
# App must be running
export BASE_URL=http://localhost:8080          # Windows: $env:BASE_URL="http://localhost:8080"
k6 run loadtest/ingest.js
```

### Measured results

**Ingest API** — 200 req/s for 30s across 20 endpoints: **p95 ≈ 46 ms, p99 ≈ 83 ms, 0% errors**.

**Delivery pipeline** — delivery is I/O-bound, so throughput scales with worker count:

| Workers | Burst | Avg end-to-end latency |
|---|---|---|
| 4 (default) | 3,000 | ~296 s |
| 50 | 5,000 | ~20.5 s (~14× faster) |

Full method and analysis in the [handbook, §11](./PROJECT_HANDBOOK.md#11-known-gaps-and-load-test-findings).

---

## Configuration

Key settings in `src/main/resources/application.yml`:

| Property | Default | Description |
|---|---|---|
| `webhook.workers` | `4` | Delivery thread count (the main throughput knob). |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/webhooks` | Postgres connection. |
| `spring.data.redis.host` / `port` | `localhost` / `6379` | Redis connection. |
| `management.server.port` | `8081` | Port for health + Prometheus metrics. |

Activate structured JSON logging with the `json` profile: `--spring.profiles.active=json`.

---

## Project status & known gaps

This is a learning/portfolio project demonstrating distributed-systems engineering. It is **not
production-hardened**. Notable gaps (details in the [handbook, §11](./PROJECT_HANDBOOK.md#11-known-gaps-and-load-test-findings)):

- No authentication/authorization.
- Signing secrets are stored and returned in plaintext (should be hashed / shown once).
- No SSRF protection on destination URLs.
- Schema via Hibernate `ddl-auto: update` (should use migrations in production).

---

## Repository layout

```
src/main/java/com/example/webhook/
  api/        REST controllers (ingest, endpoints, events, dead letters, dashboard, test receivers)
  domain/     JPA entities: Event, Endpoint, DeliveryAttempt
  repo/       Spring Data repositories
  queue/      EventQueue (Redis list + delayed sorted set)
  service/    IngestService, DeliveryService, Backoff, RateLimiter, CircuitBreaker, Signer, ...
  worker/     DeliveryWorker (thread pool), RetryPromoter, RecoveryJob
  metrics/    WebhookMetrics (Micrometer)
src/test/java/...     Unit + Testcontainers integration tests
monitoring/           Prometheus + Grafana config
docker-compose.yml    Postgres, Redis, Prometheus, Grafana
PROJECT_HANDBOOK.md   Full from-zero guide + interview prep
```
