package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/** Stage 1 plan for a numbered side-head flow table. */
public final class SideHeadFlowPlan {
    public final String tableSourceId;
    public final int pageIndex;
    public final int markerRow;
    public final int markerColumn;
    public final int headRow;
    public final int headColumn;
    public final int bodyRow;
    public final int bodyColumn;
    public final String reason;

    public SideHeadFlowPlan(
            String tableSourceId,
            int pageIndex,
            int markerRow,
            int markerColumn,
            int headRow,
            int headColumn,
            int bodyRow,
            int bodyColumn,
            String reason) {
        this.tableSourceId = tableSourceId;
        this.pageIndex = pageIndex;
        this.markerRow = markerRow;
        this.markerColumn = markerColumn;
        this.headRow = headRow;
        this.headColumn = headColumn;
        this.bodyRow = bodyRow;
        this.bodyColumn = bodyColumn;
        this.reason = reason;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(240);
        sb.append('{');
        append(sb, "kind", "side_head_flow");
        append(sb, "tableSourceId", tableSourceId);
        append(sb, "pageIndex", pageIndex);
        append(sb, "markerRow", markerRow);
        append(sb, "markerColumn", markerColumn);
        append(sb, "headRow", headRow);
        append(sb, "headColumn", headColumn);
        append(sb, "bodyRow", bodyRow);
        append(sb, "bodyColumn", bodyColumn);
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
