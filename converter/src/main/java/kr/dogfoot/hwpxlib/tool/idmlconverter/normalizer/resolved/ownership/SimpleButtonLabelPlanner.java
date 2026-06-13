package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds SimpleButtonLabelPlan once for the whole document. */
public final class SimpleButtonLabelPlanner {
    private SimpleButtonLabelPlanner() {}

    public static void plan(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null) return;
        ResolvedData data = ctx.resolvedData;
        ctx.simpleButtonLabelPlans.clear();
        ctx.simpleButtonLabelPlansByTextFrameId.clear();

        for (ResolvedPageItem anchor : data.pageItems()) {
            if (!isEligibleAnchor(anchor)) continue;
            ResolvedTextFrame labelTf = findSingleSimpleLabelTextFrame(data, anchor);
            if (labelTf == null) continue;
            ResolvedPageItem shell = chooseShellItem(data, anchor);
            if (shell == null) continue;
            String labelText = data.simpleButtonLabelText(labelTf.id());
            if (labelText == null) continue;

            SimpleButtonLabelPlan.Mode mode = hasCompletePngRender(data, anchor, labelTf)
                    ? SimpleButtonLabelPlan.Mode.COMPLETE_PNG
                    : SimpleButtonLabelPlan.Mode.TEXT_SHELL;
            LabelStyle labelStyle = labelStyle(ctx, labelTf, labelText);
            int[] sources = sourceIds(anchor, labelTf, shell);
            SimpleButtonLabelPlan plan = new SimpleButtonLabelPlan(
                    parseInt(anchor.id()),
                    parseInt(labelTf.id()),
                    parseInt(shell.id()),
                    anchor.pageIndex(),
                    labelText,
                    labelStyle.fontFamily,
                    labelStyle.fontStyle,
                    labelStyle.fontSizePt,
                    labelStyle.textColorHex,
                    labelStyle.tracking,
                    labelStyle.horizontalScale,
                    mode,
                    sources,
                    mode == SimpleButtonLabelPlan.Mode.COMPLETE_PNG
                            ? "simple_button_label_complete_png"
                            : "simple_button_label_text_shell");
            ctx.addSimpleButtonLabelPlan(plan);
            ObjectPlan objectPlan = new ObjectPlan(
                    plan.anchorDomId,
                    "simple_button_label:" + safe(anchor.type()),
                    plan.pageIndex,
                    mode == SimpleButtonLabelPlan.Mode.COMPLETE_PNG
                            ? TextAction.OWNED_BY_PNG
                            : TextAction.OWNED_BY_HWPX_TEXT,
                    mode == SimpleButtonLabelPlan.Mode.COMPLETE_PNG
                            ? VisualAction.PLACE_INLINE_PNG
                            : VisualAction.PLACE_TEXT_SHELL,
                    VisualLayer.LABEL_BACKDROP,
                    Placement.INLINE,
                    null,
                    sources,
                    anchor.zOrder(),
                    plan.reason,
                    null,
                    anchor.pageRelativeBounds() != null ? anchor.pageRelativeBounds() : anchor.geometricBounds());
            ctx.addOwnershipPlan(objectPlan);
            ctx.ownershipPlanLines.add(objectPlan.toJson());
        }
    }

    private static LabelStyle labelStyle(ResolvedBuildContext ctx, ResolvedTextFrame labelTf, String labelText) {
        LabelStyle style = new LabelStyle();
        if (ctx == null || ctx.loadIDMLStory == null || ctx.resolvedData == null
                || labelTf == null || labelTf.storyId() == null) {
            return style;
        }
        IDMLStory story = ctx.loadIDMLStory.apply(labelTf.storyId());
        if (story == null || story.paragraphs() == null) return style;
        for (IDMLParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null) continue;
                String text = clean(run.content());
                if (text.isEmpty()) continue;
                if (labelText != null && !labelText.equals(text) && !text.contains(labelText)) continue;
                style.fontFamily = run.fontFamily();
                style.fontStyle = run.fontStyle();
                style.fontSizePt = run.fontSize();
                style.tracking = run.tracking();
                style.horizontalScale = run.horizontalScale();
                style.textColorHex = ctx.resolvedData.resolveColorHex(run.fillColor());
                return style;
            }
        }
        return style;
    }

    private static String clean(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private static final class LabelStyle {
        String fontFamily;
        String fontStyle;
        Double fontSizePt;
        String textColorHex;
        Double tracking;
        Double horizontalScale;
    }

    private static boolean isEligibleAnchor(ResolvedPageItem item) {
        if (item == null || item.id() == null || !item.isInline()) return false;
        String type = item.type();
        return "Oval".equals(type)
                || "Rectangle".equals(type)
                || "Polygon".equals(type)
                || "Group".equals(type);
    }

    private static ResolvedTextFrame findSingleSimpleLabelTextFrame(ResolvedData data, ResolvedPageItem anchor) {
        Set<String> descendants = data.buildDescendantSet(anchor.id(), 5);
        ResolvedTextFrame found = null;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null || !tf.isInline()) continue;
            if (!descendants.contains(tf.id())) continue;
            if (data.simpleButtonLabelText(tf.id()) == null) continue;
            if (found != null) return null;
            found = tf;
        }
        return found;
    }

    private static ResolvedPageItem chooseShellItem(ResolvedData data, ResolvedPageItem anchor) {
        if (isShape(anchor)) return anchor;
        Set<String> descendants = data.buildDescendantSet(anchor.id(), 5);
        ResolvedPageItem best = null;
        double bestArea = 0;
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || item.id() == null || !isShape(item)) continue;
            if (!descendants.contains(item.id())) continue;
            if (!hasVisibleShellStyle(item)) continue;
            double area = area(item);
            if (area > bestArea) {
                bestArea = area;
                best = item;
            }
        }
        return best;
    }

    private static boolean isShape(ResolvedPageItem item) {
        if (item == null) return false;
        String type = item.type();
        return "Oval".equals(type) || "Rectangle".equals(type) || "Polygon".equals(type);
    }

    private static boolean hasVisibleShellStyle(ResolvedPageItem item) {
        if (item == null) return false;
        String fill = item.fillColorName();
        if (fill != null && !"None".equals(fill) && !"[None]".equals(fill)) return true;
        String stroke = item.strokeColorName();
        return stroke != null && !"None".equals(stroke) && !"[None]".equals(stroke)
                && item.strokeWeight() > 0;
    }

    private static double area(ResolvedPageItem item) {
        double[] b = item.geometricBounds();
        if (b == null || b.length < 4) b = item.pageRelativeBounds();
        if (b == null || b.length < 4) return 0;
        return Math.abs(b[3] - b[1]) * Math.abs(b[2] - b[0]);
    }

    private static boolean hasCompletePngRender(ResolvedData data, ResolvedPageItem anchor, ResolvedTextFrame labelTf) {
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (!data.shouldUseCompletePngForSimpleButtonLabel(rg)) continue;
            if (rg.id() == parseInt(anchor.id())) return true;
            String[] editableIds = rg.editableTextFrameIds();
            if (contains(editableIds, labelTf.id())) return true;
        }
        return false;
    }

    private static int[] sourceIds(ResolvedPageItem anchor, ResolvedTextFrame labelTf, ResolvedPageItem shell) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        add(ids, anchor != null ? anchor.id() : null);
        add(ids, shell != null ? shell.id() : null);
        add(ids, labelTf != null ? labelTf.id() : null);
        List<Integer> list = new ArrayList<>(ids);
        int[] result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) result[i] = list.get(i);
        return result;
    }

    private static void add(Set<Integer> ids, String id) {
        int parsed = parseInt(id);
        if (parsed >= 0) ids.add(parsed);
    }

    private static boolean contains(String[] values, String target) {
        if (values == null || target == null) return false;
        for (String value : values) {
            if (target.equals(value)) return true;
        }
        return false;
    }

    private static int parseInt(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
