"""Isolated, repeatable operator runtime for the CityBuddy verified public paths."""

# ruff: noqa: E501

from __future__ import annotations

import argparse
import contextlib
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import signal
import stat
import subprocess
import time
import uuid
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, NoReturn, cast

import bcrypt
import httpx
import redis

REPOSITORY = Path(__file__).resolve().parents[1]
STATE_ROOT = REPOSITORY / ".citybuddy-demo"
RUNS_ROOT = STATE_ROOT / "runs"
ACTIVE_STATE = STATE_ROOT / "active.json"
SCHEMA_VERSION = "citybuddy-demo-v1"
RUN_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{11,63}$")
IDENTIFIER_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
COMPOSE_SERVICES = (
    "mysql",
    "redis-commerce",
    "redis-support",
    "elasticsearch",
    "rocketmq-namesrv",
    "rocketmq-broker-proxy",
    "knowledge-indexer",
)
PUBLIC_PERMISSIONS = (
    "catalog:read",
    "order:create",
    "seckill:reserve",
    "payment:create",
    "refund:create",
    "support:session:create",
    "support:chat",
)
ACTION_ORDER_ID = "00000000-0000-0000-0000-000000000105"
ACTION_SANDBOX = "cb151-action"
ACTION_PRODUCT_ID = "cb151-action-product"
ACTION_AMOUNT = 400
KNOWLEDGE_PRODUCT_ID = "product-jasmine-tea"
KNOWLEDGE_REFUND_FAQ_ID = "faq-refund-policy"
KNOWLEDGE_DELIVERY_FAQ_ID = "faq-delivery"
DEMO_AGENT_ATTEMPT_BUDGET = 9
ELASTICSEARCH_RESTORE_ARGS = (
    "start",
    "--wait",
    "--wait-timeout",
    "90",
    "elasticsearch",
)
TRIGGERS = (
    "cb151_fail_reference_insert",
    "cb151_fail_decline_event",
    "cb151_fail_expiry_event",
)
JAVA_RUNTIME_IMAGE = (
    "maven:3.9.11-eclipse-temurin-21@"
    "sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237"
)


class DemoError(RuntimeError):
    """A bounded operator error safe to include in machine-readable output."""


@dataclass(frozen=True)
class Response:
    status: int
    body: Any


@dataclass(frozen=True)
class ActiveRun:
    run_id: str
    run_directory: Path
    project: str

    @property
    def env_file(self) -> Path:
        return self.run_directory / "runtime.env"

    @property
    def manifest_file(self) -> Path:
        return self.run_directory / "manifest.json"

    @property
    def private_file(self) -> Path:
        return self.run_directory / "private.json"

    @property
    def commerce_secrets_file(self) -> Path:
        return self.run_directory / "commerce-secrets.properties"

    @property
    def auth_secrets_file(self) -> Path:
        return self.run_directory / "auth-secrets.properties"

    @property
    def artifacts(self) -> Path:
        return self.run_directory / "artifacts"

    @classmethod
    def create(cls) -> ActiveRun:
        if ACTIVE_STATE.exists():
            existing = cls.load()
            raise DemoError(f"active demo run already exists: {existing.run_id}")
        run_id = f"{datetime.now(UTC).strftime('%Y%m%d-%H%M%S')}-{secrets.token_hex(4)}"
        run = cls(run_id, RUNS_ROOT / run_id, f"citybuddy-demo-{run_id}")
        run.validate_scope()
        STATE_ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
        RUNS_ROOT.mkdir(mode=0o700, exist_ok=True)
        run.run_directory.mkdir(mode=0o700)
        run.artifacts.mkdir(mode=0o700)
        write_json(
            ACTIVE_STATE,
            {
                "project": run.project,
                "runDirectory": str(run.run_directory),
                "runId": run.run_id,
            },
            mode=0o600,
        )
        return run

    @classmethod
    def load(cls) -> ActiveRun:
        try:
            payload = read_json(ACTIVE_STATE)
        except FileNotFoundError as error:
            raise DemoError("no active demo run") from error
        if not isinstance(payload, dict) or set(payload) != {"project", "runDirectory", "runId"}:
            raise DemoError("active demo state has an invalid schema")
        run_id = payload.get("runId")
        project = payload.get("project")
        run_directory = payload.get("runDirectory")
        if not all(isinstance(item, str) for item in (run_id, project, run_directory)):
            raise DemoError("active demo state has invalid field types")
        run = cls(str(run_id), Path(str(run_directory)), str(project))
        run.validate_scope()
        return run

    def validate_scope(self) -> None:
        if RUN_ID_PATTERN.fullmatch(self.run_id) is None:
            raise DemoError("demo run id failed its destructive-scope guard")
        if self.project != f"citybuddy-demo-{self.run_id}":
            raise DemoError("demo project failed its destructive-scope guard")
        expected = RUNS_ROOT / self.run_id
        if self.run_directory != expected:
            raise DemoError("demo run directory failed its destructive-scope guard")
        resolved_root = RUNS_ROOT.resolve()
        if expected.resolve().parent != resolved_root:
            raise DemoError("demo run directory is outside the bounded cleanup root")

    def require_confirmation(self, supplied: str) -> None:
        self.validate_scope()
        if supplied != self.run_id:
            raise DemoError("CONFIRM_DEMO_RUN_ID must exactly match the active run id")

    def manifest(self) -> dict[str, Any]:
        payload = read_json(self.manifest_file)
        if not isinstance(payload, dict) or payload.get("schemaVersion") != SCHEMA_VERSION:
            raise DemoError("runtime manifest has an invalid schema")
        if payload.get("runId") != self.run_id or payload.get("project") != self.project:
            raise DemoError("runtime manifest does not match the active run")
        return payload

    def private(self) -> dict[str, str]:
        payload = read_json(self.private_file)
        if not isinstance(payload, dict) or not all(
            isinstance(key, str) and isinstance(value, str) for key, value in payload.items()
        ):
            raise DemoError("private runtime state has an invalid schema")
        return payload


def emit(command: str, status: str, **details: object) -> None:
    print(
        json.dumps(
            {"command": command, "schemaVersion": SCHEMA_VERSION, "status": status, **details},
            separators=(",", ":"),
            sort_keys=True,
        )
    )


def fail(command: str, message: str) -> NoReturn:
    emit(command, "rejected", error=message)
    raise SystemExit(2)


def write_json(path: Path, payload: object, *, mode: int = 0o644) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(4)}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, separators=(",", ":"), sort_keys=True)
            stream.write("\n")
        os.replace(temporary, path)
        path.chmod(mode)
    finally:
        with contextlib.suppress(FileNotFoundError):
            temporary.unlink()


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise DemoError(f"invalid JSON state: {path.name}") from error


def write_private_text(path: Path, value: str) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(value)
    path.chmod(0o600)


