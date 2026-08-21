# Repository development rules

These rules replace the slice/route/recovery process used through 2026-08. That process is
archived under `docs/archive/`; it is history, not a ruleset. Do not reintroduce it.

## Working agreement

1. One branch and one pull request at a time. Do not open a second lane before the first merges.
2. Implement the smallest design that satisfies the request. No speculative abstractions,
   unrequired fallbacks, or future feature flags.
3. Run `make ci` before requesting review. The pull request records the commands actually run
   and their real results.
4. Never delete, weaken, or skip existing tests to make work pass. Never fabricate tests,
   results, commits, reviews, or evidence.
5. Never commit secrets, credentials, personal data, or private planning material.
6. Comments explain non-obvious reasons, invariants, and external constraints. They do not
   narrate the code or promise future work.

## Evidence

1. Measurement uses standard tools — k6 for HTTP paths, memtier_benchmark for Redis paths.
   Raw tool output plus the workload and environment description is the evidence.
2. Do not build verification machinery around the evidence: no reconstruction checkers, no
   manifest closures, no proof-of-proof mutation frameworks, no checker-of-checker. A small
   deterministic calculator for sample count, throughput, and percentiles is the maximum.
3. Business correctness is proven by SQL against the authoritative database plus its raw
   output. Do not reimplement the business model inside a checker.
4. Report achieved numbers with their exact boundary — workload, hardware, and what is
   excluded. Do not present a local topology result as a capacity or production claim.

## Review

1. Use one independent read-only reviewer before merging non-trivial work.
2. A reviewer finding blocks the merge only when it names an executable counterexample against
   product behavior, a secret or cleanup risk, or a business-truth conflict. Style preferences,
   additional permutations, and future-framework suggestions do not block.
3. A finding about verification machinery is a reason to delete that machinery, not a reason to
   extend it.
4. When review and implementation disagree twice on the same point, the owner decides. Do not
   spend a third cycle.
