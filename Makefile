SHELL := /bin/bash

GITLEAKS_VERSION := 8.30.1
PYTHON_PATHS := agent-service knowledge-indexer scripts tests
PYTHON_TYPED_PATHS := agent-service/src agent-service/tests knowledge-indexer/src knowledge-indexer/tests scripts tests
ENV_FILE ?= .env
COMPOSE_PROJECT_NAME ?= citybuddy
COMPOSE_WAIT_TIMEOUT ?= 90
COMPOSE_BUILD ?= --build
COMPOSE := docker compose --project-name "$(COMPOSE_PROJECT_NAME)" --env-file "$(ENV_FILE)" --file compose.yaml

.DEFAULT_GOAL := ci
.PHONY: setup setup-java setup-python setup-web setup-repo format lint typecheck test build docs-check secret-scan java-ci python-ci web-ci repo-ci ci guard-layout init-local up down reset-local grant-access revoke-v013-migration-access migrate-auth migrate-commerce migrate-agent rocketmq-store-init rocketmq-init test-integration test-runtime-integration test-mysql-integration test-identity-integration test-evaluation-identity-integration test-evaluation-sandbox-integration test-catalog-integration test-redis-integration test-elasticsearch-integration test-knowledge-search-integration test-retrieval-evidence-integration test-rocketmq-integration test-knowledge-indexer-rocketmq-spike test-knowledge-sync-integration

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
	test -x scripts/apply_mysql_grants.sh
	test -x scripts/run_mysql_migrations.sh
	test -x scripts/test_mysql_integration.sh
	test -x scripts/test_identity_integration.sh
	test -x scripts/test_evaluation_identity_integration.sh
	test -x scripts/test_evaluation_sandbox_integration.sh
	test -f scripts/drop_response_proxy.py
	test -x scripts/test_catalog_integration.sh
	test -x scripts/test_redis_integration.sh
	test -x scripts/test_elasticsearch_integration.sh
	test -x scripts/test_knowledge_search_integration.sh
	test -x scripts/test_retrieval_evidence_integration.sh
	test -x scripts/test_rocketmq_integration.sh
	test -x scripts/test_knowledge_indexer_rocketmq_spike.sh
	test -x scripts/test_knowledge_sync_integration.sh
	test -x scripts/test_runtime_integration.sh
	test -f scripts/test_dynamic_ports.sh
	test -f scripts/fake_litellm_server.py
	test -f scripts/check_knowledge_search.py
	test -f scripts/check_retrieval_evidence.py
	test -f scripts/check_retrieval_calibration.py
	test -f scripts/check_incremental_knowledge_sync.py
	test -f scripts/seed_legacy_knowledge_mapping.py
	test -f infra/mysql/grants/V001__migration_access.sql
	test -f infra/elasticsearch/Dockerfile
	test -f infra/rocketmq/broker.conf
	test -f infra/rocketmq/probe/Dockerfile
	test -f infra/rocketmq/python-spike/Dockerfile
	test -f infra/knowledge-indexer/Dockerfile
	test -f infra/rocketmq/probe/pom.xml

init-local:
	ENV_FILE="$(ENV_FILE)" ./scripts/init_local.sh

grant-access:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	@status=0; $(COMPOSE) run --rm mysql-grants || status=$$?; \
	if (( status != 0 )); then \
		echo "MySQL grant job failed; collecting service diagnostics." >&2; \
		$(COMPOSE) ps --all mysql >&2 || true; \
		container_id="$$($(COMPOSE) ps --quiet mysql 2>/dev/null)"; \
		if [[ -n "$$container_id" ]]; then \
			docker inspect --format 'mysql-status={{.State.Status}} mysql-running={{.State.Running}} mysql-restarting={{.State.Restarting}} mysql-restart-count={{.RestartCount}} mysql-health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$$container_id" >&2 || true; \
			docker inspect --format '{{range .State.Health.Log}}mysql-health-check end={{.End}} exit={{.ExitCode}} output={{json .Output}}{{println}}{{end}}' "$$container_id" >&2 || true; \
		fi; \
		$(COMPOSE) logs --no-color --tail 120 mysql >&2 || true; \
		exit $$status; \
	fi

revoke-v013-migration-access:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm -e V013_FORCE_REVOKE=true mysql-grants

migrate-auth:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm auth-migrate