def run_command(
    command: Sequence[str],
    *,
    env: Mapping[str, str] | None = None,
    timeout: float = 600,
    stdin: str | None = None,
) -> str:
    try:
        completed = subprocess.run(
            command,
            cwd=REPOSITORY,
            env=dict(env) if env is not None else None,
            input=stdin,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise DemoError(f"command failed to execute: {command[0]}") from error
    if completed.returncode != 0:
        tail = completed.stdout[-2000:].replace("\n", " ")
        raise DemoError(f"command failed ({command[0]}): {tail}")
    return completed.stdout.strip()


def compose(run: ActiveRun, *arguments: str, timeout: float = 600) -> str:
    return run_command(
        (
            "docker",
            "compose",
            "--project-name",
            run.project,
            "--env-file",
            str(run.env_file),
            "--file",
            "compose.yaml",
            *arguments,
        ),
        timeout=timeout,
    )


def compose_port(run: ActiveRun, service: str, container_port: int) -> int:
    output = compose(run, "port", service, str(container_port), timeout=30)
    match = re.search(r":([0-9]{1,5})$", output)
    if match is None:
        raise DemoError(f"could not resolve dynamic port for {service}")
    port = int(match.group(1))
    if not 1 <= port <= 65535:
        raise DemoError(f"invalid dynamic port for {service}")
    return port


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        key, separator, value = raw.partition("=")
        if not separator or not key:
            raise DemoError("runtime environment file is malformed")
        values[key] = value
    return values


def private_mode(path: Path) -> bool:
    return stat.S_IMODE(path.stat().st_mode) == 0o600


def mysql_query(
    run: ActiveRun,
    database: str,
    statement: str,
    *,
    user: str = "root",
    password: str | None = None,
) -> str:
    env = os.environ.copy()
    private = run.private()
    env["MYSQL_PWD"] = password or private["MYSQL_BOOTSTRAP_PASSWORD"]
    command = [
        "docker",
        "compose",
        "--project-name",
        run.project,
        "--env-file",
        str(run.env_file),
        "--file",
        "compose.yaml",
        "exec",
        "-T",
        "-e",
        "MYSQL_PWD",
        "mysql",
        "mysql",
        "--protocol=tcp",
        "--host=127.0.0.1",
        f"--user={user}",
        "--batch",
        "--skip-column-names",
    ]
    if database:
        command.append(database)
    return run_command(command, env=env, stdin=statement, timeout=60)


def redis_client(run: ActiveRun, *, support: bool = False, indexer: bool = False) -> redis.Redis:
    manifest = run.manifest()
    private = run.private()
    port_name = "redisSupport" if support else "redisCommerce"
    if support:
        user: str | None = "knowledge_indexer" if indexer else "agent_cache"
        password_name = "REDIS_INDEXER_CACHE_PASSWORD" if indexer else "REDIS_AGENT_CACHE_PASSWORD"
    else:
        user = None
        password_name = "REDIS_COMMERCE_PASSWORD"
    return redis.Redis(
        host="127.0.0.1",
        port=int(manifest["ports"][port_name]),
        username=user,
        password=private[password_name],
        decode_responses=True,
        socket_timeout=2,
    )


def request(
    method: str,
    url: str,
    *,
    expected: int | Sequence[int],
    headers: Mapping[str, str] | None = None,
    body: object | None = None,
    auth: tuple[str, str] | None = None,
    timeout: float = 10,
) -> Response:
    try:
        response = httpx.request(
            method,
            url,
            headers=headers,
            json=body,
            auth=auth,
            timeout=timeout,
        )
    except httpx.HTTPError as error:
        raise DemoError(f"HTTP dependency unavailable for {method} {url}") from error
    statuses = (expected,) if isinstance(expected, int) else tuple(expected)
    try:
        payload: Any = response.json() if response.content else None
    except ValueError:
        payload = response.text
    if response.status_code not in statuses:
        raise DemoError(
            f"unexpected HTTP {response.status_code} for {method} {url}: {str(payload)[:300]}"
        )
    return Response(response.status_code, payload)


def wait_http(url: str, expected: Sequence[int], process: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise DemoError(f"runtime process exited before readiness: {url}")
        try:
            response = httpx.get(url, timeout=1)
            if response.status_code in expected:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.25)
    raise DemoError(f"runtime readiness timed out: {url}")


def listener_ports(output: str) -> set[int]:
    return {
        int(match.group(1))
        for line in output.splitlines()
        if (match := re.fullmatch(r"n(?:TCP )?(?:127\.0\.0\.1|\*|\[::1\]|\[::\]):([0-9]+)", line))
        is not None
    }


def bound_port(pid: int) -> int:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            output = run_command(
                ("lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN", "-Fn"),
                timeout=5,
            )
        except DemoError:
            output = ""
        ports = listener_ports(output)
        if len(ports) == 1:
            return ports.pop()
        if not process_exists(pid):
            raise DemoError("runtime process exited before binding a port")
        time.sleep(0.25)
    raise DemoError("runtime process did not bind exactly one loopback port")


def process_exists(pid: int) -> bool:
    try:
        waited, _ = os.waitpid(pid, os.WNOHANG)
        if waited == pid:
            return False
    except ChildProcessError:
        pass
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def start_process(
    run: ActiveRun,
    name: str,
    command: Sequence[str],
    *,
    env: Mapping[str, str] | None = None,
) -> tuple[subprocess.Popen[bytes], int]:
    log = run.run_directory / f"{name}.log"
    stream = log.open("ab", buffering=0)
    log.chmod(0o600)
    try:
        process = subprocess.Popen(
            command,
            cwd=REPOSITORY,
            env=dict(env) if env is not None else None,
            stdin=subprocess.DEVNULL,
            stdout=stream,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    finally:
        stream.close()
    port = bound_port(process.pid)
    return process, port


def credential_hash(value: str) -> str:
    return bcrypt.hashpw(value.encode(), bcrypt.gensalt(rounds=12)).decode()


def generate_key(private_path: Path, public_path: Path) -> None:
    run_command(
        (
            "openssl",
            "genpkey",
            "-algorithm",
            "RSA",
            "-pkeyopt",
            "rsa_keygen_bits:2048",
            "-out",
            str(private_path),
        )
    )
    run_command(("openssl", "pkey", "-in", str(private_path), "-pubout", "-out", str(public_path)))
    private_path.chmod(0o600)
    public_path.chmod(0o600)


def stable_uuid(run_id: str, label: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"citybuddy-demo:{run_id}:{label}"))


def stable_uuid4(run_id: str, label: str) -> str:
    value = bytearray(hashlib.sha256(f"citybuddy-demo:{run_id}:{label}".encode()).digest()[:16])
    value[6] = (value[6] & 0x0F) | 0x40
    value[8] = (value[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(value)))


def stable_id(run_id: str, label: str) -> str:
    value = re.sub(r"[^a-z0-9-]", "-", f"cb151-{label}-{run_id[-8:]}")
    if IDENTIFIER_PATTERN.fullmatch(value) is None:
        raise DemoError("generated fixture id is invalid")
    return value


def compose_topics(run: ActiveRun) -> None:
    for topic, message_type in (
        ("citybuddy-catalog-events", None),
        ("citybuddy-seckill-transactions", "TRANSACTION"),
        ("citybuddy-seckill-timeouts", "DELAY"),
    ):
        arguments = [
            "run",
            "--rm",
            "--no-deps",
            "rocketmq-admin",
            "updateTopic",
            "--namesrvAddr",
            "rocketmq-namesrv:9876",
            "--clusterName",
            "DefaultCluster",
            "--topic",
            topic,
            "--readQueueNums",
            "4",
            "--writeQueueNums",
            "4",
        ]
        if message_type is not None:
            arguments.extend(("-a", f"+message.type={message_type}"))
        compose(run, *arguments, timeout=90)
    for group in (
        f"cb151-catalog-{run.run_id}",
        f"cb151-seckill-{run.run_id}",
        f"cb151-timeout-{run.run_id}",
        f"cb151-rebuild-{run.run_id}",
        "citybuddy-knowledge-indexer",
    ):
        compose(
            run,
            "run",
            "--rm",
            "--no-deps",
            "rocketmq-admin",
            "updateSubGroup",
            "--namesrvAddr",
            "rocketmq-namesrv:9876",
            "--clusterName",
            "DefaultCluster",
            "--groupName",
            group,
            "--consumeEnable",
            "true",
            timeout=90,
        )


def seed_runtime(run: ActiveRun, private: dict[str, str], fixtures: dict[str, str]) -> None:
    permissions = " ".join(PUBLIC_PERMISSIONS)
    user_hash = credential_hash(private["DEMO_USER_PASSWORD"])
    other_hash = credential_hash(private["DEMO_OTHER_PASSWORD"])
    agent_hash = credential_hash(private["AGENT_SERVICE_CLIENT_SECRET"])
    commerce_hash = credential_hash(private["COMMERCE_SERVICE_CLIENT_SECRET"])
    evaluator_hash = credential_hash(private["EVALUATION_CLIENT_SECRET"])
    mysql_query(
        run,
        "commerce_db",
        f"""
INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions) VALUES
  ('{stable_uuid(run.run_id, "user-principal")}', 'cb151-user', 'cb151-user', 'ACTIVE', '{permissions}'),
  ('{stable_uuid(run.run_id, "other-principal")}', 'cb151-other-user', 'cb151-other-user', 'ACTIVE', '{permissions}');
INSERT INTO auth_login_credential (principal_id, password_hash) VALUES
  ('{stable_uuid(run.run_id, "user-principal")}', '{user_hash}'),
  ('{stable_uuid(run.run_id, "other-principal")}', '{other_hash}');
INSERT INTO auth_service_identity (service_id, client_id, credential_hash, state, allowed_scopes) VALUES
  ('{stable_uuid(run.run_id, "agent-service")}', 'agent-service', '{agent_hash}', 'ACTIVE', 'catalog:read refund:create'),
  ('{stable_uuid(run.run_id, "commerce-service")}', 'commerce-service', '{commerce_hash}', 'ACTIVE', 'eval:principal:manage'),
  ('{stable_uuid(run.run_id, "evaluation-client")}', 'evaluation-client', '{evaluator_hash}', 'ACTIVE', 'eval:test-token:issue');
INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after) VALUES
  ('cb151-current', 'CURRENT', CURRENT_TIMESTAMP(6), NULL);
INSERT INTO catalog_metadata (singleton_id, publication_generation) VALUES (1, 1);
INSERT INTO product
  (product_id, name, description, price_minor, currency, stock_quantity, available,
   publication_state, publication_version)
VALUES
  ('{fixtures["standardProduct"]}', '茉莉绿茶 Jasmine green tea',
   'A public product description for jasmine green tea with a floral aroma. 茉莉绿茶带有清新的花香。',
   750, 'CNY', 20, TRUE, 'PUBLISHED', 1);
INSERT INTO commerce_outbox
  (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload,
   publication_state, publish_attempts, created_at, published_at)
VALUES
  ('{stable_uuid4(run.run_id, "standard-product-event")}', 'PRODUCT',
   '{fixtures["standardProduct"]}', 1, 'PRODUCT_PUBLICATION_CHANGED',
   JSON_OBJECT('productId', '{fixtures["standardProduct"]}'),
   'PUBLISHED', 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
INSERT INTO seckill_activity
  (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version)
VALUES
  ('{fixtures["seckillActivity"]}', '{fixtures["seckillProduct"]}',
   '2020-01-01 00:00:00.000000',
   '2037-01-01 00:00:00.000000', 'ACTIVE', 5, 1);
INSERT INTO faq_source
  (faq_id, draft_question, draft_answer, draft_revision, working_state,
   published_question, published_answer, published_version, published_at)
VALUES
  ('{fixtures["faq"]}', '退款政策 Refund policy',
   'Eligible unused goods may be requested for return or refund under the merchant policy.',
   1, 'PUBLISHED', '退款政策 Refund policy',
   'Eligible unused goods may be requested for return or refund under the merchant policy.',
   1, CURRENT_TIMESTAMP(6)),
  ('{fixtures["faqDelivery"]}', '配送说明 Delivery guide',
   'Public delivery guidance describes the merchant delivery area and estimated handoff process.',
   1, 'PUBLISHED', '配送说明 Delivery guide',
   'Public delivery guidance describes the merchant delivery area and estimated handoff process.',
   2, CURRENT_TIMESTAMP(6));
INSERT INTO faq_publication_command
  (idempotency_key, event_id, faq_id, expected_draft_revision,
   expected_published_version, source_version, intent_hash, occurred_at)
VALUES
  ('{fixtures["faqCommand"]}', '{stable_uuid4(run.run_id, "faq-event")}', '{fixtures["faq"]}',
   1, 0, 1, REPEAT('1', 64), CURRENT_TIMESTAMP(6)),
  ('{fixtures["faqDeliveryCommand"]}', '{stable_uuid4(run.run_id, "faq-delivery-event")}',
   '{fixtures["faqDelivery"]}', 1, 1, 2, REPEAT('2', 64), CURRENT_TIMESTAMP(6));
""",
    )


def start_auth(run: ActiveRun, private: dict[str, str], mysql_port: int) -> tuple[str, int]:
    name = runtime_container_name(run, "auth")
    log = run.run_directory / "auth.log"
    stream = log.open("ab", buffering=0)
    log.chmod(0o600)
    process = subprocess.Popen(
        (
            "docker",
            "run",
            "--rm",
            "--name",
            name,
            "--network",
            f"{run.project}_default",
            "--network-alias",
            "cb151-auth",
            "--publish",
            "127.0.0.1::8080",
            "--volume",
            f"{REPOSITORY / 'auth-service/target/auth-service-0.0.1-SNAPSHOT.jar'}:/opt/citybuddy/auth.jar:ro",
            "--volume",
            f"{run.auth_secrets_file}:/run/secrets/auth.properties:ro",
            "--volume",
            f"{run.run_directory / 'current-private.pem'}:/run/secrets/current-private.pem:ro",
            "--volume",
            f"{run.run_directory / 'current-public.pem'}:/run/secrets/current-public.pem:ro",
            JAVA_RUNTIME_IMAGE,
            "java",
            "-jar",
            "/opt/citybuddy/auth.jar",
            "--server.port=8080",
            "--server.address=0.0.0.0",
            "--spring.profiles.active=evaluation",
            "--spring.datasource.url=jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true",
            "--spring.datasource.username=auth_app",
            "--spring.config.import=file:/run/secrets/auth.properties",
            "--citybuddy.identity.enabled=true",
            "--citybuddy.identity.issuer=https://identity.citybuddy.test",
            "--citybuddy.identity.user-audience=citybuddy-web",
            "--citybuddy.identity.current-kid=cb151-current",
            "--citybuddy.identity.current-private-key-path=/run/secrets/current-private.pem",
            "--citybuddy.identity.current-public-key-path=/run/secrets/current-public.pem",
            "--citybuddy.identity.exchange-scopes[0]=catalog:read",
            "--citybuddy.identity.exchange-scopes[1]=refund:create",
        ),
        cwd=REPOSITORY,
        stdin=subprocess.DEVNULL,
        stdout=stream,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    stream.close()
    try:
        port = wait_container_port(name, process)
        wait_http(f"http://127.0.0.1:{port}/auth/jwks", (200,), process)
    except BaseException:
        stop_runtime_container(run, name)
        raise
    return name, port


def commerce_command(
    run: ActiveRun,
    private: dict[str, str],
    ports: Mapping[str, int],
    *,
    action_ttl: str = "1m",
    inside_project: bool = False,
) -> tuple[str, ...]:
    auth_base = "http://cb151-auth:8080" if inside_project else f"http://127.0.0.1:{ports['auth']}"
    rocket = "rocketmq-broker-proxy:8081" if inside_project else f"127.0.0.1:{ports['rocketmq']}"
    mysql = "mysql:3306" if inside_project else f"127.0.0.1:{ports['mysql']}"
    redis_host = "redis-commerce:6379" if inside_project else f"127.0.0.1:{ports['redisCommerce']}"
    redis_url = f"redis://:{private['REDIS_COMMERCE_PASSWORD']}@{redis_host}/0"
    return (
        "java",
        "-jar",
        "/opt/citybuddy/commerce.jar"
        if inside_project
        else "commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar",
        f"--server.port={8080 if inside_project else 0}",
        "--server.address=0.0.0.0" if inside_project else "--server.address=127.0.0.1",
        "--spring.profiles.active=evaluation",
        f"--spring.datasource.url=jdbc:mysql://{mysql}/commerce_db?useSSL=false&allowPublicKeyRetrieval=true",
        "--spring.datasource.username=commerce_app",
        "--spring.datasource.hikari.connection-timeout=2000",
        f"--spring.data.redis.url={redis_url}",
        "--citybuddy.catalog.enabled=true",
        "--citybuddy.catalog.issuer=https://identity.citybuddy.test",
        "--citybuddy.catalog.user-audience=citybuddy-web",
        f"--citybuddy.catalog.jwks-url={auth_base}/auth/jwks",
        "--citybuddy.catalog.jwks-cache-ttl=1s",
        "--citybuddy.catalog.required-permission=catalog:read",
        f"--citybuddy.catalog.rocketmq-endpoints={rocket}",
        "--citybuddy.catalog.rocketmq-topic=citybuddy-catalog-events",
        f"--citybuddy.catalog.rocketmq-consumer-group=cb151-catalog-{run.run_id}",
        "--citybuddy.orders.enabled=true",
        "--citybuddy.seckill.enabled=true",
        "--citybuddy.seckill.order.enabled=true",
        f"--citybuddy.seckill.order.rocketmq-endpoints={rocket}",
        "--citybuddy.seckill.order.rocketmq-topic=citybuddy-seckill-transactions",
        f"--citybuddy.seckill.order.rocketmq-consumer-group=cb151-seckill-{run.run_id}",
        f"--citybuddy.seckill.timeout.rocketmq-endpoints={rocket}",
        "--citybuddy.seckill.timeout.rocketmq-topic=citybuddy-seckill-timeouts",
        f"--citybuddy.seckill.timeout.rocketmq-consumer-group=cb151-timeout-{run.run_id}",
        "--citybuddy.mock-payment.enabled=true",
        "--citybuddy.mock-payment.required-permission=support:chat",
        f"--citybuddy.mock-payment.callback-key-id={private['MOCK_PAYMENT_KEY_ID']}",
        "--citybuddy.refund.enabled=true",
        "--citybuddy.actions.enabled=true",
        f"--citybuddy.actions.pending-ttl={action_ttl}",
        "--citybuddy.obo.enabled=true",
        "--citybuddy.obo.issuer=https://identity.citybuddy.test",
        f"--citybuddy.obo.jwks-url={auth_base}/auth/jwks",
        "--citybuddy.obo.jwks-cache-ttl=1s",
        "--citybuddy.agent-tools.enabled=true",
        "--citybuddy.evaluation.management-client-id=evaluation-manager",
        f"--citybuddy.evaluation.auth-base-url={auth_base}",
        "--citybuddy.evaluation.auth-client-id=commerce-service",
        "--citybuddy.evaluation.identity-issuer=https://identity.citybuddy.test",
        "--citybuddy.evaluation.user-audience=citybuddy-web",
        f"--citybuddy.evaluation.jwks-url={auth_base}/auth/jwks",
        "--citybuddy.evaluation.jwks-cache-ttl=1s",
        "--citybuddy.evaluation.provisioning-timeout=10s",
        "--citybuddy.evaluation.auth-expiry-safety=2s",
        "--citybuddy.evaluation.cleanup-retry=1s",
        "--citybuddy.evaluation.janitor-interval=5s",
        "--citybuddy.evaluation.max-cleanup-attempts=5",
        "--citybuddy.evaluation.janitor-batch-size=4",
        "--citybuddy.evaluation.build-id=cb151-demo",
        "--citybuddy.evaluation.schema-compatibility=commerce-evaluation-v1",
        "--citybuddy.knowledge-snapshot.enabled=true",
        "--citybuddy.knowledge-snapshot.client-id=knowledge-indexer",
    )


def evaluation_commerce_command(
    run: ActiveRun,
    private: dict[str, str],
    *,
    action_ttl: str = "1m",
) -> tuple[str, ...]:
    auth_base = "http://cb151-auth:8080"
    return (
        "java",
        "-jar",
        "/opt/citybuddy/commerce.jar",
        "--server.port=8080",
        "--server.address=0.0.0.0",
        "--spring.profiles.active=evaluation",
        "--spring.datasource.url=jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true",
        "--spring.datasource.username=commerce_app",
        "--spring.datasource.hikari.connection-timeout=2000",
        "--spring.config.import=file:/run/secrets/commerce.properties",
        "--citybuddy.mock-payment.enabled=true",
        "--citybuddy.mock-payment.required-permission=support:chat",
        f"--citybuddy.mock-payment.callback-key-id={private['MOCK_PAYMENT_KEY_ID']}",
        "--citybuddy.refund.enabled=true",
        "--citybuddy.refund.required-permission=refund:create",
        "--citybuddy.actions.enabled=true",
        f"--citybuddy.actions.pending-ttl={action_ttl}",
        "--citybuddy.obo.enabled=true",
        "--citybuddy.obo.issuer=https://identity.citybuddy.test",
        f"--citybuddy.obo.jwks-url={auth_base}/auth/jwks",
        "--citybuddy.obo.jwks-cache-ttl=1s",
        "--citybuddy.agent-tools.enabled=true",
        "--citybuddy.evaluation.management-client-id=evaluation-manager",
        f"--citybuddy.evaluation.auth-base-url={auth_base}",
        "--citybuddy.evaluation.auth-client-id=commerce-service",
        "--citybuddy.evaluation.identity-issuer=https://identity.citybuddy.test",
        "--citybuddy.evaluation.user-audience=citybuddy-web",
        f"--citybuddy.evaluation.jwks-url={auth_base}/auth/jwks",
        "--citybuddy.evaluation.jwks-cache-ttl=1s",
        "--citybuddy.evaluation.provisioning-timeout=10s",
        "--citybuddy.evaluation.auth-expiry-safety=2s",
        "--citybuddy.evaluation.cleanup-retry=1s",
        "--citybuddy.evaluation.janitor-interval=5s",
        "--citybuddy.evaluation.max-cleanup-attempts=5",
        "--citybuddy.evaluation.janitor-batch-size=4",
        "--citybuddy.evaluation.build-id=cb151-demo",
        "--citybuddy.evaluation.schema-compatibility=commerce-evaluation-v1",
    )


def runtime_container_name(run: ActiveRun, service: str) -> str:
    if service not in {"auth", "commerce", "evaluation-commerce"}:
        raise DemoError("runtime container service failed its destructive-scope guard")
    return f"{run.project}-{service}"


def stop_runtime_container(run: ActiveRun, name: str) -> None:
    if name not in {
        runtime_container_name(run, "auth"),
        runtime_container_name(run, "commerce"),
        runtime_container_name(run, "evaluation-commerce"),
    }:
        raise DemoError("runtime container failed its destructive-scope guard")
    inspected = subprocess.run(
        ("docker", "container", "inspect", name),
        cwd=REPOSITORY,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if inspected.returncode == 0:
        run_command(("docker", "container", "rm", "--force", name), timeout=30)


def runtime_container_running(run: ActiveRun, name: str) -> bool:
    if name not in {
        runtime_container_name(run, "auth"),
        runtime_container_name(run, "commerce"),
        runtime_container_name(run, "evaluation-commerce"),
    }:
        raise DemoError("runtime container failed its destructive-scope guard")
    inspected = subprocess.run(
        ("docker", "container", "inspect", "--format={{.State.Running}}", name),
        cwd=REPOSITORY,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        check=False,
    )
    return inspected.returncode == 0 and inspected.stdout.strip() == "true"


def wait_container_port(name: str, process: subprocess.Popen[bytes]) -> int:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise DemoError(f"runtime container exited before port publication: {name}")
        try:
            output = run_command(("docker", "port", name, "8080/tcp"), timeout=5)
        except DemoError:
            output = ""
        match = re.fullmatch(r"127\.0\.0\.1:([0-9]+)", output)
        if match is not None:
            return int(match.group(1))
        time.sleep(0.25)
    raise DemoError(f"runtime container did not publish one loopback port: {name}")


def start_commerce(
    run: ActiveRun,
    private: dict[str, str],
    ports: dict[str, int],
    *,
    action_ttl: str = "1m",
) -> tuple[str, int]:
    env = os.environ.copy()
    name = runtime_container_name(run, "commerce")
    log = run.run_directory / "commerce.log"
    stream = log.open("ab", buffering=0)
    log.chmod(0o600)
    process = subprocess.Popen(
        (
            "docker",
            "run",
            "--rm",
            "--name",
            name,
            "--network",
            f"{run.project}_default",
            "--network-alias",
            "cb151-commerce",
            "--publish",
            "127.0.0.1::8080",
            "--volume",
            f"{REPOSITORY / 'commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar'}:/opt/citybuddy/commerce.jar:ro",
            "--volume",
            f"{run.commerce_secrets_file}:/run/secrets/commerce.properties:ro",
            JAVA_RUNTIME_IMAGE,
            *commerce_command(run, private, ports, action_ttl=action_ttl, inside_project=True),
            "--spring.config.import=file:/run/secrets/commerce.properties",
        ),
        cwd=REPOSITORY,
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=stream,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    stream.close()
    try:
        port = wait_container_port(name, process)
        wait_http(f"http://127.0.0.1:{port}/api/products", (401,), process)
    except BaseException:
        stop_runtime_container(run, name)
        raise
    return name, port


def start_evaluation_commerce(
    run: ActiveRun,
    private: dict[str, str],
    *,
    action_ttl: str = "1m",
) -> tuple[str, int]:
    name = runtime_container_name(run, "evaluation-commerce")
    log = run.run_directory / "evaluation-commerce.log"
    stream = log.open("ab", buffering=0)
    log.chmod(0o600)
    process = subprocess.Popen(
        (
            "docker",
            "run",
            "--rm",
            "--name",
            name,
            "--network",
            f"{run.project}_default",
            "--network-alias",
            "cb151-evaluation-commerce",
            "--publish",
            "127.0.0.1::8080",
            "--volume",
            f"{REPOSITORY / 'commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar'}:/opt/citybuddy/commerce.jar:ro",
            "--volume",
            f"{run.commerce_secrets_file}:/run/secrets/commerce.properties:ro",
            JAVA_RUNTIME_IMAGE,
            *evaluation_commerce_command(run, private, action_ttl=action_ttl),
        ),
        cwd=REPOSITORY,
        stdin=subprocess.DEVNULL,
        stdout=stream,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    stream.close()
    try:
        port = wait_container_port(name, process)
        wait_http(f"http://127.0.0.1:{port}/api/eval/version", (401,), process)
    except BaseException:
        stop_runtime_container(run, name)
        raise
    return name, port


def start_model(run: ActiveRun, commerce_port: int) -> tuple[int, int]:
    process, port = start_process(
        run,
        "fake-llm",
        (
            str(REPOSITORY / ".venv/bin/python"),
            "scripts/fake_litellm_server.py",
            "--port",
            "0",
            "--commerce-base-url",
            f"http://127.0.0.1:{commerce_port}",
        ),
    )
    try:
        wait_http(f"http://127.0.0.1:{port}/fixture/counts", (200,), process)
    except BaseException:
        stop_process(process.pid, "fake_litellm_server.py")
        raise
    return process.pid, port


def agent_runtime_urls(ports: Mapping[str, int]) -> dict[str, str]:
    return {
        "AGENT_MODEL_PROXY_URL": f"http://127.0.0.1:{ports['model']}",
        "AGENT_COMMERCE_TOOLS_URL": f"http://127.0.0.1:{ports['model']}",
        "AGENT_COMMERCE_LIVENESS_URL": (f"http://127.0.0.1:{ports['evaluationCommerce']}"),
    }


def rebind_agent_to_dependency_ports(
    run: ActiveRun, manifest: dict[str, Any], ports: dict[str, int]
) -> None:
    current = manifest.get("processes", {}).pop("agent", None)
    if not isinstance(current, dict):
        raise DemoError("runtime manifest omitted the exact Agent process")
    write_json(run.manifest_file, manifest)
    stop_process(int(current["pid"]), str(current["marker"]))
    agent_pid, ports["agent"] = start_agent(run, run.private(), ports)
    manifest["processes"]["agent"] = {"marker": "citybuddy-agent", "pid": agent_pid}
    manifest["ports"] = ports
    manifest["baseUrls"]["agent"] = f"http://127.0.0.1:{ports['agent']}"
    manifest["baseUrls"]["webEnv"]["CITYBUDDY_AGENT_TARGET"] = f"http://127.0.0.1:{ports['agent']}"
    write_json(run.manifest_file, manifest)


def start_agent(run: ActiveRun, private: dict[str, str], ports: dict[str, int]) -> tuple[int, int]:
    env = os.environ.copy()
    env.update(
        {
            "AGENT_PORT": "0",
            "AGENT_IDENTITY_ENABLED": "true",
            "AGENT_EVALUATION_ENABLED": "true",
            "AGENT_EVALUATION_CLIENT_ID": "evaluation-manager",
            "AGENT_EVALUATION_CLIENT_SECRET": private["EVALUATION_MANAGER_SECRET"],
            "AGENT_ATTEMPT_BUDGET": str(DEMO_AGENT_ATTEMPT_BUDGET),
            "CITYBUDDY_METRICS_ENABLED": "true",
            "CITYBUDDY_ENVIRONMENT": "demo",
            "IDENTITY_ISSUER": "https://identity.citybuddy.test",
            "IDENTITY_USER_AUDIENCE": "citybuddy-web",
            "IDENTITY_JWKS_URL": f"http://127.0.0.1:{ports['auth']}/auth/jwks",
            "IDENTITY_EXCHANGE_URL": f"http://127.0.0.1:{ports['auth']}/auth/token/exchange",
            "MYSQL_HOST": "127.0.0.1",
            "MYSQL_PORT": str(ports["mysql"]),
            "MYSQL_AGENT_APP_PASSWORD": private["MYSQL_AGENT_APP_PASSWORD"],
            "AGENT_SERVICE_CLIENT_ID": "agent-service",
            "AGENT_SERVICE_CLIENT_SECRET": private["AGENT_SERVICE_CLIENT_SECRET"],
            "AGENT_EXCHANGE_SCOPES": "catalog:read refund:create",
            **agent_runtime_urls(ports),
            "AGENT_ELASTICSEARCH_URL": f"http://127.0.0.1:{ports['elasticsearch']}",
            "AGENT_KNOWLEDGE_ALIAS": "knowledge_docs_read",
            "AGENT_SUPPORT_REDIS_URL": (
                f"redis://agent_cache:{private['REDIS_AGENT_CACHE_PASSWORD']}@"
                f"127.0.0.1:{ports['redisSupport']}/0"
            ),
        }
    )
    process, port = start_process(
        run, "agent", (str(REPOSITORY / ".venv/bin/citybuddy-agent"),), env=env
    )
    try:
        wait_http(f"http://127.0.0.1:{port}/internal/metrics/prometheus", (200,), process)
    except BaseException:
        stop_process(process.pid, "citybuddy-agent")
        raise
    return process.pid, port


def rebuild_knowledge(run: ActiveRun, private: dict[str, str], ports: Mapping[str, int]) -> None:
    compose(
        run,
        "run",
        "--rm",
        "--no-deps",
        "knowledge-indexer",
        "bootstrap",
        "--elasticsearch-url",
        "http://elasticsearch:9200",
        "--index",
        "knowledge_docs_v1",
        timeout=90,
    )
    output = compose(
        run,
        "run",
        "--rm",
        "--no-deps",
        "--volume",
        f"{REPOSITORY / 'scripts/demo_knowledge_rebuild.py'}:/opt/citybuddy/demo_knowledge_rebuild.py:ro",
        "--volume",
        f"{run.private_file}:/run/secrets/citybuddy-demo.json:ro",
        "--entrypoint",
        "/opt/citybuddy/.venv/bin/python",
        "knowledge-indexer",
        "/opt/citybuddy/demo_knowledge_rebuild.py",
        "--private-file",
        "/run/secrets/citybuddy-demo.json",
        "--owner-snapshot-url",
        "http://cb151-commerce:8080/internal/knowledge/snapshot",
        "--elasticsearch-url",
        "http://elasticsearch:9200",
        "--rocketmq-endpoints",
        "rocketmq-broker-proxy:8081",
        "--topic",
        "citybuddy-catalog-events",
        "--consumer-group",
        f"cb151-rebuild-{run.run_id}",
        timeout=180,
    )
    try:
        payload = json.loads(output.splitlines()[-1])
    except (IndexError, json.JSONDecodeError) as error:
        raise DemoError("knowledge rebuild did not return machine-readable evidence") from error
    if not isinstance(payload, dict) or not payload.get("candidate"):
        raise DemoError("knowledge rebuild did not produce the expected derived index")
    write_json(run.artifacts / "knowledge-rebuild.json", payload)


def initialize_action_fixture(run: ActiveRun) -> None:
    manifest = run.manifest()
    private = run.private()
    commerce = manifest["baseUrls"]["evaluationCommerce"]
    auth = manifest["baseUrls"]["auth"]
    reset_body = {
        "sandboxId": ACTION_SANDBOX,
        "caseCorrelation": f"case-{run.run_id}",
        "ttlSeconds": 3600,
        "testUserLabel": f"action-{run.run_id}",
        "products": [
            {
                "productId": ACTION_PRODUCT_ID,
                "name": "Action refund fixture",
                "description": "Evaluation-only paid order fixture for verified PendingAction paths.",
                "priceMinor": 200,
                "currency": "CNY",
                "stockQuantity": 4,
                "available": True,
            }
        ],
        "paymentOrder": {
            "orderId": ACTION_ORDER_ID,
            "productId": ACTION_PRODUCT_ID,
            "quantity": 2,
        },
    }
    reset = request(
        "POST",
        f"{commerce}/api/eval/reset",
        expected=200,
        headers={"Idempotency-Key": f"reset-{run.run_id}"},
        body=reset_body,
        auth=("evaluation-manager", private["EVALUATION_MANAGER_SECRET"]),
    )
    handle = reset.body["testUserHandle"]
    token = request(
        "POST",
        f"{auth}/auth/eval/test-token",
        expected=200,
        headers={"X-Eval-Sandbox-Id": ACTION_SANDBOX},
        body={"handle": handle},
        auth=("evaluation-client", private["EVALUATION_CLIENT_SECRET"]),
    ).body["accessToken"]
    private["ACTION_TOKEN"] = token
    private["ACTION_HANDLE"] = handle
    write_json(run.private_file, private, mode=0o600)

    payment = request(
        "POST",
        f"{commerce}/api/orders/{ACTION_ORDER_ID}/mock-payment",
        expected=201,
        headers={
            "Authorization": f"Bearer {token}",
            "X-Eval-Sandbox-Id": ACTION_SANDBOX,
            "Idempotency-Key": f"action-payment-{run.run_id}",
        },
        body={"amountMinor": ACTION_AMOUNT, "currency": "CNY"},
    ).body
    callback(
        run,
        order_id=ACTION_ORDER_ID,
        amount=ACTION_AMOUNT,
        currency="CNY",
        correlation_id=payment["callbackCorrelationId"],
        key=f"action-callback-{run.run_id}",
        sandbox=ACTION_SANDBOX,
        session=stable_uuid(run.run_id, "action-payment-session"),
        trace=stable_uuid(run.run_id, "action-payment-trace"),
        operation=hashlib.sha256(f"action:{run.run_id}".encode()).hexdigest(),
    )


def setup() -> ActiveRun:
    run = ActiveRun.create()
    try:
        os.umask(0o077)
        run_command(
            ("./scripts/init_local.sh",),
            env={**os.environ, "ENV_FILE": str(run.env_file)},
            timeout=30,
        )
        if not private_mode(run.env_file):
            raise DemoError("runtime environment file is not mode 0600")
        env_values = read_env(run.env_file)
        private = {
            **env_values,
            "DEMO_USER_PASSWORD": secrets.token_hex(24),
            "DEMO_OTHER_PASSWORD": secrets.token_hex(24),
            "AGENT_SERVICE_CLIENT_SECRET": secrets.token_hex(24),
            "COMMERCE_SERVICE_CLIENT_SECRET": secrets.token_hex(24),
            "EVALUATION_CLIENT_SECRET": secrets.token_hex(24),
            "EVALUATION_MANAGER_SECRET": secrets.token_hex(24),
            "MOCK_PAYMENT_KEY_ID": f"cb151-{secrets.token_hex(8)}",
            "MOCK_PAYMENT_SECRET": secrets.token_hex(32),
            "KNOWLEDGE_SNAPSHOT_SECRET": secrets.token_hex(24),
        }
        write_json(run.private_file, private, mode=0o600)
        write_private_text(
            run.auth_secrets_file,
            f"spring.datasource.password={private['MYSQL_AUTH_APP_PASSWORD']}\n",
        )
        write_private_text(
            run.commerce_secrets_file,
            "\n".join(
                (
                    f"spring.datasource.password={private['MYSQL_COMMERCE_APP_PASSWORD']}",
                    f"citybuddy.evaluation.auth-client-secret={private['COMMERCE_SERVICE_CLIENT_SECRET']}",
                    f"citybuddy.evaluation.management-client-secret={private['EVALUATION_MANAGER_SECRET']}",
                    f"citybuddy.knowledge-snapshot.client-secret={private['KNOWLEDGE_SNAPSHOT_SECRET']}",
                    f"citybuddy.mock-payment.callback-secret={private['MOCK_PAYMENT_SECRET']}",
                    "",
                )
            ),
        )
        generate_key(
            run.run_directory / "current-private.pem", run.run_directory / "current-public.pem"
        )
        run_command(
            (
                "./mvnw",
                "-q",
                "-pl",
                "auth-service,commerce-service",
                "-am",
                "-DskipTests",
                "package",
            ),
            timeout=300,
        )
        run_command(
            (
                "make",
                f"ENV_FILE={run.env_file}",
                f"COMPOSE_PROJECT_NAME={run.project}",
                "COMPOSE_WAIT_TIMEOUT=120",
                "up",
            ),
            timeout=900,
        )
        compose_topics(run)
        ports: dict[str, int] = {
            "mysql": compose_port(run, "mysql", 3306),
            "redisCommerce": compose_port(run, "redis-commerce", 6379),
            "redisSupport": compose_port(run, "redis-support", 6379),
            "elasticsearch": compose_port(run, "elasticsearch", 9200),
            "rocketmq": compose_port(run, "rocketmq-broker-proxy", 8081),
        }
        fixtures = {
            "standardProduct": KNOWLEDGE_PRODUCT_ID,
            "seckillProduct": KNOWLEDGE_PRODUCT_ID,
            "seckillActivity": stable_id(run.run_id, "seckill-activity"),
            "faq": KNOWLEDGE_REFUND_FAQ_ID,
            "faqDelivery": KNOWLEDGE_DELIVERY_FAQ_ID,
            "faqCommand": stable_id(run.run_id, "faq-publish"),
            "faqDeliveryCommand": stable_id(run.run_id, "faq-delivery-publish"),
        }
        manifest: dict[str, Any] = {
            "baseUrls": {},
            "fixtures": {
                **fixtures,
                "actionOrder": ACTION_ORDER_ID,
                "actionSandbox": ACTION_SANDBOX,
            },
            "ports": ports,
            "containers": {},
            "processes": {},
            "project": run.project,
            "runId": run.run_id,
            "schemaVersion": SCHEMA_VERSION,
        }
        write_json(run.manifest_file, manifest)
        seed_runtime(run, private, fixtures)
        auth_container, ports["auth"] = start_auth(run, private, ports["mysql"])
        manifest["containers"]["auth"] = auth_container
        manifest["baseUrls"]["auth"] = f"http://127.0.0.1:{ports['auth']}"
        write_json(run.manifest_file, manifest)
        commerce_container, ports["commerce"] = start_commerce(run, private, ports)
        manifest["containers"]["commerce"] = commerce_container
        manifest["baseUrls"]["commerce"] = f"http://127.0.0.1:{ports['commerce']}"
        write_json(run.manifest_file, manifest)
        evaluation_container, ports["evaluationCommerce"] = start_evaluation_commerce(run, private)
        manifest["containers"]["evaluationCommerce"] = evaluation_container
        manifest["baseUrls"]["evaluationCommerce"] = (
            f"http://127.0.0.1:{ports['evaluationCommerce']}"
        )
        write_json(run.manifest_file, manifest)
        model_pid, ports["model"] = start_model(run, ports["evaluationCommerce"])
        manifest["processes"]["fakeLlm"] = {
            "marker": "fake_litellm_server.py",
            "pid": model_pid,
        }
        manifest["baseUrls"]["fakeLlm"] = f"http://127.0.0.1:{ports['model']}"
        write_json(run.manifest_file, manifest)
        rebuild_knowledge(run, private, ports)
        compose(
            run,
            "--profile",
            "application",
            "up",
            "--detach",
            "--wait",
            "--wait-timeout",
            "90",
            "knowledge-indexer",
            timeout=180,
        )
        agent_pid, ports["agent"] = start_agent(run, private, ports)
        manifest["processes"]["agent"] = {"marker": "citybuddy-agent", "pid": agent_pid}
        manifest["baseUrls"].update(
            {
                "agent": f"http://127.0.0.1:{ports['agent']}",
                "webEnv": {
                    "CITYBUDDY_AGENT_TARGET": f"http://127.0.0.1:{ports['agent']}",
                    "CITYBUDDY_AUTH_TARGET": f"http://127.0.0.1:{ports['auth']}",
                    "CITYBUDDY_COMMERCE_TARGET": f"http://127.0.0.1:{ports['commerce']}",
                },
            }
        )
        manifest["ports"] = ports
        write_json(run.manifest_file, manifest)
        redis_client(run).set(
            f"commerce:seckill:activity:{fixtures['seckillActivity']}",
            json.dumps(
                {
                    "activityId": fixtures["seckillActivity"],
                    "projectionVersion": 1,
                    "startsAt": "2020-01-01T00:00:00Z",
                    "endsAt": "2037-01-01T00:00:00Z",
                    "state": "ACTIVE",
                    "remainingQuota": 5,
                },
                separators=(",", ":"),
            ),
        )
        initialize_action_fixture(run)
        capture_fault_baseline(run)
        check(emit_result=False)
        emit(
            "setup",
            "passed",
            manifest=str(run.manifest_file),
            project=run.project,
            runId=run.run_id,
        )
        return run
    except BaseException as error:
        try:
            cleanup_run(run, remove=False)
            write_json(
                run.artifacts / "setup-failure.json",
                {
                    "failureClass": "setup",
                    "resourceCleanup": "passed",
                    "runId": run.run_id,
                },
            )
        except Exception as cleanup_error:
            raise DemoError(
                f"setup failed and bounded cleanup also failed: {cleanup_error}"
            ) from error
        raise


def login(run: ActiveRun, *, other: bool = False) -> str:
    private = run.private()
    auth = run.manifest()["baseUrls"]["auth"]
    suffix = "OTHER" if other else "USER"
    identifier = "cb151-other-user" if other else "cb151-user"
    response = request(
        "POST",
        f"{auth}/auth/login",
        expected=200,
        body={"loginIdentifier": identifier, "password": private[f"DEMO_{suffix}_PASSWORD"]},
    )
    return str(response.body["accessToken"])


def callback(
    run: ActiveRun,
    *,
    order_id: str,
    amount: int,
    currency: str,
    correlation_id: str,
    key: str,
    sandbox: str | None = None,
    session: str | None = None,
    trace: str | None = None,
    operation: str | None = None,
) -> Response:
    private = run.private()
    base_urls = run.manifest()["baseUrls"]
    commerce = base_urls["evaluationCommerce"] if sandbox is not None else base_urls["commerce"]
    event_id = stable_uuid(run.run_id, key)
    body = {
        "callbackEventId": event_id,
        "callbackCorrelationId": correlation_id,
        "orderId": order_id,
        "amountMinor": amount,
        "currency": currency,
        "outcome": "SUCCEEDED",
        "sandboxId": sandbox,
        "supportSessionId": session,
        "traceId": trace,
        "operationId": operation,
    }
    timestamp = str(int(time.time()))
    canonical = "\n".join(
        (
            private["MOCK_PAYMENT_KEY_ID"],
            timestamp,
            key,
            event_id,
            correlation_id,
            order_id,
            str(amount),
            currency,
            "SUCCEEDED",
            sandbox or "",
            session or "",
            trace or "",
            operation or "",
        )
    )
    signature = hmac.new(
        private["MOCK_PAYMENT_SECRET"].encode(), canonical.encode(), hashlib.sha256
    ).hexdigest()
    return request(
        "POST",
        f"{commerce}/internal/mock-payments/callback",
        expected=200,
        headers={
            "Idempotency-Key": key,
            "X-Mock-Payment-Key-Id": private["MOCK_PAYMENT_KEY_ID"],
            "X-Mock-Payment-Timestamp": timestamp,
            "X-Mock-Payment-Signature": signature,
        },
        body=body,
    )


def agent_session(run: ActiveRun, token: str, *, sandbox: str | None = None) -> str:
    headers = {"Authorization": f"Bearer {token}"}
    if sandbox is not None:
        headers["X-Eval-Sandbox-Id"] = sandbox
    response = request(
        "POST",
        f"{run.manifest()['baseUrls']['agent']}/api/sessions",
        expected=201,
        headers=headers,
        body={},
    )
    return str(response.body["sessionId"])


def agent_chat(
    run: ActiveRun,
    token: str,
    session_id: str,
    key: str,
    message: str,
    *,
    sandbox: str | None = None,
    expected: int | Sequence[int] = 200,
) -> Response:
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Session-Id": session_id,
        "Idempotency-Key": key,
    }
    if sandbox is not None:
        headers["X-Eval-Sandbox-Id"] = sandbox
    return request(
        "POST",
        f"{run.manifest()['baseUrls']['agent']}/api/chat",
        expected=expected,
        headers=headers,
        body={"message": message},
        timeout=15,
    )


def pending_id_for_turn(run: ActiveRun, turn_id: str) -> str:
    value = mysql_query(
        run,
        "cs_db",
        f"SELECT pending_action_id FROM pending_action_reference WHERE source_turn_id = '{turn_id}';",
    )
    if not value:
        raise DemoError("PendingAction reference is missing")
    return value


def wait_for_action_expiry(run: ActiveRun, pending_id: str, maximum_seconds: int = 75) -> None:
    deadline = time.monotonic() + maximum_seconds
    while time.monotonic() < deadline:
        expired = mysql_query(
            run,
            "cs_db",
            f"SELECT expires_at <= CURRENT_TIMESTAMP(6) FROM pending_action_reference WHERE pending_action_id = '{pending_id}';",
        )
        if expired == "1":
            return
        time.sleep(0.25)
    raise DemoError("PendingAction did not reach its recorded database expiry deadline")


def durable_count(run: ActiveRun, database: str, query: str, expected: int) -> None:
    value = mysql_query(run, database, query)
    if value != str(expected):
        raise DemoError(f"durable assertion expected {expected}, got {value or 'empty'}")


def decode_catalog_cache(value: str | None) -> dict[str, Any]:
    try:
        payload = json.loads(value) if value is not None else None
    except json.JSONDecodeError as error:
        raise DemoError("catalog cache control returned malformed JSON") from error
    if not isinstance(payload, dict):
        raise DemoError("catalog cache control omitted its authoritative refill")
    return payload


def timed_step(steps: list[dict[str, Any]], step_id: str, operation: Any) -> Any:
    started = time.monotonic()
    result = operation()
    steps.append(
        {
            "durationMs": int((time.monotonic() - started) * 1000),
            "id": step_id,
            "status": "passed",
        }
    )
    return result


def replay_demo(run: ActiveRun, previous: dict[str, Any]) -> dict[str, Any]:
    manifest = run.manifest()
    commerce = manifest["baseUrls"]["commerce"]
    token = login(run)
    steps: list[dict[str, Any]] = []
    order = timed_step(
        steps,
        "standard-order-replay",
        lambda: request(
            "POST",
            f"{commerce}/api/orders",
            expected=200,
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": f"order-{run.run_id}",
            },
            body={
                "productId": manifest["fixtures"]["standardProduct"],
                "quantity": 2,
                "expectedProductVersion": 1,
            },
        ),
    )
    if not order.body.get("replayed") or order.body["orderId"] != previous["orderId"]:
        raise DemoError("standard-order repeat did not replay exact durable truth")
    reservation = timed_step(
        steps,
        "seckill-replay",
        lambda: request(
            "POST",
            f"{commerce}/api/seckill/activities/{manifest['fixtures']['seckillActivity']}/reservations",
            expected=(200, 201, 202),
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": f"seckill-{run.run_id}",
            },
            body={"quantity": 1, "expectedActivityVersion": 1},
        ),
    )
    if reservation.body["reservationId"] != previous["reservationId"]:
        raise DemoError("seckill repeat diverged from the durable reservation")
    timed_step(
        steps,
        "chat-replay",
        lambda: agent_chat(
            run,
            token,
            previous["ordinarySessionId"],
            f"ordinary-{run.run_id}",
            "hello from the bounded CityBuddy demo",
        ),
    )
    result = {**previous, "repeat": True, "steps": steps}
    write_json(run.artifacts / "demo-repeat.json", result)
    emit("demo", "passed", repeat=True, runId=run.run_id, steps=steps)
    return result


def demo() -> dict[str, Any]:
    run = ActiveRun.load()
    previous_path = run.artifacts / "demo.json"
    if previous_path.exists():
        previous = read_json(previous_path)
        if not isinstance(previous, dict):
            raise DemoError("demo artifact has an invalid schema")
        return replay_demo(run, previous)

    manifest = run.manifest()
    private = run.private()
    commerce = manifest["baseUrls"]["commerce"]
    steps: list[dict[str, Any]] = []
    token = timed_step(steps, "login", lambda: login(run))
    other_token = login(run, other=True)
    products = timed_step(
        steps,
        "product-list",
        lambda: request(
            "GET",
            f"{commerce}/api/products",
            expected=200,
            headers={"Authorization": f"Bearer {token}"},
        ),
    )
    if manifest["fixtures"]["standardProduct"] not in {
        item.get("productId") for item in products.body
    }:
        raise DemoError("public product list omitted the authoritative demo fixture")

    order = timed_step(
        steps,
        "standard-order",
        lambda: request(
            "POST",
            f"{commerce}/api/orders",
            expected=201,
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": f"order-{run.run_id}",
            },
            body={
                "productId": manifest["fixtures"]["standardProduct"],
                "quantity": 2,
                "expectedProductVersion": 1,
            },
        ),
    ).body
    order_id = str(order["orderId"])
    durable_count(
        run,
        "commerce_db",
        f"SELECT COUNT(*) FROM standard_order WHERE order_id = '{order_id}' AND user_subject = 'cb151-user' AND total_price_minor = 1500;",
        1,
    )

    reservation = timed_step(
        steps,
        "seckill-submit",
        lambda: request(
            "POST",
            f"{commerce}/api/seckill/activities/{manifest['fixtures']['seckillActivity']}/reservations",
            expected=(201, 202),
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": f"seckill-{run.run_id}",
            },
            body={"quantity": 1, "expectedActivityVersion": 1},
        ),
    ).body
    reservation_id = str(reservation["reservationId"])
    timed_step(
        steps,
        "reservation-owner-status",
        lambda: request(
            "GET",
            f"{commerce}/api/reservations/{reservation_id}",
            expected=200,
            headers={"Authorization": f"Bearer {token}"},
        ),
    )
    timed_step(
        steps,
        "reservation-owner-denial",
        lambda: request(
            "GET",
            f"{commerce}/api/reservations/{reservation_id}",
            expected=404,
            headers={"Authorization": f"Bearer {other_token}"},
        ),
    )

    payment = timed_step(
        steps,
        "mock-payment-start",
        lambda: request(
            "POST",
            f"{commerce}/api/orders/{order_id}/mock-payment",
            expected=201,
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": f"payment-{run.run_id}",
            },
            body={"amountMinor": 1500, "currency": "CNY"},
        ),
    ).body
    payment_callback = timed_step(
        steps,
        "mock-payment-callback",
        lambda: callback(
            run,
            order_id=order_id,
            amount=1500,
            currency="CNY",
            correlation_id=str(payment["callbackCorrelationId"]),
            key=f"callback-{run.run_id}",
        ),
    ).body
    if payment_callback["state"] != "SUCCEEDED":
        raise DemoError("mock payment did not reach authoritative paid truth")

    refund = timed_step(
        steps,
        "refund-request",
        lambda: request(
            "POST",
            f"{commerce}/api/orders/{order_id}/refunds",
            expected=201,
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": f"refund-{run.run_id}",
            },
            body={"amountMinor": 500, "currency": "CNY"},
        ),
    ).body
    timed_step(
        steps,
        "refund-status",
        lambda: request(
            "GET",
            f"{commerce}/api/refunds/{refund['refundId']}",
            expected=200,
            headers={"Authorization": f"Bearer {token}"},
        ),
    )

    ordinary_session = timed_step(steps, "ordinary-session", lambda: agent_session(run, token))
    timed_step(
        steps,
        "ordinary-chat",
        lambda: agent_chat(
            run,
            token,
            ordinary_session,
            f"ordinary-{run.run_id}",
            "hello from the bounded CityBuddy demo",
        ),
    )
    sufficient = timed_step(
        steps,
        "rag-sufficient",
        lambda: agent_chat(
            run,
            token,
            ordinary_session,
            f"rag-sufficient-{run.run_id}",
            "retrieval-sufficient refund policy",
        ),
    ).body
    if sufficient["outcome"] != "completed" or not sufficient["citations"]:
        raise DemoError("RAG sufficient path omitted its public evidence references")
    durable_count(
        run,
        "cs_db",
        f"SELECT COUNT(*) FROM support_turn t JOIN retrieval_decision d ON d.turn_id = t.turn_id WHERE t.correlation_key = 'rag-sufficient-{run.run_id}' AND t.state = 'COMPLETED' AND d.sufficiency_outcome = 'SUFFICIENT' AND d.reason_code = 'sufficient' AND d.evidence_count = (SELECT COUNT(*) FROM retrieval_evidence e WHERE e.decision_id = d.decision_id) AND d.evidence_count > 0;",
        1,
    )
    insufficient = timed_step(
        steps,
        "rag-insufficient",
        lambda: agent_chat(
            run,
            token,
            ordinary_session,
            f"rag-insufficient-{run.run_id}",
            "retrieval-insufficient refund policy",
        ),
    ).body
    if insufficient["outcome"] != "retrieval_denied" or insufficient["citations"]:
        raise DemoError("RAG insufficient path fabricated evidence or the wrong outcome")

    action_token = private["ACTION_TOKEN"]
    decline_session = timed_step(
        steps,
        "action-session",
        lambda: agent_session(run, action_token, sandbox=ACTION_SANDBOX),
    )
    prepared = timed_step(
        steps,
        "action-prepare",
        lambda: agent_chat(
            run,
            action_token,
            decline_session,
            f"action-prepare-{run.run_id}",
            "action-prepare",
            sandbox=ACTION_SANDBOX,
        ),
    ).body
    if prepared["outcome"] != "action_pending":
        raise DemoError("PendingAction prepare did not produce pending truth")
    pending_id = pending_id_for_turn(run, str(prepared["turnId"]))
    timed_step(
        steps,
        "action-confirmation-unavailable",
        lambda: agent_chat(
            run,
            action_token,
            decline_session,
            f"action-confirm-{run.run_id}",
            "confirm",
            sandbox=ACTION_SANDBOX,
            expected=409,
        ),
    )
    clarification = timed_step(
        steps,
        "action-clarification",
        lambda: agent_chat(
            run,
            action_token,
            decline_session,
            f"action-clarify-{run.run_id}",
            "maybe change it",
            sandbox=ACTION_SANDBOX,
        ),
    ).body
    if clarification["outcome"] != "action_clarification":
        raise DemoError("PendingAction clarification diverged")
    declined = timed_step(
        steps,
        "action-decline",
        lambda: agent_chat(
            run,
            action_token,
            decline_session,
            f"action-decline-{run.run_id}",
            "decline",
            sandbox=ACTION_SANDBOX,
        ),
    ).body
    if declined["outcome"] != "action_declined":
        raise DemoError("PendingAction decline diverged")

    expiry_session = agent_session(run, action_token, sandbox=ACTION_SANDBOX)
    expiry_prepare = agent_chat(
        run,
        action_token,
        expiry_session,
        f"expiry-prepare-{run.run_id}",
        "action-prepare",
        sandbox=ACTION_SANDBOX,
    ).body
    expiry_pending_id = pending_id_for_turn(run, str(expiry_prepare["turnId"]))
    started = time.monotonic()
    wait_for_action_expiry(run, expiry_pending_id)
    expired = agent_chat(
        run,
        action_token,
        expiry_session,
        f"expiry-resolve-{run.run_id}",
        "anything",
        sandbox=ACTION_SANDBOX,
    ).body
    steps.append(
        {
            "durationMs": int((time.monotonic() - started) * 1000),
            "id": "action-expiry",
            "status": "passed",
        }
    )
    if expired["outcome"] != "action_expired":
        raise DemoError("PendingAction expiry diverged")
    durable_count(
        run,
        "cs_db",
        f"SELECT COUNT(*) FROM support_event WHERE session_id IN ('{decline_session}', '{expiry_session}') AND event_type = 'ACTION_RECEIPT';",
        0,
    )

    result = {
        "declinedPendingActionId": pending_id,
        "expiryPendingActionId": expiry_pending_id,
        "ordinarySessionId": ordinary_session,
        "orderId": order_id,
        "paymentAttemptId": payment["attemptId"],
        "refundId": refund["refundId"],
        "repeat": False,
        "reservationId": reservation_id,
        "runId": run.run_id,
        "steps": steps,
    }
    write_json(previous_path, result)
    emit("demo", "passed", repeat=False, runId=run.run_id, steps=steps)
    return result


