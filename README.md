# CityBuddy

CityBuddy is a local-commerce transaction and AI customer-support project designed around explicit identity, side-effect, retrieval, and evaluation boundaries.

## Current status

This repository is at its pre-implementation documentation baseline. It does not yet contain business code, runnable services, a Docker Compose environment, database migrations, build configuration, continuous integration, or completed tests.

The repository currently contains only:

- `README.md` — current public repository status;
- `AGENTS.md` — the sole repository development ruleset;
- `CLAUDE.md` — a pointer to `AGENTS.md`;
- `IMPLEMENTATION.md` — frozen contracts, preflight findings, the delivery route, and the rolling slice specification.

## Current limitations

No feature, service topology, command, endpoint implementation, performance result, or operational claim in the target design should be treated as implemented. No startup or test command is available yet, and no test or CI result has been produced.

Implementation must follow the single active slice in [IMPLEMENTATION.md](IMPLEMENTATION.md). Work starts only when that slice is `READY`; once a real feature branch and the first implementation change begin, work continues only on the same slice in `IN_PROGRESS` until it reaches a terminal state. Later slices must not be started early.

Startup and test instructions will be added here only after the corresponding commands exist and have been run successfully against the repository.
