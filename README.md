# CityBuddy

CityBuddy is a local-commerce transaction and AI customer-support project designed around explicit identity, side-effect, retrieval, and evaluation boundaries.

## Current status

The repository contains the verified executable, non-business baseline skeletons for:

- `auth-service` and `commerce-service` in one Java 21 Maven reactor;
- `agent-service` and `knowledge-indexer` in one Python 3.11 `uv` workspace;
- `web` as a React/TypeScript/Vite shell managed by npm.

The repository tooling includes committed Maven Wrapper, `uv.lock`, and `package-lock.json` entry points; Java, Python, and web formatting, linting, typing, unit tests, and builds; pre-commit text hygiene and staged secret detection; repository Gitleaks scanning; and GitHub Actions through the root `make ci` command.

The current executable slice and its status are maintained only in [IMPLEMENTATION.md](IMPLEMENTATION.md).

## Repository checks

The checked baseline requires a JDK capable of compiling Java release 21, Python 3.11, `uv` 0.11.24, Node.js 24 with npm, GNU Make, `curl`, and `tar`.

Install the locked dependencies and repository tools:

```shell
make setup
```

Run all formatting checks, linters, type/compile checks, unit tests, builds, pre-commit hooks, and Gitleaks:

```shell
make ci
```

Apply the configured source formatters:

```shell
make format
```

## Current limitations

No business feature, service runtime topology, API contract, database schema, Docker Compose environment, RocketMQ topic, authentication flow, model-provider integration, performance result, deployment, or operational readiness claim is implemented by this baseline. The skeletons and their construction tests do not make the services production-runnable.

Implementation starts from the single active row in [IMPLEMENTATION.md](IMPLEMENTATION.md), follows its linked slice specification, and begins with the frozen-contract sections referenced by that slice. Other slice specifications may be consulted when a dependency or contract question requires them, but they must not be implemented early.

Runtime startup and integration-test instructions will be added only by the slices that implement and successfully execute them.
