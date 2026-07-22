package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds source story flow before any container placement decision.
 *
 * <p>The container layer (table cell, text box, page frame) should receive a
 * ready paragraph flow and only decide where to place it. It must not recover
 * missing inline objects by text matching after placement.</p>
 */
public final class StoryFlowAssembler {
    private StoryFlowAssembler() {
    }

    public static List<ASTParagraph> buildCellFlow(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell) {
        return buildCellFlow(ctx, null, idmlCell);
    }

    public static List<ASTParagraph> buildCellFlow(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            IDMLTableCell idmlCell) {
        if (isResolvedInlineAnchorOnlyCell(ctx, idmlTable, idmlCell)) {
            List<ASTParagraph> inlineShellFlow = buildOwnedInlineShellFlow(ctx, idmlTable, idmlCell);
            if (inlineShellFlow != null && !inlineShellFlow.isEmpty()) {
                return inlineShellFlow;
            }
        }
        List<ASTParagraph> cellFlow = StoryLoader.astParagraphsForCell(ctx, idmlTable, idmlCell, null);
        if (hasMeaningfulFlowContent(cellFlow)) {
            List<ASTParagraph> nestedTextFrameTableFlow = buildNestedTextFrameTableFlow(ctx, idmlCell);
            if (nestedTextFrameTableFlow != null && !nestedTextFrameTableFlow.isEmpty()) {
                if (hasVisibleTextFlowContent(cellFlow)) {
                    cellFlow.addAll(nestedTextFrameTableFlow);
                    return cellFlow;
                }
                return nestedTextFrameTableFlow;
            }
            return cellFlow;
        }
        List<ASTParagraph> directNestedTableFlow = buildDirectNestedTableFlow(ctx, idmlCell);
        if (directNestedTableFlow != null && !directNestedTableFlow.isEmpty()) {
            return directNestedTableFlow;
        }
        List<ASTParagraph> nestedTextFrameTableFlow = buildNestedTextFrameTableFlow(ctx, idmlCell);
        if (nestedTextFrameTableFlow != null && !nestedTextFrameTableFlow.isEmpty()) {
            return nestedTextFrameTableFlow;
        }
        List<ASTParagraph> nestedTextFrameFlow = buildNestedTextFrameStoryFlow(ctx, idmlCell);
        if (nestedTextFrameFlow != null && !nestedTextFrameFlow.isEmpty()) {
            return nestedTextFrameFlow;
        }
        List<ASTParagraph> inlineShellFlow = buildOwnedInlineShellFlow(ctx, idmlTable, idmlCell);
        return inlineShellFlow != null ? inlineShellFlow : new ArrayList<ASTParagraph>();
    }

