#!/usr/bin/env python3
"""
source ownership policy Phase 1: 텍스트 커버리지 측정 도구.

resolved.json의 모든 텍스트가 HWPX section XML에 포함되는지 비교한다.

사용법:
    python3 scripts/text_coverage.py <resolved.json> <out.hwpx>
    python3 scripts/text_coverage.py <resolved.json> <out.hwpx> --verbose

출력: 누락 텍스트 카탈로그 + 커버리지 통계
"""
import json
import re
import sys
import zipfile
from pathlib import Path
from collections import defaultdict
from xml.etree import ElementTree as ET


# ── resolved.json 파싱 ──────────────────────────────────────

def load_resolved(path: Path):
    """Gson lenient 모드와 유사하게 제어문자 허용으로 파싱."""
    with path.open("r", encoding="utf-8") as f:
        text = f.read()
    return json.JSONDecoder(strict=False).decode(text)


def extract_resolved_paragraphs(resolved):
    """resolved.json에서 모든 단락을 추출.
    stories는 list이고 각 story가 "id" 필드를 가짐."""
    paragraphs = []
    stories = resolved.get("stories", [])
    for story in stories:
        if not isinstance(story, dict):
            continue
        sid = str(story.get("id", ""))
        for pidx, p in enumerate(story.get("paragraphs", [])):
            runs = p.get("runs", [])
            text = "".join(r.get("content", "") or r.get("text", "") for r in runs)
            paragraphs.append({
                "story_id": sid,
                "para_idx": pidx,
                "text": text,
            })
    return paragraphs


def build_story_to_frame_index(resolved):
    """storyId → list of textFrame (한 스토리가 여러 프레임에 분배될 수 있음)."""
    idx = defaultdict(list)
    for tf in resolved.get("textFrames", []):
        sid = str(tf.get("storyId", ""))
        if sid:
            idx[sid].append(tf)
    return idx


def get_page_bounds(resolved):
    """첫 페이지 폭/높이 (mm 또는 pt — 일관성만 있으면 됨)."""
    pages = resolved.get("pages", [])
    if pages:
        b = pages[0].get("bounds")
        if b and len(b) >= 4:
            return b  # [top, left, bottom, right]
    di = resolved.get("documentInfo", {})
    w = di.get("pageWidth")
    h = di.get("pageHeight")
    if w and h:
        return [0, 0, h, w]
    return None


def classify_missing_reason(frame, page_bounds):
    """frame 정보로 누락 원인 추정. source ownership policy 확장 필드 우선 사용.
    반환: (reason_short, reason_detail)."""
    if not frame:
        return ("no-frame", "스토리에 매칭되는 textFrame 없음 (테이블 셀 또는 인라인)")
    reasons = []
    detail = []

    # source ownership policy 진단 필드 (있으면 우선)
    cls = frame.get("classification")
    if cls:
        # classifyTextFrame 직접 결과
        reasons.append(f"cls={cls}")
    if frame.get("isMasterPageItem"):
        reasons.append("masterPage")
    if frame.get("nonprinting"):
        reasons.append("nonprinting")
    if frame.get("onHiddenLayer"):
        reasons.append("hidden")
    pstyles = frame.get("paragraphStyles") or []
    cstyles = frame.get("characterStyles") or []
    hashira_p = any("하시라" in s or "페이지번호" in s for s in pstyles)
    hashira_c = any("하시라" in s or "페이지번호" in s for s in cstyles)
    if hashira_p:
        reasons.append("hashira-pStyle")
    elif hashira_c:
        reasons.append("hashira-cStyle")

    # 시각 속성 (기존 휴리스틱)
    fill = frame.get("fillColor") or "None"
    stroke = frame.get("strokeColor") or "None"
    stroke_w = frame.get("strokeWeight") or 0
    rot = frame.get("rotationAngle") or 0
    opacity = frame.get("opacity", 100)
    corner = frame.get("cornerRadius") or 0

    if abs(rot) > 3:
        reasons.append(f"rotated({rot:.0f}°)")
    if opacity < 100:
        reasons.append(f"opacity={opacity}")
    if fill not in ("None", "[None]", None):
        reasons.append(f"fill={fill}")
    if stroke not in ("None", "[None]", None) and stroke_w > 0:
        reasons.append(f"stroke={stroke}@{stroke_w:.1f}")
    if corner > 0:
        reasons.append(f"corner={corner}")
    if frame.get("isInline"):
        reasons.append("inline")

    if not reasons:
        if frame.get("overflows"):
            reasons.append("overflow")
        else:
            reasons.append("?")

    return (",".join(reasons), "; ".join(detail))


# ── HWPX 텍스트 추출 ────────────────────────────────────────

HWPX_NS = {
    "hp": "http://www.hancom.co.kr/hwpml/2011/paragraph",
}


def extract_hwpx_texts(hwpx_path: Path):
    """HWPX 안의 모든 section XML에서 텍스트 추출.
    반환: 모든 <hp:t> 텍스트의 연결된 큰 문자열."""
    texts = []
    with zipfile.ZipFile(hwpx_path) as z:
        for name in z.namelist():
            if not name.startswith("Contents/section") or not name.endswith(".xml"):
                continue
            with z.open(name) as f:
                xml_bytes = f.read()
            try:
                root = ET.fromstring(xml_bytes)
            except ET.ParseError as e:
                print(f"⚠ XML parse error in {name}: {e}", file=sys.stderr)
                continue
            for t in root.iter("{%s}t" % HWPX_NS["hp"]):
                if t.text:
                    texts.append(t.text)
    return "".join(texts)


