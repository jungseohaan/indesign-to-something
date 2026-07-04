package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Writes the IDML-native story token stream used as structural truth for inline slots. */
public final class IDMLStoryTokenDiagnosticsWriter {
    private static final char OBJECT_REPLACEMENT = '\uFFFC';

    private IDMLStoryTokenDiagnosticsWriter() {}

    public static void write(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.basePath == null || ctx.resolvedData == null || ctx.loadIDMLStory == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<ResolvedStory> stories = new ArrayList<>(ctx.resolvedData.stories());
        stories.sort(Comparator.comparing(s -> safe(s.id()), IDMLStoryTokenDiagnosticsWriter::compareStoryIds));
        for (ResolvedStory resolvedStory : stories) {
            if (resolvedStory == null || resolvedStory.id() == null) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(resolvedStory.id());
            if (idmlStory == null) continue;
            lines.addAll(storyLines(resolvedStory.id(), idmlStory));
        }
        writeLines(ctx.basePath, lines);
    }

    private static List<String> storyLines(String resolvedStoryId, IDMLStory story) {
        List<String> lines = new ArrayList<>();
        List<IDMLParagraph> paragraphs = story.paragraphs();
        if (paragraphs == null) return lines;
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.size(); paragraphIndex++) {
            IDMLParagraph paragraph = paragraphs.get(paragraphIndex);
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            int tokenIndex = 0;
            for (int runIndex = 0; runIndex < paragraph.characterRuns().size(); runIndex++) {
                IDMLCharacterRun run = paragraph.characterRuns().get(runIndex);
                if (run == null) continue;
                String content = run.content() != null ? run.content() : "";
                int anchorCursor = 0;
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    char c = content.charAt(i);
                    if (c == OBJECT_REPLACEMENT) {
                        if (text.length() > 0) {
                            lines.add(textLine(resolvedStoryId, story.selfId(), paragraphIndex,
                                    runIndex, tokenIndex++, text.toString(), run));
                            text.setLength(0);
                        }
                        IDMLCharacterRun.InlineAnchor anchor = anchorAt(run, anchorCursor++);
                        lines.add(inlineLine(resolvedStoryId, story.selfId(), paragraphIndex,
                                runIndex, tokenIndex++, anchor, run));
                    } else {
                        text.append(c);
                    }
                }
                if (text.length() > 0 || content.isEmpty()) {
                    lines.add(textLine(resolvedStoryId, story.selfId(), paragraphIndex,
                            runIndex, tokenIndex++, text.toString(), run));
                }
            }
        }
        return lines;
    }

    private static IDMLCharacterRun.InlineAnchor anchorAt(IDMLCharacterRun run, int index) {
        if (run.inlineAnchors() == null || index < 0 || index >= run.inlineAnchors().size()) {
            return null;
        }
        return run.inlineAnchors().get(index);
    }

    private static String textLine(String resolvedStoryId, String idmlStoryId, int paragraphIndex,
                                   int runIndex, int tokenIndex, String text, IDMLCharacterRun run) {
        StringBuilder sb = prefix(resolvedStoryId, idmlStoryId, paragraphIndex, runIndex, tokenIndex, "TEXT");
        appendString(sb, "text", text);
        appendString(sb, "charStyle", run.appliedCharacterStyle());
        appendString(sb, "fontFamily", run.fontFamily());
        appendNumber(sb, "fontSize", run.fontSize());
        appendString(sb, "fontStyle", run.fontStyle());
        sb.append('}');
        return sb.toString();
    }

    private static String inlineLine(String resolvedStoryId, String idmlStoryId, int paragraphIndex,
                                     int runIndex, int tokenIndex,
                                     IDMLCharacterRun.InlineAnchor anchor,
                                     IDMLCharacterRun run) {
        StringBuilder sb = prefix(resolvedStoryId, idmlStoryId, paragraphIndex, runIndex, tokenIndex, "INLINE_SLOT");
        if (anchor != null) {
            appendString(sb, "anchorType", anchor.type() != null ? anchor.type().name() : null);
            String sourceId = anchorSourceId(anchor, run);
            appendString(sb, "sourceId", sourceId);
            Integer sourceObjectId = parseIdmlNumericId(sourceId);
            if (sourceObjectId != null) {
                sb.append(",\"sourceObjectId\":").append(sourceObjectId);
            }
        } else {
            appendString(sb, "anchorStatus", "MISSING_INLINE_ANCHOR_METADATA");
        }
        sb.append('}');
        return sb.toString();
    }

    private static String anchorSourceId(IDMLCharacterRun.InlineAnchor anchor, IDMLCharacterRun run) {
        if (anchor == null || run == null) return null;
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (run.inlineFrames() == null || anchor.index() < 0 || anchor.index() >= run.inlineFrames().size()) {
                return null;
            }
            IDMLTextFrame frame = run.inlineFrames().get(anchor.index());
            return frame != null ? frame.selfId() : null;
        }
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
            if (run.inlineGraphics() == null || anchor.index() < 0 || anchor.index() >= run.inlineGraphics().size()) {
                return null;
            }
            IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
            return graphic != null ? graphic.selfId() : null;
        }
        return null;
    }

    private static StringBuilder prefix(String resolvedStoryId, String idmlStoryId, int paragraphIndex,
                                        int runIndex, int tokenIndex, String kind) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"resolvedStoryId\":\"").append(escape(resolvedStoryId)).append("\"");
        appendString(sb, "idmlStoryId", idmlStoryId);
        sb.append(",\"paragraphIndex\":").append(paragraphIndex);
        sb.append(",\"runIndex\":").append(runIndex);
        sb.append(",\"tokenIndex\":").append(tokenIndex);
        appendString(sb, "kind", kind);
        return sb;
    }

    private static void writeLines(String basePath, List<String> lines) {
        try {
            java.nio.file.Path path = Paths.get(basePath, "idml-story-tokens.jsonl");
            Files.write(path, lines, StandardCharsets.UTF_8);
            System.err.println("[TextFlowDiagnostics] idml-story-tokens.jsonl: " + path);
        } catch (Exception e) {
            System.err.println("[TextFlowDiagnostics] idml-story-tokens.jsonl write failed: " + e.getMessage());
        }
    }

    private static Integer parseIdmlNumericId(String id) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'u') return null;
        try {
            return Integer.parseInt(id.substring(1), 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int compareStoryIds(String a, String b) {
        Long na = parseLong(a);
        Long nb = parseLong(b);
        if (na != null && nb != null) {
            int numeric = na.compareTo(nb);
            return numeric != 0 ? numeric : a.compareTo(b);
        }
        if (na != null) return -1;
        if (nb != null) return 1;
        return a.compareTo(b);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value == null) return;
        sb.append(",\"").append(key).append("\":\"").append(escape(value)).append("\"");
    }

    private static void appendNumber(StringBuilder sb, String key, Double value) {
        if (value == null) return;
        sb.append(",\"").append(key).append("\":").append(value);
    }

    private static String safe(String value) {
        return value != null ? value : "";
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
