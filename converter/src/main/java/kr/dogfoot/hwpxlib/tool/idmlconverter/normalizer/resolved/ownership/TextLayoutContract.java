package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import java.util.Arrays;

/** Stage 1 text-layout execution contract derived from source layout metadata. */
public final class TextLayoutContract {
    public static final String SOURCE_TEXT_WRAP = "SOURCE_TEXT_WRAP";

    public final String type;
    public final String source;
    public final int textFrameId;
    public final String wrapSide;
    public final int[] obstacleSourceObjectIds;
    public final String[] obstacleObjectPlanIds;
    public final int lineCount;
    public final String reason;

    public TextLayoutContract(
            String type,
            String source,
            int textFrameId,
            String wrapSide,
            int[] obstacleSourceObjectIds,
            String[] obstacleObjectPlanIds,
            int lineCount,
            String reason) {
        this.type = type;
        this.source = source;
        this.textFrameId = textFrameId;
        this.wrapSide = wrapSide;
        this.obstacleSourceObjectIds = obstacleSourceObjectIds != null
                ? Arrays.copyOf(obstacleSourceObjectIds, obstacleSourceObjectIds.length)
                : new int[0];
        this.obstacleObjectPlanIds = obstacleObjectPlanIds != null
                ? Arrays.copyOf(obstacleObjectPlanIds, obstacleObjectPlanIds.length)
                : new String[0];
        this.lineCount = lineCount;
        this.reason = reason;
    }

    public boolean isSourceTextWrap() {
        return SOURCE_TEXT_WRAP.equals(type);
    }

    public TextLayoutContract copy() {
        return new TextLayoutContract(
                type,
                source,
                textFrameId,
                wrapSide,
                obstacleSourceObjectIds,
                obstacleObjectPlanIds,
                lineCount,
                reason);
    }

    public String toJson() {
        return new StringBuilder(160)
                .append('{')
                .append("\"type\":\"").append(ObjectPlan.escape(type)).append("\",")
                .append("\"source\":\"").append(ObjectPlan.escape(source)).append("\",")
                .append("\"textFrameId\":").append(textFrameId).append(',')
                .append("\"wrapSide\":\"").append(ObjectPlan.escape(wrapSide)).append("\",")
                .append("\"obstacleSourceObjectIds\":")
                .append(ObjectPlan.intArrayJson(obstacleSourceObjectIds)).append(',')
                .append("\"obstacleObjectPlanIds\":")
                .append(ObjectPlan.stringArrayJson(obstacleObjectPlanIds)).append(',')
                .append("\"lineCount\":").append(lineCount).append(',')
                .append("\"reason\":\"").append(ObjectPlan.escape(reason)).append("\"")
                .append('}')
                .toString();
    }
}
