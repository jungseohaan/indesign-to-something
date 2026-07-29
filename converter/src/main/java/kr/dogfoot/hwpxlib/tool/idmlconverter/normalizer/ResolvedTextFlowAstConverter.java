package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
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

    public interface InlineAnchorTextResolver {
        String resolve(ResolvedRun run, ResolvedParagraph paragraph, int runIndex);
    }

    public static final class Options {
        private Function<String, String> colorResolver;
        private Function<String, String> textTransformer;
        private InlineAnchorTextResolver inlineAnchorTextResolver;
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

        public Options inlineAnchorTextResolver(InlineAnchorTextResolver value) {
            this.inlineAnchorTextResolver = value;
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
        boolean arrowRun = BTFontGlyphMap.isBTArrowFont(run.fontFamily())
                || BTFontGlyphMap.isBTArrowFontStyle(run.charStyle());
        if (arrowRun) {
            runText = BTFontGlyphMap.normalizeArrowGlyphText(run.fontFamily(), run.charStyle(), runText);
        }
        // EH 폰트 해킹 글리프가 EH 수식 그룹에 못 들어간 채 일반 텍스트로 남으면
        // raw 라틴 문자(µ ª ù)로 새어 한글에서 깨진다. 모든 resolved 텍스트 경로
        // (본문·테이블 셀·인라인 프레임)가 이 메서드를 거치므로 여기서 공통 디코딩한다.
        runText = kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap
                .decodeStrayGlyphText(runText, run.fontFamily());
        // EH thin space 마커 백틱(`)이 일반 텍스트로 새면 콤마·따옴표처럼 노출된다.
        // 숫자·연산자 사이 백틱만 가는 공백(U+2009)으로 치환(실측: 1단원 1`:`√2, 290`K).
        runText = ASTRunConverter.replaceEHThinSpaceBacktick(runText);
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
        return convertPreparedRunText(runText, arrowRun ? plainArrowStyleRun(run) : run, paragraph, opts);
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

    private static ResolvedRun plainArrowStyleRun(ResolvedRun src) {
        ResolvedRun out = new ResolvedRun();
        out.text(BTFontGlyphMap.ARROW);
        out.fontSize(src.fontSize());
        out.fillColor(src.fillColor());
        out.tracking(src.tracking());
        out.horizontalScale(src.horizontalScale());
        out.verticalScale(src.verticalScale());
        out.underline(src.underline());
        out.strikeThru(src.strikeThru());
        out.type(src.type());
        out.anchoredObjectId(src.anchoredObjectId());
        out.storyAnchorPlacement(src.storyAnchorPlacement());
        return out;
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
        if (resolvedPara.leftIndent() != null) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(resolvedPara.leftIndent()));
        }
        if (resolvedPara.rightIndent() != null) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(resolvedPara.rightIndent()));
        }
        if (resolvedPara.firstLineIndent() != null) {
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
        addGeneratedPrefixRun(para, resolvedPara, options);
        List<ResolvedRun> runs = resolvedPara.runs();
        for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
            ResolvedRun resolvedRun = runs.get(runIndex);
            if (resolvedRun == null) continue;
            if (resolvedRun.isInlineAnchor()) {
                if (options.inlineAnchorTextResolver == null) continue;
                String anchorText = options.inlineAnchorTextResolver.resolve(resolvedRun, resolvedPara, runIndex);
                if (anchorText == null || (options.skipBlankRuns && anchorText.trim().isEmpty())) continue;
                ResolvedRun styleRun = inlineAnchorStyleRun(runs, runIndex, resolvedRun);
                for (ASTTextRun run : convertPreparedRunText(anchorText, styleRun, para, options)) {
                    para.addItem(run);
                }
                continue;
            }
            if (resolvedRun.text() == null) continue;
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

    private static ResolvedRun inlineAnchorStyleRun(List<ResolvedRun> runs, int runIndex, ResolvedRun anchorRun) {
        ResolvedRun fallback = adjacentTextRun(runs, runIndex, -1);
        if (fallback == null) fallback = adjacentTextRun(runs, runIndex, 1);
        ResolvedRun out = new ResolvedRun();
        ResolvedRun primary = anchorRun != null ? anchorRun : fallback;
        if (primary != null) {
            out.fontFamily(primary.fontFamily());
            out.fontSize(primary.fontSize());
            out.fontStyle(primary.fontStyle());
            out.fillColor(primary.fillColor());
            out.charStyle(primary.charStyle());
            out.tracking(primary.tracking());
            out.horizontalScale(primary.horizontalScale());
            out.verticalScale(primary.verticalScale());
            out.baselineShift(primary.baselineShift());
            out.position(primary.position());
            out.underline(primary.underline());
            out.strikeThru(primary.strikeThru());
        }
        if (fallback != null) {
            if (out.fontFamily() == null) out.fontFamily(fallback.fontFamily());
            if (out.fontSize() == null) out.fontSize(fallback.fontSize());
            if (out.fontStyle() == null) out.fontStyle(fallback.fontStyle());
            if (out.fillColor() == null) out.fillColor(fallback.fillColor());
            if (out.charStyle() == null) out.charStyle(fallback.charStyle());
            if (out.tracking() == null) out.tracking(fallback.tracking());
            if (out.horizontalScale() == null) out.horizontalScale(fallback.horizontalScale());
            if (out.verticalScale() == null) out.verticalScale(fallback.verticalScale());
            if (out.baselineShift() == null) out.baselineShift(fallback.baselineShift());
            if (out.position() == null) out.position(fallback.position());
        }
        out.text(anchorRun != null && anchorRun.text() != null ? anchorRun.text() : "");
        return out;
    }

    private static ResolvedRun adjacentTextRun(List<ResolvedRun> runs, int index, int direction) {
        if (runs == null || direction == 0) return null;
        for (int i = index + direction; i >= 0 && i < runs.size(); i += direction) {
            ResolvedRun run = runs.get(i);
            if (run == null || run.isInlineAnchor()) continue;
            if (run.text() == null || run.text().isEmpty()) continue;
            return run;
        }
        return null;
    }

    private static void addGeneratedPrefixRun(
            ASTParagraph para,
            ResolvedParagraph resolvedPara,
            Options options) {
        String prefix = generatedPrefixToInsert(resolvedPara);
        if (prefix == null) return;
        ResolvedRun styleRun = firstVisibleResolvedRun(resolvedPara);
        if (styleRun == null) return;
        for (ASTTextRun run : convertPreparedRunText(prefix, styleRun, para, options)) {
            para.addItem(run);
        }
    }

    public static String generatedPrefixToInsert(ResolvedParagraph paragraph) {
        if (paragraph == null) return null;
        String prefix = paragraph.generatedPrefixText();
        if (prefix == null || prefix.trim().isEmpty()) return null;
        String normalizedPrefix = normalizeGeneratedPrefixComparable(prefix);
        if (normalizedPrefix.isEmpty()) return null;
        String paragraphText = resolvedParagraphText(paragraph);
        String normalizedParagraphText = normalizeGeneratedPrefixComparable(paragraphText);
        if (normalizedParagraphText.startsWith(normalizedPrefix)) return null;
        return prefix;
    }

    public static ResolvedRun firstVisibleResolvedRun(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return null;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || run.isInlineAnchor() || run.text() == null) continue;
            String text = normalizeGeneratedPrefixComparable(run.text());
            if (!text.isEmpty()) return run;
        }
        return null;
    }

    private static String resolvedParagraphText(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || run.isInlineAnchor() || run.text() == null) continue;
            sb.append(run.text());
        }
        return sb.toString();
    }

    private static String normalizeGeneratedPrefixComparable(String text) {
        if (text == null) return "";
        return text
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
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
