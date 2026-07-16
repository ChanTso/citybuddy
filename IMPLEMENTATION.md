# CityBuddy implementation index

**Document version:** v0.13\
**Verification date:** 2026-07-16\
**Repository phase:** Seckill transaction-message order creation verified; delayed unpaid cancellation ready

## How to use this index

1. This file is the canonical source for slice names, priority, dependencies, ordering, and status.
2. Find the single route row marked `READY` or `IN_PROGRESS`, then begin with its linked slice specification.
3. From that slice file, begin with the directly listed sections of [the frozen contracts](docs/CONTRACTS.md).
4. Prefer this focused context over a broad scan. Search or read other slice specifications when an explicit dependency, shared contract, sequencing question, or frozen-contract conflict makes that useful; treat them as read-only context and do not implement them early.
5. A slice without a linked, complete specification cannot move to `IN_PROGRESS`.
6. Pull requests contain detailed commands, results, rejection evidence, and reviewer findings. Slice files keep only the concise Completion record.

## Current repository truth

CityBuddy targets local-commerce transactions and text-only AI customer support with independent identity, side-effect, retrieval, and evaluation boundaries.

The repository contains the verified local-runtime foundations, identity vertical slice, product catalog vertical slice, standard-ordering vertical slice, and seckill reservation, admission, and durable-order mainline:

- executable non-business skeletons for `auth-service`, `commerce-service`, `agent-service`, `knowledge-indexer`, and `web`;
- one Maven reactor and wrapper, one locked `uv` workspace, and one npm lockfile;
- one pinned, health-gated MySQL instance with isolated migration/runtime identities, separate migration histories, and non-default-role grant delegation;
- two pinned, authenticated, health-gated Redis instances with distinct URLs, credentials, containers, and named volumes: Commerce uses AOF plus `noeviction`, while Support uses bounded `volatile-lfu` for TTL-bearing cache data;
- one digest-pinned Elasticsearch 8.19.8 runtime with checksum-verified analysis-ik 8.19.8, analyzer-gated health, and disposable dense-vector, kNN, and atomic-alias evidence;
- one digest-pinned RocketMQ 5.5.0 NameServer and combined Broker/Proxy runtime with behavior-gated readiness, a Java 5.x gRPC client route probe, and a disposable uniquely identified normal-message round trip;
- one ordered local and CI integration entry point proving clean aggregate startup, seven health gates, three migration histories, repeat-start idempotence, credential preservation, non-destructive shutdown, component probes, and controlled failures;
- RS256 direct-user login and JWKS publication, server-owned support sessions, independently authenticated exact-scope JIT OBO exchange, and fail-closed commerce authorization with bounded key refresh and overlap handling;
- commerce-owned product and CRM truth, authenticated published-product reads, a non-authoritative Redis cache with bounded penetration and hot-key protection, and transactional Outbox plus idempotent RocketMQ invalidation;
- direct-user standard ordering with server-authoritative product and price snapshots, user-scoped idempotency, atomic MySQL stock/order/Outbox commit, bounded recognized-conflict retries, and least-privilege runtime grants;
- MySQL-authoritative seckill activity allocation with versioned post-commit Commerce Redis projection, stale/conflicting/malformed write rejection, MySQL-only rebuild, and exact least-privilege activity grants;
- MySQL-authoritative seckill reservation intent/status with owner-scoped idempotency and polling, atomic Commerce Redis Lua admission and deterministic decisions, bounded TTL/version/number invariants, MySQL-only reservation rebuild, and exact least-privilege reservation grants;
- public owner-scoped seckill reservation submission and polling, half-message-before-Lua transaction admission, durable-marker-only checkback, restart-stable bounded deadline convergence, and idempotent atomic MySQL order/reservation/order-create-ledger/unpaid-timeout creation;
- format, lint, type/compile, unit-test, build, pre-commit, Gitleaks, and GitHub Actions paths through `make ci`.

It does not yet contain delayed unpaid cancellation or inventory/activity-quota restoration; payment, refund, or support-agent business behavior; production Elasticsearch knowledge indexes; model-provider access; deployment; or measured performance claims.

