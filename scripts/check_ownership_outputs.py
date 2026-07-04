#!/usr/bin/env python3
"""Validate ObjectPlan ownership outputs from an extraction directory."""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import sys


VISIBLE_VISUAL_ACTIONS = {
    "PLACE_INLINE_PNG",
    "PLACE_FLOATING_PNG",
    "PLACE_TEXT_SHELL",
}


def load_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    if not path.exists():
        raise FileNotFoundError(f"missing {path}")
    for index, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{index}: invalid jsonl: {exc}") from exc
    return rows


def source_ids(plan: dict) -> list[int]:
    ids = plan.get("visualSourceObjectIds") or plan.get("sourceObjectIds") or []
    return [int(v) for v in ids if isinstance(v, int) or str(v).lstrip("-").isdigit()]


def visible_visual(plan: dict) -> bool:
    return plan.get("visualAction") in VISIBLE_VISUAL_ACTIONS


def visible_slot(plan: dict) -> str:
    action = plan.get("visualAction")
    if action == "PLACE_TEXT_SHELL":
        return "SHELL_SLOT"
    if plan.get("textAction") == "OWNED_BY_PNG":
        return "TEXT_SLOT"
    return "CONTENT_VISUAL_SLOT"


def plan_ref(plan: dict) -> str:
    return (
        f"dom={plan.get('domId')}"
        f",kind={plan.get('kind')}"
        f",page={plan.get('pageIndex')}"
        f",text={plan.get('textAction')}"
        f",visual={plan.get('visualAction')}"
        f",placement={plan.get('placement')}"
        f",reason={plan.get('reason')}"
    )


def fail(message: str, details=None) -> int:
    print(f"FAIL: {message}", file=sys.stderr)
    if details is not None:
        print(json.dumps(details, ensure_ascii=False, indent=2), file=sys.stderr)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("extract_dir", type=Path)
    parser.add_argument("--expect-pages", type=int, default=None)
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    plan_path = args.extract_dir / "ownership-plan.jsonl"
    warning_path = args.extract_dir / "ownership-warnings.jsonl"

    try:
        plans = load_jsonl(plan_path)
        warnings = load_jsonl(warning_path) if warning_path.exists() else []
    except Exception as exc:
        return fail(str(exc))

    errors: list[dict] = []
    if warnings:
        errors.append({
            "code": "ownership_warnings_present",
            "count": len(warnings),
            "examples": warnings[:20],
        })

    by_source_slot: dict[tuple[int, int, str], list[dict]] = collections.defaultdict(list)
    visible_duplicate_text_shell_candidates: list[dict] = []
    visible_count = 0
    pages = set()
    for plan in plans:
        page_index = plan.get("pageIndex")
        if isinstance(page_index, int) and page_index >= 0:
            pages.add(page_index)
        if not visible_visual(plan):
            continue
        visible_count += 1
        if plan.get("reason") == "duplicate_text_shell_visual_only_container":
            visible_duplicate_text_shell_candidates.append(plan)
        for source_id in source_ids(plan):
            by_source_slot[(int(plan.get("pageIndex", -1)), source_id, visible_slot(plan))].append(plan)

    duplicate_source_slots = []
    for (page_index, source_id, slot), group in by_source_slot.items():
        if len(group) <= 1:
            continue
        duplicate_source_slots.append({
            "pageIndex": page_index,
            "sourceId": source_id,
            "slot": slot,
            "plans": [plan_ref(plan) for plan in group],
        })

    if duplicate_source_slots:
        errors.append({
            "code": "duplicate_visible_source_slot",
            "count": len(duplicate_source_slots),
            "examples": duplicate_source_slots[:20],
        })

    page_count = max(pages) + 1 if pages else 0
    if args.expect_pages is not None and page_count != args.expect_pages:
        errors.append({
            "code": "page_count_mismatch",
            "expected": args.expect_pages,
            "actual": page_count,
        })

    action_counts = collections.Counter(str(plan.get("visualAction")) for plan in plans)
    report = {
        "extractDir": str(args.extract_dir),
        "planCount": len(plans),
        "visibleVisualCount": visible_count,
        "pageCount": page_count,
        "warningCount": len(warnings),
        "visibleDuplicateTextShellCandidateCount": len(visible_duplicate_text_shell_candidates),
        "visualActionCounts": dict(sorted(action_counts.items())),
        "errors": errors,
    }

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    if errors:
        return fail("ownership output check failed", errors)

    print("OK ownership output check")
    print("planCount", len(plans))
    print("visibleVisualCount", visible_count)
    print("pageCount", page_count)
    print("warningCount", len(warnings))
    print("visibleDuplicateTextShellCandidateCount", len(visible_duplicate_text_shell_candidates))
    print("visualActionCounts", dict(sorted(action_counts.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
