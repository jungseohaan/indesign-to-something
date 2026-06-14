package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared;

import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

/** Shared ownership gates for inline label-like groups. */
public final class InlineSemanticLabelPolicy {
    private InlineSemanticLabelPolicy() {}

    public static boolean isSemanticMultiTextInlineGroup(ResolvedData data, int groupDomId) {
        if (data == null) return false;
        ResolvedPageItem group = data.getPageItem(String.valueOf(groupDomId));
        if (group == null || !"Group".equals(group.type()) || !group.isInline()) return false;

        int textCount = 0;
        boolean hasSemanticText = false;
        java.util.Set<String> descendants = data.buildDescendantSet(String.valueOf(groupDomId), 5);
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null || !tf.isInline()) continue;
            ResolvedPageItem pi = data.getPageItem(tf.id());
            if (pi == null) continue;
            String parentId = pi.parentId();
            if (parentId == null) continue;
            if (!String.valueOf(groupDomId).equals(parentId) && !descendants.contains(parentId)) continue;
            if (cleanText(tf.frameVisibleText()).isEmpty()) continue;
            textCount++;
            if (!data.isSimpleButtonLabelTextFrame(tf.id())) {
                hasSemanticText = true;
            }
        }
        return textCount >= 2 && hasSemanticText;
    }

    public static Integer semanticMultiTextInlineGroupAncestor(ResolvedData data, String textFrameId) {
        if (data == null || textFrameId == null) return null;
        String curId = textFrameId;
        for (int depth = 0; depth < 8 && curId != null; depth++) {
            ResolvedPageItem item = data.getPageItem(curId);
            if (item == null) return null;
            String parentId = item.parentId();
            if (parentId == null || parentId.isEmpty()) return null;
            int parentDomId = parseDomId(parentId);
            if (parentDomId >= 0 && isSemanticMultiTextInlineGroup(data, parentDomId)) {
                return parentDomId;
            }
            curId = parentId;
        }
        return null;
    }

    public static boolean isStandaloneSemanticGraphicInlineGroup(
            ResolvedData data,
            RenderedGroup rg) {
        if (data == null || rg == null) return false;
        if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        return isStandaloneSemanticGraphicInlineGroup(data, rg.id(), rg.bounds(),
                rg.sourceObjectIds(), rg.visualOnlyChildIds());
    }

    public static boolean isStandaloneSemanticGraphicInlineGroup(ResolvedData data, int groupDomId) {
        if (data == null) return false;
        if (data.allRenderedFloatingItems() != null) {
            for (RenderedGroup rg : data.allRenderedFloatingItems()) {
                if (rg == null || rg.id() != groupDomId) continue;
                if (isStandaloneSemanticGraphicInlineGroup(data, rg)) return true;
            }
        }
        return false;
    }

    private static boolean isStandaloneSemanticGraphicInlineGroup(
            ResolvedData data,
            int groupDomId,
            double[] bounds,
            int[] sourceObjectIds,
            int[] visualOnlyChildIds) {
        ResolvedPageItem group = data.getPageItem(String.valueOf(groupDomId));
        if (group == null || !"Group".equals(group.type()) || !group.isInline()) return false;
        if (hasDescendantTextFrame(data, String.valueOf(groupDomId))) return false;
        if (bounds == null || bounds.length < 4) return false;
        double w = Math.abs(bounds[3] - bounds[1]);
        double h = Math.abs(bounds[2] - bounds[0]);
        int sourceCount = sourceObjectIds != null ? sourceObjectIds.length : 0;
        int visualCount = visualOnlyChildIds != null ? visualOnlyChildIds.length : 0;
        if (sourceCount < 8 && visualCount < 6) return false;
        return w >= 15.0 && w <= 40.0 && h >= 10.0 && h <= 25.0;
    }

    private static boolean hasDescendantTextFrame(ResolvedData data, String groupId) {
        java.util.Set<String> descendants = data.buildDescendantSet(groupId, 5);
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            ResolvedPageItem pi = data.getPageItem(tf.id());
            if (pi == null) continue;
            String parentId = pi.parentId();
            if (parentId == null) continue;
            if (groupId.equals(parentId) || descendants.contains(parentId)) return true;
        }
        return false;
    }

    private static String cleanText(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private static int parseDomId(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