@contextlib.contextmanager
def injected_sql_fault(run: ActiveRun, create: str, restore: str) -> Iterator[None]:
    mysql_query(run, "", create)
    try:
        yield
    finally:
        mysql_query(run, "", restore)


def fault_step(results: list[dict[str, Any]], drill_id: str, operation: Any) -> Any:
    started = time.monotonic()
    result = operation()
    results.append(
        {
            "durationMs": int((time.monotonic() - started) * 1000),
            "id": drill_id,
            "restored": True,
            "status": "passed",
        }
    )
    return result


def action_prepare_for_fault(run: ActiveRun, suffix: str) -> tuple[str, str]:
    private = run.private()
    token = private["ACTION_TOKEN"]
    session = agent_session(run, token, sandbox=ACTION_SANDBOX)
    response = agent_chat(
        run,
        token,
        session,
        f"fault-prepare-{suffix}-{run.run_id}",
        "action-prepare",
        sandbox=ACTION_SANDBOX,
    ).body
    if response["outcome"] != "action_pending":
        raise DemoError("fault control could not create PendingAction")
    return session, pending_id_for_turn(run, str(response["turnId"]))


def drill_owner_denial(run: ActiveRun, demo_result: Mapping[str, Any]) -> None:
    other = login(run, other=True)
    reservation = str(demo_result["reservationId"])
    before = mysql_query(
        run,
        "commerce_db",
        f"SELECT CONCAT(state, ':', projection_version) FROM seckill_reservation WHERE reservation_id = '{reservation}';",
    )
    request(
        "GET",
        f"{run.manifest()['baseUrls']['commerce']}/api/reservations/{reservation}",
        expected=404,
        headers={"Authorization": f"Bearer {other}"},
    )
    after = mysql_query(
        run,
        "commerce_db",
        f"SELECT CONCAT(state, ':', projection_version) FROM seckill_reservation WHERE reservation_id = '{reservation}';",
    )
    if before != after:
        raise DemoError("owner denial changed authoritative reservation truth")
    owner = login(run)
    request(
        "GET",
        f"{run.manifest()['baseUrls']['commerce']}/api/reservations/{reservation}",
        expected=200,
        headers={"Authorization": f"Bearer {owner}"},
    )


