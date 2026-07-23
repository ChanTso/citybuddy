# Independent-review preflight checklist

This checklist is the mandatory implementation self-review that precedes every independent
review request. Apply every item to the complete current diff, including production code,
tests, migrations, executable configuration, contracts, route state, and closeout records.
Record the result and concrete evidence, or a precise not-applicable rationale, in the pull
request. A later semantic diff change requires the checklist to be executed and recorded again.

## Recurring defect classes

### Uniform existence-concealment responses

- Enumerate unknown, other-owner, cross-session, cross-sandbox, and malformed identities at
  every lookup boundary changed by the diff.
- Confirm identities that must be concealed use the same status, public schema, bounded message,
  and lookup shape. No broader fallback query, count, timing-dependent branch, or response field
  may reveal which concealed identity exists.
- Exercise the real route with paired unknown and cross-boundary fixtures; a service mock or a
  repository-only assertion is insufficient when the public boundary is in scope.

### Complete canonical idempotency intent comparison

- List every field that carries the operation's semantic intent, including owner/sandbox,
  resource, amount/version, outcome, correlation, and server-owned context where applicable.
- Confirm initial execution, sequential replay, concurrent replay, and conflicting identity reuse
  all compare the same canonical representation. No stored field may be omitted from reconstruction
  or normalized differently between write and replay paths.
- Prove same-intent convergence and one-field-at-a-time conflicting reuse against durable truth;
  use a locking current read where a concurrent winner must be observed.
- When bounded mutation retries exhaust, resolve committed idempotency truth with an explicit
  three-state observation: `FOUND`, `CONFIRMED_ABSENT`, or `INDETERMINATE`. A same-intent sibling
  commit returns the existing result without another mutation; a conflicting intent remains a
  deterministic conflict; only a successfully completed locking read may establish absence and
  permit one bounded final mutation attempt. A lock timeout, observation failure, or interrupted
  read is `INDETERMINATE`, never evidence of absence and never a terminal conflict or unavailable
  conclusion; use bounded re-observation and, if truth remains indeterminate, a retryable response.
  Prove the database lock wait itself is physically bounded on every production transaction path
  that can acquire the contended lock, including initial mutation, retry, final mutation, and truth
  observation; bounding only the recovery read leaves the flow blocked before it can enter the
  three-state resolver. Apply the boundary at a shared transaction/connection layer so new paths
  inherit it instead of relying on per-query opt-in. A framework transaction deadline is
  insufficient unless the real driver demonstrably interrupts row-lock waits. If a pooled session
  variable supplies the bound, capture and restore its prior value in `finally` so the recovery
  policy cannot leak into later borrowers. The restore operation must remain executable after the
  controlled failure: do not make cleanup depend on an already expired transaction deadline or the
  same failed resource state. Prove restoration with a real single-connection pool by borrowing the
  physical session again after the failure and comparing the original value; a mocked `finally`
  call or a disposable non-pooled connection is not cleanup evidence.
  Only a positively identified database resource or transaction-acquisition failure may use the
  unavailable response. Exercise found, confirmed-absent recovery, indeterminate-then-found,
  persistently indeterminate, conflicting intent, and real dependency unavailability independently.

### Total parsing exception boundary

- Treat the complete untrusted parsing operation as one failure class: character encoding,
  decoding, framing, JSON/type conversion, numeric conversion, and protocol-specific validation.
- Confirm every parse failure converges to the fixed public error without an internal exception,
  traceback, partial acceptance, permissive fallback, or secret-bearing log.
- Exercise a class-based malformed-input battery, including raw and decoded non-ASCII, invalid
  encoding, missing structure, wrong primitive types, bounds, control bytes, and nulls where the
  protocol permits them to reach the parser.
- For JSON from an untrusted dependency, reject duplicate object keys at the decoder boundary.
  A later schema check cannot recover the overwritten first value once a permissive decoder has
  accepted the duplicate. Apply the same duplicate-key hook to success and error responses.

### Bounds apply before materialization

- Enforce every count and byte bound at the acquisition boundary, before an untrusted query,
  response, message batch, or collection is fully materialized. A size check after an unbounded
  fetch is not a bounded boundary; use an exact limit or at most `maximum + 1` rows/items so the
  extra item proves overflow and fails closed without loading the full source.
