# Repository development rules

## Slice workflow

1. Read `IMPLEMENTATION.md` before changing the repository. Work on only the single active slice: start from `READY`, then change that same slice to `IN_PROGRESS` when real implementation begins.
2. Use one feature branch and one pull request per slice. Slice-work commit messages include the slice identifier. Do not implement later slices, add out-of-scope dependencies, or perform unrelated refactoring.
3. If implementation evidence conflicts with a frozen contract, mark the slice `BLOCKED` and record the evidence and impact in `IMPLEMENTATION.md`; do not silently change the contract.
4. `IMPLEMENTATION.md` is the slice-status and completion source. The pull request is the test and review evidence source. GitHub Issues are optional.

## Quality and evidence

1. Do not delete, weaken, skip, or rewrite failing tests to make work pass. Do not create placeholder tests, no-op checks, or commands that hide failures.
2. Do not fabricate tests, performance results, branches, commits, pull requests, reviews, subagent work, or completion evidence.
3. Put enforceable format, lint, type, test, dependency, and CI rules in executable configuration. Run the real `make ci` before slice closeout.
4. Every non-trivial pull request records the commands actually run, their results, exercised rejection paths, and known incomplete or out-of-scope work.
5. Never commit secrets, personal data, private URLs, internal accounts, or private planning material.

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
5. After merge, update local `main`, confirm the working tree is clean and the expected slice states are present, delete the merged branch when safe, and stop. Do not start the next slice unless the user explicitly asks.
6. If checks, review, repository policy, or mergeability block completion, stop and report the blocker; do not bypass it.

## Repository boundaries

1. Keep the long-lived Markdown set to `README.md`, `AGENTS.md`, `CLAUDE.md`, and `IMPLEMENTATION.md`. Do not add parallel status, test-plan, review-log, or ticket documents.
2. Pure spelling, documentation-only formatting, or comment-only corrections may be committed directly only when they do not change behavior, a frozen contract, or slice state.
