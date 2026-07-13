SHELL := /bin/bash

GITLEAKS_VERSION := 8.30.1

.DEFAULT_GOAL := ci
.PHONY: setup format lint typecheck test build secret-scan ci guard-layout

guard-layout:
	test -x ./mvnw
	test -f pom.xml
	test -f auth-service/pom.xml
	test -f commerce-service/pom.xml
	test -f agent-service/pyproject.toml
	test -f knowledge-indexer/pyproject.toml
	test -f uv.lock
	test -f web/package.json
	test -f web/package-lock.json
	test -f .pre-commit-config.yaml
	test -x scripts/install-gitleaks.sh

setup: guard-layout
	./mvnw --version
	uv sync --all-packages --locked
	npm --prefix web ci
	GITLEAKS_VERSION=$(GITLEAKS_VERSION) ./scripts/install-gitleaks.sh
	uv run pre-commit install-hooks

format: guard-layout
	./mvnw spotless:apply
	uv run ruff check --fix agent-service knowledge-indexer
	uv run ruff format agent-service knowledge-indexer
	npm --prefix web run format

lint: guard-layout
	./mvnw spotless:check checkstyle:check
	uv run ruff format --check agent-service knowledge-indexer
	uv run ruff check agent-service knowledge-indexer
	npm --prefix web run format:check
	npm --prefix web run lint
	uv run pre-commit run --all-files

typecheck: guard-layout
	./mvnw -DskipTests compile
	uv run mypy agent-service/src knowledge-indexer/src
	npm --prefix web run typecheck

test: guard-layout
	./mvnw test
	uv run pytest
	npm --prefix web test

build: guard-layout
	./mvnw package
	uv build --package citybuddy-agent-service
	uv build --package citybuddy-knowledge-indexer
	npm --prefix web run build

secret-scan: guard-layout
	.tools/gitleaks git --no-banner --redact --verbose .

ci: lint typecheck test build secret-scan
