# Engineering notes

Problems this project actually hit, what caused them, and what each one changed. The evidence class
varies by entry: retained runtime output from real infrastructure, integration regressions, or
code/schema review followed by a regression. Narrative reconstruction is labelled when the raw
artifact did not retain the claimed detail. The unabridged per-slice record, with a pull-request or
commit link on every entry, is in
[docs/archive/SLICE_LESSONS.md](archive/SLICE_LESSONS.md).

They are grouped by what they taught, not by when they happened.

## Locking

### The row lock limiting throughput was also hiding a deadlock

The single-activity seckill ladder contained zero HTTP 500 responses but one k6
`http_req_failed` out of 21,268 requests: an HTTP 409 `DUPLICATE_USER` decision. Spreading the same
workload shape across 32 activity rows produced 6 HTTP 500s at 100 req/s, 3 at 200, 1,803 at 400
and 4,390 at 800: 6,202 failures out of 23,254 whole-ladder requests. The log artifact contains
12,404 matching `Deadlock found when trying to get lock` text lines, numerically 2 × 6,202, but
no per-request correlation is retained and its printed minute subtotal does not reconcile. It is
evidence of the repeated diagnostic and SQL site, not a second exact deadlock-event count. The
failing statement was `INSERT INTO seckill_reservation`.

The retained InnoDB-status file contains only its deadlock section header; it does not preserve a
lock graph. The mechanism is reconstructed from the pre-fix code and InnoDB semantics:
`reserveIntent` performed `SELECT … FOR UPDATE` on an idempotency key that did not exist, which can
take a gap lock, before the competing `INSERT` needed an insert-intention lock in that gap. Compatible
gap locks followed by conflicting insert intentions explain the cycle and its disappearance after
insert-first, but the specific `supremum` record and per-transaction lock holdings are not raw
retained evidence.

It never appeared with one activity because the transaction's opening `SELECT … FOR UPDATE` on
`seckill_activity` had already serialized same-activity callers completely — only one transaction
could ever reach the insert. The lock that capped throughput was also concealing the bug, and
adding a concurrency dimension removed the cover.

The fix inverts the order: insert first, and let the unique key decide. On `DuplicateKeyException`,
read the existing row back with `FOR SHARE` and compare the intent hash. Both choices matter, and
each is a separate lesson below. The spread whole-ladder HTTP failure rate went from 6,202/23,254
(26.67%) to 0/23,256; its retained post-fix commerce-log check found zero matching deadlock
diagnostic, and the top step served its full 800 req/s schedule clean. No corresponding post-fix
commerce-log count was retained for the contended run.

**Lock-read-then-insert on a key that may not exist is inherently self-conflicting.** Idempotent
writes should be adjudicated by the unique constraint. Read-then-insert is only safe when something
outer serializes it completely, and that safety is accidental — it disappears as soon as the
workload gains a dimension.

### A shared read before an exclusive write is a deadlock waiting for a second caller

Two identical payment callbacks arriving together deadlocked on MySQL error 1213, reliably, in the
real integration suite. Both transactions took a compatible shared or gap lock on the callback
unique key first, then both asked to upgrade to the exclusive lock the write needed. InnoDB can
only pick a victim.

The fix is to serialize at the entrance instead: the callback transaction's first statement is
`SELECT … FOR UPDATE` on the payment attempt by callback correlation, so callbacks for the same
attempt queue before anything derived is read. `FOR UPDATE` does not help here because it "does not
deadlock" — it helps because it removes the S→X upgrade ring.

A bounded retry survives, because a real transaction can still form a rare lock cycle with an
unrelated resource, and InnoDB's victim rollback is a recoverable outcome. The classification of
what may be retried went through two rounds. The first fix retried on 1213 alone, because
`DuplicateKeyException` had been folded into the same retry and two different meanings were hiding
behind one. A later maintenance pass replaced "retry or fail" with something better: 1205
lock-wait-timeout and 1213 are both treated as recoverable contention, and every exhausted or
duplicate-key case re-resolves the committed truth in a fresh transaction, converging on the
existing result or a stable 409 rather than on a retry.

