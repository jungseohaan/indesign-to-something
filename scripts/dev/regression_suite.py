#!/usr/bin/env python3
"""Run registered page-level ownership regression checks.

The suite is intentionally invariant-based. It reuses existing page-scoped
extracts by default and only runs InDesign when --run-missing is explicit.
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
DEFAULT_REGISTRY = REPO_ROOT / "test-data" / "issue-regressions.json"
ISSUE_PATH = REPO_ROOT / "scripts" / "dev" / "issue.py"
REGRESSION_PATH = REPO_ROOT / "scripts" / "dev" / "regression.py"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_regression_module() -> Any:
    spec = importlib.util.spec_from_file_location("dev_regression", REGRESSION_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load regression module: {REGRESSION_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def shell_quote(value: str) -> str:
    if not value:
        return "''"
    if all(ch.isalnum() or ch in "._/-:=+" for ch in value):
        return value
    return "'" + value.replace("'", "'\"'\"'") + "'"


def run(cmd: List[str]) -> None:
    print("+ " + " ".join(shell_quote(c) for c in cmd))
    subprocess.run(cmd, cwd=str(REPO_ROOT), check=True)


def issue_dir_prefix(case_name: str, page: int, end_page: Optional[int]) -> str:
    if end_page and end_page != page:
        return f"p{page:03d}-{end_page:03d}-"
    return f"p{page:03d}-"


def latest_extract(output_root: Path, case_name: str, page: int, end_page: Optional[int]) -> Optional[Path]:
    case_dir = output_root / case_name
    if not case_dir.exists():
        return None
    prefix = issue_dir_prefix(case_name, page, end_page)
    candidates: List[Path] = []
    for issue_dir in case_dir.iterdir():
        if not issue_dir.is_dir() or not issue_dir.name.startswith(prefix):
            continue
        extract_dir = issue_dir / "extract"
        if (extract_dir / "output.idml").exists():
            candidates.append(extract_dir)
    if not candidates:
        return None
    return sorted(candidates, key=lambda p: p.parent.name)[-1]


def run_issue(case: Dict[str, Any], output_root: Path) -> Path:
    cmd = [
        sys.executable,
        str(ISSUE_PATH),
        "--case",
        str(case["case"]),
        "--page",
        str(case["page"]),
        "--output-root",
        str(output_root),
        "--no-open",
    ]
    if case.get("endPage"):
        cmd.extend(["--end-page", str(case["endPage"])])
    run(cmd)
    extract = latest_extract(output_root, str(case["case"]), int(case["page"]), case.get("endPage"))
    if extract is None:
        raise RuntimeError(f"issue run did not produce extract for {case['id']}")
    return extract


def legacy_filtered_count(summary: Optional[Dict[str, Any]]) -> int:
    if not summary:
        return 0
    try:
        return int(summary.get("filteredCount") or 0)
    except (TypeError, ValueError):
        return 0


def run_regression(
    extract_dir: Path,
    strict_warnings: bool,
    out_dir: Path,
    fail_legacy_filters: bool,
) -> Dict[str, Any]:
    regression = load_regression_module()
    extraction_policy = regression.extraction_policy_report(extract_dir)
    audit = regression.load_audit_module().build_report(extract_dir)
    failures = regression.extraction_policy_failures(extraction_policy)
    failures.extend(regression.blocking_failures(audit, strict_warnings))
    warnings = regression.warning_findings(audit)
    legacy_summary = legacy_normalization_summary(extract_dir)
    legacy_count = legacy_filtered_count(legacy_summary)
    if fail_legacy_filters and legacy_count > 0:
        failures.append(
            {
                "code": "legacy_normalization_filter_present",
                "count": legacy_count,
                "message": "Stage 1 ownership should prevent legacy normalization filters.",
            }
        )
    result = {
        "status": "FAIL" if failures else "PASS",
        "extractDir": str(extract_dir),
        "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "blockingFailures": failures,
        "warnings": warnings,
        "extractionPolicy": extraction_policy,
        "legacyNormalizationFilterSummary": legacy_summary,
        "audit": audit,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "regression-report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    regression.write_markdown(out_dir / "regression-report.md", result)
    return result


def legacy_normalization_summary(extract_dir: Optional[Path]) -> Optional[Dict[str, Any]]:
    if extract_dir is None:
        return None
    path = extract_dir / "legacy-normalization-filters.json"
    if not path.exists():
        return None
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError):
        return None
    summary = data.get("summary")
    return summary if isinstance(summary, dict) else None


def format_reason_counts(summary: Optional[Dict[str, Any]]) -> str:
    if not summary:
        return ""
    total = summary.get("filteredCount")
    reason_counts = summary.get("reasonCounts") or {}
    if not isinstance(reason_counts, dict):
        reason_counts = {}
    parts = []
    for reason, count in sorted(reason_counts.items(), key=lambda item: int(item[1]), reverse=True)[:3]:
        parts.append(f"{reason}={count}")
    if total is None:
        return ", ".join(parts)
    if not parts:
        return str(total)
    return f"{total}: " + ", ".join(parts)


def first_present(*values: Any) -> Optional[Any]:
    for value in values:
        if value is not None:
            return value
    return None


def sample_source_id(sample: Dict[str, Any]) -> Optional[Any]:
    source = first_present(sample.get("sourceObjectId"), sample.get("id"))
    if source is not None:
        return source
    for key in ("sourceObjectIds", "visualSourceObjectIds", "ownedTextFrameIds"):
        values = sample.get(key)
        if isinstance(values, list) and values:
            return values[0]
    return None


def sample_page_index(sample: Dict[str, Any]) -> Optional[int]:
    page = sample.get("pageIndex")
    try:
        return int(page)
    except (TypeError, ValueError):
        return None


def trace_commands(case: Dict[str, Any], extract_dir: Optional[str], result: Optional[Dict[str, Any]]) -> List[str]:
    if not extract_dir:
        return []
    commands: List[str] = []
    physical_page = int(case.get("page"))
    commands.append(f"make inventory EXTRACT={shell_quote(extract_dir)} PAGE={physical_page}")
    if not result:
        return commands
    audit = result.get("audit") or {}
    sections = (
        "duplicateVisibleSources",
        "textOwnerConflicts",
        "droppedDespitePlan",
        "placedDespiteDrop",
        "noObjectPlan",
        "outsidePageDespitePlan",
    )
    seen = set()
    for section in sections:
        for sample in audit.get(section) or []:
            source = sample_source_id(sample)
            if source is None:
                continue
            page_index = sample_page_index(sample)
            if page_index is not None:
                command = f"make trace EXTRACT={shell_quote(extract_dir)} PAGE_INDEX={page_index} SOURCE={source}"
            else:
                command = f"make trace EXTRACT={shell_quote(extract_dir)} PAGE={physical_page} SOURCE={source}"
            if command not in seen:
                seen.add(command)
                commands.append(command)
            if len(commands) >= 6:
                return commands
    return commands


def selected_cases(registry: Dict[str, Any], ids: List[str], tags: List[str]) -> List[Dict[str, Any]]:
    cases = [case for case in registry.get("cases") or [] if isinstance(case, dict)]
    if ids:
        wanted = set(ids)
        cases = [case for case in cases if case.get("id") in wanted]
    if tags:
        wanted_tags = set(tags)
        cases = [case for case in cases if wanted_tags & set(case.get("tags") or [])]
    return cases


def case_status(case: Dict[str, Any], result: Optional[Dict[str, Any]], skipped_reason: Optional[str]) -> str:
    if skipped_reason:
        return "SKIP"
    if not result:
        return "UNKNOWN"
    return str(result.get("status") or "UNKNOWN")


def write_suite_markdown(path: Path, rows: List[Dict[str, Any]]) -> None:
    lines = [
        "# Regression Suite Report",
        "",
        f"- generated: `{time.strftime('%Y-%m-%d %H:%M:%S')}`",
        "",
        "| status | id | case | page | title | legacy filters | report | trace hint |",
        "|---|---|---|---:|---|---|---|---|",
    ]
    for row in rows:
        case = row["case"]
        status = row["status"]
        report_path = row.get("reportPath") or ""
        extract = row.get("extractDir") or ""
        trace_hint = ""
        trace_cmds = row.get("traceCommands") or []
        if trace_cmds:
            trace_hint = "<br>".join(f"`{cmd}`" for cmd in trace_cmds[:3])
        if row.get("skippedReason"):
            trace_hint = row["skippedReason"]
        lines.append(
            "| "
            + " | ".join(
                [
                    status,
                    str(case.get("id")),
                    str(case.get("case")),
                    str(case.get("page")),
                    str(case.get("title") or ""),
                    format_reason_counts(row.get("legacyNormalizationFilterSummary")),
                    f"`{report_path}`" if report_path else "",
                    trace_hint,
                ]
            )
            + " |"
        )
    aggregate: Dict[str, int] = {}
    aggregate_total = 0
    for row in rows:
        summary = row.get("legacyNormalizationFilterSummary") or {}
        try:
            aggregate_total += int(summary.get("filteredCount") or 0)
        except (TypeError, ValueError):
            pass
        reason_counts = summary.get("reasonCounts") or {}
        if not isinstance(reason_counts, dict):
            continue
        for reason, count in reason_counts.items():
            try:
                aggregate[reason] = aggregate.get(reason, 0) + int(count)
            except (TypeError, ValueError):
                continue
    if aggregate_total or aggregate:
        lines.append("")
        lines.append("## Legacy Normalization Filters")
        lines.append("")
        lines.append(f"- total filtered candidates: `{aggregate_total}`")
        for reason, count in sorted(aggregate.items(), key=lambda item: item[1], reverse=True):
            lines.append(f"- `{reason}`: {count}")
    lines.append("")
    lines.append("## Failures")
    lines.append("")
    has_failure = False
    for row in rows:
        result = row.get("result") or {}
        failures = result.get("blockingFailures") or []
        if not failures:
            continue
        has_failure = True
        lines.append(f"### {row['case'].get('id')}")
        lines.append("")
        for failure in failures:
            lines.append(f"- `{failure.get('code')}`: {failure.get('count')} - {failure.get('message')}")
        trace_cmds = row.get("traceCommands") or []
        if trace_cmds:
            lines.append("")
            lines.append("Trace commands:")
            lines.append("")
            for cmd in trace_cmds:
                lines.append(f"- `{cmd}`")
        lines.append("")
    if not has_failure:
        lines.append("- none")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Run registered issue regression cases.")
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    parser.add_argument("--output-root", type=Path, default=REPO_ROOT / "output" / "issues")
    parser.add_argument("--out", type=Path, default=REPO_ROOT / "output" / "regression-suite")
    parser.add_argument("--id", action="append", default=[], help="Run only a registry id. May be repeated.")
    parser.add_argument("--tag", action="append", default=[], help="Run only cases with this tag. May be repeated.")
    parser.add_argument("--run-missing", action="store_true", help="Run page-scoped issue extraction when no existing extract is found.")
    parser.add_argument("--force-run", action="store_true", help="Run page-scoped issue extraction even when an existing extract is present.")
    parser.add_argument("--strict-warnings", action="store_true")
    parser.add_argument(
        "--allow-legacy-filters",
        action="store_true",
        help="Do not fail when legacy normalization filters are present.",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)

    registry = load_json(args.registry)
    cases = selected_cases(registry, args.id, args.tag)
    if not cases:
        raise SystemExit("No regression cases selected.")

    rows: List[Dict[str, Any]] = []
    for case in cases:
        extract = latest_extract(args.output_root, str(case["case"]), int(case["page"]), case.get("endPage"))
        skipped_reason: Optional[str] = None
        if args.force_run:
            extract = run_issue(case, args.output_root)
        elif extract is None:
            if args.run_missing:
                extract = run_issue(case, args.output_root)
            else:
                skipped_reason = "missing extract; rerun with --run-missing"

        result: Optional[Dict[str, Any]] = None
        report_path: Optional[Path] = None
        if extract is not None:
            report_dir = args.out / str(case["id"])
            result = run_regression(
                extract,
                args.strict_warnings,
                report_dir,
                not args.allow_legacy_filters,
            )
            report_path = report_dir / "regression-report.md"

        rows.append(
            {
                "case": case,
                "status": case_status(case, result, skipped_reason),
                "extractDir": str(extract) if extract is not None else None,
                "reportPath": str(report_path) if report_path is not None else None,
                "skippedReason": skipped_reason,
                "result": result,
                "legacyNormalizationFilterSummary": (
                    result.get("legacyNormalizationFilterSummary") if result else legacy_normalization_summary(extract)
                ),
                "traceCommands": trace_commands(case, str(extract) if extract is not None else None, result),
            }
        )
        print(f"{rows[-1]['status']} {case['id']}")

    args.out.mkdir(parents=True, exist_ok=True)
    suite = {
        "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "registry": str(args.registry),
        "rows": rows,
    }
    (args.out / "regression-suite.json").write_text(json.dumps(suite, ensure_ascii=False, indent=2), encoding="utf-8")
    write_suite_markdown(args.out / "regression-suite.md", rows)
    print(f"suite report: {args.out / 'regression-suite.md'}")
    return 1 if any(row["status"] == "FAIL" for row in rows) else 0


if __name__ == "__main__":
    raise SystemExit(main())
