package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
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
            if (appendTextAtoms(ctx, astParagraph, paragraph, textTransformer, runOptions, inlineAtomResolver)) {
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
        boolean appended = false;
        if (appendGeneratedPrefixText(ctx, target, paragraph, runOptions)) {
            appended = true;
        }
        for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
            if (atom instanceof TextFlowDocument.InlineSlotAtom) {
                if (inlineAtomResolver == null) continue;
                List<ASTInlineItem> items = inlineAtomResolver.resolve((TextFlowDocument.InlineSlotAtom) atom);
                if (items == null || items.isEmpty()) continue;
                for (ASTInlineItem item : items) {
                    if (item == null) continue;
                    target.addItem(item);
                    appended = true;
                }
                continue;
            }
            if (!(atom instanceof TextFlowDocument.TextAtom)) continue;
            TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
            String text = textAtom.text;
            if (textTransformer != null) {
                text = textTransformer.apply(text);
            }
            if (text == null || text.trim().isEmpty()) continue;

            List<ASTTextRun> runs;
            if (textAtom.sourceRun != null) {
                runs = ResolvedTextFlowAstConverter.convertRunText(
                        text,
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
                runs = ResolvedTextFlowAstConverter.convertSyntheticText(text, null, target);
            }

            // 화살표 글리프(@C / ?C / C)를 실제 화살표로 치환한다.
            //
            // 이 경로(TextFlow → AST)는 IDML 런을 거치지 않아 StoryLoader 의
            // isStandaloneBtArrowGlyphRun 분기를 타지 못한다. sourceRun 의 폰트가
            // 살아있는 마지막 지점이 여기다. 놓치면 하류에서는 폰트가 이미 벗겨져
            // 있어(함초롬돋움) 화살표인지 알 수 없고, 반응식을 표로 조판한 셀에
            // "@C" 가 그대로 노출된다.
            //
            // 글리프 코드는 문서마다 다르므로(관측: "@C", "?C", 접두문자 없는 "C")
            // 텍스트가 아니라 폰트로 판정한다.
            boolean arrowRun = textAtom.sourceRun != null
                    && BTFontGlyphMap.isBTArrowFont(textAtom.sourceRun.fontFamily());

            for (ASTTextRun run : runs) {
                if (arrowRun) {
                    run.text("→");
                    run.fontFamily(null);
                    run.fontStyle(null);
                    run.grepMathFont(false);
                    run.subscript(false);
                    run.superscript(false);
                }
                target.addItem(run);
                appended = true;
            }
        }
        return appended;
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
        List<ASTTextRun> runs = ResolvedTextFlowAstConverter.convertRunText(
                prefix,
                styleRun,
                target,
                runOptions != null
                        ? runOptions
                        : ResolvedTextFlowAstConverter.options()
                                .colorResolver(color -> ctx.resolvedData != null
                                        ? ctx.resolvedData.resolveColorHex(color)
                                        : color)
                                .truncateAtParagraphBreak(false));
        for (ASTTextRun run : runs) {
            target.addItem(run);
        }
        return !runs.isEmpty();
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
