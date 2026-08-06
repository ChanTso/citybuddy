# CityBuddy

CityBuddy is a local-commerce transaction and text-only AI customer-support project built around explicit identity, transaction, retrieval, and evaluation boundaries. The repository contains runnable Java and Python services, a health-gated local data topology, and a small React portfolio surface for the verified direct-user paths.

The canonical route and slice status live in [IMPLEMENTATION.md](IMPLEMENTATION.md). Cross-slice ownership and security boundaries live in [docs/CONTRACTS.md](docs/CONTRACTS.md).

## Verified capabilities

- Identity: RS256 direct-user login, current/overlap JWKS publication, independently authenticated JIT token exchange, exact-scope OBO tokens, and server-owned support sessions.
- Commerce: authenticated published products, standard ordering, seckill allocation/reservation/transaction ordering, unpaid cancellation, owner-scoped mock payment, partial/full refund, reconciliation, and transactional Outbox paths.
- Support: durable owner-scoped conversations, bounded agent/tool mediation, filtered JSON and POST-SSE responses, append-only feedback/evidence, and deterministic provider fakes for tests.
- Observability: optional Agent-only low-cardinality Prometheus metrics and a bounded identifier-free trace mirror; both are disabled by default and neither is business truth.
- Knowledge: versioned hybrid retrieval, reranking and sufficiency calibration, citations from committed public evidence, FAQ publication/synchronization, versioned cache, rebuild validation, and atomic forward alias switching.
- Sensitive action boundary: Commerce owns verified CB-118 PendingAction and immutable ActionReceipt truth. Agent CB-122 validates a prepared result, stores a local reference, and supports clarification, decline, and expiry. Exact Agent confirmation remains unavailable.
- Web: an intentionally small responsive React/TypeScript page for login, product browsing, idempotent seckill reservation/status, JSON or filtered SSE support turns, and a generic PendingAction notice. It adds no storefront or business authority.

## Truth ownership

- MySQL `commerce_db` is authoritative for products, inventory, orders, reservations, payments, refunds, Commerce PendingAction, and ActionReceipt.
- MySQL `cs_db` is authoritative for support sessions, conversations, event/evidence records, feedback, and the Agent-local PendingAction reference. That reference cannot override Commerce action truth.
- Elasticsearch is a derived public-knowledge index. Redis instances are non-authoritative admission projections or caches.
- Evaluation identity, sandbox, state, audit, and evidence routes exist only in the evaluation profile. The web never calls them.
- The browser never supplies user/owner truth. Its direct-user token is held only in React memory, is cleared on logout or `401`, and is not restored after refresh.

## Prerequisites and locked setup

Use Java 21, Python 3.11, `uv` 0.11.24, Node.js 24 with npm, GNU Make, Docker with Compose v2, OpenSSL, GNU `sha256sum`, `curl`, and `tar`.

```shell
make setup
```

This uses the committed Maven wrapper, `uv.lock`, and `web/package-lock.json`. Do not replace locked installs with ad-hoc dependency upgrades.

## Local data topology and migrations

Generate private synthetic credentials, start MySQL, two Redis instances, Elasticsearch/IK, and RocketMQ Broker/Proxy, then apply all three migration streams and exact grants:

```shell
make init-local
make up
```

`make up` runs `grant-access`, `migrate-auth`, `migrate-commerce`, and `migrate-agent` in the repository-defined order. To run those jobs explicitly against an existing topology:

```shell
make grant-access
make migrate-auth
make migrate-commerce
make migrate-agent
```

Normal shutdown preserves named volumes:

```shell
make down
```

The destructive reset is deliberately explicit: `make reset-local CONFIRM_RESET_LOCAL=1` removes local volumes and the generated `.env`.

## Application processes

The services require their documented runtime environment, database identities, URLs, signing material, and feature flags. The complete executable launch examples are maintained in the owning integration scripts; they generate temporary keys/credentials and do not print tokens. The underlying application entry points are:

```shell
./mvnw -pl auth-service spring-boot:run
./mvnw -pl commerce-service spring-boot:run
uv run citybuddy-agent
uv run citybuddy-indexer
```

### Optional Agent observability

Set `CITYBUDDY_METRICS_ENABLED=true` to expose the custom-registry-only internal endpoint at
`/internal/metrics/prometheus`. Any missing, empty, or case-insensitive `false` value keeps the
endpoint absent; other non-empty values are rejected at startup. The inventory is committed in
`observability/metrics-v1.json`. FAQ hit rates are calculated separately for mapping and answer as
`hit / (hit + miss)`, while Elasticsearch avoidance is
`cache_served / (cache_served + elasticsearch_issued)`. Provider-attempt counters are diagnostics
only and do not establish model-call savings.