Cross-slice target architecture, preflight conclusions, service/data ownership, interface and security boundaries, sequence diagrams, route outcomes, risks, and change control live in [docs/CONTRACTS.md](docs/CONTRACTS.md).

## Slice workflow

- At most one active slice exists.
- Before implementation begins, the active slice is the only `READY` row.
- Creating its feature branch and beginning its first implementation change moves that same row to `IN_PROGRESS`.
- Work continues only on that slice until it becomes `VERIFIED`, `BLOCKED`, or `DEFERRED`.
- P0 and P1 slices remain `PLANNED` until their dependencies are `VERIFIED`. P2 slices remain `DEFERRED` unless explicitly promoted.
- When multiple non-deferred slices become eligible, the earliest one in the route is selected unless a reviewed frozen-contract change alters the route.
- The rolling specification window is the active `READY`/`IN_PROGRESS` slice plus the next two non-`DEFERRED` route entries. Every row in that window must link to a complete specification before the active row can change. Specifications may be prepared earlier, but their presence does not make those slices active or authorize implementation.
- A hard conflict with a frozen contract marks the active slice `BLOCKED`; record a concise impact in its Completion record and keep detailed evidence in the pull request.

## Status meanings

| Status | Meaning |
|---|---|
| `PLANNED` | Route and outcome are known, but the slice is not active. |
| `READY` | The active slice before implementation begins. |
| `IN_PROGRESS` | The same active slice after its branch and first implementation change begin. |
| `VERIFIED` | Acceptance criteria, rejection paths, required evidence, review blockers, and Completion record are satisfied. |
| `BLOCKED` | Evidence shows the active slice cannot safely meet a frozen contract without a decision or prerequisite. |
| `DEFERRED` | Deliberately outside or removed from the current route. |

## Complete route

