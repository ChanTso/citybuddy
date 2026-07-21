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

### Total parsing exception boundary

- Treat the complete untrusted parsing operation as one failure class: character encoding,
  decoding, framing, JSON/type conversion, numeric conversion, and protocol-specific validation.
- Confirm every parse failure converges to the fixed public error without an internal exception,
  traceback, partial acceptance, permissive fallback, or secret-bearing log.
- Exercise a class-based malformed-input battery, including raw and decoded non-ASCII, invalid
  encoding, missing structure, wrong primitive types, bounds, control bytes, and nulls where the
  protocol permits them to reach the parser.

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
  no containers, networks, or host-port registry artifacts.

## Closeout maintenance

At each slice or authorized non-slice closeout, append every newly evidenced recurring defect
class that is not already covered above. Do not weaken or delete an existing check merely because
the current slice is unaffected. When the available evidence contains no new class, record that
explicit conclusion in the pull request rather than adding a placeholder checklist entry.
