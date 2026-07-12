# CityBuddy Implementation Plan

**Document version:** v0.1  
**Verification date:** 2026-07-12  
**Repository phase:** Repository and toolchain baseline implementation  
**Current executable slice:** `CB-000 — Repository and toolchain baseline` (`IN_PROGRESS`)

## 1. Document status and maintenance rules

This document is the public engineering contract for implementing CityBuddy. It is intentionally complete enough for a coding agent to work from the repository alone, while leaving field-level schemas, generated API models, and exact dependency patches to the slice that owns them.

The repository currently contains documentation only. No business code, runtime topology, build configuration, migration, test, CI result, or measured performance claim exists yet.

Maintenance rules:

1. At most one active slice may exist. Before implementation begins, the active slice is `READY`. Creating its real feature branch and beginning the first implementation change moves that same slice to `IN_PROGRESS`.
2. Once implementation has begun, work continues only on the single `IN_PROGRESS` slice until it becomes `VERIFIED`, `BLOCKED`, or `DEFERRED`. A different `READY` slice must not coexist with an `IN_PROGRESS` slice. Only after the active slice ends may the next slice become `READY`.
3. P0 and P1 slices that are not active remain `PLANNED` until their numbered dependencies are `VERIFIED`. P2 slices remain `DEFERRED` unless explicitly promoted. When more than one non-`DEFERRED` slice has all dependencies `VERIFIED`, the slice appearing earliest in the Complete route becomes the next `READY`; only an explicit Level 3 route change may alter this order.
4. The active slice and the next two slices in the Complete route carry full specifications. When the active slice ends, its real completion record is filled when applicable, the deterministically selected next slice becomes `READY`, and one later specification is expanded. The phrase “active slice” continues to identify the same work after `READY → IN_PROGRESS`.
5. Exact dependency versions, image digests, generated schemas, lockfiles, and executable tool configuration become the runtime version truth when their owning slice creates them. This document records compatibility boundaries and choices, not a parallel lockfile.
6. Database migrations, OpenAPI documents, ToolSpec schemas, and contract tests become the executable truth for field names and payload details. This v0.1 does not predesign a complete DDL or every future DTO.
7. A hard conflict with a frozen contract must not be hidden by an implementation workaround. The affected slice is marked `BLOCKED`, the evidence and impact are recorded here, and the contract changes only through the change rules in Section 10.
8. The long-lived Markdown set remains `README.md`, `AGENTS.md`, `CLAUDE.md`, and `IMPLEMENTATION.md`. Slice evidence belongs in pull requests and the completion records below, not in additional status documents.
9. Public model configuration uses role aliases only. Concrete provider model identifiers belong in runtime configuration and, where needed, recorded run metadata.

## 2. Project context and current truth

CityBuddy is intended to combine local-commerce transactions with a text-only AI customer-support path. Its defining boundary is not the number of services; it is that identity, transactional side effects, retrieval evidence, and evaluation-only access remain independently enforceable.

The target service set is:

| Service | Language/runtime boundary | Target responsibility |
|---|---|---|
| `auth-service` | Java 21 / Spring Boot 3.5 | Login, RS256 user tokens, service-authenticated token exchange, OBO tokens, JWKS publication and key rotation, and evaluation-only test identities. |
| `commerce-service` | Java 21 / Spring Boot 3.5 | Products, inventory, orders, seckill admission and ordering, mock payment, refund, support tickets, CRM and FAQ truth, internal tool APIs, and evaluation-only state APIs. |
| `agent-service` | Python 3.11 / FastAPI / Pydantic | Customer-support APIs, a single ReAct agent, deterministic control signals, model policy, tool mediation, action confirmation, retrieval, safety, SSE egress, and authoritative support evidence. |
| `knowledge-indexer` | Python 3.11 | Asynchronous FAQ and product indexing, source-version ordering, tombstones, rebuilds, and versioned Elasticsearch alias changes. RocketMQ consumption remains gated by the Python client spike. |
| `web` | React / TypeScript / Vite | A later minimal demonstration surface for login, products, seckill status, support chat, and action receipts. |
| `litellm-proxy` | LiteLLM Proxy | OpenAI-compatible provider access, key isolation, rate limiting, same-tier provider failover, one bounded network retry, and usage/cost records. It never makes business-tier routing decisions. |

At v0.1 none of these services or components exists in the repository. The tables and diagrams below describe implementation contracts, not deployed facts.

## 3. Preflight verification

Only decisions that affect repository initialization or the first implementation slices are recorded here. “Verified” means current official or first-party material supports the compatibility or mechanism. It does not mean CityBuddy has implemented or tested it. “Spike required” means the external material is insufficient to treat the exact project path as ready.

