# CityBuddy

CityBuddy is a local-commerce transaction backend with a text-only AI customer-support agent
built on top of it. The point of the project is the boundary between the two: an LLM agent can
read business data and *prepare* sensitive actions such as refunds, but it cannot become the
authority on whether a transaction happened.

Everything below runs locally against real MySQL, Redis, Elasticsearch, and RocketMQ instances —
no in-memory substitutes and no mocked infrastructure in the integration suite.

## What is worth reading here

- **Delegated identity.** RS256 login and JWKS publication, plus just-in-time token exchange
  that mints an exact-scope on-behalf-of token per tool call. The OBO token binds `act.azp`,
  the user subject, the server-owned support session, and resource ownership; commerce rejects
  body-level identity substitution. See [§6 of the contracts](docs/CONTRACTS.md#6-interface-and-security-boundaries).
- **Seckill admission under contention.** Redis Lua performs atomic quota and one-order-per-user
  admission; MySQL holds authoritative reservation, order, inventory, and ledger truth. A
  RocketMQ transaction message binds the admission decision to durable order creation, and the
  transaction checker resolves from a persisted decision marker only.
- **Sensitive action truth.** Commerce owns `PendingAction` and an immutable `ActionReceipt`.
  Model prose is explicitly non-authoritative: SSE tokens cannot produce a success state, and a
  deterministic action-claim lexicon exists only as defense in depth.
- **Failure convergence.** Idempotency keys, unique constraints, an inventory ledger, and
  status/version CAS make duplicate delivery, unpaid-timeout cancellation, and partial refunds
  converge to one result. The real deadlock, snapshot, and precision problems found while
  proving that are written up in [docs/LESSONS.md](docs/LESSONS.md).

## Services

| Service | Runtime | Responsibility |
|---|---|---|
| `auth-service` | Java 21 / Spring Boot | Login, RS256 tokens, JWKS and key rotation, service-authenticated exact-scope OBO exchange |
| `commerce-service` | Java 21 / Spring Boot | Products, inventory, orders, seckill, payment, refund, reconciliation, internal tool APIs |
| `agent-service` | Python 3.11 / FastAPI | Support sessions, bounded ReAct agent, tool mediation, retrieval, SSE egress, durable evidence |
| `knowledge-indexer` | Python 3.11 | FAQ/product indexing, source-version ordering, tombstones, rebuild and alias switching |
| `web` | React / TypeScript | Small demonstration surface for the verified direct-user paths |

Data topology: MySQL (`commerce_db`, `cs_db`, auth), two Redis instances with different eviction
policies, Elasticsearch 8 with the IK analyzer, and RocketMQ 5 Broker/Proxy.

## Truth ownership

- MySQL `commerce_db` is authoritative for products, inventory, orders, reservations, payments,
  refunds, `PendingAction`, and `ActionReceipt`. MySQL `cs_db` is authoritative for support
  sessions, conversations, evidence, and feedback.
- Elasticsearch is a derived index. Both Redis instances are non-authoritative projections or
  caches. The browser never supplies user or owner truth.
- Evaluation-only identity, sandbox, state, and audit routes exist solely under the `evaluation`
  profile and are never called by the web surface.

Full ownership, invariant, and interface tables are in [docs/CONTRACTS.md](docs/CONTRACTS.md).

## Running it locally

Requires Java 21, Python 3.11, `uv` 0.11.24, Node.js 24, GNU Make, Docker with Compose v2,
OpenSSL, `sha256sum`, `curl`, and `tar`.

```bash
make setup
```

Generate synthetic local credentials, start the data topology, and apply all three migration
streams with their exact grants:

```bash
make init-local && make up
```

`make down` preserves named volumes. The destructive reset is deliberately explicit:
`make reset-local CONFIRM_RESET_LOCAL=1`.

Application entry points:

```bash
./mvnw -pl auth-service spring-boot:run
```

```bash
./mvnw -pl commerce-service spring-boot:run
```

```bash
uv run citybuddy-agent
```

```bash
uv run citybuddy-indexer
```

The web surface proxies the three APIs in development:

```bash
npm --prefix web ci && cp web/.env.example web/.env.local && npm --prefix web run dev
```

## Verification

These targets run against the real local topology with isolated temporary fixtures and clean up
after themselves. They are the reproducible evidence for the capabilities listed above:

```bash
make test-identity-integration
```

```bash
make test-catalog-integration
```

```bash
make test-retrieval-evidence-integration
```

```bash
make test-knowledge-sync-integration
```

```bash
make test-knowledge-rebuild-integration
```

```bash
make test-evaluation-sandbox-integration
```

The full gate — Java, Python, web, repository hygiene, secret scanning, and the ordered
integration suite — is:

```bash
make ci
```

## Current scope

CityBuddy has no cloud deployment, no real model-provider access, and no measured performance
result; the optional Agent metrics endpoint and JSON trace mirror are diagnostics and do not
supply one. Load and latency measurement is the next piece of work, and no throughput or
capacity claim should be read into this repository until raw results land here.

The agent can prepare, clarify, decline, and expire a sensitive action, but successful agent-side
confirmation and receipt projection are not implemented; the clients therefore render no
successful action state. Cart, checkout, a full storefront, agent workstation, multimodal intake,
PII/output-safety handling, and human handoff are out of scope.

Development rules are in [AGENTS.md](AGENTS.md). Retired process records are in
[docs/archive/](docs/archive/README.md).