**Serialize concurrent writers on the aggregate root before reading anything derived from it, and
say exactly what each error class means.** Evidence: five runs of 20 concurrent duplicate-callback
rounds, 100 rounds total; a controlled 1213 injection proved one retry and a single ledger
movement.

### A lock wait does not refresh your snapshot

Two concurrent partial refunds queued correctly on the same payment attempt — and both were
accepted. The second transaction computed the already-reserved amount from the REPEATABLE READ
snapshot it had established *before* it started waiting for the lock. Waiting for a lock serializes
access; it does not move your read view.

The same window appeared again in reconciliation, where a plain read after the lock reported a
freshly committed, entirely legitimate ledger movement as a contradiction.

The immediate fix summed the reserved amount from a `SELECT … FOR UPDATE` current read of that
attempt's refund rows, and moved the reconciliation reads to `FOR SHARE`. `FOR UPDATE` and
`FOR SHARE` do not only mean "take a lock" — in InnoDB they also mean "read the committed version
as of now". The reconciliation reads are still `FOR SHARE`; the reserved total later moved into the
database as a single aggregate, for the reason in *Bounds apply before materialization* below.

**After any lock wait, every derived aggregate or related fact has to be audited individually.**
Fixing the first query is not fixing the pattern.

### Read back a duplicate with `FOR SHARE`, not `FOR UPDATE`

The insert-first fix above needs a current read, because the REPEATABLE READ snapshot predates the
concurrent insert that caused the duplicate — a plain read simply would not see the row. But it has
to be `FOR SHARE`: on a duplicate-key `INSERT`, InnoDB already holds a shared lock on the record
that was duplicated, so asking for an exclusive one re-creates exactly the S→X ring from the
payment-callback deadlock.

Two independent constraints, in opposite directions, on one statement.

## Constraints and truth

### A `CHECK` that evaluates to UNKNOWN does not reject the row

A terminal row with a null `decision_code` passed a composite `CHECK` that was meant to forbid it.
SQL is three-valued: a constraint rejects on `FALSE`, not on `UNKNOWN`, and a comparison against
`NULL` yields `UNKNOWN`. The constraint had to say `decision_code IS NOT NULL` explicitly.

**Write database constraints in three-valued logic, with the non-null premises spelled out.**

### Unique constraints must express the real business cardinality

A refund was rejected by the `(order_id, movement_type)` unique key that the payment path had
established — correct there, because an order has at most one payment movement, and wrong for
refunds, because one payment can be partially refunded several times.

The key became a generated conditional singleton: still one-per-order for the ordering,
cancellation and payment movements, and one-per-refund-identity for refunds, keyed on a stable
`mock-refund:{refundId}` business event.

**A uniqueness constraint is a statement about how many of a thing may exist.** Reusing one across
a domain where the answer differs is a schema-level type error.

### Bounds apply before materialization, and aggregates are durable truth too

Refund capacity was computed by loading every active refund amount into a `List<Long>` and summing
in the application. Two individually legal refunds whose sum exceeded the paid amount were then
reported as an ordinary eligibility conflict, and a sum that overflowed `long` escaped as a 500.

Now the database returns one exact `DECIMAL SUM`, `longValueExact()` promotes an out-of-domain
total to a dedicated integrity failure, and negative/over-paid/overflow totals are all attributed
to `REFUND_DURABLE_TRUTH_INCONSISTENT` before any eligibility decision. Regression built 600+600,
`Long.MAX_VALUE + 1`, and 1,025 separate active refunds.

**"The result set is always small" is not a bound.** The bound belongs in the query. And an
aggregate is durable truth: verify its integrity before deciding business questions with it.

### Build cross-store projections from the persisted representation

Java `Instant` carries nanoseconds; MySQL `TIMESTAMP(6)` keeps microseconds. Projecting into Redis
from the pre-write input meant the projection and the authoritative row were no longer byte-equal,
and an idempotent rebuild of an existing key read the same fact as a conflict.

**Re-read the committed row and project from that**, and treat every precision contraction —
temporal or numeric — as an explicit step.

## Distributed behaviour

### Atomic is not correct

