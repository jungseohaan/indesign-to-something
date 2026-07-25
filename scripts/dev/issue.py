#!/usr/bin/env python3
"""Run a page-scoped InDesign issue loop.

This is intentionally a thin orchestration wrapper. Ownership decisions still
belong to extract_indd.jsx/ObjectPlan; this script only resolves a case, runs a
physical page range, converts the result, and writes diagnostics that shorten
the policy-code-verify loop.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


REPO_ROOT = Path(__file__).resolve().parents[2]
CASES_JSON = REPO_ROOT / "test-data" / "cases.json"
EXTRACT_JSX = REPO_ROOT / "scripts" / "extract_indd.jsx"
CONVERTER_JAR = REPO_ROOT / "converter" / "target" / "idml-to-something-1.0.9-cli.jar"
CONVERSION_CONFIG = REPO_ROOT / "conversion-config.json"
DEFAULT_JAVA = Path("/opt/homebrew/opt/openjdk/bin/java")
AUDIT_PATH = REPO_ROOT / "scripts" / "ownership_plan_audit.py"
TRACE_SOURCE_PATH = REPO_ROOT / "scripts" / "dev" / "trace_source.py"
PAGE_INVENTORY_PATH = REPO_ROOT / "scripts" / "dev" / "page_inventory.py"
IDML_CACHE_ROOT = REPO_ROOT / "output" / "cache" / "idml"
PREVIEW_CACHE_ROOT = REPO_ROOT / "output" / "cache" / "preview"
PAGE_PLANE_CACHE_ROOT = REPO_ROOT / "output" / "cache" / "page_background_plane"

CASE_ALIASES = {
    "park31-u1": ("중3-1국어교과서(박영민)", "u1"),
    "park31-u2": ("중3-1국어교과서(박영민)", "u2"),
    "park31-u3": ("중3-1국어교과서(박영민)", "u3"),
    "park31-u4": ("중3-1국어교과서(박영민)", "u4"),
    "phs31-u1-1": ("중3-1국어교과서(박현숙)", "u1-1"),
    "phs31-u1-2": ("중3-1국어교과서(박현숙)", "u1-2"),
    "lit-guide-0": ("고등문학지도서", "총론"),
    "lit-guide-u1": ("고등문학지도서", "u1"),
    "lit-guide-u2": ("고등문학지도서", "u2"),
}


def load_cases(cases_json: Path) -> Dict[str, Any]:
    with cases_json.open("r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("cases", {})


def resolve_case(case_name: str, cases_json: Path) -> Tuple[str, str, Dict[str, Any]]:
    cases = load_cases(cases_json)
    if case_name in CASE_ALIASES:
        book_key, unit_key = CASE_ALIASES[case_name]
    elif "/" in case_name:
        book_key, unit_key = case_name.split("/", 1)
    elif ":" in case_name:
        book_key, unit_key = case_name.split(":", 1)
    else:
        matches: List[Tuple[str, str]] = []
        for book, units in cases.items():
            if not isinstance(units, dict):
                continue
            if case_name in units:
                matches.append((book, case_name))
        if len(matches) == 1:
            book_key, unit_key = matches[0]
        elif not matches:
            raise SystemExit(f"Unknown CASE '{case_name}'. Use alias or book/unit.")
        else:
            raise SystemExit(f"Ambiguous CASE '{case_name}'. Use book/unit.")

    book = cases.get(book_key)
    if not isinstance(book, dict):
        raise SystemExit(f"Unknown book '{book_key}' for CASE '{case_name}'.")
    unit = book.get(unit_key)
    if not isinstance(unit, dict) or not unit.get("path"):
        raise SystemExit(f"Unknown unit '{unit_key}' under '{book_key}'.")
    return book_key, unit_key, unit


def resolve_indd_path(path_value: str) -> Path:
    path = Path(path_value)
    if path.is_file():
        return path
    if not path.exists() or not path.is_dir():
        return path
    matches = sorted(
        p for p in path.glob("*.indd")
        if p.is_file() and not p.name.startswith("~")
    )
    if len(matches) == 1:
        return matches[0]
    if not matches:
        return path
    names = ", ".join(str(p.name) for p in matches[:8])
    if len(matches) > 8:
        names += ", ..."
    raise SystemExit(f"Multiple INDD files under case directory {path}: {names}")


def page_label(page: int, end_page: Optional[int]) -> str:
    if end_page and end_page != page:
        return f"p{page:03d}-{end_page:03d}"
    return f"p{page:03d}"


def parse_declared_pages(pages: Any) -> Optional[Tuple[int, int]]:
    if not isinstance(pages, str):
        return None
    raw = pages.strip().replace("~", "-")
    if not raw:
        return None
    if "-" in raw:
        left, right = raw.split("-", 1)
    else:
        left = right = raw
    try:
        start = int(left.strip())
        end = int(right.strip())
    except ValueError:
        return None
    if end < start:
        return None
    return start, end


def resolve_extract_page_range(unit: Dict[str, Any], page: int, end_page: int) -> Dict[str, Any]:
    declared = parse_declared_pages(unit.get("pages"))
    if declared is None:
        return {
            "printedStartPage": page,
            "printedEndPage": end_page,
            "extractStartPage": page,
            "extractEndPage": end_page,
            "pageRangeMode": "local_no_declared_pages",
            "declaredPages": None,
        }
    declared_start, declared_end = declared
    if page < declared_start or end_page > declared_end:
        raise SystemExit(
            f"Requested page range {page}..{end_page} is outside declared unit pages "
            f"{declared_start}..{declared_end}. Use the correct CASE or add a local-page mode deliberately."
        )
    return {
        "printedStartPage": page,
        "printedEndPage": end_page,
        "extractStartPage": page - declared_start + 1,
        "extractEndPage": end_page - declared_start + 1,
        "pageRangeMode": "declared_printed_to_local",
        "declaredPages": [declared_start, declared_end],
    }


def run(cmd: List[str], *, cwd: Path = REPO_ROOT, dry_run: bool = False) -> None:
    print("+ " + " ".join(shell_quote(c) for c in cmd))
    if dry_run:
        return
    subprocess.run(cmd, cwd=str(cwd), check=True)


def try_run(cmd: List[str], *, cwd: Path = REPO_ROOT) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        cwd=str(cwd),
        check=False,
        capture_output=True,
        text=True,
    )


def load_module(path: Path, name: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def shell_quote(value: str) -> str:
    if not value:
        return "''"
    if all(ch.isalnum() or ch in "._/-:=+" for ch in value):
        return value
    return "'" + value.replace("'", "'\"'\"'") + "'"


def launch_indesign(app_name: str, issue_dir: Path) -> None:
    prelaunch = f'tell application "{app_name}" to activate\n'
    prelaunch_file = issue_dir / "_prelaunch.applescript"
    prelaunch_file.write_text(prelaunch, encoding="utf-8")
    try_run(["osascript", str(prelaunch_file)])


def ensure_indesign_running(app_name: str, issue_dir: Path, dry_run: bool = False) -> None:
    if dry_run:
        return

    already_running = try_run(["pgrep", "-x", app_name]).returncode == 0
    if not already_running:
        launch_indesign(app_name, issue_dir)
        time.sleep(10)

    probe = (
        f'using terms from application "{app_name}"\n'
        f'    tell application "{app_name}" to get name\n'
        f'end using terms from\n'
    )
    probe_file = issue_dir / "_probe.applescript"
    probe_file.write_text(probe, encoding="utf-8")

    for attempt in range(10):
        result = try_run(["osascript", str(probe_file)])
        if result.returncode == 0:
            return
        if attempt == 5:
            print("[issue] InDesign SDEF probe 5회 실패 → hung 의심, killall 후 재기동")
            try_run(["killall", app_name])
            time.sleep(3)
            launch_indesign(app_name, issue_dir)
            time.sleep(15)
        else:
            wait = 5 if attempt == 0 else 9
            time.sleep(wait)

    last = try_run(["osascript", str(probe_file)])
    stderr = (last.stderr or "").strip()
    raise SystemExit(
        f"InDesign AppleScript dictionary probe failed after retries: {stderr or 'unknown error'}"
    )


def write_applescript(
    path: Path,
    app_name: str,
    indd_path: Path,
    output_dir: Path,
    start_page: int,
    end_page: int,
    perf_mode: str,
    skip_pdf: bool,
    extract_config: str,
    extract_mode: str,
    reuse_existing_idml: bool,
    page_plane_cache_dir_path: Path,
    content_mode: str = "full",
) -> None:
    args = [
        str(indd_path),
        str(output_dir),
        str(start_page),
        str(end_page),
        "0",
        "0",
        extract_config,
        perf_mode,
        "1" if skip_pdf else "0",
        "",
        extract_mode,
        "",
        "1",
        "",
        "",
        "1" if reuse_existing_idml else "0",
        str(EXTRACT_JSX),
        str(page_plane_cache_dir_path),
        content_mode,
    ]
    quoted_args = ", ".join(json.dumps(a, ensure_ascii=False) for a in args)
    script = f'''using terms from application "{app_name}"
    tell application "{app_name}"
        activate
        repeat with _preflightAttempt from 1 to 30
            try
                do script "app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT; app.scriptPreferences.enableRedraw = false; 1" language javascript
                exit repeat
            on error
                delay 2
            end try
        end repeat
        set _jsxSource to read POSIX file "{EXTRACT_JSX}"
        set _jsxArgs to {{{quoted_args}}}
        with timeout of 3600 seconds
            do script _jsxSource language javascript with arguments _jsxArgs
        end timeout
    end tell
end using terms from
'''
    path.write_text(script, encoding="utf-8")


def validate_extraction_page_range(extract_dir: Path, extract_range: Dict[str, Any]) -> None:
    """Fail fast when the extractor did not honor the requested local page range."""
    plan_path = extract_dir / "extraction-plan.json"
    if not plan_path.exists():
        raise SystemExit(f"extraction-plan.json not found after extraction: {plan_path}")
    try:
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid extraction-plan.json: {plan_path}: {exc}") from exc
    page_range = plan.get("pageRange")
    if not isinstance(page_range, dict):
        raise SystemExit(f"extraction-plan.json has no pageRange object: {plan_path}")

    expected_start = int(extract_range["extractStartPage"])
    expected_end = int(extract_range["extractEndPage"])
    actual_start = page_range.get("startPage")
    actual_end = page_range.get("endPage")
    actual_count = page_range.get("rangePageCount")
    page_count = page_range.get("pageCount")

    problems: List[str] = []
    if actual_start != expected_start or actual_end != expected_end:
        problems.append(f"expected local range {expected_start}..{expected_end}, got {actual_start}..{actual_end}")
    if not isinstance(actual_count, int) or actual_count < 1:
        problems.append(f"invalid rangePageCount={actual_count!r}")
    if isinstance(actual_start, int) and isinstance(actual_end, int) and actual_start > actual_end:
        problems.append(f"startPage {actual_start} > endPage {actual_end}")
    if isinstance(page_count, int) and isinstance(actual_end, int) and actual_end > page_count:
        problems.append(f"endPage {actual_end} exceeds pageCount {page_count}")
    expected_count = expected_end - expected_start + 1
    if isinstance(actual_count, int) and actual_count != expected_count:
        problems.append(f"expected rangePageCount {expected_count}, got {actual_count}")
    if problems:
        raise SystemExit("Extraction page range validation failed: " + "; ".join(problems))


def validate_extraction_success(extract_dir: Path) -> None:
    """Fail fast when InDesign reported an extraction error via `.done`."""
    done_path = extract_dir / ".done"
    if not done_path.exists():
        raise SystemExit(f"extraction status file not found after extraction: {done_path}")
    done_text = done_path.read_text(encoding="utf-8", errors="replace").strip()
    try:
        status = json.loads(done_text)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid extraction status JSON: {done_path}: {exc}: {done_text}") from exc
    if status.get("status") != "ok":
        message = status.get("message") or done_text
        raise SystemExit(f"Extraction failed; refusing legacy conversion fallback: {message}")
    resolved_path = extract_dir / "resolved.json"
    if not resolved_path.exists():
        raise SystemExit(f"resolved.json not found after successful extraction: {resolved_path}")


def parse_timing(path: Path) -> List[Tuple[str, int, int]]:
    if not path.exists():
        return []
    rows: List[Tuple[str, int, int]] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line or line.startswith("#") or line.startswith("tag\t"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        try:
            rows.append((parts[0], int(parts[1]), int(parts[2])))
        except ValueError:
            continue
    return rows


def write_perf_summary(extract_dir: Path, issue_dir: Path) -> None:
    rows = parse_timing(extract_dir / "_phase_timing.log")
    out = issue_dir / "perf-summary.md"
    if not rows:
        out.write_text("# Perf Summary\n\nNo `_phase_timing.log` found.\n", encoding="utf-8")
        return
    total = rows[-1][1]
    slow = sorted(rows, key=lambda r: r[2], reverse=True)[:15]
    lines = [
        "# Perf Summary",
        "",
        f"- extract total: {total / 1000.0:.2f}s",
        "",
        "## Slowest Phases",
        "",
        "| phase | delta | elapsed |",
        "|---|---:|---:|",
    ]
    for tag, elapsed, delta in slow:
        lines.append(f"| `{tag}` | {delta / 1000.0:.2f}s | {elapsed / 1000.0:.2f}s |")
    lines.append("")
    out.write_text("\n".join(lines), encoding="utf-8")


def copy_if_exists(src: Path, dst: Path) -> None:
    if src.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def link_or_copy_file(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def idml_cache_key(indd_path: Path) -> str:
    stat = indd_path.stat()
    payload = {
        "path": str(indd_path.resolve()),
        "size": stat.st_size,
        "mtimeNs": stat.st_mtime_ns,
    }
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def extract_script_version() -> str:
    try:
        text = EXTRACT_JSX.read_text(encoding="utf-8")
    except OSError:
        return "unknown"
    marker = 'EXTRACT_SCRIPT_VERSION = "'
    start = text.find(marker)
    if start < 0:
        return "unknown"
    start += len(marker)
    end = text.find('"', start)
    if end < 0:
        return "unknown"
    return text[start:end]


def idml_cache_dir(indd_path: Path) -> Path:
    return IDML_CACHE_ROOT / idml_cache_key(indd_path)


def page_plane_cache_dir(indd_path: Path, perf_mode: str, dpi: int = 220) -> Path:
    version = extract_script_version()
    mode = (perf_mode or "standard").lower()
    # SPEC-054: dpi 를 캐시 키에 포함해 해상도가 다른 페이지 평면 PNG 가 섞이지
    # 않게 한다. (기존 무접미사 캐시는 perfMode override 로 사실상 150dpi 산출물)
    return PAGE_PLANE_CACHE_ROOT / idml_cache_key(indd_path) / f"extract-v{version}" / f"single-textless-plane-{mode}-{int(dpi)}dpi"


def write_derived_extract_config(base_config_path: Path, issue_dir: Path, dpi: int) -> Path:
    """rendering.pngExportResolution 을 dpi 로 고정한 파생 config 를 issue 디렉토리에 쓴다 (SPEC-054).

    pngExportResolutionLocked 로 추출기의 perfMode override(fast=150/high=300)를
    무시하고 명시 dpi 가 항상 이기게 한다.
    """
    with base_config_path.open("r", encoding="utf-8") as f:
        config = json.load(f)
    rendering = config.setdefault("rendering", {})
    rendering["pngExportResolution"] = int(dpi)
    rendering["pngExportResolutionLocked"] = True
    # 케이스명(한글)이 포함된 issue 디렉토리 경로는 ExtendScript File() 로딩이
    # 실패할 수 있어 ASCII 경로(output/cache)에 dpi 별로 쓴다 (SPEC-054).
    derived_path = REPO_ROOT / "output" / "cache" / f"extract-config-{int(dpi)}dpi.json"
    derived_path.parent.mkdir(parents=True, exist_ok=True)
    with derived_path.open("w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
    return derived_path


def restore_cached_idml(indd_path: Path, extract_dir: Path, enabled: bool) -> Dict[str, Any]:
    cache_dir = idml_cache_dir(indd_path)
    info: Dict[str, Any] = {
        "enabled": enabled,
        "hit": False,
        "cacheDir": str(cache_dir),
        "key": cache_dir.name,
    }
    if not enabled:
        return info
    cached_idml = cache_dir / "output.idml"
    if not cached_idml.exists():
        info["reason"] = "miss"
        return info
    link_or_copy_file(cached_idml, extract_dir / "output.idml")
    for name in ["idml-zorder-map.json", "idml-zorder-map-summary.json", "_idml_zorder_map.log"]:
        cached = cache_dir / name
        if cached.exists():
            link_or_copy_file(cached, extract_dir / name)
    info["hit"] = True
    info["reason"] = "restored"
    return info


def store_cached_idml(indd_path: Path, extract_dir: Path, enabled: bool) -> Dict[str, Any]:
    cache_dir = idml_cache_dir(indd_path)
    info: Dict[str, Any] = {
        "enabled": enabled,
        "stored": False,
        "cacheDir": str(cache_dir),
        "key": cache_dir.name,
    }
    if not enabled:
        return info
    idml_path = extract_dir / "output.idml"
    if not idml_path.exists():
        info["reason"] = "missing_output_idml"
        return info
    cache_dir.mkdir(parents=True, exist_ok=True)
    cached_idml = cache_dir / "output.idml"
    if not cached_idml.exists() or not os.path.samefile(idml_path, cached_idml):
        shutil.copy2(idml_path, cached_idml)
    for name in ["idml-zorder-map.json", "idml-zorder-map-summary.json", "_idml_zorder_map.log"]:
        src = extract_dir / name
        dst = cache_dir / name
        if src.exists() and (not dst.exists() or not os.path.samefile(src, dst)):
            shutil.copy2(src, dst)
    stat = indd_path.stat()
    metadata = {
        "sourceINDD": str(indd_path.resolve()),
        "sourceSize": stat.st_size,
        "sourceMtimeNs": stat.st_mtime_ns,
        "storedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    (cache_dir / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    info["stored"] = True
    info["reason"] = "stored"
    return info


def preview_cache_file(indd_path: Path, start_page: int, end_page: int) -> Path:
    return PREVIEW_CACHE_ROOT / idml_cache_key(indd_path) / f"p{start_page:03d}-{end_page:03d}.pdf"


def restore_cached_preview(indd_path: Path, extract_dir: Path, start_page: int, end_page: int, enabled: bool) -> Dict[str, Any]:
    cached_pdf = preview_cache_file(indd_path, start_page, end_page)
    info: Dict[str, Any] = {
        "enabled": enabled,
        "hit": False,
        "cacheFile": str(cached_pdf),
    }
    if not enabled:
        info["reason"] = "disabled"
        return info
    if not cached_pdf.exists():
        info["reason"] = "miss"
        return info
    link_or_copy_file(cached_pdf, extract_dir / "preview.pdf")
    info["hit"] = True
    info["reason"] = "restored"
    return info


def store_cached_preview(indd_path: Path, extract_dir: Path, start_page: int, end_page: int, enabled: bool) -> Dict[str, Any]:
    cached_pdf = preview_cache_file(indd_path, start_page, end_page)
    info: Dict[str, Any] = {
        "enabled": enabled,
        "stored": False,
        "cacheFile": str(cached_pdf),
    }
    if not enabled:
        info["reason"] = "disabled"
        return info
    preview_pdf = extract_dir / "preview.pdf"
    if not preview_pdf.exists():
        info["reason"] = "missing_preview_pdf"
        return info
    cached_pdf.parent.mkdir(parents=True, exist_ok=True)
    if not cached_pdf.exists() or not os.path.samefile(preview_pdf, cached_pdf):
        shutil.copy2(preview_pdf, cached_pdf)
    stat = indd_path.stat()
    metadata = {
        "sourceINDD": str(indd_path.resolve()),
        "sourceSize": stat.st_size,
        "sourceMtimeNs": stat.st_mtime_ns,
        "startPage": start_page,
        "endPage": end_page,
        "storedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    (cached_pdf.parent / f"{cached_pdf.stem}.metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    info["stored"] = True
    info["reason"] = "stored"
    return info


def write_preview_applescript(
    path: Path,
    app_name: str,
    indd_path: Path,
    preview_pdf: Path,
    start_page: int,
    end_page: int,
) -> None:
    js = f"""