    private static boolean isResolvedInlineAnchorOnlyCell(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null) return false;
        ResolvedTable resolvedTable = idmlTable != null
                ? ctx.resolvedData.getTableByIdOrSourceId(idmlTable.selfId())
                : null;
        if (resolvedTable == null && idmlCell.selfId() != null) {
            resolvedTable = ctx.resolvedData.getTableByIdOrSourceId(idmlCell.selfId());
        }
        if (resolvedTable == null) return false;
        ResolvedTable.Cell resolvedCell = resolvedTable.cellAt(idmlCell.rowIndex(), idmlCell.columnIndex());
        return resolvedCell != null
                && !resolvedCell.hasTextRuns()
                && resolvedCell.inlineAnchorIds() != null
                && !resolvedCell.inlineAnchorIds().isEmpty();
    }

    private static boolean hasMeaningfulFlowContent(List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return false;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null) continue;
            if (paragraph.inlineTable() != null) return true;
            if (paragraph.items() == null || paragraph.items().isEmpty()) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item == null) continue;
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (hasMeaningfulText(text)) return true;
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean hasMeaningfulText(String text) {
        if (text == null || text.isEmpty()) return false;
        String normalized = text
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .trim();
        return !normalized.isEmpty();
    }

    private static boolean hasVisibleTextFlowContent(List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return false;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun
                        && hasMeaningfulText(((ASTTextRun) item).text())) {
                    return true;
                }
                if (item instanceof ASTInlineObject
                        && hasVisibleTextFlowContent(((ASTInlineObject) item).paragraphs())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ASTParagraph> buildDirectNestedTableFlow(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || ctx.styleResolver == null
                || idmlCell == null || !idmlCell.hasDirectNestedTables()) {
            return null;
        }
        List<ASTParagraph> paragraphs = new ArrayList<>();
        for (IDMLTable nestedTable : idmlCell.directNestedTables()) {
            if (nestedTable == null) continue;
            ASTTable nestedAst = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter.convertTableSimple(
                    nestedTable,
                    0, 0, 0,
                    null, null, null,
                    ctx.resolvedData,
                    ctx.styleResolver,
                    (table, nestedCell) -> buildCellFlow(ctx, table, nestedCell));
            if (nestedAst == null) continue;
            ASTParagraph paragraph = new ASTParagraph();
            paragraph.inlineTable(nestedAst);
            paragraphs.add(paragraph);
        }
        return paragraphs;
    }

    private static List<ASTParagraph> buildNestedTextFrameTableFlow(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || ctx.styleResolver == null
                || ctx.loadIDMLStory == null || idmlCell == null
                || idmlCell.textFrameStoryRefs() == null
                || idmlCell.textFrameStoryRefs().isEmpty()) {
            return null;
        }
        List<ASTParagraph> paragraphs = new ArrayList<>();
        for (String storyRef : orderedTextFrameStoryRefsForCellFlow(ctx, idmlCell)) {
            if (storyRef == null) continue;
            if (isStoryOwnedByInlineTextShellPlan(ctx, storyRef)) {
                continue;
            }
            if (!shouldCellConsumeNestedStoryRef(ctx, idmlCell, storyRef)
                    && isStoryOwnedByPlacedTextFrame(ctx, storyRef)) {
                continue;
            }
            IDMLStory nestedStory = ctx.loadIDMLStory.apply(storyRef);
            if (nestedStory == null || !nestedStory.hasTables() || nestedStory.tables() == null) continue;
            for (IDMLTable nestedTable : nestedStory.tables()) {
                if (nestedTable == null) continue;
                ASTTable nestedAst = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter.convertTableSimple(
                        nestedTable,
                        0, 0, 0,
                        null, null, null,
                        ctx.resolvedData,
                        ctx.styleResolver,
                        (table, nestedCell) -> buildCellFlow(ctx, table, nestedCell));
                if (nestedAst == null) continue;
                ASTParagraph paragraph = new ASTParagraph();
                paragraph.inlineTable(nestedAst);
                paragraphs.add(paragraph);
            }
        }
        return paragraphs.isEmpty() ? null : paragraphs;
    }

    private static List<ASTParagraph> buildOwnedInlineShellFlow(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null) {
            return new ArrayList<ASTParagraph>();
        }
        List<ASTParagraph> paragraphs = new ArrayList<>();
        Set<Integer> appendedAnchorIds = new LinkedHashSet<>();
        Set<String> consumedOwnershipKeys = new LinkedHashSet<>();

        appendPlannedInlineObjectsFromResolvedTableCell(
                ctx, idmlTable, idmlCell, paragraphs, appendedAnchorIds, consumedOwnershipKeys);
        appendPlannedInlineTextShellsFromCellAnchors(
                ctx, idmlCell, paragraphs, appendedAnchorIds, consumedOwnershipKeys);

        if (idmlCell.textFrameStoryRefs() == null || idmlCell.textFrameStoryRefs().isEmpty()) {
            return paragraphs;
        }
        for (String storyRef : orderedTextFrameStoryRefsForCellFlow(ctx, idmlCell)) {
            if (storyRef == null) continue;
            String storyId = toDecimalStoryId(storyRef);
            List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
            if ((frames == null || frames.isEmpty()) && !storyRef.equals(storyId)) {
                frames = ctx.resolvedData.getTextFramesForStory(storyRef);
            }
            if (frames == null || frames.isEmpty()) continue;
            for (ResolvedTextFrame tf : frames) {
                int tfDomId = parseDomId(tf);
                if (tfDomId < 0) continue;
                if (!consumeInlineOwnershipKeyForOwnedTextFrame(ctx, tfDomId, consumedOwnershipKeys)) continue;
                ASTInlineObject inlineShell =
                        InlineFrameHandler.loadPlannedInlineTextShellForOwnedTextFrame(ctx, tfDomId);
                if (inlineShell == null) continue;
                ASTParagraph paragraph = new ASTParagraph();
                paragraph.addItem(inlineShell);
                paragraphs.add(paragraph);
            }
        }
        return paragraphs;
    }

    private static void appendPlannedInlineObjectsFromResolvedTableCell(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            IDMLTableCell idmlCell,
            List<ASTParagraph> paragraphs,
            Set<Integer> appendedAnchorIds,
            Set<String> consumedOwnershipKeys) {
        if (ctx == null || ctx.resolvedData == null
                || idmlCell == null || paragraphs == null) {
            return;
        }
        ResolvedTable resolvedTable = idmlTable != null
                ? ctx.resolvedData.getTableByIdOrSourceId(idmlTable.selfId())
                : null;
        if (resolvedTable == null && idmlCell.selfId() != null) {
            resolvedTable = ctx.resolvedData.getTableByIdOrSourceId(idmlCell.selfId());
        }
        if (resolvedTable == null) return;
        ResolvedTable.Cell resolvedCell = resolvedTable.cellAt(idmlCell.rowIndex(), idmlCell.columnIndex());
        if (resolvedCell == null || resolvedCell.hasTextRuns()
                || resolvedCell.inlineAnchorIds() == null
                || resolvedCell.inlineAnchorIds().isEmpty()) {
            return;
        }
        ASTParagraph paragraph = new ASTParagraph();
        for (Integer anchorId : resolvedCell.inlineAnchorIds()) {
            if (anchorId == null || anchorId < 0) continue;
            if (appendedAnchorIds != null && appendedAnchorIds.contains(anchorId)) continue;
            if (!cellContainsInlineAnchor(idmlCell, anchorId)) continue;
            List<ASTInlineItem> plannedItems =
                    InlineFrameHandler.loadPlannedCellInlineCarrierItems(ctx, anchorId);
            if ((plannedItems == null || plannedItems.isEmpty())
                    && InlineFrameHandler.shouldKeepAnchoredInlineByOwnershipPlan(ctx, anchorId)) {
                plannedItems = InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, anchorId, null, null);
            }
            if (plannedItems != null && !plannedItems.isEmpty()) {
                InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, anchorId, paragraph);
                int added = appendInlineItemsKeepingObjectsInline(paragraph, plannedItems, ctx, consumedOwnershipKeys);
                if (added > 0) {
                    consumeInlineTextShellOwnerKeysForAnchor(ctx, anchorId, consumedOwnershipKeys);
                    if (appendedAnchorIds != null) appendedAnchorIds.add(anchorId);
                }
            }
        }
        if (paragraph.items() != null && !paragraph.items().isEmpty()) {
            paragraphs.add(paragraph);
        }
    }

    private static boolean cellContainsInlineAnchor(IDMLTableCell idmlCell, int anchorId) {
        if (idmlCell == null || idmlCell.paragraphs() == null || anchorId < 0) return false;
        for (IDMLParagraph paragraph : idmlCell.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (containsInlineAnchorDomId(run, anchorId)) return true;
                if (containsInlineGraphicDomId(run.inlineGraphics(), anchorId)) return true;
                if (containsInlineFrameDomId(run.inlineFrames(), anchorId)) return true;
                for (String inlineId : inlineGraphicIdsInRunOrder(run)) {
                    if (parseInlineObjectDomId(inlineId) == anchorId) return true;
                }
            }
        }
        return false;
    }

    private static void appendPlannedInlineTextShellsFromCellAnchors(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell,
            List<ASTParagraph> paragraphs,
            Set<Integer> appendedAnchorIds,
            Set<String> consumedOwnershipKeys) {
        if (ctx == null || idmlCell == null || idmlCell.paragraphs() == null || paragraphs == null) {
            return;
        }
        for (IDMLParagraph idmlParagraph : idmlCell.paragraphs()) {
            ASTParagraph paragraph = null;
            if (idmlParagraph == null || idmlParagraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : idmlParagraph.characterRuns()) {
                if (run == null) continue;
                List<String> inlineIds = inlineGraphicIdsInRunOrder(run);
                for (String inlineId : inlineIds) {
                    int domId = parseInlineObjectDomId(inlineId);
                    if (domId < 0) continue;
                    if (appendedAnchorIds != null && appendedAnchorIds.contains(domId)) continue;
                    List<ASTInlineItem> plannedItems =
                            InlineFrameHandler.loadPlannedCellInlineCarrierItems(ctx, domId);
                    if ((plannedItems == null || plannedItems.isEmpty())
                            && InlineFrameHandler.shouldKeepAnchoredInlineByOwnershipPlan(ctx, domId)) {
                        plannedItems = InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, domId, null, null);
                    }
                    if (plannedItems != null && !plannedItems.isEmpty()) {
                        if (paragraph == null) {
                            paragraph = new ASTParagraph();
                            if (idmlParagraph.appliedParagraphStyle() != null) {
                                paragraph.paragraphStyleRef(idmlParagraph.appliedParagraphStyle());
                            }
                        }
                        InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, domId, paragraph);
                        int added = appendInlineItemsKeepingObjectsInline(paragraph, plannedItems, ctx, consumedOwnershipKeys);
                        if (added > 0) {
                            consumeInlineTextShellOwnerKeysForAnchor(ctx, domId, consumedOwnershipKeys);
                            if (appendedAnchorIds != null) appendedAnchorIds.add(domId);
                        }
                    }
                }
            }
            if (paragraph != null && paragraph.items() != null && !paragraph.items().isEmpty()) {
                paragraphs.add(paragraph);
            }
        }
    }

    private static void appendInlineItemsKeepingObjectsInline(
            ASTParagraph paragraph,
            List<ASTInlineItem> items) {
        appendInlineItemsKeepingObjectsInline(paragraph, items, null, null);
    }

    private static int appendInlineItemsKeepingObjectsInline(
            ASTParagraph paragraph,
            List<ASTInlineItem> items,
            ResolvedBuildContext ctx,
            Set<String> consumedOwnershipKeys) {
        if (paragraph == null || items == null) return 0;
        int added = 0;
        for (ASTInlineItem item : items) {
            if (item == null) continue;
            if (item instanceof ASTInlineObject) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (!consumeInlineOwnershipKey(ctx, obj, consumedOwnershipKeys)) {
                    continue;
                }
                obj.keepInline(true);
            }
            paragraph.addItem(item);
            added++;
        }
        return added;
    }

    private static boolean consumeInlineOwnershipKey(
            ResolvedBuildContext ctx,
            ASTInlineObject obj,
            Set<String> consumedOwnershipKeys) {
        if (consumedOwnershipKeys == null) return true;
        String key = inlineOwnershipExecutionKey(ctx, obj);
        if (key == null || key.isEmpty()) return true;
        if (consumedOwnershipKeys.contains(key)) return false;
        consumedOwnershipKeys.add(key);
        return true;
    }

    private static boolean consumeInlineOwnershipKeyForOwnedTextFrame(
            ResolvedBuildContext ctx,
            int textFrameDomId,
            Set<String> consumedOwnershipKeys) {
        if (consumedOwnershipKeys == null || ctx == null || textFrameDomId < 0) return true;
        String key = inlineOwnershipExecutionKeyForOwnedTextFrame(ctx, textFrameDomId);
        if (key == null || key.isEmpty()) return true;
        if (consumedOwnershipKeys.contains(key)) return false;
        consumedOwnershipKeys.add(key);
        return true;
    }

    private static void consumeInlineTextShellOwnerKeysForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            Set<String> consumedOwnershipKeys) {
        if (ctx == null || anchoredObjectId < 0 || consumedOwnershipKeys == null) return;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            if (!containsInt(plan.ownedTextFrameIds, anchoredObjectId)
                    && !containsInt(plan.sourceObjectIds, anchoredObjectId)
                    && !containsInt(plan.visualSourceObjectIds, anchoredObjectId)
                    && !containsInt(plan.styleSourceObjectIds, anchoredObjectId)) {
                continue;
            }
            String key = inlineOwnershipExecutionKey(plan);
            if (key != null && !key.isEmpty()) {
                consumedOwnershipKeys.add(key);
            }
        }
    }

    private static String inlineOwnershipExecutionKey(
            ResolvedBuildContext ctx,
            ASTInlineObject obj) {
        if (ctx == null || obj == null) return null;
        int domId = parseInlineObjectDomId(obj.sourceId());
        if (domId < 0) return null;
        ObjectPlan plan = ctx.findOwnershipPlanForDomId(domId);
        if (plan == null) return null;
        if (plan.objectPlanId != null && !plan.objectPlanId.isEmpty()) {
            return "objectPlan:" + plan.objectPlanId;
        }
        String bundle = plan.sourceBundleKey != null && !plan.sourceBundleKey.isEmpty()
                ? plan.sourceBundleKey
                : "dom:" + plan.domId;
        return "bundle:" + bundle
                + ":slot:" + safeString(plan.slotRole)
                + ":visual:" + plan.visualAction
                + ":text:" + plan.textAction
                + ":placement:" + plan.placement;
    }

    private static String inlineOwnershipExecutionKeyForOwnedTextFrame(
            ResolvedBuildContext ctx,
            int textFrameDomId) {
        if (ctx == null || textFrameDomId < 0) return null;
        for (ObjectPlan plan : ctx.ownershipPlansForOwnedTextFrame(textFrameDomId)) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!containsInt(plan.ownedTextFrameIds, textFrameDomId)) continue;
            return inlineOwnershipExecutionKey(plan);
        }
        return null;
    }

    private static String inlineOwnershipExecutionKey(ObjectPlan plan) {
        if (plan == null) return null;
        if (plan.objectPlanId != null && !plan.objectPlanId.isEmpty()) {
            return "objectPlan:" + plan.objectPlanId;
        }
        String bundle = plan.sourceBundleKey != null && !plan.sourceBundleKey.isEmpty()
                ? plan.sourceBundleKey
                : "dom:" + plan.domId;
        return "bundle:" + bundle
                + ":slot:" + safeString(plan.slotRole)
                + ":visual:" + plan.visualAction
                + ":text:" + plan.textAction
                + ":placement:" + plan.placement;
    }

    private static boolean containsInt(int[] values, int target) {
        if (values == null || values.length == 0) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static List<String> inlineGraphicIdsInRunOrder(IDMLCharacterRun run) {
        List<String> ids = new ArrayList<>();
        if (run == null) return ids;
        if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) {
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                if (anchor == null) continue;
                if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
                    if (run.inlineGraphics() == null || anchor.index() < 0
                            || anchor.index() >= run.inlineGraphics().size()) {
                        continue;
                    }
                    IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
                    if (graphic != null && graphic.selfId() != null) ids.add(graphic.selfId());
                } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
                    if (run.inlineFrames() == null || anchor.index() < 0
                            || anchor.index() >= run.inlineFrames().size()) {
                        continue;
                    }
                    kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame frame =
                            run.inlineFrames().get(anchor.index());
                    if (frame != null && frame.selfId() != null) ids.add(frame.selfId());
                }
            }
            return ids;
        }
        if (run.inlineGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic graphic : run.inlineGraphics()) {
                if (graphic != null && graphic.selfId() != null) ids.add(graphic.selfId());
            }
        }
        if (run.inlineFrames() != null) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame frame : run.inlineFrames()) {
                if (frame != null && frame.selfId() != null) ids.add(frame.selfId());
            }
        }
        return ids;
    }

    private static int parseInlineObjectDomId(String id) {
        if (id == null || id.isEmpty()) return -1;
        String s = id;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        boolean hexId = s.startsWith("u") || s.startsWith("U");
        if (hexId) s = s.substring(1);
        int end = 0;
        while (end < s.length()) {
            char c = s.charAt(end);
            boolean valid = hexId
                    ? ((c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))
                    : (c >= '0' && c <= '9');
            if (!valid) break;
            end++;
        }
        if (end == 0) return -1;
        try {
            return Integer.parseInt(s.substring(0, end), hexId ? 16 : 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean containsInlineAnchorDomId(IDMLCharacterRun run, int anchorId) {
        if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) return false;
        for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
            if (anchor == null) continue;
            if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
                List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame> frames = run.inlineFrames();
                int index = anchor.index();
                if (frames != null && index >= 0 && index < frames.size()
                        && idMatches(frames.get(index).selfId(), anchorId)) {
                    return true;
                }
            } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
                List<IDMLCharacterRun.InlineGraphic> graphics = run.inlineGraphics();
                int index = anchor.index();
                if (graphics != null && index >= 0 && index < graphics.size()
                        && inlineGraphicContainsDomId(graphics.get(index), anchorId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsInlineGraphicDomId(
            List<IDMLCharacterRun.InlineGraphic> graphics,
            int anchorId) {
        if (graphics == null || graphics.isEmpty()) return false;
        for (IDMLCharacterRun.InlineGraphic graphic : graphics) {
            if (inlineGraphicContainsDomId(graphic, anchorId)) return true;
        }
        return false;
    }

    private static boolean inlineGraphicContainsDomId(
            IDMLCharacterRun.InlineGraphic graphic,
            int anchorId) {
        if (graphic == null) return false;
        if (idMatches(graphic.selfId(), anchorId)) return true;
        if (containsInlineGraphicDomId(graphic.childGraphics(), anchorId)) return true;
        return containsInlineFrameDomId(graphic.childTextFrames(), anchorId);
    }

    private static boolean containsInlineFrameDomId(
            List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame> frames,
            int anchorId) {
        if (frames == null || frames.isEmpty()) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame frame : frames) {
            if (frame == null) continue;
            if (idMatches(frame.selfId(), anchorId)) return true;
        }
        return false;
    }

    private static boolean idMatches(String sourceId, int domId) {
        return parseInlineObjectDomId(sourceId) == domId;
    }

    private static List<ASTParagraph> buildNestedTextFrameStoryFlow(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null
                || idmlCell.textFrameStoryRefs() == null
                || idmlCell.textFrameStoryRefs().isEmpty()) {
            return null;
        }
        List<ASTParagraph> merged = new ArrayList<>();
        for (String storyRef : orderedTextFrameStoryRefsForCellFlow(ctx, idmlCell)) {
            if (storyRef == null) continue;
            if (isStoryOwnedByInlineTextShellPlan(ctx, storyRef)) {
                continue;
            }
            if (!shouldCellConsumeNestedStoryRef(ctx, idmlCell, storyRef)
                    && isStoryOwnedByPlacedTextFrame(ctx, storyRef)) {
                continue;
            }
            IDMLStory idmlStory = ctx.loadIDMLStory != null ? ctx.loadIDMLStory.apply(storyRef) : null;
            if (idmlStory != null && idmlStory.hasTables()) continue;
            ResolvedStory story = ctx.resolvedData.getStory(toDecimalStoryId(storyRef));
            if (story == null) {
                story = ctx.resolvedData.getStory(storyRef);
            }
            if (!hasAuthoritativeResolvedStructure(story)) continue;
            List<ASTParagraph> paragraphs = StoryConverter.convertStoryParagraphs(ctx, story);
            if (paragraphs != null && !paragraphs.isEmpty()) {
                merged.addAll(paragraphs);
            }
        }
        return merged.isEmpty() ? null : merged;
    }

    private static List<String> orderedTextFrameStoryRefsForCellFlow(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell) {
        List<String> refs = new ArrayList<>();
        if (idmlCell == null || idmlCell.textFrameStoryRefs() == null) return refs;
        refs.addAll(idmlCell.textFrameStoryRefs());
        if (ctx == null || ctx.resolvedData == null || refs.size() < 2) return refs;
        refs.sort(new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                ResolvedTextFrame fa = firstInlineFrameForStory(ctx, a);
                ResolvedTextFrame fb = firstInlineFrameForStory(ctx, b);
                if (fa == null || fb == null) return 0;
                return compareInlineFrameFlowPosition(fa, fb);
            }
        });
        return refs;
    }

    private static ResolvedTextFrame firstInlineFrameForStory(ResolvedBuildContext ctx, String storyRef) {
        if (ctx == null || ctx.resolvedData == null || storyRef == null) return null;
        String storyId = toDecimalStoryId(storyRef);
        List<ResolvedTextFrame> frames = storyId != null
                ? ctx.resolvedData.getTextFramesForStory(storyId)
                : null;
        if ((frames == null || frames.isEmpty()) && !storyRef.equals(storyId)) {
            frames = ctx.resolvedData.getTextFramesForStory(storyRef);
        }
        if (frames == null || frames.isEmpty()) return null;
        ResolvedTextFrame best = null;
        for (ResolvedTextFrame frame : frames) {
            if (frame == null || !frame.isInline() || !validBounds(frame.pageRelativeBounds())) continue;
            if (best == null || compareInlineFrameFlowPosition(frame, best) < 0) {
                best = frame;
            }
        }
        return best;
    }

    private static int compareInlineFrameFlowPosition(ResolvedTextFrame a, ResolvedTextFrame b) {
        int page = Integer.compare(a.pageIndex(), b.pageIndex());
        if (page != 0) return page;
        double[] ab = a.pageRelativeBounds();
        double[] bb = b.pageRelativeBounds();
        int top = Double.compare(ab[0], bb[0]);
        if (top != 0) return top;
        int left = Double.compare(ab[1], bb[1]);
        if (left != 0) return left;
        int z = Integer.compare(a.zOrder(), b.zOrder());
        if (z != 0) return z;
        return safeString(a.id()).compareTo(safeString(b.id()));
    }

    private static boolean validBounds(double[] bounds) {
        return bounds != null
                && bounds.length >= 4
                && Double.isFinite(bounds[0])
                && Double.isFinite(bounds[1])
                && Double.isFinite(bounds[2])
                && Double.isFinite(bounds[3]);
    }

    private static String safeString(String value) {
        return value != null ? value : "";
    }

    public static boolean shouldCellConsumeNestedStoryRef(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell,
            String storyRef) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null
                || storyRef == null
                || idmlCell.textFrameStoryRefs() == null
                || idmlCell.textFrameStoryRefs().isEmpty()) {
            return false;
        }
        boolean referencedByCell = false;
        for (String ref : idmlCell.textFrameStoryRefs()) {
            if (ref == null) continue;
            if (ref.equals(storyRef) || toDecimalStoryId(ref).equals(toDecimalStoryId(storyRef))) {
                referencedByCell = true;
                break;
            }
        }
        if (!referencedByCell) return false;

        String storyId = toDecimalStoryId(storyRef);
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if ((frames == null || frames.isEmpty()) && !storyRef.equals(storyId)) {
            frames = ctx.resolvedData.getTextFramesForStory(storyRef);
        }
        if (frames == null || frames.isEmpty()) return false;

        boolean sawInlineFrame = false;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null) continue;
            if (!tf.isInline()) {
                return false;
            }
            sawInlineFrame = true;
        }
        return sawInlineFrame;
    }

    public static boolean isStoryOwnedByInlineTextShellPlan(ResolvedBuildContext ctx, String storyRef) {
        if (ctx == null || ctx.resolvedData == null || storyRef == null) return false;
        String storyId = toDecimalStoryId(storyRef);
        if (storyId == null) return false;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames == null || frames.isEmpty()) return false;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.id() == null) continue;
            try {
                int domId = Integer.parseInt(tf.id());
                if (ctx.isTextFrameOwnedByTextShellPlan(domId)
                        && ctx.ownershipPlanPlacesInlineHwpxText(domId)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Non-DOM ids cannot be checked against Stage 1 ownership plans.
            }
        }
        return false;
    }

    public static boolean isStoryOwnedByPlacedTextFrame(ResolvedBuildContext ctx, String storyRef) {
        if (ctx == null || ctx.resolvedData == null || storyRef == null) return false;
        String storyId = toDecimalStoryId(storyRef);
        if (storyId == null) return false;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames == null || frames.isEmpty()) return false;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.id() == null) continue;
            try {
                int domId = Integer.parseInt(tf.id());
                if (ctx.isTextDisposed(domId, FrameDisposition.TEXT_BLOCK_PLACED)) return true;
                if (ctx.isTextFrameOwnedByTextShellPlan(domId)) return true;
            } catch (NumberFormatException ignored) {
                // Non-DOM ids cannot be checked against text-frame ownership disposition.
            }
        }
        return false;
    }

    private static int parseDomId(ResolvedTextFrame tf) {
        if (tf == null || tf.id() == null) return -1;
        try {
            return Integer.parseInt(tf.id());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean hasAuthoritativeResolvedStructure(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        int nonEmptyParagraphs = 0;
        for (ResolvedParagraph para : story.paragraphs()) {
            if (para == null || para.runs() == null) continue;
            int visibleRuns = 0;
            for (ResolvedRun run : para.runs()) {
                if (run == null || run.isInlineAnchor()) continue;
                String text = run.text();
                if (text != null && !text.trim().isEmpty()) visibleRuns++;
            }
            if (visibleRuns > 0) nonEmptyParagraphs++;
            if (visibleRuns > 1) return true;
        }
        return nonEmptyParagraphs > 1;
    }

    static String toDecimalStoryId(String storyRef) {
        if (storyRef == null || storyRef.isEmpty()) return null;
        String s = storyRef;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        if (s.startsWith("Story_")) s = s.substring("Story_".length());
        if (s.startsWith("u") && s.length() > 1) {
            try {
                return String.valueOf(Integer.parseInt(s.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return storyRef;
            }
        }
        return storyRef;
    }
}