def drill_catalog_cache(run: ActiveRun) -> None:
    manifest = run.manifest()
    product = manifest["fixtures"]["standardProduct"]
    key = f"catalog:product:{product}:1"
    cache = redis_client(run)
    authoritative_before = mysql_query(
        run,
        "commerce_db",
        f"SELECT CONCAT(product_id, ':', price_minor, ':', stock_quantity, ':', publication_version) FROM product WHERE product_id = '{product}';",
    )
    cache.set(
        key,
        json.dumps(
            {
                "available": True,
                "currency": "CNY",
                "description": "forged derived value",
                "name": "forged",
                "priceMinor": 1,
                "productId": "cb151-wrong-product",
                "publicationVersion": 1,
                "stockQuantity": 1,
            },
            separators=(",", ":"),
        ),
        ex=30,
    )
    token = login(run)
    public = request(
        "GET",
        f"{manifest['baseUrls']['commerce']}/api/products/{product}",
        expected=200,
        headers={"Authorization": f"Bearer {token}"},
    ).body
    cached_product = decode_catalog_cache(cast(str | None, cache.get(key)))
    authoritative_after = mysql_query(
        run,
        "commerce_db",
        f"SELECT CONCAT(product_id, ':', price_minor, ':', stock_quantity, ':', publication_version) FROM product WHERE product_id = '{product}';",
    )
    if (
        public["productId"] != product
        or public["priceMinor"] != 750
        or not isinstance(cached_product, dict)
        or cached_product.get("productId") != product
        or cached_product.get("priceMinor") != 750
        or authoritative_after != authoritative_before
    ):
        raise DemoError("catalog cache fault did not fall back to authoritative MySQL truth")
    request(
        "GET",
        f"{manifest['baseUrls']['commerce']}/api/products/{product}",
        expected=200,
        headers={"Authorization": f"Bearer {token}"},
    )