The linked slice name is the canonical detailed specification. Target outcomes are retained separately in the [route outcome catalog](docs/CONTRACTS.md#contracts-route-outcomes).

| Slice | Priority | State | Depends on |
|---|---:|---:|---|
| [CB-000 — Repository and toolchain baseline](docs/slices/CB-000.md) | P0 | `VERIFIED` | Documentation baseline |
| [CB-010 — MySQL migration and access foundation](docs/slices/CB-010.md) | P0 | `VERIFIED` | `CB-000` |
| [CB-011 — Dual Redis runtime foundation](docs/slices/CB-011.md) | P0 | `VERIFIED` | `CB-010` |
| [CB-012 — Elasticsearch and IK runtime foundation](docs/slices/CB-012.md) | P0 | `VERIFIED` | `CB-011` |
| [CB-013 — RocketMQ Broker and Proxy foundation](docs/slices/CB-013.md) | P0 | `VERIFIED` | `CB-012` |
| [CB-014 — Local runtime integration closure](docs/slices/CB-014.md) | P0 | `VERIFIED` | `CB-013` |
| [CB-085 — Python RocketMQ consumer viability spike](docs/slices/CB-085.md) | P0 | `VERIFIED` | `CB-014` |
| [CB-020 — Identity, JWKS and JIT OBO vertical slice](docs/slices/CB-020.md) | P0 | `VERIFIED` | `CB-014` |
| [CB-030 — Product catalog and cache invalidation](docs/slices/CB-030.md) | P0 | `VERIFIED` | `CB-020` |
| [CB-040 — Standard ordering and MySQL inventory](docs/slices/CB-040.md) | P0 | `VERIFIED` | `CB-030` |
| [CB-050 — Seckill activity allocation and versioned Redis projection](docs/slices/CB-050.md) | P0 | `VERIFIED` | `CB-040` |
| [CB-051 — Seckill reservation, atomic Lua admission, and owner-scoped polling](docs/slices/CB-051.md) | P0 | `VERIFIED` | `CB-050` |
| [CB-060 — RocketMQ transaction admission and idempotent seckill order creation](docs/slices/CB-060.md) | P0 | `VERIFIED` | `CB-051` |
| [CB-061 — Delayed unpaid cancellation and ledger restoration](docs/slices/CB-061.md) | P0 | `IN_PROGRESS` | `CB-060` |
| [CB-070 — Idempotent mock payment, authenticated callback, and payment ledger transitions](docs/slices/CB-070.md) | P0 | `PLANNED` | `CB-061` |
| [CB-071 — Refund state machine and payment/refund reconciliation](docs/slices/CB-071.md) | P0 | `PLANNED` | `CB-070` |
| [CB-080 — Support conversation, event, and evidence lifecycle](docs/slices/CB-080.md) | P0 | `PLANNED` | `CB-020`, `CB-030`, `CB-040` |
| `CB-081 — Bounded agent, model routing, and ToolSpec control` | P0 | `PLANNED` | `CB-080` |
| `CB-082 — Filtered SSE, feedback, and deterministic support end-to-end evidence` | P0 | `PLANNED` | `CB-081` |
| `CB-090 — Versioned hybrid knowledge index and deterministic retrieval fusion` | P0 | `PLANNED` | `CB-014`, `CB-082` |
| `CB-091 — Rerank, sufficiency calibration, and retrieval evidence` | P0 | `PLANNED` | `CB-090` |
| `CB-100 — Evaluation identity provisioning and sandbox-bound token lifecycle` | P0 | `PLANNED` | `CB-020`, `CB-040`, `CB-061`, `CB-071`, `CB-082`, `CB-091` |
| `CB-101 — Evaluation sandbox lifecycle and fail-closed enforcement` | P0 | `PLANNED` | `CB-100` |
| `CB-102 — Evaluation state, audit, version, evidence, and async liveness guards` | P0 | `PLANNED` | `CB-101` |
| `CB-110 — FAQ publication truth and transactional Outbox` | P1 | `PLANNED` | `CB-030`, `CB-085`, `CB-091` |
| `CB-111 — Incremental knowledge sync and versioned two-level cache` | P1 | `PLANNED` | `CB-110` |
| `CB-112 — Knowledge rebuild validation and atomic alias switch` | P1 | `PLANNED` | `CB-111` |
| `CB-120 — Commerce PendingAction and atomic ActionReceipt transaction` | P1 | `PLANNED` | `CB-071`, `CB-082` |
| `CB-121 — Agent confirmation, receipt projection, and turn commit` | P1 | `PLANNED` | `CB-120`, `CB-082` |
| `CB-130 — Memory watermarks, prompt/PII, and tiered output safety` | P1 | `PLANNED` | `CB-112`, `CB-121` |
| `CB-131 — Authoritative handoff tickets and agent projection` | P1 | `PLANNED` | `CB-130` |
| `CB-132 — Reviewed failure-candidate capture and authenticated export` | P1 | `PLANNED` | `CB-131` |
| `CB-140 — Minimal web demonstration` | P1 | `PLANNED` | `CB-020`, `CB-030`, `CB-061`, `CB-082`, `CB-091`, `CB-121` |
| `CB-150 — Metrics and optional no-op trace sink` | P1 | `PLANNED` | `CB-102`, `CB-132`, `CB-140` |
| `CB-151 — Scripted reset/demo and repeatable fault drills` | P1 | `PLANNED` | `CB-150` |
| `CB-152 — Load, latency, and quality evidence` | P1 | `PLANNED` | `CB-151` |
| `CB-900 — Multimodal intake and object storage outline` | P2 | `DEFERRED` | Explicit promotion only |
| `CB-910 — Action recovery scanning and advanced resilience outline` | P2 | `DEFERRED` | Explicit promotion only |
| `CB-920 — Advanced retrieval, provider-cache experiments, and expanded operations views` | P2 | `DEFERRED` | Explicit promotion only |

## Change control

- Editorial changes do not alter behavior, ownership, security, sequencing, or acceptance.
- Implementation details preserve every frozen contract and slice dependency and are proven through executable configuration and tests.
- Frozen-contract changes include service/language responsibility, truth ownership, security boundaries, token claims, sandbox semantics, transaction mainlines, action truth, development order, or committed P0/P1 scope.
- A frozen-contract conflict marks the affected slice `BLOCKED` before replacement criteria are proposed.
- The full classification, required spikes, and risk register are in [docs/CONTRACTS.md](docs/CONTRACTS.md#contracts-risks-change-control).
