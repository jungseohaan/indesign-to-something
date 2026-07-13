#!/usr/bin/env python3
"""Run page/extract regression checks for source ownership invariants.

This wrapper turns the lower-level ownership audit into a developer-loop
artifact. It can validate an existing extract directory, or run the page-scoped
issue loop first when CASE/PAGE are provided.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


REPO_ROOT = Path(__file__).resolve().parents[2]
AUDIT_PATH = REPO_ROOT / "scripts" / "ownership_plan_audit.py"
ISSUE_PATH = REPO_ROOT / "scripts" / "dev" / "issue.py"
EXTRACTION_CHECK_PATH = REPO_ROOT / "scripts" / "check_extraction_results.py"


def load_audit_module() -> Any:
    spec = importlib.util.spec_from_file_location("ownership_plan_audit", AUDIT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load audit module: {AUDIT_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_extraction_check_module() -> Any:
    spec = importlib.util.spec_from_file_location("check_extraction_results", EXTRACTION_CHECK_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load extraction result checker: {EXTRACTION_CHECK_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(cmd: List[str]) -> None:
    print("+ " + " ".join(shell_quote(c) for c in cmd))
    subprocess.run(cmd, cwd=str(REPO_ROOT), check=True)


def shell_quote(value: str) -> str:
    if not value:
        return "''"
    if all(ch.isalnum() or ch in "._/-:=+" for ch in value):
        return value
    return "'" + value.replace("'", "'\"'\"'") + "'"


def latest_issue_extract() -> Path:
    marker = REPO_ROOT / ".codex_tmp" / "latest_issue_dir"
    if not marker.exists():
        raise SystemExit("latest issue marker not found after issue run")
    issue_dir = Path(marker.read_text(encoding="utf-8").strip())
    extract_dir = issue_dir / "extract"
    if not extract_dir.exists():
        raise SystemExit(f"extract dir not found after issue run: {extract_dir}")
    return extract_dir


def resolve_extract(args: argparse.Namespace) -> Path:
    if args.extract:
        extract_dir = Path(args.extract)
        if not extract_dir.exists():
            raise SystemExit(f"extract dir does not exist: {extract_dir}")
        return extract_dir
    if not args.case or args.page is None:
        raise SystemExit("Provide --extract, or provide --case and --page.")
    cmd = [
        sys.executable,
        str(ISSUE_PATH),
        "--case",
        args.case,
        "--page",
        str(args.page),
        "--no-open",
    ]
    if args.end_page is not None:
        cmd.extend(["--end-page", str(args.end_page)])
    run(cmd)
    return latest_issue_extract()


def blocking_failures(report: Dict[str, Any], strict_warnings: bool) -> List[Dict[str, Any]]:
    counts = report.get("counts") or {}
    failures: List[Dict[str, Any]] = []
    checks = {
        "duplicateVisibleVisualSourceRefs": "same source/page has multiple visible visual owners",
        "textOwnerConflicts": "same text source is owned by PNG and HWPX text",
        "placedDespiteDrop": "visual was placed even though ObjectPlan says DROP_VISUAL",
        "noObjectPlan": "render decision has no ObjectPlan",
    }
    for key, message in checks.items():
        count = int(counts.get(key) or 0)
        if count:
            failures.append({"code": key, "count": count, "message": message})
    if strict_warnings:
        count = int(counts.get("warnings") or 0)
        if count:
            failures.append({"code": "ownershipWarnings", "count": count, "message": "ownership warnings are present"})
    return failures


def warning_findings(report: Dict[str, Any]) -> List[Dict[str, Any]]:
    counts = report.get("counts") or {}
    findings: List[Dict[str, Any]] = []
    checks = {
        "warnings": "ownership warning rows are present",
        "droppedDespitePlan": "planned visual was not placed",
        "outsidePageDespitePlan": "planned visual was outside page during execution",
        "plannedNonFloatingRoutes": "planned non-floating visual was routed later",
        "expectedNonFloatingBypass": "planned non-floating visual bypassed floating placement",
    }
    for key, message in checks.items():
        count = int(counts.get(key) or 0)
        if count:
            findings.append({"code": key, "count": count, "message": message})
    return findings


def extraction_policy_report(extract_dir: Path) -> Dict[str, Any]:
    return load_extraction_check_module().build_report(extract_dir)


def extraction_policy_failures(report: Dict[str, Any]) -> List[Dict[str, Any]]:
    failures: List[Dict[str, Any]] = []
    for error in report.get("errors") or []:
        if not isinstance(error, dict):
            continue
        failures.append({
            "code": "extractionPolicy:" + str(error.get("code") or "unknown"),
            "count": int(error.get("count") or 1),
            "message": "extraction artifact violates source ownership policy",
        })
    return failures


def write_markdown(path: Path, result: Dict[str, Any]) -> None:
    report = result["audit"]
    counts = report.get("counts") or {}
    lines = [
        "# Regression Report",
        "",
        f"- status: `{result['status']}`",
        f"- extract dir: `{result['extractDir']}`",
        f"- generated: `{result['generatedAt']}`",
        "",
        "## Blocking Failures",
        "",
    ]
    if result["blockingFailures"]:
        for failure in result["blockingFailures"]:
            lines.append(f"- `{failure['code']}`: {failure['count']} - {failure['message']}")
    else:
        lines.append("- none")
    lines += ["", "## Warnings", ""]
    if result["warnings"]:
        for warning in result["warnings"]:
            lines.append(f"- `{warning['code']}`: {warning['count']} - {warning['message']}")
    else:
        lines.append("- none")
    extraction_policy = result.get("extractionPolicy") or {}
    extraction_errors = extraction_policy.get("errors") or []
    lines += ["", "## Extraction Policy", ""]
    if extraction_errors:
        for error in extraction_errors[:20]:
            lines += ["```json", json.dumps(error, ensure_ascii=False, indent=2), "```", ""]
    else:
        lines.append("- OK")
    lines += [
        "",
        "## Counts",
        "",
        "```json",
        json.dumps(counts, ensure_ascii=False, indent=2),
        "```",
        "",
    ]
    for section in (
        "duplicateVisibleSources",
        "textOwnerConflicts",
        "placedDespiteDrop",
        "noObjectPlan",
        "droppedDespitePlan",
        "outsidePageDespitePlan",
    ):
        rows = report.get(section) or []
        if not rows:
            continue
        lines += [f"## {section}", ""]
        for row in rows[:20]:
            lines += ["```json", json.dumps(row, ensure_ascii=False, indent=2), "```", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Run source ownership regression checks.")
    parser.add_argument("--extract", type=Path, help="Existing extract directory.")
    parser.add_argument("--case", help="Case alias for a fresh page-scoped run.")
    parser.add_argument("--page", type=int, help="Physical start page for a fresh run.")
    parser.add_argument("--end-page", type=int, default=None)
    parser.add_argument("--strict-warnings", action="store_true")
    parser.add_argument("--out", type=Path, default=None, help="Report directory. Defaults under extract dir.")
    args = parser.parse_args(list(argv) if argv is not None else None)

    extract_dir = resolve_extract(args)
    extraction_policy = extraction_policy_report(extract_dir)
    audit = load_audit_module().build_report(extract_dir)
    failures = extraction_policy_failures(extraction_policy) + blocking_failures(audit, args.strict_warnings)
    warnings = warning_findings(audit)
    result = {
        "status": "FAIL" if failures else "PASS",
        "extractDir": str(extract_dir),
        "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "blockingFailures": failures,
        "warnings": warnings,
        "extractionPolicy": extraction_policy,
        "audit": audit,
    }

    out_dir = args.out or (extract_dir.parent / "regression")
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "regression-report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(out_dir / "regression-report.md", result)

    print(f"regression status: {result['status']}")
    print(f"report: {out_dir / 'regression-report.md'}")
    for failure in failures:
        print(f"FAIL {failure['code']}: {failure['count']} - {failure['message']}")
    if not failures:
        for warning in warnings[:10]:
            print(f"WARN {warning['code']}: {warning['count']} - {warning['message']}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