def drill_seckill_convergence(run: ActiveRun, demo_result: Mapping[str, Any]) -> None:
    manifest = run.manifest()
    token = login(run)
    response = request(
        "POST",
        f"{manifest['baseUrls']['commerce']}/api/seckill/activities/{manifest['fixtures']['seckillActivity']}/reservations",
        expected=(200, 201, 202),
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": f"seckill-{run.run_id}",
        },
        body={"quantity": 1, "expectedActivityVersion": 1},
    ).body
    if response["reservationId"] != demo_result["reservationId"]:
        raise DemoError("seckill replay did not converge on the original reservation")
    durable_count(
        run,
        "commerce_db",
        f"SELECT COUNT(*) FROM seckill_reservation WHERE reservation_id = '{response['reservationId']}';",
        1,
    )
    request(
        "GET",
        f"{manifest['baseUrls']['commerce']}/api/reservations/{response['reservationId']}",
        expected=200,
        headers={"Authorization": f"Bearer {token}"},
    )


def drill_payment_refund(run: ActiveRun, demo_result: Mapping[str, Any]) -> None:
    manifest = run.manifest()
    commerce = manifest["baseUrls"]["commerce"]
    token = login(run)
    order_id = str(demo_result["orderId"])
    payment_replay = request(
        "POST",
        f"{commerce}/api/orders/{order_id}/mock-payment",
        expected=200,
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": f"payment-{run.run_id}",
        },
        body={"amountMinor": 1500, "currency": "CNY"},
    ).body
    if not payment_replay["replayed"]:
        raise DemoError("payment replay did not return committed truth")
    request(
        "POST",
        f"{commerce}/api/orders/{order_id}/mock-payment",
        expected=409,
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": f"payment-{run.run_id}",
        },
        body={"amountMinor": 1499, "currency": "CNY"},
    )
    refund_replay = request(
        "POST",
        f"{commerce}/api/orders/{order_id}/refunds",
        expected=200,
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": f"refund-{run.run_id}",
        },
        body={"amountMinor": 500, "currency": "CNY"},
    ).body
    if not refund_replay["replayed"]:
        raise DemoError("refund replay did not return committed truth")
    request(
        "POST",
        f"{commerce}/api/orders/{order_id}/refunds",
        expected=409,
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": f"refund-{run.run_id}",
        },
        body={"amountMinor": 501, "currency": "CNY"},
    )
    durable_count(
        run,
        "commerce_db",
        f"SELECT COUNT(*) FROM mock_payment_attempt WHERE order_id = '{order_id}';",
        1,
    )
    durable_count(
        run,
        "commerce_db",
        f"SELECT COUNT(*) FROM mock_refund WHERE order_id = '{order_id}';",
        1,
    )


