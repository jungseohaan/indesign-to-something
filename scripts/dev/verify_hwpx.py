#!/usr/bin/env python3
"""HWPX 구조 시그니처 추출 + 골든 스냅샷 회귀 비교 (SPEC-061).

변환 산출물(HWPX)에서 회귀에 민감한 구조 시그니처를 뽑아 JSON으로 저장하고,
이전에 검증된 골든 스냅샷과 비교한다. PR 전 회귀 게이트 용도.

시그니처 항목 (이 세션들에서 실제 회귀가 났던 축들):
- 수식: hp:script 전체 목록 (SPEC-059 좌변 잘림 검출)
- 표: 표별 (행x열, 식별용 선두 텍스트, 셀 fill 색 집합, fill/괘선 셀 수)
  (SPEC-060 표 스타일 흡수 소실 검출)
- 인라인/플로팅 이미지 수 (SPEC-056/057 시각물 유실 검출)
- 유령 테이블: 폭<=0.2pt & 높이>=3pt (유령 스페이서 회귀 검출)
- 텍스트 유출 휴리스틱: 긴 NBSP 연쇄, □(U+25A1) 개수
- 총 가시 문자 수 / 문단 수 / 섹션 수

사용:
  # 시그니처 출력
  python3 scripts/dev/verify_hwpx.py out.hwpx

  # 골든 저장 (검증 완료 시점에)
  python3 scripts/dev/verify_hwpx.py out.hwpx --write-golden test-data/golden/과학u1.json

  # 골든과 비교 (diff 있으면 exit 1)
  python3 scripts/dev/verify_hwpx.py out.hwpx --golden test-data/golden/과학u1.json
"""

import argparse
import json
import re
import subprocess
import sys
import zipfile
from collections import Counter
from pathlib import Path


def _find_table_spans(x: str):
    """중첩을 고려해 모든 <hp:tbl ...>...</hp:tbl> 스팬을 (attr, start, end)로 반환."""
    spans = []
    stack = []
    for m in re.finditer(r"<hp:tbl\b[^>]*>|</hp:tbl>", x):
        tok = m.group(0)
        if tok.startswith("<hp:tbl"):
            stack.append((tok, m.start()))
        else:
            if not stack:
                continue
            open_tok, start = stack.pop()
            spans.append((open_tok, start, m.end()))
    spans.sort(key=lambda t: t[1])
    return spans


def _own_cell_border_fills(x: str, start: int, end: int, inner_spans):
    """해당 표 스팬의 <hp:tc>만 (중첩 표 내부 제외) borderFillIDRef 수집."""
    ids = []
    for m in re.finditer(r'<hp:tc[^>]*borderFillIDRef="(\d+)"', x[start:end]):
        pos = start + m.start()
        nested = any(s < pos < e for (_, s, e) in inner_spans)
        if not nested:
            ids.append(m.group(1))
    return ids


def signature(hwpx_path: str) -> dict:
    z = zipfile.ZipFile(hwpx_path)
    names = z.namelist()
    sections = sorted(n for n in names if re.match(r"Contents/section\d+\.xml", n))
    header = z.read("Contents/header.xml").decode("utf-8", errors="ignore")

    # borderFill id → (faceColor, solid border edge count)
    fill_of = {}
    for m in re.finditer(r'<hh:borderFill id="(\d+)".*?</hh:borderFill>', header, re.S):
        frag = m.group(0)
        wb = re.search(r'<hc:winBrush faceColor="([^"]*)"', frag)
        face = wb.group(1) if wb else None
        if face in (None, "none", "#FFFFFF"):
            face = None
        solid_edges = len(re.findall(r'type="SOLID"', frag))
        fill_of[m.group(1)] = (face, solid_edges)

    sig = {
        "sections": len(sections),
        "equations": [],
        "tables": [],
        "picCount": 0,
        "picSizes": Counter(),
        "ghostTables": 0,
        "longNbspRuns": 0,
        "whiteSquareCount": 0,
        "visibleChars": 0,
        "paragraphs": 0,
        "binDataCount": sum(1 for n in names if "BinData/" in n),
    }

    for n in sections:
        x = z.read(n).decode("utf-8", errors="ignore")

        sig["equations"].extend(
            re.findall(r"<hp:script[^>]*>([^<]*)</hp:script>", x))

        sig["picCount"] += len(re.findall(r"<hp:pic\b", x))
        for m in re.finditer(r'<hp:curSz width="(\d+)" height="(\d+)"', x):
            w, h = int(m.group(1)), int(m.group(2))
            # 10pt 버킷으로 양자화 (렌더 미세 오차 허용)
            sig["picSizes"][f"{w // 1000}x{h // 1000}"] += 1

        spans = _find_table_spans(x)
        for open_tok, start, end in spans:
            rc = re.search(r'rowCnt="(\d+)"', open_tok)
            cc = re.search(r'colCnt="(\d+)"', open_tok)
            szm = re.search(r'<hp:sz width="(\d+)"[^/]*height="(\d+)"', x[start:end])
            if szm:
                w, h = int(szm.group(1)), int(szm.group(2))
                if w <= 20 and h >= 300:
                    sig["ghostTables"] += 1
            inner = [(o, s, e) for (o, s, e) in spans if s > start and e < end]
            bf_ids = _own_cell_border_fills(x, start, end, inner)
            fills = sorted({fill_of.get(b, (None, 0))[0] for b in bf_ids
                            if fill_of.get(b, (None, 0))[0]})
            filled = sum(1 for b in bf_ids if fill_of.get(b, (None, 0))[0])
            bordered = sum(1 for b in bf_ids if fill_of.get(b, (None, 0))[1] > 0)
            texts = "".join(re.findall(r"<hp:t>([^<]*)</hp:t>", x[start:end]))
            head = re.sub(r"\s+", " ", texts).strip()[:24]
            sig["tables"].append({
                "rc": int(rc.group(1)) if rc else 0,
                "cc": int(cc.group(1)) if cc else 0,
                "head": head,
                "fills": fills,
                "filledCells": filled,
                "borderedCells": bordered,
            })

        texts = re.findall(r"<hp:t>([^<]*)</hp:t>", x)
        joined = "".join(texts)
        sig["visibleChars"] += len(re.sub(r"\s", "", joined))
        sig["paragraphs"] += len(re.findall(r"<hp:p\b", x))
        sig["whiteSquareCount"] += joined.count("□")
        sig["longNbspRuns"] += len(re.findall(r" {8,}", joined))

    sig["picSizes"] = dict(sorted(sig["picSizes"].items()))
    sig["tables"].sort(key=lambda t: (t["rc"], t["cc"], t["head"]))
    return sig


