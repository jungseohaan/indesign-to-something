package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBreak;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedTextFlowAstConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.ParagraphPropertyResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Materializes planner-declared TextFlow units into AST text without re-scanning resolved stories. */
public final class TextFlowAstMaterializer {
    private TextFlowAstMaterializer() {}

    public interface InlineAtomResolver {
        List<ASTInlineItem> resolve(TextFlowDocument.InlineSlotAtom atom);
    }

    public static final class SourceLineBreakLayout {
        public final long indentToHerePosition;
        public final boolean insertTabAfterLineBreak;

        public SourceLineBreakLayout(long indentToHerePosition, boolean insertTabAfterLineBreak) {
            this.indentToHerePosition = indentToHerePosition;
            this.insertTabAfterLineBreak = insertTabAfterLineBreak;
        }

        boolean enabled() {
            return indentToHerePosition > 0 || insertTabAfterLineBreak;
        }
    }

    public static boolean appendFirstVisibleTextRuns(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextFlowUnit unit,
            Function<String, String> textTransformer) {
        if (ctx == null || target == null || unit == null || unit.paragraphs == null) return false;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null || paragraph.atoms == null) continue;
            boolean appended = appendTextAtoms(ctx, target, paragraph, textTransformer);
            if (appended) return true;
        }
        return false;
    }

    public static List<ASTParagraph> convertUnit(
            ResolvedBuildContext ctx,
            TextFlowDocument.TextFlowUnit unit,
            Function<String, String> textTransformer,
            String defaultAlignment) {
        return convertUnit(ctx, unit, textTransformer, defaultAlignment, null);
    }

    public static List<ASTParagraph> convertUnit(
            ResolvedBuildContext ctx,
            TextFlowDocument.TextFlowUnit unit,
            Function<String, String> textTransformer,
            String defaultAlignment,
            ResolvedTextFlowAstConverter.Options runOptions) {
        return convertUnit(ctx, unit, textTransformer, defaultAlignment, runOptions, null);
    }

    public static List<ASTParagraph> convertUnit(
            ResolvedBuildContext ctx,
            TextFlowDocument.TextFlowUnit unit,
            Function<String, String> textTransformer,
            String defaultAlignment,
            ResolvedTextFlowAstConverter.Options runOptions,
            InlineAtomResolver inlineAtomResolver) {
        return convertUnit(ctx, unit, textTransformer, defaultAlignment, runOptions, inlineAtomResolver, null);
    }

    public static List<ASTParagraph> convertUnit(
            ResolvedBuildContext ctx,
            TextFlowDocument.TextFlowUnit unit,
            Function<String, String> textTransformer,
            String defaultAlignment,
            ResolvedTextFlowAstConverter.Options runOptions,
            InlineAtomResolver inlineAtomResolver,
            SourceLineBreakLayout lineBreakLayout) {
        List<ASTParagraph> out = new ArrayList<>();
        if (ctx == null || unit == null || unit.paragraphs == null) return out;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null || paragraph.atoms == null) continue;
            ASTParagraph astParagraph = new ASTParagraph();
            if (paragraph.sourceParagraph != null) {
                ParagraphPropertyResolver.applyResolved(astParagraph, paragraph.sourceParagraph, ctx);
            } else {
                if (paragraph.styleName != null) {
                    astParagraph.paragraphStyleRef(paragraph.styleName);
                }
                String alignment = paragraph.justification != null ? paragraph.justification : defaultAlignment;
                if (alignment != null && !alignment.isEmpty()) {
                    astParagraph.alignment(alignment);
                }
            }
            if (lineBreakLayout != null && lineBreakLayout.indentToHerePosition > 0) {
                astParagraph.indentToHerePosition(lineBreakLayout.indentToHerePosition);
            }
            if (appendTextAtoms(ctx, astParagraph, paragraph, textTransformer, runOptions, inlineAtomResolver, lineBreakLayout)) {
                out.add(astParagraph);
            }
        }
        return out;
    }

    private static boolean appendTextAtoms(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextFlowParagraph paragraph,
            Function<String, String> textTransformer) {
        return appendTextAtoms(ctx, target, paragraph, textTransformer, null);
    }

    private static boolean appendTextAtoms(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextFlowParagraph paragraph,
            Function<String, String> textTransformer,
            ResolvedTextFlowAstConverter.Options runOptions) {
        return appendTextAtoms(ctx, target, paragraph, textTransformer, runOptions, null);
    }

    private static boolean appendTextAtoms(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextFlowParagraph paragraph,
            Function<String, String> textTransformer,
            ResolvedTextFlowAstConverter.Options runOptions,
            InlineAtomResolver inlineAtomResolver) {
        return appendTextAtoms(ctx, target, paragraph, textTransformer, runOptions, inlineAtomResolver, null);
    }

    private static boolean appendTextAtoms(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextFlowParagraph paragraph,
            Function<String, String> textTransformer,
            ResolvedTextFlowAstConverter.Options runOptions,
            InlineAtomResolver inlineAtomResolver,
            SourceLineBreakLayout lineBreakLayout) {
        boolean appended = false;
        if (appendGeneratedPrefixText(ctx, target, paragraph, runOptions)) {
            appended = true;
        }
        boolean previousInlineSlotMaterialized = false;
        for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
            if (atom instanceof TextFlowDocument.InlineSlotAtom) {
                if (inlineAtomResolver == null) {
                    previousInlineSlotMaterialized = false;
                    continue;
                }
                List<ASTInlineItem> items = inlineAtomResolver.resolve((TextFlowDocument.InlineSlotAtom) atom);
                if (items == null || items.isEmpty()) {
                    previousInlineSlotMaterialized = false;
                    continue;
                }
                boolean inlineAppended = false;
                for (ASTInlineItem item : items) {
                    if (item == null) continue;
                    target.addItem(item);
                    appended = true;
                    inlineAppended = true;
                }
                previousInlineSlotMaterialized = inlineAppended;
                continue;
            }
            if (!(atom instanceof TextFlowDocument.TextAtom)) {
                previousInlineSlotMaterialized = false;
                continue;
            }
            TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
            String text = textAtom.text;
            if (previousInlineSlotMaterialized && isInlineSlotReservationSpacer(text)) {
                continue;
            }
            if (textTransformer != null) {
                text = textTransformer.apply(text);
            }
            if (text == null || text.trim().isEmpty()) {
                previousInlineSlotMaterialized = false;
                continue;
            }

            appended |= appendTextAtomRuns(ctx, target, textAtom, text, runOptions, lineBreakLayout);
            previousInlineSlotMaterialized = false;
        }
        return appended;
    }

    private static boolean isInlineSlotReservationSpacer(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasReservationSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\u00A0'
                    || ch == '\u2002'
                    || ch == '\u2003'
                    || ch == '\u2004'
                    || ch == '\u2005'
                    || ch == '\u2006'
                    || ch == '\u2007'
                    || ch == '\u2008'
                    || ch == '\u2009'
                    || ch == '\u200A') {
                hasReservationSpace = true;
                continue;
            }
            if (ch == ' ' || ch == '\t') {
                continue;
            }
            return false;
        }
        return hasReservationSpace;
    }

    private static boolean appendTextAtomRuns(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextAtom textAtom,
            String text,
            ResolvedTextFlowAstConverter.Options runOptions,
            SourceLineBreakLayout lineBreakLayout) {
        if (text == null || text.isEmpty()) return false;
        boolean appended = false;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean atEnd = i == text.length();
            boolean atLineBreak = !atEnd && isSourceLineBreak(text.charAt(i));
            if (!atEnd && !atLineBreak) continue;
            if (i > start) {
                appended |= appendTextAtomSegment(ctx, target, textAtom, text.substring(start, i), runOptions);
            }
            if (atLineBreak) {
                appendLineBreak(target, lineBreakLayout);
                appended = true;
                start = i + 1;
            }
        }
        return appended;
    }

    private static boolean appendTextAtomSegment(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextAtom textAtom,
            String segment,
            ResolvedTextFlowAstConverter.Options runOptions) {
        if (segment == null || segment.trim().isEmpty()) return false;
        List<ASTTextRun> runs;
        if (textAtom.sourceRun != null) {
            runs = ResolvedTextFlowAstConverter.convertRunText(
                    segment,
                    textAtom.sourceRun,
                    target,
                    runOptions != null
                            ? runOptions
                            : ResolvedTextFlowAstConverter.options()
                                    .colorResolver(color -> ctx.resolvedData != null
                                            ? ctx.resolvedData.resolveColorHex(color)
                                            : color)
                                    .truncateAtParagraphBreak(false));
        } else {
            runs = ResolvedTextFlowAstConverter.convertSyntheticText(segment, null, target);
        }

        // 화살표 런: 텍스트는 ResolvedDataReader 가 파싱 직후 이미 "→" 로
        // 정규화했다. 여기서는 폰트만 벗긴다 — BT화살표 폰트를 그대로 두면
        // 한글이 글리프를 렌더링하지 못한다.
        boolean arrowRun = textAtom.sourceRun != null
                && (BTFontGlyphMap.isBTArrowFont(textAtom.sourceRun.fontFamily())
                || BTFontGlyphMap.isBTArrowFontStyle(textAtom.sourceRun.charStyle()));

        boolean appended = false;
        for (ASTTextRun run : runs) {
            if (arrowRun) {
                run.fontFamily(null);
                run.fontStyle(null);
                run.grepMathFont(false);
                run.subscript(false);
                run.superscript(false);
            }
            appendTextRunCoalescing(target, run);
            appended = true;
        }
        return appended;
    }

    private static boolean isSourceLineBreak(char ch) {
        return ch == '\n' || ch == '\r' || ch == '\u2028';
    }

    private static void appendLineBreak(ASTParagraph target, SourceLineBreakLayout lineBreakLayout) {
        if (target == null) return;
        List<ASTInlineItem> items = target.items();
        if (items != null && !items.isEmpty() && items.get(items.size() - 1) instanceof ASTBreak) return;
        target.addItem(new ASTBreak(ASTBreak.BreakType.LINE));
        if (lineBreakLayout != null && lineBreakLayout.insertTabAfterLineBreak) {
            ASTTextRun tab = new ASTTextRun();
            tab.text("\t");
            target.addItem(tab);
        }
    }

    private static boolean appendGeneratedPrefixText(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            TextFlowDocument.TextFlowParagraph paragraph,
            ResolvedTextFlowAstConverter.Options runOptions) {
        if (paragraph == null || target == null) return false;
        String prefix = null;
        ResolvedRun styleRun = null;
        if (paragraph.sourceParagraph != null) {
            prefix = ResolvedTextFlowAstConverter.generatedPrefixToInsert(paragraph.sourceParagraph);
            styleRun = ResolvedTextFlowAstConverter.firstVisibleResolvedRun(paragraph.sourceParagraph);
        }
        if (paragraph.sourceParagraph == null
                && (prefix == null || prefix.trim().isEmpty())
                && paragraph.generatedPrefixText != null) {
            prefix = paragraph.generatedPrefixText;
        }
        if (prefix == null || prefix.trim().isEmpty()) return false;
        if (styleRun == null) {
            styleRun = firstVisibleSourceRun(paragraph);
        }
        if (styleRun == null) return false;
        ResolvedTextFlowAstConverter.Options options = runOptions != null
                ? runOptions
                : ResolvedTextFlowAstConverter.options()
                        .colorResolver(color -> ctx.resolvedData != null
                                ? ctx.resolvedData.resolveColorHex(color)
                                : color)
                        .truncateAtParagraphBreak(false);
        int appendedCount = 0;
        if (paragraph.generatedPrefixMarkerRun != null && !prefix.isEmpty()) {
            int markerEnd = prefix.offsetByCodePoints(0, 1);
            appendedCount += appendPrefixPiece(target, prefix.substring(0, markerEnd),
                    paragraph.generatedPrefixMarkerRun, options);
            if (markerEnd < prefix.length()) {
                ResolvedRun separatorRun = paragraph.generatedPrefixSeparatorRun != null
                        ? paragraph.generatedPrefixSeparatorRun
                        : styleRun;
                appendedCount += appendPrefixPiece(target, prefix.substring(markerEnd),
                        separatorRun, options);
            }
        } else {
            appendedCount += appendPrefixPiece(target, prefix, styleRun, options);
        }
        return appendedCount > 0;
    }

    private static int appendPrefixPiece(
            ASTParagraph target,
            String text,
            ResolvedRun styleRun,
            ResolvedTextFlowAstConverter.Options options) {
        if (target == null || text == null || text.isEmpty() || styleRun == null) return 0;
        List<ASTTextRun> runs = ResolvedTextFlowAstConverter.convertRunText(
                text,
                styleRun,
                target,
                options);
        for (ASTTextRun run : runs) {
            appendTextRunCoalescing(target, run);
        }
        return runs.size();
    }

    private static void appendTextRunCoalescing(ASTParagraph target, ASTTextRun run) {
        if (target == null || run == null) return;
        List<ASTInlineItem> items = target.items();
        if (items == null || items.isEmpty()) {
            target.addItem(run);
            return;
        }
        ASTInlineItem lastItem = items.get(items.size() - 1);
        if (lastItem instanceof ASTTextRun) {
            ASTTextRun previous = (ASTTextRun) lastItem;
            if (canCoalesce(previous, run)) {
                previous.text(previous.text() + run.text());
                return;
            }
        }
        target.addItem(run);
    }

    private static boolean canCoalesce(ASTTextRun a, ASTTextRun b) {
        if (a == null || b == null || a.text() == null || b.text() == null) return false;
        return a.hasSameStyle(b);
    }

    private static ResolvedRun firstVisibleSourceRun(TextFlowDocument.TextFlowParagraph paragraph) {
        if (paragraph == null || paragraph.atoms == null) return null;
        for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
            if (!(atom instanceof TextFlowDocument.TextAtom)) continue;
            TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
            if (textAtom.text == null || textAtom.text.trim().isEmpty()) continue;
            if (textAtom.sourceRun != null) return textAtom.sourceRun;
        }
        return null;
    }
}
