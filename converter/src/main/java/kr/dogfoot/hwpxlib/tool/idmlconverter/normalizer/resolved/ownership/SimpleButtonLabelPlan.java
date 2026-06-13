package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import java.util.Arrays;

/**
 * Stage 1 plan for short inline marker labels such as 가/나/다, ㄱ/ㄴ, 1/2.
 *
 * <p>The planner owns classification. Text/table builders should only execute
 * this plan when they encounter the inline anchor.</p>
 */
public final class SimpleButtonLabelPlan {
    public enum Mode {
        TEXT_SHELL,
        COMPLETE_PNG
    }

    public final int anchorDomId;
    public final int labelTextFrameDomId;
    public final int shellDomId;
    public final int pageIndex;
    public final String labelText;
    public final String labelFontFamily;
    public final String labelFontStyle;
    public final Double labelFontSizePt;
    public final String labelTextColorHex;
    public final Double labelTracking;
    public final Double labelHorizontalScale;
    public final Mode mode;
    public final int[] sourceObjectIds;
    public final String reason;

    public SimpleButtonLabelPlan(
            int anchorDomId,
            int labelTextFrameDomId,
            int shellDomId,
            int pageIndex,
            String labelText,
            String labelFontFamily,
            String labelFontStyle,
            Double labelFontSizePt,
            String labelTextColorHex,
            Double labelTracking,
            Double labelHorizontalScale,
            Mode mode,
            int[] sourceObjectIds,
            String reason) {
        this.anchorDomId = anchorDomId;
        this.labelTextFrameDomId = labelTextFrameDomId;
        this.shellDomId = shellDomId;
        this.pageIndex = pageIndex;
        this.labelText = labelText;
        this.labelFontFamily = labelFontFamily;
        this.labelFontStyle = labelFontStyle;
        this.labelFontSizePt = labelFontSizePt;
        this.labelTextColorHex = labelTextColorHex;
        this.labelTracking = labelTracking;
        this.labelHorizontalScale = labelHorizontalScale;
        this.mode = mode != null ? mode : Mode.TEXT_SHELL;
        this.sourceObjectIds = sourceObjectIds != null ? Arrays.copyOf(sourceObjectIds, sourceObjectIds.length) : new int[0];
        this.reason = reason;
    }
}
