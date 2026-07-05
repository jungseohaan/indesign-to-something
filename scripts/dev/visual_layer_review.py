#!/usr/bin/env python3
"""Build visual-layer overlap diagnostics for human review.

The report is intentionally policy-shaped: it reads Stage 1 ObjectPlan output
and resolved TextFrame metadata, then highlights visible visual plans that
overlap editable HWPX text frames. It does not decide ownership or add page
exceptions.
"""

from __future__ import annotations

import argparse
import html
import json
import shutil
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


BEHIND_TEXT_LAYERS = {
    "PAGE_BACKGROUND",
    "CONTAINER_BACKDROP",
    "TEXT_CARD_BACKDROP",
    "CONTENT_BACKDROP",
}

IN_FRONT_LAYERS = {
    "CONTAINER_FACE",
    "LABEL_CONNECTOR_BACKDROP",
    "LABEL_BACKDROP",
    "LABEL_OVERLAY_BACKDROP",
    "CONTENT_VISUAL",
    "CONTAINER_OUTLINE",
    "FOREGROUND_MASK",
}

VISIBLE_VISUAL_ACTIONS = {
    "PLACE_INLINE_PNG",
    "PLACE_FLOATING_PNG",
    "PLACE_TEXT_SHELL",
}

PLACED_CONTENT_TYPES = {"Image", "PDF", "EPS"}
SHELL_SOURCE_TYPES = {"Group", "Rectangle", "Oval", "Polygon", "GraphicLine"}


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


def as_int(value: Any, fallback: int = -1) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def bounds(row: Dict[str, Any], key: str = "bounds") -> Optional[Tuple[float, float, float, float]]:
    value = row.get(key)
    if not isinstance(value, list) or len(value) < 4:
        return None
    try:
        t, l, b, r = (float(value[0]), float(value[1]), float(value[2]), float(value[3]))
    except (TypeError, ValueError):
        return None
    if b <= t or r <= l:
        return None
    return (t, l, b, r)


def area(b: Optional[Tuple[float, float, float, float]]) -> float:
    if b is None:
        return 0.0
    return max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])


def overlap(a: Optional[Tuple[float, float, float, float]],
            b: Optional[Tuple[float, float, float, float]]) -> Optional[Tuple[float, float, float, float]]:
    if a is None or b is None:
        return None
    t = max(a[0], b[0])
    l = max(a[1], b[1])
    bottom = min(a[2], b[2])
    r = min(a[3], b[3])
    if bottom <= t or r <= l:
        return None
    return (t, l, bottom, r)


def short_text(value: Any, limit: int = 80) -> str:
    text = " ".join(str(value or "").split())
    if len(text) <= limit:
        return text
    return text[:limit - 1] + "…"


def id_list(value: Any) -> List[int]:
    if not isinstance(value, list):
        return []
    out: List[int] = []
    for item in value:
        parsed = as_int(item, -1)
        if parsed >= 0:
            out.append(parsed)
    return out


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


def page_indexes_from_args(extract_dir: Path, pages: Sequence[int], page_indexes: Sequence[int]) -> Optional[set[int]]:
    selected: set[int] = set()
    for page_index in page_indexes:
        selected.add(page_index)
    for page in pages:
        if page <= 0:
            raise SystemExit("--page is physical and must be >= 1")
        mapped = page_index_from_issue_metadata(extract_dir, page)
        selected.add(mapped if mapped is not None else page - 1)
    return selected or None


def page_name_by_index(resolved: Dict[str, Any]) -> Dict[int, str]:
    out: Dict[int, str] = {}
    for page in resolved.get("pages") or []:
        if not isinstance(page, dict):
            continue
        index = as_int(page.get("index"), -1)
        if index >= 0:
            out[index] = str(page.get("name") or index + 1)
    return out


def page_bounds_by_index(resolved: Dict[str, Any]) -> Dict[int, Tuple[float, float, float, float]]:
    out: Dict[int, Tuple[float, float, float, float]] = {}
    for page in resolved.get("pages") or []:
        if not isinstance(page, dict):
            continue
        index = as_int(page.get("index"), -1)
        b = bounds(page)
        if index >= 0 and b is not None:
            out[index] = b
    return out


def source_item_by_id(resolved: Dict[str, Any]) -> Dict[int, Dict[str, Any]]:
    out: Dict[int, Dict[str, Any]] = {}
    for row in resolved.get("pageItems") or []:
        if not isinstance(row, dict):
            continue
        item_id = as_int(row.get("id"), -1)
        if item_id >= 0:
            out[item_id] = row
    return out


