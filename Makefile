SHELL := /bin/bash

GITLEAKS_VERSION := 8.30.1
PYTHON_PATHS := agent-service knowledge-indexer scripts tests
PYTHON_TYPED_PATHS := agent-service/src agent-service/tests knowledge-indexer/src knowledge-indexer/tests scripts tests
ENV_FILE ?= .env
COMPOSE_PROJECT_NAME ?= citybuddy
COMPOSE := docker compose --project-name "$(COMPOSE_PROJECT_NAME)" --env-file "$(ENV_FILE)" --file compose.yaml

.DEFAULT_GOAL := ci
.PHONY: setup format lint typecheck test build docs-check secret-scan java-ci python-ci web-ci repo-ci ci guard-layout init-local up down reset-local migrate-auth migrate-commerce migrate-agent test-integration

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
	test -f compose.yaml
	test -f .env.example
	test -f scripts/check_docs_route.py
	test -x scripts/install-gitleaks.sh
	test -x scripts/init_local.sh
	test -x scripts/require_local_env.sh
	test -x scripts/run_mysql_migrations.sh
	test -x scripts/test_mysql_integration.sh

init-local:
	ENV_FILE="$(ENV_FILE)" ./scripts/init_local.sh

migrate-auth:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm auth-migrate

migrate-commerce:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm commerce-migrate

migrate-agent:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm agent-migrate

up:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) up --detach --wait mysql
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) migrate-auth
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) migrate-commerce
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) migrate-agent

down:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) down --remove-orphans

reset-local:
	@test "$(CONFIRM_RESET_LOCAL)" = "1" || { echo "Refusing reset: rerun with CONFIRM_RESET_LOCAL=1" >&2; exit 1; }
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) down --volumes --remove-orphans
	rm -f "$(ENV_FILE)"

test-integration:
	./scripts/test_mysql_integration.sh

setup: guard-layout
	./mvnw --version
	uv sync --all-packages --locked
	npm --prefix web ci
	GITLEAKS_VERSION=$(GITLEAKS_VERSION) ./scripts/install-gitleaks.sh
	uv run pre-commit install-hooks

format: guard-layout
	./mvnw spotless:apply
	uv run ruff check --fix $(PYTHON_PATHS)
	uv run ruff format $(PYTHON_PATHS)
	npm --prefix web run format

lint: guard-layout
	./mvnw spotless:check checkstyle:check
	uv run ruff format --check $(PYTHON_PATHS)
	uv run ruff check $(PYTHON_PATHS)
	npm --prefix web run format:check
	npm --prefix web run lint
	uv run pre-commit run --all-files

typecheck: guard-layout
	./mvnw -DskipTests compile
	uv run mypy $(PYTHON_TYPED_PATHS)
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

docs-check: guard-layout
	uv run python scripts/check_docs_route.py

secret-scan: guard-layout
	.tools/gitleaks git --no-banner --redact --verbose .

java-ci: guard-layout
	./mvnw verify

python-ci: guard-layout
	uv run ruff format --check $(PYTHON_PATHS)
	uv run ruff check $(PYTHON_PATHS)
	uv run mypy $(PYTHON_TYPED_PATHS)
	uv run pytest
	uv build --package citybuddy-agent-service
	uv build --package citybuddy-knowledge-indexer

web-ci: guard-layout
	npm --prefix web run format:check
	npm --prefix web run lint
	npm --prefix web test
	npm --prefix web run build

repo-ci: guard-layout
	uv run pre-commit run --all-files
	$(MAKE) secret-scan

ci: java-ci python-ci web-ci repo-ci test-integration
