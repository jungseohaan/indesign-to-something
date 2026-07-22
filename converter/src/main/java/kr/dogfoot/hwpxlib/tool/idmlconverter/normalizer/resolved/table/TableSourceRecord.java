package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.table;

import java.util.Arrays;

/** Immutable Stage 0 facts for one IDML table carrier. */
public final class TableSourceRecord {
    public final String carrierTextFrameId;
    public final int carrierDomId;
    public final String storyId;
    public final int storyDomId;
    public final String tableId;
    public final int tableDomId;
    public final int pageIndex;
    public final boolean visibleAncestry;
    public final boolean tableOnlyStory;
    public final String[] ancestryIds;
    public final int[] styleSourceIds;
    public final double[] bounds;
    public final String issue;

    public TableSourceRecord(
            String carrierTextFrameId,
            int carrierDomId,
            String storyId,
            int storyDomId,
            String tableId,
            int tableDomId,
            int pageIndex,
            boolean visibleAncestry,
            boolean tableOnlyStory,
            String[] ancestryIds,
            int[] styleSourceIds,
            double[] bounds,
            String issue) {
        this.carrierTextFrameId = carrierTextFrameId;
        this.carrierDomId = carrierDomId;
        this.storyId = storyId;
        this.storyDomId = storyDomId;
        this.tableId = tableId;
        this.tableDomId = tableDomId;
        this.pageIndex = pageIndex;
        this.visibleAncestry = visibleAncestry;
        this.tableOnlyStory = tableOnlyStory;
        this.ancestryIds = ancestryIds != null ? Arrays.copyOf(ancestryIds, ancestryIds.length) : new String[0];
        this.styleSourceIds = styleSourceIds != null ? Arrays.copyOf(styleSourceIds, styleSourceIds.length) : new int[0];
        this.bounds = bounds != null ? Arrays.copyOf(bounds, bounds.length) : null;
        this.issue = issue;
    }

    public boolean executable() {
        return issue == null && visibleAncestry && carrierDomId >= 0 && storyDomId >= 0
                && tableDomId >= 0 && pageIndex >= 0 && bounds != null && bounds.length >= 4;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"carrierTextFrameId\":\"").append(escape(carrierTextFrameId)).append("\"");
        sb.append(",\"carrierDomId\":").append(carrierDomId);
        sb.append(",\"storyId\":\"").append(escape(storyId)).append("\"");
        sb.append(",\"storyDomId\":").append(storyDomId);
        sb.append(",\"tableId\":\"").append(escape(tableId)).append("\"");
        sb.append(",\"tableDomId\":").append(tableDomId);
        sb.append(",\"pageIndex\":").append(pageIndex);
        sb.append(",\"visibleAncestry\":").append(visibleAncestry);
        sb.append(",\"tableOnlyStory\":").append(tableOnlyStory);
        sb.append(",\"executable\":").append(executable());
        sb.append(",\"ancestryIds\":[");
        for (int i = 0; i < ancestryIds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(ancestryIds[i])).append('"');
        }
        sb.append(']');
        sb.append(",\"styleSourceIds\":").append(intArrayJson(styleSourceIds));
        sb.append(",\"bounds\":").append(boundsJson(bounds));
        if (issue != null) sb.append(",\"issue\":\"").append(escape(issue)).append("\"");
        sb.append('}');
        return sb.toString();
    }

    static String intArrayJson(int[] ids) {
        if (ids == null) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    static String boundsJson(double[] b) {
        if (b == null || b.length < 4) return "null";
        return "[" + b[0] + "," + b[1] + "," + b[2] + "," + b[3] + "]";
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
