#!/usr/bin/env python3
"""Audit source ownership policy ownership output for one extract directory.

The converter writes three useful JSONL files next to resolved.json:

  - ownership-plan.jsonl
  - ownership-warnings.jsonl
  - render-decisions.jsonl

This script keeps the audit policy simple:

  1. Stage 1 plans should not contain duplicate visible source ownership.
  2. Stage 3 execution should follow the plan, not invent new placement.
  3. Missing plans are reported as planner coverage gaps.

Usage:
  python3 scripts/ownership_plan_audit.py <extract_dir>
  python3 scripts/ownership_plan_audit.py <extract_dir> --json output/audit.json
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


VISIBLE_VISUAL_ACTIONS = {
    "PLACE_INLINE_PNG",
    "PLACE_FLOATING_PNG",
    "PLACE_TEXT_SHELL",
}

PLACED_DECISIONS = {
    "PLACE",
    "PLACE_BACKGROUND_SHELL",
    "PLACE_OVERFLOW_PREVIOUS_PAGE",
    "PLACE_INLINE_TEXT_SHELL",
    "PLACE_LABEL_SHELL",
    "PLACE_TEXTLESS_VISUAL_FRAGMENT",
    "PLACE_TEXT_SHELL_FIGURE",
    "PLACE_TEXT_SHELL_BLOCK",
}

ROUTED_DECISIONS = {
    "ROUTE_INLINE_VISUAL",
}


def load_jsonl(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    if not path.exists():
        return rows
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def parse_page_from_warning(row: Dict[str, Any]) -> Optional[int]:
    if row.get("pageIndex") is not None:
        return row.get("pageIndex")
    detail = row.get("detail") or ""
    m = re.search(r"\bpage=(\d+)\b", detail)
    if m:
        return int(m.group(1))
    m = re.search(r"\bp(\d+)\b", detail)
    if m:
        return int(m.group(1))
    return None


def plan_key(plan: Dict[str, Any]) -> str:
    return "/".join(
        str(plan.get(k, ""))
        for k in ("domId", "kind", "visualAction", "policyLayer", "placement")
    )


def visual_sources(plan: Dict[str, Any]) -> List[int]:
    sources = plan.get("visualSourceObjectIds") or plan.get("sourceObjectIds") or []
    return [int(source) for source in sources]


def text_sources(plan: Dict[str, Any]) -> List[int]:
    owned = plan.get("ownedTextFrameIds") or []
    if owned:
        return [int(source) for source in owned]
    if str(plan.get("kind") or "").startswith("text_frame") and plan.get("domId") is not None:
        return [int(plan["domId"])]
    return [int(source) for source in plan.get("sourceObjectIds") or []]


def audit_plan_duplicates(plans: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    by_source: Dict[Tuple[int, Any], List[Dict[str, Any]]] = defaultdict(list)
    for plan in plans:
        if plan.get("visualAction") not in VISIBLE_VISUAL_ACTIONS:
            continue
        page = plan.get("pageIndex")
        for source in visual_sources(plan):
            by_source[(source, page)].append(plan)

    duplicates: List[Dict[str, Any]] = []
    for (source, page), group in by_source.items():
        if len(group) <= 1:
            continue
        duplicates.append(
            {
                "sourceObjectId": source,
                "pageIndex": page,
                "plans": [plan_key(p) for p in group],
            }
        )
    return sorted(duplicates, key=lambda d: (d["pageIndex"] is None, d["pageIndex"], d["sourceObjectId"]))


def audit_text_owner_conflicts(plans: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    by_source: Dict[Tuple[int, Any], Counter] = defaultdict(Counter)
    for plan in plans:
        page = plan.get("pageIndex")
        text_action = plan.get("textAction")
        if text_action not in {"OWNED_BY_HWPX_TEXT", "OWNED_BY_PNG"}:
            continue
        for source in text_sources(plan):
            by_source[(source, page)][text_action] += 1

    conflicts: List[Dict[str, Any]] = []
    for (source, page), actions in by_source.items():
        if actions["OWNED_BY_HWPX_TEXT"] and actions["OWNED_BY_PNG"]:
            conflicts.append(
                {
                    "sourceObjectId": source,
                    "pageIndex": page,
                    "actions": dict(actions),
                }
            )
    return sorted(conflicts, key=lambda d: (d["pageIndex"] is None, d["pageIndex"], d["sourceObjectId"]))


def group_render_decisions(rows: Iterable[Dict[str, Any]]) -> Dict[Tuple[Any, Any, Any], Dict[str, Any]]:
    grouped: Dict[Tuple[Any, Any, Any], Dict[str, Any]] = defaultdict(
        lambda: {"rows": [], "planVisualAction": None, "pageIndex": None, "reason": None, "file": None}
    )
    for row in rows:
        key = (row.get("id"), row.get("itemType"), row.get("pageIndex"))
        entry = grouped[key]
        entry["rows"].append(row)
        if row.get("planVisualAction") is not None:
            entry["planVisualAction"] = row.get("planVisualAction")
        entry["pageIndex"] = row.get("pageIndex")
        entry["reason"] = row.get("reason")
        entry["file"] = row.get("file")
    return grouped


def audit_execution(rows: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    grouped = group_render_decisions(rows)
    dropped_despite_plan = []
    outside_page_despite_plan = []
    expected_non_floating_bypass = []
    planned_non_floating_routes = []
    placed_despite_drop = []
    no_object_plan = []
    cross = Counter()

    for (dom_id, item_type, page), entry in grouped.items():
        decisions = [r.get("decision") for r in entry["rows"]]
        placed = any(d in PLACED_DECISIONS for d in decisions)
        routed = any(d in ROUTED_DECISIONS for d in decisions)
        plan = entry["planVisualAction"]
        cross[(plan, "PLACED" if placed else "SKIPPED", item_type)] += 1
        sample = {
            "id": dom_id,
            "itemType": item_type,
            "pageIndex": page,
            "reason": entry["reason"],
            "file": entry["file"],
            "planVisualAction": plan,
            "planPlacement": next((r.get("planPlacement") for r in entry["rows"] if r.get("planPlacement")), None),
            "decisions": decisions,
        }
        if plan == "PLACE_FLOATING_PNG" and not placed and "SKIP_OUTSIDE_PAGE" in decisions:
            outside_page_despite_plan.append(sample)
        elif plan == "PLACE_FLOATING_PNG" and not placed:
            dropped_despite_plan.append(sample)
        elif plan in {"PLACE_INLINE_PNG", "PLACE_TEXT_SHELL"} and not placed:
            if routed:
                planned_non_floating_routes.append(sample)
            elif "SKIP_OBJECT_PLAN_NOT_FLOATING_VISUAL" in decisions:
                expected_non_floating_bypass.append(sample)
            else:
                dropped_despite_plan.append(sample)
        if plan == "DROP_VISUAL" and placed:
            placed_despite_drop.append(sample)
        if any(d == "SKIP_NO_OBJECT_PLAN" for d in decisions):
            no_object_plan.append(sample)

    return {
        "cross": cross,
        "dropped_despite_plan": dropped_despite_plan,
        "outside_page_despite_plan": outside_page_despite_plan,
        "expected_non_floating_bypass": expected_non_floating_bypass,
        "planned_non_floating_routes": planned_non_floating_routes,
        "placed_despite_drop": placed_despite_drop,
        "no_object_plan": no_object_plan,
    }


def top_pages(
    warnings: Iterable[Dict[str, Any]],
    duplicates: Iterable[Dict[str, Any]],
    execution: Dict[str, Any],
) -> Counter:
    pages = Counter()
    for row in warnings:
        page = parse_page_from_warning(row)
        if page is not None:
            pages[page] += 1
    for row in duplicates:
        page = row.get("pageIndex")
        if page is not None:
            pages[page] += 2
    for key in ("dropped_despite_plan", "placed_despite_drop", "no_object_plan"):
        for row in execution[key]:
            page = row.get("pageIndex")
            if page is not None:
                pages[page] += 3
    return pages


def print_counter(title: str, counter: Counter, limit: int = 20) -> None:
    print(f"\n## {title}")
    if not counter:
        print("  (없음)")
        return
    for key, count in counter.most_common(limit):
        print(f"  {key}: {count}")


def print_samples(title: str, rows: List[Dict[str, Any]], limit: int = 12) -> None:
    print(f"\n## {title}: {len(rows)}")
    if not rows:
        print("  (없음)")
        return
    for row in rows[:limit]:
        print("  " + json.dumps(row, ensure_ascii=False))


def build_report(extract_dir: Path) -> Dict[str, Any]:
    plans = load_jsonl(extract_dir / "ownership-plan.jsonl")
    warnings = load_jsonl(extract_dir / "ownership-warnings.jsonl")
    decisions = load_jsonl(extract_dir / "render-decisions.jsonl")

    plan_action_counts = Counter(p.get("visualAction") for p in plans)
    plan_text_counts = Counter(p.get("textAction") for p in plans)
    plan_layer_counts = Counter(p.get("policyLayer") for p in plans)
    warning_counts = Counter(w.get("code") for w in warnings)
    warning_page_counts = Counter(parse_page_from_warning(w) for w in warnings)
    warning_page_counts.pop(None, None)

    duplicates = audit_plan_duplicates(plans)
    text_conflicts = audit_text_owner_conflicts(plans)
    execution = audit_execution(decisions)
    page_scores = top_pages(warnings, duplicates, execution)

    return {
        "extractDir": str(extract_dir),
        "counts": {
            "plans": len(plans),
            "warnings": len(warnings),
            "renderDecisionRows": len(decisions),
            "duplicateVisibleVisualSourceRefs": len(duplicates),
            "textOwnerConflicts": len(text_conflicts),
        "droppedDespitePlan": len(execution["dropped_despite_plan"]),
        "outsidePageDespitePlan": len(execution["outside_page_despite_plan"]),
            "expectedNonFloatingBypass": len(execution["expected_non_floating_bypass"]),
            "plannedNonFloatingRoutes": len(execution["planned_non_floating_routes"]),
            "placedDespiteDrop": len(execution["placed_despite_drop"]),
            "noObjectPlan": len(execution["no_object_plan"]),
        },
        "planVisualActionCounts": dict(plan_action_counts),
        "planTextActionCounts": dict(plan_text_counts),
        "planPolicyLayerCounts": dict(plan_layer_counts),
        "warningCounts": dict(warning_counts),
        "warningPageCounts": dict(warning_page_counts),
        "pageScores": dict(page_scores),
        "duplicateVisibleSources": duplicates[:100],
        "textOwnerConflicts": text_conflicts[:100],
        "droppedDespitePlan": execution["dropped_despite_plan"][:100],
        "outsidePageDespitePlan": execution["outside_page_despite_plan"][:100],
        "expectedNonFloatingBypass": execution["expected_non_floating_bypass"][:100],
        "plannedNonFloatingRoutes": execution["planned_non_floating_routes"][:100],
        "placedDespiteDrop": execution["placed_despite_drop"][:100],
        "noObjectPlan": execution["no_object_plan"][:100],
        "executionCross": {
            "|".join(str(part) for part in key): value
            for key, value in execution["cross"].items()
        },
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("extract_dir", type=Path)
    ap.add_argument("--json", type=Path, help="write full audit report as JSON")
    args = ap.parse_args()

    report = build_report(args.extract_dir)

    print("# ownership plan audit")
    print(f"extractDir: {report['extractDir']}")
    print("\n## counts")
    for key, value in report["counts"].items():
        print(f"  {key}: {value}")

    print_counter("plan visualAction", Counter(report["planVisualActionCounts"]))
    print_counter("plan textAction", Counter(report["planTextActionCounts"]))
    print_counter("plan policyLayer", Counter(report["planPolicyLayerCounts"]))
    print_counter("ownership warnings", Counter(report["warningCounts"]))
    print_counter("top pages to inspect", Counter({int(k): v for k, v in report["pageScores"].items()}), 15)

    print_samples("duplicate visible visual-source samples", report["duplicateVisibleSources"])
    print_samples("text owner conflict samples", report["textOwnerConflicts"])
    print_samples("dropped despite plan samples", report["droppedDespitePlan"])
    print_samples("outside page despite plan samples", report["outsidePageDespitePlan"])
    print_samples("expected non-floating bypass samples", report["expectedNonFloatingBypass"])
    print_samples("planned non-floating route samples", report["plannedNonFloatingRoutes"])
    print_samples("placed despite drop samples", report["placedDespiteDrop"])
    print_samples("no object plan samples", report["noObjectPlan"])

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nJSON: {args.json}")


if __name__ == "__main__":
    main()
