# Periodic ledger-query diagnostic — registered 2026-09-06

Application and benchmark source remain `16cb21154d1ae94c65fff56b8a96ea7f4514f924`.
Original sampler label: `ordersteady_16cb211_20260906T072515Z_r160_s300`.
That point completed all 48,000 admissions/orders correctly but accumulated work after
about 35,000 arrivals. Do not classify it as a proven application-only capacity limit.

Post-point MySQL uses a 134,217,728-byte InnoDB buffer pool. Its cumulative statement
summary identified our periodic `inventory_ledger GROUP BY movement_type` plus SUM as
the largest SELECT total: 268 executions, 5,311,728 examined rows, 83.1184s total and
1.5913s maximum. These counters include earlier points and are diagnostic clues, not
an attribution of all cost to the 160/s window. The real timeout-dispatch query uses
the intended pending-state index and examined only 168,217 rows in 38,492 cumulative
executions; no product index change is justified by that observation.

Repeat exactly 160/s for 300 seconds, one activity/product, 48,050 users/stock/quota,
same code/JARs, VM8/14GB, Commerce4, batches, delays, generator, output format and
five-second sampler. The sole query change is removing the periodic ledger aggregate;
all other progress queries, including order/admission counts, oldest pending and
pending timeout dispatch, remain. Full ledger SQL stays before input and after clearance.
Do not add an approximate ledger model or change database buffer-pool settings.

Read ordinary InnoDB status and normalized statement counters once before input and
once after input/clearance, so this new point has actual counter deltas. Do not reset
counters, run EXPLAIN or insert additional heavy queries during input. After the
standard progress checks confirm clearance, execute the unchanged complete final SQL.
Retain all 300 seconds, startup, late behavior and drain data; keep the earlier point.

Compare behavior after crossing 35,000 arrivals, Q/T/oldest-pending trends, physical
read/wait counter increments and whole-window completion. If removing this query
changes late behavior, attribute it to the measurement configuration, not new service
code. Random primary lookups or buffer eviction remain possible mechanisms until
counter evidence supports them. If it does not change behavior, do not silently
remove more queries in the same point; diagnose the remaining cost separately.

Use a fresh request-label namespace; retained completed intents expire normally.
Existing correctness, token-expiry and 240-second estimated-drain stop rules apply.
This diagnostic replaces the immediate 120/200 progression because a measured probe
cost can change the interpretation of that progression. No cloud work is triggered.

## Follow-up registered after the sampler diagnostic

Diagnostic label `orderprobe_16cb211_20260906T074822Z_r160_nolgr` completed 48,001
unique orders with correct final SQL and no late growing backlog. The same application
commit `16cb21154d1ae94c65fff56b8a96ea7f4514f924` will now receive the already planned
final 200/s point for 300s, a new fixture with 60,050 users/stock/quota, and the same
sampler without its periodic ledger aggregate. Full pre/post ledger SQL and database
counters remain. This point extends the lower-impact measurement series; it is not
a same-sampler comparison with the first 160/s point. Retain startup and the full
window; existing stop and drain rules remain. No further upward doubling is planned.
