#!/usr/bin/env python3
"""Dump PDF pages and HWPX image-layer pages, then compare image placements.

This script is intentionally mechanical:
- PDF pages are rendered from preview.pdf at a fixed DPI.
- HWPX pages are reconstructed from hp:pic placements and BinData PNG bytes.
- Expected image placements come from extract/render-decisions.jsonl.
- Matching uses PNG byte hash so we compare the same rendered asset end-to-end.

The output is useful for checking whether Stage 1 / Stage 3 image geometry is
preserved into final HWPX without relying on manual page-by-page inspection.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import math
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple
from xml.etree import ElementTree as ET

from PIL import Image


REPO_ROOT = Path(__file__).resolve().parents[2]
CONVERTER_JAR = REPO_ROOT / "converter" / "target" / "idml-to-something-1.0.9-cli.jar"
CONVERSION_CONFIG = REPO_ROOT / "conversion-config.json"
DEFAULT_JAVA = Path("/opt/homebrew/opt/openjdk/bin/java")
POINTS_PER_MM = 72.0 / 25.4

NS = {
    "hp": "http://www.hancom.co.kr/hwpml/2011/paragraph",
    "hc": "http://www.hancom.co.kr/hwpml/2011/core",
}


@dataclass
class ExpectedPlacement:
    page_index: int
    page_number: int
    file: str
    file_path: Path
    sha1: str
    candidate_id: str
    plan_pass_id: str
    bounds_mm: Tuple[float, float, float, float]
    bounds_pt: Tuple[float, float, float, float]
    z_order: int


@dataclass
class HwpxPlacement:
    page_number: int
    binary_id: str
    sha1: str
    x_pt: float
    y_pt: float
    w_pt: float
    h_pt: float
    z_order: int
    treat_as_char: bool


def shell_quote(value: str) -> str:
    if not value:
        return "''"
    if all(ch.isalnum() or ch in "._/-:=+" for ch in value):
        return value
    return "'" + value.replace("'", "'\"'\"'") + "'"


def run(cmd: List[str], *, cwd: Path = REPO_ROOT, capture: bool = False) -> subprocess.CompletedProcess[str]:
    print("+ " + " ".join(shell_quote(c) for c in cmd))
    return subprocess.run(
        cmd,
        cwd=str(cwd),
        check=True,
        text=True,
        capture_output=capture,
    )


def java_command() -> str:
    env_java = Path(sys.executable).parent.parent / "bin" / "java"
    if env_java.exists():
        return str(env_java)
    if "JAVA" in __import__("os").environ:
        return __import__("os").environ["JAVA"]
    if DEFAULT_JAVA.exists():
        return str(DEFAULT_JAVA)
    return "java"


def ensure_converter_built() -> None:
    if CONVERTER_JAR.exists():
        return
    run(["mvn", "-pl", "converter", "-am", "-DskipTests", "package"])


def parse_page_spec(start: Optional[int], end: Optional[int], max_page: int) -> Tuple[int, int]:
    s = start or 1
    e = end or max_page
    if s < 1 or e < s or e > max_page:
        raise SystemExit(f"invalid page range: {s}-{e} (max {max_page})")
    return s, e


def load_resolved(extract_dir: Path) -> dict:
    path = extract_dir / "resolved.json"
    if not path.exists():
        raise SystemExit(f"resolved.json not found: {path}")
    return json.loads(path.read_text())


def resolved_scale_factor(resolved: dict) -> float:
    value = resolved.get("scaleFactor")
    if value is None:
        return POINTS_PER_MM
    return float(value)


def resolved_page_sizes_pt(resolved: dict) -> Dict[int, Tuple[int, int]]:
    out: Dict[int, Tuple[int, int]] = {}
    for idx, page in enumerate(resolved.get("pages", [])):
        bounds = page.get("bounds")
        if not bounds or len(bounds) < 4:
            continue
        width = max(1, int(round((float(bounds[3]) - float(bounds[1])) * POINTS_PER_MM)))
        height = max(1, int(round((float(bounds[2]) - float(bounds[0])) * POINTS_PER_MM)))
        out[idx] = (width, height)
    return out


def iter_hwpx_page_paragraphs(root: ET.Element) -> Iterable[Tuple[int, List[ET.Element]]]:
    page_number = 1
    paras: List[ET.Element] = []
    for para in root.findall("hp:p", NS):
        paras.append(para)
        if para.find(".//hp:secPr", NS) is not None:
            yield page_number, paras
            page_number += 1
            paras = []
    if paras:
        yield page_number, paras


def render_pdf_pages(preview_pdf: Path, out_dir: Path, start_page: int, end_page: int, dpi: int) -> Dict[int, Path]:
    ensure_converter_built()
    out_dir.mkdir(parents=True, exist_ok=True)
    out: Dict[int, Path] = {}
    for page in range(start_page, end_page + 1):
        cp = run(
            [
                java_command(),
                "-jar",
                str(CONVERTER_JAR),
                "--render-pdf-page",
                str(preview_pdf),
                str(page),
                "--dpi",
                str(dpi),
            ],
            capture=True,
        )
        data = json.loads(cp.stdout)
        data_url = data["data_url"]
        _, encoded = data_url.split(",", 1)
        jpg = base64.b64decode(encoded)
        path = out_dir / f"page-{page:03d}.jpg"
        path.write_bytes(jpg)
        out[page] = path
    return out


def sha1_bytes(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


def load_expected_placements(extract_dir: Path, scale_factor: float) -> Dict[int, List[ExpectedPlacement]]:
    path = extract_dir / "render-decisions.jsonl"
    if not path.exists():
        raise SystemExit(f"render-decisions.jsonl not found: {path}")
    by_page: Dict[int, List[ExpectedPlacement]] = {}
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("decision") != "PLACE":
            continue
        if row.get("planPlacement") != "FLOATING":
            continue
        file_rel = row.get("file") or row.get("planFile")
        if not file_rel:
            continue
        file_path = extract_dir / file_rel
        if not file_path.exists():
            continue
        data = file_path.read_bytes()
        bounds = row.get("bounds")
        if not bounds or len(bounds) < 4:
            continue
        bounds_pt = tuple(float(v) * scale_factor for v in bounds)
        page_index = int(row["pageIndex"])
        page_number = page_index + 1
        entry = ExpectedPlacement(
            page_index=page_index,
            page_number=page_number,
            file=file_rel,
            file_path=file_path,
            sha1=sha1_bytes(data),
            candidate_id=str(row.get("candidateId") or row.get("objectPlanCandidateId") or ""),
            plan_pass_id=str(row.get("planPassId") or ""),
            bounds_mm=(float(bounds[0]), float(bounds[1]), float(bounds[2]), float(bounds[3])),
            bounds_pt=bounds_pt,
            z_order=int(row.get("planZOrder") or 0),
        )
        by_page.setdefault(page_number, []).append(entry)
    for page in by_page:
        by_page[page].sort(key=lambda e: (e.z_order, e.file))
    return by_page


def render_expected_layers(
    expected_by_page: Dict[int, List[ExpectedPlacement]],
    page_sizes_pt: Dict[int, Tuple[int, int]],
    out_dir: Path,
) -> Dict[int, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    out: Dict[int, Path] = {}
    for page_number, entries in expected_by_page.items():
        page_index = page_number - 1
        size = page_sizes_pt.get(page_index)
        if not size:
            continue
        canvas = Image.new("RGBA", size, (255, 255, 255, 0))
        for entry in entries:
            image = Image.open(entry.file_path).convert("RGBA")
            top_pt, left_pt, bottom_pt, right_pt = entry.bounds_pt
            width = max(1, int(round(right_pt - left_pt)))
            height = max(1, int(round(bottom_pt - top_pt)))
            if width <= 0 or height <= 0:
                continue
            if image.size != (width, height):
                image = image.resize((width, height), Image.Resampling.LANCZOS)
            canvas.alpha_composite(image, (int(round(left_pt)), int(round(top_pt))))
        path = out_dir / f"page-{page_number:03d}.png"
        canvas.save(path)
        out[page_number] = path
    return out


def crop_hwpx_picture_image(pic: ET.Element, image: Image.Image, display_w_pt: float, display_h_pt: float) -> Image.Image:
    clip = pic.find("hp:imgClip", NS)
    if clip is None:
        return image
    left = float(clip.get("left", "0"))
    right = float(clip.get("right", str(int(round(display_w_pt * 100)))))
    top = float(clip.get("top", "0"))
    bottom = float(clip.get("bottom", str(int(round(display_h_pt * 100)))))
    display_w_hu = max(1.0, display_w_pt * 100.0)
    display_h_hu = max(1.0, display_h_pt * 100.0)
    crop_l = max(0.0, min(1.0, left / display_w_hu))
    crop_t = max(0.0, min(1.0, top / display_h_hu))
    crop_r = max(0.0, min(1.0, right / display_w_hu))
    crop_b = max(0.0, min(1.0, bottom / display_h_hu))
    if crop_r <= crop_l or crop_b <= crop_t:
        return image
    px_l = int(round(image.width * crop_l))
    px_t = int(round(image.height * crop_t))
    px_r = int(round(image.width * crop_r))
    px_b = int(round(image.height * crop_b))
    px_l = max(0, min(image.width - 1, px_l))
    px_t = max(0, min(image.height - 1, px_t))
    px_r = max(px_l + 1, min(image.width, px_r))
    px_b = max(px_t + 1, min(image.height, px_b))
    return image.crop((px_l, px_t, px_r, px_b))


def render_hwpx_image_pages(
    hwpx_path: Path,
    out_dir: Path,
    page_sizes_pt: Dict[int, Tuple[int, int]],
    start_page: int,
    end_page: int,
) -> Tuple[Dict[int, Path], Dict[int, List[HwpxPlacement]]]:
    out_dir.mkdir(parents=True, exist_ok=True)
    out_paths: Dict[int, Path] = {}
    placements: Dict[int, List[HwpxPlacement]] = {}
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        with zipfile.ZipFile(hwpx_path) as z:
            z.extractall(tmp)
        section_paths = sorted(
            (tmp / "Contents").glob("section*.xml"),
            key=lambda p: int("".join(ch for ch in p.stem if ch.isdigit()) or "0"),
        )
        global_page = 1
        for section_path in section_paths:
            root = ET.parse(section_path).getroot()
            for _, paras in iter_hwpx_page_paragraphs(root):
                if global_page < start_page or global_page > end_page:
                    global_page += 1
                    continue
                page_index = global_page - 1
                size = page_sizes_pt.get(page_index)
                if not size:
                    global_page += 1
                    continue
                canvas = Image.new("RGBA", size, (255, 255, 255, 0))
                page_rows: List[HwpxPlacement] = []
                pics: List[Tuple[int, ET.Element]] = []
                for para in paras:
                    for pic in para.findall(".//hp:pic", NS):
                        pics.append((int(pic.get("zOrder", "0")), pic))
                pics.sort(key=lambda pair: pair[0])
                for z_order, pic in pics:
                    pos = pic.find("hp:pos", NS)
                    sz = pic.find("hp:sz", NS)
                    img = pic.find(".//hc:img", NS)
                    if pos is None or sz is None or img is None:
                        continue
                    binary_id = img.get("binaryItemIDRef")
                    if not binary_id:
                        continue
                    image_path = tmp / "BinData" / f"{binary_id}.png"
                    if not image_path.exists():
                        continue
                    data = image_path.read_bytes()
                    picture = Image.open(io.BytesIO(data)).convert("RGBA")
                    treat_as_char = pos.get("treatAsChar", "0") == "1"
                    x_pt = float(pos.get("horzOffset", "0")) / 100.0
                    y_pt = float(pos.get("vertOffset", "0")) / 100.0
                    w_pt = float(sz.get("width", "0")) / 100.0
                    h_pt = float(sz.get("height", "0")) / 100.0
                    if w_pt <= 0 or h_pt <= 0:
                        continue
                    picture = crop_hwpx_picture_image(pic, picture, w_pt, h_pt)
                    target_w = max(1, int(round(w_pt)))
                    target_h = max(1, int(round(h_pt)))
                    if picture.size != (target_w, target_h):
                        picture = picture.resize((target_w, target_h), Image.Resampling.LANCZOS)
                    canvas.alpha_composite(picture, (int(round(x_pt)), int(round(y_pt))))
                    page_rows.append(
                        HwpxPlacement(
                            page_number=global_page,
                            binary_id=binary_id,
                            sha1=sha1_bytes(data),
                            x_pt=x_pt,
                            y_pt=y_pt,
                            w_pt=w_pt,
                            h_pt=h_pt,
                            z_order=z_order,
                            treat_as_char=treat_as_char,
                        )
                    )
                path = out_dir / f"page-{global_page:03d}.png"
                canvas.save(path)
                out_paths[global_page] = path
                placements[global_page] = page_rows
                global_page += 1
    return out_paths, placements


def rect_from_bounds_pt(bounds_pt: Tuple[float, float, float, float]) -> Tuple[float, float, float, float]:
    top, left, bottom, right = bounds_pt
    return left, top, right, bottom


def rect_from_actual(actual: HwpxPlacement) -> Tuple[float, float, float, float]:
    return actual.x_pt, actual.y_pt, actual.x_pt + actual.w_pt, actual.y_pt + actual.h_pt


def rect_iou(a: Tuple[float, float, float, float], b: Tuple[float, float, float, float]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1 = max(ax1, bx1)
    iy1 = max(ay1, by1)
    ix2 = min(ax2, bx2)
    iy2 = min(ay2, by2)
    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0
    inter = (ix2 - ix1) * (iy2 - iy1)
    a_area = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    b_area = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = a_area + b_area - inter
    if union <= 0.0:
        return 0.0
    return inter / union


def placement_delta(actual: HwpxPlacement, expected: ExpectedPlacement) -> Tuple[float, float, float, float]:
    exp_top, exp_left, exp_bottom, exp_right = expected.bounds_pt
    exp_w = exp_right - exp_left
    exp_h = exp_bottom - exp_top
    return (
        actual.x_pt - exp_left,
        actual.y_pt - exp_top,
        actual.w_pt - exp_w,
        actual.h_pt - exp_h,
    )


def geometry_match_score(actual: HwpxPlacement, expected: ExpectedPlacement) -> float:
    dx, dy, dw, dh = placement_delta(actual, expected)
    rect_actual = rect_from_actual(actual)
    rect_expected = rect_from_bounds_pt(expected.bounds_pt)
    iou = rect_iou(rect_actual, rect_expected)
    exp_top, exp_left, exp_bottom, exp_right = expected.bounds_pt
    exp_w = max(1.0, exp_right - exp_left)
    exp_h = max(1.0, exp_bottom - exp_top)
    normalized = (
        abs(dx) / exp_w +
        abs(dy) / exp_h +
        abs(dw) / exp_w +
        abs(dh) / exp_h
    )
    # Lower is better. Strong overlap should dominate.
    return normalized + (1.0 - iou) * 2.0


def geometry_match_acceptable(actual: HwpxPlacement, expected: ExpectedPlacement) -> bool:
    dx, dy, dw, dh = placement_delta(actual, expected)
    rect_actual = rect_from_actual(actual)
    rect_expected = rect_from_bounds_pt(expected.bounds_pt)
    iou = rect_iou(rect_actual, rect_expected)
    exp_top, exp_left, exp_bottom, exp_right = expected.bounds_pt
    exp_w = max(1.0, exp_right - exp_left)
    exp_h = max(1.0, exp_bottom - exp_top)
    max_pos = max(abs(dx), abs(dy))
    max_size = max(abs(dw), abs(dh))
    return (
        iou >= 0.85 or
        (max_pos <= 2.0 and max_size <= 2.0) or
        (
            abs(dx) / exp_w <= 0.01 and
            abs(dy) / exp_h <= 0.01 and
            abs(dw) / exp_w <= 0.01 and
            abs(dh) / exp_h <= 0.01
        )
    )


def build_match_payload(actual: HwpxPlacement, expected: ExpectedPlacement, method: str) -> dict:
    exp_top, exp_left, exp_bottom, exp_right = expected.bounds_pt
    exp_w = exp_right - exp_left
    exp_h = exp_bottom - exp_top
    dx, dy, dw, dh = placement_delta(actual, expected)
    return {
        "candidateId": expected.candidate_id,
        "planPassId": expected.plan_pass_id,
        "file": expected.file,
        "matchMethod": method,
        "expected": {
            "xPt": round(exp_left, 3),
            "yPt": round(exp_top, 3),
            "wPt": round(exp_w, 3),
            "hPt": round(exp_h, 3),
            "zOrder": expected.z_order,
        },
        "delta": {
            "dxPt": round(dx, 3),
            "dyPt": round(dy, 3),
            "dwPt": round(dw, 3),
            "dhPt": round(dh, 3),
        },
        "iou": round(rect_iou(rect_from_actual(actual), rect_from_bounds_pt(expected.bounds_pt)), 4),
    }


def match_placements(
    expected_by_page: Dict[int, List[ExpectedPlacement]],
    hwpx_by_page: Dict[int, List[HwpxPlacement]],
) -> Dict[int, List[dict]]:
    report: Dict[int, List[dict]] = {}
    for page_number, actual_rows in hwpx_by_page.items():
        expected_rows = expected_by_page.get(page_number, [])
        actual_rows = [row for row in actual_rows if not row.treat_as_char]
        expected_by_hash: Dict[str, List[Tuple[int, ExpectedPlacement]]] = {}
        for idx, row in enumerate(expected_rows):
            expected_by_hash.setdefault(row.sha1, []).append((idx, row))
        used_expected: Set[int] = set()
        matched_actual_indexes: Set[int] = set()
        page_report: List[dict] = []
        for actual_idx, actual in enumerate(actual_rows):
            matches = expected_by_hash.get(actual.sha1, [])
            chosen_expected_idx = None
            chosen = None
            best_score = None
            for expected_idx, cand in matches:
                if expected_idx in used_expected:
                    continue
                score = geometry_match_score(actual, cand)
                if best_score is None or score < best_score:
                    best_score = score
                    chosen_expected_idx = expected_idx
                    chosen = cand
            row = {
                "binaryId": actual.binary_id,
                "sha1": actual.sha1,
                "actual": {
                    "xPt": round(actual.x_pt, 3),
                    "yPt": round(actual.y_pt, 3),
                    "wPt": round(actual.w_pt, 3),
                    "hPt": round(actual.h_pt, 3),
                    "zOrder": actual.z_order,
                    "treatAsChar": actual.treat_as_char,
                },
                "match": None,
            }
            if chosen is not None and chosen_expected_idx is not None:
                used_expected.add(chosen_expected_idx)
                matched_actual_indexes.add(actual_idx)
                row["match"] = build_match_payload(actual, chosen, "sha1")
            page_report.append(row)

        unmatched_expected = [
            (idx, row)
            for idx, row in enumerate(expected_rows)
            if idx not in used_expected
        ]
        for actual_idx, row in enumerate(page_report):
            if actual_idx in matched_actual_indexes:
                continue
            actual = actual_rows[actual_idx]
            chosen_expected_idx = None
            chosen = None
            best_score = None
            for expected_idx, cand in unmatched_expected:
                if expected_idx in used_expected:
                    continue
                if not geometry_match_acceptable(actual, cand):
                    continue
                score = geometry_match_score(actual, cand)
                if best_score is None or score < best_score:
                    best_score = score
                    chosen_expected_idx = expected_idx
                    chosen = cand
            if chosen is not None and chosen_expected_idx is not None:
                used_expected.add(chosen_expected_idx)
                row["match"] = build_match_payload(actual, chosen, "geometry")
        report[page_number] = page_report
    return report


def write_summary(report: Dict[int, List[dict]], out_path: Path) -> None:
    summary = {"pages": {}}
    for page, rows in sorted(report.items()):
        total = len(rows)
        matched = sum(1 for row in rows if row.get("match"))
        max_abs = 0.0
        for row in rows:
            match = row.get("match")
            if not match:
                continue
            delta = match["delta"]
            max_abs = max(
                max_abs,
                abs(delta["dxPt"]),
                abs(delta["dyPt"]),
                abs(delta["dwPt"]),
                abs(delta["dhPt"]),
            )
        summary["pages"][str(page)] = {
            "actualImageCount": total,
            "matchedCount": matched,
            "unmatchedCount": total - matched,
            "maxAbsDeltaPt": round(max_abs, 3),
        }
    out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2))


def find_preview_pdf(extract_dir: Path, explicit_pdf: Optional[Path]) -> Optional[Path]:
    if explicit_pdf:
        return explicit_pdf if explicit_pdf.exists() else None
    direct = extract_dir / "preview.pdf"
    if direct.exists():
        return direct
    parent = extract_dir.parent / "preview.pdf"
    if parent.exists():
        return parent
    return None


def main(argv: Optional[Iterable[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="Dump PDF/HWPX pages and compare image placements.")
    ap.add_argument("--extract", type=Path, required=True)
    ap.add_argument("--hwpx", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--preview-pdf", type=Path, default=None)
    ap.add_argument("--start-page", type=int, default=None)
    ap.add_argument("--end-page", type=int, default=None)
    ap.add_argument("--pdf-dpi", type=int, default=72)
    args = ap.parse_args(list(argv) if argv is not None else None)

    if not args.extract.exists():
        raise SystemExit(f"extract dir not found: {args.extract}")
    if not args.hwpx.exists():
        raise SystemExit(f"hwpx not found: {args.hwpx}")

    resolved = load_resolved(args.extract)
    scale_factor = resolved_scale_factor(resolved)
    page_sizes_pt = resolved_page_sizes_pt(resolved)
    max_page = len(page_sizes_pt)
    start_page, end_page = parse_page_spec(args.start_page, args.end_page, max_page)

    out_dir = args.out
    out_dir.mkdir(parents=True, exist_ok=True)

    preview_pdf = find_preview_pdf(args.extract, args.preview_pdf)
    pdf_pages: Dict[int, Path] = {}
    if preview_pdf and preview_pdf.exists():
        pdf_pages = render_pdf_pages(preview_pdf, out_dir / "pdf-pages", start_page, end_page, args.pdf_dpi)
    else:
        print("! preview.pdf not found; skipping PDF page dump")

    expected = load_expected_placements(args.extract, scale_factor)
    render_expected_layers(expected, page_sizes_pt, out_dir / "expected-image-pages")
    hwpx_pages, hwpx_placements = render_hwpx_image_pages(
        args.hwpx,
        out_dir / "hwpx-image-pages",
        page_sizes_pt,
        start_page,
        end_page,
    )
    report = match_placements(expected, hwpx_placements)
    (out_dir / "placement-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    write_summary(report, out_dir / "summary.json")

    print(f"dumped hwpx image pages: {len(hwpx_pages)}")
    print(f"dumped pdf pages: {len(pdf_pages)}")
    print(f"report: {out_dir / 'placement-report.json'}")
    print(f"summary: {out_dir / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