migrate-commerce:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	@preflight_status=0; \
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) grant-access || preflight_status=$$?; \
	if (( preflight_status != 0 )); then \
	  $(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) revoke-v013-migration-access || true; \
	  exit $$preflight_status; \
	fi; \
	prepare_status=0; \
	$(COMPOSE) run --rm -e MIGRATION_PREPARE_V013=true commerce-migrate || prepare_status=$$?; \
	if (( prepare_status != 0 )); then \
	  $(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) revoke-v013-migration-access || true; \
	  exit $$prepare_status; \
	fi; \
	grant_status=0; \
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) grant-access || grant_status=$$?; \
	if (( grant_status != 0 )); then \
	  $(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) revoke-v013-migration-access || true; \
	  exit $$grant_status; \
	fi; \
	migration_status=0; \
	$(COMPOSE) run --rm commerce-migrate || migration_status=$$?; \
	cleanup_status=0; \
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) revoke-v013-migration-access || cleanup_status=$$?; \
	if (( migration_status != 0 )); then exit $$migration_status; fi; \
	exit $$cleanup_status

migrate-agent:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm agent-migrate

rocketmq-store-init:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm --no-deps rocketmq-store-init

rocketmq-init:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) run --rm --no-deps rocketmq-admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic cb013-readiness --readQueueNums 1 --writeQueueNums 1

up:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) rocketmq-store-init
	$(COMPOSE) up $(COMPOSE_BUILD) --detach --wait --wait-timeout $(COMPOSE_WAIT_TIMEOUT) mysql redis-commerce redis-support elasticsearch rocketmq-namesrv rocketmq-broker-proxy
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) rocketmq-init
	$(COMPOSE) up $(COMPOSE_BUILD) --detach --wait --wait-timeout $(COMPOSE_WAIT_TIMEOUT) rocketmq-probe
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) grant-access
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) migrate-auth
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) migrate-commerce
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) migrate-agent
	$(MAKE) ENV_FILE=$(ENV_FILE) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) grant-access

down:
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) down --remove-orphans

reset-local:
	@test "$(CONFIRM_RESET_LOCAL)" = "1" || { echo "Refusing reset: rerun with CONFIRM_RESET_LOCAL=1" >&2; exit 1; }
	ENV_FILE="$(ENV_FILE)" ./scripts/require_local_env.sh
	$(COMPOSE) down --volumes --remove-orphans
	rm -f "$(ENV_FILE)"

test-mysql-integration:
	./scripts/test_mysql_integration.sh

test-identity-integration:
	./scripts/test_identity_integration.sh

test-evaluation-identity-integration:
	./scripts/test_evaluation_identity_integration.sh

test-evaluation-sandbox-integration:
	./scripts/test_evaluation_sandbox_integration.sh

test-catalog-integration:
	./scripts/test_catalog_integration.sh

test-redis-integration:
	./scripts/test_redis_integration.sh

test-elasticsearch-integration:
	./scripts/test_elasticsearch_integration.sh

test-knowledge-search-integration:
	./scripts/test_knowledge_search_integration.sh

test-retrieval-evidence-integration:
	./scripts/test_retrieval_evidence_integration.sh

test-rocketmq-integration:
	./scripts/test_rocketmq_integration.sh

test-knowledge-indexer-rocketmq-spike:
	./scripts/test_knowledge_indexer_rocketmq_spike.sh

test-knowledge-sync-integration:
	./scripts/test_knowledge_sync_integration.sh

test-runtime-integration:
	./scripts/test_runtime_integration.sh

test-integration:
	$(MAKE) test-runtime-integration
	$(MAKE) test-mysql-integration
	$(MAKE) test-identity-integration
	$(MAKE) test-evaluation-identity-integration
	$(MAKE) test-evaluation-sandbox-integration
	$(MAKE) test-catalog-integration
	$(MAKE) test-redis-integration
	$(MAKE) test-elasticsearch-integration
	$(MAKE) test-knowledge-search-integration
	$(MAKE) test-retrieval-evidence-integration
	$(MAKE) test-rocketmq-integration
	$(MAKE) test-knowledge-indexer-rocketmq-spike
	$(MAKE) test-knowledge-sync-integration

setup: setup-java setup-python setup-web setup-repo

setup-java: guard-layout
	./mvnw --version

setup-python: guard-layout
	uv sync --all-packages --locked

setup-web: guard-layout
	npm --prefix web ci

setup-repo: setup-python
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