A Redis Lua script is indivisible. That says nothing about whether the decision it writes is right.
Review found four admission and rebuild invariant gaps plus an oversized-TTL path that could leave
a partial write behind — all of them atomically applied.

**Validate every boundary that can influence the write set before the first mutation.**

### A broker's diagnostics are not your protocol

The broker was configured with `transactionCheckMax=3`; a real repeated-`UNKNOWN` drill observed
two checker callbacks. Trying to read the broker's own termination topic to settle it returned
`40002 cannot access system topic` — the proxy forbids it by design.

So the application stopped trying to derive its terminal state from the broker at all. It persists
its own one-time `transaction_resolution_due_at`, derived from the broker's check window with
margin and never recomputed on restart; a bounded indexed worker sweeps due rows; a Redis Lua CAS
lets the first legal admission-or-timeout decision win and write a durable marker; and the checker
becomes a pure read of that marker, answering `UNKNOWN` when it cannot read it. The broker's own
terminal state is evidenced separately with `mqadmin`.

**A gap between configured and observed is a diagnostic, not an interface.** Own your termination
deadline in your own durable state.

### Separate "we accepted this" from "we finished this"

A support turn calls a model, an identity service and a tool over the network. Holding one database
transaction across all of that gives neither atomicity nor a small lock window, and a single
transaction cannot express both "the request was accepted" and "the evidence of executing it is
all-or-nothing".

The turn commits `PROCESSING` with the user input and a one-time deadline derived from the attempt
budget, then commits the full evidence and terminal state in a second transaction. A controlled
failure in the second phase rolls back every partial record and converges the same accepted turn to
`FAILED` in its own bounded transaction. The deadline is the fence: without it, a process that died
after phase one made the same idempotent request return 409 forever.

**Durable workflows that cross the network need an explicit acceptance boundary and a persisted,
fenceable ownership deadline** — one that survives the process that created it.

### Claim an action before an irreversible remote call, not after

Confirming a refund calls commerce, then records the outcome locally. If the response is lost after
commerce commits, the refund exists remotely and the local record says it never happened.

Commerce's idempotency guarantees at most one refund. It does not guarantee that local truth agrees
one occurred — a distinction that is easy to conflate, and one I argued my way past once before a
review produced the counterexample with no concurrency in it at all.