def drill_rag(run: ActiveRun) -> None:
    manifest = run.manifest()
    token = login(run)
    session = agent_session(run, token)
    insufficient = agent_chat(
        run,
        token,
        session,
        f"fault-rag-insufficient-{run.run_id}",
        "retrieval-insufficient refund policy",
    ).body
    if insufficient["outcome"] != "retrieval_denied" or insufficient["citations"]:
        raise DemoError("RAG insufficiency fault fabricated evidence")
    original_elasticsearch_port = int(manifest["ports"]["elasticsearch"])
    alias_url = f"http://127.0.0.1:{original_elasticsearch_port}/_alias/knowledge_docs_read"
    before = json.dumps(request("GET", alias_url, expected=200).body, sort_keys=True)
    compose(run, "stop", "elasticsearch", timeout=60)
    try:
        unavailable = agent_chat(
            run,
            token,
            session,
            f"fault-rag-unavailable-{run.run_id}",
            "retrieval-sufficient dependency unavailable refund policy",
            expected=(200, 503),
        )
        if unavailable.status == 200 and unavailable.body.get("outcome") != "retrieval_denied":
            raise DemoError("RAG dependency failure was not bounded")
    finally:
        compose(run, *ELASTICSEARCH_RESTORE_ARGS, timeout=120)
    restored_elasticsearch_port = compose_port(run, "elasticsearch", 9200)
    if restored_elasticsearch_port != original_elasticsearch_port:
        ports = {name: int(port) for name, port in manifest["ports"].items()}
        ports["elasticsearch"] = restored_elasticsearch_port
        rebind_agent_to_dependency_ports(run, manifest, ports)
    alias_url = f"http://127.0.0.1:{restored_elasticsearch_port}/_alias/knowledge_docs_read"
    after = json.dumps(request("GET", alias_url, expected=200).body, sort_keys=True)
    if before != after:
        raise DemoError("Elasticsearch restoration changed the exact alias inventory")
    control = agent_chat(
        run,
        token,
        session,
        f"fault-rag-control-{run.run_id}",
        "retrieval-sufficient restored refund policy",
    ).body
    if control["outcome"] != "completed" or not control["citations"]:
        raise DemoError("RAG post-restoration control did not recover")


