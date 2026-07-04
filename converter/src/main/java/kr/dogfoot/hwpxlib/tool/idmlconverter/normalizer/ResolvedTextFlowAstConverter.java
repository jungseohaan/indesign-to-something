package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTabStop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Converts resolved paragraph/run TextFlow into AST paragraphs. */
public final class ResolvedTextFlowAstConverter {
    private ResolvedTextFlowAstConverter() {}

    public static final class Options {
        private Function<String, String> colorResolver;
        private Function<String, String> textTransformer;
        private String defaultAlignment;
        private TextStyleApplicator.ResolvedStyleOptions styleOptions;
        private boolean copyTabStops;
        private boolean preserveUnderlineBlank;
        private boolean truncateAtParagraphBreak = true;
        private boolean skipBlankRuns;
        private boolean skipEmptyParagraphs;

        public Options colorResolver(Function<String, String> value) {
            this.colorResolver = value;
            return this;
        }

        public Options textTransformer(Function<String, String> value) {
            this.textTransformer = value;
            return this;
        }

        public Options defaultAlignment(String value) {
            this.defaultAlignment = value;
            return this;
        }

        public Options styleOptions(TextStyleApplicator.ResolvedStyleOptions value) {
            this.styleOptions = value;
            return this;
        }

        public Options copyTabStops(boolean value) {
            this.copyTabStops = value;
            return this;
        }

        public Options preserveUnderlineBlank(boolean value) {
            this.preserveUnderlineBlank = value;
            return this;
        }

        public Options truncateAtParagraphBreak(boolean value) {
            this.truncateAtParagraphBreak = value;
            return this;
        }

        public Options skipBlankRuns(boolean value) {
            this.skipBlankRuns = value;
            return this;
        }

        public Options skipEmptyParagraphs(boolean value) {
            this.skipEmptyParagraphs = value;
            return this;
        }
    }

    public static Options options() {
        return new Options();
    }

    public static List<ASTParagraph> convertStory(ResolvedStory story, Options options) {
        List<ASTParagraph> paragraphs = new ArrayList<>();
        if (story == null || story.paragraphs() == null) return paragraphs;
        Options opts = options != null ? options : options();
        for (ResolvedParagraph resolvedPara : story.paragraphs()) {
            if (resolvedPara == null) continue;
            ASTParagraph para = convertParagraph(resolvedPara, opts);
            if (opts.skipEmptyParagraphs && !hasVisibleText(para)) continue;
            paragraphs.add(para);
        }
        return paragraphs;
    }

