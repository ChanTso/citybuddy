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
6. If checks, review, repository policy, or mergeability block completion, stop and report the blocker; do not bypass it.

## Continuous-slice Goals

1. Only an explicit user-started Goal may activate continuous-slice execution. A normal request to implement one slice does not activate it. The Goal is the sole execution driver: do not create, enable, or use a scheduled heartbeat or other recurring trigger for this loop unless the user explicitly changes this strategy.
2. Continuous mode remains sequential: finish and merge one slice, update local `main`, confirm a clean working tree, delete the merged branch when safe, then re-read `AGENTS.md` and `IMPLEMENTATION.md` from updated `main` before considering another slice.
3. Resume an interrupted slice only when there is exactly one `IN_PROGRESS` row and no `READY` row, its linked specification is complete, its feature branch is uniquely identifiable, and any existing pull request unambiguously matches that branch and slice. Resume only that slice through the remaining normal implementation, independent review, CI, closeout, and merge workflow; do not start another slice.
4. Start the next slice only when there is no `IN_PROGRESS` row, exactly one `READY` row, no unresolved branch or pull request from the preceding or another active slice, and the linked specification is complete. Start that slice through the normal branch, status, implementation, independent review, CI, closeout, and merge workflow.
5. Standing authorization covers only repetition or resumption of the existing single-slice workflow. It does not authorize parallel writers, frozen-contract changes, route or scope changes, bypassing review or CI, destructive operations, new secrets, broader permissions, or other materially different actions.
6. Stop continuous execution and report the reason when repository state matches neither the resume state nor the next-slice state above; a slice becomes `BLOCKED`; evidence conflicts with a frozen contract; `main`, branch, or pull-request state is ambiguous or contradictory; a required check, review, merge, credential, or permission fails; or continuation requires new user authority.

## Repository boundaries

1. Root governance Markdown is limited to `README.md`, `AGENTS.md`, `CLAUDE.md`, and `IMPLEMENTATION.md`. Cross-slice contracts live only in `docs/CONTRACTS.md`; detailed slice specifications live only in `docs/slices/CB-*.md`.
2. Do not create a parallel status source, roadmap, test plan, review log, ticket document, duplicate contract file, or unlinked slice specification.
3. Pure spelling, documentation-only formatting, or comment-only corrections may be committed directly only when they do not change behavior, a frozen contract, or slice state.
