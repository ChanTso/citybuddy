# Archived route decisions and outcome catalog

These sections were removed from `docs/CONTRACTS.md` when the slice route was retired.
They are preserved verbatim for history. See [README.md](README.md).

## 8. Route outcome catalog

Slice status, priority, dependencies, and ordering live only in [ROUTE_HISTORY.md](ROUTE_HISTORY.md). This catalog preserves the target outcome for each route entry without duplicating mutable status.

| Slice | Target outcome |
|---|---|
| `CB-000` | Real module skeletons, pinned build/package entry points, meaningful checks/tests, pre-commit hygiene and staged secret scanning, Gitleaks, a working root `make ci`, and CI without provider keys. No business behavior. |
| `CB-010` | Health-gated MySQL, independent migration histories, explicit bootstrap/migration/runtime identities, synthetic local credential initialization, and real permission-denial checks without API containerization. |
| `CB-011` | Two health-gated Redis instances with distinct URLs and verified commerce no-eviction/AOF versus support LFU/TTL-oriented policies. |
| `CB-012` | A pinned Elasticsearch/IK pair with real analyzer, dense-vector, kNN, and atomic-alias evidence. |
| `CB-013` | A pinned RocketMQ 5 Broker/Proxy path with meaningful readiness and a normal-message round trip through the selected client path. |
| `CB-014` | Clean-environment orchestration, aggregate health and migration gates, integration checks, controlled startup failure, and resource-appropriate CI wiring across the completed local dependency topology. |
| `CB-085` | Early reproducible decision on Python connection, subscription, consumption, acknowledgement, retry/redelivery, long-processing behavior, source ordering, tombstones, and rebuild/alias handoff. No fallback is pre-approved. |
| `CB-020` | Explicit direct-user versus OBO token chains, login/JWKS, `POST /api/sessions` server-generated support-session ownership foundation, authenticated exchange with verified session binding, exact scope, commerce authorization, cross-user/sandbox rejection, and auth-table least-privilege evidence. |
| `CB-030` | Product and CRM truth; null-cache plus Bloom penetration protection, mutex hot-key rebuild, jittered TTLs, transactional MySQL change plus Outbox, request-side best-effort delete, consumer idempotent delete/rebuild, no request-thread cache/Elasticsearch dual write, and evidence that `auth_app` cannot access commerce business tables. |
| `CB-040` | Idempotent standard orders using MySQL stock updates, finite retry, ownership checks, and auditable rejection paths. |
| `CB-050` | Transactional seckill activity allocation and versioned Commerce Redis projection derived only after MySQL commit, with stale-version rejection and MySQL-based rebuild. |
| `CB-051` | MySQL-authoritative reservation intent/status, owner-scoped polling, and atomic quota/one-user/reservation Lua admission without claiming a durable order early. |
| `CB-060` | Half-message/Lua/commit-or-rollback with bounded `UNKNOWN`, `SP-003`, idempotent order consumption, final database uniqueness, and one atomic order/reservation/order-create-ledger/deadline transaction with stable timeout identity. |
| `CB-061` | Bounded handoff and delayed dispatch for every durable eligible timeout identity, followed by MySQL-authoritative conditional cancellation plus exactly-once inventory/activity-quota restoration ledger movements. |
| `CB-070` | Idempotent mock payment, authenticated callback, legal payment transitions, and payment ledger movements with duplicate and illegal-transition rejection. |
| `CB-071` | Refund state machine, refund ledger, payment/refund reconciliation, and proof that cancellation/payment/refund cannot restore inventory twice. |
| `CB-080` | FastAPI conversation/event/evidence lifecycle over CB-020 sessions with ordered `cs_db` truth and owner isolation. |
| `CB-081` | One bounded ReAct loop with shared `attempt_budget`, provider-scoped circuit breaker, main-Agent plus ToolSpec control, structured `deny_with_feedback`, and LiteLLM alias path to a fake provider. |
| `CB-082` | Filtered SSE, authorized feedback, deterministic model fakes, and support end-to-end evidence without raw tool or retrieval leakage. |
| `CB-090` | Initial `knowledge_docs_vN` alias plus `knowledge.search`, BM25/dense recall, and deterministic RRF hybrid retrieval. |
| `CB-091` | Rerank alias, sufficiency calibration, and retrieval evidence bound to the index version and sources actually used. |
| `CB-100` | Service-authenticated evaluation test-principal provisioning/revoke, opaque handles, test-token issuance, OBO sandbox binding, and identity lifecycle. No evaluator implementation. |
| `CB-101` | Reset-created fixtures and sandbox `PROVISIONING → ACTIVE → DEAD` lifecycle with fail-closed activation, compensation, completion, TTL/janitor closure, SQL filtering, and irreversible-side-effect stubs. |
| `CB-102` | Commerce-owned, sandbox-scoped evaluation state, audit, and version APIs. No agent evidence route or evaluator implementation. |
| `CB-103` | Agent-owned, sandbox/trace-bound evaluation evidence API over authoritative allowed `cs_db` evidence. No evaluator implementation. |
| `CB-104` | Executable zero-carrier inventory, real production-entry rejection, payload non-carriage, and reserved sandbox-property rejection; the complete liveness/drop obligation is frozen onto any future slice that first introduces an evaluation-reachable asynchronous carrier. No evaluator implementation. |
| `CB-105` | Sandbox-bound idempotent mock-payment callbacks with exact correlation, active-liveness enforcement, and no production shortcut. No evaluator implementation. |
| `CB-110` | FAQ draft/publish truth with publication version and transactional Outbox event. |
| `CB-111` | Python incremental synchronization with source-version and tombstone convergence. |
| `CB-112` | Versioned two-level FAQ cache with guarded query mapping and version-keyed answer entries. |
| `CB-113` | Complete knowledge snapshot, concurrent catch-up, full validation, and atomic initial read-alias switch. |
| `CB-114` | Predecessor catch-up through a fresh watermark, full revalidation, and atomic rollback without losing post-switch updates. |
| `CB-115` | Exact, server-owned cleanup of expired abandoned candidates and predecessors with active/rollback safety fences. |
| `CB-120` | Commerce-side PendingAction prepare/confirm with atomic validate/consume/execute/immutable ActionReceipt truth and server idempotency in one transaction. |
| `CB-116` | One shared committed-payment durable-truth closure and caller-specific authorization visibility contract across payment start, callbacks, and evaluation state/audit, with stable-key enumeration, cardinality/content reconciliation, total concealment, and server-only attribution. |
| `CB-117` | Physically bounded refund transactions and post-rollback contention recovery that return immutable validated lifecycle truth, restore pooled MySQL session policy, and expose only durable replay or typed indeterminate outcomes without partial effects. |
| `CB-118` | Commerce-side PendingAction prepare/confirm with atomic validate/consume/refund/immutable ActionReceipt truth and server idempotency in one transaction, consuming the verified CB-116/CB-117 boundaries without redesigning them. |
| `CB-121` | Agent confirmation, receipt projection, turn commit point, and model/network retry that regenerates text without commerce re-execution. |
| `CB-122` | Agent-owned PendingAction reference, total bounded action parsing, complete conversation/evaluation event closure, and deterministic clarification/decline/expiry with exact confirmation fixed unavailable and no commerce confirm effect. |
| `CB-123` | Exact confirmation arbitration, fresh OBO, complete untrusted receipt validation, atomic immutable ActionReceipt projection/event/turn commit, and receipt-first loss/restart/concurrent replay without a second commerce mutation. |
| `CB-130` | Retained vNext design for summary watermarks, prompt construction, PII handling, and tiered output safety; not part of the current portfolio route. |
| `CB-131` | Retained vNext design for Commerce-owned authoritative handoff ticket/SLA/Outbox, controlled `handoff_packet`, agent projection, `HUMAN_PENDING`, and open-ticket sensitive-write prohibition; not part of the current portfolio route. |
| `CB-132` | Retained vNext evolution for reviewed/masked/synthetic failure-candidate capture and authenticated bundle export; not part of the current portfolio route. |
| `CB-140` | Minimal portfolio web plus factual README for login, products, seckill reservation/status, support chat, and PendingAction prepare/clarification/decline/expiry. No successful confirmation, receipt card, cart, full store, or workstation. |
| `CB-150` | Agent-only operational metrics for verified paths and an optional default-no-op Agent trace sink that never becomes business or evidence truth. |
| `CB-151` | Scripted reset/demo and phase-bound fault drills only for verified identity, catalog, ordering/seckill, payment/refund, RAG, chat, and PendingAction prepare/decline/expiry paths. |
| `CB-152` | Blocked historical attempt at the seckill-measurement outcome. It produced no valid result or credit and is superseded only for the unfulfilled outcome by CB-155. |
| `CB-155` | Fresh JMeter seckill load evidence with authoritative Commerce Q01-Q09 concurrency correctness and independently committed result artifacts, as the sole replacement for CB-152's unfulfilled output. |
| `CB-153` | Locust/Mock-LLM path-separated Agent latency evidence with independently committed result artifacts. |
| `CB-154` | RAG quality, FAQ/cache and Elasticsearch-avoidance evidence plus a five-output aggregate that references rather than rewrites CB-155/153 results. |
| `CB-900` | Future multimodal boundary only. |
| `CB-910` | Future recovery scanner and observed-failure-driven resilience only. |
| `CB-920` | Optional experiments and expanded views; no result assumed. |

<a id="contracts-risks-change-control"></a>


### 10.3 Change classification

