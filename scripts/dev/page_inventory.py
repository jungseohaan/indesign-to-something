#!/usr/bin/env python3
"""Build a page-local inventory from extractor diagnostics.

This script does not decide ownership. It only reshapes existing extractor
diagnostics into compact TSV/Markdown files so an issue can start from
`CASE + PAGE` without first knowing source ids.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8", errors="replace"))


def load_jsonl(path: Path) -> List[Dict[str, Any]]:
    if not path.exists():
        return []
    rows: List[Dict[str, Any]] = []
    with path.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def text_blob(value: Any) -> str:
    if isinstance(value, dict):
        return " ".join(text_blob(v) for v in value.values())
    if isinstance(value, list):
        return " ".join(text_blob(v) for v in value)
    return "" if value is None else str(value)


def flow_text(row: Dict[str, Any]) -> str:
    chunks: List[str] = []
    for paragraph in row.get("paragraphs") or []:
        if not isinstance(paragraph, dict):
            continue
        for run in paragraph.get("runs") or []:
            if not isinstance(run, dict):
                continue
            if run.get("kind") == "TEXT":
                chunks.append(str(run.get("text") or ""))
            elif run.get("kind") == "INLINE_SLOT":
                chunks.append("[INLINE_SLOT]")
    text = " ".join("".join(chunks).split())
    return text[:240]


def bounds_key(row: Dict[str, Any]) -> Sequence[float]:
    bounds = row.get("bounds")
    if isinstance(bounds, list) and len(bounds) >= 4:
        try:
            return (float(bounds[0]), float(bounds[1]), float(bounds[2]), float(bounds[3]))
        except (TypeError, ValueError):
            pass
    return (999999.0, 999999.0, 999999.0, 999999.0)


def page_matches(row: Dict[str, Any], page_index: Optional[int]) -> bool:
    if page_index is None:
        return True
    return row.get("pageIndex") == page_index


def text_flow_page_matches(row: Dict[str, Any], page_index: Optional[int]) -> bool:
    if page_index is None:
        return True
    pages = row.get("ownerPageIndexes")
    if isinstance(pages, list):
        return page_index in pages
    return row.get("pageIndex") == page_index


def ids_text(row: Dict[str, Any], key: str) -> str:
    value = row.get(key)
    if not isinstance(value, list):
        return ""
    return ",".join(str(v) for v in value[:16]) + ("..." if len(value) > 16 else "")


def tsv_escape(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value).replace("\t", " ").replace("\n", " ").replace("\r", " ")


def write_tsv(path: Path, rows: List[Dict[str, Any]], columns: Sequence[str]) -> None:
    lines = ["\t".join(columns)]
    for row in rows:
        lines.append("\t".join(tsv_escape(row.get(col)) for col in columns))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def compact_source(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": row.get("id"),
        "kind": row.get("kind"),
        "parentId": row.get("parentId"),
        "parentKind": row.get("parentKind"),
        "layerName": row.get("layerName"),
        "zOrder": row.get("zOrder"),
        "visible": row.get("visible"),
        "hasText": row.get("hasText"),
        "textLength": row.get("textLength"),
        "textPreview": row.get("textPreview"),
        "bounds": row.get("bounds"),
    }


def compact_plan(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "objectPlanId": row.get("objectPlanId"),
        "pageIndex": row.get("pageIndex"),
        "kind": row.get("kind"),
        "candidatePurpose": row.get("candidatePurpose"),
        "sourceObjectIds": ids_text(row, "sourceObjectIds"),
        "visualSourceObjectIds": ids_text(row, "visualSourceObjectIds"),
        "ownedTextFrameIds": ids_text(row, "ownedTextFrameIds"),
        "textAction": row.get("textAction"),
        "visualAction": row.get("visualAction"),
        "placement": row.get("placement"),
        "visualLayer": row.get("visualLayer"),
        "policyLayer": row.get("policyLayer"),
        "materialization": row.get("materialization"),
        "zOrder": row.get("zOrder"),
        "file": row.get("file"),
        "reason": row.get("reason"),
        "bounds": row.get("bounds"),
    }


def compact_decision(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "phase": row.get("phase"),
        "decision": row.get("decision"),
        "id": row.get("id"),
        "pageIndex": row.get("pageIndex"),
        "file": row.get("file") or row.get("sourceFile") or row.get("planFile"),
        "planVisualAction": row.get("planVisualAction"),
        "planPlacement": row.get("planPlacement"),
        "planVisualLayer": row.get("planVisualLayer"),
        "planPolicyLayer": row.get("planPolicyLayer"),
        "planMaterialization": row.get("planMaterialization"),
        "planZOrder": row.get("planZOrder"),
        "detail": row.get("detail"),
        "reason": row.get("reason"),
        "bounds": row.get("bounds"),
    }


def compact_flow(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "storyId": row.get("storyId"),
        "textOwner": row.get("textOwner"),
        "textLength": row.get("textLength"),
        "inlineSlotCount": row.get("inlineSlotCount"),
        "ownerTextFrameIds": ids_text(row, "ownerTextFrameIds"),
        "ownerPageIndexes": ids_text(row, "ownerPageIndexes"),
        "text": flow_text(row),
    }


def build_inventory(extract_dir: Path, page_index: Optional[int]) -> Dict[str, Any]:
    extraction_plan = load_json(extract_dir / "extraction-plan.json") or {}
    object_plans_json = load_json(extract_dir / "object-plans.json") or {}
    source_items = [
        compact_source(row)
        for row in extraction_plan.get("sourceItems") or []
        if isinstance(row, dict) and page_matches(row, page_index)
    ]
    object_plans = [
        compact_plan(row)
        for row in object_plans_json.get("objectPlans") or []
        if isinstance(row, dict) and page_matches(row, page_index)
    ]
    render_decisions = [
        compact_decision(row)
        for row in load_jsonl(extract_dir / "render-decisions.jsonl")
        if page_matches(row, page_index)
    ]
    text_flows = [
        compact_flow(row)
        for row in load_jsonl(extract_dir / "text-flow.jsonl")
        if text_flow_page_matches(row, page_index)
    ]

    source_items.sort(key=lambda row: (bounds_key(row)[0], bounds_key(row)[1], row.get("zOrder") or 0, row.get("id") or 0))
    object_plans.sort(key=lambda row: (bounds_key(row)[0], bounds_key(row)[1], row.get("zOrder") or 0, str(row.get("objectPlanId") or "")))
    render_decisions.sort(key=lambda row: (bounds_key(row)[0], bounds_key(row)[1], row.get("planZOrder") or 0, row.get("id") or 0))
    text_flows.sort(key=lambda row: (str(row.get("ownerPageIndexes") or ""), str(row.get("storyId") or "")))

    return {
        "extractDir": str(extract_dir),
        "pageIndex": page_index,
        "summary": {
            "sourceItems": len(source_items),
            "objectPlans": len(object_plans),
            "renderDecisions": len(render_decisions),
            "textFlows": len(text_flows),
            "sourceKinds": dict(Counter(row.get("kind") for row in source_items)),
            "planVisualActions": dict(Counter(row.get("visualAction") for row in object_plans)),
            "planPlacements": dict(Counter(row.get("placement") for row in object_plans)),
            "renderDecisionsByDecision": dict(Counter(row.get("decision") for row in render_decisions)),
        },
        "sourceItems": source_items,
        "objectPlans": object_plans,
        "renderDecisions": render_decisions,
        "textFlows": text_flows,
    }


def write_markdown(path: Path, inventory: Dict[str, Any]) -> None:
    lines = [
        "# Page Inventory",
        "",
        f"- extract dir: `{inventory['extractDir']}`",
        f"- pageIndex: `{inventory.get('pageIndex')}`",
        "",
        "## Summary",
        "",
        "```json",
        json.dumps(inventory["summary"], ensure_ascii=False, indent=2),
        "```",
        "",
    ]
    for name, keys in (
        ("sourceItems", ("id", "kind", "parentId", "layerName", "zOrder", "hasText", "textPreview", "bounds")),
        ("objectPlans", ("objectPlanId", "kind", "sourceObjectIds", "textAction", "visualAction", "placement", "visualLayer", "materialization", "zOrder", "file", "reason", "bounds")),
        ("renderDecisions", ("decision", "id", "file", "planVisualAction", "planPlacement", "planVisualLayer", "planZOrder", "detail", "bounds")),
        ("textFlows", ("storyId", "textOwner", "textLength", "inlineSlotCount", "ownerTextFrameIds", "text")),
    ):
        rows = inventory[name]
        lines += [f"## {name} ({len(rows)})", ""]
        if not rows:
            lines.append("_none_\n")
            continue
        header = "| " + " | ".join(keys) + " |"
        sep = "| " + " | ".join("---" for _ in keys) + " |"
        lines += [header, sep]
        for row in rows[:80]:
            lines.append("| " + " | ".join(tsv_escape(row.get(k))[:180] for k in keys) + " |")
        if len(rows) > 80:
            lines.append(f"\n... {len(rows) - 80} more rows omitted.\n")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_inventory(output_dir: Path, inventory: Dict[str, Any]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "page-inventory.json").write_text(json.dumps(inventory, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(output_dir / "page-inventory.md", inventory)
    write_tsv(
        output_dir / "page-items.tsv",
        inventory["sourceItems"],
        ("id", "kind", "parentId", "parentKind", "layerName", "zOrder", "visible", "hasText", "textLength", "textPreview", "bounds"),
    )
    write_tsv(
        output_dir / "object-plans.tsv",
        inventory["objectPlans"],
        ("objectPlanId", "kind", "candidatePurpose", "sourceObjectIds", "visualSourceObjectIds", "ownedTextFrameIds", "textAction", "visualAction", "placement", "visualLayer", "policyLayer", "materialization", "zOrder", "file", "reason", "bounds"),
    )
    write_tsv(
        output_dir / "render-decisions.tsv",
        inventory["renderDecisions"],
        ("phase", "decision", "id", "file", "planVisualAction", "planPlacement", "planVisualLayer", "planPolicyLayer", "planMaterialization", "planZOrder", "detail", "reason", "bounds"),
    )
    write_tsv(
        output_dir / "text-flows.tsv",
        inventory["textFlows"],
        ("storyId", "textOwner", "textLength", "inlineSlotCount", "ownerTextFrameIds", "ownerPageIndexes", "text"),
    )


def page_index_from_issue_metadata(extract_dir: Path, page: int) -> Optional[int]:
    meta_path = extract_dir / "issue-run.json"
    if not meta_path.exists():
        return None
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        printed_start = int(meta.get("printedStartPage"))
        printed_end = int(meta.get("printedEndPage"))
        extract_start = int(meta.get("extractStartPage"))
    except (TypeError, ValueError, json.JSONDecodeError):
        return None
    if page < printed_start or page > printed_end:
        return None
    return extract_start + (page - printed_start) - 1


def page_index_from_args(extract_dir: Path, page: Optional[int], page_index: Optional[int]) -> Optional[int]:
    if page_index is not None:
        return page_index
    if page is None:
        return None
    if page <= 0:
        raise SystemExit("--page is physical and must be >= 1")
    mapped = page_index_from_issue_metadata(extract_dir, page)
    if mapped is not None:
        return mapped
    return page - 1


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Build a page-local inventory from extractor diagnostics.")
    parser.add_argument("extract_dir", type=Path)
    parser.add_argument("--page", type=int, default=None, help="Physical page number. Converted to zero-based pageIndex.")
    parser.add_argument("--page-index", type=int, default=None, help="Raw zero-based diagnostics pageIndex.")
    parser.add_argument("--out", type=Path, default=None, help="Output directory. Defaults to extract_dir.parent/trace.")
    args = parser.parse_args(list(argv) if argv is not None else None)

    if not args.extract_dir.exists():
        raise SystemExit(f"extract dir does not exist: {args.extract_dir}")
    page_index = page_index_from_args(args.extract_dir, args.page, args.page_index)
    out = args.out or (args.extract_dir.parent / "trace")
    inventory = build_inventory(args.extract_dir, page_index)
    write_inventory(out, inventory)
    print(f"page inventory: {out / 'page-inventory.md'}")
    print(json.dumps(inventory["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