The reference is now moved `PENDING → CONFIRMING` in its own transaction before the call. Recovery
re-enters commerce with a fresh key and replays the committed receipt instead of issuing a second
refund. Proven end to end: three confirmations across two actions produced two receipts, two
refunds, two consumed actions. Evidence: [#94](https://github.com/ChanTso/citybuddy/pull/94).

## Measurement

### Most of the agent's CPU was building TLS trust stores for `http://` URLs

The first three-path measurement found the agent comfortably *not* CPU-bound at the rates it
served cleanly, and burning five to six cores one step past the knee. Profiling it anyway, on the
retrieval path at concurrency 8, put **94.4 %** of on-CPU samples in `ssl.create_default_context`
as the leaf frame — 13.1 ms of CPU per construction, measured in the same container — because every
outbound call went through a module-level `httpx` helper that builds a whole client, and
constructing a client loads and parses the system CA bundle whether or not the URL is `https://`.
Every one of those URLs was `http://`.

I predicted that removing it would make each turn cheaper without moving the ceiling, precisely
because the agent was not CPU-bound where it served cleanly. That was wrong. One process-wide
client moved the plain-chat knee from 50 req/s at p99 50.1 ms to 75 req/s at p99 31.3 ms, and
knowledge retrieval from 10 req/s to 60, and the failure mode past the knee became graceful
shedding instead of seconds of p99. The overloaded steps show an Agent-container plateau around
1.4 logical CPUs while the sampled dependencies stay below one; the dedicated benchmark
Elasticsearch process was not sampled, so neither “Agent-local ceiling” nor universal dependency
headroom is established.

**Not being CPU-bound at the serving rate does not mean the wasted CPU is irrelevant to where
serving stops.** Profile before optimising, be suspicious of per-request client construction, and
state a prediction so the measurement can contradict it. Evidence:
[bench/agent/README.md](../bench/agent/README.md), [#92](https://github.com/ChanTso/citybuddy/pull/92).

### Container CPU can falsify a boundary attribution without naming a Java method

The refund-preparation ladder was described as hitting a commerce tool boundary at 20 requests/s.
That path alone exchanges a service credential for an OBO on every turn. At 5, 10, 15, 20, and 30
requests/s, the auth container's median CPU rose almost linearly through 117%, 234%, 358%, 527%, and
694%, while commerce stayed between 2% and 6%. The original attribution had not sampled Java stacks
and was wrong at the service boundary.

The credential check used BCrypt cost 12 on every successful exchange. A process-local proof cache
made the benchmark fast, but independent review rejected it: a process memory disclosure could
reveal both the HMAC key and a reusable proof for a low-entropy service secret. The measured
prototype remains in the evidence directory rather than being relabelled as the final result.

The replacement makes the credential class explicit. Human passwords and legacy service rows keep
per-request BCrypt at the cost encoded in each hash; the measured baseline service row used cost 12.
Newly provisioned service identities receive generated 256-bit machine tokens and store
client-bound, versioned SHA-256 digests. The deployment counterfactual therefore includes an
explicit service-credential rotation. At 30/s it changed 803 served plus 98 dropped at p50 4.14
seconds to 901/901 served, zero dropped, and p50 13.4 ms. Auth median CPU fell from 694.42% to 4.30%.
The final 5→30/s ladder had no HTTP error, SQL-classified failed turn, or observed new saturation,
but it neither measures unrotated legacy rows nor establishes capacity above 30/s.

**A whole-container CPU series identifies the dominant sampled CPU consumer, not saturation or the
method that consumed the cycles.** Auth had no per-container CPU quota in this fixture; its readings
approached the shared eight-CPU Docker boundary but do not prove an Auth-local saturation point.
Here the method attribution comes from the controlled code-and-credential counterfactual: preserve
the workload, remove repeated successful BCrypt from the machine-credential path, and watch both
the CPU slope and the throughput collapse disappear. That is weaker than a Java stack profile but
stronger than the old prose. Evidence, the rejected prototype, and their exact limits are in
[bench/agent/README.md](../bench/agent/README.md#repeated-obo-service-credential-verification).

### Measure before attributing slowness; the conspicuous wait is rarely the cost

One CI job took 46.0 minutes against 7.9 for the next slowest, and it defined the critical path.
The obvious suspects were 25 service restarts, a `sleep 60`, and a 1-minute TTL wait.

Differencing the job log by timestamp showed all gaps of 5 seconds or more summed to 2.9 minutes,
the largest of them being that TTL wait, and each Spring Boot restart costing 5–10 seconds. The
real time was spread evenly across 23,372 log lines: a 3,080-cell corruption matrix, each cell one
mutation, one probe and one full truth restoration — 39.9 minutes, 87 % of the job. The most
visible thing and the expensive thing were not the same thing.

Every cell already ended by restoring truth, so cells had no ordering dependency and could be
sharded by global index modulo. Each shard still asserts the complete 3,080-cell census, so a
dropped cell fails a total rather than quietly running one fewer.

**Uniformly spread cost has no spike to find; only cumulative arithmetic finds it.** And a
parallelisation's correctness should rest on an assertion every shard executes, not on confidence
that the split was right.

## Operations

### JWKS publishes all keys or none

Standing the local stack up produced a JWKS endpoint returning 500 for every request. auth-service
publishes every signing-key row that is `CURRENT`, plus any `OVERLAP` row not yet past its
retirement time, and throws if *any* published `kid` has no configured runtime key — so one
leftover row from a different local fixture takes down the whole document, not just its own
entry.

Two consequences, both real: the failure looks nothing like its cause, and any fixture that seeds a
signing key owns the whole table for as long as it runs. Both the demonstration and the benchmark
rig now clear the table and reseed, and the demonstration hands its row back when it stops.

**An all-or-nothing publication boundary turns any single bad member into a total outage,** and
fixtures that share a singleton table have to be explicit about owning it.
