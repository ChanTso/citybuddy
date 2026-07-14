# Repository development rules

## Slice workflow

1. Read `IMPLEMENTATION.md` before changing the repository. It is the canonical source for slice names, priority, dependencies, ordering, and status.
2. Find the single route row marked `READY` or `IN_PROGRESS`, then begin with its linked slice specification and the directly listed sections of `docs/CONTRACTS.md`.
3. Prefer this active-slice context over a broad scan. Search or read other slice specifications when needed to resolve an explicit dependency, shared contract, sequencing question, or frozen-contract conflict; treat them as read-only context and do not implement them early. A slice without a linked, complete specification cannot start.
4. Work on only the single active slice: start from `READY`, then change that same route row to `IN_PROGRESS` when its feature branch exists and real implementation begins.
5. Slice work uses one feature branch and one pull request. Slice-work commit messages include the slice identifier. Do not implement later slices, add out-of-scope dependencies, or perform unrelated refactoring.
6. If implementation evidence conflicts with a frozen contract, mark the slice `BLOCKED` and record the concise impact in its Completion record; keep detailed evidence in the pull request and do not silently change the contract.
7. `IMPLEMENTATION.md` is the slice-status source, the linked slice file is the specification and Completion-record source, and the pull request is the detailed test and review evidence source. GitHub Issues are optional.
8. A governance-document change that does not implement a slice uses a dedicated documentation branch and pull request and does not change the active slice status.

## Quality and evidence

1. Do not delete, weaken, skip, or rewrite failing tests to make work pass. Do not create placeholder tests, no-op checks, or commands that hide failures.
2. Do not fabricate tests, performance results, branches, commits, pull requests, reviews, subagent work, or completion evidence.
3. Put enforceable format, lint, type, test, dependency, and CI rules in executable configuration. Run the real `make ci` before slice closeout.
4. Every non-trivial pull request records the commands actually run, their results, exercised rejection paths, and known incomplete or out-of-scope work.
5. Never commit secrets, personal data, private URLs, internal accounts, or private planning material.
6. A failed `gh auth status` inside the sandbox is inconclusive because the sandbox may not expose the host keyring, credential helper, network, or GitHub session. Retry the same non-mutating authentication check outside the sandbox with approval before claiming that credentials are invalid or asking the user to run `gh auth login`; only report an authentication blocker when the outside-sandbox check also fails.

## Implementation and comment style

1. Implement the smallest design that satisfies the active slice and its referenced frozen contracts.
2. Add validation at real trust boundaries, required invariants, and explicit rejection paths. Do not duplicate guards already enforced by types, schemas, constructors, or validation within the same trusted process boundary. Cross-service, network, messaging, persistence, user, model, and tool boundaries are not trusted merely because they are internal.
3. Do not add speculative abstractions, unrequired fallback or retry paths, future feature flags, or broad catch-and-continue handling unless the active slice or one of its referenced frozen contracts explicitly requires them.
4. Let unexpected programmer and configuration errors fail visibly unless the active slice or one of its referenced frozen contracts defines a boundary translation or recovery rule.
5. Comments explain non-obvious reasons, invariants, and external constraints. They do not narrate the code, restate types, or promise future work.

## Agent roles

1. The primary coding session is the orchestrator, primary developer, and sole integrator. It owns production code, build and CI configuration, migrations, implementation decisions, commits, slice status, Completion records, and merge decisions.
2. For a non-trivial slice, use one independent test/review subagent before closeout. It is read-only by default and checks the slice requirements, rejection paths, evidence, tests, and false-green risks.
3. If explicitly authorized, the subagent may sequentially edit only tests, fixtures, and dedicated test helpers. It must not edit production code, build or lockfiles, CI, migrations, frozen contracts, acceptance criteria, slice status, or Completion records; it must not commit or merge.
4. At most one agent may write at a time. Additional specialist reviewers, when useful for high-risk work, remain read-only.

## Slice closeout and merge

1. Close the slice in the same feature branch and pull request. Do not create a second pull request only for status or Completion-record updates.
2. When all acceptance criteria, rejection paths, required evidence, review blockers, and required checks are satisfied:
   - fill the real Completion record;
   - mark the completed slice `VERIFIED`;
   - mark the next eligible slice as the only `READY` slice;
   - roll the detailed specifications forward as required.
3. Commit and push the closeout changes, then rerun the required checks. These state changes become authoritative only after they land on `main`.
4. Unless the user asks to stop before merge, merge the pull request after required checks pass and no blocker remains. Use a merge commit for a clean slice branch; squash only when the history is genuinely noisy or the user requests it.
5. After merge, update local `main`, confirm the working tree is clean and the expected slice states are present, and delete the merged branch when safe. Then stop unless the user has explicitly activated a continuous-slice Goal under the rules below; an active continuous-slice Goal is standing authorization to start the next eligible slice without a new prompt.
6. If review, repository policy, mergeability, or a check failure outside the recoverable same-slice CI and review paths below blocks completion, stop and report the blocker; do not bypass it.

