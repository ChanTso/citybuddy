from __future__ import annotations

import re
import shlex
import subprocess
from pathlib import Path


ROOT = Path(__file__).parents[1]
HELPER = ROOT / "scripts" / "surefire_evidence.sh"


def report(class_name: str, *, tests: int, skipped: int = 0) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{class_name}" tests="{tests}" errors="0" '
        f'skipped="{skipped}" failures="0">\n'
        "</testsuite>\n"
    )


def run_helper(report_dir: Path, function: str, class_name: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            "bash",
            "-c",
            'source "$1"; "$2" "$3" "$4"',
            "surefire-evidence-test",
            str(HELPER),
            function,
            str(report_dir),
            class_name,
        ],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def test_surefire_evidence_rejects_missing_zero_and_skipped_reports(tmp_path: Path) -> None:
    class_name = "io.citybuddy.RequiredIntegrationTest"
    report_path = tmp_path / f"TEST-{class_name}.xml"

    missing = run_helper(tmp_path, "assert_surefire_classes_executed", class_name)
    assert missing.returncode != 0
    assert "Missing fresh Surefire report" in missing.stderr

    report_path.write_text(report(class_name, tests=0), encoding="utf-8")
    zero = run_helper(tmp_path, "assert_surefire_classes_executed", class_name)
    assert zero.returncode != 0
    assert "did not execute any required" in zero.stderr

    report_path.write_text(report(class_name, tests=2, skipped=2), encoding="utf-8")
    skipped = run_helper(tmp_path, "assert_surefire_classes_executed", class_name)
    assert skipped.returncode != 0
    assert "did not execute every required" in skipped.stderr


def test_surefire_evidence_clears_stale_reports_and_accepts_fresh_execution(
    tmp_path: Path,
) -> None:
    class_name = "io.citybuddy.RequiredIntegrationTest"
    report_path = tmp_path / f"TEST-{class_name}.xml"
    report_path.write_text(report(class_name, tests=4), encoding="utf-8")

    cleared = run_helper(tmp_path, "clear_surefire_reports", class_name)
    assert cleared.returncode == 0
    assert not report_path.exists()

    report_path.write_text(report(class_name, tests=4), encoding="utf-8")
    accepted = run_helper(tmp_path, "assert_surefire_classes_executed", class_name)
    assert accepted.returncode == 0
    assert "executed 4 required tests" in accepted.stdout


def test_every_surefire_integration_entry_has_a_fresh_complete_report_gate() -> None:
    java_tests = {
        path.stem: path
        for module in ("auth-service", "commerce-service")
        for path in (ROOT / module / "src" / "test").rglob("*.java")
    }
    selected_conditionally_enabled: set[str] = set()
    gated_classes: set[str] = set()

    for script in sorted((ROOT / "scripts").glob("test_*integration.sh")):
        content = script.read_text(encoding="utf-8")
        selected_names = {
            name
            for selection in re.findall(r"-Dtest=([A-Za-z0-9_,]+)", content)
            for name in selection.split(",")
        }
        if not selected_names:
            continue

        array_match = re.search(
            r"required_surefire_classes=\(\s*(.*?)\s*\)", content, re.DOTALL
        )
        assert array_match is not None, f"{script.name} has no required Surefire class inventory"
        inventory = set(shlex.split(array_match.group(1)))
        expected: set[str] = set()
        for simple_name in selected_names:
            source = java_tests[simple_name]
            java = source.read_text(encoding="utf-8")
            package = re.search(r"^package ([A-Za-z0-9_.]+);", java, re.MULTILINE)
            assert package is not None
            class_name = f"{package.group(1)}.{simple_name}"
            expected.add(class_name)
            if "@EnabledIfEnvironmentVariable" in java or "Assumptions." in java:
                selected_conditionally_enabled.add(class_name)

        assert inventory == expected
        assert 'source "$repo_root/scripts/surefire_evidence.sh"' in content
        assert "clear_surefire_reports" in content
        assert "assert_surefire_classes_executed" in content
        gated_classes.update(inventory)

    all_conditionally_enabled: set[str] = set()
    for source in java_tests.values():
        java = source.read_text(encoding="utf-8")
        if "@EnabledIfEnvironmentVariable" not in java and "Assumptions." not in java:
            continue
        package = re.search(r"^package ([A-Za-z0-9_.]+);", java, re.MULTILINE)
        assert package is not None
        all_conditionally_enabled.add(f"{package.group(1)}.{source.stem}")

    assert selected_conditionally_enabled == all_conditionally_enabled
    assert all_conditionally_enabled <= gated_classes