- Exercise the production acquisition path with more underlying rows/items than the configured
  maximum. Confirm the fetch itself remains bounded, the response or state transition is rejected,
  and no prefix is misreported as a complete snapshot.
- Elasticsearch search evidence must require an object-valued `hits.total` with
  `relation == "eq"`. A `gte` relation is a lower bound, not an exact set/count commitment. For a
  deliberately bounded top-k query the exact total may exceed returned hits; for a complete
  candidate enumeration it must equal the materialized set under the explicit `maximum + 1` cap.

### Projection marker and owner-journal set equality

- Enumerate owner snapshot truth, accepted Broker journal events, and candidate control markers as
  independent faces. First classify which journal events are covered by the captured owner
  snapshot; then require the candidate `SYNC_EVENT` stable event-id set to equal that covered journal
  set and compare every marker content field to the canonical event commitment. Marker shape
  validity is only a local assertion and cannot establish membership.
- Materialize every owner-covered accepted event before candidate validation, including stale
  historical versions whose public document is already newer. Exercise real deletion of an expected
  marker and insertion of a shape-valid orphan marker; both must fail validation. Future journal
  events not yet visible in the owner snapshot remain unacknowledged and outside the committed set,
  never silently promoted to owner truth.
- When Broker receipt disposition is non-atomic, durably seal the exact validated marker set before
  the first ACK. Recheck every currently pending receipt against that sealed set before acknowledging
  any of them; an owner-covered event that arrives after validation must remain unacknowledged and
  force revalidation. Exercise a controlled multi-message partial ACK where the first disposition
  succeeds and a later one fails, then prove restart reconstructs the already-ACKed prefix from the
  durable checkpoint and converges with the redelivered suffix. A completion flag written after ACK
  is not a substitute because the acknowledged prefix is no longer reconstructable from Broker
  delivery alone.
- Treat a durable receipt checkpoint as grow-only under every concurrent control-record transition.
  Read and retain the storage engine's compare-and-set version, create with create-only semantics,
  and update only against that exact version. After a conflict, reread the latest record and
  re-evaluate the transition; never resubmit a stale full document. Pass one immutable canonical
  checkpoint value—complete descriptor tuple plus its deterministically derived marker map—to both
  receipt disposition and subsequent completion. Completion must verify every expected descriptor
  by stable identity and exact content as an immutable subset of the latest record, preserve any
  concurrent superset, and apply the same check on an already-completed idempotent path. Exercise a
  deterministic interleaving where a stale completion pauses after reading, another coordinator
  seals a new descriptor, the stale write loses the compare-and-set, and restart still reconstructs
  the exact checkpoint and marker that authorized ACK. Audit all writes to the control record; one
  remaining unconditional update invalidates the evidence.

### One total-order contract in all three places

- Compare the production query/order implementation, the public OpenAPI or frozen contract, and
  the executable assertion. All three must name the same full deterministic key sequence.
- Include real tied records that exercise every trailing tie-break key under the actual bounded
  query and repeat the read to prove byte-for-byte stable order.
- Reject evidence based on distinct timestamps, a test-only query, or a checker that validates
  fields without validating their order.

### Bidirectional audit/state completeness reconciliation

- Make the reconciliation predicate total over every audited row in the sandbox scope. Do not let
  an `entity_type` or other `WHERE` filter remove a row from the reconciler's responsibility; dispatch
  every row by its declared type to that type's business anchor. Keep the type whitelist closed and
  fail closed for an unknown or future type.
- Separate enumeration from validity assertions. Every reconciliation `JOIN`/`WHERE` predicate must
  be classified in the pull request: enumeration may use only the stable sandbox scope, stable
  business keys, and the terminal classification that defines a truth face. Intent hashes,
  cross-row identities, owner/context equality, amounts, currencies, versions, linkage, and all
  other validity predicates run only after enumeration; placing one in the enumerator turns an
  inconsistency into an apparent absence. Treat an unclassified predicate, or a predicate classified
  as an assertion but retained in `JOIN`/`WHERE`, as incomplete review evidence.