    public static List<ASTTextRun> convertRunText(String text,
                                                   ResolvedRun run,
                                                   ASTParagraph paragraph,
                                                   Options options) {
        if (run == null || text == null) return new ArrayList<>();
        Options opts = options != null ? options : options();
        String runText = text;
        if (opts.truncateAtParagraphBreak) {
            int crIdx = runText.indexOf('\r');
            if (crIdx >= 0) {
                runText = runText.substring(0, crIdx);
            }
        }
        if (opts.textTransformer != null) {
            runText = opts.textTransformer.apply(runText);
            if (runText == null) runText = "";
        }
        if (opts.skipBlankRuns && runText.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return convertPreparedRunText(runText, run, paragraph, opts);
    }

    public static List<ASTTextRun> convertSyntheticText(String text,
                                                        TextStyleApplicator.ExplicitStyle style,
                                                        ASTParagraph paragraph) {
        return TextRunSegmenter.fromSyntheticText(
                text,
                style,
                paragraph != null && paragraph.hasTabStops());
    }

    public static List<ASTTextRun> convertIdmlRun(
            IDMLCharacterRun run,
            String paragraphStyleRef,
            StylePropertyResolver styleResolver,
            ResolvedData resolvedData,
            ASTParagraph paragraph,
            boolean preserveUnderlineBlank) {
        return TextRunSegmenter.fromIdmlRun(
                run,
                paragraphStyleRef,
                styleResolver,
                resolvedData,
                paragraph != null && paragraph.hasTabStops(),
                preserveUnderlineBlank);
    }

    private static List<ASTTextRun> convertPreparedRunText(String text,
                                                           ResolvedRun run,
                                                           ASTParagraph paragraph,
                                                           Options options) {
        return TextRunSegmenter.fromResolvedText(
                text,
                run,
                color -> options.colorResolver != null ? options.colorResolver.apply(color) : color,
                paragraph != null && paragraph.hasTabStops(),
                options.preserveUnderlineBlank,
                options.styleOptions);
    }

    private static ASTParagraph convertParagraph(ResolvedParagraph resolvedPara, Options options) {
        ASTParagraph para = new ASTParagraph();
        if (resolvedPara.styleName() != null) {
            para.paragraphStyleRef(resolvedPara.styleName());
        }
        if (resolvedPara.justification() != null) {
            para.alignment(resolvedPara.justification());
        } else if (options.defaultAlignment != null) {
            para.alignment(options.defaultAlignment);
        }
        Double fixedLeading = resolvedPara.fixedLeading();
        if (fixedLeading != null && fixedLeading > 0) {
            para.lineSpacingType("fixed");
            para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
        }
        if (resolvedPara.spaceBefore() != null && resolvedPara.spaceBefore() > 0) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(resolvedPara.spaceBefore()));
        }
        if (resolvedPara.spaceAfter() != null && resolvedPara.spaceAfter() > 0) {
            para.spaceAfter(CoordinateConverter.pointsToHwpunits(resolvedPara.spaceAfter()));
        }
        if (resolvedPara.leftIndent() != null && resolvedPara.leftIndent() != 0) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(resolvedPara.leftIndent()));
        }
        if (resolvedPara.rightIndent() != null && resolvedPara.rightIndent() != 0) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(resolvedPara.rightIndent()));
        }
        if (resolvedPara.firstLineIndent() != null && resolvedPara.firstLineIndent() != 0) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(resolvedPara.firstLineIndent()));
        }
        if (options.copyTabStops && resolvedPara.hasTabStops()) {
            double leftPt = resolvedPara.leftIndent() != null ? resolvedPara.leftIndent() : 0;
            for (ResolvedTabStop tabStop : resolvedPara.tabStops()) {
                if (tabStop.position() == null || tabStop.position() <= 0) continue;
                double posPt = tabStop.position() - leftPt;
                if (posPt < 0) posPt = 0;
                para.addTabStop(new ASTTabStop(
                        CoordinateConverter.pointsToHwpunits(posPt),
                        ASTStoryConverter.mapTabAlignment(tabStop.alignment()),
                        tabStop.leader()));
            }
        }
        addRuns(para, resolvedPara, options);
        return para;
    }

    private static void addRuns(ASTParagraph para, ResolvedParagraph resolvedPara, Options options) {
        if (resolvedPara.runs() == null) return;
        for (ResolvedRun resolvedRun : resolvedPara.runs()) {
            if (resolvedRun == null || resolvedRun.isInlineAnchor() || resolvedRun.text() == null) continue;
            String text = resolvedRun.text();
            boolean stopAfterRun = false;
            if (options.truncateAtParagraphBreak) {
                int crIdx = text.indexOf('\r');
                if (crIdx >= 0) {
                    text = text.substring(0, crIdx);
                    stopAfterRun = true;
                }
            }
            if (options.textTransformer != null) {
                text = options.textTransformer.apply(text);
                if (text == null) text = "";
            }
            if (options.skipBlankRuns && text.trim().isEmpty()) {
                if (stopAfterRun) break;
                continue;
            }
            for (ASTTextRun run : convertPreparedRunText(text, resolvedRun, para, options)) {
                para.addItem(run);
            }
            if (stopAfterRun) break;
        }
    }

    private static boolean hasVisibleText(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return false;
        for (Object item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && !text.trim().isEmpty()) return true;
        }
        return false;
    }
}
