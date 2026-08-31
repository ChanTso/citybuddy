from __future__ import annotations

import copy
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest
from citybuddy_indexer.knowledge import (
    INITIAL_PUBLIC_CORPUS,
    KNOWLEDGE_ALIAS,
    KNOWLEDGE_INDEX_MAPPING,
)

REPOSITORY = Path(__file__).resolve().parents[1]
SETUP = REPOSITORY / "bench/agent/setup_agent_bench.sh"
VERIFIER = REPOSITORY / "scripts/verify_agent_knowledge_fixture.py"
BENCH_INDEX = "knowledge_docs_v1"
BENCH_ALIAS = KNOWLEDGE_ALIAS


def _search_payload() -> dict[str, Any]:
    return {
        "_shards": {"failed": 0, "skipped": 0, "successful": 1, "total": 1},
        "hits": {
            "hits": [
                {
                    "_id": document.document_id,
                    "_index": BENCH_INDEX,
                    "_score": 1.0,
                    "_source": document.as_source(),
                }
                for document in INITIAL_PUBLIC_CORPUS
            ],
            "max_score": 1.0,
            "total": {"relation": "eq", "value": len(INITIAL_PUBLIC_CORPUS)},
        },
        "timed_out": False,
        "took": 1,
    }


def _write_fixture_payloads(tmp_path: Path) -> dict[str, Path]:
    payloads = {
        "bootstrap": {
            "alias": BENCH_ALIAS,
            "documentCount": len(INITIAL_PUBLIC_CORPUS),
            "indexVersion": BENCH_INDEX,
        },
        "alias": {BENCH_INDEX: {"aliases": {BENCH_ALIAS: {}}}},
        "mapping": {BENCH_INDEX: copy.deepcopy(KNOWLEDGE_INDEX_MAPPING)},
        "visible": _search_payload(),
        "all": _search_payload(),
    }
    paths: dict[str, Path] = {}
    for label, payload in payloads.items():
        path = tmp_path / f"{label}.json"
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        paths[label] = path
    return paths


def _verify(paths: dict[str, Path]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            "--bootstrap",
            str(paths["bootstrap"]),
            "--alias",
            str(paths["alias"]),
            "--mapping",
            str(paths["mapping"]),
            "--visible",
            str(paths["visible"]),
            "--all",
            str(paths["all"]),
        ],
        cwd=REPOSITORY,
        check=False,
        capture_output=True,
        text=True,
    )


def test_agent_knowledge_fixture_accepts_only_committed_exact_corpus(tmp_path: Path) -> None:
    paths = _write_fixture_payloads(tmp_path)

    result = _verify(paths)

    assert result.returncode == 0
    assert result.stderr == ""
    assert json.loads(result.stdout) == {
        "alias": BENCH_ALIAS,
        "documentCount": 4,
        "documentIds": sorted(document.document_id for document in INITIAL_PUBLIC_CORPUS),
        "index": BENCH_INDEX,
        "visibleDocumentCount": 4,
    }


@pytest.mark.parametrize("counterexample", ["extra_document", "filtered_alias", "wrong_mapping"])
def test_agent_knowledge_fixture_rejects_executable_counterexamples(
    tmp_path: Path, counterexample: str
) -> None:
    paths = _write_fixture_payloads(tmp_path)
    if counterexample == "extra_document":
        payload = json.loads(paths["all"].read_text(encoding="utf-8"))
        extra_source = copy.deepcopy(INITIAL_PUBLIC_CORPUS[0].as_source())
        extra_source["schema_version"] = "cb111-v1"
        extra_source["source_id"] = "legacy-extra"
        payload["hits"]["hits"].append(
            {
                "_id": "legacy-extra:overview",
                "_index": BENCH_INDEX,
                "_source": extra_source,
            }
        )
        payload["hits"]["total"]["value"] += 1
        paths["all"].write_text(json.dumps(payload), encoding="utf-8")
    elif counterexample == "filtered_alias":
        payload = {
            BENCH_INDEX: {
                "aliases": {
                    BENCH_ALIAS: {"filter": {"term": {"public_metadata.language": "zh-en"}}}
                }
            }
        }
        paths["alias"].write_text(json.dumps(payload), encoding="utf-8")
    else:
        payload = json.loads(paths["mapping"].read_text(encoding="utf-8"))
        del payload[BENCH_INDEX]["mappings"]["properties"]["sync_event_commitment"]
        paths["mapping"].write_text(json.dumps(payload), encoding="utf-8")

    result = _verify(paths)

    assert result.returncode == 1
    assert result.stdout == ""
    assert result.stderr.startswith("Agent knowledge fixture verification failed:")


def _copy_setup(tmp_path: Path) -> tuple[Path, Path]:
    setup = tmp_path / "bench/agent/setup_agent_bench.sh"
    setup.parent.mkdir(parents=True)
    shutil.copyfile(SETUP, setup)
    run_dir = tmp_path / "bench/.run"
    run_dir.mkdir()
    (run_dir / "citybuddy_commit").write_text("old-commit\n", encoding="utf-8")
    (run_dir / "agent_setup_boundary.json").write_text("{}\n", encoding="utf-8")
    return setup, run_dir


def test_setup_invalidates_completed_boundary_before_configuration_validation(
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
    assert not (run_dir / "agent_setup_boundary.json").exists()


def test_setup_invalidates_completed_boundary_when_git_inspection_fails(tmp_path: Path) -> None:
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
    assert not (run_dir / "agent_setup_boundary.json").exists()


def test_setup_orders_runtime_and_fixture_truth_before_atomic_completion() -> None:
    source = SETUP.read_text(encoding="utf-8")
    java_build = source.index(
        "./mvnw --batch-mode --no-transfer-progress "
        "-pl auth-service,commerce-service -am clean package"
    )
    stop = source.index('echo "== stopping the previous bench services =="')
    first_grant = source.index('"${mysql_setup_make[@]}" grant-access', stop)
    migrate_auth = source.index('"${mysql_setup_make[@]}" migrate-auth', first_grant)
    migrate_commerce = source.index('"${mysql_setup_make[@]}" migrate-commerce', migrate_auth)
    migrate_agent = source.index('"${mysql_setup_make[@]}" migrate-agent', migrate_commerce)
    final_grant = source.index('"${mysql_setup_make[@]}" grant-access', migrate_agent)
    first_fixture_dml = source.index("mysql_bootstrap commerce_db", final_grant)
    verify_knowledge = source.index("scripts/verify_agent_knowledge_fixture.py")
    start_agent = source.index("docker run --detach --name citybuddy-bench-agent")
    publish_boundary = source.index('mv "$boundary_tmp" "$boundary_file"')
    publish_compatibility = source.index('mv "$commit_tmp" "$commit_file"')
    mark_complete = source.index("setup_complete=true")

    assert java_build < stop < first_grant < migrate_auth < migrate_commerce < migrate_agent
    assert migrate_agent < final_grant < first_fixture_dml
    assert verify_knowledge < start_agent
    assert publish_boundary < publish_compatibility < mark_complete
    assert "AGENT_ELASTICSEARCH_URL=http://citybuddy-bench-elasticsearch:9200" in source
    assert "AGENT_ELASTICSEARCH_URL=http://elasticsearch:9200" not in source
    bootstrap_block = source[
        source.index("uv run citybuddy-indexer bootstrap") : source.index(
            "curl --fail --silent --show-error", source.index("uv run citybuddy-indexer bootstrap")
        )
    ]
    assert "|| true" not in bootstrap_block
    assert 'printf \'%s\\n\' "$citybuddy_commit" > "$commit_file"' not in source