- Treat prose rules, manually reviewed predicate tables, and source-text parsing as useful heuristics,
  not totality evidence. SQL and Java text admit function wrapping, aliases, subqueries, and indirect
  helper calls that a bounded textual checker cannot enumerate completely. Prove totality at the
  observable behavior boundary with a real integration matrix generated from `information_schema`:
  every physical column in each audited durable face must have an explicit anchored-content or
  mechanism-backed-invariant disposition and a controlled data-consistency fault. A new schema
  column without a disposition must make matrix construction fail closed. Exercise every applicable
  corruption with each companion truth face present and absent; normal publication/state behavior
  must reject rather than silently report zero work. Text-level predicate allowlists may remain as a
  cheap review signal, but their success is never evidence that all predicates or reachable
  enumerators were found.
- Define behavioral completeness over the Cartesian product of two enumerable ground truths:
  every lifecycle state reachable from the persisted state machine and every physical column from
  `information_schema`. Construct a real fixture in each reachable state and execute every column
  disposition there; a column matrix sampled in only one legal state is incomplete. Bind the state
  inventory to the database state type and every production transition so an added state or schema
  column fails closed. For the FAQ publication aggregate the closed state set is exactly `DRAFT`
  and `PUBLISHED`; a mutable current draft is anchored to its latest immutable draft command, while
  the latest applied publication command anchors only the published face. Any future completeness
  objection must identify an omitted reachable state or physical column; a proposed third
  orthogonal dimension triggers owner review rather than an unbounded implementation expansion.
- Enumerate every overlapping authoritative truth face independently and require equality of their
  stable key sets before comparing row content. For a successful standard payment, independently
  enumerate `PAID` orders, `STANDARD_PAYMENT` ledger movements, terminal successful callbacks, and
  declared `PAYMENT_CALLBACK` audit rows; then assert every content column for each common key.
  Exercise deletion of every face, every column formerly carried by an enumerator predicate, and
  all two-way combinations. A query that constructs one face by joining through another face's
  validity predicates is not independent set reconciliation.
- Anchor the forward check in authoritative business truth. Every successful event must have
  exactly one audit/state reference whose reference identity, owner/sandbox, session, trace,
  operation, entity type/id/version, outcome, and other content-bearing columns all match.
- Classify every audit column exposed by a public API or carrying cursor, ordering, or time semantics
  into exactly one of two cases. An externally anchorable column must be written explicitly from
  business truth in the same transaction, without a database-generated default, and reconciled by
  exact equality. A database-generated column with no external truth must have an enforced internal
  invariant; document its residual freedom and why that freedom carries no content semantics. No
  content-bearing column may remain excluded from both cases.
- For the current audit schema, anchor `created_at` exactly to the business-event timestamp. Treat
  `sequence_id` as database-generated and require its sandbox-scoped order to be consistent with
  anchored `created_at`; equal-timestamp reordering under the existing sequence tie key is the
  documented non-content residual and need not be detected. An internal invariant is acceptable
  only when the write path names and implements the mechanism that guarantees it; require a real
  concurrent counterexample test when timing or transaction order could otherwise violate it. The
  current mechanism serializes every sandbox audit producer on the same `eval_sandbox` row before
  choosing a wall-clock-clamped event time and assigning `sequence_id`.
- Do not mistake aggregate statistics for a commitment to a fixed legacy set. Counts, maxima, and
  boundary-row identities are useful fast-failure checks but cannot exclude delete-and-replace
  attacks. V013 therefore commits the complete ordered legacy row set: every column, including
  `sandbox_id` and `sequence_id`, uses the pinned UTF-8 byte-length-prefix encoding (`N;` for null),
  timestamps use UTC Unix epoch microseconds, each row is SHA-256 hashed, and row hashes ordered by
  `sequence_id` are chained from the format-domain seed. Migration and reconciliation must share the
  exact format identifier and executable known-answer test. Recompute and compare the whole-set
  digest before accepting legacy state or replay; a count/cutoff match alone is never sufficient.
- Only rows in that immutable V013 set commitment may retain `LEGACY_CUTOFF`; every post-migration
  row is explicitly `BUSINESS_EVENT`, payment rows may never be legacy, and a self-declared future
  legacy row fails closed. Exercise multiple legacy rows in multiple sandboxes, deletion of a lower
  sequence, same-hole digest-self-consistent replacement, every single legacy column, and
  cross-sandbox redistribution. A legacy same-intent replay rebuilds product observation truth from
  the stored audit timestamp; it must not replace that truth with `now`.
