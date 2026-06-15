package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects page-level group stacks that are visually authored as a flowing unit.
 *
 * <p>These are not IDML inline anchors, so treating each frame as a static
 * page-floating object makes the middle title/table drift away from the
 * surrounding story. The ownership signal is source structure: a short title
 * TextFrame and a table-only TextFrame are descendants of the same group and
 * touch vertically in the same column.</p>
 */
public final class GroupedFlowStackPolicy {
    private static final int MAX_TITLE_CHARS = 48;
    private static final double SAME_COLUMN_OVERLAP_RATIO = 0.70;
    private static final double MAX_VERTICAL_GAP_PT = 8.0;

    private GroupedFlowStackPolicy() {}

    public static boolean isFlowStackTitleTextFrame(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (!isShortTitleFrame(tf)) return false;
        ResolvedPageItem titleItem = pageItem(ctx, tf);
        if (titleItem == null) return false;
        double[] titleBounds = bounds(titleItem);
        if (titleBounds == null) return false;

        Set<String> titleAncestors = ancestorIds(ctx, titleItem);
        if (titleAncestors.isEmpty()) return false;
        for (ResolvedTextFrame candidate : textFrames(ctx)) {
            if (candidate == null || candidate == tf) continue;
            if (candidate.pageIndex() != tf.pageIndex()) continue;
            if (!isTableOnly(ctx, candidate)) continue;
            ResolvedPageItem tableItem = pageItem(ctx, candidate);
            double[] tableBounds = bounds(tableItem);
            if (isStackedBelow(titleBounds, tableBounds)
                    && (sharesAncestor(ctx, tableItem, titleAncestors)
                    || isTightlyStackedBelow(titleBounds, tableBounds))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFlowStackTableTextFrame(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (!isTableOnly(ctx, tf)) return false;
        ResolvedPageItem tableItem = pageItem(ctx, tf);
        if (tableItem == null) return false;
        double[] tableBounds = bounds(tableItem);
        if (tableBounds == null) return false;

        Set<String> tableAncestors = ancestorIds(ctx, tableItem);
        if (tableAncestors.isEmpty()) return false;
        for (ResolvedTextFrame candidate : textFrames(ctx)) {
            if (candidate == null || candidate == tf) continue;
            if (candidate.pageIndex() != tf.pageIndex()) continue;
            if (!isShortTitleFrame(candidate)) continue;
            ResolvedPageItem titleItem = pageItem(ctx, candidate);
            double[] titleBounds = bounds(titleItem);
            if (isStackedBelow(titleBounds, tableBounds)
                    && (sharesAncestor(ctx, titleItem, tableAncestors)
                    || isTightlyStackedBelow(titleBounds, tableBounds))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasFlowStackTitleAboveBounds(
            ResolvedBuildContext ctx,
            int pageIndex,
            double tableTop,
            double tableLeft,
            double tableBottom,
            double tableRight) {
        double[] tableBounds = new double[] { tableTop, tableLeft, tableBottom, tableRight };
        for (ResolvedTextFrame candidate : textFrames(ctx)) {
            if (candidate == null) continue;
            if (candidate.pageIndex() != pageIndex) continue;
            if (!isShortTitleFrame(candidate)) continue;
            ResolvedPageItem titleItem = pageItem(ctx, candidate);
            if (isTightlyStackedBelow(bounds(titleItem), tableBounds)) return true;
        }
        return false;
    }

    private static Iterable<ResolvedTextFrame> textFrames(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null) return java.util.Collections.emptyList();
        return ctx.resolvedData.textFrames();
    }

    private static ResolvedPageItem pageItem(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null) return null;
        return ctx.resolvedData.getPageItem(tf.id());
    }

    private static boolean isShortTitleFrame(ResolvedTextFrame tf) {
        if (tf == null || tf.onHiddenLayer() || tf.nonprinting()) return false;
        String text = normalize(tf.frameVisibleText());
        if (text.length() < 2 || text.length() > MAX_TITLE_CHARS) return false;
        return text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
    }

    private static boolean isTableOnly(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || ctx.loadIDMLStory == null || tf == null || tf.storyId() == null) return false;
        IDMLStory story = ctx.loadIDMLStory.apply(tf.storyId());
        if (story == null || !story.hasTables()) return false;
        if (tf.onHiddenLayer() || tf.nonprinting()) return false;
        return TableFrameOwnershipPolicy.isTableAnchorOnlyFrame(tf);
    }

    private static boolean sharesAncestor(
            ResolvedBuildContext ctx, ResolvedPageItem item, Set<String> ancestorIds) {
        if (ctx == null || item == null || ancestorIds == null || ancestorIds.isEmpty()) return false;
        String parentId = item.parentId();
        while (parentId != null && !parentId.isEmpty()) {
            if (ancestorIds.contains(parentId)) return true;
            ResolvedPageItem parent = ctx.resolvedData.getPageItem(parentId);
            if (parent == null) break;
            parentId = parent.parentId();
        }
        return false;
    }

    private static Set<String> ancestorIds(ResolvedBuildContext ctx, ResolvedPageItem item) {
        Set<String> out = new HashSet<String>();
        if (ctx == null || ctx.resolvedData == null || item == null) return out;
        String parentId = item.parentId();
        while (parentId != null && !parentId.isEmpty()) {
            ResolvedPageItem parent = ctx.resolvedData.getPageItem(parentId);
            if (parent == null) break;
            if ("Group".equals(parent.type())) out.add(parentId);
            parentId = parent.parentId();
        }
        return out;
    }

    private static boolean isStackedBelow(double[] top, double[] bottom) {
        if (top == null || bottom == null || top.length < 4 || bottom.length < 4) return false;
        double overlap = Math.min(top[3], bottom[3]) - Math.max(top[1], bottom[1]);
        double minWidth = Math.max(1.0, Math.min(top[3] - top[1], bottom[3] - bottom[1]));
        if (overlap / minWidth < SAME_COLUMN_OVERLAP_RATIO) return false;
        double gap = bottom[0] - top[2];
        return gap >= -1.0 && gap <= MAX_VERTICAL_GAP_PT;
    }

    private static boolean isTightlyStackedBelow(double[] top, double[] bottom) {
        if (top == null || bottom == null || top.length < 4 || bottom.length < 4) return false;
        double overlap = Math.min(top[3], bottom[3]) - Math.max(top[1], bottom[1]);
        double minWidth = Math.max(1.0, Math.min(top[3] - top[1], bottom[3] - bottom[1]));
        double gap = bottom[0] - top[2];
        return overlap / minWidth >= 0.90 && gap >= -0.5 && gap <= 2.0;
    }

    private static double[] bounds(ResolvedPageItem item) {
        if (item == null) return null;
        double[] b = item.pageRelativeBounds();
        return b != null ? b : item.geometricBounds();
    }

    private static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString().trim();
    }
}
