#!/usr/bin/env python3
"""Lightweight source-ownership policy guard.

The check is intentionally narrow: it catches executor-local bypass patterns
that previously caused TextFrame geometry to be rewritten after Stage 1.
It is not a substitute for ObjectPlan validation.
"""

from __future__ import annotations

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]

CHECKS = {
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase2/FramePlacer.java": [
        "OVERLAY_Y_OVERLAP_MIN",
        "OVERLAY_X_COVERAGE_MIN",
        "XSHIFT_",
        "TITLE_SHELL_HEIGHT_RATIO",
        "MULTILINE_TITLE_SHELL_",
        "visibleOwnedTextFrameShellBounds",
        "centeredMultilineTitleShellBounds",
        "shouldAlignTitleToVisibleShell",
        "shouldExpandTitleToSiblingShell",
        "isSiblingPartOfOwnedTextShell",
        "hasHwpxOwnedVisualShell",
        "alphaBounds(",
        "shouldUseAlphaBounds(",
        "타이틀 오버레이",
        "X-shift",
        "YGAP_SPLIT_FACTOR",
        "placeByYGapSplit",
        "YGapSplit",
        "sourceIdBase + (n > 1 ? \"_g\"",
        "block.suppressParaLeftIndent",
        "TOP_ALIGN_OFFSET_RATIO",
        "topOffset / frameH",
        "block.verticalJustification(\"CENTER_ALIGN\")",
    ],
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java": [
        "suppressLeftIndent",
        "excludedParagraphIndices",
        "skipParagraphs",
    ],
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryLoader.java": [
        "suppressLeftIndent",
    ],
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/ParagraphPropertyResolver.java": [
        "suppressLeftIndent",
    ],
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/ParagraphDistributor.java": [
        "excludedParagraphIndices",
        "skipParagraphs",
    ],
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ResolvedToASTBuilder.java": [
        "SingleLineExpander",
        "singleLineExpand",
        "single-line guarantee",
    ],
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase4_7/NumberedSideHeadTableNormalizer.java": [
        "SIDE_HEAD_FLOW_UNPLANNED_BRIDGE",
        "if (!planned &&",
    ],
    "core/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ast/ASTTextFrameBlock.java": [
        "composedCharStart",
        "composedCharEnd",
        "narrowedWidth",
        "narrowedXOffset",
        "suppressParaLeftIndent",
        "excludedParagraphIndices",
        "skipParagraphs",
    ],
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/flat/FlatLayoutNode.java": [
        "narrowedWidth",
        "narrowedXOffset",
    ],
}

DELETED_FILES = [
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase5/WrapPhase5.java",
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase4_7/SingleLineExpander.java",
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/legacy/FloatingImageMerger.java",
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/stage3/VisualOverflowPlacer.java",
]


def main() -> int:
    failures: list[str] = []
    for rel_path, needles in CHECKS.items():
        path = ROOT / rel_path
        if not path.exists():
            failures.append(f"missing checked file: {rel_path}")
            continue
        text = path.read_text(encoding="utf-8")
        for needle in needles:
            if needle in text:
                failures.append(f"{rel_path}: forbidden executor-local TF geometry bypass: {needle}")

    for rel_path in DELETED_FILES:
        path = ROOT / rel_path
        if path.exists():
            failures.append(f"{rel_path}: forbidden legacy wrap/layout postprocess file still exists")

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1

    print("OK: source ownership policy guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
