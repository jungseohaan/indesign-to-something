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
    "candidate_direct",
    "candidate_source_set_direct",
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


def fail(message: str, details=None) -> int:
    print(f"FAIL: {message}", file=sys.stderr)
    if details is not None:
        print(json.dumps(details, ensure_ascii=False, indent=2), file=sys.stderr)
    return 1


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
        results = load_results(args.extract_dir)
    except Exception as exc:
        return fail(str(exc))

    rows = results.get("results") or []
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

    expected_counts = None
    if args.baseline == "two-unit-30p":
        expected_counts = TWO_UNIT_30P_COUNTS
        actual_counts = {k: pass_counts[k] for k in sorted(pass_counts) if k}
        if actual_counts != expected_counts:
            errors.append({
                "code": "pass_count_baseline_mismatch",
                "expected": expected_counts,
                "actual": actual_counts,
            })

    report = {
        "extractDir": str(args.extract_dir),
        "validation": validation,
        "resultCount": len(rows),
        "passCounts": dict(sorted(pass_counts.items())),
        "strategies": dict(sorted(strategies.items())),
        "byPassStrategy": {
            f"{pass_id}|{strategy}": count
            for (pass_id, strategy), count in sorted(by_pass_strategy.items())
        },
        "baseline": args.baseline,
        "errors": errors,
    }

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    if errors:
        return fail("extraction result policy check failed", errors)

    print("OK extraction result policy check")
    print("resultCount", len(rows))
    print("passCounts", dict(sorted(pass_counts.items())))
    print("strategies", dict(sorted(strategies.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