def drill_provider(run: ActiveRun) -> None:
    token = login(run)
    session = agent_session(run, token)
    denied = agent_chat(
        run,
        token,
        session,
        f"fault-provider-{run.run_id}",
        "provider-failure",
    ).body
    if denied["outcome"] != "provider_denied":
        raise DemoError("chat provider failure did not produce the bounded denial")
    control = agent_chat(
        run,
        token,
        session,
        f"fault-provider-control-{run.run_id}",
        "ordinary restored chat",
    ).body
    if control["outcome"] != "completed":
        raise DemoError("chat provider post-fault control did not recover")


def drill_action_prepare(run: ActiveRun) -> None:
    private = run.private()
    token = private["ACTION_TOKEN"]
    session = agent_session(run, token, sandbox=ACTION_SANDBOX)
    with injected_sql_fault(
        run,
        "CREATE TRIGGER cs_db.cb151_fail_reference_insert BEFORE INSERT ON cs_db.pending_action_reference FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'controlled cb151 reference failure';",
        "DROP TRIGGER cs_db.cb151_fail_reference_insert;",
    ):
        agent_chat(
            run,
            token,
            session,
            f"fault-reference-{run.run_id}",
            "action-prepare",
            sandbox=ACTION_SANDBOX,
            expected=503,
        )
        durable_count(
            run,
            "cs_db",
            f"SELECT COUNT(*) FROM pending_action_reference WHERE session_id = '{session}';",
            0,
        )
        durable_count(
            run,
            "cs_db",
            f"SELECT COUNT(*) FROM support_event WHERE session_id = '{session}' AND event_type = 'ACTION_PREPARED';",
            0,
        )
    assert_fault_baseline(run)
    action_prepare_for_fault(run, "reference-control")


def drill_action_decline(run: ActiveRun) -> None:
    private = run.private()
    token = private["ACTION_TOKEN"]
    session, pending = action_prepare_for_fault(run, "grant-decline")
    with injected_sql_fault(
        run,
        "REVOKE UPDATE (state, resolved_at, resolution_turn_id, resolution_trace_id) ON cs_db.pending_action_reference FROM 'agent_app'@'%';",
        "GRANT UPDATE (state, resolved_at, resolution_turn_id, resolution_trace_id) ON cs_db.pending_action_reference TO 'agent_app'@'%';",
    ):
        agent_chat(
            run,
            token,
            session,
            f"fault-decline-grant-{run.run_id}",
            "decline",
            sandbox=ACTION_SANDBOX,
            expected=503,
        )
        state = mysql_query(
            run,
            "cs_db",
            f"SELECT CONCAT(state, ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '{session}' AND event_type = 'ACTION_DECLINED')) FROM pending_action_reference WHERE pending_action_id = '{pending}';",
        )
        if state != "PENDING:0":
            raise DemoError("decline grant fault left partial local truth")
    assert_fault_baseline(run)
    control = agent_chat(
        run,
        token,
        session,
        f"fault-decline-control-{run.run_id}",
        "decline",
        sandbox=ACTION_SANDBOX,
    ).body
    if control["outcome"] != "action_declined":
        raise DemoError("decline grant restoration control failed")

    trigger_session, trigger_pending = action_prepare_for_fault(run, "trigger-decline")
    with injected_sql_fault(
        run,
        "CREATE TRIGGER cs_db.cb151_fail_decline_event BEFORE INSERT ON cs_db.support_event FOR EACH ROW SET NEW.sequence = IF(NEW.event_type = 'ACTION_DECLINED', 0, NEW.sequence);",
        "DROP TRIGGER cs_db.cb151_fail_decline_event;",
    ):
        agent_chat(
            run,
            token,
            trigger_session,
            f"fault-decline-trigger-{run.run_id}",
            "decline",
            sandbox=ACTION_SANDBOX,
            expected=503,
        )
        state = mysql_query(
            run,
            "cs_db",
            f"SELECT CONCAT(state, ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '{trigger_session}' AND event_type = 'ACTION_DECLINED')) FROM pending_action_reference WHERE pending_action_id = '{trigger_pending}';",
        )
        if state != "PENDING:0":
            raise DemoError("ACTION_DECLINED trigger fault left partial truth")
    assert_fault_baseline(run)
    control = agent_chat(
        run,
        token,
        trigger_session,
        f"fault-decline-trigger-control-{run.run_id}",
        "decline",
        sandbox=ACTION_SANDBOX,
    ).body
    if control["outcome"] != "action_declined":
        raise DemoError("ACTION_DECLINED trigger restoration control failed")


def drill_action_expiry(run: ActiveRun) -> None:
    private = run.private()
    token = private["ACTION_TOKEN"]
    session, pending = action_prepare_for_fault(run, "expiry")
    wait_for_action_expiry(run, pending)
    with injected_sql_fault(
        run,
        "CREATE TRIGGER cs_db.cb151_fail_expiry_event BEFORE INSERT ON cs_db.support_event FOR EACH ROW SET NEW.sequence = IF(NEW.event_type = 'ACTION_EXPIRED', 0, NEW.sequence);",
        "DROP TRIGGER cs_db.cb151_fail_expiry_event;",
    ):
        agent_chat(
            run,
            token,
            session,
            f"fault-expiry-trigger-{run.run_id}",
            "expire",
            sandbox=ACTION_SANDBOX,
            expected=503,
        )
        state = mysql_query(
            run,
            "cs_db",
            f"SELECT CONCAT(state, ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '{session}' AND event_type = 'ACTION_EXPIRED')) FROM pending_action_reference WHERE pending_action_id = '{pending}';",
        )
        if state != "PENDING:0":
            raise DemoError("ACTION_EXPIRED trigger fault left partial truth")
    assert_fault_baseline(run)
    control = agent_chat(
        run,
        token,
        session,
        f"fault-expiry-control-{run.run_id}",
        "anything",
        sandbox=ACTION_SANDBOX,
    ).body
    if control["outcome"] != "action_expired":
        raise DemoError("ACTION_EXPIRED trigger restoration control failed")