Set `CITYBUDDY_TRACE_EXPORT_URL` to one fixed `http` or `https` endpoint to enable the Agent's
custom JSON trace mirror. Leaving it empty selects `Noop`: no worker, queue, network request, or
trace metric is created. The enabled mirror has a 64-item queue, 50 ms HTTP timeout, zero retry,
and a 300 ms shutdown bound. Its six-field payload contains only schema, service, bounded span and
outcome, duration, and occurrence time; it contains no identifiers, content, credentials, or raw
errors. This mirror is not OpenTelemetry and export success is never evidence or business truth.

Use these scripts as the reproducible, fully configured local service examples:

```shell
make test-identity-integration
make test-catalog-integration
make test-retrieval-evidence-integration
make test-knowledge-sync-integration
make test-knowledge-rebuild-integration
make test-evaluation-sandbox-integration
```

They exercise real local services and dependencies, use isolated temporary fixtures, and clean them
up. They are verified engineering evidence rather than long-running demo provisioning. The CB-151
generalized persistent demo/reset/fault harness was not merged and is not part of the current route.

## Web

Install the locked web packages and copy the public proxy-target example if the three APIs use ports other than the defaults shown there:

```shell
npm --prefix web ci
cp web/.env.example web/.env.local
npm --prefix web run dev
```

The Vite development server proxies `/auth` to auth-service, the product/seckill/reservation paths to commerce-service, and session/chat paths to agent-service. `web/.env.local` contains only public local targets; credentials do not belong there.

Build and inspect the static artifact:

```shell
npm --prefix web run build
npm --prefix web run preview
```

`preview` serves the generated static artifact. It is not a production reverse proxy or deployment.

## Ordered demonstration

CB-140 supplies the interactive web surface. Start from a direct-user fixture that has the
published-product, seckill-reservation, support-session, and support-chat permissions used by the
verified integration topology. The current route does not provide a persistent demo-data reset
command, so the automated commands below are the reproducible source of fixtures and service
evidence; do not claim an interactive manual run unless those services and equivalent fixtures are
actually active.

1. Run `make test-identity-integration` to verify login, server-owned support session creation, ordinary JSON chat, idempotent replay, and filtered SSE without private events.
2. Run `make test-catalog-integration` to verify published-product reads, reservation submission, owner-scoped polling, rejection, ordered/cancelled terminal truth, and no early order claim.
3. Run `make test-retrieval-evidence-integration` to verify the RAG answer/evidence path and public citation projection from sufficient stored evidence.
4. Run `make test-evaluation-sandbox-integration` for the full isolated CB-122 backend evidence: prepare, clarification, exact `decline`, server-observed expiry, and exact confirmation unavailability. Evaluation APIs used by that test are not called by the web.
5. Run `npm --prefix web test` for the browser-facing login/logout/expiry reset, product states, stable reservation intent, bounded polling, session reuse, JSON/SSE exclusivity, generic PendingAction notice, clarification, decline, expiry, reserved receipt rejection, and fixed confirmation-unavailable UI.
6. With equivalent long-running local services active, run `npm --prefix web run dev`, log in, inspect published products, submit the fixture activity id/version, observe only the returned reservation state, send an ordinary support turn, ask a public-knowledge question, prepare a sensitive action, clarify or use **拒绝此动作**, observe expiry only after a server response, and send the exact message `confirm` to observe the fixed unavailable result.

In JSON chat mode the page can show public citations. SSE mode shows only token text and the public terminal outcome because the current SSE contract carries no citations. A refresh signs the user out.

## Checks

Focused web checks:

```shell
npm --prefix web run format:check
npm --prefix web run lint
npm --prefix web run typecheck
npm --prefix web test
npm --prefix web run build
make web-ci
```

Repository checks:

```shell
make repo-ci
make ci
```

`make ci` runs Java, Python, web, repository hygiene/secret checks, and the ordered real local integration suite. It requires the prerequisites above and sufficient local Docker resources.

## Current limitations

CityBuddy has no cloud deployment or production real-provider claim, no measured performance or
quality result, and no operational-readiness claim. The optional Agent metrics and trace mirror do
not supply those claims. The current measurement-only route is CB-152 seckill evidence, CB-153
Agent-path latency evidence, and CB-154 retrieval/cache quality plus final aggregation; it ends at
CB-154. Future measurement commands must be ephemeral thin wrappers over `make init-local`,
`make up`, the required application processes, and explicit `make reset-local
CONFIRM_RESET_LOCAL=1`, not a `make demo-*` lifecycle framework. No runner or result artifact exists
yet.

The current Agent route has no successful confirmation, local ActionReceipt projection, `action_completed` turn, or receipt card. The blocked CB-121/CB-123 history does not make those capabilities available. The web never infers action type, amount, order, deadline, identifier, or terminal truth from reply prose.

MemoryPacker/watermarks, the PII/output-safety lane, handoff or `HUMAN_PENDING`, failure-candidate
export, cart, checkout, full storefront, agent workstation, multimodal intake, and deployment are
outside the current route.
