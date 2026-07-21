# CityBuddy

CityBuddy is a local-commerce transaction and AI customer-support project designed around explicit identity, side-effect, retrieval, and evaluation boundaries.

## Current status

The repository contains the verified executable, non-business baseline skeletons for:

- `auth-service` and `commerce-service` in one Java 21 Maven reactor;
- `agent-service` and `knowledge-indexer` in one Python 3.11 `uv` workspace;
- `web` as a React/TypeScript/Vite shell managed by npm.

The repository tooling includes committed Maven Wrapper, `uv.lock`, and `package-lock.json` entry points; Java, Python, and web formatting, linting, typing, unit tests, and builds; pre-commit text hygiene and staged secret detection; repository Gitleaks scanning; and GitHub Actions covering every target in the root `make ci` command.

The current executable slice and its status are maintained only in [IMPLEMENTATION.md](IMPLEMENTATION.md).

## Repository checks

The checked baseline requires a JDK capable of compiling Java release 21, Python 3.11, `uv` 0.11.24, Node.js 24 with npm, GNU Make, a running Docker daemon with Docker Compose v2, OpenSSL, GNU `sha256sum`, `curl`, and `tar`.

Install the locked dependencies and repository tools:

```shell
make setup
```

Run all formatting checks, linters, type/compile checks, unit tests, builds, pre-commit hooks,
Gitleaks, and the ordered local data-runtime integration checks:

```shell
make ci
```

Apply the configured source formatters:

```shell
make format
```

## Current limitations

The local runtime currently covers only the verified MySQL, dual Redis, Elasticsearch/IK, and
RocketMQ 5 Broker/Proxy foundations. No business feature, API contract, production business
schema, complete service runtime, production RocketMQ topic or event schema, authentication flow,
model-provider integration, performance result, deployment, or operational readiness claim is
implemented. The service skeletons are not production-runnable.

Implementation starts from the single active row in [IMPLEMENTATION.md](IMPLEMENTATION.md), follows its linked slice specification, and begins with the frozen-contract sections referenced by that slice. Other slice specifications may be consulted when a dependency or contract question requires them, but they must not be implemented early.

## Local data runtimes

Generate private synthetic credentials, start the health-gated MySQL, dual Redis,
Elasticsearch/IK, and RocketMQ Broker/Proxy topology, and run the owning migration streams:

```shell
make init-local
make up
```

Commerce Redis and Support Redis have separate required URLs, credentials, Docker-assigned host
ports, containers, and named volumes. Commerce Redis uses AOF with `noeviction`; Support Redis uses a bounded
`volatile-lfu` policy for TTL-bearing cache data. Neither Redis instance is authoritative business
storage.

Elasticsearch is a single local node with the version-matched IK analyzer installed in its pinned
image. This runtime foundation does not create the later production knowledge index or make
Elasticsearch authoritative storage.

RocketMQ runs a pinned 5.x NameServer and combined Broker/Proxy process. Later 5.x clients use the
Proxy endpoint reported by `docker compose port rocketmq-broker-proxy 8081`; a dedicated
gRPC Java probe gates `make up` on that exact route. The disposable readiness topic and integration
probe do not define production topics, payloads, consumers, or delivery-semantics guarantees.

The normal shutdown path is non-destructive and preserves the MySQL, Redis, Elasticsearch, and
RocketMQ named volumes:

```shell
make down
```

Run the complete integration entry point in its fixed resource-safe order. It verifies a clean
aggregate startup, migration and health gates, repeat-start idempotence, non-destructive shutdown,
and the MySQL, Redis, Elasticsearch/IK, and RocketMQ component probes and rejection paths:

```shell
make test-integration
```

GitHub Actions runs the same component and integration targets as `make ci` in isolated parallel
lanes, then requires every lane through the final `ci` check. Component failures remain non-zero
instead of being reported as skipped passes.

`make reset-local CONFIRM_RESET_LOCAL=1` is the explicit destructive path; it removes local named
volumes and the generated credential file.
