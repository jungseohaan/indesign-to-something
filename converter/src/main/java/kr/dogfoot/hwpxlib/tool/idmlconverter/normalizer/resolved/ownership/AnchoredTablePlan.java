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
        append(sb, "domId", ownerTextFrameDomId);
        append(sb, "kind", "anchored_table");
        append(sb, "textAction", TextAction.OWNED_BY_HWPX_TEXT.name());
        append(sb, "visualAction", VisualAction.PLACE_TABLE_STYLE.name());
        append(sb, "visualLayer", VisualLayer.CONTENT_VISUAL.name());
        append(sb, "policyLayer", PolicyLayer.TEXT.name());
        append(sb, "placement", Placement.INLINE.name());
        append(sb, "renderId", -1);
        appendSourceObjectIds(sb);
        append(sb, "zOrder", 0);
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

    private void appendSourceObjectIds(StringBuilder sb) {
        sb.append("\"sourceObjectIds\":[");
        boolean first = true;
        if (ownerTextFrameDomId >= 0) {
            sb.append(ownerTextFrameDomId);
            first = false;
        }
        if (anchoredTextFrameDomId >= 0 && anchoredTextFrameDomId != ownerTextFrameDomId) {
            if (!first) sb.append(',');
            sb.append(anchoredTextFrameDomId);
        }
        sb.append("],");
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