- **Level 1 — editorial:** naming, wording, links, or document placement that does not change behavior, ownership, security, sequencing, or acceptance. Correct directly and record it in the normal pull request.
- **Level 2 — implementation detail:** exact patch versions, image digests, migration library, package layout, test framework settings, or an equivalent implementation technique that preserves every frozen contract and slice dependency. Pin it in executable configuration and prove it with tests.
- **Level 3 — frozen-contract change:** service/language responsibility, truth ownership, security boundary, token claims, sandbox semantics, transaction-message mainline, action truth, development order, or committed P0/P1 scope. When implementation evidence exposes a conflict, mark the affected slice `BLOCKED` first and record the failed test/spike or incompatible primary-source evidence. A separately reviewed pre-implementation route decision records its rationale, exact contract touched, impact radius, migration/operational cost, and replacement acceptance criteria without fabricating a failed implementation or `BLOCKED` state. No fallback is approved by anticipation.

**Resolved Level 3 route decision — 2026-07-14:** Before CB-010 implementation began, the repository owner approved partitioning its original local-runtime outcome into sequential CB-010 through CB-014 delivery units. Independent review found that MySQL identities/migrations, dual Redis, Elasticsearch/IK, RocketMQ, and aggregate orchestration/CI were separate failure domains in one oversized review unit; it also found ambiguity around API startup and first-run local credentials. The total committed behavior, service and language boundaries, truth ownership, security rules, and rejection evidence are unchanged. Downstream dependencies that require the complete runtime move from CB-010 to CB-014; CB-085 additionally names the CB-012 Elasticsearch and CB-013 RocketMQ prerequisites. No runtime, schema, or stored-data migration is required because the original CB-010 was still `READY` and unimplemented. The linked CB-010 through CB-014 specifications are the replacement acceptance criteria, with CB-014 retaining clean-environment aggregate startup, failure propagation, and CI closure.

