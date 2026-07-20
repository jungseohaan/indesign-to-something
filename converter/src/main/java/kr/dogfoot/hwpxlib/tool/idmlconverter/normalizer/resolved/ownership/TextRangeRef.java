package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/** A Stage 1 ownership reference to a text span inside a source TextFrame story. */
public final class TextRangeRef {
    public final int textFrameId;
    public final String storyId;
    public final int paragraphIndex;
    public final int runIndex;
    public final int start;
    public final int end;
    public final int paragraphStart;
    public final int paragraphEnd;
    public final String text;

    public TextRangeRef(
            int textFrameId,
            String storyId,
            int paragraphIndex,
            int runIndex,
            int start,
            int end,
            int paragraphStart,
            int paragraphEnd,
            String text) {
        this.textFrameId = textFrameId;
        this.storyId = storyId;
        this.paragraphIndex = paragraphIndex;
        this.runIndex = runIndex;
        this.start = start;
        this.end = end;
        this.paragraphStart = paragraphStart;
        this.paragraphEnd = paragraphEnd;
        this.text = text;
    }

    public TextRangeRef copy() {
        return new TextRangeRef(
                textFrameId, storyId, paragraphIndex, runIndex, start, end,
                paragraphStart, paragraphEnd, text);
    }

    public String toJson() {
        return "{\"textFrameId\":" + textFrameId
                + ",\"storyId\":\"" + ObjectPlan.escape(storyId) + "\""
                + ",\"paragraphIndex\":" + paragraphIndex
                + ",\"runIndex\":" + runIndex
                + ",\"start\":" + start
                + ",\"end\":" + end
                + ",\"paragraphStart\":" + paragraphStart
                + ",\"paragraphEnd\":" + paragraphEnd
                + ",\"text\":\"" + ObjectPlan.escape(text) + "\"}";
    }
}