- Enumerate authoritative business truth independently for every whitelisted audit type, and keep
  writer-supported types structurally equal to the reconciliation whitelist so adding a writer type
  without an explicit reconciliation branch fails compilation or an executable coverage test.
- Run the reverse check from every in-scope audit/state reference to authoritative truth so
  duplicates and orphans fail closed; a filter that only validates rows already present, or only
  rows of selected types, is not a completeness proof.
- Exercise real durable corruption for a missing row, each identity-bearing column, duplicate row,
  orphan row, cross-type pseudo-duplicate, unknown type, and content/version mismatch for every
  whitelisted type. Independently tamper every externally anchored content column, and tamper each
  database-generated column so its declared internal invariant is violated. Every affected service
  view must reject normal success, and restoring authoritative data must restore the normal response.

### Exact transient migration capabilities

- When a versioned migration needs data access to a table that does not exist when the fixed grant
  manifest first runs, do not replace the future-table limitation with database-wide DML. Name a
  deterministic migration barrier after the prerequisite DDL, grant only the exact table actions
  needed for the bounded data step, and revoke them when the same migration is sealed or interrupted.
- Keep one version, checksum, and history row across the barrier. Make every phase observable from
  migration-owned state, fail closed for an unknown or partially executed phase, and make grant and
  revoke transitions idempotent without trusting a metadata view that cannot observe another
  account's effective privileges.
- Publish the grantable `AWAITING` phase only after every pre-barrier DDL statement has completed.
  Orchestration must run revocation cleanup after every post-grant result, including a killed client,
  `POPULATING`, `SEALED` with incomplete history, and an unknown phase; retry preflight must also
  revoke before refusing recovery. Prove these windows with real interrupted migration processes,
  the persisted phase/history state, the migration account's `SHOW GRANTS`, and actual denied source
  reads and watermark writes. Successful terminal-state evidence alone does not cover interruption.
- Cleanup after an interrupted grant job must call a dedicated unconditional force-revoke path; it
  must not reuse a phase-sensitive normal grant command that grants again while the barrier remains
  `AWAITING`. Kill the real grant client only after both exact table privileges are observable, then
  prove orchestration exits nonzero while `SHOW GRANTS`, source reads, and watermark writes all show
  the privileges were removed.
- Prove the terminal authority with the migration account's real `SHOW GRANTS` and denied reads and
  writes against both unrelated same-database truth and another owner's private tables. A grant-job
  success message, manifest text, or bootstrap-session metadata query is not terminal evidence.

### Time-bounded integration fixture validity

- Map every time-bounded sandbox, identity, token, lease, and retry window from creation through its
  last asserted use. When a suite grows, prove the fixture is still valid at the boundary under test;
  an expired credential that happens to return the expected status is a false green.
- Give long-running happy-path fixtures a contract-valid lifetime that covers the measured suite
  path, or refresh credentials at explicit phase boundaries. Keep separate short-lived fixtures for
  expiry behavior instead of making one fixture prove both longevity and expiry.
- When a 401/403/404/503 classification matters, record the response body and issuance/failure
  timestamps and identify the component that produced it. Do not carry a parked-defect label from a
  prior run across a different assertion without re-establishing that the same boundary failed.
- When a known defect is parked, name the first dependent required check where it becomes due and
  predefine the sequential disposition if it recurs. A later failure must be attributed from its own
  assertion, response, timestamps, durable truth, and service logs before reusing the parked label.

### Phase-bound controlled failure injection

- Bind every controlled failure to the exact operation phase it claims to exercise: method, target,
  attempt, and the observable trigger must all be explicit. A path-only proxy rule is insufficient
  when reads and writes share that path, because dropping the read can falsely claim a post-mutation
  indeterminate response without executing the mutation.
- Distinguish pre-mutation unavailability from post-acceptance response loss. For the latter, prove
  the authoritative mutation became durable before suppressing its response, then prove retry or
  redelivery reconstructs the accepted result from durable state rather than applying a second
  semantic mutation. Record the trigger evidence and the final durable postcondition.
- Keep the injector fail-closed: if the intended method/phase is never reached, the test must fail
  rather than consume its failure budget on another operation or pass only because a later retry
  happens to converge.
- For a time-varying boundary, inject strictly inside the intended semantic partition with a stated
  margin and require the corresponding discriminator. Do not inject at a moving equality boundary:
  clock progress and TTL rounding can move the sample into an adjacent valid partition while the
  assertion still appears to exercise the original condition.

