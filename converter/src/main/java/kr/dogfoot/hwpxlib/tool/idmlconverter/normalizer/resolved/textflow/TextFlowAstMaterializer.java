package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
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
            if (appendTextAtoms(ctx, astParagraph, paragraph, textTransformer, runOptions)) {
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
        boolean appended = false;
        if (appendGeneratedPrefixText(ctx, target, paragraph, runOptions)) {
            appended = true;
        }
        for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
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
            for (ASTTextRun run : runs) {
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
