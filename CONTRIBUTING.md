# Contributing to CityBuddy

[Project overview](README.md) · [中文介绍](README.zh-CN.md) · [Development agreement](AGENTS.md)

Keep changes focused on a concrete behavior, defect or documentation gap. The [business contracts](docs/CONTRACTS.md) describe the transaction and authorization boundaries; [engineering notes](docs/LESSONS.md) explain existing tradeoffs. [AGENTS.md](AGENTS.md) remains the repository's development ruleset.

## Set up

Install JDK 21, Python 3.11+, Node.js 24, uv, GNU Make and Docker Compose v2. See the [project guide](docs/PROJECT_GUIDE.md) for additional command-line prerequisites. From the repository root:

```sh
make setup
```

This prepares the Java, Python, Web and repository-check tooling. For local service configuration, run `make init-local`; follow the [demo guide](docs/DEMO.md) for the shared ShopMate retail deployment, fixture ownership and shutdown steps. Existing runtime files and data are not sample files to commit.

## Check a change

Before requesting review, run the four base checks:

```sh
make java-ci python-ci web-ci repo-ci
```

Also run the integration suites affected by the change. Examples from the [Makefile](Makefile):

| Changed behavior | Relevant entry points |
|---|---|
| Order, payment or refund persistence | `make test-mysql-integration` |
| Login, signing keys or delegation | `make test-identity-integration` |
| Catalog, cache or publication events | `make test-catalog-integration test-redis-integration` |
| Transaction messages or asynchronous order recovery | `make test-rocketmq-integration` |
| Evaluation identity or isolated business fixtures | `make test-evaluation-identity-integration test-evaluation-sandbox-integration` |

These are examples, not the full matrix. Use the suites covering the actual behavior and dependencies you changed; integration scripts exercise real local services and fixtures. Do not point fixture or reset commands at a deployment whose data you need to keep.

[GitHub Actions](.github/workflows/ci.yml) runs the full matrix in parallel as the merge gate. Run local `make ci` when changing CI or shared topology; it otherwise repeats the same matrix serially. Do not remove or weaken tests to obtain a pass.

## Submit for review

- Keep one focused branch and pull request at a time.
- Describe the triggering problem, the resulting behavior and any material tradeoff. Include the commands actually run and their real results.
- Add or update tests at the affected business or trust boundary. Keep documentation and both overview READMEs consistent when changing public behavior.
- Substantive changes receive an independent read-only review before merge. Follow the existing commit style and keep unrelated cleanup separate.

## Measurements and sensitive data

Performance work follows the [benchmark guide](bench/README.md): use standard tools, a committed source-clean version and the full measured CityBuddy SHA. Keep the workload, hardware, persistence settings and exclusions with the raw output; validate business state with SQL against the authoritative database.

Report observed work points and comparison results within their actual scope. Sold-out request throughput, successful order throughput and Agent task completion are different measurements. Preserve unsuccessful or inconclusive runs alongside the conclusions they inform.

Never commit credentials, tokens, personal data, runtime databases or private planning material. Keep local configuration in ignored files and inspect generated output before including it in a pull request. Measurement directories retain their existing evidence rules; do not sweep unrelated local results into a change.
