#!/usr/bin/env python3
"""Validate extraction-results.json against the source extraction policy.

This is intentionally metadata-only: it checks that an already-created
extraction directory obeys the planner/candidate contract without opening
InDesign or converting to HWPX.
"""

import argparse
import collections
import json
from pathlib import Path
import sys


DIRECT_STRATEGIES = {
    "applied_master_snapshot_candidate",
    "candidate_direct",
    "candidate_source_set_direct",
    "candidate_source_set_contains_master_cluster",
    "explicit_ownership_candidate",
    "planned_source_set_composite",
}

TWO_UNIT_30P_COUNTS = {
    "pass.complex_graphic_frames": 125,
    "pass.decoration_groups": 451,
    "pass.editable_textframe_visual_shells": 9,
    "pass.image_placed_frames": 4,
    "pass.image_textless_groups": 17,
    "pass.inline_objects": 247,
    "pass.master_page_graphics": 21,
    "pass.vector_shape_frames": 63,
}


def load_results(extract_dir: Path) -> dict:
    path = extract_dir / "extraction-results.json"
    if not path.exists():
        raise FileNotFoundError(f"missing {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def load_optional_json(path: Path) -> dict:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def load_optional_jsonl(path: Path) -> list:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))
    return rows


def fail(message: str, details=None) -> int:
    print(f"FAIL: {message}", file=sys.stderr)
    if details is not None:
        print(json.dumps(details, ensure_ascii=False, indent=2), file=sys.stderr)
    return 1


def valid_bounds(bounds) -> bool:
    if not isinstance(bounds, list) or len(bounds) < 4:
        return False
    try:
        values = [float(bounds[i]) for i in range(4)]
    except (TypeError, ValueError):
        return False
    return values[2] > values[0] and values[3] > values[1]


def bounds_materially_larger(outer, inner, eps: float = 0.1) -> bool:
    if not valid_bounds(outer) or not valid_bounds(inner):
        return False
    outer = [float(outer[i]) for i in range(4)]
    inner = [float(inner[i]) for i in range(4)]
    return (
        outer[0] < inner[0] - eps
        or outer[1] < inner[1] - eps
        or outer[2] > inner[2] + eps
        or outer[3] > inner[3] + eps
    )


def has_crop_contract(row: dict) -> bool:
    return valid_bounds(row.get("cropSourceBounds"))


def visible_visual_action(row: dict) -> bool:
    action = row.get("visualAction") or row.get("planVisualAction")
    return action in {
        "PLACE_FLOATING_PNG",
        "PLACE_INLINE_PNG",
        "PLACE_TEXT_SHELL",
    }


def broad_render_source_without_crop(rows: list) -> list:
    offenders = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        if not visible_visual_action(row):
            continue
        if not bounds_materially_larger(row.get("renderSourceBounds"), row.get("bounds")):
            continue
        if has_crop_contract(row):
            continue
        offenders.append({
            "candidateId": row.get("candidateId") or row.get("objectPlanCandidateId"),
            "pageIndex": row.get("pageIndex"),
            "visualAction": row.get("visualAction") or row.get("planVisualAction"),
            "bounds": row.get("bounds"),
            "renderSourceBounds": row.get("renderSourceBounds"),
            "cropSourceBounds": row.get("cropSourceBounds"),
            "file": row.get("file") or row.get("planFile"),
        })
    return offenders


def build_report(extract_dir: Path, baseline: str = None) -> dict:
    results = load_results(extract_dir)
    rows = results.get("results") or []
    object_plans = load_optional_json(extract_dir / "object-plans.json")
    object_plan_rows = object_plans.get("objectPlans") or []
    render_decision_rows = load_optional_jsonl(extract_dir / "render-decisions.jsonl")
    validation = results.get("validation") or {}
    pass_counts = collections.Counter(row.get("planPassId") for row in rows)
    strategies = collections.Counter(row.get("candidateMatchStrategy") for row in rows)
    by_pass_strategy = collections.Counter(
        (row.get("planPassId"), row.get("candidateMatchStrategy")) for row in rows
    )

    errors = []
    if validation.get("status") != "OK" or validation.get("issueCount") not in (0, None):
        errors.append({
            "code": "validation_not_ok",
            "status": validation.get("status"),
            "issueCount": validation.get("issueCount"),
            "issues": validation.get("issues", [])[:10],
        })

    missing_candidate = [row for row in rows if not row.get("stampedCandidateId")]
    if missing_candidate:
        errors.append({
            "code": "missing_candidate_stamp",
            "count": len(missing_candidate),
            "examples": missing_candidate[:10],
        })

    missing_strategy = [row for row in rows if not row.get("candidateMatchStrategy")]
    if missing_strategy:
        errors.append({
            "code": "missing_candidate_strategy",
            "count": len(missing_strategy),
            "examples": missing_strategy[:10],
        })

    forbidden_strategy = [
        row for row in rows
        if row.get("candidateMatchStrategy") not in DIRECT_STRATEGIES
    ]
    if forbidden_strategy:
        errors.append({
            "code": "non_direct_candidate_strategy",
            "count": len(forbidden_strategy),
            "strategies": dict(sorted(strategies.items())),
            "examples": forbidden_strategy[:10],
        })

    fallback_strategy = [
        row for row in rows
        if "fallback" in str(row.get("candidateMatchStrategy"))
        or row.get("candidateMatchStrategy") == "source_set_legacy_all_members"
    ]
    if fallback_strategy:
        errors.append({
            "code": "fallback_candidate_strategy",
            "count": len(fallback_strategy),
            "examples": fallback_strategy[:10],
        })

    object_plan_crop_offenders = broad_render_source_without_crop(object_plan_rows)
    if object_plan_crop_offenders:
        errors.append({
            "code": "object_plan_broad_render_source_missing_crop_contract",
            "count": len(object_plan_crop_offenders),
            "examples": object_plan_crop_offenders[:10],
        })

    render_decision_crop_offenders = broad_render_source_without_crop(render_decision_rows)
    if render_decision_crop_offenders:
        errors.append({
            "code": "render_decision_broad_render_source_missing_crop_contract",
            "count": len(render_decision_crop_offenders),
            "examples": render_decision_crop_offenders[:10],
        })

    expected_counts = None
    if baseline == "two-unit-30p":
        expected_counts = TWO_UNIT_30P_COUNTS
        actual_counts = {k: pass_counts[k] for k in sorted(pass_counts) if k}
        if actual_counts != expected_counts:
            errors.append({
                "code": "pass_count_baseline_mismatch",
                "expected": expected_counts,
                "actual": actual_counts,
            })

    report = {
        "extractDir": str(extract_dir),
        "validation": validation,
        "resultCount": len(rows),
        "passCounts": dict(sorted(pass_counts.items())),
        "strategies": dict(sorted(strategies.items())),
        "objectPlanCount": len(object_plan_rows),
        "renderDecisionCount": len(render_decision_rows),
        "byPassStrategy": {
            f"{pass_id}|{strategy}": count
            for (pass_id, strategy), count in sorted(by_pass_strategy.items())
        },
        "baseline": baseline,
        "errors": errors,
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("extract_dir", type=Path)
    parser.add_argument(
        "--baseline",
        choices=["two-unit-30p"],
        default=None,
        help="also assert a known pass-count baseline",
    )
    parser.add_argument(
        "--json",
        type=Path,
        default=None,
        help="write a summary report",
    )
    args = parser.parse_args()

    try:
        report = build_report(args.extract_dir, args.baseline)
    except Exception as exc:
        return fail(str(exc))

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    errors = report.get("errors") or []
    if errors:
        return fail("extraction result policy check failed", errors)

    print("OK extraction result policy check")
    print("resultCount", report.get("resultCount"))
    print("passCounts", report.get("passCounts"))
    print("strategies", report.get("strategies"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
