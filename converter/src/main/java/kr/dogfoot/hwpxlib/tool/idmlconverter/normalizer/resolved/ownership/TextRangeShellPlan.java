package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import java.util.Arrays;

/** Stage 1 plan for moving a source TextFrame text range into a sibling shell. */
public final class TextRangeShellPlan {
    public final int shellDomId;
    public final int textFrameId;
    public final String storyId;
    public final int pageIndex;
    public final int zOrder;
    public final TextRangeRef range;
    public final String shellType;
    public final double[] shellBounds;
    public final String fillColorHex;
    public final String strokeColorHex;
    public final double strokeWeight;
    public final double cornerRadius;

    public TextRangeShellPlan(
            int shellDomId,
            int textFrameId,
            String storyId,
            int pageIndex,
            int zOrder,
            TextRangeRef range,
            String shellType,
            double[] shellBounds,
            String fillColorHex,
            String strokeColorHex,
            double strokeWeight,
            double cornerRadius) {
        this.shellDomId = shellDomId;
        this.textFrameId = textFrameId;
        this.storyId = storyId;
        this.pageIndex = pageIndex;
        this.zOrder = zOrder;
        this.range = range != null ? range.copy() : null;
        this.shellType = shellType;
        this.shellBounds = shellBounds != null ? Arrays.copyOf(shellBounds, shellBounds.length) : null;
        this.fillColorHex = fillColorHex;
        this.strokeColorHex = strokeColorHex;
        this.strokeWeight = strokeWeight;
        this.cornerRadius = cornerRadius;
    }

    public String toJson() {
        return "{\"shellDomId\":" + shellDomId
                + ",\"textFrameId\":" + textFrameId
                + ",\"storyId\":\"" + ObjectPlan.escape(storyId) + "\""
                + ",\"pageIndex\":" + pageIndex
                + ",\"zOrder\":" + zOrder
                + ",\"range\":" + (range != null ? range.toJson() : "null")
                + ",\"shellType\":\"" + ObjectPlan.escape(shellType) + "\""
                + ",\"shellBounds\":" + boundsJson(shellBounds)
                + ",\"fillColorHex\":\"" + ObjectPlan.escape(fillColorHex) + "\""
                + ",\"strokeColorHex\":\"" + ObjectPlan.escape(strokeColorHex) + "\""
                + ",\"strokeWeight\":" + strokeWeight
                + ",\"cornerRadius\":" + cornerRadius
                + "}";
    }

    private static String boundsJson(double[] bounds) {
        if (bounds == null || bounds.length < 4) return "[]";
        return "[" + bounds[0] + "," + bounds[1] + "," + bounds[2] + "," + bounds[3] + "]";
    }
}
