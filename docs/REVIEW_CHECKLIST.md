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

## Closeout maintenance

At each slice or authorized non-slice closeout, append every newly evidenced recurring defect
class that is not already covered above. Do not weaken or delete an existing check merely because
the current slice is unaffected. When the available evidence contains no new class, record that
explicit conclusion in the pull request rather than adding a placeholder checklist entry.
