# Repository development rules

## Active slice discipline

1. Read `IMPLEMENTATION.md` before changing the repository. At most one active slice may exist.
2. Before work starts, the active slice is the single slice marked `READY`. When a real feature branch is created and the first implementation change begins, change that same slice to `IN_PROGRESS`.
3. After work starts, continue only the single `IN_PROGRESS` slice until it becomes `VERIFIED`, `BLOCKED`, or `DEFERRED`. A `READY` slice and a different `IN_PROGRESS` slice must never coexist. Only after the active slice ends may the next slice become `READY`.
4. Implement one slice at a time. Do not pre-implement later slices, add out-of-scope dependencies, or perform unrelated refactoring.
5. Do not silently change a frozen contract. When implementation evidence reveals a hard conflict, stop the affected work, mark the active slice `BLOCKED`, and record the evidence and impact in `IMPLEMENTATION.md`.

## Git, pull request, and issue discipline

1. One slice uses one feature branch and one pull request. Every slice-work commit message must include the slice identifier.
2. Pure spelling, documentation-only formatting, or comment-only corrections may be committed directly only when they do not change behavior, a frozen contract, or the active-slice discipline.
3. Preserve small, truthful, readable commits on a clean branch. Squash only when the history is genuinely confusing, misleading, or not reviewable.
4. Every non-trivial pull request records the exact test and check commands actually run, their exact results, the rejection paths exercised, and every known incomplete or out-of-scope item.
5. GitHub Issues are optional. They are not a prerequisite for starting a slice, the default workflow, or a second source of status truth. `IMPLEMENTATION.md` is the slice-status source; the pull request is the test and check evidence source.
6. Do not fabricate tests, performance results, branches, commits, pull requests, reviews, subagent activity, or completion evidence.

## Testing and completion truth

1. Do not delete, weaken, skip, or rewrite a failing test merely to make a change pass.
2. Do not create empty checks, placeholder tests, no-op tasks, or commands that succeed without checking anything meaningful.
3. Before a pull request is ready for review, run the repository's real `make ci` entry point. `CB-000` is responsible for making that entry point exist and perform meaningful checks; until then, do not claim it is available.
4. Mark a slice `VERIFIED` only after every acceptance criterion, rejection path, and item of required evidence has been satisfied. Then fill `Branch / PR / Commits / Tests / Notes` with real values. Never prefill those fields.
5. Put automatically enforceable formatting, linting, typing, testing, dependency, and CI rules in executable configuration rather than duplicating their detailed settings here.

## Agent roles and write ownership

### Primary coding session

Codex is the default primary coding session. The primary session is the orchestrator, primary developer, and sole integrator. It exclusively maintains the complete active-slice context; creates and manages the feature branch; makes implementation decisions; modifies production code, dependencies, build and tool configuration, CI, and migrations; writes tightly coupled unit and contract tests; reviews and integrates any subagent test proposal; fixes failures; creates real commits; runs final `make ci`; fills the real Completion record; determines whether `VERIFIED` is justified; and changes slice status in `IMPLEMENTATION.md`.

A subagent must not independently commit, merge, mark a slice `VERIFIED`, change slice status, or modify a Completion record.

### Default test/review subagent

Use one independent test/review subagent by default for a non-trivial slice.

1. **Pre-implementation read-only phase:** it reads the active slice's Acceptance criteria, Rejection paths, and Required evidence, then returns a test matrix and false-green risks to the primary session. It does not modify files or create a long-lived test-plan document.
2. **Independent review after a stable draft:** it reviews the diff and existing tests for empty tests, skipped modules, swallowed exit codes, incorrect mocks, local/CI command mismatch, happy-path-only coverage, and similar false-green risks. Findings include exact file locations, commands, failure output, and reproduction steps.
3. **Optional test-only write phase:** this occurs only with explicit primary-session authorization and sequential write ownership. The primary session pauses file modification. The subagent may change only test files, test fixtures, and dedicated test helpers. It must not change production code, build or lockfiles, CI, Makefile, migrations, frozen contracts, Acceptance criteria, Required evidence, slice status, or Completion records. It must not weaken assertions, expand timeouts, add skips, or swallow errors to fit the implementation. It must not commit. The primary session reviews, runs, and decides whether to integrate every change.

If the tooling cannot guarantee sequential writes and explicit file boundaries, the test/review subagent remains read-only.

### Testing responsibility split

The primary developer writes unit tests and necessary contract tests that are tightly coupled to implementation interfaces. The independent test/review subagent focuses on black-box behavior, rejection paths, authorization and cross-user failures, idempotency, retries and duplicate delivery, fault injection, mutation-style verification, CI false positives, and local/CI command parity. Independent testing means independent design and final scrutiny; it does not require every test file to be written by another agent.

### Concurrency and workspace limits

At most one agent may be write-capable at any time. The primary session and a subagent must not modify the same workspace or coupled files concurrently. Do not default to a production-code subagent, a test-writing subagent, and a merge-only primary session. The one-slice, one-branch, one-PR workflow does not create extra worktrees by default. A Git worktree is allowed only for a genuinely independent, clearly bounded, explicitly approved task where it reduces rather than creates integration risk.

### High-risk specialist reviewers

A temporary read-only specialist reviewer may be added for `CB-020` identity and authorization, `CB-060` RocketMQ transaction and failure semantics, `CB-120` concurrency/idempotency/action truth, or `CB-100` sandbox isolation and fail-closed behavior. The reviewer reads a stable snapshot, changes no files or acceptance criteria, performs no concurrent write, and returns findings to the primary session for disposition.

### Evidence location

Do not create `TEST_PLAN.md`, `REVIEW.md`, subagent logs, or a fifth long-lived Markdown file. Test matrices, independent-review findings, exact commands, and results belong in the pull request. `IMPLEMENTATION.md` keeps only slice status and the final Completion record. The primary session is the sole integrator of final code and evidence.

## Repository boundaries

1. Never commit secrets, personal data, private URLs, internal accounts, or private planning material. Do not copy non-public source material into this repository.
2. Keep the long-lived Markdown set to `README.md`, `AGENTS.md`, `CLAUDE.md`, and `IMPLEMENTATION.md`. Do not add a fifth long-lived Markdown document or a parallel status system.
3. Do not introduce dependencies outside the active slice or perform a convenient refactor that is not required by its acceptance criteria.
