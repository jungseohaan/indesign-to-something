#!/usr/bin/env python3
"""Trace a source/text/page through extraction and conversion diagnostics.

The script does not decide ownership. It only joins existing diagnostics so a
page issue can be inspected without repeatedly grepping large JSON files.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set


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


def as_ints(values: Any) -> Set[int]:
    out: Set[int] = set()
    if not isinstance(values, list):
        return out
    for value in values:
        try:
            out.add(int(value))
        except (TypeError, ValueError):
            pass
    return out


def row_sources(row: Dict[str, Any]) -> Set[int]:
    sources: Set[int] = set()
    for key in (
        "sourceObjectIds",
        "visualSourceObjectIds",
        "styleSourceObjectIds",
        "ownedTextFrameIds",
        "ownerTextFrameIds",
        "exportSourceObjectIds",
        "sourceRootObjectIds",
        "clusterSourceObjectIds",
        "editableTextFrameIds",
        "textFrameIds",
        "hiddenTextFrameIds",
    ):
        sources |= as_ints(row.get(key))
    for key in ("id", "domId", "renderId", "parentId", "ownerTextFrameDomId", "anchoredTextFrameDomId", "anchoredObjectId"):
        value = row.get(key)
        try:
            if value is not None and int(value) >= 0:
                sources.add(int(value))
        except (TypeError, ValueError):
            pass
    return sources


def has_source(row: Dict[str, Any], source_ids: Set[int]) -> bool:
    if not source_ids:
        return False
    return bool(row_sources(row) & source_ids)


def page_matches(row: Dict[str, Any], page: Optional[int]) -> bool:
    if page is None:
        return True
    return row.get("pageIndex") == page


def text_flow_page_matches(row: Dict[str, Any], page: Optional[int]) -> bool:
    if page is None:
        return True
    pages = row.get("ownerPageIndexes")
    if isinstance(pages, list):
        return page in pages
    return row.get("pageIndex") == page


def text_blob(value: Any) -> str:
    if isinstance(value, dict):
        return " ".join(text_blob(v) for v in value.values())
    if isinstance(value, list):
        return " ".join(text_blob(v) for v in value)
    return "" if value is None else str(value)


def snippet_matches(row: Dict[str, Any], snippet: Optional[str]) -> bool:
    if not snippet:
        return False
    return snippet in text_blob(row)


def compact(row: Dict[str, Any], keys: Iterable[str]) -> Dict[str, Any]:
    return {key: row.get(key) for key in keys if key in row and row.get(key) is not None}


def summarize_plans(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    return {
        "count": len(rows),
        "textAction": dict(Counter(row.get("textAction") for row in rows)),
        "visualAction": dict(Counter(row.get("visualAction") for row in rows)),
        "placement": dict(Counter(row.get("placement") for row in rows)),
        "visualLayer": dict(Counter(row.get("visualLayer") for row in rows)),
    }


def summarize_decisions(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    return {
        "count": len(rows),
        "decision": dict(Counter(row.get("decision") for row in rows)),
        "phase": dict(Counter(row.get("phase") for row in rows)),
        "planVisualAction": dict(Counter(row.get("planVisualAction") for row in rows)),
    }


def collect_source_items(extraction_plan: Any, source_ids: Set[int], page: Optional[int], snippet: Optional[str]) -> List[Dict[str, Any]]:
    if not isinstance(extraction_plan, dict):
        return []
    rows = extraction_plan.get("sourceItems") or []
    out: List[Dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict) or not page_matches(row, page):
            continue
        if has_source(row, source_ids) or snippet_matches(row, snippet):
            out.append(row)
    return out


def source_items_document(extraction_plan: Any, source_graph: Any) -> Dict[str, Any]:
    if isinstance(extraction_plan, dict) and extraction_plan.get("sourceItems"):
        return extraction_plan
    if isinstance(source_graph, dict) and source_graph.get("nodes"):
        return {"sourceItems": source_graph.get("nodes") or []}
    if isinstance(source_graph, dict) and source_graph.get("nodePreview"):
        return {"sourceItems": source_graph.get("nodePreview") or []}
    if isinstance(extraction_plan, dict):
        summary = extraction_plan.get("sourceItemSummary")
        if isinstance(summary, dict) and summary.get("sourceItemPreview"):
            return {"sourceItems": summary.get("sourceItemPreview") or []}
    return extraction_plan if isinstance(extraction_plan, dict) else {}


def collect_candidates(extraction_plan: Any, source_ids: Set[int], page: Optional[int], snippet: Optional[str]) -> List[Dict[str, Any]]:
    if not isinstance(extraction_plan, dict):
        return []
    rows = extraction_plan.get("candidates") or []
    if not rows:
        summary = extraction_plan.get("candidateSummary")
        if isinstance(summary, dict):
            rows = summary.get("candidatePreview") or []
    out: List[Dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict) or not page_matches(row, page):
            continue
        if has_source(row, source_ids) or snippet_matches(row, snippet):
            out.append(row)
    return out


def collect_object_plans(object_plans_json: Any, source_ids: Set[int], page: Optional[int], snippet: Optional[str]) -> List[Dict[str, Any]]:
    if not isinstance(object_plans_json, dict):
        return []
    rows = object_plans_json.get("objectPlans") or []
    out: List[Dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict) or not page_matches(row, page):
            continue
        if has_source(row, source_ids) or snippet_matches(row, snippet):
            out.append(row)
    return out


def collect_jsonl(rows: List[Dict[str, Any]], source_ids: Set[int], page: Optional[int], snippet: Optional[str]) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for row in rows:
        if not page_matches(row, page):
            continue
        if has_source(row, source_ids) or snippet_matches(row, snippet):
            out.append(row)
    return out


def text_flow_sources(row: Dict[str, Any]) -> Set[int]:
    sources = row_sources(row)
    for paragraph in row.get("paragraphs") or []:
        if not isinstance(paragraph, dict):
            continue
        for run in paragraph.get("runs") or []:
            if isinstance(run, dict):
                sources |= row_sources(run)
    return sources


def collect_text_flows(rows: List[Dict[str, Any]], source_ids: Set[int], page: Optional[int], snippet: Optional[str]) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for row in rows:
        if not text_flow_page_matches(row, page):
            continue
        sources = text_flow_sources(row)
        if (source_ids and (sources & source_ids)) or snippet_matches(row, snippet):
            out.append(row)
    return out


def collect_results(extraction_results: Any, source_ids: Set[int], page: Optional[int], snippet: Optional[str]) -> List[Dict[str, Any]]:
    if not isinstance(extraction_results, dict):
        return []
    rows = extraction_results.get("results") or []
    out: List[Dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict) or not page_matches(row, page):
            continue
        if has_source(row, source_ids) or snippet_matches(row, snippet):
            out.append(row)
    return out


def expand_sources(groups: Iterable[Iterable[Dict[str, Any]]], seed: Set[int]) -> Set[int]:
    sources = set(seed)
    for rows in groups:
        for row in rows:
            sources |= row_sources(row)
            if "paragraphs" in row:
                sources |= text_flow_sources(row)
    return sources


def image_paths(extract_dir: Path, rows: Iterable[Dict[str, Any]]) -> List[str]:
    paths: List[str] = []
    seen: Set[str] = set()
    for row in rows:
        for key in ("file", "sourceFile", "planFile"):
            value = row.get(key)
            if not value:
                continue
            p = Path(str(value))
            if not p.is_absolute():
                p = extract_dir / p
            s = str(p)
            if s not in seen and p.exists():
                seen.add(s)
                paths.append(s)
    return paths


def write_markdown(path: Path, report: Dict[str, Any]) -> None:
    lines = [
        "# Source Trace",
        "",
        f"- extract dir: `{report['extractDir']}`",
        f"- page: `{report.get('page', 'all')}`",
        f"- query sources: `{', '.join(map(str, sorted(report['querySourceIds']))) or '-'}`",
        f"- expanded sources: `{', '.join(map(str, sorted(report['expandedSourceIds']))) or '-'}`",
        f"- snippet: `{report.get('snippet') or '-'}`",
        "",
        "## Summary",
        "",
        "```json",
        json.dumps(report["summary"], ensure_ascii=False, indent=2),
        "```",
        "",
    ]
    for section in ("textFlows", "sourceItems", "candidates", "objectPlans", "ownershipPlans", "renderDecisions", "extractionResults"):
        rows = report[section]
        lines += [f"## {section} ({len(rows)})", ""]
        for row in rows[:30]:
            lines += [
                "```json",
                json.dumps(row, ensure_ascii=False, indent=2),
                "```",
                "",
            ]
        if len(rows) > 30:
            lines.append(f"... {len(rows) - 30} more rows omitted.\n")
    if report["imagePaths"]:
        lines += ["## Images", ""]
        for p in report["imagePaths"]:
            lines.append(f"- `{p}`")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def build_report(extract_dir: Path, source_ids: Set[int], page: Optional[int], snippet: Optional[str]) -> Dict[str, Any]:
    extraction_plan = load_json(extract_dir / "extraction-plan.json")
    source_graph = load_json(extract_dir / "source-graph.json")
    source_items_doc = source_items_document(extraction_plan, source_graph)
    object_plans_json = load_json(extract_dir / "object-plans.json")
    extraction_results = load_json(extract_dir / "extraction-results.json")
    text_flows = load_jsonl(extract_dir / "text-flow.jsonl")
    ownership_plan = load_jsonl(extract_dir / "ownership-plan.jsonl")
    render_decisions = load_jsonl(extract_dir / "render-decisions.jsonl")

    text_flow_rows = collect_text_flows(text_flows, source_ids, page, snippet)
    source_ids = expand_sources([text_flow_rows], source_ids)
    source_items = collect_source_items(source_items_doc, source_ids, page, snippet)
    candidates = collect_candidates(extraction_plan, source_ids, page, snippet)
    object_plans = collect_object_plans(object_plans_json, source_ids, page, snippet)
    ownership_rows = collect_jsonl(ownership_plan, source_ids, page, snippet)
    decision_rows = collect_jsonl(render_decisions, source_ids, page, snippet)
    result_rows = collect_results(extraction_results, source_ids, page, snippet)

    expanded = expand_sources(
        [text_flow_rows, source_items, candidates, object_plans, ownership_rows, decision_rows, result_rows],
        source_ids,
    )
    if expanded != source_ids:
        text_flow_rows = collect_text_flows(text_flows, expanded, page, snippet)
        source_items = collect_source_items(source_items_doc, expanded, page, snippet)
        candidates = collect_candidates(extraction_plan, expanded, page, snippet)
        object_plans = collect_object_plans(object_plans_json, expanded, page, snippet)
        ownership_rows = collect_jsonl(ownership_plan, expanded, page, snippet)
        decision_rows = collect_jsonl(render_decisions, expanded, page, snippet)
        result_rows = collect_results(extraction_results, expanded, page, snippet)

    compact_source_items = [
        compact(row, ("id", "pageIndex", "kind", "parentId", "parentKind", "bounds", "zOrder", "layerName", "visible", "textFrameClass", "textLength", "hasText", "textPreview"))
        for row in source_items
    ]
    compact_candidates = [
        compact(row, ("candidateId", "passId", "pageIndex", "purpose", "reason", "sourceObjectIds", "visualSourceObjectIds", "exportSourceObjectIds", "ownedTextFrameIds", "slotRole", "renderMode", "bounds"))
        for row in candidates
    ]
    compact_object_plans = [
        compact(row, ("objectPlanId", "bundleId", "candidateId", "pageIndex", "kind", "candidatePurpose", "sourceObjectIds", "visualSourceObjectIds", "styleSourceObjectIds", "ownedTextFrameIds", "materialization", "textAction", "visualAction", "placement", "coordinateSpace", "visualLayer", "zOrder", "reason", "file", "bounds"))
        for row in object_plans
    ]
    compact_ownership = [
        compact(row, ("domId", "kind", "pageIndex", "sourceObjectIds", "textAction", "visualAction", "visualLayer", "policyLayer", "placement", "materialization", "zOrder", "reason", "file", "bounds"))
        for row in ownership_rows
    ]
    compact_decisions = [
        compact(row, ("phase", "decision", "detail", "id", "pageIndex", "file", "sourceFile", "itemType", "reason", "visualOwner", "textOwner", "planTextAction", "planVisualAction", "planVisualLayer", "planPolicyLayer", "planPlacement", "planMaterialization", "planZOrder", "planReason", "bounds", "sourceBounds"))
        for row in decision_rows
    ]
    compact_results = [
        compact(row, ("exportId", "status", "id", "pageIndex", "type", "planPassId", "candidateId", "candidateMatchStrategy", "file", "bounds", "reason", "visualOwner", "textOwner", "sourceObjectIds", "exportSourceObjectIds", "editableTextFrameIds", "textFrameIds", "hiddenTextFrameIds"))
        for row in result_rows
    ]
    compact_text_flows = [
        compact(row, ("storyId", "textOwner", "textLength", "inlineSlotCount", "ownerTextFrameIds", "ownerPageIndexes", "paragraphs"))
        for row in text_flow_rows
    ]

    return {
        "extractDir": str(extract_dir),
        "page": page,
        "querySourceIds": sorted(source_ids),
        "expandedSourceIds": sorted(expanded),
        "snippet": snippet,
        "summary": {
            "textFlows": len(compact_text_flows),
            "sourceItems": len(compact_source_items),
            "candidates": len(compact_candidates),
            "objectPlans": summarize_plans(object_plans),
            "ownershipPlans": summarize_plans(ownership_rows),
            "renderDecisions": summarize_decisions(decision_rows),
            "extractionResults": {
                "count": len(result_rows),
                "status": dict(Counter(row.get("status") for row in result_rows)),
                "type": dict(Counter(row.get("type") for row in result_rows)),
            },
        },
        "textFlows": compact_text_flows,
        "sourceItems": compact_source_items,
        "candidates": compact_candidates,
        "objectPlans": compact_object_plans,
        "ownershipPlans": compact_ownership,
        "renderDecisions": compact_decisions,
        "extractionResults": compact_results,
        "imagePaths": image_paths(extract_dir, list(decision_rows) + list(result_rows) + list(object_plans)),
    }


def parse_sources(values: Optional[List[str]]) -> Set[int]:
    sources: Set[int] = set()
    for value in values or []:
        for part in value.replace(",", " ").split():
            sources.add(int(part))
    return sources


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
    parser = argparse.ArgumentParser(description="Trace source ids or text snippets through extractor diagnostics.")
    parser.add_argument("extract_dir", type=Path)
    parser.add_argument("--source", action="append", help="Source id. May be repeated or comma-separated.")
    parser.add_argument("--page", type=int, default=None, help="Physical page number. Converted to zero-based pageIndex.")
    parser.add_argument("--page-index", type=int, default=None, help="Raw zero-based diagnostics pageIndex.")
    parser.add_argument("--snippet", default=None, help="Text snippet to search in diagnostics.")
    parser.add_argument("--json", type=Path, default=None, help="Write full JSON report.")
    parser.add_argument("--markdown", type=Path, default=None, help="Write Markdown report.")
    args = parser.parse_args(list(argv) if argv is not None else None)

    extract_dir = args.extract_dir
    if not extract_dir.exists():
        raise SystemExit(f"extract dir does not exist: {extract_dir}")
    source_ids = parse_sources(args.source)
    if not source_ids and not args.snippet:
        raise SystemExit("Provide --source and/or --snippet.")

    page_index = page_index_from_args(extract_dir, args.page, args.page_index)
    report = build_report(extract_dir, source_ids, page_index, args.snippet)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    print(f"expanded sources: {', '.join(map(str, report['expandedSourceIds'])) or '-'}")
    if report["imagePaths"]:
        print("images:")
        for p in report["imagePaths"][:20]:
            print(f"  {p}")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        write_markdown(args.markdown, report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
