package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/** Stage 1 plan for an IDML table anchored in a TextFrame story. */
public final class AnchoredTablePlan {
    public final int ownerTextFrameDomId;
    public final String ownerStoryId;
    public final int afterParagraphIndex;
    public final String wrapperTableId;
    public final int anchoredTextFrameDomId;
    public final String nestedStoryId;
    public final String nestedTableId;
    public final int pageIndex;
    public final String reason;

    public AnchoredTablePlan(
            int ownerTextFrameDomId,
            String ownerStoryId,
            int afterParagraphIndex,
            String wrapperTableId,
            int anchoredTextFrameDomId,
            String nestedStoryId,
            String nestedTableId,
            int pageIndex,
            String reason) {
        this.ownerTextFrameDomId = ownerTextFrameDomId;
        this.ownerStoryId = ownerStoryId;
        this.afterParagraphIndex = afterParagraphIndex;
        this.wrapperTableId = wrapperTableId;
        this.anchoredTextFrameDomId = anchoredTextFrameDomId;
        this.nestedStoryId = nestedStoryId;
        this.nestedTableId = nestedTableId;
        this.pageIndex = pageIndex;
        this.reason = reason;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        append(sb, "kind", "anchored_table");
        append(sb, "ownerTextFrameDomId", ownerTextFrameDomId);
        append(sb, "ownerStoryId", ownerStoryId);
        append(sb, "afterParagraphIndex", afterParagraphIndex);
        append(sb, "wrapperTableId", wrapperTableId);
        append(sb, "anchoredTextFrameDomId", anchoredTextFrameDomId);
        append(sb, "nestedStoryId", nestedStoryId);
        append(sb, "nestedTableId", nestedTableId);
        append(sb, "pageIndex", pageIndex);
        append(sb, "reason", reason);
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null) return;
        sb.append('"').append(escape(key)).append("\":\"")
                .append(escape(value)).append("\",");
    }

    private static void append(StringBuilder sb, String key, int value) {
        sb.append('"').append(escape(key)).append("\":").append(value).append(',');
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
