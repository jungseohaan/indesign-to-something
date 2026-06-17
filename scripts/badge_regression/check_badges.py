#!/usr/bin/env python3
"""Badge regression harness for the source ownership policy.

For each fixture in corpus.json: convert the extraction to HWPX, then assert
that badge text is owned by HWPX text and the visual shell is placed once from
the Stage 1 ownership plan. The test intentionally follows source object
ownership instead of guessing from text length, coordinates, or page-specific
phrases.

PASS criteria per badge:
  text_present    : the badge text appears in HWPX text runs
  tf_ids          : resolved.json has exact source TextFrame ids for the badge
  shell           : a rendered ObjectPlan for those ids is PLACE_TEXT_SHELL and
                    the executor placed it as a shell figure
  no_complete_png : no rendered plan owns the same badge text as a complete PNG

Native text-on-fill boxes are still accepted as a compatibility fallback, but
the preferred policy shape is textless shell figure + editable HWPX TextFrame.

Usage:
  python3 scripts/badge_regression/check_badges.py            # convert + check all
  python3 scripts/badge_regression/check_badges.py --build    # mvn build first
  python3 scripts/badge_regression/check_badges.py --json baseline.json  # write report
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def sh(cmd, **kw):
    return subprocess.run(cmd, cwd=ROOT, shell=isinstance(cmd, str), **kw)


def build():
    print(">> building core+converter ...", flush=True)
    r = sh("mvn -q -pl core install -DskipTests && mvn -q -pl converter package -DskipTests",
           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if r.returncode != 0:
        sys.stdout.write(r.stdout.decode("utf-8", "replace"))
        raise SystemExit("BUILD FAILED")


def convert(cfg, fx, out_hwpx):
    idml = os.path.join(ROOT, fx["idml"])
    resolved = os.path.join(ROOT, fx["resolved"])
    links = fx["links"]
    jar = os.path.join(ROOT, cfg["jar"])
    cmd = [cfg["java"], "-jar", jar, "--convert", idml, out_hwpx,
           "--resolved", resolved, "--links-directory", links]
    r = sh(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if r.returncode != 0:
        sys.stdout.write(r.stdout.decode("utf-8", "replace"))
        raise SystemExit("CONVERT FAILED for " + fx["name"])


def read_sections(hwpx):
    secs = []
    with zipfile.ZipFile(hwpx) as z:
        for n in sorted(z.namelist()):
            if re.match(r"Contents/section\d+\.xml$", n):
                secs.append(z.read(n).decode("utf-8", "replace"))
    return secs


def normalize_text(value):
    value = value or ""
    for ch in ("\ufffc", "\u0016", "\u0018", "\u0007"):
        value = value.replace(ch, "")
    return re.sub(r"\s+", "", value)


def load_json(path):
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_jsonl(path):
    rows = []
    if not path or not os.path.exists(path):
        return rows
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def enclosing_box(xml, pos):
    """Return (box_xml, has_bg) for the innermost <hp:rect> OR <hp:tbl> containing pos.

    A unified inline badge renders as an inline box (treatAsChar=1) whose own
    background carries the badge graphic, so we accept inline OR floating boxes —
    the invariant is text-on-own-background, not the anchor mode.
    """
    best = None  # (start, end)
    for tag in ("hp:rect", "hp:tbl"):
        stack = []
        for m in re.finditer(r"<%s\b[^>]*>|</%s>" % (tag, tag), xml):
            if m.group().startswith("</"):
                if stack:
                    op = stack.pop()
                    if op.start() <= pos <= m.end():
                        if best is None or op.start() > best[0]:
                            best = (op.start(), m.end())
            else:
                stack.append(m)
    if best is None:
        return None, False
    seg = xml[best[0]:best[1]]
    has_bg = ("imgBrush" in seg or "binaryItemIDRef" in seg
              or "winBrush" in seg or "faceColor" in seg)
    return seg, has_bg


def all_boxes(xml):
    """Yield (seg, stripped_runtext, has_bg) for every balanced <hp:rect>/<hp:tbl>.

    Badge text is often tokenized across runs (e.g. '구성 방향' -> '구성',' ','방향'),
    so we match against each box's whitespace-stripped concatenated run text.
    """
    for tag in ("hp:rect", "hp:tbl"):
        stack = []
        for m in re.finditer(r"<%s\b[^>]*>|</%s>" % (tag, tag), xml):
            if m.group().startswith("</"):
                if stack:
                    op = stack.pop()
                    seg = xml[op.start():m.end()]
                    runs = "".join(re.findall(r"<hp:t>(.*?)</hp:t>", seg, re.S))
                    stripped = re.sub(r"\s+", "", runs)
                    has_bg = ("imgBrush" in seg or "binaryItemIDRef" in seg
                              or "winBrush" in seg or "faceColor" in seg)
                    yield seg, stripped, has_bg
            else:
                stack.append(m)


def exact_text_frame_ids(resolved, text):
    target = normalize_text(text)
    ids = []
    for tf in resolved.get("textFrames", []):
        visible = normalize_text(tf.get("frameVisibleText"))
        if visible == target:
            ids.append(str(tf.get("id")))
    return ids


def plan_sources(plan):
    return {str(x) for x in plan.get("sourceObjectIds", [])}


def shell_plans_for(plans, tf_ids):
    tf_ids = set(tf_ids)
    return [
        p for p in plans
        if p.get("renderId", -1) != -1
        and p.get("visualAction") == "PLACE_TEXT_SHELL"
        and plan_sources(p) & tf_ids
    ]


def complete_png_dups_for(plans, tf_ids):
    tf_ids = set(tf_ids)
    return [
        p for p in plans
        if p.get("renderId", -1) != -1
        and plan_sources(p) & tf_ids
        and p.get("textAction") == "OWNED_BY_PNG"
        and str(p.get("visualAction", "")).startswith("PLACE_")
    ]


def placed_shell_ids(decisions):
    return {
        str(d.get("id")) for d in decisions
        if d.get("decision") in ("PLACE_TEXT_SHELL_FIGURE", "PLACE_TEXT_SHELL_GRAPHIC")
    }


def check_badge(secs, resolved, plans, decisions, text):
    res = {"text_present": False, "on_background": False, "occurrences": 0,
           "in_boxed": 0, "tf_ids": [], "shell_planned": False,
           "shell_placed": False, "complete_png_dups": 0,
           "no_complete_png": True}
    target = normalize_text(text)
    for xml in secs:
        run_text = normalize_text("".join(re.findall(r"<hp:t>(.*?)</hp:t>", xml, re.S)))
        if target in run_text:
            res["text_present"] = True
        for _seg, runtext, has_bg in all_boxes(xml):
            if target and target in runtext:
                res["occurrences"] += 1
                res["in_boxed"] += 1
                if has_bg:
                    res["on_background"] = True
    res["tf_ids"] = exact_text_frame_ids(resolved, text)
    shells = shell_plans_for(plans, res["tf_ids"])
    res["shell_planned"] = bool(shells)
    shell_ids = {str(p.get("renderId")) for p in shells}
    placed = placed_shell_ids(decisions)
    res["shell_placed"] = bool(shell_ids & placed) if decisions else res["shell_planned"]
    dups = complete_png_dups_for(plans, res["tf_ids"])
    res["complete_png_dups"] = len(dups)
    res["no_complete_png"] = not dups
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--json", default=None)
    ap.add_argument("--corpus", default=os.path.join(os.path.dirname(__file__), "corpus.json"))
    ap.add_argument("--strict", action="store_true",
                    help="exit non-zero when any badge regression fails")
    args = ap.parse_args()

    cfg = json.load(open(args.corpus, encoding="utf-8"))
    if args.build:
        build()

    report = {"fixtures": []}
    total = passed = 0
    for fx in cfg["fixtures"]:
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, fx["name"] + ".hwpx")
            convert(cfg, fx, out)
            secs = read_sections(out)
            resolved = load_json(os.path.join(ROOT, fx["resolved"]))
            fixture_dir = os.path.dirname(os.path.join(ROOT, fx["resolved"]))
            plans = load_jsonl(os.path.join(fixture_dir, "ownership-plan.jsonl"))
            decisions = load_jsonl(os.path.join(fixture_dir, "render-decisions.jsonl"))
            frow = {"name": fx["name"], "badges": []}
            print("\n=== fixture: %s ===" % fx["name"])
            print("  %-12s %-5s %-5s %-5s %-5s  %s" % (
                "badge", "text", "tf", "shell", "PASS", "(occ/boxed/dups)"))
            for b in fx["badges"]:
                r = check_badge(secs, resolved, plans, decisions, b["text"])
                has_shell_or_native_bg = r["shell_placed"] or r["on_background"]
                ok = (r["text_present"] and bool(r["tf_ids"])
                      and has_shell_or_native_bg and r["no_complete_png"])
                total += 1
                passed += 1 if ok else 0
                print("  %-12s %-5s %-5s %-5s %-5s  (%d/%d/%d)" % (
                    b["text"], "Y" if r["text_present"] else "-",
                    "Y" if r["tf_ids"] else "-",
                    "Y" if has_shell_or_native_bg else "-",
                    "PASS" if ok else "FAIL",
                    r["occurrences"], r["in_boxed"], r["complete_png_dups"]))
                frow["badges"].append({**b, **r, "pass": ok})
            report["fixtures"].append(frow)
    print("\n>> %d/%d badges PASS" % (passed, total))
    report["summary"] = {"passed": passed, "total": total}
    if args.json:
        json.dump(report, open(args.json, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        print(">> report written to", args.json)
    if args.strict and passed != total:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