### Attributable rejection and unavailability classification

- Inventory every internal producer that can render the same bounded public rejection. Give each
  producer a unique server-side reason code and make integration diagnostics capture only that code;
  keep private claims, resource state, dependency details, and the reason code out of the public
  response. A shared status and response body without a producer-level attribution channel is not
  sufficient evidence for an intermittent classification failure.
- Separate credential or authoritative-state validation from dependency acquisition. Invalid
  credentials and positively confirmed inactive, missing, or mismatched state may produce the
  contract rejection; JWKS refresh failure, timeout, connection exhaustion, database read failure,
  or any other inability to determine the truth must produce bounded unavailability and must not
  persist a terminal denial decision. Broad authorization exception handlers must explicitly
  preserve the unavailable class instead of folding it into 401/403/404.
- Exercise every changed decision point in both directions: a confirmed negative returns the fixed
  rejection, while a controlled dependency failure returns the fixed unavailable response. For
  cached dependencies, force cache expiry before the failure; include timing pressure and retain the
  reason code, response, authoritative state, and reached fault boundary so a status coincidence
  cannot pass as attribution.
- Resolve an already committed idempotent result from its complete durable truth before consulting
  mutable admission or liveness state. Exact replay returns that result without mutation even after
  admission closes; conflicting intent or damaged committed truth remains a deterministic conflict.
  Only an operation with no committed result may enter the current liveness and locking path.
- **Evaluation mock-payment owner-approved closure boundary:** enumerate the complete committed result
  as callback, payment attempt, order, payment ledger, and evaluation audit, anchored first by callback
  correlation and retained through signed order/event/context locators when one face is missing. Lock
  and reconcile that five-face set in the mutation transaction before sandbox liveness. Treat each
  face's cardinality as the closed partition `0`, `1`, or `>=2`: zero is missing, one must match every
  committed content column exactly, and two or more is inconsistent even when one row is otherwise
  valid. Use sandbox ownership only to derive the stable-key set under review; enumerate every row for
  those keys before asserting sandbox/type/status content, so a damaged sibling cannot leave scope. Real
  evidence covers the five faces across all three cardinalities, every persisted content
  column, face pairs, and replay waiting across concurrent commit plus completion; it must observe the
  signed callback boundary and reject inconsistency with 409 rather than 403 or 500. A further proposed
  damage dimension must be named explicitly and classified by the owner: a mechanically bounded axis
  is added to the closure, while an unbounded value partition follows the recorded CB-112 residual-risk
  process instead of reopening an unbounded matrix.
- Define each committed face once as executable metadata: its physical tables, stable keys, and
  participating columns. Both the signed callback resolver and evaluation state/audit reconciler must
  derive their enumeration from that same definition; for the order face, `order_id` spans both
  `standard_order` and `seckill_order`. Every injected closure cell must drive the signed callback,
  `/api/eval/state`, and `/api/eval/audit` together and require the same `409` classification. Audit
  cardinality uses the callback entity id or the complete exact sandbox/session/trace/operation tuple,
  never an `OR` over individual context columns; prove that two legitimate operations in one support
  session coexist without a false duplicate. This structural closure is the owner-approved final
  completeness boundary for the internal evaluation view: later internal-only completeness proposals
  are recorded as residual risk, while any finding affecting production payment/refund, transaction
  consistency, identity authorization, or another business-core path remains blocking.
- For the owner-capped internal evaluation callback only, verify that every column named
  participating by the shared face metadata is independently read and asserted by both callback and
  state/audit reconciliation, and has a single-column three-path fault cell; a compound mutation may
  not stand in for its constituent columns. Any excluded column needs an executable per-column
  disposition and an explicit residual-risk rationale. The currently accepted no-second-anchor
  residuals are start `request_idempotency_key`, fixture `evaluation_owner_handle`, and generated
  ledger `movement_id`; do not generalize this exception to production idempotency, refund, inventory,
  transaction, or authorization truth.
- Review exception-to-HTTP mappings against the full subtype hierarchy. Do not map a broad database
  superclass such as `DataAccessException` to unavailable when it also contains lock-contention and
  constraint-conflict subtypes. Prove connection/resource failure, lock timeout/deadlock, duplicate
  constraint, same-intent replay, and conflicting-intent replay independently; transient contention
  must converge inside the service to committed truth or conflict rather than escape into a 503
  boundary handler.