(function () {{
    app.scriptPreferences.userInteractionLevel = UserInteractionLevels.NEVER_INTERACT;
    app.scriptPreferences.enableRedraw = false;

    var inddFile = File({json.dumps(str(indd_path), ensure_ascii=False)});
    var outFile = File({json.dumps(str(preview_pdf), ensure_ascii=False)});
    if (!inddFile.exists) throw new Error("INDD not found: " + inddFile.fsName);
    if (!outFile.parent.exists) outFile.parent.create();

    function findOpenDocument(fsName) {{
        for (var i = 0; i < app.documents.length; i++) {{
            try {{
                if (app.documents[i].fullName && app.documents[i].fullName.fsName === fsName) {{
                    return app.documents[i];
                }}
            }} catch (e) {{}}
        }}
        return null;
    }}

    var doc = findOpenDocument(inddFile.fsName);
    var openedHere = false;
    if (!doc) {{
        doc = app.open(inddFile, false);
        openedHere = true;
    }}

    try {{
        app.pdfExportPreferences.exportReaderSpreads = false;
        if ({start_page} === 1 && {end_page} === doc.pages.length) {{
            app.pdfExportPreferences.pageRange = PageRange.ALL_PAGES;
        }} else {{
            app.pdfExportPreferences.pageRange = "+{start_page}-+{end_page}";
        }}
        app.pdfExportPreferences.colorBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.colorBitmapSamplingDPI = 300;
        app.pdfExportPreferences.colorBitmapCompression = BitmapCompression.JPEG;
        app.pdfExportPreferences.colorBitmapQuality = CompressionQuality.HIGH;
        app.pdfExportPreferences.grayscaleBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.grayscaleBitmapSamplingDPI = 300;
        app.pdfExportPreferences.grayscaleBitmapCompression = BitmapCompression.JPEG;
        app.pdfExportPreferences.grayscaleBitmapQuality = CompressionQuality.HIGH;
        app.pdfExportPreferences.monochromeBitmapSampling = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.monochromeBitmapSamplingDPI = 1200;
        app.pdfExportPreferences.cropImagesToFrames = true;
        app.pdfExportPreferences.compressTextAndLineArt = true;
        app.pdfExportPreferences.acrobatCompatibility = AcrobatCompatibility.ACROBAT_7;
        app.pdfExportPreferences.subsetFontsBelow = 100;
        app.pdfExportPreferences.optimizePDF = true;
        doc.exportFile(ExportFormat.PDF_TYPE, outFile);
    }} finally {{
        if (openedHere) doc.close(SaveOptions.NO);
    }}
}})();
"""
    script = f'''using terms from application "{app_name}"
    tell application "{app_name}"
        activate
        with timeout of 1800 seconds
            do script {json.dumps(js, ensure_ascii=False)} language javascript
        end timeout
    end tell
end using terms from
'''
    path.write_text(script, encoding="utf-8")


def ensure_preview_pdf(
    app_name: str,
    indd_path: Path,
    extract_dir: Path,
    issue_dir: Path,
    start_page: int,
    end_page: int,
    enabled: bool,
    dry_run: bool,
) -> Dict[str, Any]:
    restore = restore_cached_preview(indd_path, extract_dir, start_page, end_page, enabled)
    if not enabled or restore.get("hit") or dry_run:
        return {"restore": restore}

    script_path = issue_dir / "run_preview_export.scpt"
    write_preview_applescript(script_path, app_name, indd_path, extract_dir / "preview.pdf", start_page, end_page)
    run(["osascript", str(script_path)], dry_run=dry_run)
    store = store_cached_preview(indd_path, extract_dir, start_page, end_page, enabled)
    return {"restore": restore, "store": store}


def write_trace_files(extract_dir: Path, issue_dir: Path) -> None:
    trace = issue_dir / "trace"
    trace.mkdir(parents=True, exist_ok=True)
    for name in [
        "ownership-plan.jsonl",
        "ownership-warnings.jsonl",
        "render-decisions.jsonl",
        "extraction-results.json",
        "extraction-plan.json",
        "object-plans.json",
        "planner-diagnostics-summary.json",
        "resolved.json",
    ]:
        copy_if_exists(extract_dir / name, trace / name)


def write_audit_reports(extract_dir: Path, issue_dir: Path) -> Dict[str, Any]:
    audit_dir = issue_dir / "audit"
    audit_dir.mkdir(parents=True, exist_ok=True)
    report = load_module(AUDIT_PATH, "ownership_plan_audit").build_report(extract_dir)
    (audit_dir / "ownership-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    counts = report.get("counts") or {}
    warning_counts = report.get("warningCounts") or {}
    page_scores = report.get("pageScores") or {}
    lines = [
        "# Ownership Audit Summary",
        "",
        "## Blocking Counts",
        "",
        f"- duplicate visible visual source refs: `{counts.get('duplicateVisibleVisualSourceRefs', 0)}`",
        f"- text owner conflicts: `{counts.get('textOwnerConflicts', 0)}`",
        f"- placed despite DROP_VISUAL: `{counts.get('placedDespiteDrop', 0)}`",
        f"- render rows without ObjectPlan: `{counts.get('noObjectPlan', 0)}`",
        "",
        "## Warning Counts",
        "",
        f"- ownership warning rows: `{counts.get('warnings', 0)}`",
        f"- dropped despite plan: `{counts.get('droppedDespitePlan', 0)}`",
        f"- outside page despite plan: `{counts.get('outsidePageDespitePlan', 0)}`",
        f"- expected non-floating bypass: `{counts.get('expectedNonFloatingBypass', 0)}`",
        f"- planned non-floating routes: `{counts.get('plannedNonFloatingRoutes', 0)}`",
        "",
    ]
    if warning_counts:
        lines += ["## Ownership Warning Codes", ""]
        for code, count in sorted(warning_counts.items(), key=lambda item: (-item[1], str(item[0]))):
            lines.append(f"- `{code}`: {count}")
        lines.append("")
    if page_scores:
        lines += ["## Pages To Inspect", ""]
        for page_key, score in sorted(page_scores.items(), key=lambda item: (-item[1], int(item[0])))[:12]:
            lines.append(f"- pageIndex `{page_key}`: score {score}")
        lines.append("")
    for section in ("duplicateVisibleSources", "textOwnerConflicts", "droppedDespitePlan", "placedDespiteDrop", "noObjectPlan"):
        rows = report.get(section) or []
        if not rows:
            continue
        lines += [f"## {section} Samples", ""]
        for row in rows[:5]:
            lines += ["```json", json.dumps(row, ensure_ascii=False, indent=2), "```", ""]
    (audit_dir / "ownership-audit.md").write_text("\n".join(lines), encoding="utf-8")
    return report


def write_source_trace(
    extract_dir: Path,
    issue_dir: Path,
    page_index: int,
    sources: List[str],
    snippet: Optional[str],
) -> Optional[Path]:
    if not sources and not snippet:
        return None
    trace_module = load_module(TRACE_SOURCE_PATH, "trace_source")
    source_ids = trace_module.parse_sources(sources)
    report = trace_module.build_report(extract_dir, source_ids, page_index, snippet)
    trace_dir = issue_dir / "trace"
    trace_dir.mkdir(parents=True, exist_ok=True)
    md_path = trace_dir / "source-trace.md"
    trace_module.write_markdown(md_path, report)
    (trace_dir / "source-trace.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return md_path


def write_page_inventory(extract_dir: Path, issue_dir: Path, page_index: int) -> Optional[Path]:
    inventory_module = load_module(PAGE_INVENTORY_PATH, "page_inventory")
    inventory = inventory_module.build_inventory(extract_dir, page_index)
    trace_dir = issue_dir / "trace"
    inventory_module.write_inventory(trace_dir, inventory)
    return trace_dir / "page-inventory.md"


def write_report(
    issue_dir: Path,
    case_name: str,
    book_key: str,
    unit_key: str,
    unit: Dict[str, Any],
    page: int,
    end_page: int,
    extract_range: Dict[str, Any],
    extract_dir: Path,
    hwpx_path: Path,
    audit: Optional[Dict[str, Any]] = None,
    source_trace_path: Optional[Path] = None,
    page_inventory_path: Optional[Path] = None,
) -> None:
    done_text = ""
    done_path = extract_dir / ".done"
    if done_path.exists():
        done_text = done_path.read_text(encoding="utf-8", errors="replace").strip()
    rows = parse_timing(extract_dir / "_phase_timing.log")
    total = f"{rows[-1][1] / 1000.0:.2f}s" if rows else "n/a"
    render_count = len(list((extract_dir / "rendered_frames").glob("*"))) if (extract_dir / "rendered_frames").exists() else 0
    audit_counts = (audit or {}).get("counts") or {}
    lines = [
        "# Issue Run Report",
        "",
        f"- case: `{case_name}`",
        f"- book: `{book_key}`",
        f"- unit: `{unit_key}` ({unit.get('desc', '')})",
        f"- physical page range: `{page}..{end_page}`",
        f"- extract local page range: `{extract_range['extractStartPage']}..{extract_range['extractEndPage']}`",
        f"- page range mode: `{extract_range['pageRangeMode']}`",
        f"- source INDD: `{unit.get('path', '')}`",
        f"- extract dir: `{extract_dir}`",
        f"- HWPX: `{hwpx_path}`",
        f"- extract status: `{done_text or 'unknown'}`",
        f"- rendered files: `{render_count}`",
        f"- extract elapsed: `{total}`",
        "",
        "## Audit",
        "",
        f"- duplicate visible visual source refs: `{audit_counts.get('duplicateVisibleVisualSourceRefs', 'n/a')}`",
        f"- text owner conflicts: `{audit_counts.get('textOwnerConflicts', 'n/a')}`",
        f"- dropped despite plan: `{audit_counts.get('droppedDespitePlan', 'n/a')}`",
        f"- placed despite DROP_VISUAL: `{audit_counts.get('placedDespiteDrop', 'n/a')}`",
        f"- no ObjectPlan render rows: `{audit_counts.get('noObjectPlan', 'n/a')}`",
        f"- full audit: `{issue_dir / 'audit' / 'ownership-audit.md'}`",
        "",
        "## Next Inspection",
        "",
        "- Start with `trace/page-inventory.md` to find source/text/plan/render candidates on this page.",
        "- Check `trace/ownership-plan.jsonl` for Stage 1 ownership.",
        "- Check `trace/render-decisions.jsonl` for executed visual placement.",
        "- Check `perf-summary.md` for bottlenecks.",
    ]
    if page_inventory_path is not None:
        lines.append(f"- Page inventory: `{page_inventory_path}`.")
    if source_trace_path is not None:
        lines.append(f"- Check `{source_trace_path}` for the requested source/snippet trace.")
    lines.append("")
    (issue_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


def ensure_converter_built(dry_run: bool) -> None:
    if CONVERTER_JAR.exists():
        return
    run(["mvn", "-pl", "converter", "-am", "-DskipTests", "package"], dry_run=dry_run)


def java_command() -> str:
    env_java = os.environ.get("JAVA")
    if env_java:
        return env_java
    if DEFAULT_JAVA.exists():
        return str(DEFAULT_JAVA)
    return "java"


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Run a page-scoped issue extraction/conversion loop.")
    parser.add_argument("--case", required=True, help="Case alias, unit key, or book/unit path.")
    parser.add_argument("--page", required=True, type=int, help="Physical start page.")
    parser.add_argument("--end-page", type=int, default=None, help="Physical end page. Defaults to --page.")
    parser.add_argument("--cases-json", default=str(CASES_JSON), help="Case registry JSON path.")
    parser.add_argument("--output-root", default=str(REPO_ROOT / "output" / "issues"))
    parser.add_argument("--app", default="Adobe InDesign 2026")
    parser.add_argument("--perf-mode", default="fast")
    parser.add_argument(
        "--extract-config",
        default=str(CONVERSION_CONFIG),
        help="Optional extractor config path. Defaults to the repo conversion-config.json.",
    )
    parser.add_argument("--extract-mode", default="full", help="Extractor mode, e.g. full or spread_chunks.")
    parser.add_argument(
        "--content-mode",
        default="full",
        choices=["full", "text-only", "graphic-only"],
        help="SPEC-054: text-only skips all PNG rendering; graphic-only converts visuals without text.",
    )
    parser.add_argument(
        "--dpi",
        type=int,
        default=120,
        help="SPEC-054: PNG export resolution for the dev loop (default 120; production uses 220).",
    )
    parser.add_argument(
        "--reuse-idml",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Reuse cached output.idml for repeated immutable-INDD test runs.",
    )
    parser.add_argument("--source", action="append", default=[], help="Optional source id to trace after conversion.")
    parser.add_argument("--snippet", default=None, help="Optional text snippet to trace after conversion.")
    parser.add_argument("--skip-pdf", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--open", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument(
        "--margin-guide",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Draw diagnostic margin guide lines into the converted HWPX.",
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(list(argv) if argv is not None else None)

    end_page = args.end_page or args.page
    if end_page < args.page:
        raise SystemExit("--end-page must be >= --page")

    cases_json = Path(args.cases_json)
    if not cases_json.exists():
        raise SystemExit(f"cases.json not found: {cases_json}")
    book_key, unit_key, unit = resolve_case(args.case, cases_json)
    indd_path = resolve_indd_path(unit["path"])
    if not indd_path.exists():
        raise SystemExit(f"INDD path does not exist: {indd_path}")
    if not EXTRACT_JSX.exists():
        raise SystemExit(f"extract script not found: {EXTRACT_JSX}")
    extract_range = resolve_extract_page_range(unit, args.page, end_page)

    stamp = time.strftime("%Y%m%d-%H%M%S")
    output_root = Path(args.output_root)
    if not output_root.is_absolute():
        output_root = REPO_ROOT / output_root
    issue_dir = output_root / args.case / f"{page_label(args.page, args.end_page)}-{stamp}"
    extract_dir = issue_dir / "extract"
    converted_dir = issue_dir / "converted"
    page_plane_cache = page_plane_cache_dir(indd_path, args.perf_mode, args.dpi)

    script_path = issue_dir / "run_extract.scpt"
    if not args.dry_run:
        extract_dir.mkdir(parents=True, exist_ok=True)
        converted_dir.mkdir(parents=True, exist_ok=True)
        extract_config_path = write_derived_extract_config(
            Path(args.extract_config), issue_dir, args.dpi)
        idml_cache_restore = restore_cached_idml(indd_path, extract_dir, args.reuse_idml)
        write_applescript(
            script_path,
            args.app,
            indd_path,
            extract_dir,
            int(extract_range["extractStartPage"]),
            int(extract_range["extractEndPage"]),
            args.perf_mode,
            True,
            str(extract_config_path),
            args.extract_mode,
            bool(idml_cache_restore.get("hit")),
            page_plane_cache,
            args.content_mode,
        )
    else:
        idml_cache_restore = {
            "enabled": args.reuse_idml,
            "hit": False,
            "cacheDir": str(idml_cache_dir(indd_path)),
            "key": idml_cache_dir(indd_path).name,
            "reason": "dry_run",
        }

    print(f"[issue] case={args.case} book={book_key} unit={unit_key} page={args.page}..{end_page}")
    print(f"[issue] extract-local-page={extract_range['extractStartPage']}..{extract_range['extractEndPage']} mode={extract_range['pageRangeMode']}")
    print(f"[issue] content-mode={args.content_mode} dpi={args.dpi}")
    print(f"[issue] extract-mode={args.extract_mode} graphics-mode=single-textless-plane")
    print(f"[issue] idml-cache={idml_cache_restore.get('reason')} hit={idml_cache_restore.get('hit')} dir={idml_cache_restore.get('cacheDir')}")
    print(f"[issue] page-plane-cache=enabled dir={page_plane_cache}")
    print(f"[issue] preview-cache={'disabled' if args.skip_pdf else 'enabled'}")
    print(f"[issue] output={issue_dir}")
    ensure_indesign_running(args.app, issue_dir, args.dry_run)
    run(["osascript", str(script_path)], dry_run=args.dry_run)

    if not args.dry_run:
        issue_meta = {
            "case": args.case,
            "book": book_key,
            "unit": unit_key,
            "sourceINDD": str(indd_path),
            "extractMode": args.extract_mode,
            "graphicsMode": "single-textless-plane",
            "idmlCache": idml_cache_restore,
            "pagePlaneCache": {
                "enabled": True,
                "cacheDir": str(page_plane_cache),
                "key": page_plane_cache.name,
            },
            **extract_range,
        }
        (extract_dir / "issue-run.json").write_text(json.dumps(issue_meta, ensure_ascii=False, indent=2), encoding="utf-8")
        (issue_dir / "issue-run.json").write_text(json.dumps(issue_meta, ensure_ascii=False, indent=2), encoding="utf-8")
        validate_extraction_success(extract_dir)
        validate_extraction_page_range(extract_dir, extract_range)
        idml_cache_store = store_cached_idml(indd_path, extract_dir, args.reuse_idml)
        preview_cache = ensure_preview_pdf(
            args.app,
            indd_path,
            extract_dir,
            issue_dir,
            int(extract_range["extractStartPage"]),
            int(extract_range["extractEndPage"]),
            not args.skip_pdf,
            args.dry_run,
        )
        issue_meta["idmlCacheStore"] = idml_cache_store
        issue_meta["previewCache"] = preview_cache
        (extract_dir / "issue-run.json").write_text(json.dumps(issue_meta, ensure_ascii=False, indent=2), encoding="utf-8")
        (issue_dir / "issue-run.json").write_text(json.dumps(issue_meta, ensure_ascii=False, indent=2), encoding="utf-8")

    case_file_label = args.case.replace("/", "-").replace(":", "-")
    hwpx_path = converted_dir / f"{case_file_label}-{page_label(args.page, args.end_page)}.hwpx"
    ensure_converter_built(args.dry_run)
    convert_cmd = [
        java_command(),
        "-jar",
        str(CONVERTER_JAR),
        "--convert",
        str(extract_dir / "output.idml"),
        str(hwpx_path),
        "--links-directory",
        str(extract_dir / "Links"),
        "--include-images",
    ]
    if args.margin_guide:
        convert_cmd.append("--margin-guide")
    if CONVERSION_CONFIG.exists():
        convert_cmd.extend(["--config", str(CONVERSION_CONFIG)])
    run(convert_cmd, dry_run=args.dry_run)

    if not args.dry_run:
        write_trace_files(extract_dir, issue_dir)
        write_perf_summary(extract_dir, issue_dir)
        audit = write_audit_reports(extract_dir, issue_dir)
        target_page_index = int(extract_range["extractStartPage"]) - 1
        page_inventory_path = write_page_inventory(extract_dir, issue_dir, target_page_index)
        source_trace_path = write_source_trace(extract_dir, issue_dir, target_page_index, args.source, args.snippet)
        write_report(
            issue_dir,
            args.case,
            book_key,
            unit_key,
            unit,
            args.page,
            end_page,
            extract_range,
            extract_dir,
            hwpx_path,
            audit,
            source_trace_path,
            page_inventory_path,
        )
        (REPO_ROOT / ".codex_tmp").mkdir(exist_ok=True)
        (REPO_ROOT / ".codex_tmp" / "latest_issue_dir").write_text(str(issue_dir), encoding="utf-8")
        if args.open:
            run(["open", str(hwpx_path)], dry_run=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
