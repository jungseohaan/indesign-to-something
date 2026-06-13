package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Single source-to-AST run segment gateway.
 *
 * <p>All source text paths should split control characters and object
 * replacement characters here before style is applied. This keeps text frames,
 * inline text frames, and table cells from inventing incompatible run
 * boundaries.</p>
 */
public final class TextRunSegmenter {
    private TextRunSegmenter() {}

    public static final class Result {
        private final List<ASTTextRun> runs;
        private final boolean stopAfterRun;

        private Result(List<ASTTextRun> runs, boolean stopAfterRun) {
            this.runs = runs;
            this.stopAfterRun = stopAfterRun;
        }

        public List<ASTTextRun> runs() {
            return runs;
        }

        public boolean stopAfterRun() {
            return stopAfterRun;
        }
    }

    public static List<ASTTextRun> fromIdmlRun(IDMLCharacterRun run,
                                               String paragraphStyleRef,
                                               StylePropertyResolver styleResolver,
                                               ResolvedData resolvedData,
                                               boolean hasTabStops,
                                               boolean preserveUnderlineBlank) {
        if (run == null || run.content() == null || run.content().isEmpty()) {
            return Collections.emptyList();
        }
        List<ASTTextRun> out = new ArrayList<>();
        for (String segment : splitObjectReplacement(run.content())) {
            String text = TextControlNormalizer.normalize(
                    segment,
                    hasTabStops,
                    preserveUnderlineBlank,
                    true);
            if (text.isEmpty()) continue;
            out.add(TextStyleApplicator.createStyledRun(
                    text, run, paragraphStyleRef, styleResolver, resolvedData));
        }
        return out;
    }

    public static Result fromResolvedRun(ResolvedRun run,
                                         ColorResolver colorResolver,
                                         boolean hasTabStops,
                                         boolean preserveUnderlineBlank,
                                         boolean truncateAtParagraphBreak) {
        if (run == null || run.isInlineAnchor() || run.text() == null || run.text().isEmpty()) {
            return new Result(Collections.emptyList(), false);
        }

        String text = run.text();
        boolean stopAfterRun = false;
        if (truncateAtParagraphBreak) {
            int crIdx = text.indexOf('\r');
            if (crIdx >= 0) {
                text = text.substring(0, crIdx);
                stopAfterRun = true;
            }
        }

        List<ASTTextRun> out = new ArrayList<>();
        out.addAll(fromResolvedText(
                text,
                run,
                colorResolver != null ? colorResolver::resolve : null,
                hasTabStops,
                preserveUnderlineBlank,
                null));
        return new Result(out, stopAfterRun);
    }

    public static List<ASTTextRun> fromResolvedText(String text,
                                                    ResolvedRun run,
                                                    Function<String, String> colorResolver,
                                                    boolean hasTabStops,
                                                    boolean preserveUnderlineBlank,
                                                    TextStyleApplicator.ResolvedStyleOptions styleOptions) {
        if (run == null || run.isInlineAnchor() || text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<ASTTextRun> out = new ArrayList<>();
        for (String segment : splitObjectReplacement(text)) {
            String normalized = TextControlNormalizer.normalize(
                    segment,
                    hasTabStops,
                    preserveUnderlineBlank,
                    true);
            if (normalized.isEmpty()) continue;
            ASTTextRun astRun = new ASTTextRun();
            astRun.text(normalized);
            TextStyleApplicator.applyResolvedStyle(astRun, run, colorResolver, styleOptions);
            out.add(astRun);
        }
        return out;
    }

    public static List<ASTTextRun> fromSyntheticText(String text,
                                                     TextStyleApplicator.ExplicitStyle style,
                                                     boolean hasTabStops) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<ASTTextRun> out = new ArrayList<>();
        for (String segment : splitObjectReplacement(text)) {
            String normalized = TextControlNormalizer.normalize(
                    segment,
                    hasTabStops,
                    false,
                    true);
            if (normalized.isEmpty()) continue;
            ASTTextRun astRun = new ASTTextRun();
            astRun.text(normalized);
            TextStyleApplicator.applyExplicitStyle(astRun, style);
            out.add(astRun);
        }
        return out;
    }

    private static List<String> splitObjectReplacement(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<String> segments = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC') {
                if (buffer.length() > 0) {
                    segments.add(buffer.toString());
                    buffer.setLength(0);
                }
                continue;
            }
            buffer.append(ch);
        }
        if (buffer.length() > 0) {
            segments.add(buffer.toString());
        }
        return segments;
    }
}