def is_editable_text_frame(row: Dict[str, Any], editable_ids: set[int]) -> bool:
    tf_id = as_int(row.get("id"), -1)
    if editable_ids and tf_id not in editable_ids:
        return False
    if row.get("visible") is False or row.get("hiddenByParent") is True:
        return False
    text = row.get("frameVisibleText")
    return bool(str(text or "").strip())


def text_plan_by_text_frame_id(ownership_plans: Sequence[Dict[str, Any]]) -> Dict[int, Dict[str, Any]]:
    out: Dict[int, Dict[str, Any]] = {}
    for row in ownership_plans:
        if row.get("textAction") != "OWNED_BY_HWPX_TEXT":
            continue
        if row.get("materialization") not in {"HWPX_TEXT", "HWPX_TABLE_STYLE"}:
            continue
        ids = id_list(row.get("ownedTextFrameIds"))
        if not ids:
            dom_id = as_int(row.get("domId"), -1)
            if dom_id >= 0:
                ids = [dom_id]
        for tf_id in ids:
            current = out.get(tf_id)
            if current is None or as_int(row.get("zOrder"), 0) > as_int(current.get("zOrder"), 0):
                out[tf_id] = row
    return out


def collect_text_frames(
        resolved: Dict[str, Any],
        selected_pages: Optional[set[int]],
        text_plans_by_id: Dict[int, Dict[str, Any]]) -> List[Dict[str, Any]]:
    editable_ids = {as_int(v, -1) for v in resolved.get("editableTextFrameIds") or []}
    editable_ids.discard(-1)
    out: List[Dict[str, Any]] = []
    for row in resolved.get("textFrames") or []:
        if not isinstance(row, dict) or not is_editable_text_frame(row, editable_ids):
            continue
        tf_id = as_int(row.get("id"), -1)
        page_index = as_int(row.get("pageIndex"), -1)
        if selected_pages is not None and page_index not in selected_pages:
            continue
        b = bounds(row, "pageRelativeBounds") or bounds(row, "geometricBounds")
        if b is None:
            continue
        text_plan = text_plans_by_id.get(tf_id)
        effective_z = as_int(text_plan.get("zOrder"), as_int(row.get("zOrder"), 0)) if text_plan else as_int(row.get("zOrder"), 0)
        out.append({
            "id": tf_id,
            "pageIndex": page_index,
            "zOrder": effective_z,
            "resolvedZOrder": as_int(row.get("zOrder"), 0),
            "textPlanKnown": text_plan is not None,
            "textPlanReason": text_plan.get("reason") if text_plan else None,
            "bounds": list(b),
            "text": short_text(row.get("frameVisibleText"), 120),
            "storyId": row.get("storyId"),
        })
    return out


def is_visible_visual_plan(row: Dict[str, Any]) -> bool:
    return row.get("visualAction") in VISIBLE_VISUAL_ACTIONS and bounds(row) is not None


def collect_visual_plans(extract_dir: Path, selected_pages: Optional[set[int]]) -> List[Dict[str, Any]]:
    resolved = load_json(extract_dir / "resolved.json") or {}
    return collect_visual_plans_from_rows(
        load_jsonl(extract_dir / "ownership-plan.jsonl"),
        selected_pages,
        resolved)


def source_profile(
        row: Dict[str, Any],
        source_items: Dict[int, Dict[str, Any]],
        page_bounds: Dict[int, Tuple[float, float, float, float]]) -> Dict[str, Any]:
    source_ids = id_list(row.get("visualSourceObjectIds")) or id_list(row.get("sourceObjectIds"))
    source_id_set = set(source_ids)
    type_counts = Counter()
    layer_counts = Counter()
    page_level_shape_roots: List[int] = []
    low_opacity_placed_children: List[int] = []
    for source_id in source_ids:
        item = source_items.get(source_id)
        if not item:
            type_counts["<missing>"] += 1
            continue
        item_type = str(item.get("type") or "<blank>")
        type_counts[item_type] += 1
        layer_counts[str(item.get("layerName") or "<blank>")] += 1
        if item_type in SHELL_SOURCE_TYPES and not str(item.get("parentId") or "").strip():
            page_level_shape_roots.append(source_id)
        if item_type in PLACED_CONTENT_TYPES:
            try:
                opacity = float(item.get("opacity"))
            except (TypeError, ValueError):
                opacity = 100.0
            parent_id = as_int(item.get("parentId"), -1)
            if 0.0 < opacity <= 35.0 and (not source_id_set or parent_id in source_id_set):
                low_opacity_placed_children.append(source_id)
    b = bounds(row)
    page_b = page_bounds.get(as_int(row.get("pageIndex"), -1))
    page_area_ratio = area(b) / area(page_b) if page_b is not None and area(page_b) > 0.0 else 0.0
    return {
        "sourceTypeCounts": dict(type_counts),
        "sourceLayerCounts": dict(layer_counts),
        "pageAreaRatio": round(page_area_ratio, 4),
        "hasPlacedContentSource": any(k in PLACED_CONTENT_TYPES for k in type_counts),
        "hasPageLevelShapeRoot": bool(page_level_shape_roots),
        "hasLowOpacityPlacedChild": bool(low_opacity_placed_children),
        "shapeShellOnlySources": bool(type_counts)
        and all(k in SHELL_SOURCE_TYPES or k == "<missing>" for k in type_counts),
    }


