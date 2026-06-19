package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

/**
 * Ownership rules for TextFrames whose visible content is an IDML table.
 *
 * <p>Some InDesign table frames are exported as inline TextFrames even though
 * their visible table is laid out as a page object with its own pageIndex and
 * bounds. For table-only frames, the frame itself owns placement; using a
 * text-flow anchor can move the table to a previous page.</p>
 */
public final class TableFrameOwnershipPolicy {
    private TableFrameOwnershipPolicy() {}

    public static boolean shouldPlaceInlineTableAsPageLevel(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            IDMLStory story) {
        if (ctx != null && story != null && story.tables() != null) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table : story.tables()) {
                if (table != null && ctx.isAnchoredTableSource(table.selfId())) {
                    return false;
                }
            }
        }
        return shouldPlaceInlineTableAsPageLevel(tf, story);
    }

    public static boolean shouldPlaceInlineTableAsPageLevel(
            ResolvedTextFrame tf,
            IDMLStory story) {
        if (tf == null || story == null) return false;
        if (!tf.isInline() || !story.hasTables()) return false;
        if (tf.sourceHidden()) return false;
        if (!isTableAnchorOnlyFrame(tf)) return false;

        return true;
    }

    public static boolean isTableAnchorOnlyFrame(ResolvedTextFrame tf) {
        if (tf == null) return false;
        String text = tf.frameVisibleText();
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            if (Character.isISOControl(ch)) continue;
            if (ch == '\uFFFC') continue;
            return false;
        }
        return true;
    }

    /**
     * A TextFrame whose visible frame text is only a table/object marker still
     * owns visible HWPX text when the attached Story consists of table cells.
     * Treating it as an empty text frame lets Phase 3 skip it while Phase 4 has
     * no explicit ownership contract, which can drop the cell text.
     */
    public static boolean isTableOnlyTextFrame(ResolvedTextFrame tf, IDMLStory story) {
        if (tf == null || story == null || !story.hasTables()) return false;
        if (tf.sourceHidden()) return false;
        if (!isTableAnchorOnlyFrame(tf)) return false;
        if (hasStandaloneStoryText(story)) return false;
        return hasVisibleCellText(story);
    }

    public static boolean hasStandaloneStoryText(IDMLStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph paragraph : story.paragraphs()) {
            if (paragraph == null) continue;
            String text = paragraph.getPlainText();
            if (!normalizeVisibleText(text).isEmpty()) return true;
        }
        return false;
    }

    public static boolean hasVisibleCellText(IDMLStory story) {
        if (story == null || story.tables() == null) return false;
        for (IDMLTable table : story.tables()) {
            if (table == null || table.rows() == null) continue;
            for (IDMLTableRow row : table.rows()) {
                if (row == null || row.cells() == null) continue;
                for (IDMLTableCell cell : row.cells()) {
                    if (cell == null || cell.paragraphs() == null) continue;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph paragraph : cell.paragraphs()) {
                        if (paragraph == null) continue;
                        String text = paragraph.getPlainText();
                        if (!normalizeVisibleText(text).isEmpty()) return true;
                    }
                }
            }
        }
        return false;
    }

    private static String normalizeVisibleText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0016' || ch == '\u0018'
                    || ch == '\u0003' || ch == '\u0007' || ch == '\u0008') {
                continue;
            }
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }
}
