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
    "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase4_7/NumberedSideHeadTableNormalizer.java",
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

    dovira = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/DoviraSubunitMarkerPolicy.java"
    if dovira.exists():
        text = dovira.read_text(encoding="utf-8")
        if "closeBounds(inlineMarker.bounds(), rg.bounds())" in text:
            guard = "resolvedData.ownershipPlans() != null && !resolvedData.ownershipPlans().isEmpty()"
            if guard not in text:
                failures.append(
                    "DoviraSubunitMarkerPolicy.java: duplicate marker bounds heuristic must be legacy-only behind Stage 1 ObjectPlan guard"
                )

    resolved_data = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/resolved/ResolvedData.java"
    if resolved_data.exists():
        text = resolved_data.read_text(encoding="utf-8")
        if "public boolean isHwpxOwnedTextFrame(String domId)" in text:
            required = [
                "if (hasStage1ObjectPlans())",
                "return hasHwpxTextOwnerPlanForFrame(domId);",
                "return hasPngTextOwnerPlanForFrame(domId);",
            ]
            for needle in required:
                if needle not in text:
                    failures.append(
                        "ResolvedData.java: text ownership helpers must use ObjectPlan textAction before rendered textOwner fallback"
                    )
                    break
        for marker in [
            "public boolean shouldKeepVisualLabelTextEditable(RenderedGroup item)",
            "private void unregisterEditableLabelTextOwnersCoveredByShell(RenderedGroup shell)",
        ]:
            if marker in text:
                block = text[text.index(marker):]
                if "if (hasStage1ObjectPlans()) return" not in block[:240]:
                    failures.append(
                        "ResolvedData.java: visual-label text-owner repair must be legacy-only behind Stage 1 ObjectPlan guard"
                    )
                    break

    resolved_build_context = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/ResolvedBuildContext.java"
    if resolved_build_context.exists():
        text = resolved_build_context.read_text(encoding="utf-8")
        if "public boolean shouldDropVisualByOwnershipPlan(RenderedGroup rg)" in text:
            required = [
                "public boolean hasStage1ObjectPlans()",
                "if (hasStage1ObjectPlans()) {\n            return false;\n        }\n        return isTextFrameOwnedButPlanMissingForRendered(rg);",
                "if (hasStage1ObjectPlans()) return false;",
            ]
            for needle in required:
                if needle not in text:
                    failures.append(
                        "ResolvedBuildContext.java: rendered text-owner visual drop fallback must be legacy-only behind Stage 1 ObjectPlan guard"
                    )
                    break

    frame_placer = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase2/FramePlacer.java"
    if frame_placer.exists():
        text = frame_placer.read_text(encoding="utf-8")
        marker = "private static String[] plannedOrLegacyEditableTextFrameIds"
        if marker in text:
            helper = text[text.index(marker):]
            plan_idx = helper.find("if (plan != null)")
            stage1_idx = helper.find("if (ctx != null && ctx.hasStage1ObjectPlans())")
            rendered_idx = helper.find("if (!rg.hasEditableTextHiddenFromPng())")
            if plan_idx < 0 or stage1_idx < 0 or rendered_idx < 0 or not (plan_idx < stage1_idx < rendered_idx):
                failures.append(
                    "FramePlacer.java: inline editable text-length rendered fallback must be after ObjectPlan and Stage 1 guards"
                )

    table_builder = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase4/TableBuilder.java"
    if table_builder.exists():
        text = table_builder.read_text(encoding="utf-8")
        forbidden_table_absorption_helpers = [
            "private static int absorbCellBackgroundPageObjects",
            "private static int absorbTableBorderPageObjects",
            "private static Set<Integer> tableStyleSourceIdsForOwner",
            "private static List<ASTTableCell> findStyleBandCoveredCells",
            "private static void suppressRenderedGroupsCoveredByAbsorbedCellBackgrounds",
        ]
        for marker in forbidden_table_absorption_helpers:
            if marker in text:
                failures.append(
                    "TableBuilder.java: V2 must not restore page-object table decoration absorption helpers"
                )
                break
        forbidden_table_projection = [
            "page_object backgrounds absorbed into table cells",
            "page_object borders absorbed into table cell borders",
            "cell background absorbed: page_item",
            "table border absorbed: page_item",
        ]
        for needle in forbidden_table_projection:
            if needle in text:
                failures.append(
                    "TableBuilder.java: V2 must not report page-object table decoration as HWPX table style absorption"
                )

    background_injector = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase6/BackgroundInjector.java"
    if background_injector.exists():
        text = background_injector.read_text(encoding="utf-8")
        forbidden_crop_contracts = [
            "private static double[] cropSourceBounds(ObjectPlan plan)",
            "return plan != null ? plan.renderSourceBounds : null;",
        ]
        for needle in forbidden_crop_contracts:
            if needle in text:
                failures.append(
                    "BackgroundInjector.java: Stage 3 must not derive cropSourceBounds from ObjectPlan.renderSourceBounds"
                )
                break
        if "plan.cropSourceBounds" not in text:
            failures.append(
                "BackgroundInjector.java: Stage 3 crop must execute ObjectPlan.cropSourceBounds when present"
            )
        if "return rg != null ? rg.cropSourceBounds() : null;" not in text:
            failures.append(
                "BackgroundInjector.java: legacy crop fallback must use only explicit RenderedGroup.cropSourceBounds"
            )

    planner_bundles = ROOT / "scripts/indd/planner_bundles.jsx"
    if planner_bundles.exists():
        text = planner_bundles.read_text(encoding="utf-8")
        forbidden_crop_aliases = [
            "|| candidate.cropSourceBounds",
            "candidate.cropSourceBounds || _plannerBundleRenderSourceBounds",
        ]
        for needle in forbidden_crop_aliases:
            if needle in text:
                failures.append(
                    "planner_bundles.jsx: cropSourceBounds must not be aliased into renderSourceBounds"
                )
                break
        if "_plannerBundleRenderSourceBounds" in text:
            failures.append(
                "planner_bundles.jsx: source extent must not be named as render-only bounds when it also supplies cropSourceBounds"
            )
        required_crop_contract = [
            "_plannerBundleSourceExtentBounds",
            "_plannerBundleCropSourceBounds",
            "var sourceExtentBounds = _plannerBundleSourceExtentBounds",
            ": _plannerBundleCropSourceBounds(candidate, sourceExtentBounds)",
        ]
        for needle in required_crop_contract:
            if needle not in text:
                failures.append(
                    "planner_bundles.jsx: page-local visual fragments must derive explicit cropSourceBounds from Stage 1 source extent, not Stage 3 geometry"
                )
                break
        if "cropSourceBounds: cropSourceBounds" not in text:
            failures.append(
                "planner_bundles.jsx: ObjectPlan must carry cropSourceBounds as a first-class field"
            )

    ownership_planner = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/ownership/OwnershipPlanner.java"
    if ownership_planner.exists():
        text = ownership_planner.read_text(encoding="utf-8")
        forbidden_crop_aliases = [
            "renderSourceBoundsOfRenderedFragment",
            "plan.renderSourceBounds)",
            "plan.renderSourceBounds;",
        ]
        for needle in forbidden_crop_aliases:
            if needle in text:
                failures.append(
                    "OwnershipPlanner.java: Stage 1 must not use renderSourceBounds as an executable crop/page-fragment contract"
                )
                break
        if "cropSourceBoundsOfRenderedFragment" not in text or "plan.cropSourceBounds" not in text:
            failures.append(
                "OwnershipPlanner.java: page-local visual fragments must use explicit cropSourceBounds"
            )
        forbidden_reason_text_shell_repairs = [
            "isVisualOnlyTextShellReason",
            'reason.contains("visual_only")',
        ]
        for needle in forbidden_reason_text_shell_repairs:
            if needle in text:
                failures.append(
                    "OwnershipPlanner.java: text-shell text-slot cleanup must not depend on diagnostic reason visual_only strings"
                )
                break

    ownership_validator = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/ownership/OwnershipPlanValidator.java"
    if ownership_validator.exists():
        text = ownership_validator.read_text(encoding="utf-8")
        forbidden_reason_text_shell_shortcuts = [
            "isVisualOnlyTextShellReason",
            'reason.contains("visual_only")',
        ]
        for needle in forbidden_reason_text_shell_shortcuts:
            if needle in text:
                failures.append(
                    "OwnershipPlanValidator.java: visual-only text-shell validation must require structured text ownership, not reason strings"
                )
                break

    resolved_data = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/resolved/ResolvedData.java"
    if resolved_data.exists():
        text = resolved_data.read_text(encoding="utf-8")
        if "preserveRenderedFloatingItemsForObjectPlans" not in text:
            failures.append(
                "ResolvedData.java: object-plan inputs must preserve rendered material before legacy reason/geometry cleanup"
            )
        required_preserve_gate = "if (!preserveRenderedFloatingItemsForObjectPlans) {"
        if required_preserve_gate not in text:
            failures.append(
                "ResolvedData.java: rendered floating item legacy cleanup must be gated off for ObjectPlan inputs"
            )

    resolved_data_reader = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/resolved/ResolvedDataReader.java"
    if resolved_data_reader.exists():
        text = resolved_data_reader.read_text(encoding="utf-8")
        if "hasSiblingObjectPlans(filePath)" not in text:
            failures.append(
                "ResolvedDataReader.java: resolved.json reader must detect sibling object-plans.json"
            )
        if "data.preserveRenderedFloatingItemsForObjectPlans(preserveRenderedMaterialForObjectPlans);" not in text:
            failures.append(
                "ResolvedDataReader.java: object-plan presence must preserve rendered material during read"
            )

    ast_inline_object_builder = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTInlineObjectBuilder.java"
    if ast_inline_object_builder.exists():
        text = ast_inline_object_builder.read_text(encoding="utf-8")
        marker = "private static boolean isHwpxOwnedChildTextFrame"
        if marker in text:
            helper = text[text.index(marker):]
            plan_first_idx = helper.find("if (resolvedData.isHwpxOwnedTextFrame(domId)) return true;")
            stage1_idx = helper.find("if (hasStage1ObjectPlans(resolvedData)) return false;")
            rendered_idx = helper.find("resolvedData.allRenderedFloatingItems()")
            if plan_first_idx < 0 or stage1_idx < 0 or rendered_idx < 0 or not (plan_first_idx < stage1_idx < rendered_idx):
                failures.append(
                    "ASTInlineObjectBuilder.java: inline child TextFrame rendered ownership fallback must be legacy-only after Stage 1 ObjectPlan guard"
                )
        if "private static boolean hasStage1ObjectPlans(ResolvedData resolvedData)" not in text:
            failures.append(
                "ASTInlineObjectBuilder.java: inline child TextFrame ownership must expose a Stage 1 ObjectPlan guard"
            )

    ast_overlay_builder = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTOverlayBuilder.java"
    if ast_overlay_builder.exists():
        text = ast_overlay_builder.read_text(encoding="utf-8")
        marker = "private static boolean isOwnedByTextlessShell"
        if marker in text:
            helper = text[text.index(marker):]
            stage1_idx = helper.find("if (hasStage1ObjectPlans(resolvedData))")
            plan_first_idx = helper.find("return resolvedData.isHwpxOwnedTextFrame(domId);")
            rendered_idx = helper.find("\"hwpx_tf\".equals(rg.textOwner())")
            if stage1_idx < 0 or plan_first_idx < 0 or rendered_idx < 0 or not (stage1_idx < plan_first_idx < rendered_idx):
                failures.append(
                    "ASTOverlayBuilder.java: overlay TextFrame rendered shell ownership fallback must be legacy-only behind Stage 1 ObjectPlan guard"
                )
        if "private static boolean hasStage1ObjectPlans(ResolvedData resolvedData)" not in text:
            failures.append(
                "ASTOverlayBuilder.java: overlay TextFrame ownership must expose a Stage 1 ObjectPlan guard"
            )

    ast_run_converter = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTRunConverter.java"
    if ast_run_converter.exists():
        text = ast_run_converter.read_text(encoding="utf-8")
        marker = "private static boolean isTextFrameOwnedByRenderedTextlessShell"
        if marker in text:
            helper_start = text.index(marker)
            next_marker = text.find("private static boolean isTextFrameOwnedByPlannedTextShell", helper_start)
            helper = text[helper_start:next_marker if next_marker >= 0 else len(text)]
            stage1_idx = helper.find("if (hasStage1ObjectPlans)")
            direct_plan_idx = helper.find("return isTextFrameOwnedByPlannedTextShell(tfDomId, ctx);")
            rendered_idx = helper.find("resolvedData.allRenderedFloatingItems()")
            rendered_plan_lookup_idx = helper.find("findOwnershipPlanForRendered(rg)")
            if stage1_idx < 0 or direct_plan_idx < 0 or rendered_idx < 0 or not (stage1_idx < direct_plan_idx < rendered_idx):
                failures.append(
                    "ASTRunConverter.java: inline TextFrame skip must use direct ObjectPlan scan before rendered fallback"
                )
            if rendered_plan_lookup_idx >= 0:
                failures.append(
                    "ASTRunConverter.java: inline TextFrame skip must not use rendered groups as a Stage 1 ObjectPlan lookup bridge"
                )
        if "private static boolean isTextFrameOwnedByPlannedTextShell" not in text:
            failures.append(
                "ASTRunConverter.java: inline TextFrame skip must expose a direct planned text-shell ownership helper"
            )

    ast_story_converter = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTStoryConverter.java"
    if ast_story_converter.exists():
        text = ast_story_converter.read_text(encoding="utf-8")
        marker = "private static boolean isOwnedByRenderedTextlessShell"
        if marker in text:
            helper_start = text.index(marker)
            next_marker = text.find("private static boolean isTextFrameOwnedByPlannedTextShell", helper_start)
            helper = text[helper_start:next_marker if next_marker >= 0 else len(text)]
            stage1_idx = helper.find("if (hasStage1ObjectPlans)")
            direct_plan_idx = helper.find("return isTextFrameOwnedByPlannedTextShell(tfDomId, ctx);")
            rendered_idx = helper.find("resolvedData.allRenderedFloatingItems()")
            rendered_plan_lookup_idx = helper.find("findOwnershipPlanForRendered(rg)")
            if stage1_idx < 0 or direct_plan_idx < 0 or rendered_idx < 0 or not (stage1_idx < direct_plan_idx < rendered_idx):
                failures.append(
                    "ASTStoryConverter.java: inline TextFrame skip must use direct ObjectPlan scan before rendered fallback"
                )
            if rendered_plan_lookup_idx >= 0:
                failures.append(
                    "ASTStoryConverter.java: inline TextFrame skip must not use rendered groups as a Stage 1 ObjectPlan lookup bridge"
                )
        if "private static boolean isTextFrameOwnedByPlannedTextShell" not in text:
            failures.append(
                "ASTStoryConverter.java: inline TextFrame skip must expose a direct planned text-shell ownership helper"
            )

    ast_table_converter = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTTableConverter.java"
    if ast_table_converter.exists():
        text = ast_table_converter.read_text(encoding="utf-8")
        marker = "private static RenderedGroup findTextHiddenShellForEditableTextFrame"
        if marker in text:
            helper_start = text.index(marker)
            next_marker = text.find("private static RenderedGroup findPlannedTextHiddenShellForInlineTextFrame", helper_start)
            helper = text[helper_start:next_marker if next_marker >= 0 else len(text)]
            stage1_idx = helper.find("if (hasStage1ObjectPlans)")
            direct_plan_idx = helper.find("return findPlannedTextHiddenShellForInlineTextFrame(ctx, resolvedData, tfDomId);")
            rendered_idx = helper.find("resolvedData.allRenderedFloatingItems()")
            rendered_plan_lookup_idx = helper.find("findOwnershipPlanForRendered(rg)")
            if stage1_idx < 0 or direct_plan_idx < 0 or rendered_idx < 0 or not (stage1_idx < direct_plan_idx < rendered_idx):
                failures.append(
                    "ASTTableConverter.java: table-cell inline shell fill must use direct ObjectPlan scan before rendered fallback"
                )
            if rendered_plan_lookup_idx >= 0:
                failures.append(
                    "ASTTableConverter.java: table-cell inline shell fill must not use rendered groups as a Stage 1 ObjectPlan lookup bridge"
                )
        planned_marker = "private static RenderedGroup findPlannedTextHiddenShellForInlineTextFrame"
        table_bridge_marker = "private static kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext tableBridgeContext"
        if planned_marker in text:
            helper_start = text.index(planned_marker)
            next_marker = text.find(table_bridge_marker, helper_start)
            helper = text[helper_start:next_marker if next_marker >= 0 else len(text)]
            if "findOwnershipPlanForRendered(rg)" in helper:
                failures.append(
                    "ASTTableConverter.java: planned table-cell shell lookup must scan ObjectPlans directly, not via rendered groups"
                )
        else:
            failures.append(
                "ASTTableConverter.java: table-cell shell fill must expose a direct planned text-shell lookup helper"
            )

    inline_frame_handler = ROOT / "converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/InlineFrameHandler.java"
    if inline_frame_handler.exists():
        text = inline_frame_handler.read_text(encoding="utf-8")
        marker = "private static String[] editableTextFrameIds"
        if marker in text:
            helper = text[text.index(marker):]
            plan_idx = helper.find("if (plan != null)")
            stage1_idx = helper.find("if (hasStage1ObjectPlans(ctx))")
            rendered_idx = helper.find("return renderedEditableTextFrameIds(shell);")
            if plan_idx < 0 or stage1_idx < 0 or rendered_idx < 0 or not (plan_idx < stage1_idx < rendered_idx):
                failures.append(
                    "InlineFrameHandler.java: editable TextFrame id rendered fallback must be after ObjectPlan and Stage 1 guards"
                )
        cover_marker = "private static boolean isCoveredByInlineTextShellSourceBundle"
        if cover_marker in text:
            cover = text[text.index(cover_marker):]
            stage1 = "if (hasStage1ObjectPlans(ctx)) {\n            return plannedInlineTextShellCoversSourceBundle(ctx, sourceObjectId);"
            if stage1 not in cover or "private static boolean plannedInlineTextShellCoversSourceBundle" not in text:
                failures.append(
                    "InlineFrameHandler.java: inline text-shell source-bundle coverage must use ObjectPlans before rendered textOwner fallback"
                )

    planner_bundles = ROOT / "scripts/indd/planner_bundles.jsx"
    object_plans = ROOT / "scripts/indd/object_plans.jsx"
    if planner_bundles.exists() and object_plans.exists():
        planner_text = planner_bundles.read_text(encoding="utf-8")
        object_plan_text = object_plans.read_text(encoding="utf-8")
        required_root_export_contract = [
            "pass.complex_graphic_frames",
            "SHELL_SLOT",
            "background_shell_slot",
            "complex_graphic_source_set",
            "exportTargetObjectId",
            "clusterHasEditableText === true ||",
            "clusterHasTextFrame === true",
        ]
        if "_plannerBundleAllowsRootExportVisibleFragmentContract" not in planner_text:
            failures.append(
                "planner_bundles.jsx: complex textless background shell root-export fragment contract is missing"
            )
        else:
            for needle in required_root_export_contract:
                if needle not in planner_text:
                    failures.append(
                        f"planner_bundles.jsx: root-export visible fragment contract missing guard: {needle}"
                    )
                    break
        if "_objectPlanAllowsRootExportVisibleFragmentContract" not in object_plan_text:
            failures.append(
                "object_plans.jsx: ObjectPlan import-ready contract must accept complex textless background shell root export"
            )
        else:
            for needle in required_root_export_contract:
                if needle not in object_plan_text:
                    failures.append(
                        f"object_plans.jsx: root-export visible fragment contract missing guard: {needle}"
                    )
                    break

    execution_candidates = ROOT / "scripts/indd/execution_candidates.jsx"
    if execution_candidates.exists():
        text = execution_candidates.read_text(encoding="utf-8")
        required_bridge_fields = [
            "candidate.mode = objectPlan.mode ||",
            "candidate.clusterRelation = objectPlan.clusterRelation ||",
            "candidate.sourceRootObjectIds = _sortedNumericIds(objectPlan.sourceRootObjectIds);",
            "candidate.clusterSourceObjectIds = _sortedNumericIds(objectPlan.clusterSourceObjectIds);",
            "candidate.omittedClusterSourceObjectIds = _sortedNumericIds(objectPlan.omittedClusterSourceObjectIds);",
            "\"sourceRootObjectIds\"",
            "\"clusterSourceObjectIds\"",
            "\"omittedClusterSourceObjectIds\"",
            "\"clusterRelation\"",
        ]
        for needle in required_bridge_fields:
            if needle not in text:
                failures.append(
                    f"execution_candidates.jsx: ObjectPlan execution bridge must preserve Stage 1 slot-only contract field: {needle}"
                )
                break

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1

    print("OK: source ownership policy guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