def content_backdrop_class(plan: Dict[str, Any]) -> str:
    slot_role = str(plan.get("slotRole") or "")
    if slot_role != "CONTENT_VISUAL_SLOT":
        return ""
    layer = str(plan.get("visualLayer") or "")
    policy_layer = str(plan.get("policyLayer") or "")
    if layer not in {"PAGE_BACKGROUND", "CONTAINER_BACKDROP"} and policy_layer != "BACKGROUND":
        return ""
    reason = str(plan.get("reason") or "")
    action = str(plan.get("visualAction") or "")
    if reason in {"page_spanning_backdrop_visual", "page_spanning_backdrop_visual_fragment"}:
        return "OK_EXPLICIT_PAGE_SPANNING_BACKDROP_CONTRACT"
    if action == "PLACE_TEXT_SHELL" and plan.get("shapeShellOnlySources"):
        return "REVIEW_CONTENT_SLOT_SHAPE_SHELL_MISMATCH"
    if action == "PLACE_TEXT_SHELL":
        return "REVIEW_CONTENT_SLOT_TEXT_SHELL_MISMATCH"
    if reason in {"master_graphic", "master_page_graphic", "master_side_composite"}:
        if plan.get("materialization") == "TEXTLESS_VISUAL_FRAGMENT":
            return "OK_MASTER_GRAPHIC_BACKGROUND_FRAGMENT_CONTRACT"
        return "REVIEW_MASTER_GRAPHIC_BACKGROUND_CONTRACT"
    if (plan.get("hasPlacedContentSource")
            and plan.get("hasPageLevelShapeRoot")
            and plan.get("hasLowOpacityPlacedChild")):
        return "OK_SOURCE_AUTHORED_PAGE_WASH_BACKDROP_CONTRACT"
    if plan.get("hasPlacedContentSource"):
        return "REVIEW_PLACED_CONTENT_BACKGROUND_CONTRACT"
    return "REVIEW_CONTENT_SLOT_BACKGROUND_CONTRACT"


