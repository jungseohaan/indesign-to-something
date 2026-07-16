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
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        List<ASTParagraph> cellFlow = StoryLoader.astParagraphsForCell(ctx, idmlTable, idmlCell, null);
        if (hasMeaningfulFlowContent(cellFlow)) {
            return cellFlow;
        }
        List<ASTParagraph> directNestedTableFlow = buildDirectNestedTableFlow(ctx, idmlCell);
        if (directNestedTableFlow != null && !directNestedTableFlow.isEmpty()) {
            return directNestedTableFlow;
        }
        List<ASTParagraph> nestedTextFrameFlow = buildNestedTextFrameStoryFlow(ctx, idmlCell);
        if (nestedTextFrameFlow != null && !nestedTextFrameFlow.isEmpty()) {
            return nestedTextFrameFlow;
        }
        List<ASTParagraph> inlineShellFlow = buildOwnedInlineShellFlow(ctx, idmlTable, idmlCell);
        return inlineShellFlow != null ? inlineShellFlow : new ArrayList<ASTParagraph>();
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

    private static List<ASTParagraph> buildOwnedInlineShellFlow(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null) {
            return new ArrayList<ASTParagraph>();
        }
        List<ASTParagraph> paragraphs = new ArrayList<>();

        appendPlannedInlineObjectsFromResolvedTableCell(ctx, idmlTable, idmlCell, paragraphs);
        appendPlannedInlineTextShellsFromCellAnchors(ctx, idmlCell, paragraphs);

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
            List<ASTParagraph> paragraphs) {
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
            if (!cellContainsInlineAnchor(idmlCell, anchorId)) continue;
            if (!InlineFrameHandler.shouldKeepAnchoredInlineByOwnershipPlan(ctx, anchorId)) continue;
            List<ASTInlineItem> plannedItems =
                    InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, anchorId, null, null);
            if (plannedItems != null) {
                InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, anchorId, paragraph);
                appendInlineItemsKeepingObjectsInline(paragraph, plannedItems);
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
            List<ASTParagraph> paragraphs) {
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
                    if (paragraph == null) {
                        paragraph = new ASTParagraph();
                        if (idmlParagraph.appliedParagraphStyle() != null) {
                            paragraph.paragraphStyleRef(idmlParagraph.appliedParagraphStyle());
                        }
                    }
                    List<ASTInlineItem> plannedItems =
                            InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, domId, null, null);
                    if (plannedItems != null) {
                        InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, domId, paragraph);
                        appendInlineItemsKeepingObjectsInline(paragraph, plannedItems);
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
        if (paragraph == null || items == null) return;
        for (ASTInlineItem item : items) {
            if (item == null) continue;
            if (item instanceof ASTInlineObject) {
                ((ASTInlineObject) item).keepInline(true);
            }
            paragraph.addItem(item);
        }
    }

    private static List<String> inlineGraphicIdsInRunOrder(IDMLCharacterRun run) {
        List<String> ids = new ArrayList<>();
        if (run == null) return ids;
        if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) {
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                if (anchor == null || anchor.type() != IDMLCharacterRun.InlineAnchorType.GRAPHIC) continue;
                if (run.inlineGraphics() == null || anchor.index() < 0
                        || anchor.index() >= run.inlineGraphics().size()) {
                    continue;
                }
                IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
                if (graphic != null && graphic.selfId() != null) ids.add(graphic.selfId());
            }
            return ids;
        }
        if (run.inlineGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic graphic : run.inlineGraphics()) {
                if (graphic != null && graphic.selfId() != null) ids.add(graphic.selfId());
            }
        }
        return ids;
    }

    private static int parseInlineObjectDomId(String id) {
        if (id == null || id.isEmpty()) return -1;
        String s = id;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        if (s.startsWith("u") || s.startsWith("U")) s = s.substring(1);
        int end = 0;
        while (end < s.length()) {
            char c = s.charAt(end);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) break;
            end++;
        }
        if (end == 0) return -1;
        try {
            return Integer.parseInt(s.substring(0, end), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
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

    private static boolean isStoryOwnedByInlineTextShellPlan(ResolvedBuildContext ctx, String storyRef) {
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
