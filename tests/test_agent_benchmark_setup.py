from __future__ import annotations

import io
import os
import shutil
import subprocess
import tarfile
import textwrap
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
SETUP = REPOSITORY / "bench/agent/setup_agent_bench.sh"
DOCKERFILE = REPOSITORY / "bench/agent/Dockerfile"


def _copy_setup(tmp_path: Path) -> tuple[Path, Path]:
    setup = tmp_path / "bench/agent/setup_agent_bench.sh"
    setup.parent.mkdir(parents=True)
    shutil.copyfile(SETUP, setup)
    run_dir = tmp_path / "bench/.run"
    run_dir.mkdir()
    (run_dir / "citybuddy_commit").write_text("old-commit\n", encoding="utf-8")
    (run_dir / "agent_setup_environment.json").write_text("{}\n", encoding="utf-8")
    return setup, run_dir


def test_setup_invalidates_completed_environment_before_configuration_validation(
    tmp_path: Path,
) -> None:
    setup, run_dir = _copy_setup(tmp_path)
    environment = dict(os.environ)
    environment["AGENT_BENCH_USERS"] = "0"

    result = subprocess.run(
        ["/bin/bash", str(setup)],
        cwd=tmp_path,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert not (run_dir / "citybuddy_commit").exists()
    assert not (run_dir / "agent_setup_environment.json").exists()


def test_setup_invalidates_completed_environment_when_git_inspection_fails(
    tmp_path: Path,
) -> None:
    setup, run_dir = _copy_setup(tmp_path)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    git = bin_dir / "git"
    git.write_text("#!/bin/sh\nexit 23\n", encoding="utf-8")
    git.chmod(0o755)
    environment = dict(os.environ)
    environment["PATH"] = f"{bin_dir}:{environment['PATH']}"

    result = subprocess.run(
        ["/bin/bash", str(setup)],
        cwd=tmp_path,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 23
    assert not (run_dir / "citybuddy_commit").exists()
    assert not (run_dir / "agent_setup_environment.json").exists()


def test_agent_image_context_contains_only_tracked_commit_sources(tmp_path: Path) -> None:
    repository = tmp_path / "repository"
    repository.mkdir()
    subprocess.run(["git", "init", "--quiet"], cwd=repository, check=True)
    (repository / ".gitignore").write_text(
        "__pycache__/\n*.pyc\n*.pyo\n*.egg-info/\n*.pth\nbuild/\ndist/\n",
        encoding="utf-8",
    )
    tracked = {
        "agent-service/src/citybuddy_agent/application.py": "VALUE = 'agent'\n",
        "knowledge-indexer/src/citybuddy_indexer/knowledge.py": "VALUE = 'knowledge'\n",
    }
    for name, content in tracked.items():
        path = repository / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=repository, check=True)
    subprocess.run(
        [
            "git",
            "-c",
            "user.name=CityBuddy",
            "-c",
            "user.email=citybuddy@example.invalid",
            "commit",
            "--quiet",
            "-m",
            "fixture",
        ],
        cwd=repository,
        check=True,
    )

    ignored = {
        "agent-service/src/citybuddy_agent/__pycache__/application.cpython-311.pyc": b"old",
        "agent-service/src/citybuddy_agent/legacy.pyo": b"old",
        "agent-service/src/citybuddy_agent_service.egg-info/entry_points.txt": b"old",
        "agent-service/src/__editable__.citybuddy_agent.pth": b"old",
        "knowledge-indexer/src/citybuddy_indexer/__pycache__/knowledge.cpython-311.pyc": b"old",
        "knowledge-indexer/src/build/lib/citybuddy_indexer/knowledge.py": b"old",
        "knowledge-indexer/src/dist/stale.whl": b"old",
    }
    for name, ignored_content in ignored.items():
        path = repository / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(ignored_content)
    status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )
    assert status.stdout == ""

    archive = subprocess.run(
        [
            "git",
            "archive",
            "--format=tar",
            "HEAD",
            "--",
            "agent-service/src",
            "knowledge-indexer/src",
        ],
        cwd=repository,
        check=True,
        capture_output=True,
    )
    with tarfile.open(fileobj=io.BytesIO(archive.stdout), mode="r:") as payload:
        archived_names = set(payload.getnames())
    assert set(tracked).issubset(archived_names)
    assert not set(ignored).intersection(archived_names)

    setup_source = SETUP.read_text(encoding="utf-8")
    archive_start = setup_source.index('git archive --format=tar "$citybuddy_commit"')
    agent_build_end = setup_source.index(
        "docker build --quiet --file infra/elasticsearch/Dockerfile", archive_start
    )
    agent_build = setup_source[archive_start:agent_build_end]
    assert "agent-service/src" in agent_build
    assert "knowledge-indexer/src" in agent_build
    assert "docker build --quiet --file bench/agent/Dockerfile -" in agent_build
    assert "--tag" not in agent_build
    assert "RUN uv sync --locked --no-dev --all-packages" in DOCKERFILE.read_text(encoding="utf-8")