def faults() -> dict[str, Any]:
    run = ActiveRun.load()
    demo_path = run.artifacts / "demo.json"
    demo_result = demo() if not demo_path.exists() else read_json(demo_path)
    if not isinstance(demo_result, dict):
        raise DemoError("demo artifact has an invalid schema")
    results: list[dict[str, Any]] = []
    try:
        fault_step(
            results, "F1-identity-owner-denial", lambda: drill_owner_denial(run, demo_result)
        )
        fault_step(results, "F2-catalog-cache-authority", lambda: drill_catalog_cache(run))
        fault_step(
            results, "F3-seckill-convergence", lambda: drill_seckill_convergence(run, demo_result)
        )
        fault_step(
            results, "F4-payment-refund-replay", lambda: drill_payment_refund(run, demo_result)
        )
        fault_step(results, "F5-rag-boundaries", lambda: drill_rag(run))
        fault_step(results, "F6-chat-provider", lambda: drill_provider(run))
        fault_step(results, "F7a-action-prepare", lambda: drill_action_prepare(run))
        fault_step(results, "F7b-action-decline", lambda: drill_action_decline(run))
        fault_step(results, "F7c-action-expiry", lambda: drill_action_expiry(run))
    finally:
        restore_runtime_faults(run)
    assert_fault_baseline(run)
    result = {"drills": results, "runId": run.run_id}
    write_json(run.artifacts / "faults.json", result)
    emit("faults", "passed", drills=results, runId=run.run_id)
    return result


def demo_all() -> None:
    run = setup()
    cleanup_result: dict[str, Any] | None = None
    try:
        demo_result = demo()
        fault_result = faults()
        check_result = check(emit_result=False)
    finally:
        cleanup_result = cleanup_run(run, remove=True)
    emit(
        "all",
        "passed",
        cleanup=cleanup_result,
        demoSteps=len(demo_result["steps"]),
        faultDrills=len(fault_result["drills"]),
        runId=run.run_id,
        verification=check_result,
    )


def capture_fault_baseline(run: ActiveRun, *, persist: bool = True) -> dict[str, Any]:
    grants = mysql_query(run, "", "SHOW GRANTS FOR 'agent_app'@'%';")
    columns = mysql_query(
        run,
        "information_schema",
        """
SELECT CONCAT(column_name, ':', privilege_type)
FROM column_privileges
WHERE grantee = "'agent_app'@'%'"
  AND table_schema = 'cs_db' AND table_name = 'pending_action_reference'
ORDER BY column_name, privilege_type;
""",
    )
    trigger_names = ", ".join(f"'{name}'" for name in TRIGGERS)
    triggers = mysql_query(
        run,
        "information_schema",
        f"""
SELECT CONCAT(trigger_schema, '.', trigger_name)
FROM triggers
WHERE trigger_schema = 'cs_db' AND trigger_name IN ({trigger_names})
ORDER BY trigger_name;
""",
    )
    baseline = {
        "columnPrivileges": columns.splitlines() if columns else [],
        "grantsSha256": hashlib.sha256(grants.encode()).hexdigest(),
        "triggerInventory": triggers.splitlines() if triggers else [],
    }
    if persist:
        write_json(run.artifacts / "fault-baseline.json", baseline)
    return baseline


def restore_runtime_faults(run: ActiveRun) -> None:
    statements = [f"DROP TRIGGER IF EXISTS cs_db.{name};" for name in TRIGGERS]
    statements.append(
        "GRANT UPDATE (state, resolved_at, resolution_turn_id, resolution_trace_id) "
        "ON cs_db.pending_action_reference TO 'agent_app'@'%';"
    )
    mysql_query(run, "", "\n".join(statements))


def assert_fault_baseline(run: ActiveRun) -> None:
    expected = read_json(run.artifacts / "fault-baseline.json")
    actual = capture_fault_baseline(run, persist=False)
    if actual != expected:
        raise DemoError("runtime grant/trigger restoration did not match the exact baseline")


def process_command(pid: int) -> str:
    return run_command(("ps", "-p", str(pid), "-o", "command="), timeout=5)


def stop_process(pid: int, marker: str) -> None:
    if not process_exists(pid):
        return
    command = process_command(pid)
    if marker not in command:
        raise DemoError(f"refusing to stop stale or unrelated PID {pid}")
    try:
        group = os.getpgid(pid)
    except ProcessLookupError:
        return
    if group != pid:
        raise DemoError(f"refusing to stop non-isolated process group for PID {pid}")
    os.killpg(group, signal.SIGTERM)
    deadline = time.monotonic() + 10
    while process_exists(pid) and time.monotonic() < deadline:
        time.sleep(0.1)
    if process_exists(pid):
        os.killpg(group, signal.SIGKILL)


def cleanup_run(run: ActiveRun, *, remove: bool) -> dict[str, Any]:
    run.validate_scope()
    errors: list[str] = []
    if (run.artifacts / "fault-baseline.json").exists():
        restore_runtime_faults(run)
        assert_fault_baseline(run)
    if run.manifest_file.exists():
        manifest = run.manifest()
        for container in manifest.get("containers", {}).values():
            try:
                stop_runtime_container(run, str(container))
            except DemoError as error:
                errors.append(str(error))
        for process in manifest.get("processes", {}).values():
            try:
                stop_process(int(process["pid"]), str(process["marker"]))
            except DemoError as error:
                errors.append(str(error))
    if run.env_file.exists():
        try:
            compose(run, "down", "--volumes", "--remove-orphans", timeout=180)
        except DemoError as error:
            errors.append(str(error))
    if errors:
        raise DemoError("cleanup did not finish: " + "; ".join(errors))
    result = {"projectRemoved": True, "runDirectoryRemoved": remove, "runId": run.run_id}
    if remove:
        run.validate_scope()
        shutil.rmtree(run.run_directory)
        with contextlib.suppress(FileNotFoundError):
            ACTIVE_STATE.unlink()
    return result


def cleanup(confirmation: str) -> None:
    run = ActiveRun.load()
    run.require_confirmation(confirmation)
    result = cleanup_run(run, remove=True)
    emit("cleanup", "passed", cleanup=result, runId=run.run_id)


def reset(confirmation: str) -> None:
    current = ActiveRun.load()
    current.require_confirmation(confirmation)
    cleanup_result = cleanup_run(current, remove=True)
    replacement = setup()
    emit(
        "reset",
        "passed",
        cleanup=cleanup_result,
        previousRunId=current.run_id,
        runId=replacement.run_id,
    )


def status() -> None:
    try:
        run = ActiveRun.load()
    except DemoError:
        emit("status", "inactive")
        return
    manifest = run.manifest()
    processes = {
        name: process_exists(int(details["pid"])) for name, details in manifest["processes"].items()
    }
    containers = {
        name: runtime_container_running(run, str(container))
        for name, container in manifest.get("containers", {}).items()
    }
    emit(
        "status",
        "active" if all((*processes.values(), *containers.values())) else "degraded",
        baseUrls=manifest["baseUrls"],
        containers=containers,
        processes=processes,
        project=run.project,
        runId=run.run_id,
    )


def check(*, emit_result: bool = True) -> dict[str, Any]:
    run = ActiveRun.load()
    manifest = run.manifest()
    private = run.private()
    modes = {
        path.name: stat.S_IMODE(path.stat().st_mode)
        for path in (
            run.env_file,
            run.private_file,
            run.auth_secrets_file,
            run.commerce_secrets_file,
            run.run_directory / "current-private.pem",
        )
    }
    if any(mode != 0o600 for mode in modes.values()):
        raise DemoError("one or more secret-bearing runtime files are not mode 0600")
    for name, details in manifest["processes"].items():
        pid = int(details["pid"])
        if not process_exists(pid) or str(details["marker"]) not in process_command(pid):
            raise DemoError(f"runtime process is not healthy: {name}")
    for name, container in manifest.get("containers", {}).items():
        if not runtime_container_running(run, str(container)):
            raise DemoError(f"runtime container is not healthy: {name}")
    for service in COMPOSE_SERVICES:
        state = compose(run, "ps", "--status", "running", "--services", service, timeout=30)
        if service not in state.splitlines():
            raise DemoError(f"Compose service is not running: {service}")
    readiness = {
        "auth": request("GET", f"{manifest['baseUrls']['auth']}/auth/jwks", expected=200).status,
        "metrics": request(
            "GET", f"{manifest['baseUrls']['agent']}/internal/metrics/prometheus", expected=200
        ).status,
        "model": request(
            "GET", f"{manifest['baseUrls']['fakeLlm']}/fixture/counts", expected=200
        ).status,
    }
    product_count = mysql_query(
        run,
        "commerce_db",
        f"SELECT COUNT(*) FROM product WHERE product_id IN ('{manifest['fixtures']['standardProduct']}', '{manifest['fixtures']['seckillProduct']}') AND publication_state = 'PUBLISHED';",
    )
    faq_count = mysql_query(
        run,
        "commerce_db",
        f"SELECT COUNT(*) FROM faq_source WHERE (faq_id = '{manifest['fixtures']['faq']}' AND published_version = 1) OR (faq_id = '{manifest['fixtures']['faqDelivery']}' AND published_version = 2);",
    )
    if product_count != "1" or faq_count != "2":
        raise DemoError("authoritative fixture inventory is incomplete")
    alias = request(
        "GET",
        f"http://127.0.0.1:{manifest['ports']['elasticsearch']}/_alias/knowledge_docs_read",
        expected=200,
    ).body
    if not isinstance(alias, dict) or len(alias) != 1:
        raise DemoError("knowledge alias does not resolve to exactly one index")
    assert_fault_baseline(run)
    public_files = [run.manifest_file, *run.artifacts.glob("*.json")]
    scanned_files = [*public_files, *run.run_directory.glob("*.log")]
    for value in private.values():
        if len(value) < 12:
            continue
        for path in scanned_files:
            if value in path.read_text(encoding="utf-8"):
                raise DemoError(f"secret value leaked into public artifact {path.name}")
    result = {
        "alias": next(iter(alias)),
        "fixtures": "complete",
        "modes": {name: oct(mode) for name, mode in modes.items()},
        "readiness": readiness,
        "runId": run.run_id,
        "secrets": "absent-from-public-artifacts",
    }
    write_json(run.artifacts / "check.json", result)
    if emit_result:
        emit("check", "passed", **result)
    return result


def dispatch(arguments: argparse.Namespace) -> None:
    command = arguments.command
    if command == "setup":
        setup()
    elif command == "status":
        status()
    elif command == "check":
        check()
    elif command == "cleanup":
        cleanup(arguments.confirm_run_id)
    elif command == "reset":
        reset(arguments.confirm_run_id)
    elif command == "demo":
        demo()
    elif command == "faults":
        faults()
    elif command == "all":
        demo_all()
    else:
        raise DemoError("unknown demo command")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    for command in ("setup", "demo", "faults", "status", "check", "all"):
        commands.add_parser(command)
    for command in ("cleanup", "reset"):
        destructive = commands.add_parser(command)
        destructive.add_argument("--confirm-run-id", required=True)
    return result


def main() -> None:
    arguments = parser().parse_args()
    try:
        dispatch(arguments)
    except DemoError as error:
        fail(arguments.command, str(error))
    except redis.RedisError:
        fail(arguments.command, "Redis dependency rejected the bounded operation")


if __name__ == "__main__":
    main()