- Integrity and uniqueness failures discovered while resolving already committed truth are deterministic
  conflicts, not unexpected server errors. Use a dedicated internal integrity signal and translate it at
  the owning operation boundary; do not allow a repository `IllegalStateException` or multi-row helper
  failure to escape as 500.
- Before adding an internal-surface invariant to a validator shared with production, enumerate every
  caller and every later legal lifecycle state of the same durable row. A historical committed result
  remains replayable after a later valid transition unless the business contract explicitly revokes
  it; for payment, a legitimate partial or full refund changes the accumulator but must not invalidate
  the immutable original callback result. Add a cross-lifecycle regression that executes the later
  transition and then replays the earlier result, including a no-duplicate durable-side-effect check.

### Faults never become terminal business decisions

- Enumerate every consumer outcome and classify its evidence source. ACK, reject, drop, not-found,
  stale, or another terminal disposition requires a positively established business conclusion;
  malformed or contradictory owner-local state, integrity failure, dependency unavailability,
  timeout, and indeterminate results remain retryable or explicitly unavailable. Audit broad result
  and exception mappings so those fault classes cannot be folded into a business conflict merely
  because they share one internal return shape.
- A lower, owner-local projection may report a contradiction but cannot independently establish a
  business conflict. Route that contradiction through the authoritative truth before any terminal
  disposition; if authoritative truth confirms the operation, repair the local projection and
  preserve at least one retry observation before ACK. Tests that expect a local contradiction to
  become a permanent conflict are defect-preserving tests and must be corrected, not retained as
  compatibility evidence.
- For a stateful multi-phase consumer, close every mechanically enumerable disposition axis. At
  minimum enumerate each persisted state class and mutation phase, including the indeterminate window
  after authoritative success but before owner-local finalization. Field deletion, unknown fields,
  malformed lease/version, and loss of the whole state must preserve retry/replay repair, while only a
  confirmed competing or superseding business fact may terminate.
- Do not claim totality by parsing production source text. A regex or bounded parser still chooses
  operands, syntax, helper reachability, and label granularity; its chosen scope becomes another
  place where persisted state can disappear from responsibility. For a Redis-backed state machine,
  construct each legal persisted class through the real production transition, then enumerate the
  resulting ground truth from live Redis: `SCAN` every owned key, inspect its type, existence and
  TTL, and use `HGETALL` to enumerate every physical field. Generate field deletion/mutation and
  key existence/type/TTL/extra-field faults directly from that snapshot in every mutation phase.
  Re-enumerate before injection and fail matrix construction if the live key or field inventory has
  drifted. Expected absence is also an invariant: for example, a ready tombstone must exercise an
  unexpected versioned-answer key. Source-text analysis may remain diagnostic only; it is not
  completeness evidence.
- Give each implemented state-machine control path a distinct internal detail code and declare the
  complete production-return set in one phase-scoped registry. Collect the codes actually returned by
  real execution and require exact equality with that registry separately before and after the
  authoritative mutation; an unobserved registered code or an unregistered runtime code is a
  construction failure. A label attached only to a literal return statement is insufficient when
  several field, TTL, type, existence, or tombstone predicates flow to it. For every data-damage cell,
  assert both Worker disposition and final convergence across the source state, current versioned
  answer (or its required absence), and corresponding Elasticsearch document; an Agent lookup miss
  alone is not convergence evidence.
- Give every coordination marker both a bounded semantic lease and a bounded physical lifecycle.
  On a TTL-oriented cache, every marker must have positive TTL and its physical TTL must exceed the
  remaining semantic lease by a fixed safety margin; a lease checked only by a future request does
  not reclaim abandoned state. Prove owner crash or lost redelivery cannot accumulate immortal keys,
  early eviction remains fail-closed, expiry becomes retryable absence, exact retry renews safely,
  and stale finalize/abort calls cannot mutate a replacement owner.
- Persist a terminal completion marker only after every required authoritative mutation and
  receipt/journal disposition has succeeded. If the final receipt commit fails or discovers a
  contradictory event, the durable state must remain resumable and must not claim completion;
  prove the crash/failure window and the subsequent redelivery or owner-truth recovery path.
