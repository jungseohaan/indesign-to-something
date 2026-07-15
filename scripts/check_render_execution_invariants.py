#!/usr/bin/env python3
"""Check executor-level invariants from render-decisions diagnostics.

This complements the static source-ownership guard. It does not infer
ownership from pixels or page symptoms; it verifies that Stage 3 diagnostics
execute already-declared ObjectPlan contracts in the allowed channel.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable


def iter_jsonl(path: Path) -> Iterable[dict]:
    with path.open(encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid jsonl: {exc}") from exc
            if isinstance(value, dict):
                yield value


def check_render_decisions(path: Path) -> list[str]:
    failures: list[str] = []
    for row in iter_jsonl(path):
        action = row.get("planVisualAction")
        placement = row.get("planPlacement")
        materialization = row.get("planMaterialization")
        decision = row.get("decision")
        detail = row.get("detail") or ""
        row_id = row.get("id")
        page = row.get("pageIndex")

        if action == "PLACE_TEXT_SHELL" and placement == "INLINE":
            if decision != "PLACE_INLINE_TEXT_SHELL":
                failures.append(
                    f"{path}: page={page} id={row_id}: inline PLACE_TEXT_SHELL executed as {decision}"
                )
            forbidden_details = [
                "IMAGE with HWPX text overlays",
                "deferred overlay",
                "PAPER-relative",
                "page-level overlay",
            ]
            for needle in forbidden_details:
                if needle in detail:
                    failures.append(
                        f"{path}: page={page} id={row_id}: inline PLACE_TEXT_SHELL leaked through {needle!r}"
                    )
                    break
            if materialization == "EXTRACTED_PNG_VECTOR" and "INLINE_TEXT_FRAME" not in detail:
                failures.append(
                    f"{path}: page={page} id={row_id}: extracted inline text shell missing INLINE_TEXT_FRAME execution detail"
                )
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "extract_dir",
        type=Path,
        help="Extract directory containing render-decisions.jsonl.",
    )
    args = parser.parse_args()

    render_decisions = args.extract_dir / "render-decisions.jsonl"
    if not render_decisions.exists():
        raise SystemExit(f"render-decisions.jsonl not found: {render_decisions}")

    failures = check_render_decisions(render_decisions)
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        return 1
    print("OK: render execution invariants passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
