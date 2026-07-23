#!/usr/bin/env python3
"""HWPX 문자 속성 프로파일 — GREP/스타일 변경의 회귀 감시용 (SPEC-067).

구조 시그니처 골든(verify_hwpx.py)에는 색·폰트·이탤릭 축이 없다. GREP 규칙은
주로 이 축에 작용하므로, 문자별 (색, 폰트, 이탤릭, 첨자) 분포를 따로 떠서
변경 전후를 비교한다.

사용:
  python3 scripts/dev/charpr_profile.py out.hwpx --write baseline.json
  python3 scripts/dev/charpr_profile.py out.hwpx --compare baseline.json
"""
import argparse, json, re, sys, zipfile
from collections import Counter
from pathlib import Path


def profile(path):
    z = zipfile.ZipFile(path)
    header = z.read("Contents/header.xml").decode("utf-8", errors="ignore")
    fonts = {m.group(1): m.group(2)
             for m in re.finditer(r'<hh:font id="(\d+)"[^>]*face="([^"]*)"', header)}
    props = {}
    for m in re.finditer(r'<hh:charPr id="(\d+)"(.*?)</hh:charPr>', header, re.S):
        cid, body = m.group(1), m.group(2)
        color = re.search(r'textColor="([^"]*)"', body)
        face = re.search(r'hangul="(\d+)"', body)
        props[cid] = {
            "color": color.group(1) if color else None,
            "font": fonts.get(face.group(1)) if face else None,
            "italic": "<hh:italic/>" in body,
            "bold": "<hh:bold/>" in body,
            "sub": "SUBSCRIPT" in body,
            "sup": "SUPERSCRIPT" in body,
        }
    colors, faces, flags = Counter(), Counter(), Counter()
    for n in z.namelist():
        if not re.match(r"Contents/section\d+\.xml", n):
            continue
        x = z.read(n).decode("utf-8", errors="ignore")
        for m in re.finditer(r'charPrIDRef="(\d+)"[^>]*>\s*<hp:t>([^<]*)</hp:t>', x):
            p = props.get(m.group(1))
            if not p:
                continue
            chars = len(m.group(2))
            colors[p["color"] or "?"] += chars
            faces[p["font"] or "?"] += chars
            for k in ("italic", "bold", "sub", "sup"):
                if p[k]:
                    flags[k] += chars
    return {"colors": dict(colors), "fonts": dict(faces), "flags": dict(flags)}


def compare(base, cur, tolerance):
    problems = []
    for axis in ("colors", "fonts", "flags"):
        b, c = base.get(axis, {}), cur.get(axis, {})
        for k in sorted(set(b) | set(c)):
            bv, cv = b.get(k, 0), c.get(k, 0)
            if bv == cv:
                continue
            if abs(bv - cv) <= max(tolerance, bv * 0.005):
                continue
            problems.append(f"{axis}[{k}]: {bv} → {cv}")
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("hwpx")
    ap.add_argument("--write")
    ap.add_argument("--compare")
    ap.add_argument("--tolerance", type=int, default=0)
    a = ap.parse_args()
    p = profile(a.hwpx)
    if a.write:
        Path(a.write).parent.mkdir(parents=True, exist_ok=True)
        Path(a.write).write_text(json.dumps(p, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[charpr] baseline → {a.write}")
        return 0
    if a.compare:
        base = json.loads(Path(a.compare).read_text(encoding="utf-8"))
        probs = compare(base, p, a.tolerance)
        if probs:
            print(f"[charpr] DIFF {len(probs)}건:")
            for s in probs[:40]:
                print("  -", s)
            return 1
        print("[charpr] PASS — 문자 속성 분포 일치")
        return 0
    print(json.dumps(p, ensure_ascii=False, indent=1)[:2000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