**Resolved Level 3 route decision — 2026-07-15:** CB-010 implementation analysis exposed a conflict between the absolute bootstrap prohibition on business-data capability in [storage topology](#contract-storage-truth) and [runtime access](#contract-runtime-access), and MySQL's requirement that a grantor possess delegated privileges with `GRANT OPTION`. Before any CB-010 implementation landed, the repository owner approved the standard MySQL role boundary: delegation privileges live in a dedicated non-default role, `activate_all_roles_on_login=OFF`, and only an explicit one-shot grant job may activate it for a fixed version-controlled allowlist of exact `GRANT`/`REVOKE` statements. The job rejects caller-supplied SQL and business DML, clears the role to `NONE`, and neither its credentials nor role appear in migration or runtime configuration. The impact radius is CB-010 bootstrap/grant automation and later reviewed updates to its grant manifest; service ownership, runtime least privilege, database topology, and business-data access remain unchanged. Operational cost is one short-lived grant job, role-state assertions, and negative tests; there is no schema or stored-data migration because CB-010 remains unimplemented on `main`. Keeping an absolute capability prohibition is not viable under MySQL grant semantics, while using `root` or granting the bootstrap account direct always-active data privileges would weaken credential isolation. The updated CB-010 acceptance criteria and rejection evidence are the replacement acceptance criteria.

**Resolved Level 3 route decision — 2026-07-15 (post-CB-030 refinement):** Before CB-040 or any affected successor began implementation, the repository owner explicitly approved a reviewed replacement map for the unstarted route. The complexity gate found multiple independently testable outcomes and failure/recovery domains inside the former CB-050, CB-060, CB-070, CB-080, CB-090, CB-100, CB-110, CB-120, CB-130, and CB-150 review units. The approved replacements are CB-050/051, CB-060/061, CB-070/071, CB-080/081/082, CB-090/091, CB-100/101/102, CB-110/111/112, CB-120/121, CB-130/131/132, and CB-150/151/152; CB-040 and CB-140 remain single slices. CB-040 remains the sole `READY` row, every replacement row is `PLANNED`, and no affected implementation branch or pull request existed when the decision was made.

The remapped route dependencies are: CB-050 ← CB-040; CB-051 ← CB-050; CB-060 ← CB-051; CB-061 ← CB-060; CB-070 ← CB-061; CB-071 ← CB-070; CB-080 ← CB-020/030/040; CB-081 ← CB-080; CB-082 ← CB-081; CB-090 ← CB-014/082; CB-091 ← CB-090; CB-100 ← CB-020/040/061/071/082/091; CB-101 ← CB-100; CB-102 ← CB-101; CB-103 ← CB-102; CB-104 ← CB-103; CB-105 ← CB-104; CB-110 ← CB-030/085/091; CB-111 ← CB-110; CB-112 ← CB-111; CB-120 ← CB-071/082; CB-121 ← CB-120/082; CB-130 ← CB-112/121; CB-131 ← CB-130; CB-132 ← CB-131; CB-140 ← CB-020/030/061/082/091/121; CB-150 ← CB-105/132/140; CB-151 ← CB-150; and CB-152 ← CB-151. Owning-slice references in the data, API, asynchronous, outcome, spike, and risk maps are remapped to the smallest replacement that closes the same invariant.

The total committed P0/P1 outcomes, priorities, service/language responsibilities, truth ownership, security rules, RocketMQ transaction-message mainline, and ActionReceipt truth are unchanged. In particular, CB-060 keeps order creation, reservation transition, final database uniqueness, order-create ledger movement, unpaid deadline, and stable timeout identity in one MySQL transaction; CB-061 owns the bounded scheduling handoff, delayed dispatch, and atomic cancellation/restoration transaction. CB-070 keeps payment transition, authenticated callback, payment ledger, and payment-timeout extension together; CB-071 owns refund and full reconciliation. CB-101 keeps reset/provision/activate-or-compensate fail closed. CB-110 keeps FAQ publish plus Outbox in one transaction. CB-120 keeps commerce-side confirmation validation, one-time consume, business mutation, and ActionReceipt persistence in one transaction; CB-121 cannot re-execute it. CB-131 keeps authoritative ticket/SLA/Outbox, agent projection, `HUMAN_PENDING`, and the open-ticket sensitive-write guard as one end-to-end invariant, while the then-numbered CB-112 keeps rebuild validation and atomic alias switch. There is no runtime, schema, or stored-data migration cost because all affected work was unstarted. The linked CB-050 through CB-061 specifications are the immediate replacement acceptance criteria and rolling runway; every later unlinked row remains gated by the normal rolling-specification rule before activation.

**2026-07-20 numbering supplement to the 2026-07-15 decision:** The two preceding historical paragraphs retain the identifiers approved at that time. Their combined `CB-111` knowledge synchronization/cache outcome is now refined into `CB-111` incremental version/tombstone convergence and `CB-112` versioned two-level FAQ cache, while every historical reference there to the then-numbered `CB-112` rebuild/alias outcome maps to the current sequential `CB-113` initial switch, `CB-114` rollback, and `CB-115` cleanup outcomes. The current dependency tail is `CB-110 ← CB-030/085/091`, `CB-111 ← CB-110`, `CB-112 ← CB-111`, `CB-113 ← CB-112`, `CB-114 ← CB-113`, `CB-115 ← CB-114`, and `CB-130 ← CB-115/121`; all other dependencies in that historical decision remain unchanged.

**Resolved Level 3 route decision — 2026-07-16 (CB-060 transaction terminal convergence):** Independent CB-060 review found that a Broker-discarded transaction could leave its MySQL reservation permanently `PENDING`. With `transactionCheckMax=3`, real Broker/Proxy/Java-client evidence expected three checkbacks but observed two; the discrepancy remains diagnostic evidence and is not a protocol contract. The Proxy also rejected application subscription to `TRANS_CHECK_MAX_TIME_TOPIC` with response `40002 cannot access system topic`. Two local recovery drafts—subscribing to the system topic and mutating timeout state from checker callback count and message payload—were discarded without commit because the first is unsupported by the Proxy and the second violates the durable-marker-only checker boundary. The repository owner approved preserving the transaction-message mainline while adding one persisted `transaction_resolution_due_at`, derived once from the configured timeout/check/max-count upper bound plus safety margin, and one indexed bounded CB-060 deadline decision worker. The worker reads MySQL reservation truth, uses Redis Lua compare-and-set so an existing admitted or rejected marker always wins, creates `TRANSACTION_TIMEOUT` only when the marker is absent, and idempotently converges MySQL after the marker decision; restart does not recompute the deadline, Redis failure is not interpreted as absence, and no RocketMQ payload or callback count becomes decision truth. Broker terminal outcome remains independently evidenced through `mqadmin` against the system terminal topic, which the application never consumes. The impact radius is CB-060's reservation schema, exact runtime DML grant, Redis admission/timeout scripts, deadline worker, public status convergence, and real failure tests. Operational cost is one indexed bounded worker and restart-safe convergence; no external dependency, service/language boundary, truth owner, later-slice behavior, or generalized recovery scanner is added. Replacement acceptance requires both admission/timeout race orders to produce one marker; convergence after Redis success/MySQL failure and restart; fail-indeterminate Redis outage; admitted-marker preservation; no timeout order or ledger; pure marker-only checker evidence; and convergence of the pre-half-send, post-half/no-marker, and post-marker/pre-MySQL crash windows.

**Resolved Level 3 route decision — 2026-07-18 (CB-082 public action-status channel):** Two independent CB-082 recovery reviews demonstrated that a deterministic word list cannot completely classify free-form natural-language action-completion claims: after blocking `Your cancellation is complete.`, later reviews found `It has been refunded.`, `I cancelled it for you.`, `Your refund has been issued.`, and `The payment went through.`, while broad matching also rejected explicit negation. The repository owner approved clarifying the existing [agent/action truth boundary](#contract-agent-action-evidence): public SSE `token` is non-authoritative explanation prose, and the only public action-status carrier is `action_receipt` derived from immutable ActionReceipt truth. Later web and client slices may render action status only from that event. CB-082 retains a normalized, documented, bounded action-claim lexicon solely as defense in depth; residual uncovered wording is recorded risk, not a completeness guarantee, while documented known forms fail closed and explicit negation does not. The impact radius is CB-082's acceptance/rejection evidence, SSE guard documentation and regression corpus, the API-map success semantics, the model-text risk guardrail, and later client rendering rules. Operational cost is limited to deterministic guard tuning and tests; there is no route, dependency, service/language, API schema, database, migration, stored-data, or external-dependency change. Replacement acceptance requires the documented bounded lexicon and normalization to reject its known unreceipted claims, fixed explicit-negation cases to remain ordinary prose, synthetic `action_receipt` input to fail closed before CB-120/CB-121, and tests/contracts to prove that prose cannot create or impersonate the receipt channel. This preserves “ActionReceipt is the action truth / retries regenerate explanation only” and does not authorize clients to infer action state from prose.

**Resolved Level 3 route decision — 2026-07-18 (CB-102 evaluation-boundary partition):** The CB-091 closeout complexity gate found that the original unstarted CB-102 combined four independently deliverable outcomes across separable service and truth boundaries: commerce-owned state/audit/version APIs, an agent-owned `cs_db` evidence API, participating asynchronous-consumer liveness enforcement, and sandbox-bound mock-payment callbacks. Following the established CB-010 partition precedent, the repository owner approved replacing that one P0 row with the sequential P0 route CB-102 through CB-105: commerce evaluation state/audit/version APIs, agent evaluation evidence API, asynchronous sandbox liveness guards, and sandbox-bound idempotent mock-payment callbacks. The total committed behavior, priority, service/language boundaries, truth ownership, security rules, rejection evidence, evaluation-profile isolation, and “no evaluator implementation” boundary are unchanged; this decision only creates reviewable delivery units and does not authorize early implementation. CB-103, CB-104, and CB-105 depend sequentially on their predecessor, and downstream work that required the complete original CB-102 outcome, including CB-150, now depends on CB-105. There is no runtime, schema, stored-data, or migration cost because the original CB-102 had no feature branch, pull request, complete linked specification, or implementation. The linked CB-102 specification and later rolling-window specifications are the replacement acceptance criteria, while the API, asynchronous, route-outcome, dependency, and risk maps retain the same aggregate boundary under the smallest owning replacement slice.

**Resolved Level 3 route decision — 2026-07-19 (CB-104 zero-carrier closure):** Independent complete-diff review exposed that the proposed seckill guard evidence bypassed the real production-only controller and injected sandbox context through direct coordinator/repository and Broker test paths. A complete executable inventory then proved that the current repository has zero evaluation-reachable asynchronous producer/consumer paths: seckill reservation and its derived unpaid-timeout path are production-only, product publication is production-only, standard-order/refund Outbox rows have no selecting runtime publisher, CB-085 is a disposable spike, and agent-service has no asynchronous runtime. Full sandbox isolation of shared seckill activity, product, inventory, ledger, quota, and Redis truth is not an evaluation-mainline outcome and remains eligible only for a future dedicated slice. The repository owner therefore approved narrowing CB-104 to the six-row executable inventory, real production-entry rejection, payload non-carriage, and real Broker reserved-property rejection evidence, while removing all unprovable liveness-guard runtime work and mock evidence. The complete guard obligation moves to the first future slice that introduces an evaluation-reachable asynchronous carrier and must be delivered there with real redelivery, completion-race, restart, outage, and idempotent drop/archive evidence. Total promised behavior, priority, service/language and truth boundaries, sandbox security, CB-105 and downstream dependencies, and the prohibition on evaluator implementation are unchanged; no runtime or stored-data migration is required.

**Resolved Level 3 route decision — 2026-07-19 (CB-111 knowledge-delivery partition):** When CB-111 first entered the rolling specification window during CB-104 closeout, independent review applied the complexity gate and found two independently deliverable outcomes with separable rejection/recovery evidence and service/truth boundaries: Python/RocketMQ-to-Elasticsearch incremental source-version/tombstone convergence, and agent-service/knowledge-indexer-to-Support-Redis versioned query/answer caching integrated with retrieval evidence. Following the CB-010 and CB-102 partition precedents, the repository owner approved replacing the combined CB-111 outcome with sequential CB-111 incremental knowledge synchronization, CB-112 versioned two-level FAQ cache, and renumbering the former CB-112 rebuild/atomic-alias outcome to CB-113. CB-112 depends on CB-111 and CB-113 depends on CB-112. The 2026-07-20 route decision below further partitions that unstarted rebuild outcome and supersedes only this paragraph's current downstream dependency mapping. Total committed behavior, priority, Python/service ownership, MySQL/Elasticsearch/Support-Redis truth hierarchy, security rules, asynchronous envelope and rejection evidence are unchanged; this decision only creates reviewable delivery units and does not authorize early implementation. There is no runtime, schema, stored-data, migration, or operational transition cost because CB-111, the former CB-112, and their dependent work are unstarted. The linked CB-111 specification and later rolling-window specifications under their current identifiers are the replacement acceptance criteria.

**Resolved Level 3 route decision — 2026-07-20 (knowledge rebuild lifecycle partition):** During CB-110 independent review, the complexity gate found that the unstarted CB-113 specification combined three independently deliverable outcomes with separable acceptance, rejection, recovery, truth-state, and destructive-operation evidence: authoritative snapshot/catch-up/full validation plus the initial atomic alias switch; predecessor catch-up/full revalidation plus atomic rollback; and destructive cleanup of expired abandoned candidates or predecessors. The repository owner approved replacing the monolithic outcome with the sequential P1 route `CB-113` snapshot/catch-up/validation/atomic switch, `CB-114` catch-up/revalidation/atomic rollback, and `CB-115` expired-candidate/predecessor safety cleanup. CB-114 depends on CB-113, CB-115 depends on CB-114, and every downstream dependency on completion of the former full CB-113 lifecycle, including CB-130, moves to CB-115. Total behavior, priority, Python/service ownership, MySQL/Elasticsearch/Support-Redis truth hierarchy, public-field and retrieval contracts, security boundaries, and production-only asynchronous rules are unchanged; the split only creates reviewable delivery units and does not authorize early implementation. There is no runtime, schema, stored-data, migration, or operational transition cost because the affected rebuild lifecycle and downstream work are unstarted and the previously unused numeric identifiers CB-114 and CB-115 require no renumbering. The linked CB-113, CB-114, and CB-115 specifications are the replacement acceptance criteria.

**Resolved Level 3 route decision — 2026-07-19 (independent-review structural gate):** Repeated independent reviews across completed slices found recurring false-green classes after earlier checks had passed: existence disclosure drift, incomplete canonical idempotency comparisons, partial parser exception handling, ordering expressed differently by production SQL/public contract/test evidence, and one-way audit validation that could not prove durable completeness. Before CB-105 closeout, the repository owner approved a mandatory implementer self-review gate in `docs/REVIEW_CHECKLIST.md`. Before every independent review request, the primary implementer must execute the complete checklist against the complete current diff and record concrete conclusions or precise not-applicable rationales in the pull request; any later semantic diff change invalidates and repeats both the self-review and independent review. Each closeout extends the checklist with every newly evidenced recurring class, or records in the pull request that no new class exists without adding placeholders or weakening existing checks. The impact radius is repository governance, review sequencing, pull-request evidence, and future closeout maintenance only. Operational cost is one explicit self-review pass before each independent review. No service/language responsibility, production behavior, truth owner, security or transaction boundary, route dependency, API/schema, migration, stored data, external dependency, or acceptance criterion is changed. The replacement acceptance is the enforceable `AGENTS.md` gate plus the five initial recurring-class checks and their CB-105 complete-diff independent review.

**Resolved Level 3 contract decision — 2026-07-22 (terminal consumer classification):** The repository owner approved a cross-consumer rule after the same classification error appeared in three mechanisms: RocketMQ transaction checkback could treat an indeterminate result as terminal, evaluation authorization could fold dependency unavailability into 403 rejection, and CB-112 initially folded malformed derived-cache state into a permanent knowledge-sync conflict and ACK. The exact contract change is the messaging rule above: only a positively established business conclusion may terminate consumption; integrity faults, dependency failures, timeouts, contradictory state, and indeterminate outcomes remain retryable or unavailable. The impact radius is every current and future asynchronous consumer plus its review evidence; it changes no message schema, service/language or truth ownership, API response schema, route dependency, migration, stored data, or external dependency. Operational cost is explicit failure taxonomy and a persisted-state × mutation-phase disposition matrix where a consumer spans multiple phases. Replacement acceptance for CB-112 is the complete fence-state × Elasticsearch-phase matrix, retryable malformed-state recovery, and bounded physical TTL; later consumer slices must apply the same classification rule at their own boundaries.

**Resolved Level 3 scope decision — 2026-07-22 (CB-112 constructive completeness boundary):** After live Redis enumeration replaced two failed source-text closure mechanisms, independent review found a real positive-TTL corruption that remained terminally ACKed because FINALIZE lacked PREPARE's TTL upper-bound and lease-safety guards. The owner approved fixing that concrete asymmetry while defining CB-112's adversarial completeness claim over exactly four mechanically enumerable ground truths: live Redis keys, fields and key-level properties; persisted classes `missing`, `ready0`, `ready1-live`, and `ready1-tombstone`; phases before Elasticsearch and after Elasticsearch success but before Redis finalization; and exact phase-scoped runtime coverage of one production discriminator registry. Exhaustive partitioning of every attribute value domain is explicitly outside this claim: value partitions have no mechanically enumerable ground truth, and a semantic guard absent from production cannot be discovered by code coverage. That residual risk is accepted and recorded in the CB-112 specification; targeted design review remains responsible for semantic guard completeness. Replacement acceptance requires one shared PREPARE/FINALIZE preparation-state guard, including physical-TTL upper bound and strict remaining-lease-plus-safety-margin checks; real regressions for the identified TTL partitions; exact registered-versus-observed discriminator equality in both phases; and the existing Worker disposition plus Redis/Elasticsearch convergence evidence. A CB-112 same-class review finding blocks closeout only when it identifies an omission inside one of the four included ground truths; an additional value-domain partition is recorded as design-review advice under the accepted residual risk. The impact radius is CB-112 implementation, specification, review checklist and evidence only. No service/language responsibility, truth owner, security or transaction boundary, message/API schema, route dependency, migration, stored data, external dependency, or later-slice acceptance is changed; operational cost is the shared guard and phase-scoped registry check.

**Resolved Level 3 scope decision — 2026-07-22 (evaluation mock-payment committed-replay closure):** During the maintenance lane preceding CB-113, independent review showed that callback-key/event-only pre-reading could classify a damaged or concurrently committed callback through the later sandbox-liveness fence. The owner defined this pure evaluation-only surface's mechanically enumerable committed-result closure as exactly five durable faces: callback, payment attempt, order, payment ledger, and evaluation audit. The callback correlation is the primary stable anchor; signed request order/event/context locators retain a face when another face is missing. Within the same transaction, the implementation must lock and enumerate these faces before consulting mutable sandbox liveness. A later independent review found that the callback face could contain two rows for the same non-unique correlation and escape as an unclassified 500, proving that face membership alone omitted the bounded cardinality axis. The owner therefore refined the closure to the Cartesian product of the five faces, cardinality partition `0`/`1`/`>=2`, and every persisted content column when a row exists. Zero is missing, exactly one row must match the complete committed truth, and two or more rows are inconsistent even when one row is valid. Sandbox ownership derives the stable-key set under review but is not an outer row filter: every row bearing one of those stable keys is enumerated before sandbox, type, terminal-state, or other content assertions, so a cross-sandbox or cross-type damaged sibling remains inside the closure. If any committed face exists, exact canonical intent returns the fully reconciled existing result while conflicting intent, a missing face, duplicate cardinality, or any inconsistent content returns 409; the liveness fence is reachable only when the closure contains no committed result. Replacement acceptance is real cardinality and content data-consistency fault injection across all five faces, single-face and pairwise evidence, plus a deterministic concurrent window in which replay waits for a callback commit and completion before resolving committed truth without fencing. Integrity and uniqueness failures in this resolver are classified by root cause as 409 and may not escape as repository `IllegalStateException` or HTTP 500. This bounded closure explicitly governs adversarial completeness for the internal evaluation callback only; CB-070 production payment and other business-core idempotency boundaries retain their stricter existing standards. A future proposed damage axis must be stated explicitly: a mechanically bounded axis is incorporated and closed, while an unbounded value-domain partition follows the CB-112 owner scope process and is recorded as residual risk rather than driving an unbounded matrix. Residual risk remains a future durable sixth face or an owner-accepted unbounded axis being introduced without updating this decision. The decision changes no route, service/language or truth ownership, public API schema, security credential, migration, stored data, dependency, or production payment acceptance.

**2026-07-22 final structural supplement to the evaluation mock-payment closure:** Independent review then proved that the conceptual order face had drifted between paths: the signed callback resolver treated `order_id` across both `standard_order` and `seckill_order`, while evaluation state/audit reconciliation enumerated only `standard_order`, so a cross-type sibling produced `409/200/200`. The owner approved one final internal-view expansion: the five face definitions now have one executable source of physical tables, stable keys, and participating columns, and both callback and view paths derive their enumeration from it. The order face is explicitly the union of `standard_order` and `seckill_order`; each corruption cell must drive signed callback, state, and audit and require the same conflict classification. Evaluation-audit cardinality identifies a callback by entity id or by the complete exact sandbox/session/trace/operation tuple, because a support session may legitimately contain multiple operations. Once this shared definition and cross-path classification matrix pass, further adversarial-completeness proposals confined to this internal evaluation view are non-blocking residual-risk records under the CB-112 scope precedent. This is the final owner cap for that internal surface. The cap does not apply to production payment or refund behavior, transaction consistency, identity authorization, idempotent ordering, or another business-core path; a finding in those paths remains blocking under the existing strict standards. No route, public API, schema, migration, stored data, dependency, service/language or truth ownership changes.

The shared definition distinguishes exact/invariant-backed participating columns from columns whose original value has no independent durable anchor. A complete-diff review proved that merely listing `mock_payment_attempt.succeeded_at` without reading and asserting it still permitted `409/200/200`; the attempt event time, canonical attempt intent, and evaluation-only zero-refund accumulator are therefore explicit callback/view assertions and independent fault cells, and compound state/version/time mutations do not count as per-column evidence. The zero-refund rule is not part of production committed-payment replay: a legal later partial or full refund changes that accumulator without invalidating the immutable original callback result. The start-command `request_idempotency_key` is canonical business intent and must be committed, reconciled, and independently fault-injected even for an evaluation-created attempt; the owner rejected treating it as an internal residual. Two internal-only residual dispositions remain recorded in executable metadata under the final cap: `evaluation_owner_handle` is reset provenance while committed replay is anchored to effective `user_subject`; and the database-generated ledger `movement_id` has primary-key identity but no second content anchor. Valid-value substitution of those two fields is not claimed detectable by the internal evaluation view. This residual record does not weaken their production idempotency, refund, or transaction constraints, and any real business-path inconsistency remains blocking.

**Resolved Level 3 contract amendment — 2026-07-26 (CB-116 committed-content anchor graph):** The five committed-payment faces remain callback, attempt, order, ledger, and evaluation audit; immutable order provenance is a validation substructure of the order face, not a sixth face. Every payment-relevant durable column has exactly one executable disposition: authoritative root; hash commitment with the shared writer/resolver canonicalizer; existing origin commitment; derived replica with named scope-aware anchors; database-backed invariant; declared correlated group; or a separately approved residual. A metadata label is insufficient: every declared origin is enumerated with a fixed acquisition bound and consumed by the production resolver before content assertions, while executable tests fail closed for an unregistered physical column, duplicate declaration, dangling anchor, unused origin definition, or missing validator. Production standard orders are anchored by their immutable `order_idempotency` intent commitment using the unchanged CB-040 canonicalizer; production seckill orders are anchored by their immutable activity, reservation, and order-create movement relations. Evaluation standard orders retain their evaluation-fixture root and do not acquire a production-idempotency requirement. Existing roots and commitments must be consumed before introducing a residual or correlated equivalence; a production business field used by payment/refund/Action decisions that has no executable existing anchor requires a new owner decision rather than an implementation-local exception. This amendment adds no face, migration, schema/index responsibility, payment command, Outbox field, callback field, write protocol, mutable-product lookup, service/language boundary, route dependency, or public response change.

**Resolved Level 3 contract amendment — 2026-07-26 (CB-116 committed payment event time):** The payment transaction generates one `TIMESTAMP(6)` event time and copies it to `mock_payment_callback.created_at`, `mock_payment_attempt.succeeded_at`, and, for evaluation payments, `eval_commerce_audit_reference.created_at`. These participating columns form the single `PAYMENT_EVENT_TIME` correlated content group rather than three independently anchored absolute values. Every applicable member must be non-null and exactly equal; evaluation audit additionally retains its `BUSINESS_EVENT` anchor and sandbox sequence/time relative-order invariant. A strict-subset change or a whole-group change that violates relative order is durable-integrity damage, while a whole-group equal shift that preserves every other commitment and invariant is observationally equivalent because the current durable model contains no independent absolute-microsecond commitment. This amendment changes no payment status, owner/sandbox identity, request or callback intent, amount, currency, order, ledger, audit identity/content, or cardinality fail-closed rule, and does not add a migration, table/index, request field, hash semantic, payment command, Outbox, provider callback, or refund behavior. If refund eligibility windows, settlement SLA, user-visible results, legal audit, Action execution, or another business decision later consumes absolute payment event time, a separate slice must first introduce an independent durable commitment and write protocol; consumers may not infer such authority from the current equality-only group.

**Resolved Level 3 internal-attribution boundary decision — 2026-07-27 (CB-116 authoritative audit origin):** The shared `eval_commerce_audit_reference` table stores product-observation and payment-callback references, but its mutable `entity_type` discriminator is validated content rather than authority for the record family. Evaluation reconciliation must establish origin independently from the durable product-observation root, the committed callback/attempt root plus the canonical payment audit identity, or the immutable V013 legacy-set commitment. A uniquely established payment origin may produce route-specific committed-payment integrity attribution; a uniquely established product origin produces route-specific evaluation-audit integrity attribution; no root, multiple roots, cross-family collision, or orphan state fails closed as non-payment audit-integrity damage. The discriminator is checked only after origin is established and cannot move a product row into the payment producer class. State and audit remain public 409 with their existing bounded bodies; producer reasons remain server-only and request-local. This decision changes no schema, migration, write protocol, response field, payment/refund state, route, dependency, service ownership, authorization boundary, or fail-closed standard.

**Resolved Level 3 route decision — 2026-07-23 (terminal portfolio route and evidence boundary):** The repository owner set `CB-152` as the natural end of the continuous P0/P1 route. No slice or self-created work may be added after it, and the existing `CB-900`/`CB-910`/`CB-920` outlines remain deferred outside this Goal. `CB-150` must implement only the smallest optional no-op-capable trace sink already promised; it may not become evidence or business truth. `CB-152` must produce and record real load, latency, and quality measurements in the local Docker Compose environment with the environment labelled. Mock/local results cannot be described as real-provider capacity or quality, and any real-provider claim would require separately identified real-provider evidence. The impact radius is route termination and CB-150/CB-152 acceptance wording only; no service/language owner, truth boundary, dependency, schema, migration, stored data, or external provider obligation is added.

**Resolved Level 3 scope decision — 2026-07-23 (adversarial-completeness budget):** For pure internal, non-business evaluation audit/state reconciliation surfaces, closeout may use an owner-approved Cartesian closure over mechanically enumerable ground truths plus an explicit residual-risk record, following the CB-112 precedent. This prevents unbounded adversarial expansion where no constructive termination test exists. The budget does not relax business-core surfaces: transaction messages, inventory consistency, identity authorization, idempotent ordering, payment/refund truth, and comparable production invariants retain their established strict acceptance and blocking review standard. Each internal closure must name its included axes, excluded dimension, and residual risk; owner approval cannot be inferred by an implementation session.

**Resolved Level 3 scope decision — 2026-07-23 (Elasticsearch runtime least privilege):** Local and CI Elasticsearch run single-node with `xpack.security.enabled=false`; therefore runtime rejection evidence for admin, wildcard, and unrelated-index capabilities is assigned to the existing deployment/hardening residual-risk bucket for CB-113 and the same CB-114/CB-115 operations. These slices still prove exact server-owned versioned index and alias behavior and reject caller-selected targets, but do not claim runtime Elasticsearch authorization enforcement. MySQL runtime identities, cross-database denial, and Support Redis denial remain strict and unchanged. The impact radius is only Elasticsearch access-denial acceptance/evidence in CB-113 through CB-115; there is no runtime configuration, dependency, identity, schema, migration, stored-data, route, or truth-owner change.

**Resolved Level 3 route decision — 2026-07-23 (defer knowledge rollback and cleanup evolution):** After CB-113 reached `VERIFIED`, the owner approved removing CB-114 and CB-115 from the current committed execution route and retaining their existing linked specifications as `DEFERRED` vNext evolution. The current knowledge-lifecycle promise ends at CB-113's complete authoritative snapshot, concurrent catch-up, full validation, durable Broker checkpoint, and atomic verified forward alias switch. Automatic predecessor catch-up/revalidation/reverse alias rollback and destructive expired candidate/predecessor cleanup are not implemented or promised by the current route. The recorded predecessor, handoff watermark, and bounded rollback lease remain durable CB-113 evidence but do not create an automatic rollback or deletion worker. Residual operational risk is explicit: recovery is manual and repeated rebuilds may retain additional physical indexes, causing storage growth until a separately authorized vNext Goal re-evaluates CB-114/115.

A repository-wide dependency audit found no CB-130 or downstream code, schema, state transition, test evidence, or acceptance criterion that consumes CB-114 rollback or CB-115 cleanup. CB-130 consumes the verified knowledge/retrieval foundation and CB-121 receipt/turn boundary, so its dependency changes from `CB-115, CB-121` to `CB-113, CB-121`. CB-120 becomes the sole `READY` slice; CB-114 and CB-115 are `DEFERRED`; no other row is `READY` or `IN_PROGRESS`. Their detailed specifications remain the only future evolution specifications and gain no duplicate route-status field. No rollback, deletion, production code, migration, schema, stored data, service/language responsibility, truth ownership, security boundary, or transaction boundary changes in this decision. Reactivation requires a distinct owner-started vNext Goal and a fresh complexity/dependency review; it never occurs automatically after CB-152.

**Resolved Level 3 replacement decision — 2026-07-26 (CB-120 blocked-slice supersession):** PR #54 established that CB-120 could not safely close as one reviewable unit. Its semantic reviewed head `dd4dec6e1af9db1f300b72052613024cbc441aa5` and complete binary diff SHA-256 `94f8d129e484c4cdec4ad66e55cd221a89990edf6c39d9deba65479508b61606` left lifecycle recovery performing transaction-external locking validation after its bounded observation transaction. That read bypassed the refund transaction boundary and physical lock-wait policy, admitted a non-coherent snapshot, and could expose raw MySQL 1205/1213 as an unclassified 500. The blocked-state commit `3732a2697cee70333e5fcca0b1270fbae3296016` recorded the failure; PR #54 was closed without merge, its branch was deleted, and CB-120 remains permanently `BLOCKED`. PR #54 is a detailed failure and design-reference source only and grants no implementation, test, review, CI, closeout, or verification credit to a replacement.

The owner approved three previously unused P1 replacement slices with one truth or transaction boundary apiece. CB-116 closes committed-payment truth and caller-specific authorization visibility across payment start, authenticated callbacks, and evaluation state/audit. CB-117 depends on CB-116 and closes physically bounded refund contention recovery, including fresh-transaction complete lifecycle validation and the PR #54 blocker. CB-118 depends on CB-116, CB-117, and CB-082 and delivers the original commerce PendingAction/ActionReceipt product result in one atomic validation/consume/refund/receipt transaction. This partition preserves the original total outcome, `commerce_db` truth ownership, owner/session/sandbox authorization, concealment, canonical idempotency, refund/payment integrity, and the indivisible Action transaction. It does not authorize a global acyclic lock protocol, refund state-machine redesign, weakened security boundary, partial Action transaction, or inheritance of unmerged evidence.

Only unstarted dependencies move: CB-121 now depends on CB-118 and CB-082, and its specification consumes the CB-118 confirmation/result boundary. CB-130 and the later route retain their existing semantic chain through CB-121; no downstream row depends on CB-120. The CB-120 Completion record gains only the append-only supersession reference and its identifier, Goal, `BLOCKED` state, evidence, and history remain unchanged. CB-116 is the sole `READY` replacement; CB-117 and CB-118 remain `PLANNED`; no row is `IN_PROGRESS`. This route-refinement lane changes only the canonical route, current owning-slice references, linked specifications, and this decision. It changes no production code, test, migration, OpenAPI, runtime configuration, persisted data, service/language responsibility, truth owner, credential, or deployed dependency.

**Resolved Level 3 replacement decision — 2026-07-29 (CB-121 blocked-slice supersession):** PR #64 attempted the complete agent confirmation, local reference/evidence, immutable ActionReceipt projection, public receipt, and turn-commit result in one 22-file change with 8,248 additions and 210 deletions. Its semantic reviewed head `2082ae81abed3b46cdd516d16b9fcf6f09d9dfd4` and binary diff SHA-256 `e31bb52495f589bedb788479ec9dfb93ba969dc0e6a77e6d366e7cb65fdcc95b` reached the owner stop boundary with two blockers: decline/expiry replay did not bind every action event `trace_id` to its owning turn, so conversation and evaluation classified the same durable damage differently; and the evidence did not compose persisted `CONFIRMING` through a deep malformed receipt response, zero partial local success truth, and same-key convergence to the valid committed receipt. Commit `25dcf69650647d3e4903075f9934c8dd4843af96` and complete blocked diff SHA-256 `61ad5162778450e0469115ad596a214a6759a3f195203023c04acfc183da6cd0` recorded that result. PR #64 was closed without merge, its feature branch was deleted, and CB-121 remains permanently `BLOCKED`; its identifier, Goal, blockers, and failed history are not renamed, rewritten, or treated as completed work.

The owner approved two previously unused P1 replacements because the failed unit crossed two separable review and truth boundaries. CB-122 depends on CB-118 and CB-082 and closes only the pre-confirmation agent boundary: one total bounded action parser, complete validation of the CB-118 prepared result, one agent-owned PendingAction reference, `action_pending` turn plus `ACTION_PREPARED` evidence, deterministic clarification/decline/expiry, and a shared conversation/evaluation event closure whose every event is fully bound to owner, session, turn, trace, sequence, type, payload, PendingAction identity, commitment, expiry, and sandbox. Exact confirmation is a fixed bounded non-success with zero commerce confirm call in CB-122. CB-123 depends on CB-122, CB-118, and CB-082 and owns the indivisible confirmation result: exact `PENDING → CONFIRMING` arbitration, fresh exact-scope OBO, CB-118 confirm/result consumption, complete untrusted receipt validation, and one atomic local transaction for immutable projection, `ACTION_RECEIPT`, `CONFIRMED` reference, and the same durable confirmation turn's `action_completed` outcome. Its mandatory composition is persisted `CONFIRMING` followed by a deep malformed receipt response and zero partial local success, then same-key recovery of the valid committed receipt into that same turn with exactly one local closure and no second commerce mutation.

This replacement preserves the full original CB-121 product result, `commerce_db` ActionReceipt authority, `cs_db` projection/evidence ownership, direct-user and OBO identity, owner/session/turn/trace/sandbox security boundaries, non-authoritative prose rule, receipt-first replay, and the atomic receipt/event/reference/turn success boundary. It does not split the CB-118 commerce transaction or the CB-123 local success transaction, weaken an authorization or integrity rejection, add a recovery scanner, or inherit any implementation, test, review, CI, closeout, or verification credit from PR #64. That failed PR remains only a counterexample, design, and test-reference source.

Only unstarted route dependencies move. CB-130 now depends on CB-113 and CB-123; CB-140 consumes CB-123 instead of CB-121; no downstream row depends on CB-121. CB-121 receives only the append-only supersession reference and remains `BLOCKED`. CB-122 is the sole `READY` row, CB-123 and all later committed work remain `PLANNED`, and no row is `IN_PROGRESS`. This decision changes only route/specification ownership and current contract references; it adds no production code, test, migration, grant, OpenAPI, runtime configuration, stored data, service/language responsibility, truth owner, credential, or deployed dependency. The route still ends at CB-152, and CB-152 retains the mandatory real environment-labelled load, latency, and quality evidence.

**Resolved Level 3 internal-interface amendment — 2026-07-30 (CB-118 prepare projection for CB-122):**
Fresh CB-122 review proved that the verified CB-118 prepare response did not project the already
committed PendingAction owner, support-session, turn, trace, required-scope, sandbox, and target
version bindings, so the Agent could not independently validate the complete durable `PREPARED`
result. The owner approved a narrow internal response amendment: `PendingActionView` now projects
those existing values from the post-prepare `PendingActionRecord`; initial create and same-intent
replay return identical bindings, with only the existing status/replayed distinction. No
`pending_action` schema, transaction, idempotency commitment, confirmation, refund, receipt, or
truth owner changes. CB-118 remains the authoritative PendingAction source.

CB-122 must treat every projected value as untrusted input and compare it with the validated direct
principal, owned session, server turn and trace, fixed `refund:create` scope, null-safe evaluation
sandbox, canonical request arguments, and a positive target version. The Agent may persist
`target_version` only as an immutable local evidence copy in its unmerged V007 reference and
`ACTION_PREPARED` event; it cannot use that copy to override Commerce truth or avoid CB-118's later
confirmation revalidation. The additional response bindings are restricted to the internal
ToolAdapter. Explicit model input retains only pending-action id, action type, order id, amount,
currency, state, and expiry; no new binding or server-only producer may enter model, chat, or SSE
output. The impact radius is the CB-118 internal view/OpenAPI and tests plus CB-122 validation,
evidence, producer attribution, and V007. Operational and migration cost is limited to the
unreleased Agent evidence column; no deployed Commerce data migration or new endpoint is required.

**Resolved Level 3 portfolio-route decision — 2026-08-02 (stop Agent success mainline and deliver
verified portfolio evidence):** The repository owner accepted CB-123's final blocker and stop
boundary. PR #68's final reviewed implementation allowed evaluation evidence to accept a valid
`BUDGET_CHARGED` event inserted after `ACTION_RECEIPT` with the terminal suffix shifted, while the
conversation closure rejected the same durable damage. That violated the required shared complete
completed-action event lifecycle closure. CB-123 remains permanently `BLOCKED`; PR #68 stays closed,
is not reopened, and grants no implementation, test, review, CI, closeout, resume-ready, or
verification credit. It is retained only as failed design and counterexample evidence. This decision
is not a CB-123 replacement and creates no replacement slice. CB-121 likewise remains permanently
`BLOCKED` with its existing history unchanged.

The current portfolio Goal no longer implements Agent successful confirmation, Agent ActionReceipt
projection, `ACTION_RECEIPT`, `action_completed` Turn commit, receipt cards, or post-commerce Agent
receipt recovery. Verified Commerce CB-118 authority is unchanged: Commerce may prepare/confirm one
sensitive refund and atomically persist the immutable ActionReceipt under its existing transaction,
authorization, idempotency, and truth boundaries. Verified Agent CB-122 authority is also unchanged:
Agent may validate and project `PREPARED`, clarify, decline, expire, and close that local evidence,
while exact confirmation remains a fixed side-effect-free non-success. Residual risk is explicit: a
sensitive action can be prepared, clarified, declined, or expired through Agent, but successful
Agent confirmation and local ActionReceipt/Turn closure are not part of the current portfolio route.
No UI, README, metric, demo, or measurement may present those absent capabilities as success.

CB-130 memory/PII, CB-131 handoff, and CB-132 candidate export are removed from the current route and
retained only as vNext evolution. Their retained design creates no current dependency or activation;
a future Goal requires separate owner approval and fresh dependency/contract review. The current
route turns to portfolio delivery: CB-140 builds the minimal web and factual README only from
verified CB-020 login, CB-030 products, CB-060 public seckill reservation/status, CB-082 chat/SSE,
CB-091 RAG, CB-118 Commerce PendingAction authority, and CB-122 Agent-local PendingAction outcomes.
CB-150 depends only on CB-140, CB-151 only on CB-150, and CB-152 only on CB-151. No current downstream
slice depends on CB-123, CB-130, CB-131, or CB-132.

CB-150 is limited to minimal metrics for implemented paths and an optional no-op trace mirror.
CB-151 resets and demonstrates only verified identity, catalog, ordering/seckill, payment/refund,
RAG, chat, and PendingAction prepare/decline/expiry behavior. CB-152 measures only
JMeter seckill QPS/concurrency correctness; Locust/Mock-LLM ordinary-chat, RAG, and PendingAction
prepare P99; RAG HitRate@5/MRR; FAQ mapping/answer cache hit rates; Elasticsearch knowledge-search
avoidance; and the environment, dataset, duration, and first evidenced bottleneck. It must not
measure or claim Agent successful confirmation, Agent receipt Turn commit, MemoryPacker summary,
handoff, failure-candidate export, deployment, or real-provider capability. This route decision
changes no production code, tests, migration, grant, OpenAPI, README, schema, persisted data,
service/language owner, truth owner, security boundary, or transaction boundary; README changes
belong to the later CB-140 implementation lane.

**Resolved scope refinement — 2026-08-03 (CB-150 Agent-only observability):** The current portfolio
scope of CB-150 contains only `agent-service` operational metrics and an optional Agent trace sink.
The current route adds no metrics dependency, registry, scrape endpoint, operation instrumentation,
or trace implementation to `auth-service` or `commerce-service`. CB-152 obtains transaction QPS and
client latency from JMeter and Locust, reconciles transaction correctness against authoritative
Commerce durable truth, and calculates RAG quality offline. Of its five portfolio measurement
outputs, only FAQ cache hit rates and Elasticsearch knowledge-search avoidance require in-service
counters, and those counters belong to Agent. Provider-attempt counters may remain Agent runtime
diagnostics but do not produce a cache-saving portfolio result. Java instrumentation would not add
a portfolio measurement output, but would materially enlarge the implementation and regression
surface.

Agent metrics remain a purely observational side channel. They are neither business truth nor
evaluation truth, and loss, duplication, or outage must not change business responses, durable
state, or evidence. Metric names and labels are bounded and must not contain high-cardinality
identity or business values. The Agent trace sink remains optional, default `Noop`, bounded in queue
and timeout, zero-retry, and neither evidence nor business truth.

CB-152 must not reintroduce Auth or Commerce metrics or Java instrumentation. Its first-bottleneck
analysis may use JMeter/Locust client latency, errors and throughput; authoritative durable state;
Docker/container CPU, memory and I/O observations; and Agent metrics. If that evidence cannot
uniquely identify the first bottleneck, the result is `indeterminate`; missing Java server metrics
does not invalidate a run.

This refinement changes no service/language ownership, business transaction, API, schema, grant,
route order, portfolio measurement output, or CB-151 demo scope. It changes no production code,
test, dependency, lockfile, README, CI, private repository, or service evaluation artifact.

**Resolved metric correction — 2026-08-03 (FAQ cache saves Elasticsearch lookup, not model
calls):** The current Agent execution order is model decision → `knowledge.search` tool request →
FAQ cache or Elasticsearch choice → reranker → final model response. A valid FAQ cache hit avoids
only the Elasticsearch knowledge search. It does not avoid the initial model decision, the reranker
request, or the final model response request. The former model-call-saving formula
`avoided / (avoided + issued)` has no current producer and is removed from the portfolio measurement
route.

The replacement portfolio result is Elasticsearch knowledge-search avoidance:
`cache_served / (cache_served + elasticsearch_issued)`. Each eligible non-replayed
`knowledge.search` execution that reaches and completes the backend choice contributes exactly one
bounded Agent decision: `cache_served` for a valid cache hit with zero Elasticsearch search, or
`elasticsearch_issued` when miss, bypass, unavailable, or invalid cache handling actually invokes
the logical Elasticsearch knowledge search. Replay, rejection or malformed tool input
before the choice, budget/circuit denial before the choice, and a path with neither usable cache nor
an issued Elasticsearch search are excluded from this denominator and remain visible through
operation outcomes.

FAQ mapping and answer hit rates remain separate and use `hit / (hit + miss)`; bypass,
unavailable, and invalid remain separately reported. Actual primary, fallback, and reranker attempt
counters may remain bounded Agent diagnostics, but do not enter the Elasticsearch-avoidance
denominator, do not support a model-saving claim, and do not become the fifth portfolio number.
CB-150 must not change the CB-112 execution chain, add a pre-model shortcut, bypass the existing
reranker/sufficiency or evidence contracts, or use cached answers to skip the Agent loop merely to
manufacture a saving.

CB-152 must not reintroduce model-call saving, count replay/denial as a cache saving, estimate
provider saving from cold/warm traffic, or report counterfactual model savings without a real
producer. Its five outputs remain JMeter seckill QPS/concurrency correctness; Locust Agent-path P99;
RAG HitRate@5/MRR; FAQ mapping/answer hit rates; and Elasticsearch knowledge-search avoidance. This
correction changes no ownership, API, transaction, retrieval policy, cache behavior, route order,
Agent-only metrics scope, trace scope, or measurement count.

<a id="contract-measurement-route-disposition"></a>

**Resolved Level 3 portfolio-route disposition — 2026-08-06 (CB-151 blocked measurement-route
split):** PR #75 closed without merge after CB-151 exhausted its review recovery budget. CB-151's
Goal, blocker evidence, Completion record, and `BLOCKED` history remain immutable; the current route
creates no CB-151 replacement and inherits no code, test, review, CI, verification, or Resume-ready
credit from that pull request. The generalized persistent demo/reset/fault harness is dropped from
the current portfolio route. No PR #75 code or state/artifact schema may be copied, cherry-picked,
or treated as an implemented prerequisite.

The original CB-152 had not started when the owner applied the complexity gate. It combined three
independently deliverable boundaries with separable acceptance, rejection, recovery, truth, and
tool evidence: (1) JMeter against Commerce public seckill APIs and authoritative
reservation/order/inventory-ledger truth, (2) Locust with a deterministic Mock LLM against Agent
public paths and minimum durable path-validity truth, and (3) offline labelled RAG evaluation plus
Agent FAQ/cache counters, real Elasticsearch-call reconciliation, and final portfolio aggregation.
JMeter and Locust are distinct tools; Commerce and Agent are distinct truth owners; concurrency
correctness and latency have different rejection evidence; offline retrieval/cache quality does not
depend on a load runner; no atomic transaction or security boundary spans the three outcomes; and
the original combined unit could not reasonably be reviewed, tested, recovered, and merged as one
coherent pull request. The split occurs before any CB-152 implementation and neither adds nor
removes a portfolio output or changes business behavior.

The current technical dependencies are `CB-152 ← CB-150`, `CB-153 ← CB-150`, and `CB-154 ←
CB-152, CB-153`. CB-152 and CB-153 are technically independent sibling measurements; canonical
route order executes CB-152 first and does not invent a dependency solely to encode that order.
CB-154 depends on both because it consumes their committed bundles. This disposition supersedes
only the current dependency and terminus mappings in the 2026-08-02 portfolio-route decision and
the historical CB-152 terminus supplements; their original facts remain historical record. The
route now terminates at CB-154, no CB-155 is authorized, and completion leaves no `READY` or
`IN_PROGRESS` row. CB-151 remains `BLOCKED`; CB-152 is the sole `READY` row; CB-153 and CB-154 are
`PLANNED`. Blocked and deferred Agent confirmation/receipt, memory, handoff, export, rollback,
cleanup, and other vNext work does not reactivate automatically.

The five portfolio outputs are unchanged: seckill QPS/concurrency correctness; path-separated Agent
P99; RAG HitRate@5/MRR; FAQ mapping/answer hit rates; and Elasticsearch knowledge-search avoidance.
First bottleneck is explanatory metadata, not a sixth output. Each slice owns a persistent,
sanitized, checker-reconstructable bundle at `evidence/measurements/<slice-id>/` containing at least
`manifest.json`, `result.json`, `checksums.sha256`, and lossless bounded `raw/` artifacts including
failures. Each specification freezes its artifact budget; exceeding it stops and adjusts the
predeclared run instead of discarding samples. CB-152 and CB-153 results become independently valid
on their own verification, raw reconstruction, independent `NO BLOCKER`, and exact-main checks.
CB-154 may validate and reference their committed schema and digest but may not recalculate,
overwrite, or gate their validity.

All three slices are restricted to single-command ephemeral thin orchestration over the existing
runtime chain: `make init-local`, `make up` with a unique `ENV_FILE` and
`COMPOSE_PROJECT_NAME`, required application child processes, readiness, measurement,
authoritative reconciliation, artifact write, owned-child shutdown, explicit `make reset-local
CONFIRM_RESET_LOCAL=1`, and exact residue checks. `make up` retains its existing RocketMQ store and
runtime initialization, health, grant, and three-migration responsibilities. `make down` preserves
volumes and the env file and cannot close cleanup. Runners keep no cross-command active state; use
no active-run/phase/PID/container registry, generalized reset/demo/fault or resume semantics,
host-wide prune, default project, user `.env`, wildcard cleanup, or `make demo-*`; and prove exact
project containers, networks, volumes, owned processes, generated env, and temporary secret residue
are zero/absent without allowing cleanup failure to hide the first failure.

After the first complete evidence execution, each measurement slice has at most two total
scope-owned semantic recovery cycles, each requiring unique evidence, the minimum correction,
targeted verification, and the required full evidence/review. Read-only preflight, source
attribution without correction, superseded CI, platform-confirmed external-infrastructure rerun,
and commands denied before repository execution do not count. Exhaustion cannot open a third
framework lane, rename the same root cause, weaken raw/artifact truth, or rebuild a general runner;
the slice stops under its predeclared blocked or claim-reduction rule and waits for the owner.

CB-152 Q01-Q09, unexplained-error zero, duplicate/over-allocation zero, and residue zero are never
reducible. CB-153 per-path raw samples and error accounting, prohibition on mixed aggregation,
path-validity controls, and residue zero are never reducible. CB-154 freezes a blocking core before
execution: a versioned labelled RAG set and raw ranking reconstruction; cold miss and warm hit;
bypass/unavailable/invalid exclusion from mapping/answer hit denominators; formulas `hit / (hit +
miss)` and `cache_served / (cache_served + elasticsearch_issued)`; and request-level proof that every
cache-served choice issues zero logical Elasticsearch searches while every issued choice invokes
exactly one, including invoked-then-failed. Provider attempts remain diagnostic. Core failure blocks
CB-154. Expiry and replay are a predeclared extended layer: only after two exhausted semantic cycles
may they be marked residual with workload scope limited to cold/warm eligible synthetic requests,
without changing a core formula or claiming all-scenario coverage.

This disposition changes the public route, linked specifications, route outcomes, frozen decision
record, and directly conflicting README facts only. It implements no measurement, runner, artifact,
test, business behavior, production code, workflow, dependency, migration, schema, grant, API,
stored data, service/language responsibility, truth owner, security boundary, or transaction
boundary. It makes no private-core or ServiceEval change.

**Resolved factual execution disposition — 2026-08-10 (CB-152 final evidence blocker):** This is
the current factual status that supersedes only the status snapshot in the 2026-08-06 disposition;
the earlier decision text remains its unchanged historical record. CB-152 legally started, but
implementation PR #80 closed without merge and formed no acceptable result. Fresh final review of
evidence head `19a878e31d85e35d327ec6b1f86f0a2132fd0214` proved executable Q08 false-greens: malformed
unknown and other-owner 404 JSON bodies with the wrong bounded key set could collapse to equal null
category/message tuples, and a 64-character non-hex reservation-locator hash could pass without
validation. The invalid bundle was deleted at final implementation head
`3932a0f00be43620b3e5ddf158ac2de305192d1b`. No result bundle, QPS, percentile, Resume-ready,
implementation, `VERIFIED`, or replacement credit is retained. PR #80 remains the detailed test,
failure, and reviewer-evidence record; none of its unmerged code is a reusable implementation asset.

CB-152 is now `BLOCKED`; CB-153 and CB-154 remain `PLANNED`; the route has no `READY` and no
`IN_PROGRESS` row. The technical dependencies remain exactly `CB-152 ← CB-150`, `CB-153 ← CB-150`,
and `CB-154 ← CB-152, CB-153`. The same five portfolio outputs remain unchanged, CB-154 still
depends on both CB-152 and CB-153, the route still terminates at CB-154, and the decision that no
CB-155 is authorized remains in force. No replacement map exists, and neither CB-153 nor any
blocked or deferred work is activated. Any supersession requires a separate owner-authorized Level
3 replacement map satisfying the conditions in `AGENTS.md`; blocked history cannot be deleted or
rewritten. This addendum changes no API, schema, formula, Q01-Q09 requirement, business contract,
truth owner, security boundary, transaction boundary, dependency, output, or route terminus.

**Resolved Level 3 replacement disposition — 2026-08-10 (CB-152 superseded by CB-155):**

**Trigger and evidence.** PR #80 is closed without merge, CB-152's factual `BLOCKED` status and
Completion record are on `main`, and fresh final review proved executable Q08 false-greens. No valid
bundle, metric, implementation, review, CI, verification, Resume-ready, or performance credit
survived. The implementation branch is deleted, no unresolved branch or open pull request remains,
the latest `main` checks succeeded, and the owner supplied this explicit Level 3 replacement map;
all supersession preconditions are satisfied.

**Exact decision.** CB-152 remains permanently `BLOCKED`. CB-155 becomes the sole `READY` row and
depends on CB-150. CB-153 remains `PLANNED` and depends on CB-150. CB-154 remains `PLANNED` and now
depends on CB-155 plus CB-153. Canonical execution is `CB-155 → CB-153 → CB-154`, and the route
terminates at CB-154. CB-152 and CB-155 do not represent two Portfolio numbers: both map to the
same first output, seckill QPS / concurrency correctness. CB-152 is immutable blocked history and
CB-155 is the sole current result owner. This paragraph supersedes the current effect of the
2026-08-06 decision that no CB-155 was authorized and the 2026-08-10 factual addendum that no
replacement map existed and no CB-155 remained in force; those dated historical texts remain
unchanged. No CB-156 or further seckill-measurement replacement is authorized.

**Impact radius.** The change is limited to the public route, route outcome catalog, CB-152
append-only supersession reference, new CB-155 specification, CB-154 input dependency, and directly
conflicting README route text. It changes no production behavior, API, schema, grant, migration,
truth owner, security boundary, transaction boundary, Q01-Q09 requirement, five-output set,
first-bottleneck explanatory status, ServiceEval work, or private-core state.

**Operational cost.** CB-155 requires a fresh implementation from current `main`; no PR #80 commit,
runner, checker, JMeter plan, test, bundle, or other code is reusable. There is no stored-data or
runtime migration. This decision adds one documentation/specification unit; later implementation
remains one ephemeral measurement runner within the linked slice boundary.

**Replacement acceptance and terminal stop.** CB-155 must satisfy the unchanged CB-152 measurement
outcome and Q01-Q09, publish `evidence/measurements/CB-155/` independently, close its pre-formal
adversarial requirements, and obtain a fresh independent `NO BLOCKER` without reusing PR #80
implementation. The Portfolio still has exactly five outputs—seckill QPS / concurrency correctness,
Agent-path P99, RAG HitRate@5 / MRR, FAQ mapping / answer hit rates, and Elasticsearch
knowledge-search avoidance—and first bottleneck remains explanatory metadata, not a sixth output.
If CB-155 becomes `BLOCKED`, no CB-156 or second replacement is authorized and the route stops for
owner disposition.

**Resolved Level 3 implementation-surface clarification — 2026-08-11 (CB-155 frozen-SQL LOC
accounting):**

**Trigger.** The bounded CB-155 preflight correctly identified the five-file functional gate, but
allocated only 75–105 runner lines to the ten literal SQL blocks together with prepared execution and
rowset serialization. Mechanical review of the still-unstarted `READY` specification proves that it
already contains ten SQL blocks and 174 nonblank SQL lines. Continuing with a total-only projection
would defer the surface stop until substantial implementation had been completed. This is an
implementation-before projection correction, not evidence of a CB-155 implementation or formal-run
failure.

**Exact decision.** `total_nonblank` is every nonblank source line in the CB-155 runner and checker.
`frozen_sql_nonblank` is exactly 174 lines from the ten ordered specification SQL blocks, and only
when each implemented block is mechanically equal line by line and in order.
`authored_nonblank = total_nonblank - frozen_sql_nonblank` only after that equality passes. The hard
limits are `authored_nonblank <= 1,800`, `total_nonblank <= 1,974`, and at most five functional
implementation/test files. CB-155 has no separate pre-formal smoke profile or alternate workload
branch. Its first live lifecycle invocation is its first complete formal evidence execution, and the
existing one post-formal scope-owned semantic recovery cycle remains unchanged.

**Rationale.** The 174 literal SQL lines were already accepted as normative Q01-Q09 content through
route/specification review, while implementation must still prove exact block count, order, character
content, and single occurrence. Prepared binding, execution, fetch, serialization, error handling,
cleanup, and all other implementation logic remain authored surface. Total LOC must still be
disclosed and is independently capped at 1,974. This is neither an open-ended exclusion nor a way to
hide review surface: only the 174 mechanically equal SQL payload lines receive the frozen exclusion.

**Impact radius.** This clarification affects only CB-155 implementation-surface accounting, the
number of CB-155 pre-formal execution paths, and future private factual synchronization. It does not
change Q01-Q09 or their SQL content, workload, artifact contract, business truth, production API,
schema, grant, migration, dependency, output ownership, route terminus, recovery-cycle count, or the
no-CB-156 boundary.

**Stop rule.** Frozen SQL equality failure, `authored_nonblank > 1,800`,
`total_nonblank > 1,974`, or more than five functional files stops CB-155 before formal execution.
These gates must not be relaxed again, and no CB-156 may be created.

**Resolved factual execution disposition — 2026-08-12 (CB-155 final pre-formal evidence
blocker):** This addendum supersedes only the current CB-155 route-status effect of the earlier
measurement-route decisions; every dated decision above remains unchanged historical record.

**Trigger and evidence.** CB-155 legally began on the now-deleted
`codex/cb155-seckill-measurement` branch. At final/reviewed head
`471b622f6771783a3a3a19b6091ddb48702b1135`, the five-file functional surface passed with
`total_nonblank = 1971`, `frozen_sql_nonblank = 174`, `authored_nonblank = 1797`, exact ten-block
SQL equality, 68 focused tests, pre-formal `make ci`, and 19 successful PR checks. Fresh independent
pre-formal review nevertheless proved two executable false-green classes. First, Python bool/int
equality and incomplete required-fixture primitive closure allowed values including
`activityProjectionVersion = true`, workload `quantity = true`, cleanup integer count `false`,
`pathsAbsent = 1`, `warmup.productId = null`, `warmup.activityId = true`, and
`fixtureOrDatasetVersion = true`. Second, the checker did not require the Q07 replay/Q07a terminal
body to equal the Q04 terminal public/durable body for the same `reservationLocatorHash`; replacing
both Q04 order hashes with another valid hash while retaining Q07's original order binding could
still pass. Two pre-formal recovery cycles for those review-finding classes were consumed, and no
independent `NO BLOCKER` was issued. The formal lifecycle never started. PR #84 closed without
merge.

**Current factual state.** CB-155 is `BLOCKED`; CB-153 and CB-154 remain `PLANNED`. The route has
zero `READY` and zero `IN_PROGRESS` rows. No valid bundle, Q01-Q09 result, measured number,
implementation credit, `VERIFIED` credit, or Resume-ready credit exists. The runner, checker, JMX
plan, tests, and candidate implementation from PR #84 did not enter `main`.

**Preserved route facts.** Dependencies remain exactly `CB-155 ← CB-150`, `CB-153 ← CB-150`, and
`CB-154 ← CB-155 + CB-153`. The five outputs remain seckill QPS/concurrency correctness, Agent-path
P99, RAG HitRate@5/MRR, FAQ mapping/answer hit rates, and Elasticsearch knowledge-search avoidance;
first bottleneck remains explanatory metadata rather than a sixth output. Truth ownership, security
boundaries, transaction boundaries, result ownership, and the architectural terminus at CB-154 are
unchanged.

**Terminal stop.** CB-155 was the sole and final replacement. No CB-156, second seckill replacement,
claim reduction, or automatic CB-153 activation is authorized. The current measurement route has
no eligible implementation lane. Further work requires a new owner terminal disposition, not
another implementation recovery.

**Impact radius.** This factual addendum synchronizes only route status, the CB-155 Completion
record, the README current limitation, and the terminal owner-decision requirement. It changes no
production behavior, API, schema, grant, migration, Q01-Q09, literal SQL, workload, artifact
contract, output ownership, dependency, ServiceEval work, or private-core state. Closed PR #84
continues to carry the detailed implementation, test, and review evidence.

**Resolved contract correction — 2026-08-06 (opaque support-session consumer closure):** The owner
explicitly authorized this correction in a defect-correction non-slice lane; it is not a silent
contract change or an AGENTS.md Rule 2 maintenance lane. A support session is an opaque identifier.
The unchanged Agent producer `secrets.token_urlsafe(32)` generates exactly 43 unpadded Base64URL
characters in `[A-Za-z0-9_-]`, with every alphabet character permitted in the first position. The
consumer contract is the union of that canonical language and the existing bounded human-readable
language `[A-Za-z0-9][A-Za-z0-9._:-]{0,63}`. Consumers preserve the value byte-for-byte; they do not
trim, case-fold, encode, decode, replace, prefix, retry, or otherwise normalize it. Existing stored
sessions remain valid, and generic human-readable identifier grammars for sandbox, product, trace,
operation, reset, case correlation, and test-user labels are unchanged.

The first documented consumer failure was the shell argv transport fixed by `c723f398`, where a
leading-dash session was parsed as an option. The second was the Commerce evaluation session parser,
whose generic leading-alphanumeric grammar rejected the same valid producer partition. The closure
also applies the dedicated union validator at real Action and evaluation-payment support-session
boundaries; Auth remains an exact pass-through from token-exchange request to OBO `session` claim.
Invalid support-session grammar on the evaluation tool boundary remains public HTTP 400 with the
fixed `Bad request` body and no field or value disclosure, while the server records only the precise
request-local reason `TOOL_SUPPORT_SESSION_INVALID`. The Agent response, OBO claim, headers,
authorization context, durable filters, observations, audit references, Action records, and payment
records retain exact byte equality. Producer entropy, alphabet, persistence, response shape, schema,
stored data, grants, and public API shape do not change.

The parked documentation PR #76 did not introduce this defect and remains read-only evidence. A
mechanical repository-wide audit of opaque generators and their positional languages found no
additional producer/consumer mismatch: `opaque_identifier_audit = no additional mismatch found`.
Any later mismatch in a different identifier domain requires a separate owner decision and is not
authorized by this correction.

Current Level 3 conflicts: **none unresolved as of 2026-08-06**.