def collect_visual_plans_from_rows(
        ownership_plans: Sequence[Dict[str, Any]],
        selected_pages: Optional[set[int]],
        resolved: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
    source_items = source_item_by_id(resolved or {})
    page_bounds = page_bounds_by_index(resolved or {})
    out: List[Dict[str, Any]] = []
    for row in ownership_plans:
        if not is_visible_visual_plan(row):
            continue
        page_index = as_int(row.get("pageIndex"), -1)
        if selected_pages is not None and page_index not in selected_pages:
            continue
        b = bounds(row)
        if b is None:
            continue
        plan = {
            "domId": row.get("domId"),
            "kind": row.get("kind"),
            "candidateId": row.get("candidateId"),
            "pageIndex": page_index,
            "textAction": row.get("textAction"),
            "visualAction": row.get("visualAction"),
            "visualLayer": row.get("visualLayer"),
            "policyLayer": row.get("policyLayer"),
            "placement": row.get("placement"),
            "materialization": row.get("materialization"),
            "slotRole": row.get("slotRole"),
            "planPassId": row.get("planPassId"),
            "zOrder": as_int(row.get("zOrder"), 0),
            "sourceObjectIds": id_list(row.get("sourceObjectIds")),
            "sourceRootObjectIds": id_list(row.get("sourceRootObjectIds")),
            "visualSourceObjectIds": id_list(row.get("visualSourceObjectIds")),
            "ownedTextFrameIds": id_list(row.get("ownedTextFrameIds")),
            "sourceBundleKey": row.get("sourceBundleKey"),
            "sourceLayerName": row.get("sourceLayerName"),
            "sourceLayerIndex": row.get("sourceLayerIndex"),
            "reason": row.get("reason"),
            "file": row.get("file"),
            "bounds": list(b),
        }
        plan.update(source_profile(row, source_items, page_bounds))
        plan["contentBackdropClass"] = content_backdrop_class(plan)
        out.append(plan)
    return out


def plane_for_layer(layer: str) -> str:
    if layer in BEHIND_TEXT_LAYERS:
        return "BEHIND_TEXT"
    if layer in IN_FRONT_LAYERS:
        return "IN_FRONT_OF_TEXT"
    return "UNKNOWN"


def text_owner_ids_by_frame(ownership_plans: Sequence[Dict[str, Any]]) -> Dict[int, List[str]]:
    out: Dict[int, List[str]] = defaultdict(list)
    for row in ownership_plans:
        ids = id_list(row.get("ownedTextFrameIds"))
        if not ids:
            continue
        label = str(row.get("domId") or row.get("candidateId") or row.get("reason") or "")
        for tf_id in ids:
            out[tf_id].append(label)
    return out


def text_relation(plan: Dict[str, Any], tf: Dict[str, Any], owners_by_tf: Dict[int, List[str]]) -> str:
    tf_id = as_int(tf.get("id"), -1)
    parts: List[str] = []
    if tf_id in set(plan.get("ownedTextFrameIds") or []):
        parts.append("OWNED_BY_VISUAL")
    if tf_id in set(plan.get("sourceObjectIds") or []):
        parts.append("TF_IN_SOURCE")
    if tf_id in set(plan.get("visualSourceObjectIds") or []):
        parts.append("TF_IN_VISUAL_SOURCE")
    if tf_id in set(plan.get("styleSourceObjectIds") or []):
        parts.append("TF_IN_STYLE_SOURCE")
    if tf_id in set(plan.get("hiddenVisualSourceObjectIds") or []):
        parts.append("TF_IN_HIDDEN_SOURCE")
    if owners_by_tf.get(tf_id):
        parts.append("TF_HAS_OWNER")
    return "+".join(parts) if parts else "INDEPENDENT_OVERLAP"


def classify_overlap(
        plan: Dict[str, Any],
        tf: Dict[str, Any],
        overlap_bounds: Tuple[float, float, float, float],
        relation: str) -> str:
    layer = str(plan.get("visualLayer") or "")
    plane = plane_for_layer(layer)
    owned = tf["id"] in set(plan.get("ownedTextFrameIds") or [])
    ratio = area(overlap_bounds) / max(area(tuple(tf["bounds"])), 0.0001)
    plan_z = as_int(plan.get("zOrder"), 0)
    text_z = as_int(tf.get("zOrder"), 0)
    if plane == "IN_FRONT_OF_TEXT" and owned:
        if plan_z >= text_z:
            return "HIGH_OWNED_TF_FRONT_Z_CONFLICT"
        return "INFO_OWNED_TF_FRONT_Z_OK"
    if plane == "IN_FRONT_OF_TEXT":
        if plan_z >= text_z:
            if "TF_IN_SOURCE" in relation or "TF_IN_VISUAL_SOURCE" in relation:
                return "HIGH_SOURCE_RELATED_FRONT_Z_CONFLICT"
            return "REVIEW_INDEPENDENT_FRONT_Z_OVERLAP"
        return "LOW_FRONT_PLANE_BEHIND_TEXT_Z"
    if plane == "UNKNOWN":
        return "MEDIUM_UNKNOWN_PLANE_OVERLAP"
    if plan_z >= text_z and ratio >= 0.25:
        return "LOW_BEHIND_PLANE_SOURCE_Z_CHECK"
    return "INFO_BEHIND_PLANE_OVERLAP"


def ownership_diagnostic(plan: Dict[str, Any], tf: Dict[str, Any], relation: str) -> str:
    layer = str(plan.get("visualLayer") or "")
    visual_action = str(plan.get("visualAction") or "")
    text_action = str(plan.get("textAction") or "")
    policy_layer = str(plan.get("policyLayer") or "")
    slot_role = str(plan.get("slotRole") or "")
    tf_id = as_int(tf.get("id"), -1)
    owned = tf_id in set(plan.get("ownedTextFrameIds") or [])
    if (visual_action == "PLACE_TEXT_SHELL"
            and text_action == "OWNED_BY_HWPX_TEXT"
            and not owned
            and layer in BEHIND_TEXT_LAYERS):
        return "OK_TEXT_SHELL_BACKDROP_OVER_NON_OWNED_HWPX_TEXT"
    if (visual_action == "PLACE_TEXT_SHELL"
            and text_action == "OWNED_BY_HWPX_TEXT"
            and not owned
            and layer in IN_FRONT_LAYERS):
        return "RISK_TEXT_SHELL_FRONT_OVER_NON_OWNED_HWPX_TEXT"
    if (slot_role == "CONTENT_VISUAL_SLOT"
            and layer in {"PAGE_BACKGROUND", "CONTAINER_BACKDROP"}
            and policy_layer == "BACKGROUND"):
        return str(plan.get("contentBackdropClass") or "REVIEW_CONTENT_VISUAL_SLOT_IN_BACKGROUND_PLANE")
    if "TF_HAS_OWNER" in relation and layer in BEHIND_TEXT_LAYERS:
        return "OK_VISUAL_BEHIND_HWPX_TEXT_OWNER"
    return "CHECK_VISUAL_TEXT_RELATION"


def build_review(extract_dir: Path, selected_pages: Optional[set[int]], min_overlap: float) -> Dict[str, Any]:
    resolved = load_json(extract_dir / "resolved.json") or {}
    ownership_plans = load_jsonl(extract_dir / "ownership-plan.jsonl")
    text_plans_by_id = text_plan_by_text_frame_id(ownership_plans)
    owners_by_tf = text_owner_ids_by_frame(ownership_plans)
    page_names = page_name_by_index(resolved)
    page_bounds = page_bounds_by_index(resolved)
    text_frames = collect_text_frames(resolved, selected_pages, text_plans_by_id)
    visual_plans = collect_visual_plans_from_rows(ownership_plans, selected_pages, resolved)
    text_by_page: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    for tf in text_frames:
        text_by_page[tf["pageIndex"]].append(tf)

    overlaps: List[Dict[str, Any]] = []
    for plan in visual_plans:
        pb = tuple(plan["bounds"])
        for tf in text_by_page.get(plan["pageIndex"], []):
            ob = overlap(pb, tuple(tf["bounds"]))
            if ob is None or area(ob) < min_overlap:
                continue
            relation = text_relation(plan, tf, owners_by_tf)
            diagnostic = ownership_diagnostic(plan, tf, relation)
            overlaps.append({
                "pageIndex": plan["pageIndex"],
                "pageName": page_names.get(plan["pageIndex"], str(plan["pageIndex"] + 1)),
                "severity": classify_overlap(plan, tf, ob, relation),
                "diagnostic": diagnostic,
                "plane": plane_for_layer(str(plan.get("visualLayer") or "")),
                "textRelation": relation,
                "overlapArea": round(area(ob), 3),
                "overlapBounds": [round(v, 3) for v in ob],
                "visual": plan,
                "textFrame": tf,
            })

    overlaps.sort(key=lambda row: (
        row["pageIndex"],
        str(row["severity"]),
        row["visual"].get("zOrder", 0),
        str(row["visual"].get("domId")),
        row["textFrame"].get("id", 0),
    ))
    pages = sorted({*(tf["pageIndex"] for tf in text_frames), *(p["pageIndex"] for p in visual_plans)})
    summary = {
        "extractDir": str(extract_dir),
        "pages": len(pages),
        "textFrames": len(text_frames),
        "textFramesWithPlan": sum(1 for tf in text_frames if tf.get("textPlanKnown")),
        "visibleVisualPlans": len(visual_plans),
        "overlaps": len(overlaps),
        "severityCounts": dict(Counter(row["severity"] for row in overlaps)),
        "diagnosticCounts": dict(Counter(row["diagnostic"] for row in overlaps)),
        "textRelationCounts": dict(Counter(row["textRelation"] for row in overlaps)),
        "visualLayerCounts": dict(Counter(row["visual"].get("visualLayer") for row in overlaps)),
        "contentBackdropClassCounts": dict(Counter(
            row["visual"].get("contentBackdropClass")
            for row in overlaps
            if row["visual"].get("contentBackdropClass"))),
        "planeCounts": dict(Counter(row["plane"] for row in overlaps)),
    }
    return {
        "summary": summary,
        "pageNames": page_names,
        "pageBounds": {str(k): list(v) for k, v in page_bounds.items()},
        "textFrames": text_frames,
        "visualPlans": visual_plans,
        "overlaps": overlaps,
    }


def esc(value: Any) -> str:
    return html.escape("" if value is None else str(value), quote=True)


def rect_attrs(b: Sequence[float]) -> str:
    t, l, bottom, r = [float(v) for v in b[:4]]
    return f'x="{l:.3f}" y="{t:.3f}" width="{r - l:.3f}" height="{bottom - t:.3f}"'


def svg_for_page(review: Dict[str, Any], page_index: int) -> str:
    page_b = review["pageBounds"].get(str(page_index), [0, 0, 280, 220])
    t, l, bottom, r = [float(v) for v in page_b[:4]]
    width = r - l
    height = bottom - t
    text_frames = [tf for tf in review["textFrames"] if tf["pageIndex"] == page_index]
    overlaps = [row for row in review["overlaps"] if row["pageIndex"] == page_index]
    parts = [
        f'<svg viewBox="{l} {t} {width} {height}" class="page-map" role="img">',
        f'<rect {rect_attrs(page_b)} class="page-bg"><title>page {page_index}</title></rect>',
    ]
    for tf in text_frames:
        title = f'TF {tf["id"]} z={tf["zOrder"]}: {tf["text"]}'
        parts.append(f'<rect {rect_attrs(tf["bounds"])} class="tf-box"><title>{esc(title)}</title></rect>')
    for row in overlaps:
        plan = row["visual"]
        cls = "risk-high" if row["severity"].startswith("HIGH") else ("risk-medium" if row["severity"].startswith("MEDIUM") or row["severity"].startswith("REVIEW") else "risk-low")
        title = (
            f'{row["severity"]} visual={plan.get("domId")} layer={plan.get("visualLayer")} '
            f'z={plan.get("zOrder")} TF={row["textFrame"].get("id")} area={row["overlapArea"]}'
        )
        parts.append(f'<rect {rect_attrs(plan["bounds"])} class="visual-box {cls}"><title>{esc(title)}</title></rect>')
        parts.append(f'<rect {rect_attrs(row["overlapBounds"])} class="overlap-box"><title>{esc(title)}</title></rect>')
    parts.append("</svg>")
    return "\n".join(parts)


def write_json(path: Path, review: Dict[str, Any]) -> None:
    path.write_text(json.dumps(review, ensure_ascii=False, indent=2), encoding="utf-8")


def write_tsv(path: Path, review: Dict[str, Any]) -> None:
    cols = [
        "pageIndex", "pageName", "severity", "diagnostic", "plane", "textRelation", "overlapArea",
        "visualDomId", "visualLayer", "policyLayer", "slotRole", "contentBackdropClass",
        "visualAction", "placement", "zOrder", "pageAreaRatio", "sourceTypeCounts",
        "textFrameId", "textZOrder", "ownedTextFrameIds", "file", "reason", "text",
    ]
    lines = ["\t".join(cols)]
    for row in review["overlaps"]:
        plan = row["visual"]
        tf = row["textFrame"]
        values = [
            row["pageIndex"], row["pageName"], row["severity"], row["diagnostic"],
            row["plane"], row["textRelation"], row["overlapArea"],
            plan.get("domId"), plan.get("visualLayer"), plan.get("policyLayer"), plan.get("slotRole"),
            plan.get("contentBackdropClass"), plan.get("visualAction"), plan.get("placement"),
            plan.get("zOrder"), plan.get("pageAreaRatio"), json.dumps(plan.get("sourceTypeCounts"), ensure_ascii=False),
            tf.get("id"), tf.get("zOrder"), ",".join(str(v) for v in plan.get("ownedTextFrameIds") or []),
            plan.get("file"), plan.get("reason"), tf.get("text"),
        ]
        lines.append("\t".join(esc(v).replace("\t", " ").replace("\n", " ") for v in values))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_markdown(path: Path, review: Dict[str, Any]) -> None:
    lines = [
        "# Visual Layer Review",
        "",
        "## Summary",
        "",
        "```json",
        json.dumps(review["summary"], ensure_ascii=False, indent=2),
        "```",
        "",
        "## Highest Risk Overlaps",
        "",
        "| page | severity | diagnostic | content class | relation | visual | layer | policy | slot | z | TF | area | file | reason | text |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    focus_rows = [
        row for row in review["overlaps"]
        if row["severity"].startswith("HIGH") or row["severity"].startswith("REVIEW")
    ]
    for row in focus_rows[:160]:
        plan = row["visual"]
        tf = row["textFrame"]
        lines.append(
            "| "
            + " | ".join(esc(v) for v in (
                row["pageName"],
                row["severity"],
                row.get("diagnostic"),
                plan.get("contentBackdropClass"),
                row.get("textRelation"),
                plan.get("domId"),
                plan.get("visualLayer"),
                plan.get("policyLayer"),
                plan.get("slotRole"),
                plan.get("zOrder"),
                tf.get("id"),
                row["overlapArea"],
                plan.get("file"),
                plan.get("reason"),
                short_text(tf.get("text"), 70),
            ))
            + " |"
        )
    if len(focus_rows) > 160:
        lines.append(f"| … | {len(focus_rows) - 160} more |  |  |  |  |  |  |  |  |  |  |  |  |  |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def asset_name_for_source(source: Path) -> str:
    return source.name


def copy_review_asset(source: Path, asset_dir: Path) -> Optional[str]:
    if not source.exists() or not source.is_file():
        return None
    asset_dir.mkdir(parents=True, exist_ok=True)
    target = asset_dir / asset_name_for_source(source)
    if not target.exists() or target.stat().st_size != source.stat().st_size:
        shutil.copy2(source, target)
    return f"{asset_dir.name}/{target.name}"


def write_html(path: Path, review: Dict[str, Any], extract_dir: Path) -> None:
    out_dir = path.parent
    asset_dir = out_dir / "visual-layer-review-assets"
    page_indexes = sorted({row["pageIndex"] for row in review["overlaps"]})
    rows_by_page: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    for row in review["overlaps"]:
        rows_by_page[row["pageIndex"]].append(row)
    css = """
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:24px;color:#17202a;background:#fafafa}
h1,h2{margin:0 0 12px} section{margin:28px 0;padding:18px;background:white;border:1px solid #ddd;border-radius:8px}
.summary{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0 24px}.pill{padding:6px 10px;border:1px solid #ccc;border-radius:999px;background:#fff}
.page-grid{display:grid;grid-template-columns:minmax(320px,520px) minmax(320px,1fr);gap:18px;align-items:start}.page-map{width:100%;border:1px solid #aaa;background:#fff;position:sticky;top:12px}
.page-bg{fill:#fff;stroke:#bbb;stroke-width:.25}.tf-box{fill:rgba(25,118,210,.08);stroke:#1976d2;stroke-width:.55}
.visual-box{stroke-width:.75}.risk-high{fill:rgba(229,57,53,.16);stroke:#e53935}.risk-medium{fill:rgba(251,140,0,.14);stroke:#fb8c00}.risk-low{fill:rgba(117,117,117,.10);stroke:#757575}
.overlap-box{fill:rgba(213,0,249,.23);stroke:#9c27b0;stroke-width:.45}
.side-pane{max-height:82vh;overflow:auto;border:1px solid #ddd;border-radius:8px;background:#fcfcfc;padding:10px}
.page-summary{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 10px}.mini-pill{font-size:12px;padding:4px 8px;border:1px solid #ccc;border-radius:999px;background:#fff}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:8px}.card{border:1px solid #ddd;border-radius:8px;padding:8px;background:#fff}.card.high{border-color:#e57373}.card.medium{border-color:#ffb74d}
.thumb{height:64px;display:flex;align-items:center;justify-content:center;background:#f3f3f3;margin:6px 0;overflow:hidden}.thumb img{max-width:100%;max-height:64px}
.meta{font-size:11px;line-height:1.35;color:#333}.text{font-size:11px;color:#555;margin-top:5px;max-height:2.8em;overflow:hidden}
code{font-size:12px}
"""
    parts = [
        "<!doctype html><meta charset='utf-8'>",
        f"<title>Visual Layer Review</title><style>{css}</style>",
        "<h1>Visual Layer Review</h1>",
        "<div class='summary'>",
    ]
    for key, value in review["summary"].items():
        parts.append(f"<div class='pill'><strong>{esc(key)}</strong>: {esc(value)}</div>")
    parts.append("</div>")
    parts.append("<p>Blue boxes are editable HWPX text frames. Red/orange/gray boxes are visual ObjectPlans overlapping them. Purple is the overlap area. Diagnostic labels explain whether the overlap is an expected ownership backdrop or a content/backdrop review target.</p>")
    for page_index in page_indexes:
        page_name = review["pageNames"].get(page_index, str(page_index + 1))
        page_rows = rows_by_page[page_index]
        high_count = sum(1 for row in page_rows if row["severity"].startswith("HIGH"))
        review_count = sum(1 for row in page_rows if row["severity"].startswith("REVIEW"))
        parts.append(f"<section id='page-{page_index}'><h2>Page {esc(page_name)} <code>pageIndex={page_index}</code> · {len(page_rows)} overlaps · {high_count} high · {review_count} review</h2>")
        parts.append("<div class='page-grid'>")
        parts.append(svg_for_page(review, page_index))
        severity_counts = Counter(row["severity"] for row in page_rows)
        diagnostic_counts = Counter(row["diagnostic"] for row in page_rows)
        layer_counts = Counter(row["visual"].get("visualLayer") for row in page_rows)
        parts.append("<aside class='side-pane'>")
        parts.append("<div class='page-summary'>")
        for key, count in severity_counts.most_common():
            parts.append(f"<span class='mini-pill'>{esc(key)}: {count}</span>")
        for key, count in diagnostic_counts.most_common():
            parts.append(f"<span class='mini-pill'>{esc(key)}: {count}</span>")
        for key, count in layer_counts.most_common():
            parts.append(f"<span class='mini-pill'>{esc(key)}: {count}</span>")
        parts.append("</div>")
        parts.append("<div class='cards'>")
        for row in page_rows:
            plan = row["visual"]
            tf = row["textFrame"]
            sev_class = "high" if row["severity"].startswith("HIGH") else ("medium" if row["severity"].startswith("MEDIUM") or row["severity"].startswith("REVIEW") else "low")
            image_html = ""
            if plan.get("file"):
                image_path = extract_dir / str(plan["file"])
                asset_ref = copy_review_asset(image_path, asset_dir)
                if asset_ref:
                    image_html = f"<div class='thumb'><img src='{esc(asset_ref)}'></div>"
            parts.append(f"<div class='card {sev_class}'>")
            parts.append(f"<strong>{esc(row['severity'])}</strong>{image_html}")
            parts.append("<div class='meta'>")
            parts.append(f"diagnostic <code>{esc(row.get('diagnostic'))}</code><br>")
            parts.append(f"visual <code>{esc(plan.get('domId'))}</code> · layer <code>{esc(plan.get('visualLayer'))}</code> · plane <code>{esc(row.get('plane'))}</code><br>")
            parts.append(f"policy <code>{esc(plan.get('policyLayer'))}</code> · slot <code>{esc(plan.get('slotRole'))}</code><br>")
            if plan.get("contentBackdropClass"):
                parts.append(f"content class <code>{esc(plan.get('contentBackdropClass'))}</code><br>")
            parts.append(f"source types <code>{esc(plan.get('sourceTypeCounts'))}</code> · page ratio <code>{esc(plan.get('pageAreaRatio'))}</code><br>")
            parts.append(f"relation <code>{esc(row.get('textRelation'))}</code><br>")
            parts.append(f"action <code>{esc(plan.get('visualAction'))}</code> · placement <code>{esc(plan.get('placement'))}</code> · z <code>{esc(plan.get('zOrder'))}</code><br>")
            parts.append(f"TF <code>{esc(tf.get('id'))}</code> · text z <code>{esc(tf.get('zOrder'))}</code> · overlap {esc(row.get('overlapArea'))}<br>")
            parts.append(f"file <code>{esc(plan.get('file'))}</code><br>reason <code>{esc(plan.get('reason'))}</code>")
            parts.append("</div>")
            parts.append(f"<div class='text'>{esc(tf.get('text'))}</div>")
            parts.append("</div>")
        parts.append("</div></aside></div></section>")
    path.write_text("\n".join(parts), encoding="utf-8")


def write_outputs(out_dir: Path, review: Dict[str, Any], extract_dir: Path) -> Dict[str, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {
        "json": out_dir / "visual-layer-review.json",
        "tsv": out_dir / "visual-layer-review.tsv",
        "md": out_dir / "visual-layer-review.md",
        "html": out_dir / "visual-layer-review.html",
    }
    write_json(paths["json"], review)
    write_tsv(paths["tsv"], review)
    write_markdown(paths["md"], review)
    write_html(paths["html"], review, extract_dir)
    return paths


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Build visual-layer overlap diagnostics for human review.")
    parser.add_argument("extract_dir", type=Path)
    parser.add_argument("--page", type=int, action="append", default=[], help="Physical page number. May be repeated.")
    parser.add_argument("--page-index", type=int, action="append", default=[], help="Raw zero-based pageIndex. May be repeated.")
    parser.add_argument("--min-overlap", type=float, default=0.25, help="Minimum overlap area in page units.")
    parser.add_argument("--out", type=Path, default=None, help="Output directory. Defaults to extract_dir.parent/trace.")
    args = parser.parse_args(list(argv) if argv is not None else None)

    if not args.extract_dir.exists():
        raise SystemExit(f"extract dir does not exist: {args.extract_dir}")
    selected_pages = page_indexes_from_args(args.extract_dir, args.page, args.page_index)
    out_dir = args.out or (args.extract_dir.parent / "trace")
    review = build_review(args.extract_dir, selected_pages, args.min_overlap)
    paths = write_outputs(out_dir, review, args.extract_dir)
    print(json.dumps(review["summary"], ensure_ascii=False, indent=2))
    for name, path in paths.items():
        print(f"{name}: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