def _table_key(t: dict) -> str:
    return f'{t["rc"]}x{t["cc"]}|{t["head"]}'


def diff_signatures(golden: dict, current: dict) -> list:
    problems = []

    for key in ("sections", "picCount", "ghostTables", "longNbspRuns",
                "whiteSquareCount", "binDataCount", "paragraphs"):
        g, c = golden.get(key), current.get(key)
        if g != c:
            problems.append(f"{key}: {g} → {c}")

    g_chars, c_chars = golden.get("visibleChars", 0), current.get("visibleChars", 0)
    if abs(g_chars - c_chars) > max(20, g_chars * 0.001):
        problems.append(f"visibleChars: {g_chars} → {c_chars}")

    g_eq = Counter(golden.get("equations", []))
    c_eq = Counter(current.get("equations", []))
    for script, cnt in (g_eq - c_eq).items():
        problems.append(f"수식 소실 x{cnt}: {script[:60]!r}")
    for script, cnt in (c_eq - g_eq).items():
        problems.append(f"수식 추가 x{cnt}: {script[:60]!r}")

    g_tab = {}
    for t in golden.get("tables", []):
        g_tab.setdefault(_table_key(t), []).append(t)
    c_tab = {}
    for t in current.get("tables", []):
        c_tab.setdefault(_table_key(t), []).append(t)
    for key in sorted(set(g_tab) | set(c_tab)):
        gl, cl = g_tab.get(key, []), c_tab.get(key, [])
        if len(gl) != len(cl):
            problems.append(f"표 수 변화 [{key}]: {len(gl)} → {len(cl)}")
            continue
        for gt, ct in zip(gl, cl):
            for f in ("fills", "filledCells", "borderedCells"):
                if gt.get(f) != ct.get(f):
                    problems.append(
                        f"표 스타일 변화 [{key}] {f}: {gt.get(f)} → {ct.get(f)}")

    g_pic = golden.get("picSizes", {})
    c_pic = current.get("picSizes", {})
    for bucket in sorted(set(g_pic) | set(c_pic)):
        if g_pic.get(bucket, 0) != c_pic.get(bucket, 0):
            problems.append(
                f"이미지 크기버킷 {bucket}(10pt): {g_pic.get(bucket, 0)} → {c_pic.get(bucket, 0)}")

    return problems


def main():
    ap = argparse.ArgumentParser(description="HWPX 구조 시그니처 / 골든 회귀 비교")
    ap.add_argument("hwpx")
    ap.add_argument("--golden", help="비교할 골든 스냅샷 JSON")
    ap.add_argument("--write-golden", help="현재 시그니처를 골든으로 저장")
    ap.add_argument("--json", help="시그니처 JSON 출력 경로")
    args = ap.parse_args()

    sig = signature(args.hwpx)

    if args.json:
        Path(args.json).write_text(
            json.dumps(sig, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[verify_hwpx] signature → {args.json}")

    if args.write_golden:
        meta = {"source": str(Path(args.hwpx).name)}
        try:
            meta["gitRev"] = subprocess.check_output(
                ["git", "rev-parse", "--short", "HEAD"],
                text=True, stderr=subprocess.DEVNULL).strip()
        except Exception:
            pass
        out = {"meta": meta, "signature": sig}
        p = Path(args.write_golden)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[verify_hwpx] golden → {args.write_golden}")
        return 0

    if args.golden:
        golden = json.loads(Path(args.golden).read_text(encoding="utf-8"))
        problems = diff_signatures(golden.get("signature", golden), sig)
        if problems:
            print(f"[verify_hwpx] FAIL — 골든 대비 차이 {len(problems)}건:")
            for p in problems:
                print("  -", p)
            print("(의도한 변화라면 --write-golden 으로 골든을 갱신하세요)")
            return 1
        print("[verify_hwpx] PASS — 골든과 구조 시그니처 일치")
        return 0

    print(json.dumps({k: (v if k not in ("equations",) else f"{len(v)}건")
                      for k, v in sig.items()
                      if k != "tables"}, ensure_ascii=False, indent=1))
    print(f"tables: {len(sig['tables'])}건, equations: {len(sig['equations'])}건")
    return 0


if __name__ == "__main__":
    sys.exit(main())
