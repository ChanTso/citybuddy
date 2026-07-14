SHELL := /bin/bash

GITLEAKS_VERSION := 8.30.1
PYTHON_PATHS := agent-service knowledge-indexer scripts tests
PYTHON_TYPED_PATHS := agent-service/src agent-service/tests knowledge-indexer/src knowledge-indexer/tests scripts tests

.DEFAULT_GOAL := ci
.PHONY: setup format lint typecheck test build docs-check secret-scan java-ci python-ci web-ci repo-ci ci guard-layout

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
	test -f scripts/check_docs_route.py
	test -x scripts/install-gitleaks.sh

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

ci: java-ci python-ci web-ci repo-ci