| Item | Conclusion | Status | Adopted implementation boundary | Official or first-party sources |
|---|---|---:|---|---|
| Java 21 with Spring Boot 3.5 and Spring Security/Nimbus | Spring Boot 3.5 supports Java 21, and Spring Security's resource-server JWT path uses Nimbus-based JWT processing and supports JWKS, issuer/audience validation, and custom validators. | Verified | Use Java 21 and Spring Boot `3.5.x`. Manage Spring Security and Nimbus through the Boot dependency graph unless a documented security fix requires an explicit override. Pin exact patches in Maven configuration. | [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html); [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) |
| MyBatis-Plus on the Java transaction service | The maintained Boot 3 starter is the supported integration path, and its documentation warns against adding the raw MyBatis starter alongside it. | Verified | Use only `mybatis-plus-spring-boot3-starter` for MyBatis-Plus integration. Do not add a competing MyBatis starter. Pin the exact compatible patch in the Maven build. | [MyBatis-Plus installation](https://baomidou.com/en/getting-started/install/) |
| Java multi-module build | Maven's reactor natively aggregates and orders multi-module builds; Maven Wrapper pins the build entry point. | Verified | Choose Maven only. Use one root reactor for `auth-service` and `commerce-service`, dependency/plugin management at the root, and Maven Wrapper. Do not add Gradle. | [Maven multi-module reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html); [Maven Wrapper](https://maven.apache.org/tools/wrapper/) |
| RocketMQ 5 local runtime and Java client | RocketMQ 5 documents a Broker plus Proxy path and a gRPC/protobuf client family. Transaction messages expose half-message, commit/rollback, and transaction-check semantics; delay messages use a delivery timestamp. | Verified | The local runtime must expose a RocketMQ 5 Proxy endpoint and the Java code must use the 5.x client path. Topic message types are explicit. Consumer idempotency remains an application obligation. | [Local RocketMQ 5 quick start](https://rocketmq.apache.org/docs/quickStart/01quickstart/); [transaction messages](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/); [delay messages](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/); [official clients repository](https://github.com/apache/rocketmq-clients) |
| RocketMQ transaction failure behavior in the intended seckill chain | The platform mechanisms are documented, but the exact half-message/Lua/checkback timing, duplicate delivery, and failure recovery must be proven against the selected Broker, Proxy, and Java client patches. | Spike required | Keep the frozen transaction-message design. Before declaring the ordering slice verified, exercise Lua rejection, duplicate delivery after commit, and missing second-phase acknowledgement/checkback. The checker reads only the durable decision marker. It may return `UNKNOWN` only while the marker is missing or temporarily indeterminate; broker transaction timeout, check interval, and maximum check count define the terminal boundary. | [RocketMQ transaction-message lifecycle and limits](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/) |
| Python RocketMQ consumption for `knowledge-indexer` | The official client repository advertises Python support and includes examples, but those examples do not establish the exact acknowledgement, invisible-duration, retry, and long-processing behavior required here. An issue filed in the official Apache RocketMQ clients repository reports a PushConsumer invisible-duration gap for a Python client/Proxy combination. | Spike required | Keep `knowledge-indexer` behind a small messaging adapter. Test both the viable manual-ack/simple-consumer and push-consumer paths where available. Do not claim production-ready consumption until the eight-item spike in Section 10 passes. | [official client feature matrix](https://github.com/apache/rocketmq-clients); [Python examples](https://github.com/apache/rocketmq-clients/tree/master/python/example); [issue #1198 in the official Apache RocketMQ clients repository](https://github.com/apache/rocketmq-clients/issues/1198) |
| MySQL 8 transactional truth and Redis 7 dual-instance semantics | InnoDB provides the required transaction model. Redis documents `noeviction`, LFU policies, and AOF persistence; separate instances are appropriate when workloads require incompatible eviction and durability behavior. | Verified | Use one MySQL 8 instance with `commerce_db` and `cs_db`; use two Redis 7 instances. Commerce Redis uses `noeviction` plus AOF. Support Redis uses TTL-based data with an LFU eviction policy. Redis never becomes the transaction, inventory, quota, or action truth. | [MySQL 8 InnoDB transaction model](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-model.html); [Redis eviction](https://redis.io/docs/latest/develop/reference/eviction/); [Redis persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) |
| Elasticsearch 8 dense vectors, kNN, and versioned aliases | Elasticsearch 8 documents `dense_vector`, approximate/exact kNN search, and atomic alias actions. | Verified | Pin one Elasticsearch 8 patch in runtime configuration. Build `knowledge_docs_vN`, validate it, then switch a stable read alias atomically. Keep private order, refund, coupon, and personal data outside the index. | [dense vector](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/dense-vector.html); [kNN search](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/knn-search.html); [aliases](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/aliases.html) |
| Reciprocal rank fusion | Elasticsearch documents RRF, but server-side feature availability must not become an undeclared deployment assumption. | Verified | Preserve RRF as the fusion algorithm. The default project implementation merges separately retrieved BM25 and kNN result lists in `agent-service`; a server-side RRF path may be enabled later only after the selected distribution is verified. | [Elasticsearch RRF](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/rrf.html) |
| IK analyzer compatibility | The maintained IK plugin requires a plugin artifact that matches the Elasticsearch version. The exact future Elasticsearch patch is not yet pinned. | Spike required | In `CB-010`, pin Elasticsearch and IK together, install the plugin in the image, and run an analyzer smoke test. Do not silently fall back to a different analyzer. Mark the slice `BLOCKED` if no reproducible compatible artifact or build path exists. | [IK analyzer maintainer repository](https://github.com/infinilabs/analysis-ik) |
| Python 3.11, FastAPI, Pydantic, and `pyproject.toml` | Current FastAPI supports the Pydantic v2 path; current Pydantic supports Python 3.11. `uv` supports `pyproject.toml`, workspaces, and a shared lockfile. | Verified | Use Python 3.11, Pydantic v2 APIs, a `uv` workspace for the two Python packages, per-package `pyproject.toml` metadata, and a committed `uv.lock`. Exact patches are locked by `uv.lock`. | [FastAPI Pydantic migration](https://fastapi.tiangolo.com/how-to/migrate-from-pydantic-v1-to-pydantic-v2/); [Pydantic installation](https://pydantic.dev/docs/validation/latest/get-started/install/); [uv project layout](https://docs.astral.sh/uv/concepts/projects/layout/); [uv workspaces](https://docs.astral.sh/uv/concepts/projects/workspaces/) |
| LiteLLM Proxy compatibility and retry boundary | LiteLLM Proxy exposes an OpenAI-compatible interface and supports retry plus model-group fallback. | Verified | Application code calls role aliases through the proxy. `ModelRouter` alone selects the business tier. LiteLLM may retry a transient/network failure once and fail over only within the same tier. The application and proxy share a bounded attempt budget; stacked unbounded retries are forbidden. | [LiteLLM Proxy](https://docs.litellm.ai/docs/simple_proxy); [fallback and retry](https://docs.litellm.ai/docs/proxy/reliability) |
| Docker Compose readiness and migration jobs | Compose distinguishes container start from readiness, supports health-gated dependencies and successful one-shot dependencies, and can run one-off jobs. | Verified | Every stateful dependency gets a meaningful health check. Services use long-form dependency conditions where required. Database migration is a separate one-shot service/job and never an implicit side effect of API startup. | [Compose startup order](https://docs.docker.com/compose/how-tos/startup-order/); [docker compose run](https://docs.docker.com/reference/cli/docker/compose/run/) |
| Initialization checks and build tools | Maintained tools exist for each active language and for secret scanning. | Verified | Java: Maven Wrapper, Spotless, Checkstyle, Java compiler release 21, JUnit/Surefire. Python: Ruff, mypy, pytest under `uv`. Web: npm lockfile, Prettier, ESLint, `tsc --noEmit`, Vitest, Vite build. Repository: Gitleaks. `make ci` invokes only checks backed by real files and tests. | [Spotless Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven); [Maven Checkstyle](https://maven.apache.org/plugins/maven-checkstyle-plugin/); [Maven Compiler](https://maven.apache.org/plugins/maven-compiler-plugin/); [Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/); [Ruff](https://docs.astral.sh/ruff/); [mypy](https://mypy.readthedocs.io/en/stable/); [pytest](https://docs.pytest.org/en/stable/); [ESLint](https://eslint.org/docs/latest/use/getting-started); [Prettier](https://prettier.io/docs/); [TypeScript compiler](https://www.typescriptlang.org/docs/handbook/compiler-options.html); [Vitest](https://vitest.dev/guide/); [npm ci](https://docs.npmjs.com/cli/v11/commands/npm-ci/); [Gitleaks](https://github.com/gitleaks/gitleaks) |

**Preflight outcome:** no Level 3 conflict has been found. The frozen mainline remains implementable. Python RocketMQ consumption, the Elasticsearch/IK patch pair, and the project-specific RocketMQ failure behavior remain explicit spikes; none is silently treated as proven.

## 4. Frozen contracts

### 4.1 Service and language boundaries

- `auth-service` owns token issuance, token exchange, service authentication at the exchange endpoint, JWKS, signing-key lifecycle, and evaluation-only test token issuance. No other service owns a token-signing key.
- `commerce-service` owns transactional business state and all business-side authorization decisions, including audience, scope, sandbox, and resource ownership.
- `agent-service` owns support orchestration and support evidence. It can request a delegated token but cannot issue identity, choose arbitrary scopes, or substitute a user identifier from a request body.
- `knowledge-indexer` is an asynchronous projection worker. It does not become a source of product or FAQ truth.
- `web` is a later demonstration client, not an authority for confirmation, identity, price, stock, action status, or sandbox state.
- `litellm-proxy` is provider infrastructure, not a business router. Business-tier selection stays in `agent-service`.
- Java owns authentication and commerce transactions. Python owns the agent path and indexing worker. Cross-language synchronous calls use internal HTTP/REST JSON; RocketMQ is used only for asynchronous messaging.

### 4.2 Identity, delegation, and authorization

Token classes are distinguished explicitly by a token-purpose/type claim or an equivalent independent authentication chain. The absence of an actor claim is never treated as a permissive direct-user downgrade.

**Direct user JWT**

1. A user logs in through `auth-service` and receives an RS256-signed direct user JWT.
2. User-facing routes in `agent-service` and `commerce-service` validate signature, fixed issuer, configured user-facing audience, expiry, not-before, accepted clock skew, user principal, route-required role or user permission, and resource ownership.
3. An unknown `kid` triggers one JWKS refresh and one validation retry; continued failure is rejected.
4. A direct user JWT does not require `act.azp`, does not require an OBO scope, and does not carry the support-session identifier. Production direct-user tokens do not carry an evaluation sandbox claim.

**Agent OBO**

5. Conversation and public FAQ paths do not acquire commerce authority. On the first internal commerce tool call, `agent-service` requests a short-lived OBO just in time.
6. `POST /api/sessions` is the only support-session bootstrap. It requires a direct user JWT; `agent-service` generates an opaque session id, binds it to the validated token subject, and in evaluation also binds the sandbox context. The client cannot choose the owner. Wrong token type, cross-user substitution, or sandbox mismatch rejects. `X-Session-Id` identifies this support session, not a login-token session, and every use is rechecked against the validated user and sandbox context in `cs_db`.
7. On first tool use, `agent-service` submits the validated user JWT, its independently authenticated service credential, the verified support-session binding, and the exact server-side ToolSpec scope to token exchange. `auth-service` trusts the authenticated service's session-binding assertion and writes that support session into the OBO.
8. The OBO contains at least an explicit OBO purpose/type, `sub`, `user_id`, support `session`, `aud=commerce-service`, exact `scope`, `act.azp=agent-service`, `jti`, `exp`, and the applicable not-before/issued-at metadata. Scope is fixed by ToolSpec; neither model nor request payload can widen it. Cache keys are limited to `user + support session + exact scope` and never outlive the token.
9. `commerce-service` accepts internal tool identity only from the validated OBO. It validates signature, fixed issuer, OBO purpose/type, audience, exact required scope, actor, user subject, support session, expiry/not-before/skew, and resource ownership. It never trusts identity fields in the request body.
10. Evaluation test JWTs and their derived OBO tokens carry the same sandbox claim. Internal tool requests also require sandbox header/claim equality and an ACTIVE sandbox. Production tokens carry no sandbox claim, and production rejects the evaluation header.
11. Signing private keys stay in `auth-service` secret material. Public keys overlap for at least the maximum token lifetime plus accepted clock skew during rotation.

### 4.3 Storage topology and truth hierarchy

- MySQL 8 is one physical instance with two logical databases: `commerce_db` and `cs_db`. Cross-database joins are forbidden; data crosses service boundaries through APIs or events.
- Runtime identities are distinct: `auth_app` accesses only auth-owned principals, credential verifiers, service identities, signing-key metadata, and evaluation test-principal records; `commerce_app` accesses only commerce-owned transaction and business tables; `agent_app` accesses only agent-owned tables in `cs_db`.
- A bootstrap/admin identity exists only to create databases, accounts, and grants. Separate migration identities execute only their owning migration streams. Runtime identities do not execute DDL, have no global/admin grants, and never use bootstrap/admin credentials.
- Auth-owned persistence remains an auth-owned table family in `commerce_db`; this does not give `commerce_app` access to credential or private identity metadata and does not add a third database.
- `commerce_db` is the truth for products, orders, inventory, seckill allocation, reservations, payments, refunds, authoritative support tickets, CRM, published FAQ state, PendingAction, ActionReceipt, sandbox registration, and transaction Outbox records.
- `cs_db` is the truth for support sessions, event/evidence records, retrieval evidence, summaries, feedback, failure candidates, and handoff/receipt projections. A projection never overrides commerce action or ticket truth.
- Commerce Redis is a separate Redis 7 instance using `noeviction` and AOF. Support Redis is a separate Redis 7 instance using TTL-oriented data and LFU eviction.
- MySQL remains the truth for transactions, inventory, quotas, action state, tickets, and idempotency. Redis is only admission control, a projection, a lock, or a cache. A Redis success alone never proves an order, refund, payment, or confirmed action.
- Elasticsearch is a derived public-knowledge index and never contains private orders, refunds, personal coupons, or other user-private transactional data.

### 4.4 Messaging and consistency responsibilities

- RocketMQ 5 runs with Broker and Proxy available to the 5.x clients. The Proxy endpoint remains explicit.
- Seckill ordering uses a transaction message: send the half message, run Redis Lua admission, then commit, roll back, or temporarily return `UNKNOWN`. A deterministic result writes a durable transaction decision marker; admission also writes the reservation projection required by the order path.
- `UNKNOWN` is an intermediate checker result only when the durable decision marker is missing or temporarily indeterminate. It is not a permanent application terminal state. Broker transaction timeout, check interval, and maximum check count define the terminal boundary; the application must not depend on unlimited `UNKNOWN` responses.
- The checker reads only the durable marker. Marker and reservation TTL cover the complete configured timeout/check interval/maximum-check window. The implementation proves the final broker outcome when that boundary is exhausted.
- Downstream order creation is idempotent. Database unique constraints, an inventory-ledger movement keyed by the business event, and conditional state transitions handle repeated delivery; a duplicate returns or projects the existing result.
- `CB-060` establishes the minimum `inventory_ledger` capability for seckill order creation, unpaid cancellation, inventory/activity-quota restoration, and replay idempotency. `CB-070` extends it for payment, refund, and full reconciliation.
- Delay messages trigger unpaid-order cancellation and later ticket SLA checks. Delivery is a trigger, not authority: consumers re-read MySQL state and use conditional status/version updates.
- MySQL-derived product, FAQ, order, refund, complaint, and ticket events use an Outbox row written in the same local transaction as the business change. Authoritative ticket creation, state changes, SLA delay events, and their Outbox records are produced by `commerce-service`; `agent-service` only requests handoff and stores a projection.
- Transaction messages, delay messages, domain events, and knowledge-sync events are distinct responsibilities. Request threads do not dual-write MySQL, Redis caches, and Elasticsearch as independent truths.

### 4.5 Agent, tool, action, and evidence boundaries

- Production support uses one ReAct agent. There is no multi-agent or decomposer mainline.
- `RuleRouter` emits deterministic signals only, such as high risk, private action, cacheable public FAQ, obvious chitchat, and complexity. It never chooses the final handling strategy.
- `ModelRouter` is the only business policy decision-maker for model tier, escalation, and budget. LiteLLM may only retry/fail over inside the selected tier.
- One shared `attempt_budget` spans model, LiteLLM, HTTP, and tool attempts. Circuit breakers are provider-scoped, do not open before a minimum request count, and use bounded half-open probes. Provider fallback stays within the tier selected by `ModelRouter`.
- `ToolAdapter` returns structured `deny_with_feedback` results. The single main Agent, constrained by ToolSpec and deterministic signals, handles missing slots, RAG/tool choice, clarification, and refusal. CityBuddy does not train or introduce a separate intent classifier.
- `MemoryPacker` may combine a commerce-owned read-only CRM view, recent turns, and a summary protected by a monotonic `summary_until_turn` watermark. Model input is separated into `SYSTEM`, `TOOLS`, `USER`, `UNTRUSTED RETRIEVED`, and `UNTRUSTED TOOL DATA`; citations may point only to allowlisted evidence sources.
- ToolSpec defines each tool's schema, risk tier, fixed scope, timeout, idempotency behavior, and model-visible output. The model cannot expand a scope or bypass confirmation.
- Read and ordinary write tools are checked at `commerce-service`. Sensitive actions first create a PendingAction in `commerce_db`; `agent-service` stores only the identifier and presents a text confirmation request.
- Confirmation is not a front-end security primitive. In one commerce transaction, the service validates the PendingAction's argument hash, resource version, expiry, ownership, and unconsumed state; consumes it once; executes the business mutation; and persists an ActionReceipt.
- A successful ActionReceipt is the action truth and the turn-level commit point. `agent-service` persists its evidence projection and emits `action_receipt`. Any later model or network retry may regenerate explanation text only and must not execute the tool again.
- Output is risk-tiered. Action claims such as “refunded” or “ordered” are held until a matching successful receipt exists; ordinary knowledge/chitchat may stream through a small buffer; a secondary text/tool consistency guard may block contradictions but never replaces receipt truth. Asynchronous grounding can create evidence or a follow-up candidate, but there is no mainline SSE retraction.
- The server derives the write idempotency key from `turn_id`, tool identity, and argument hash. A repeated key returns the existing action result or receipt.
- Internal agent events may include text deltas, tool lifecycle, retrieval and guard details, receipts, errors, and completion. The public SSE egress filter exposes only `token`, `action_receipt`, `done`, and `error`.
- Human handoff is a bounded ticket flow, not a full agent workstation. Explicit user request, repeated unresolved intent, sustained negative sentiment, or high risk may trigger it; low retrieval confidence alone leads to clarification or denial. While the support session is in `HUMAN_PENDING` mode and its authoritative ticket remains open, sensitive writes are prohibited; any SLA delay message rechecks the durable ticket state.
- `cs_db` plus the evaluation-only evidence API is the authoritative support-evidence channel. Langfuse may be enabled only as an optional observability profile with a no-op fallback; it can mirror traces but never becomes an assertion source or prompt authority. Prompt definitions remain versioned with the code.
- CI and tests never receive a real model-provider key. Model calls must be replaceable by deterministic fakes or mocks.

### 4.6 Retrieval and knowledge boundaries

- RAG is invoked through the `knowledge.search` tool; it is not unconditionally prepended to every turn.
- Retrieval keeps the original query and an optional rewrite as separate recall inputs. Both can contribute BM25 and dense-vector candidates.
- The default fusion is deterministic application-side RRF, followed by a reranker role alias and a sufficiency gate whose score threshold and top-result margin are calibrated on a development set. Insufficient evidence produces a structured denial or clarification path instead of an unsupported answer.
- Knowledge is stored in `knowledge_docs_vN` and read through a stable alias. FAQ and product chunks share the logical index and are separated by `doc_type` and metadata.
- FAQ is one question-and-answer document per published item. Product documents use structure-aware chunks with a bounded length and modest overlap; each chunk is one Elasticsearch document.
- Every indexed record carries source identity, source version, document type, publication state, and deletion/tombstone information. Older out-of-order events cannot overwrite a newer source version.
- Rebuilds create a new physical version, load and validate it, run the required retrieval checks, then atomically switch the alias. The old version remains available for controlled rollback until cleanup is authorized.
- Real-time price, stock, and availability are read from commerce tools. An Elasticsearch record is never the live transaction truth.

### 4.7 Evaluation-only CityBuddy boundary

- Evaluation routes are loaded only by the evaluation profile. Production returns not found for `/api/eval/*`, rejects `X-Eval-Sandbox-Id`, and cannot issue evaluation test tokens.
- The evaluator first calls `commerce-service POST /api/eval/reset`. Commerce creates the one-time sandbox and business fixtures, then calls an internal service-authenticated `auth-service` provisioning endpoint with sandbox id, case correlation, TTL, and minimum test-principal attributes.
- `auth-service` persists its own TTL-bound provisioning record and returns an opaque test-user handle. It never reads the commerce sandbox registry. The reset response returns only the sandbox/test-user handle needed by the evaluator and never returns credentials.
- The evaluator calls `POST /auth/eval/test-token` with an independent evaluation API credential, sandbox header, and opaque test-user handle. `auth-service` validates only its provisioning record and issues a test JWT with the sandbox claim. Derived OBO tokens preserve the same claim.
- Runtime sandbox liveness remains a `commerce-service` decision. Reset/provision failure must not leave a usable ACTIVE sandbox: the reset transaction is failed before activation or commerce performs an explicit compensating transition to DEAD and calls the eval-only auth revocation endpoint for the handle.
- Provisioning and revocation are service-authenticated, idempotent by sandbox/case correlation or handle, TTL-bound, and have explicit duplicate/reset-retry semantics. A handle cannot mint a token for another sandbox or after expiry/revocation. `auth-service` never reads the commerce sandbox registry.
- Normal case completion calls the commerce completion endpoint, which idempotently transitions `ACTIVE → DEAD` and revokes or invalidates the test-principal handle. TTL/janitor is a backstop for abandoned cases, not the normal completion path. Reset/provision compensation performs the same fail-closed invalidation.
- Each sandbox identifier is one-time, has `ACTIVE/DEAD` plus TTL, and is never reused. Asynchronous consumers check liveness before applying effects; inactive work is dropped or archived.
- Business tables participating in evaluation carry explicit sandbox context. Repository/SQL enforcement fails closed when required context is absent.
- The evaluation profile stubs irreversible external effects such as SMS. Mock payment remains part of the business flow but uses sandbox-bound data and idempotent callbacks; it is not replaced by an untracked shortcut.
- `commerce-service` owns reset, state, audit, version, sandbox truth, and test-identity provisioning orchestration. `agent-service` owns the evaluation-only evidence API backed by `cs_db`. ServiceEval implementation is outside this repository; only these cross-system contracts are defined here.

### 4.8 Explicit non-goals for the current mainline

The current route does not include multimodal input, image/audio/video storage, a full shopping site or cart, a multi-page commerce product, a full human-agent workstation, multi-agent orchestration, a decomposer model, long-term vector memory, a second vector database, a service gateway or registry, Kubernetes, production return of evaluation evidence, automatic code changes by an evaluator, or a recovery scanner that can repeat committed actions.

## 5. Service and data ownership boundaries

### 5.1 Runtime access boundaries

| Identity/component | May write | May read | Forbidden direct access |
|---|---|---|---|
| Bootstrap/admin identity | Database creation, account creation, and grants only | Server metadata required for bootstrap | Application runtime use, ordinary migrations, business data access |
| Auth migration identity | Auth-owned migration stream in `commerce_db` | Auth migration history and auth-owned schema metadata | Commerce business tables, `cs_db`, application runtime |
| Commerce migration identity | Commerce-owned migration stream in `commerce_db` | Commerce migration history and commerce-owned schema metadata | Auth credential/private metadata, `cs_db`, application runtime |
| Agent migration identity | Agent-owned migration stream in `cs_db` | Agent migration history and agent-owned schema metadata | `commerce_db`, application runtime |
| `auth_app` / `auth-service` | Auth-owned principal, credential-verifier, service-identity, signing-key metadata, and eval test-principal records | The same auth-owned family | Commerce business table families, `cs_db`, Elasticsearch; DDL/global/admin grants |
| `commerce_app` / `commerce-service` | Commerce-owned business tables, ticket truth, transaction Outbox, sandbox registry, Commerce Redis | Commerce-owned business tables; JWKS over HTTP | Auth credential/service-identity/private metadata, `cs_db`, Support Redis, direct model providers; DDL/global/admin grants |
| `agent_app` / `agent-service` | Agent-owned `cs_db` tables and Support Redis | Agent-owned `cs_db`; Elasticsearch; commerce data only through scoped tool APIs; JWKS over HTTP; model proxy | All `commerce_db` tables, signing keys, direct provider credentials; DDL/global/admin grants |
| `knowledge-indexer` | Versioned Elasticsearch indexes; allowed FAQ-version cache entries in Support Redis | Knowledge events and published source payloads | Runtime writes to `commerce_db` or `cs_db`; private order/refund data |
| `web` | No authoritative data store | Public/user-scoped HTTP APIs | Databases, Redis, Elasticsearch, RocketMQ, signing material |
| `litellm-proxy` | Provider-routing, usage, and cost records configured for the proxy | Runtime alias mapping and provider credentials | Business databases, ToolSpec policy, user/resource authorization decisions |

### 5.2 Data ownership and invariant map

This table fixes ownership and invariants without fixing complete columns, index names, or object models. The owning migration, OpenAPI definition, ToolSpec, and contract tests provide those details in the listed slice.

| Entity or table family | Owner and database/store | Business key or unique invariant | Key state machine or transaction boundary | Migration/index slice |
|---|---|---|---|---|
| User principals, login credentials, service identities, signing-key metadata | `auth-service`; auth-owned family in `commerce_db`; private keys/credentials supplied as secrets; runtime identity `auth_app` | Stable user subject and unique login identifier; unique service client identity; unique `kid` per active public key set | Principal `ACTIVE/DISABLED`; service credential independently revocable; signing keys overlap during rotation; private keys are never returned | `CB-020` |
| User profile/CRM | `commerce-service`; `commerce_db`; runtime identity `commerce_app` | One profile per immutable user subject | Commerce rules write; `agent-service` reads only through a scoped commerce tool | `CB-030` |
| Product and published product content | `commerce-service`; `commerce_db` | Stable product identifier; publication/version value increases monotonically | Product update and its Outbox event are one transaction; price/stock remain live commerce fields | `CB-030` |
| Standard order and stock item | `commerce-service`; `commerce_db` | Stable order identifier; request idempotency key unique in its user/action scope | MySQL conditional stock decrement or optimistic version check and order creation are one business transaction; finite retry only | `CB-040` |
| `seckill_activity` | `commerce-service`; `commerce_db` | Stable activity identifier; activity allocation cannot exceed the inventory allocated to it | Quota allocation is a MySQL transaction. Redis receives only the admission projection | `CB-050` |
| Reservation | `commerce-service`; truth in `commerce_db`, hot projection in Commerce Redis | Unique `reservation_id`; projection is not authoritative | Admission records `PENDING/ADMITTED/REJECTED`; order consumer transitions the durable reservation conditionally | `CB-050` for admission model; durable order linkage in `CB-060` |
| One-user-one-order seckill invariant | `commerce-service`; `commerce_db` plus Lua admission marker in Commerce Redis | Database uniqueness on `(activity_id, user_id)` and uniqueness on `reservation_id` | Lua blocks obvious duplicates; database uniqueness is final and repeated messages resolve to the existing result | `CB-060` |
| `inventory_ledger` | `commerce-service`; `commerce_db` | Unique business event/idempotency key per inventory movement | `CB-060` creates the minimum append-only movements for seckill order, unpaid cancellation, inventory/activity-quota restoration, and replay idempotency; `CB-070` extends payment/refund movements and reconciliation | `CB-060` foundation; `CB-070` extension |
| Transaction Outbox | `commerce-service`; `commerce_db` | Unique event identifier and aggregate version/idempotency key | Business mutation and Outbox insert commit together; publisher marks delivery progress without changing the business result | First introduced in `CB-030`, extended by owning slices |
| Mock payment | `commerce-service`; `commerce_db` | Unique payment attempt/callback idempotency key per order | `UNPAID → PAID`; duplicate callbacks return the existing state; illegal transitions reject | `CB-070` |
| Refund | `commerce-service`; `commerce_db` | Unique refund identifier and request idempotency key; refundable amount cannot exceed the eligible amount | Requested/processing/succeeded/failed states are guarded by order/payment state; refund and relevant ledger entries share the required transaction boundary | `CB-070`; sensitive confirmation integration in `CB-120` |
| PendingAction | `commerce-service`; `commerce_db` | Unique `pending_action_id`; one server-derived idempotency key per turn/tool/argument hash | Prepared with argument hash, resource version, owner, expiry, and unconsumed state; confirmation validation, one-time consume, and business execution occur in one transaction | `CB-120` |
| ActionReceipt | `commerce-service`; `commerce_db` | Unique receipt identifier and unique action idempotency key | Persisted in the same successful transaction as the action. It is immutable action truth; a repeated key returns the existing receipt | `CB-120` |
| Sandbox registry | `commerce-service`; `commerce_db` | One-time unique sandbox identifier, never reused | `PROVISIONING → ACTIVE → DEAD` or equivalent fail-closed transition; normal completion uses the idempotent completion endpoint, reset/provision failure compensates to DEAD, and TTL/janitor cleans abandoned cases; asynchronous consumers check liveness | `CB-100` |
| Eval test-principal provisioning record | `auth-service`; auth-owned family in `commerce_db`; runtime identity `auth_app` | Unique opaque test-user handle bound to sandbox and case correlation; idempotent provisioning and revoke keys | TTL-bound provisioned/revoked lifecycle; duplicate reset returns the same valid binding or a deterministic conflict; completion/compensation revocation is idempotent; token issuance validates this record only and never reads commerce tables | `CB-100` |
| Authoritative support ticket/handoff | `commerce-service`; `commerce_db` | Unique ticket identifier and idempotent handoff request key; one applicable open ticket per configured session/action boundary | `REQUESTED → QUEUED → ASSIGNED → ACCEPTED → CLOSED / EXPIRED`; creation, state change, SLA delay event, and Outbox are commerce transactions. `HUMAN_PENDING` is an agent session mode, not a ticket state; the support session in `HUMAN_PENDING` mode plus an open authoritative ticket blocks sensitive writes | `CB-130` |
| Support session identity and ownership | `agent-service`; `cs_db`; runtime identity `agent_app` | Server-generated opaque session id bound to immutable user subject and, in evaluation, sandbox context | Direct-user-authenticated creation; client cannot choose owner; cross-user, wrong token type, and sandbox mismatch reject; identity/ownership foundation only | `CB-020` |
| Support conversation, event, and evidence lifecycle | `agent-service`; `cs_db`; runtime identity `agent_app` | Ordered records scoped to the server-created support session and owner | Conversation lifecycle and append-only evidence are bound to the established session; no cross-user reuse | `CB-080` |
| Handoff projection | `agent-service`; `cs_db`; runtime identity `agent_app` | Projection keyed to authoritative commerce ticket id and support session | Agent requests handoff, enters/leaves `HUMAN_PENDING` mode, stores controlled handoff/evidence projection, and never becomes ticket truth | `CB-130` |
| Agent event and evidence records | `agent-service`; `cs_db` | Unique `(trace_id, sequence)` or equivalent ordered event key | Append-only evidence for accepted internal events; public SSE is a filtered projection | `CB-080` |
| Retrieval evidence | `agent-service`; `cs_db` | Trace/turn association plus index version and source references | Stores the evidence actually used by the turn; never re-queries Elasticsearch to rewrite history | `CB-090` |
| Support summary | `agent-service`; `cs_db`, with hot copy in Support Redis | One current summary watermark per session; monotonically increasing `summary_until_turn` | An older asynchronous summary cannot overwrite a newer watermark; cold summary is recoverable from MySQL | `CB-130` |
| Feedback | `agent-service`; `cs_db` | Unique feedback record associated with trace/session/user or sandbox | Append-only user signal; authorization and ownership checked at write | `CB-080` |
| Failure candidate | `agent-service`; `cs_db` | Unique candidate identifier and source trace; export status is idempotent | Raw support evidence stays in CityBuddy. Only a reviewed, masked, synthetic bundle may cross the evaluation boundary | `CB-130` for capture policy, review/masking, and authenticated bundle export |
| FAQ source and publication version | `commerce-service`; `commerce_db` | Stable FAQ identifier and monotonically increasing published version | Draft/publish transition and Outbox event commit together; only published versions can be indexed or cached as authoritative answers | `CB-110` |
| `knowledge_docs_vN` and read alias | `knowledge-indexer`; Elasticsearch | One document/chunk identity per source version; alias points to one approved physical version | Source-version compare, idempotent upsert, tombstone delete, validate new version, atomic alias switch | `CB-090` for initial searchable index; automated publication/rebuild in `CB-110` |
| FAQ authoritative cache entries | `agent-service` and `knowledge-indexer`; Support Redis | Query hash maps to `{faq_id, version}`; answer key is `{faq_id, version}` | Only a high-confidence single match that passes guards may populate the first level; published version changes naturally invalidate old answer keys | `CB-110` |

### 5.3 Truth hierarchy

When two stores disagree, resolve the conflict in this order:

1. `commerce_db` for transaction, inventory, quota, resource ownership, PendingAction, ActionReceipt, sandbox, payment, and refund truth;
2. `cs_db` for the support evidence that was observed and persisted by `agent-service`;
3. Elasticsearch for a versioned public-knowledge projection;
4. Redis for admission state, projections, locks, or caches;
5. optional observability data as a non-authoritative mirror.

## 6. Interface and security boundaries

### 6.1 API contract map

Paths below are stable contract families. Full request/response fields are deferred to the owning OpenAPI or ToolSpec and its contract tests.

| Caller → owner | Method and path | Authentication | Required claims or headers | Success semantics | Principal rejection semantics | Owning slice |
|---|---|---|---|---|---|---|
| `web` → `auth-service` | `POST /auth/login` | User credential exchange | No bearer token; request fields defined by OpenAPI | Returns an explicitly typed direct user JWT with fixed issuer, configured user-facing audience, principal, time bounds, and route-relevant user authority | Invalid/disabled principal rejects without credential disclosure | `CB-020` |
| `agent-service` or `commerce-service` → `auth-service` | `GET /auth/jwks` | Public-key distribution endpoint | Stable `kid`; cache validators allowed | Returns current and overlapping public keys only | Unavailable/malformed key set causes fail-closed validation after one bounded refresh | `CB-020` |
| `web` or authorized evaluator → `agent-service` | `POST /api/sessions` | Direct user JWT | Fixed issuer, configured user-facing audience, explicit direct-user type, user principal/permission; evaluator also supplies matching sandbox context | Generates an opaque support-session id server-side and binds it to the token subject; evaluation sessions also bind the sandbox | Client-supplied owner, wrong token type, cross-user substitution, invalid audience/issuer, sandbox mismatch, or production eval header rejects | `CB-020` |
| `agent-service` → `auth-service` | `POST /auth/token/exchange` | Independent `agent-service` service credential plus validated direct user JWT | Explicit direct-user token type; fixed issuer/user audience; verified user subject; verified support-session binding; exact ToolSpec scope | Returns an explicitly typed short OBO with `aud=commerce-service`, exact scope, `act.azp=agent-service`, user subject, support session, time bounds, and unchanged eval sandbox claim when applicable | Wrong issuer/audience/type, invalid service credential, forged session binding, disallowed scope, or claim-mode mismatch rejects | `CB-020`; eval claim extension in `CB-100` |
| `commerce-service` → `auth-service` | `POST /internal/eval/test-principals/provision` | Dedicated service authentication for `commerce-service`; evaluation profile only | Sandbox id, case correlation, TTL, minimum test-subject attributes, idempotency key | Creates or returns the same TTL-bound provisioning record and opaque test-user handle; returns no credential | Invalid service identity, conflicting duplicate, invalid TTL/subject, dead/revoked correlation, or production profile rejects | `CB-100` |
| `commerce-service` → `auth-service` | `POST /internal/eval/test-principals/{handle}/revoke` | Dedicated service authentication for `commerce-service`; evaluation profile only | Opaque handle, sandbox/case correlation where required, idempotency key | Idempotently revokes or confirms invalidation of the auth-owned provisioning record | Any other service identity, mismatched handle/correlation, invalid credential, or production profile rejects | `CB-100` |
| Authorized evaluator → `auth-service` | `POST /auth/eval/test-token` | Independent evaluation API credential; evaluation profile only | `X-Eval-Sandbox-Id`, opaque test-user handle; handle must match an unexpired auth-owned provisioning record | Returns an explicitly typed test direct-user JWT carrying the bound sandbox claim | Arbitrary sandbox id, wrong handle, expired/revoked record, mismatch, invalid credential, or production profile rejects | `CB-100` |
| `web` or evaluator → `agent-service` | `POST /api/chat` | Direct user JWT | Fixed issuer/user-facing audience/type, principal/permission; `X-Session-Id` support session owned by user; trace headers; eval also supplies matching sandbox header | Returns one complete response plus any user-visible receipt | Wrong issuer/audience/type, forged/cross-user support session, sandbox mismatch, policy block, or exhausted attempts rejects | `CB-080`; eval checks in `CB-100`; receipts in `CB-120` |
| `web` or evaluator → `agent-service` | `POST /api/chat/stream` | Direct user JWT | Same direct-user and support-session rules as `/api/chat` | SSE emits only `token`, `action_receipt`, `done`, and `error` | Same identity/session/sandbox failures; no raw tool/retrieval output | `CB-080`; receipts in `CB-120` |
| `web` → `agent-service` | `POST /api/feedback` | Direct user JWT | User principal, support session, trace; ownership must match persisted support evidence | Persists authorized feedback in `cs_db` | Wrong issuer/audience/type, unknown trace, forged/cross-user session, or ownership failure rejects | `CB-080` |
| Authorized evaluator → `agent-service` | `GET /api/eval/evidence/{traceId}` | Independent evaluation API credential; evaluation profile only | Sandbox and trace must be associated | Returns authoritative allowed support evidence from `cs_db` | Production not found; cross-sandbox/unknown trace/invalid credential rejects | `CB-100` |
| `web` → `commerce-service` | `GET /api/products`, `GET /api/products/{productId}` | Direct user JWT or explicit public-read policy | For authenticated routes: fixed issuer/user audience/direct type, principal/permission; no body identity trust | Returns published product data with live commerce fields | Wrong issuer/audience/type, forbidden resource/profile, or missing product rejects | `CB-030` |
| `web` → `commerce-service` | `POST /api/orders` | Direct user JWT | Fixed issuer/user audience/direct type, user permission, ownership, `Idempotency-Key` | Atomically creates or returns the same standard order after a valid MySQL stock update | Wrong issuer/audience/type, cross-user resource, insufficient stock, conflicting idempotency, illegal quantity, or stale version rejects | `CB-040` |
| `web` → `commerce-service` | `POST /api/seckill/activities/{activityId}/reservations` | Direct user JWT | Direct-user claims, ownership, idempotency; eval header/claim equality when applicable | Starts transaction-message admission and returns reservation status, never a false completed-order claim | Identity/type/audience failure, no quota, duplicate user, inactive activity, sandbox mismatch, or indeterminate bounded transaction result rejects/returns explicit status | `CB-050`, `CB-060` |
| `web` → `commerce-service` | `GET /api/reservations/{reservationId}` | Direct user JWT | Direct-user claims and ownership; sandbox when applicable | Returns durable/projection status distinguishing admitted, ordered, rejected, expired | Cross-user/cross-sandbox access or unknown reservation rejects | `CB-060` |
| `web` → `commerce-service` | `POST /api/orders/{orderId}/mock-payment` | Direct user JWT | Direct-user claims, ownership, `Idempotency-Key` | Starts eligible mock payment | Wrong identity mode, paid/cancelled/ineligible order, cross-user access, or idempotency conflict rejects | `CB-070` |
| Mock payment component → `commerce-service` | `POST /internal/mock-payments/callback` | Separate internal callback credential/signature | Callback idempotency and payment/order correlation | Applies one legal transition; duplicate returns existing result | Invalid credential, unknown correlation, or illegal transition rejects and audits | `CB-070` |
| `agent-service` → `commerce-service` | `POST /internal/tools/{toolName}` | Agent OBO only | Fixed issuer; explicit OBO type; `aud=commerce-service`; exact ToolSpec scope; `act.azp=agent-service`; user subject; verified support session; time bounds; ownership; eval header/claim equality and liveness | Executes scoped tool and returns bounded view/evidence metadata | Direct-user token, wrong issuer/audience/type/scope/actor, missing/forged session, body identity substitution, cross-user resource, sandbox mismatch/inactive, schema error, or timeout rejects | `CB-080` plus owning business slice |
| `agent-service` → `commerce-service` | `POST /internal/tools/actions/prepare` | Agent OBO only | Same OBO rules; sensitive scope; idempotency and trace/turn correlation | Creates/returns PendingAction bound to owner, support session, argument hash, version, expiry | Token-mode, actor, session, scope, ownership, stale resource, conflict, or sandbox failure rejects | `CB-120` |
| `agent-service` → `commerce-service` | `POST /internal/tools/actions/{pendingActionId}/confirm` | Agent OBO only | Same owner/session/scope; exact sandbox equality; confirmation idempotency | One transaction validates/consumes/executes and persists ActionReceipt | Expired/consumed action, mismatch, ownership, illegal transition, or sandbox failure rolls back | `CB-120` |
| `agent-service` → `commerce-service` | `POST /internal/handoffs` | Agent OBO only | Exact handoff scope, user subject, support session, idempotency, evidence correlation | Creates/returns authoritative ticket; agent stores projection and enters `HUMAN_PENDING` as applicable | Wrong token mode/scope/session/owner, duplicate conflict, or invalid transition rejects | `CB-130` |
| `agent-service` export process → ServiceEval authenticated import contract | Candidate bundle import endpoint defined by the receiving system | Dedicated cross-system authentication; no direct database access | Reviewed export authorization, masked/synthetic payload, stable candidate id/version, audit correlation | Transfers only a controlled reviewed/masked/synthetic failure-candidate bundle; raw `cs_db` evidence remains in CityBuddy | Missing review/masking, raw production evidence, invalid authentication, replay conflict, or attempted direct `cs_db` access rejects | `CB-130` |
| Authorized evaluator → `commerce-service` | `POST /api/eval/reset` | Independent evaluation API credential; evaluation profile only | New sandbox id, case correlation, fixture specification, reset idempotency | Creates fixtures, provisions auth test principal through the internal endpoint, activates sandbox only on successful closure, and returns sandbox plus opaque test-user handle without credentials | Reused/conflicting id, invalid fixture/credential, provisioning failure, or compensation failure rejects and leaves no usable ACTIVE sandbox | `CB-100` |
| Authorized evaluator → `commerce-service` | `POST /api/eval/sandboxes/{sandboxId}/complete` | Independent evaluation API credential; evaluation profile only | Sandbox id, case correlation/idempotency; caller must be authorized for the sandbox | Idempotently transitions `ACTIVE → DEAD`, invokes or confirms test-principal handle revocation, and makes late async work fail liveness checks | Unknown/cross-sandbox id, invalid credential, conflicting correlation, revocation failure without safe invalidation, or production profile rejects | `CB-100` |
| Authorized evaluator → `commerce-service` | `GET /api/eval/state` | Independent evaluation API credential; evaluation profile only | Sandbox context | Returns sandbox-scoped business snapshot | Missing/mismatched/unscoped access rejects | `CB-100` |
| Authorized evaluator → `commerce-service` | `GET /api/eval/audit/{sessionId}` | Independent evaluation API credential; evaluation profile only | Sandbox and session association | Returns sandbox-scoped audit/receipt references | Cross-sandbox/session or invalid credential rejects | `CB-100` |
| Authorized evaluator → `commerce-service` | `GET /api/eval/version` | Independent evaluation API credential; evaluation profile only | Evaluation credential | Returns build/schema/capability identifiers | Invalid credential or production profile rejects | `CB-100` |

### 6.2 Asynchronous contract map

| Channel family | Producer → consumer | RocketMQ message type | Stable payload/invariant boundary | Failure and replay rule | Owning slice |
|---|---|---|---|---|---|
| Seckill order transaction | `commerce-service` producer → `commerce-service` order consumer | Transaction | Reservation/activity/user/sandbox identifiers, event id, version | Half message commits only after Lua admission. `UNKNOWN` is temporary; configured broker timeout/check interval/max-check count form the terminal boundary. Consumer uniqueness and minimum ledger movements make replay harmless | `CB-060` |
| Order/payment timeout | `commerce-service` → `commerce-service` timeout consumer | Delay | Order id, expected state/version, due time, event id, sandbox | Re-read MySQL; conditional cancellation and ledger restoration are idempotent; paid/final orders are not cancelled | `CB-060`; payment/refund extension `CB-070` |
| Commerce domain events | Commerce Outbox publisher → authorized consumers | Normal | Event id, aggregate/version, occurred time, sandbox where applicable | Mutation and Outbox commit together; consumers are idempotent; late events cannot reverse newer state | `CB-030` and owning slices |
| Knowledge synchronization | Commerce Outbox publisher → `knowledge-indexer` | Normal | Source id/type/version, publication state, tombstone, public content/reference | Older versions discarded; retry safe; tombstones and rebuild evidence retained | `CB-085`, `CB-110` |
| Support evidence/candidate events | `agent-service` bounded async publisher → authorized support-side consumers | Normal | Trace/session/event id, minimized PII, sandbox where applicable | Idempotent projection/candidate work; inactive sandbox work dropped/archived | `CB-130` |
| Ticket/handoff and SLA events | `commerce-service` transaction/Outbox → authorized consumers | Normal or Delay | Authoritative ticket id/state/version, support-session correlation, event id, due time, sandbox | Ticket mutation and Outbox commit together; SLA trigger re-reads ticket; duplicates/late delivery cannot regress state. `agent-service` is not the authoritative producer | `CB-130` |

### 6.3 Fail-closed security rules

- Authentication failure is never converted into an anonymous business request.
- Missing audience, scope, actor, owner, session, or required sandbox context rejects; it does not fall back to a broader query.
- Production rejects `X-Eval-Sandbox-Id` and does not load `/api/eval/*` or evidence routes.
- Evaluation requests require both management authentication and sandbox-bound user identity for black-box chat. The management credential is not a substitute for a user JWT.
- SQL repositories, batch updates, deletes, and asynchronous consumers that participate in evaluation are covered by tests proving sandbox filtering. An absent sandbox context in an evaluation path fails before SQL mutation.
- Before model calls, personal data is masked. Any reversible mapping is session-scoped, short-lived, and excluded from logs. ToolAdapter restores only fields explicitly allowed by that tool; final output does not automatically restore every masked value. Stable business identifiers needed for tool use follow their explicit ToolSpec policy.
- Tool results are stored server-side in full only where the evidence policy allows. The model receives a bounded view, and SSE receives a smaller allowlisted view.
- Secrets are injected at runtime, excluded from logs, absent from committed examples, and scanned before merge.

## 7. Contract-level sequence diagrams

### 7.1 Direct user JWT to support-session validation to JIT OBO

```mermaid
sequenceDiagram
    actor U as User or Web
    participant A as auth-service
    participant G as agent-service
    participant S as MySQL cs_db
    participant C as commerce-service

    U->>A: POST /auth/login
    A-->>U: Explicitly typed direct user JWT
    U->>G: POST /api/sessions with direct user JWT
    G->>G: Validate signature, issuer, user audience/type, time, principal and permission
    G->>S: Create opaque support session bound to token subject and optional eval sandbox
    alt Wrong token type, client-supplied owner, cross-user substitution, or sandbox mismatch
        G-->>U: Reject session creation
    else Session created
        G-->>U: Opaque X-Session-Id
    end
    U->>G: Chat request with JWT and X-Session-Id
    G->>S: Verify support session belongs to token subject and sandbox context

    alt Direct JWT invalid or support session forged/cross-user/sandbox-mismatched
        G-->>U: Reject authentication or session ownership
    else No commerce tool is needed
        G-->>U: Respond without commerce authority
    else First commerce tool call
        G->>A: Exchange validated user JWT + service credential + verified support session + exact ToolSpec scope
        alt Wrong issuer/audience/type, service identity, session binding, or scope
            A-->>G: Reject exchange
            G-->>U: Safe error without commerce action
        else Exchange accepted
            A-->>G: Explicitly typed OBO with aud=commerce-service, actor, user, support session and exact scope
            G->>C: Internal tool call with OBO
            C->>C: Validate issuer, OBO type, audience, scope, actor, user/session, time and ownership
            alt Direct-user token used on OBO route or OBO validation fails
                C-->>G: Reject authorization
            else Eval header/claim mismatch or sandbox inactive
                C-->>G: Reject sandbox context
            else Request body substitutes identity or resource belongs to another user
                C-->>G: Reject resource access
            else Authorization succeeds
                C-->>G: Scoped tool result
                G-->>U: User-safe response
            end
        end
    end
```

### 7.2 RocketMQ transaction message, Lua admission, and idempotent order creation

```mermaid
sequenceDiagram
    actor U as User or Web
    participant C as commerce-service producer
    participant M as RocketMQ Broker and Proxy
    participant R as Commerce Redis
    participant W as commerce-service consumer
    participant D as MySQL commerce_db

    U->>C: Request seckill reservation
    C->>M: Send transaction half message
    M-->>C: Half message accepted
    C->>R: Run Lua quota, one-user, and reservation admission

    alt Lua deterministically rejects and writes a rejection marker
        R-->>C: Rejected
        C->>M: Roll back half message
        Note over M,W: Rolled-back message is not delivered
        C-->>U: Rejected reservation status
    else Lua admits and writes reservation plus admission marker
        R-->>C: Admitted with reservation id
        C->>M: Commit half message
        M-->>W: Deliver committed transaction message
        W->>D: Conditional order insert and reservation transition
        alt Unique activity-user or reservation key already exists
            D-->>W: Existing order/result
            W-->>M: Acknowledge duplicate safely
        else Insert and transition succeed
            D-->>W: New durable order
            W-->>M: Acknowledge consumption
        else Database failure
            D-->>W: Failure
            Note over W,M: No acknowledgement, bounded retry or dead-letter policy applies
        end
        C-->>U: Reservation id, client polls durable status
    else Lua result has no durable decision marker
        R-->>C: Indeterminate
        C->>M: Report UNKNOWN
        C-->>U: Indeterminate reservation status
    end

    opt Second-phase acknowledgement is missing or result is UNKNOWN
        M->>C: Transaction checkback
        C->>R: Read transaction decision marker only
        alt Marker says admitted
            C-->>M: COMMIT
        else Marker says rejected
            C-->>M: ROLLBACK
        else Marker absent or temporarily indeterminate
            C-->>M: UNKNOWN
            Note over M,C: UNKNOWN is intermediate only. Broker timeout, check interval, and maximum check count define the terminal boundary; the application cannot rely on unlimited UNKNOWN.
        end
    end
```

### 7.3 PendingAction, atomic confirmation, ActionReceipt, and retry boundary

```mermaid
sequenceDiagram
    actor U as User
    participant G as agent-service
    participant C as commerce-service
    participant D as MySQL commerce_db
    participant E as MySQL cs_db

    U->>G: Request a sensitive action
    G->>C: Prepare action with OBO and server idempotency key
    C->>D: Create PendingAction bound to owner, args hash, version, and expiry
    D-->>C: PendingAction persisted
    C-->>G: pending_action_id and confirmation summary
    G-->>U: Ask for text confirmation

    alt User declines or never confirms
        Note over G,C: No business mutation is executed
    else User sends confirmation text
        U->>G: Confirm
        G->>C: Confirm pending_action_id with OBO
        C->>D: Begin one business transaction
        D->>D: Validate owner, scope, args hash, resource version, expiry, and unconsumed state
        alt Validation fails or business transition is illegal
            D-->>C: Roll back
            C-->>G: Structured rejection, no receipt
            G-->>U: Safe rejection or clarification
        else Validation succeeds
            D->>D: Consume PendingAction once
            D->>D: Execute mutation and persist ActionReceipt
            D-->>C: Commit action and receipt together
            C-->>G: Authoritative ActionReceipt
            G->>E: Persist receipt projection and turn evidence
            G-->>U: SSE action_receipt, then explanation
            opt Model or network retry after the commit point
                G->>G: Regenerate explanation from stored receipt
                Note over G,C: No second commerce execution is allowed
                G-->>U: token and done events only
            end
        end
    end
```

## 8. Development slice route

### 8.1 Status rules

| Status | Meaning |
|---|---|
| `PLANNED` | Route and outcome are known, but the slice is not active. |
| `READY` | The active slice before implementation begins. It is the only slice from which work may start. |
| `IN_PROGRESS` | The same active slice after its real feature branch and first implementation change begin. Work may continue only on this slice. |
| `VERIFIED` | Acceptance criteria, rejection paths, required evidence, and completion record are satisfied with real results. |
| `BLOCKED` | Evidence shows the active slice cannot safely meet a frozen contract without a decision or prerequisite. |
| `DEFERRED` | Deliberately outside or removed from the current route. |

At most one active slice exists. `READY` and `IN_PROGRESS` cannot coexist on different slices. The next slice becomes `READY` only after the active slice reaches `VERIFIED`, `BLOCKED`, or `DEFERRED`. When multiple non-`DEFERRED` slices have all dependencies `VERIFIED`, the slice appearing earliest in the Complete route is selected; only an explicit Level 3 route change may alter that order. There is no `IN_PROGRESS` or `VERIFIED` slice at v0.1.

### 8.2 Complete route

| Slice | Priority | State | Depends on | Target outcome |
|---|---:|---:|---|---|
| `CB-000 — Repository and toolchain baseline` | P0 | `IN_PROGRESS` | Documentation baseline | Real module skeletons, pinned build/package entry points, meaningful checks/tests, pre-commit hygiene and staged secret scanning, Gitleaks, a working root `make ci`, and CI without provider keys. No business behavior. |
| `CB-010 — Local runtime and data foundation` | P0 | `PLANNED` | `CB-000` | Healthy MySQL/two Redis/Elasticsearch/IK/RocketMQ Broker+Proxy, independent migration jobs, bootstrap and migration identities, runtime grants for `auth_app`/`commerce_app`/`agent_app`, and real readiness/permission denial checks. |
| `CB-085 — Python RocketMQ consumer viability spike` | P0 | `PLANNED` | `CB-010` | Early reproducible decision on Python connection, subscription, consumption, acknowledgement, retry/redelivery, long-processing behavior, source ordering, tombstones, and rebuild/alias handoff. No fallback is pre-approved. |
| `CB-020 — Identity, JWKS and JIT OBO vertical slice` | P0 | `PLANNED` | `CB-010` | Explicit direct-user versus OBO token chains, login/JWKS, `POST /api/sessions` server-generated support-session ownership foundation, authenticated exchange with verified session binding, exact scope, commerce authorization, cross-user/sandbox rejection, and auth-table least-privilege evidence. |
| `CB-030 — Product catalog and cache invalidation` | P0 | `PLANNED` | `CB-020` | Product and CRM truth; null-cache plus Bloom penetration protection, mutex hot-key rebuild, jittered TTLs, transactional MySQL change plus Outbox, request-side best-effort delete, consumer idempotent delete/rebuild, no request-thread cache/Elasticsearch dual write, and evidence that `auth_app` cannot access commerce business tables. |
| `CB-040 — Standard ordering and MySQL inventory` | P0 | `PLANNED` | `CB-030` | Idempotent standard orders using MySQL stock updates, finite retry, ownership checks, and auditable rejection paths. |
| `CB-050 — Seckill quota, reservation, and Lua admission` | P0 | `PLANNED` | `CB-040` | Transactional activity allocation, Redis projection, atomic quota/one-user/reservation Lua admission, and status polling without claiming a durable order early. |
| `CB-060 — RocketMQ transaction ordering and delayed cancellation` | P0 | `PLANNED` | `CB-050` | Half-message/Lua/commit-or-rollback with bounded `UNKNOWN`, idempotent order consumption, minimum inventory ledger for order/cancel/restoration/replay, delayed unpaid cancellation, and controlled failure evidence including broker terminal settings. |
| `CB-070 — Mock payment, refund, ledger extension, and state machines` | P0 | `PLANNED` | `CB-060` | Idempotent mock payment, legal payment/refund transitions, ledger extension for payment/refund and full reconciliation, duplicate/illegal-transition rejection, and no double restoration. |
| `CB-080 — Single-agent control plane, tools, SSE, and support evidence` | P0 | `PLANNED` | `CB-020`, `CB-030`, `CB-040` | FastAPI conversation/event/evidence lifecycle over CB-020 sessions; one bounded ReAct loop with shared `attempt_budget`, provider-scoped circuit breaker with minimum request count and half-open probe, main-Agent plus ToolSpec handling without a separate intent classifier, structured `deny_with_feedback`, LiteLLM alias path to a fake provider, filtered SSE, deterministic model fakes, and `cs_db` truth. |
| `CB-090 — RAG core and initial versioned knowledge index` | P0 | `PLANNED` | `CB-010`, `CB-080` | `knowledge.search`, BM25/dense recall, deterministic RRF, rerank alias, sufficiency calibration, retrieval evidence, and initial `knowledge_docs_vN` alias. |
| `CB-100 — Evaluation profile, sandbox, identity provisioning, state, audit, version, and evidence` | P0 | `PLANNED` | `CB-020`, `CB-040`, `CB-060`, `CB-070`, `CB-080`, `CB-090` | Reset-created fixtures, service-authenticated auth provisioning and revoke, opaque test-user handles, idempotent sandbox completion, fail-closed activation/compensation/TTL-janitor closure, test token and OBO sandbox binding, state/audit/version/evidence, SQL filtering, async liveness, irreversible-side-effect stubs, and sandboxed idempotent mock-payment callbacks. No evaluator implementation. |
| `CB-110 — FAQ publication, knowledge sync, cache versioning, and index rebuild` | P1 | `PLANNED` | `CB-030`, `CB-085`, `CB-090` | FAQ draft/publish truth, Outbox/RocketMQ sync, Python adapter, source versions/tombstones, two-level cache, rebuild validation, and alias switch. |
| `CB-120 — PendingAction, ActionReceipt, and turn commit point` | P1 | `PLANNED` | `CB-070`, `CB-080` | Sensitive prepare/confirm, atomic validate/consume/execute, immutable receipt truth, server idempotency, projection, and retry without re-execution. |
| `CB-130 — Memory watermarks, tiered output, and human handoff` | P1 | `PLANNED` | `CB-110`, `CB-120` | Summary/prompt/PII/output safety plus commerce-owned authoritative ticket state and SLA events, controlled masked auditable `handoff_packet`, agent handoff projection, support-session `HUMAN_PENDING` plus open-ticket sensitive-write prohibition, and reviewed/masked/synthetic failure-candidate bundle export through an authenticated import contract only. |
| `CB-140 — Minimal web demonstration` | P1 | `PLANNED` | `CB-020`, `CB-030`, `CB-060`, `CB-080`, `CB-090`, `CB-120` | Login, products, seckill/reservation status, support chat, and receipt cards. No cart, multi-page store, or workstation. |
| `CB-150 — Observability, scripted demonstration, fault drills, and measured evidence` | P1 | `PLANNED` | `CB-100`, `CB-130`, `CB-140` | Metrics, optional no-op-capable trace sink, scripted reset/demo, repeatable fault drills, JMeter transaction/seckill load tests, Locust plus Mock LLM agent-framework tests, real-LLM end-to-end latency/quality only, and measured evidence that does not mislabel provider throttling as CityBuddy capacity. |
| `CB-900 — Multimodal intake and object storage outline` | P2 | `DEFERRED` | Explicit promotion only | Future multimodal boundary only. |
| `CB-910 — Action recovery scanning and advanced resilience outline` | P2 | `DEFERRED` | Explicit promotion only | Future recovery scanner and observed-failure-driven resilience only. |
| `CB-920 — Advanced retrieval, provider-cache experiments, and expanded operations views` | P2 | `DEFERRED` | Explicit promotion only | Optional experiments and expanded views; no result assumed. |

## 9. Detailed slice specifications

Only the active slice and the next two slices in the Complete route are expanded here. At v0.1 these are `CB-000`, `CB-010`, and `CB-085`. `CB-020` remains a route outline and is expanded only after `CB-000` ends and `CB-010` becomes the active slice.

### 9.1 `CB-000 — Repository and toolchain baseline`

**State:** `IN_PROGRESS`

#### Goal

Create a real, reproducible repository skeleton for the Java, Python, and web tracks, with one build/package manager per track and a meaningful `make ci` entry point. Establish engineering checks without implementing business capabilities or pretending that runtime dependencies exist.

#### In scope

- Create executable skeleton modules for `auth-service`, `commerce-service`, `agent-service`, `knowledge-indexer`, and `web`. A declared module must contain a real buildable/importable entry point; empty directories are not deliverables. Do not create an empty `litellm-proxy` placeholder: its first real, validated configuration belongs to the agent integration slice.
- Create one root Maven reactor and Maven Wrapper for both Java services. Configure Java 21 compilation, Spring Boot 3.5 dependency management, Spotless, Checkstyle, and JUnit 5/Surefire.
- Create one `uv` workspace for both Python packages, package-local `pyproject.toml` metadata, and a committed `uv.lock`. Configure Ruff formatting/linting, mypy, and pytest.
- Create one npm-managed React/TypeScript/Vite web package with a committed package lock. Configure Prettier, ESLint, TypeScript checking, Vitest, and a Vite production build. Do not introduce a second JavaScript package manager.
- Add repository-level line-ending/encoding and ignore rules, safe environment-variable examples without values, and Gitleaks secret scanning.
- Create `.pre-commit-config.yaml` with only meaningful hooks applicable to the current files, including basic text hygiene and staged secret scanning. Pre-commit and CI must have clear, consistent responsibilities.
- Add a root Makefile with real `setup`, `format`, `lint`, `typecheck`, `test`, `build`, and `ci` behavior for the files that exist in this slice. Runtime-only targets such as `up`, `down`, and `test-integration` are added by `CB-010`, when they can execute real work.
- Add CI for the current code that invokes the same `make ci` entry point. It must not require Docker services, a real LLM key, or any private credential in this slice.
- Add meaningful baseline tests: Java application contexts or equivalent bootstraps load in an isolated test profile; Python application/worker factories construct with deterministic settings; the web shell renders or exercises real starter behavior. Tests that only assert a constant are forbidden.
- Update `README.md` with setup/test commands only after those exact commands have run successfully.

#### Acceptance criteria

1. A clean checkout can install/synchronize dependencies through the committed wrapper and lockfiles without choosing between competing build systems.
2. The Maven reactor compiles both Java modules with release 21, runs formatter checks, lint, and real tests, and produces build artifacts. No Gradle file or wrapper exists.
3. `uv sync --locked` (or the locked equivalent selected in executable configuration) succeeds for the workspace; Ruff, mypy, pytest, and package build/import checks run against both Python packages.
4. `npm ci` succeeds; Prettier check, ESLint, `tsc --noEmit`, Vitest, and the Vite build run against the web package.
5. Gitleaks scans the tracked repository and fails on a controlled secret fixture supplied only during a test of the scanner, without committing a secret.
6. `make ci` runs all applicable format checks, linters, type/compile checks, unit tests, builds, and secret scanning; it exits non-zero when any real check fails.
7. No Make target, CI job, or test is a no-op, unconditional success, missing-directory skip disguised as success, or placeholder.
8. CI calls `make ci` rather than reimplementing a divergent command set and completes without model-provider keys or live infrastructure.
9. The skeleton contains no product, order, payment, refund, seckill, agent decision, retrieval, sandbox, or evaluation behavior.
10. `README.md` still distinguishes implemented repository tooling from all future runtime and business capabilities.
11. `.pre-commit-config.yaml` runs applicable text-hygiene and staged-secret hooks; no hook is an echo, unconditional success, nonexistent tool, or command that cannot inspect current files.
12. A controlled synthetic failure proves staged secret detection and non-zero propagation without committing a real secret, and pre-commit does not contradict the CI entry point.

#### Rejection paths

- Reject the slice if both Maven and Gradle, multiple Python environment managers, or multiple JavaScript package managers are introduced.
- Reject the slice if any active package lacks a committed lock/wrapper strategy or silently resolves unbounded dependencies in CI.
- Reject the slice if `make ci` succeeds while skipping an existing module, swallows a failing subprocess, or runs only formatting without tests/builds.
- Reject placeholder tests such as constant assertions, empty suites accepted as success, or tasks that echo a future command without executing it.
- Reject any CI path that requires a real LLM key, commits a secret, reaches a paid model, or depends on local untracked state.
- Reject business endpoints, database schemas, Compose topology, message topics, or future-slice dependencies added “for convenience.”
- Reject README claims of runnable services, passed integration tests, or startup commands that were not executed.
- Reject pre-commit hooks that are no-op, always successful, reference absent tools, hide skips, or conflict with the checks invoked by CI.

#### Out of scope

- Docker Compose and all stateful runtime dependencies;
- database selection details beyond module ownership, migrations, or any business DDL;
- authentication flows, JWTs, JWKS, or OBO;
- product, order, inventory, seckill, payment, refund, agent, RAG, action, sandbox, or web feature behavior;
- release packaging, deployment, performance testing, and demonstration scripts.

#### Required evidence

- Pull-request diff showing the real module/build structure and absence of competing build systems;
- clean-checkout logs for Maven Wrapper, locked `uv`, and `npm ci` setup;
- full `make ci` log with each invoked check and its exit status visible;
- test reports proving non-empty Java, Python, and web tests ran;
- Java 21, Python 3.11, and pinned build-tool version outputs captured in the pull request;
- Gitleaks pass result and a documented controlled failure check that did not commit a secret;
- `.pre-commit-config.yaml` execution plus a controlled staged-secret failure using synthetic test material and proof that the text-hygiene hooks inspect current files;
- pull-request findings from a real independent test/review subagent covering `make ci`, module coverage, exit-code propagation, no-op/skip risks, and the controlled failure; this item remains unfilled until that review actually occurs;
- README command evidence for every command added to README.

#### Completion record

| Field | Value |
|---|---|
| Status | Not started |
| Branch | Not started |
| PR | Not started |
| Commits | Not started |
| Tests | Not started |
| Notes | Not started |

### 9.2 `CB-010 — Local runtime and data foundation`

**State:** `PLANNED`

#### Goal

Provide a reproducible, health-gated local dependency environment and independent database migration path for later slices. Prove connectivity, permissions, and component-specific semantics without claiming that a business chain is implemented.

#### In scope

- Add a Docker Compose topology for MySQL 8, Commerce Redis 7, Support Redis 7, Elasticsearch 8 with a version-matched IK plugin, RocketMQ NameServer, and RocketMQ Broker plus Proxy.
- Pin exact image versions or digests in executable configuration. Elasticsearch and IK are pinned and tested as one compatibility pair.
- Create `commerce_db` and `cs_db` on the one MySQL instance. Use a bootstrap/admin identity only for database/account/grant creation; separate auth, commerce, and agent migration identities for their migration streams; and runtime identities `auth_app`, `commerce_app`, and `agent_app` with least-privilege grants and no DDL/global/admin privilege. Do not create placeholder business tables merely to test grants.
- Establish independent migration jobs/entry points for the auth-owned and commerce-owned streams in `commerce_db` and the agent-owned stream in `cs_db`. Each records real migration history, is rerunnable, and finishes before APIs start. Runtime processes never auto-run migrations or use migration credentials.
- Configure Commerce Redis with `noeviction` and AOF, and Support Redis with TTL-oriented use and an LFU eviction policy. Expose separate connection variables.
- Configure meaningful health checks for MySQL, both Redis instances, Elasticsearch, RocketMQ Broker/Proxy, and migration completion. Use health/completion dependencies rather than sleep-only startup ordering.
- Add real `make up`, `make down`, and `make test-integration` targets, and extend `make ci` only with fast integration checks appropriate for CI resources.
- Add minimal connection/permission tests: all declared runtime identities and their denial boundaries; independent Redis URLs and policies; Elasticsearch analyzer/vector/kNN/alias operations; RocketMQ Proxy connection and a normal-message round trip.
- Perform the IK compatibility spike and record the exact tested pair in runtime configuration and the slice completion notes.
- Keep all credentials synthetic and local; `.env.example` contains names and safe descriptions, not values.

#### Acceptance criteria

1. `make up` returns successfully only after required long-running dependencies are healthy and all three owning migration jobs/streams have completed successfully; a failed health check or migration makes startup fail visibly.
2. `make down` removes the local application topology predictably without deleting durable data unless an explicit destructive reset command is separately chosen in a later slice.
3. `make test-integration` proves `agent_app` cannot access `commerce_db`, runtime identities cannot execute DDL or use global/admin grants, and applications do not use bootstrap/admin credentials. Auth-versus-commerce family denials are added when real auth and business tables exist in `CB-020` and `CB-030`.
4. Bootstrap, auth migration, commerce migration, agent migration, and runtime identities are distinguishable. Migration jobs are separately invokable and rerunnable, maintain independent owning histories, and are not hidden API startup side effects.
5. Runtime inspection proves Commerce Redis has the intended no-eviction/AOF behavior and Support Redis has the intended LFU/TTL-oriented behavior. The two URLs resolve to distinct instances.
6. Elasticsearch loads IK successfully, analyzes a representative mixed-language query, creates a test `dense_vector` mapping, performs a minimal kNN query, and atomically changes a test alias.
7. RocketMQ exposes the 5.x Proxy endpoint and passes a minimal producer/consumer round trip through the selected client path. Transaction and delay fault semantics remain owned by `CB-060`.
8. Applications never use MySQL root/bootstrap/admin credentials. Runtime identities cannot execute DDL or hold global/admin grants; `agent_app` cannot access `commerce_db`; no cross-database join is introduced. Auth-versus-commerce table-family isolation is completed as real owning tables appear in `CB-020` and `CB-030`.
9. Health checks validate actual dependency readiness rather than only process existence or open TCP ports where a stronger check is available.
10. README adds local-runtime commands only after their exact successful execution is captured. It does not claim product, order, agent, or evaluation functionality.

#### Rejection paths

- Reject a Compose topology that starts dependents after a fixed sleep, treats “container running” as readiness, or masks an unhealthy dependency.
- Reject a single Redis instance, identical persistence/eviction policy for both workloads, or an application connection that can silently switch between them.
- Reject application use of bootstrap/admin or migration credentials, runtime DDL/global/admin grants, `agent_app` access to `commerce_db`, or cross-database SQL joins.
- Reject an API/worker that mutates schema during normal startup or a migration target that succeeds without connecting to and validating its database.
- Reject RocketMQ without a reachable 5.x Proxy path or a client probe that bypasses the selected runtime path.
- Reject Elasticsearch startup without a verified IK analyzer. If a reproducible matching plugin artifact/build cannot be obtained, mark `CB-010` `BLOCKED`; do not change analyzers silently.
- Reject committed credentials, provider keys, private URLs, or environment files containing real values.
- Reject any assertion that seckill, transactions, RAG, or agent behavior is complete merely because dependencies are healthy.

#### Out of scope

- Complete business table schemas, seed catalogs, orders, inventory, or support evidence data;
- login/JWT/OBO behavior;
- transaction-message seckill logic, delayed cancellation behavior, or failure drills;
- Python RocketMQ consumer acceptance for `knowledge-indexer` beyond runtime availability;
- LiteLLM business routing, real model access, RAG content, sandbox behavior, or a web demonstration.

#### Required evidence

- Pinned Compose configuration and image/plugin provenance in the pull request;
- `make up`, health status, migration-job logs, and `make down` results from a clean environment;
- repeat-run migration logs proving idempotent history handling;
- MySQL account/grant creation and denial output proving applications do not use root, runtime identities lack DDL/global/admin privilege, migration identities are separate, and `agent_app` cannot access `commerce_db`;
- Redis configuration/persistence checks for both distinct instances;
- IK analyzer, dense-vector, kNN, and atomic-alias smoke-test output;
- RocketMQ Broker/Proxy version/configuration evidence and normal-message round-trip output;
- failure evidence showing startup becomes non-zero when a required migration or health check is intentionally made to fail.

#### Completion record

| Field | Value |
|---|---|
| Status | Not started |
| Branch | Not started |
| PR | Not started |
| Commits | Not started |
| Tests | Not started |
| Notes | Not started |

### 9.3 `CB-085 — Python RocketMQ consumer viability spike`

**State:** `PLANNED`

#### Goal

Produce a reproducible go/no-go decision for the Python `knowledge-indexer` RocketMQ 5 consumption boundary before knowledge-sync implementation depends on it. The spike proves client semantics; it does not implement the production indexer.

#### In scope

- Use the RocketMQ Broker/Proxy and exact Python client patch pinned by `CB-010` through a small disposable messaging adapter.
- Prove connection, topic subscription/filtering, message consumption, the available acknowledgement mode, controlled failure and retry/redelivery, and long-processing/invisible-duration behavior.
- Prove source-version out-of-order rejection and tombstone handling with deterministic test payloads.
- Exercise a disposable rebuild into a new Elasticsearch index and an atomic alias handoff to show that the consumer boundary can support the later workflow.
- Record client mode, Proxy/Broker/client versions, acknowledgement semantics, retry timings, duplicate behavior, exceptions, and operational limitations in the pull request and Completion record.
- Keep production language and protocol boundaries frozen. No fallback or partial production adapter is approved by this spike alone.

#### Acceptance criteria

1. A clean local run connects through the selected RocketMQ 5 Proxy path and consumes only the intended subscription/filter.
2. The experiment identifies and demonstrates the exact acknowledgement mechanism used by the selected Python client mode.
3. A controlled handler failure results in observable bounded retry/redelivery behavior with duplicate delivery documented and safely handled by the test adapter.
4. A controlled long-processing case proves the effective invisible-duration/lease behavior or produces a reproducible failure showing that it cannot meet the worker requirement.
5. Older source versions are rejected deterministically and do not overwrite a newer applied version.
6. Tombstone delivery removes or marks the disposable projection idempotently, including duplicate tombstones.
7. A disposable rebuild writes a new index version, validates it, and switches the test alias atomically without mutating business truth.
8. The result is an evidence-backed `viable` or `not viable` decision. A not-viable result marks the dependent knowledge-indexer MQ work `BLOCKED`; it does not silently authorize a language/client/protocol change.

#### Rejection paths

- Reject an experiment that proves only publish/receive but not acknowledgement, retry/redelivery, long processing, ordering, tombstones, and alias handoff.
- Reject conclusions inferred only from documentation or examples without execution against the pinned local Broker/Proxy/client combination.
- Reject swallowed callback errors, automatic acknowledgement that cannot be observed, or a test that treats duplicate delivery as impossible.
- Reject a rebuild test that writes directly through the read alias or switches an unvalidated index.
- Reject production knowledge-indexer behavior, business event schemas, or a fallback implementation added during the spike.
- Reject wording that treats an issue report as a maintainer-confirmed root cause or formal compatibility guarantee.

#### Out of scope

- Production FAQ/product publication, Outbox relay, and complete knowledge event contracts;
- production `knowledge-indexer` service behavior, monitoring, scaling, or deployment;
- RAG retrieval quality, reranking, cache population, or corpus migration;
- changing the frozen Python worker boundary or pre-approving another client/protocol.

#### Required evidence

- Exact Broker, Proxy, Python client, Python runtime, and Elasticsearch versions used;
- commands and logs for connection, subscription/filtering, consumption, and acknowledgement;
- controlled failure and redelivery logs with attempts, timing, and duplicate identifiers;
- long-processing/invisible-duration result with reproducible timing and exceptions;
- deterministic out-of-order and tombstone test output;
- disposable rebuild validation and atomic alias-switch output;
- a concise viability decision and any blocking evidence, recorded without claiming production completion;
- real Branch, PR, Commits, Tests, and Notes only at completion.

#### Completion record

| Field | Value |
|---|---|
| Status | Not started |
| Branch | Not started |
| PR | Not started |
| Commits | Not started |
| Tests | Not started |
| Notes | Not started |

## 10. Open spikes, risks, and change control

### 10.1 Required spikes

| Spike | State | Owning slice | Required experiment and exit criteria | Consequence if it fails |
|---|---:|---|---|---|
| `SP-001 — Python RocketMQ consumer viability` | Open | `CB-085` | Against the pinned RocketMQ Broker/Proxy and Python client, prove: connection; subscription/filtering; consumption; explicit or listener acknowledgement; retry/redelivery after controlled failure; long-processing/invisible-duration behavior; source-version out-of-order rejection; tombstone handling; and a rebuild followed by alias switch. Record client mode, exceptions, timing, and duplicate behavior. | Block `knowledge-indexer` MQ implementation. No language or protocol fallback is pre-approved. Moving the MQ adapter out of Python changes a frozen language/service boundary and therefore requires Level 3 review with the failed spike evidence. |
| `SP-002 — Elasticsearch/IK version pair` | Open | `CB-010` | Pin one Elasticsearch 8 patch and matching IK artifact/build, install reproducibly, pass startup and analyzer tests, and record provenance in executable configuration. | Block `CB-010`. Do not silently omit IK or change analysis behavior. A different analyzer is a contract change requiring evidence and review. |
| `SP-003 — RocketMQ transaction failure drill` | Open | `CB-060` | Prove Lua rejection rolls back without downstream delivery; commit followed by duplicate delivery creates one durable order; and a lost/unknown second-phase result triggers checkback based solely on the decision marker. Prove `UNKNOWN` is intermediate, record broker transaction timeout/check interval/maximum check count, verify the terminal broker outcome, and prove marker/reservation TTL covers the complete configured window. | Block `CB-060`. Any move away from the transaction-message mainline requires Level 3 review with failure evidence, affected invariants, migration cost, and updated tests. |

### 10.2 Risk register

| Risk | Guardrail and owner |
|---|---|
| Dependency/version drift | Each owning slice pins exact patches and image digests in build files/lockfiles. Markdown keeps only the compatibility boundary. Renovation is accepted only with real build/contract tests. |
| Retry amplification across agent, proxy, HTTP, and MQ | One bounded attempt budget is propagated. `ModelRouter` owns tier changes; LiteLLM gets at most one transient/network retry and same-tier fallback. Side-effect retries return existing receipts/results. Owned by `CB-080` and `CB-120`. |
| Redis or Elasticsearch treated as business truth | Contract tests and reconciliation always compare against MySQL. User-visible order/action success requires durable MySQL state or ActionReceipt. Owned by transaction slices. |
| Cross-database or cross-service data leakage | Bootstrap/migration/runtime identity separation, `auth_app`/`commerce_app`/`agent_app` grants, no cross-database joins, API-only boundaries, token-derived ownership, private data excluded from RAG, and staged negative tests in `CB-010`, `CB-020`, `CB-030`, and later agent migrations. |
| Evaluation sandbox leakage, orphaned test identity, or late asynchronous effects | Commerce-orchestrated auth provisioning/revoke, opaque TTL-bound handles, idempotent normal completion, fail-closed activation/compensation, TTL/janitor backstop, header/claim equality, ACTIVE/DEAD registry, SQL/repository filters, and consumer liveness checks. Owned by `CB-100`. |
| Model text contradicts action state | ActionReceipt is authoritative; action assertions wait for matching receipt, and retries regenerate text only. Owned by `CB-120` and `CB-130`. |
| Private or provider credentials in repository/CI | Environment/secret injection, safe examples, redaction tests, Gitleaks, deterministic model fakes, and no real provider key in CI. Owned from `CB-000` onward. |
| Evidence or observability divergence | `commerce_db` and `cs_db` remain authoritative for their domains; optional Langfuse tracing is a mirror and may degrade to a no-op sink. Owned by `CB-080`, `CB-100`, and `CB-150`. |

### 10.3 Change classification

- **Level 1 — editorial:** naming, wording, links, or document placement that does not change behavior, ownership, security, sequencing, or acceptance. Correct directly and record it in the normal pull request.
- **Level 2 — implementation detail:** exact patch versions, image digests, migration library, package layout, test framework settings, or an equivalent implementation technique that preserves every frozen contract and slice dependency. Pin it in executable configuration and prove it with tests.
- **Level 3 — frozen-contract change:** service/language responsibility, truth ownership, security boundary, token claims, sandbox semantics, transaction-message mainline, action truth, development order, or committed P0/P1 scope. Mark the affected slice `BLOCKED` first. Record the failed test/spike or incompatible primary-source evidence, the exact contract touched, impact radius, migration/operational cost, and replacement acceptance criteria. No fallback is approved by anticipation.

Current Level 3 conflicts: **none identified as of 2026-07-12**.