def _write_executable(path: Path, source: str) -> None:
    path.write_text(textwrap.dedent(source).lstrip(), encoding="utf-8")
    path.chmod(0o755)


def _prepare_cleanup_counterexample(tmp_path: Path) -> tuple[Path, dict[str, str], Path]:
    setup, run_dir = _copy_setup(tmp_path)
    commit = "a" * 40
    for stream in ("auth", "commerce", "agent"):
        migration = tmp_path / f"infra/mysql/migrations/{stream}/V001__fixture.sql"
        migration.parent.mkdir(parents=True, exist_ok=True)
        migration.write_text("SELECT 1;\n", encoding="utf-8")
    for reset in (
        "bench/agent/sql/reset_commerce_fixture.sql",
        "bench/agent/sql/reset_support_fixture.sql",
    ):
        path = tmp_path / reset
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("", encoding="utf-8")
    (tmp_path / ".env").write_text(
        "\n".join(
            [
                "MYSQL_COMMERCE_APP_PASSWORD=commerce",
                "MYSQL_AUTH_APP_PASSWORD=auth",
                "MYSQL_AGENT_APP_PASSWORD=agent",
                "MYSQL_BOOTSTRAP_PASSWORD=root",
                "REDIS_COMMERCE_PASSWORD=redis",
                "MYSQL_AUTH_MIGRATION_PASSWORD=auth-migration",
                "MYSQL_COMMERCE_MIGRATION_PASSWORD=commerce-migration",
                "MYSQL_AGENT_MIGRATION_PASSWORD=agent-migration",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    fake_bin = tmp_path / "fake-bin"
    fake_bin.mkdir()
    docker_log = tmp_path / "docker.log"
    docker_state = tmp_path / "es-started"
    es_id = "e" * 64
    mysql_id = "1" * 64
    agent_image_id = f"sha256:{'a' * 64}"
    es_image_id = f"sha256:{'b' * 64}"
    java_image_id = f"sha256:{'c' * 64}"
    net_image_id = f"sha256:{'d' * 64}"
    mysql_image_id = f"sha256:{'f' * 64}"
    mysql_image_ref = f"mysql:8.4.10@{mysql_image_id}"
    _write_executable(
        tmp_path / "mvnw",
        """
        #!/bin/sh
        mkdir -p auth-service/target commerce-service/target
        printf auth > auth-service/target/auth-service-0.0.1-SNAPSHOT.jar
        printf commerce > commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar
        """,
    )
    _write_executable(
        fake_bin / "git",
        f"""
        #!/bin/sh
        case "$1" in
          status) exit 0 ;;
          rev-parse) printf '%s\\n' '{commit}' ;;
          archive) exit 0 ;;
          *) exit 1 ;;
        esac
        """,
    )
    _write_executable(fake_bin / "make", "#!/bin/sh\nexit 0\n")
    _write_executable(
        fake_bin / "mysql",
        """
        #!/bin/sh
        case "$*" in
          *"SELECT version"*) printf '001\\n' ;;
        esac
        """,
    )
    _write_executable(
        fake_bin / "uv",
        """
        #!/bin/sh
        case "$*" in
          *"hash_test_credential.py"*) printf 'fixture-hash\\n'; exit 0 ;;
          *"citybuddy-indexer bootstrap"*) exit 99 ;;
          *) exit 0 ;;
        esac
        """,
    )
    _write_executable(
        fake_bin / "curl",
        '#!/bin/sh\nprintf \'{"status":"yellow"}\\n\'\n',
    )
    _write_executable(
        fake_bin / "docker",
        f"""
        #!/bin/sh
        command="$1"
        shift
        case "$command" in
          port)
            case "$1" in
              {mysql_id}) printf '0.0.0.0:3306\\n' ;;
              citybuddy-bench-elasticsearch) printf '127.0.0.1:49200\\n' ;;
            esac
            ;;
          build)
            case "$*" in
              *bench/agent/Dockerfile*) printf '%s' '{agent_image_id}' ;;
              *infra/elasticsearch/Dockerfile*) printf '%s' '{es_image_id}' ;;
              *) exit 1 ;;
            esac
            ;;
          image)
            case "$*" in
              *eclipse-temurin*) printf '%s' '{java_image_id}' ;;
              *alpine:3.20*) printf '%s' '{net_image_id}' ;;
              *mysql:8.4.10*) printf '%s' '{mysql_image_id}' ;;
              *) exit 1 ;;
            esac
            ;;
          compose)
            printf '%s\\n' '{{"services":{{"mysql":{{"image":"{mysql_image_ref}"}}}}}}'
            ;;
          run)
            echo "run $*" >> "$DOCKER_LOG"
            name=""
            while [ "$#" -gt 0 ]; do
              if [ "$1" = --name ]; then name="$2"; shift 2; else shift; fi
            done
            if [ "$name" = citybuddy-bench-elasticsearch ]; then
              : > "$DOCKER_STATE"
              printf '%s\\n' '{es_id}'
            elif [ "$name" = citybuddy-bench-indexer ]; then
              printf '{{"indexVersion":"knowledge_docs_v1"}}\\n'
              if [ -n "${{SETUP_SIGNAL:-}}" ]; then
                kill -s "$SETUP_SIGNAL" "$PPID"
                sleep 0.1
                exit 0
              fi
              exit 41
            fi
            ;;
          inspect)
            format="$2"
            name="$3"
            case "$name:$format" in
              citybuddy-mysql-1:*Config.Image*) printf '%s\\n' '{mysql_image_ref}' ;;
              citybuddy-mysql-1:*'.Image'*) printf '%s\\n' '{mysql_image_id}' ;;
              citybuddy-mysql-1:*'.Id'*) printf '%s\\n' '{mysql_id}' ;;
              citybuddy-bench-elasticsearch:*'.Image'*) printf '%s\\n' '{es_image_id}' ;;
              citybuddy-bench-elasticsearch:*'.Id'*) printf '%s\\n' '{es_id}' ;;
              *:*StartedAt*) printf '2026-08-31T00:00:00Z\\n' ;;
              *:*RestartCount*) printf '0\\n' ;;
              *:*Running*) printf 'true\\n' ;;
              *) exit 1 ;;
            esac
            ;;
          logs) exit 0 ;;
          ps)
            printf 'ps %s\\n' "$*" >> "$DOCKER_LOG"
            case "$*" in
              *"citybuddy-bench-elasticsearch"*)
                [ ! -f "$DOCKER_STATE" ] || printf '%s\\n' '{es_id}'
                ;;
            esac
            ;;
          rm)
            printf 'rm %s\\n' "$*" >> "$DOCKER_LOG"
            if [ "$*" = '-f {es_id}' ]; then
              printf 'cleanup-remove:%s\\n' '{es_id}' >> "$DOCKER_LOG"
              if [ "${{CLEANUP_FAILURE:-0}}" = 1 ]; then exit 17; fi
              rm -f "$DOCKER_STATE"
            fi
            ;;
          *) exit 1 ;;
        esac
        """,
    )
    environment = dict(os.environ)
    environment.update(
        {
            "AGENT_BENCH_USERS": "1",
            "CLEANUP_FAILURE": "0",
            "DOCKER_LOG": str(docker_log),
            "DOCKER_STATE": str(docker_state),
            "PATH": f"{fake_bin}:{environment['PATH']}",
            "SETUP_SIGNAL": "",
        }
    )
    return setup, environment, run_dir


@pytest.mark.parametrize("cleanup_failure", [False, True])
def test_failed_setup_cleans_only_its_labeled_containers_and_preserves_status(
    tmp_path: Path, cleanup_failure: bool
) -> None:
    setup, environment, run_dir = _prepare_cleanup_counterexample(tmp_path)
    environment["CLEANUP_FAILURE"] = "1" if cleanup_failure else "0"

    result = subprocess.run(
        ["/bin/bash", str(setup)],
        cwd=tmp_path,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 41
    docker_log = Path(environment["DOCKER_LOG"]).read_text(encoding="utf-8")
    assert f"sha256:{'b' * 64}" in docker_log
    assert "--name citybuddy-bench-indexer" in docker_log
    assert "--network citybuddy_default" in docker_log
    assert f"sha256:{'a' * 64} -m citybuddy_indexer bootstrap" in docker_log
    assert "--elasticsearch-url http://citybuddy-bench-elasticsearch:9200" in docker_log
    assert "citybuddy-bench-elasticsearch:local" not in docker_log
    assert f"cleanup-remove:{'e' * 64}" in docker_log
    assert "label=citybuddy.bench.setup-nonce=" in docker_log
    assert f"label=citybuddy.bench.citybuddy-commit={'a' * 40}" in docker_log
    assert "rm -f citybuddy-mysql-1" not in docker_log
    for name in (
        "citybuddy-bench-pool",
        "citybuddy-bench-k6",
        "citybuddy-bench-profile-load",
        "citybuddy-bench-indexer",
        "citybuddy-bench-agent",
        "citybuddy-bench-model",
        "citybuddy-bench-net",
        "citybuddy-bench-commerce",
        "citybuddy-bench-auth",
        "citybuddy-bench-elasticsearch",
    ):
        assert f"name=^/{name}$" in docker_log
    assert not (run_dir / "citybuddy_commit").exists()
    assert not (run_dir / "agent_setup_environment.json").exists()
    if cleanup_failure:
        assert (
            "Agent setup cleanup failed with status 17 (original setup status: 41)."
            in result.stderr
        )
    else:
        assert "Agent setup cleanup failed" not in result.stderr


@pytest.mark.parametrize(("signal", "status"), [("HUP", 129), ("INT", 130), ("TERM", 143)])
def test_interrupted_setup_cleans_its_labeled_containers(
    tmp_path: Path, signal: str, status: int
) -> None:
    setup, environment, run_dir = _prepare_cleanup_counterexample(tmp_path)
    environment["SETUP_SIGNAL"] = signal

    result = subprocess.run(
        ["/bin/bash", str(setup)],
        cwd=tmp_path,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == status
    docker_log = Path(environment["DOCKER_LOG"]).read_text(encoding="utf-8")
    assert f"cleanup-remove:{'e' * 64}" in docker_log
    assert not (run_dir / "citybuddy_commit").exists()
    assert not (run_dir / "agent_setup_environment.json").exists()
