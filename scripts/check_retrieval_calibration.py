"""Derive and verify the committed CB-091 sufficiency calibration artifact."""

from __future__ import annotations

import json

from citybuddy_agent.retrieval import calibration_classification, load_calibration


def main() -> None:
    calibration = load_calibration()
    passing: list[tuple[float, float]] = []
    for threshold in calibration.threshold_candidates:
        for margin in calibration.margin_candidates:
            if all(
                calibration_classification(case.scores, threshold, margin) == case.expected
                for case in calibration.cases
            ):
                passing.append((threshold, margin))
    selected = (calibration.score_threshold, calibration.top_result_margin)
    if passing != [selected]:
        raise SystemExit(
            f"Calibration is not uniquely derived: expected {[selected]!r}, got {passing!r}"
        )
    print(
        json.dumps(
            {
                "calibrationVersion": calibration.calibration_version,
                "caseCount": len(calibration.cases),
                "scoreThreshold": calibration.score_threshold,
                "topResultMargin": calibration.top_result_margin,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