# ── 정규화 + 매칭 ───────────────────────────────────────────

def normalize_text(t: str) -> str:
    """공백/제어문자 정규화. 매칭 비교용."""
    if not t:
        return ""
    # ASCII 제어문자 + 일부 invisible 제거
    t = re.sub(r"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F￼﻿​-‏]", "", t)
    # 다양한 공백을 일반 공백으로 (em space, en space, thin space 등)
    t = re.sub(r"[\s  - 　]+", " ", t)
    return t.strip()


def measure_coverage(paragraphs, hwpx_text, story_to_frames, page_bounds):
    """각 paragraph가 HWPX 텍스트에 포함되는지 검사."""
    hwpx_norm = normalize_text(hwpx_text)
    results = []
    for p in paragraphs:
        para_text = normalize_text(p["text"])
        if not para_text:
            continue
        # 짧은 텍스트 (1~2 char)는 매칭 신뢰성 낮아 별도 처리
        if len(para_text) <= 2:
            status = "found" if para_text in hwpx_norm else "missing-short"
        elif para_text in hwpx_norm:
            status = "found"
        else:
            # 부분 매칭: 처음 50%가 들어가면 partial
            half = para_text[: max(10, len(para_text) // 2)]
            if half in hwpx_norm:
                status = "partial"
            else:
                status = "missing"
        # 누락인 경우 frame 분류 추가
        reason = ""
        if status in ("missing", "partial", "missing-short"):
            frames = story_to_frames.get(p["story_id"], [])
            frame = frames[0] if frames else None
            reason, _ = classify_missing_reason(frame, page_bounds)
        results.append({
            **p,
            "norm_text": para_text,
            "status": status,
            "reason": reason,
        })
    return results


# ── 리포트 ──────────────────────────────────────────────────

def print_report(results, verbose=False):
    counts = defaultdict(int)
    for r in results:
        counts[r["status"]] += 1
    total = sum(counts.values())
    if total == 0:
        print("(텍스트 없음)")
        return

    print(f"=== 텍스트 커버리지 ({total}개 단락) ===")
    for status in ["found", "partial", "missing", "missing-short"]:
        n = counts[status]
        pct = 100 * n / total
        bar = "█" * int(pct / 2)
        print(f"  {status:14s}: {n:5d} ({pct:5.1f}%) {bar}")

    missing = [r for r in results if r["status"] in ("missing", "partial")]
    if not missing:
        print("\n✓ 누락 텍스트 없음")
        return

    # 분류별 그룹핑
    print(f"\n=== 누락 분류 통계 ({len(missing)}개) ===")
    reason_counts = defaultdict(int)
    for r in missing:
        reason_counts[r["reason"]] += 1
    for reason, n in sorted(reason_counts.items(), key=lambda x: -x[1]):
        print(f"  {reason:50s}: {n:3d}")

    print(f"\n=== 누락 텍스트 카탈로그 ({len(missing)}개, 분류별 정렬) ===")
    # 분류별 → 길이별 정렬
    missing.sort(key=lambda r: (r["reason"], -len(r["norm_text"])))

    limit = None if verbose else 40
    current_reason = None
    shown = 0
    for r in missing:
        if limit and shown >= limit:
            break
        if r["reason"] != current_reason:
            current_reason = r["reason"]
            print(f"\n  ── {current_reason} ──")
        text = r["norm_text"]
        preview = text if len(text) <= 70 else text[:67] + "..."
        marker = "△" if r["status"] == "partial" else "✗"
        print(f"  {marker} story={r['story_id']} ({len(text):3d}자): {preview}")
        shown += 1
    if limit and len(missing) > limit:
        print(f"\n  ... 그 외 {len(missing) - limit}개. --verbose로 전체 보기")


def main():
    args = sys.argv[1:]
    verbose = "--verbose" in args
    args = [a for a in args if a != "--verbose"]
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(1)
    resolved_path = Path(args[0])
    hwpx_path = Path(args[1])
    if not resolved_path.exists():
        print(f"✗ {resolved_path} 없음", file=sys.stderr)
        sys.exit(1)
    if not hwpx_path.exists():
        print(f"✗ {hwpx_path} 없음", file=sys.stderr)
        sys.exit(1)

    print(f"resolved: {resolved_path}")
    print(f"hwpx:     {hwpx_path}\n")

    resolved = load_resolved(resolved_path)
    paragraphs = extract_resolved_paragraphs(resolved)
    story_to_frames = build_story_to_frame_index(resolved)
    page_bounds = get_page_bounds(resolved)
    print(f"resolved.json: {len(paragraphs)} paragraphs, {sum(len(v) for v in story_to_frames.values())} textFrames")
    print(f"page bounds: {page_bounds}")

    hwpx_text = extract_hwpx_texts(hwpx_path)
    print(f"HWPX: {len(hwpx_text)} chars\n")

    results = measure_coverage(paragraphs, hwpx_text, story_to_frames, page_bounds)
    print_report(results, verbose=verbose)


if __name__ == "__main__":
    main()
