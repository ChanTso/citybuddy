# CityBuddy implementation index

**Document version:** v0.3\
**Verification date:** 2026-07-15\
**Repository phase:** MySQL and dual Redis foundations ready

## How to use this index

1. This file is the canonical source for slice names, priority, dependencies, ordering, and status.
2. Find the single route row marked `READY` or `IN_PROGRESS`, then begin with its linked slice specification.
3. From that slice file, begin with the directly listed sections of [the frozen contracts](docs/CONTRACTS.md).
4. Prefer this focused context over a broad scan. Search or read other slice specifications when an explicit dependency, shared contract, sequencing question, or frozen-contract conflict makes that useful; treat them as read-only context and do not implement them early.
5. A slice without a linked, complete specification cannot move to `IN_PROGRESS`.
6. Pull requests contain detailed commands, results, rejection evidence, and reviewer findings. Slice files keep only the concise Completion record.

## Current repository truth

CityBuddy targets local-commerce transactions and text-only AI customer support with independent identity, side-effect, retrieval, and evaluation boundaries.

The repository contains the verified CB-000, CB-010, and CB-011 foundations:

- executable non-business skeletons for `auth-service`, `commerce-service`, `agent-service`, `knowledge-indexer`, and `web`;
- one Maven reactor and wrapper, one locked `uv` workspace, and one npm lockfile;
- one pinned, health-gated MySQL instance with isolated migration/runtime identities, separate migration histories, and non-default-role grant delegation;
- two pinned, authenticated, health-gated Redis instances with distinct URLs, credentials, containers, and named volumes: Commerce uses AOF plus `noeviction`, while Support uses bounded `volatile-lfu` for TTL-bearing cache data;
- format, lint, type/compile, unit-test, build, pre-commit, Gitleaks, and GitHub Actions paths through `make ci`.

It does not yet contain business behavior, API/worker runtime packaging, production business schemas, Elasticsearch/IK, RocketMQ, aggregate runtime closure, model-provider access, deployment, or measured performance claims.

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
| [CB-012 — Elasticsearch and IK runtime foundation](docs/slices/CB-012.md) | P0 | `IN_PROGRESS` | `CB-011` |
| [CB-013 — RocketMQ Broker and Proxy foundation](docs/slices/CB-013.md) | P0 | `PLANNED` | `CB-012` |
| [CB-014 — Local runtime integration closure](docs/slices/CB-014.md) | P0 | `PLANNED` | `CB-013` |
| [CB-085 — Python RocketMQ consumer viability spike](docs/slices/CB-085.md) | P0 | `PLANNED` | `CB-014` |
| `CB-020 — Identity, JWKS and JIT OBO vertical slice` | P0 | `PLANNED` | `CB-014` |
| `CB-030 — Product catalog and cache invalidation` | P0 | `PLANNED` | `CB-020` |
| `CB-040 — Standard ordering and MySQL inventory` | P0 | `PLANNED` | `CB-030` |
| `CB-050 — Seckill quota, reservation, and Lua admission` | P0 | `PLANNED` | `CB-040` |
| `CB-060 — RocketMQ transaction ordering and delayed cancellation` | P0 | `PLANNED` | `CB-050` |
| `CB-070 — Mock payment, refund, ledger extension, and state machines` | P0 | `PLANNED` | `CB-060` |
| `CB-080 — Single-agent control plane, tools, SSE, and support evidence` | P0 | `PLANNED` | `CB-020`, `CB-030`, `CB-040` |
| `CB-090 — RAG core and initial versioned knowledge index` | P0 | `PLANNED` | `CB-014`, `CB-080` |
| `CB-100 — Evaluation profile, sandbox, identity provisioning, state, audit, version, and evidence` | P0 | `PLANNED` | `CB-020`, `CB-040`, `CB-060`, `CB-070`, `CB-080`, `CB-090` |
| `CB-110 — FAQ publication, knowledge sync, cache versioning, and index rebuild` | P1 | `PLANNED` | `CB-030`, `CB-085`, `CB-090` |
| `CB-120 — PendingAction, ActionReceipt, and turn commit point` | P1 | `PLANNED` | `CB-070`, `CB-080` |
| `CB-130 — Memory watermarks, tiered output, and human handoff` | P1 | `PLANNED` | `CB-110`, `CB-120` |
| `CB-140 — Minimal web demonstration` | P1 | `PLANNED` | `CB-020`, `CB-030`, `CB-060`, `CB-080`, `CB-090`, `CB-120` |
| `CB-150 — Observability, scripted demonstration, fault drills, and measured evidence` | P1 | `PLANNED` | `CB-100`, `CB-130`, `CB-140` |
| `CB-900 — Multimodal intake and object storage outline` | P2 | `DEFERRED` | Explicit promotion only |
| `CB-910 — Action recovery scanning and advanced resilience outline` | P2 | `DEFERRED` | Explicit promotion only |
| `CB-920 — Advanced retrieval, provider-cache experiments, and expanded operations views` | P2 | `DEFERRED` | Explicit promotion only |

## Change control

- Editorial changes do not alter behavior, ownership, security, sequencing, or acceptance.
- Implementation details preserve every frozen contract and slice dependency and are proven through executable configuration and tests.
- Frozen-contract changes include service/language responsibility, truth ownership, security boundaries, token claims, sandbox semantics, transaction mainlines, action truth, development order, or committed P0/P1 scope.
- A frozen-contract conflict marks the affected slice `BLOCKED` before replacement criteria are proposed.
- The full classification, required spikes, and risk register are in [docs/CONTRACTS.md](docs/CONTRACTS.md#contracts-risks-change-control).