## Continuous-slice Goals

1. Only an explicit user-started Goal may activate continuous-slice execution. A normal request to implement one slice does not activate it. The Goal is the sole execution driver: do not create, enable, or use a scheduled heartbeat or other recurring trigger for this loop unless the user explicitly changes this strategy.
2. Continuous mode remains sequential. Before choosing a transition, inspect updated `main` plus local and remote slice branches and pull requests, then select exactly one of the mutually exclusive states below.
3. Resume setup or implementation only when updated `main` has one `READY` slice and exactly one unresolved feature branch belongs to that same slice, with at most one open pull request that unambiguously matches it. Inspect the branch head and continue on that existing branch only if it either still matches `main` before real implementation began, or changes that slice to the sole `IN_PROGRESS` row, has no `READY` row, and retains a complete linked specification. In the first case, change the same row to `IN_PROGRESS` with the first real implementation change; in the second, resume the remaining normal implementation, independent review, CI, closeout, and merge workflow. Do not create another branch or start another slice.
4. Resume pre-merge closeout only when exactly one unresolved feature branch and pull request belong to the slice that is still `READY` on updated `main`, while the pull-request head validly changes that slice to `VERIFIED`, marks exactly one eligible next slice `READY`, and has no `IN_PROGRESS` row. Continue only the remaining review, checks, closeout fixes, and merge for that pull request; do not start the next slice before the closeout lands on `main`.
5. Start a fresh slice only when updated `main` has no `IN_PROGRESS` row, exactly one `READY` row with a complete linked specification, and no unresolved local or remote slice branch or pull request for that slice or any preceding unfinished slice. Start it through the normal branch, status, implementation, independent review, CI, closeout, and merge workflow.
6. After a merge, update local `main`, confirm a clean working tree and the expected slice states, delete the merged branch when safe, then re-read `AGENTS.md` and `IMPLEMENTATION.md` from updated `main` before selecting another state.
7. Standing authorization covers only repetition or resumption of the existing single-slice workflow. It does not authorize parallel writers, frozen-contract changes, route or scope changes, bypassing review or CI, destructive operations, new secrets, broader permissions, or other materially different actions.
8. During an active continuous-slice Goal, an ordinary required CI failure is recoverable when its evidence unambiguously attributes the failure to the current slice branch and the smallest correction remains within the active slice specification, frozen contracts, existing branch and pull request, and already authorized tools and permissions. Stay on the same slice, report the failure evidence, implement the smallest correction, push it, and rerun the applicable targeted tests, any independent review made necessary by the correction, and all required checks. Do not merge or start another slice while any required check is failing.
9. During an active continuous-slice Goal, an ordinary review blocker is recoverable when the finding is concrete, actionable, unambiguously attributable to the current slice branch or pull request, and the smallest correction remains within the active slice specification, frozen contracts, existing branch and pull request, and already authorized tools and permissions. The primary session must validate the finding rather than accept it blindly, report the evidence, and apply the smallest correction; pull-request description and evidence corrections are included. Rerun applicable targeted tests and obtain a fresh independent read-only review of the complete revised diff. An explicit `NO BLOCKER` result is required before closeout, merge, or starting another slice.
10. One recovery cycle is one evidence-based correction followed by the required reruns or re-review. Perform at most two consecutive recovery cycles for the same CI failure class or review-finding class; a full pass of all required checks resets the CI limit, and an independent `NO BLOCKER` review resets the review limit. Do not evade either limit through cosmetic changes, retries without a correction, test weakening, skipped checks or review, relabeling the same root cause, or a new branch or pull request.
11. Stop continuous execution and report the reason when repository state matches zero or multiple states above; a slice becomes `BLOCKED`; evidence conflicts with a frozen contract; `main`, branch, or pull-request state is ambiguous or contradictory; a CI failure is unrelated to the current slice, has ambiguous evidence, or persists after two recovery cycles; a review finding is unrelated, ambiguous, cannot be validated, conflicts with another required source, or persists after two recovery cycles; a correction requires changed contracts, route, scope, dependencies, secrets, permissions, or user authority; a finding raises unresolved security-boundary, data-loss, or destructive-operation risk; merge, credentials, repository policy, CI infrastructure, or mergeability blocks progress; or continuation would bypass a required check or independent review.

## Repository boundaries

1. Root governance Markdown is limited to `README.md`, `AGENTS.md`, `CLAUDE.md`, and `IMPLEMENTATION.md`. Cross-slice contracts live only in `docs/CONTRACTS.md`; detailed slice specifications live only in `docs/slices/CB-*.md`.
2. Do not create a parallel status source, roadmap, test plan, review log, ticket document, duplicate contract file, or unlinked slice specification.
3. Pure spelling, documentation-only formatting, or comment-only corrections may be committed directly only when they do not change behavior, a frozen contract, or slice state.
