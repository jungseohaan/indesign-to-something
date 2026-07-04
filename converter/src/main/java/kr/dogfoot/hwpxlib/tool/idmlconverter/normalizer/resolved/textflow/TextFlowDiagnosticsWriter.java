package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Writes TextFlow diagnostics next to ownership-plan.jsonl. */
public final class TextFlowDiagnosticsWriter {
    private TextFlowDiagnosticsWriter() {}

    public static void write(ResolvedBuildContext ctx, TextFlowDiagnostics diagnostics) {
        if (ctx == null || ctx.basePath == null || diagnostics == null) return;
        writeLines(ctx.basePath, "text-flow.jsonl", flowLines(diagnostics));
        writeLines(ctx.basePath, "text-flow-warnings.jsonl",
                diagnostics.warnings != null ? diagnostics.warnings : Collections.emptyList());
        IDMLStoryTokenDiagnosticsWriter.write(ctx);
    }

    private static void writeLines(String basePath, String fileName, List<String> lines) {
        try {
            java.nio.file.Path path = Paths.get(basePath, fileName);
            Files.write(path, lines != null ? lines : Collections.emptyList(), StandardCharsets.UTF_8);
            System.err.println("[TextFlowDiagnostics] " + fileName + ": " + path);
        } catch (Exception e) {
            System.err.println("[TextFlowDiagnostics] " + fileName + " write failed: " + e.getMessage());
        }
    }

    private static List<String> flowLines(TextFlowDiagnostics diagnostics) {
        List<String> lines = new ArrayList<>();
        if (diagnostics.flows == null) return lines;
        for (TextFlowDiagnostics.TextFlow flow : diagnostics.flows) {
            lines.add(toJson(flow));
        }
        return lines;
    }

    private static String toJson(TextFlowDiagnostics.TextFlow flow) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"storyId\":\"").append(escape(flow.storyId)).append("\"");
        sb.append(",\"textOwner\":\"").append(escape(flow.textOwner)).append("\"");
        sb.append(",\"textLength\":").append(flow.textLength);
        sb.append(",\"inlineSlotCount\":").append(flow.inlineSlotCount);
        sb.append(",\"ownerTextFrameIds\":").append(stringArray(flow.ownerTextFrameIds));
        sb.append(",\"ownerPageIndexes\":").append(intArray(flow.ownerPageIndexes));
        sb.append(",\"paragraphs\":[");
        for (int i = 0; i < flow.paragraphs.size(); i++) {
            if (i > 0) sb.append(',');
            appendParagraph(sb, flow.paragraphs.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendParagraph(StringBuilder sb, TextFlowDiagnostics.TextFlowParagraph paragraph) {
        sb.append("{\"index\":").append(paragraph.index);
        sb.append(",\"styleName\":\"").append(escape(paragraph.styleName)).append("\"");
        sb.append(",\"justification\":\"").append(escape(paragraph.justification)).append("\"");
        sb.append(",\"runs\":[");
        for (int i = 0; i < paragraph.runs.size(); i++) {
            if (i > 0) sb.append(',');
            appendRun(sb, paragraph.runs.get(i));
        }
        sb.append("]}");
    }

    private static void appendRun(StringBuilder sb, TextFlowDiagnostics.TextFlowRun run) {
        sb.append("{\"index\":").append(run.index);
        sb.append(",\"kind\":\"").append(escape(run.kind)).append("\"");
        if ("INLINE_SLOT".equals(run.kind)) {
            if (run.anchoredObjectId != null) {
                sb.append(",\"anchoredObjectId\":").append(run.anchoredObjectId);
            }
            appendString(sb, "planTextAction", run.planTextAction);
            appendString(sb, "planVisualAction", run.planVisualAction);
            appendString(sb, "planPlacement", run.planPlacement);
            appendString(sb, "planMaterialization", run.planMaterialization);
            appendString(sb, "planReason", run.planReason);
            appendString(sb, "sourceStatus", run.sourceStatus);
            appendString(sb, "sourceType", run.sourceType);
            if (run.sourcePageIndex != null) {
                sb.append(",\"sourcePageIndex\":").append(run.sourcePageIndex);
            }
            appendString(sb, "sourceLayerName", run.sourceLayerName);
            if (run.sourceInline != null) {
                sb.append(",\"sourceInline\":").append(run.sourceInline.booleanValue());
            }
            appendString(sb, "sourceStoryAnchorPlacement", run.sourceStoryAnchorPlacement);
            if (run.sourceHidden != null) {
                sb.append(",\"sourceHidden\":").append(run.sourceHidden.booleanValue());
            }
            appendBounds(sb, "sourceBounds", run.sourceBounds);
        } else {
            appendString(sb, "text", run.text);
            appendString(sb, "fontFamily", run.fontFamily);
            appendNumber(sb, "fontSize", run.fontSize);
            appendString(sb, "fontStyle", run.fontStyle);
            appendString(sb, "fillColor", run.fillColor);
            appendString(sb, "charStyle", run.charStyle);
            appendNumber(sb, "tracking", run.tracking);
            appendNumber(sb, "horizontalScale", run.horizontalScale);
            appendNumber(sb, "baselineShift", run.baselineShift);
        }
        sb.append('}');
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value == null) return;
        sb.append(",\"").append(key).append("\":\"").append(escape(value)).append("\"");
    }

    private static void appendNumber(StringBuilder sb, String key, Double value) {
        if (value == null) return;
        sb.append(",\"").append(key).append("\":").append(value);
    }

    private static void appendBounds(StringBuilder sb, String key, double[] bounds) {
        if (bounds == null || bounds.length < 4) return;
        sb.append(",\"").append(key).append("\":[");
        for (int i = 0; i < bounds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(bounds[i]);
        }
        sb.append(']');
    }

    private static String stringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escape(values.get(i))).append('"');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String intArray(List<Integer> values) {
        StringBuilder sb = new StringBuilder("[");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(values.get(i));
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
