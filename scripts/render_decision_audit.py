#!/usr/bin/env python3
"""SPEC-036 Tier 0 — 캐릭터화 하네스.

render-decisions.jsonl(변환 시 ResolvedBuildContext.recordRenderedDecision 출력)을 읽어
OwnershipPlanner의 plan(planVisualAction)과 실제 배치 결과(decision)가 어긋나는 항목을 찾는다.

plan↔실제 불일치 = SPEC-036이 해소하려는 "두 결정 시스템 공존" 버그의 정량 지표.
  - DROPPED_DESPITE_PLAN : plan=PLACE_FLOATING_PNG 인데 어디에도 배치 안 됨 → 누락(예: page53 일러스트)
  - PLACED_DESPITE_DROP  : plan=DROP_VISUAL 인데 floating 배치됨 → 중복

사용:
  python3 scripts/render_decision_audit.py <extract_dir>/render-decisions.jsonl
  python3 scripts/render_decision_audit.py            # 최신 추출 자동 탐색
"""
import json
import sys
import glob
import os
from collections import defaultdict, Counter

PLACED_DECISIONS = {"PLACE", "PLACE_OVERFLOW_PREVIOUS_PAGE"}


def latest_jsonl():
    cands = glob.glob(os.path.expanduser(
        "~/Library/Caches/idml-to-hwpx/extracts/*/render-decisions.jsonl"))
    cands += glob.glob("/var/folders/*/T/indd-extract-*/render-decisions.jsonl")
    if not cands:
        return None
    return max(cands, key=os.path.getmtime)


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else latest_jsonl()
    if not path or not os.path.exists(path):
        print("render-decisions.jsonl 경로를 찾지 못함. 인자로 전달하세요.", file=sys.stderr)
        sys.exit(1)
    rows = load(path)
    print(f"# render-decision audit\n원본: {path}\n총 결정 라인: {len(rows)}\n")

    # (id, itemType) 별로 결정·plan 집계
    by_item = defaultdict(lambda: {"decisions": [], "plan": None, "reason": None,
                                   "page": None, "file": None})
    for d in rows:
        key = (d.get("id"), d.get("itemType"))
        e = by_item[key]
        e["decisions"].append((d.get("phase"), d.get("decision")))
        if d.get("planVisualAction"):
            e["plan"] = d["planVisualAction"]
        e["reason"] = d.get("reason")
        e["page"] = d.get("pageIndex")
        e["file"] = d.get("file")

    dropped_despite_plan = []   # plan=PLACE_FLOATING_PNG, 미배치
    placed_despite_drop = []    # plan=DROP_VISUAL, 배치됨
    for (oid, itype), e in by_item.items():
        placed = any(dec in PLACED_DECISIONS for _, dec in e["decisions"])
        plan = e["plan"]
        if plan == "PLACE_FLOATING_PNG" and not placed:
            dropped_despite_plan.append((oid, itype, e))
        elif plan == "DROP_VISUAL" and placed:
            placed_despite_drop.append((oid, itype, e))

    def dump(title, items):
        print(f"## {title}: {len(items)}건")
        if not items:
            print("  (없음)\n")
            return
        # reason별 집계
        rc = Counter(e["reason"] for _, _, e in items)
        print("  reason별:", dict(rc.most_common()))
        # 최종 SKIP 사유별 집계
        sc = Counter()
        for _, _, e in items:
            skips = [dec for _, dec in e["decisions"] if dec not in PLACED_DECISIONS]
            sc[skips[-1] if skips else "?"] += 1
        print("  최종 결정별:", dict(sc.most_common()))
        print("  샘플(최대 15):")
        for oid, itype, e in sorted(items, key=lambda x: -(x[2]["page"] or 0))[:15]:
            chain = " → ".join(f"{p}:{dec}" for p, dec in e["decisions"])
            print(f"    id={oid} {itype} p{e['page']} reason={e['reason']}")
            print(f"        {chain}")
            print(f"        file={e['file']}")
        print()

    print("=" * 72)
    dump("DROPPED_DESPITE_PLAN (누락 위험: plan=PLACE_FLOATING_PNG 인데 미배치)",
         dropped_despite_plan)
    dump("PLACED_DESPITE_DROP (중복 위험: plan=DROP_VISUAL 인데 배치됨)",
         placed_despite_drop)

    # plan vs 실제 전체 교차표 (page_object만)
    print("## plan(visualAction) × 최종배치 교차표 (page_object)")
    cross = Counter()
    for (oid, itype), e in by_item.items():
        if itype != "page_object":
            continue
        placed = any(dec in PLACED_DECISIONS for _, dec in e["decisions"])
        cross[(e["plan"], "PLACED" if placed else "SKIPPED")] += 1
    for (plan, outcome), n in sorted(cross.items(), key=lambda x: -x[1]):
        print(f"  {str(plan):22} {outcome:8} {n}")

    total_mismatch = len(dropped_despite_plan) + len(placed_despite_drop)
    print(f"\n## 요약: 불일치 {total_mismatch}건 "
          f"(누락위험 {len(dropped_despite_plan)} / 중복위험 {len(placed_despite_drop)})")


if __name__ == "__main__":
    main()