- When two operations consume the same persisted transition shape, define their guard set once and
  reuse it. Review the shared guard against the semantic invariants, including TTL upper bound and the
  strict physical-TTL versus remaining-lease-plus-safety-margin relation; runtime code coverage can
  prove implemented guards execute, but cannot discover a semantic guard that was never implemented.
- **CB-112 owner-approved closure boundary:** completeness evidence is limited to four mechanically
  enumerable ground truths: (1) keys, fields and key-level properties discovered from live Redis,
  (2) persisted classes `missing`, `ready0`, `ready1-live`, and `ready1-tombstone`, (3) phases
  `before-es` and `after-es-before-finalize`, and (4) exact phase-scoped runtime coverage of the
  production discriminator registry. Exhaustive partitioning of every attribute value domain is
  explicitly excluded because it has no mechanical ground truth or constructive termination test.
  Targeted value-boundary regressions remain required when design review identifies a semantic
  condition, but they are not evidence of value-domain totality. For CB-112, a same-class review
  blocker must identify an omission within one of the four included ground truths; another value
  partition is a design-review recommendation under the recorded residual risk, not a closeout
  blocker.

### Runtime-owned integration-test ports

- Do not derive, probe-and-release, or lease a host port in a requester process that is not the
  resource which will bind it. For container ports, omit the published host port and let Docker
  allocate and hold it; read the result only after startup with `docker compose port` or
  `docker port`. For host applications, request port zero and read the bound port from that live
  process. The socket owner and the lifecycle owner must be the same resource.
- Keep the Docker publication configuration stable across restarts: declare the loopback host and
  container target without a `published` value. A stopped, restarting, or cleanup-resistant
  container retains Docker's publication identity; a new project receives another port without a
  repository registry, PID fingerprint, stale-record recovery, or quarantine state.
- Bind port discovery to the specific live owner and startup generation. Reject malformed,
  multi-address, missing, stale-log, or out-of-range discovery output; an application restart must
  ignore the prior generation's log line and read the newly bound port before any client request.
  Because port zero may change the endpoint on every restart, enumerate the live dependency graph:
  reconfigure or restart every surviving client before it can issue another request, then prove the
  first post-recovery request reaches the new owner rather than the prior port.
- Prove the behavior with real concurrent suites and controlled cleanup failure: two projects must
  start concurrently with disjoint runtime-owned ports; after a failed `down`, the residual project
  must keep its port while a new project starts on another port. Repeated normal execution must leave
  no containers, networks, or host-port registry artifacts. A resource-stop failure must always emit
  an unambiguous diagnostic even when the primary test already failed; preserve the primary exit code
  without making the secondary cleanup failure invisible.

### Application-level readiness after dependency recovery

- Treat container health and application readiness as separate facts. After restarting or restoring
  a dependency, gate follow-up assertions on an application endpoint that actually exercises the
  recovered client or connection pool and returns its expected success status; a healthy database,
  search, cache, or broker container does not prove the surviving application has reconnected.
- Keep the controlled outage faithful to the runtime topology. If a dependency restart can receive a
  new runtime-owned host port, either refresh every dependent endpoint first or inject the outage in
  place without changing the published endpoint. A readiness timeout must retain application logs
  and fail the test; it must not reinterpret a correct unavailable response as a product defect.

## Closeout maintenance

At each slice or authorized non-slice closeout, append every newly evidenced recurring defect
class that is not already covered above. Do not weaken or delete an existing check merely because
the current slice is unaffected. When the available evidence contains no new class, record that
explicit conclusion in the pull request rather than adding a placeholder checklist entry.

For a pure internal evaluation/audit/state surface, an owner-approved closeout boundary may be the
Cartesian product of mechanically enumerable ground truths plus an explicit residual-risk record.
That boundary must name every included axis and excluded unbounded dimension. It does not apply to
transaction messages, inventory consistency, identity authorization, idempotent ordering, or other
business-core paths, whose established strict evidence remains blocking.

For any conditionally enabled test class used as acceptance or rejection evidence, inspect the
runner report rather than trusting process exit zero. Record the executed and skipped counts, and
make the owning integration script fail unless every named required class has a report with
`tests > 0`, `skipped = 0`, `failures = 0`, and `errors = 0`. A command whose selected tests were
all skipped is not evidence, even when the build succeeds.
