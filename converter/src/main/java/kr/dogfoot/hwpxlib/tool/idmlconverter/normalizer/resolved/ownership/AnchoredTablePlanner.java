package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 1 ownership for tables anchored inside a text story.
 *
 * <p>InDesign can express a visible table as a table marker in the body story
 * whose cell owns an inline TextFrame containing the real nested table. That
 * source concept is "table follows this paragraph", not "independent page
 * table". This planner records that relationship before legacy phases run.</p>
 */
public final class AnchoredTablePlanner {
    private AnchoredTablePlanner() {}

    public static void plan(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null || ctx.loadIDMLStory == null) return;
        Set<String> plannedStories = new HashSet<>();
        Set<String> plannedTables = new HashSet<>();
        for (ResolvedTextFrame seedTf : ctx.resolvedData.textFrames()) {
            if (seedTf == null || seedTf.storyId() == null) continue;
            if (seedTf.isInline() || seedTf.onHiddenLayer() || seedTf.nonprinting()) continue;
            if (!plannedStories.add(seedTf.storyId())) continue;

            IDMLStory ownerStory = ctx.loadIDMLStory.apply(seedTf.storyId());
            if (ownerStory == null || !ownerStory.hasTables()) continue;
            List<ResolvedTextFrame> ownerFrames = visibleNonInlineFramesForStory(ctx, seedTf.storyId());
            List<IDMLTable> wrapperTables = storyAnchoredTables(ownerStory);
            for (int tableOrdinal = 0; tableOrdinal < wrapperTables.size(); tableOrdinal++) {
                IDMLTable wrapperTable = wrapperTables.get(tableOrdinal);
                if (wrapperTable == null || wrapperTable.paragraphIndexBefore() < 0) continue;
                if (!plannedTables.add(wrapperTable.selfId())) continue;
                ResolvedTextFrame ownerTf = ownerFrameForTableOrdinal(ownerFrames, tableOrdinal);
                if (ownerTf == null) ownerTf = seedTf;
                AnchoredTablePlan plan = buildPlan(ctx, ownerTf, ownerStory, wrapperTable);
                if (plan == null) continue;
                ctx.addAnchoredTablePlan(plan);
                ctx.ownershipPlanLines.add(plan.toJson());
            }
        }
    }

    private static List<ResolvedTextFrame> visibleNonInlineFramesForStory(
            ResolvedBuildContext ctx,
            String storyId) {
        List<ResolvedTextFrame> result = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || storyId == null) return result;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames == null) return result;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.isInline() || tf.onHiddenLayer() || tf.nonprinting()) continue;
            result.add(tf);
        }
        return result;
    }

    private static List<IDMLTable> storyAnchoredTables(IDMLStory story) {
        List<IDMLTable> result = new ArrayList<>();
        if (story == null || story.tables() == null) return result;
        for (IDMLTable table : story.tables()) {
            if (table != null && table.paragraphIndexBefore() >= 0) {
                result.add(table);
            }
        }
        return result;
    }

    private static ResolvedTextFrame ownerFrameForTableOrdinal(
            List<ResolvedTextFrame> ownerFrames,
            int tableOrdinal) {
        if (ownerFrames == null || ownerFrames.isEmpty()) return null;
        int seen = 0;
        for (ResolvedTextFrame tf : ownerFrames) {
            int markers = tableMarkerCount(tf != null ? tf.frameVisibleText() : null);
            if (markers <= 0) continue;
            if (tableOrdinal < seen + markers) return tf;
            seen += markers;
        }
        return ownerFrames.get(0);
    }

    private static int tableMarkerCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\u0016') count++;
        }
        return count;
    }

    private static AnchoredTablePlan buildPlan(
            ResolvedBuildContext ctx,
            ResolvedTextFrame ownerTf,
            IDMLStory ownerStory,
            IDMLTable wrapperTable) {
        NestedTableRef nested = findNestedTable(ctx, wrapperTable);
        if (nested == null && !hasStandaloneStoryText(ownerStory)) return null;

        // table_only owner(본문 텍스트 없음)이고 wrapper가 실제 다행 레이아웃 테이블이면
        // anchored inline-flow로 평탄화하지 않는다. 이 경우 table은 owner 프레임 자체의 내용이며,
        // Phase 4가 프레임 bounds에 정상 테이블로 렌더(셀 내 nested table은 restoreNestedTextFrameTables로 복원)
        // 해야 행 위치/셀 폭이 보존된다. wrapperFlow 평탄화는 7행 사이드바를 한 칸으로 뭉개 위치/폭을 깬다.
        if (!hasStandaloneStoryText(ownerStory) && isMultiRowLayoutTable(wrapperTable)) {
            return null;
        }
        String nestedStoryId = nested != null ? nested.storyId : null;
        String nestedTableId = nested != null ? nested.tableId : wrapperTable.selfId();
        int anchoredTfDomId = nested != null ? nested.textFrameDomId : -1;
        int ownerTfDomId = parseDecimal(ownerTf.id(), -1);
        if (ownerTfDomId < 0 || nestedTableId == null) return null;
        int afterParagraphIndex = Math.max(0, wrapperTable.paragraphIndexBefore() - 1);
        return new AnchoredTablePlan(
                ownerTfDomId,
                ownerTf.storyId(),
                afterParagraphIndex,
                wrapperTable.selfId(),
                anchoredTfDomId,
                nestedStoryId,
                nestedTableId,
                ownerTf.pageIndex(),
                "story_table_marker_after_paragraph");
    }

    /**
     * 실제 다행 레이아웃 테이블 여부: 2행 이상이고, nested-table 셀 외에도
     * 독립 콘텐츠(텍스트/그래픽/자식 TextFrame)를 가진 셀이 2개 이상.
     * thin carrier(단일 행으로 nested table만 감싸는 래퍼)는 false.
     */
    private static boolean isMultiRowLayoutTable(IDMLTable wrapperTable) {
        if (wrapperTable == null || wrapperTable.rows() == null) return false;
        if (wrapperTable.rows().size() <= 1) return false;
        int contentCells = 0;
        for (IDMLTableRow row : wrapperTable.rows()) {
            if (row == null || row.cells() == null) continue;
            for (IDMLTableCell cell : row.cells()) {
                if (cell == null) continue;
                boolean hasContent = (cell.textFrameStoryRefs() != null && !cell.textFrameStoryRefs().isEmpty())
                        || cellHasText(cell);
                if (hasContent) contentCells++;
            }
        }
        return contentCells >= 2;
    }

    private static boolean cellHasText(IDMLTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph p : cell.paragraphs()) {
            if (p == null) continue;
            String t = p.getPlainText();
            if (t != null && !t.replace("￼", "").trim().isEmpty()) return true;
        }
        return false;
    }

    private static boolean hasStandaloneStoryText(IDMLStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph paragraph : story.paragraphs()) {
            if (paragraph == null) continue;
            String text = paragraph.getPlainText();
            if (text == null) continue;
            String normalized = text
                    .replace("\uFFFC", "")
                    .replace("\u0016", "")
                    .replace("\u0018", "")
                    .replace("\u0003", "")
                    .replace("\u0007", "")
                    .replace("\u0008", "")
                    .trim();
            if (!normalized.isEmpty()) return true;
        }
        return false;
    }

    private static NestedTableRef findNestedTable(ResolvedBuildContext ctx, IDMLTable wrapperTable) {
        if (wrapperTable.rows() == null) return null;
        for (IDMLTableRow row : wrapperTable.rows()) {
            if (row == null || row.cells() == null) continue;
            for (IDMLTableCell cell : row.cells()) {
                if (cell == null || cell.textFrameStoryRefs() == null) continue;
                for (String storyRef : cell.textFrameStoryRefs()) {
                    IDMLStory nestedStory = ctx.loadIDMLStory.apply(storyRef);
                    if (nestedStory == null || !nestedStory.hasTables()) continue;
                    IDMLTable nestedTable = firstTable(nestedStory);
                    if (nestedTable == null || nestedTable.selfId() == null) continue;
                    return new NestedTableRef(
                            toDecimalStoryId(storyRef),
                            nestedTable.selfId(),
                            firstTextFrameDomIdForStory(ctx, storyRef));
                }
            }
        }
        return null;
    }

    private static IDMLTable firstTable(IDMLStory story) {
        List<IDMLTable> tables = story.tables();
        return tables != null && !tables.isEmpty() ? tables.get(0) : null;
    }

    private static int firstTextFrameDomIdForStory(ResolvedBuildContext ctx, String storyRef) {
        String decimalStoryId = toDecimalStoryId(storyRef);
        if (decimalStoryId == null || ctx.resolvedData == null) return -1;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(decimalStoryId);
        if (frames == null || frames.isEmpty() || frames.get(0) == null) return -1;
        return parseDecimal(frames.get(0).id(), -1);
    }

    private static String toDecimalStoryId(String storyRef) {
        if (storyRef == null || storyRef.isEmpty()) return null;
        String s = storyRef;
        try {
            if (s.startsWith("u") || s.startsWith("U")) {
                return String.valueOf(Integer.parseInt(s.substring(1), 16));
            }
            return String.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return storyRef;
        }
    }

    private static int parseDecimal(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class NestedTableRef {
        final String storyId;
        final String tableId;
        final int textFrameDomId;

        NestedTableRef(String storyId, String tableId, int textFrameDomId) {
            this.storyId = storyId;
            this.tableId = tableId;
            this.textFrameDomId = textFrameDomId;
        }
    }
}
