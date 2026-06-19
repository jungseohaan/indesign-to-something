package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 1 policy for "large number + small head + body" side-head flows.
 *
 * <p>The output shape is a borderless two-column flow table where the left
 * marker cell spans the head and body rows. This planner records the source
 * table before legacy builders run; executors must not rediscover the policy
 * from page number, text phrase, or absolute coordinates.</p>
 */
public final class SideHeadFlowPlanner {
    private SideHeadFlowPlanner() {}

    public static void plan(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null || ctx.loadIDMLStory == null) return;
        Set<String> plannedStories = new HashSet<>();
        Set<String> plannedTables = new HashSet<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.storyId() == null) continue;
            if (tf.sourceHidden()) continue;
            if (!plannedStories.add(tf.storyId())) continue;

            IDMLStory story = ctx.loadIDMLStory.apply(tf.storyId());
            if (story == null || story.tables() == null) continue;
            for (IDMLTable table : story.tables()) {
                if (table == null || table.selfId() == null) continue;
                if (!plannedTables.add(table.selfId())) continue;
                if (!isSideHeadFlowCandidate(table)) continue;
                SideHeadFlowPlan plan = new SideHeadFlowPlan(
                        table.selfId(),
                        tf.pageIndex(),
                        0,
                        0,
                        0,
                        1,
                        1,
                        1,
                        "source_table_numbered_marker_with_head_and_body");
                ctx.addSideHeadFlowPlan(plan);
                ctx.ownershipPlanLines.add(plan.toJson());
            }
        }
    }

    private static boolean isSideHeadFlowCandidate(IDMLTable table) {
        if (table.columnCount() != 1) return false;
        List<IDMLTableRow> rows = table.rows();
        if (rows == null || rows.size() < 2) return false;
        IDMLTableCell firstCell = singleCell(rows.get(0));
        IDMLTableCell bodyCell = singleCell(rows.get(1));
        if (firstCell == null || bodyCell == null) return false;
        if (!hasSubstantiveParagraph(bodyCell)) return false;
        return hasLargeNumericMarkerAndHead(firstCell);
    }

    private static IDMLTableCell singleCell(IDMLTableRow row) {
        if (row == null || row.cells() == null || row.cells().size() != 1) return null;
        return row.cells().get(0);
    }

    private static boolean hasSubstantiveParagraph(IDMLTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (IDMLParagraph paragraph : cell.paragraphs()) {
            if (!visibleText(paragraph).trim().isEmpty()) return true;
            if (hasInlineContent(paragraph)) return true;
        }
        return false;
    }

    private static boolean hasLargeNumericMarkerAndHead(IDMLTableCell cell) {
        if (cell == null || cell.paragraphs() == null || cell.paragraphs().size() != 1) return false;
        IDMLParagraph paragraph = cell.paragraphs().get(0);
        if (paragraph == null || paragraph.characterRuns() == null) return false;

        if (hasAutomaticNumberMarker(paragraph)) {
            return hasInlineContent(paragraph) || !visibleText(paragraph).trim().isEmpty();
        }

        boolean sawMarker = false;
        boolean sawHeadContentAfterMarker = false;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null) continue;
            String text = run.content();
            String trimmed = text != null ? text.trim() : "";
            if (!sawMarker) {
                if (trimmed.isEmpty()) continue;
                String markerText = markerText(trimmed);
                if (!markerText.matches("[0-9]{1,2}")) return false;
                sawMarker = true;
                if (runHasInlineHeadContent(run)) sawHeadContentAfterMarker = true;
                continue;
            }
            if (!trimmed.isEmpty() || runHasInlineHeadContent(run)) {
                sawHeadContentAfterMarker = true;
            }
        }
        return sawMarker && sawHeadContentAfterMarker;
    }

    private static boolean hasAutomaticNumberMarker(IDMLParagraph paragraph) {
        if (paragraph == null) return false;
        String expr = paragraph.numberingExpression();
        return expr != null && expr.contains("^#");
    }

    private static boolean hasInlineContent(IDMLParagraph paragraph) {
        if (paragraph == null || paragraph.characterRuns() == null) return false;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (runHasInlineHeadContent(run)) return true;
        }
        return false;
    }

    private static boolean runHasInlineHeadContent(IDMLCharacterRun run) {
        return run != null
                && ((run.inlineFrames() != null && !run.inlineFrames().isEmpty())
                || (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty())
                || (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()));
    }

    private static String visibleText(IDMLParagraph paragraph) {
        if (paragraph == null) return "";
        String text = paragraph.getPlainText();
        if (text == null) return "";
        return stripObjectChars(text);
    }

    private static String markerText(String text) {
        if (text == null) return "";
        return stripObjectChars(text).trim();
    }

    private static String stripObjectChars(String text) {
        if (text == null) return "";
        return text
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "");
    }
}
