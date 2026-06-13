package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
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
        if (tf.onHiddenLayer() || tf.nonprinting()) return false;
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
}
