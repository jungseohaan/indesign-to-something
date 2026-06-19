package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6.BackgroundInjector;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 visual executor: 얇은 라인-추적 visual을 floating PNG 대신 HWPX 텍스트
 * 강조(character shading)로 흡수한다. ABSORB_TEXT_STYLE visual action에 해당한다.
 *
 * <p>SPEC-036: BackgroundInjector(Phase 6)에서 분리한 순수 실행 로직. ownership 판단을
 * 새로 하지 않고, 이미 floating 후보로 들어온 얇은 강조 backdrop을 편집 텍스트에 붙인다.</p>
 */
public final class VisualTextEmphasisAbsorber {

    private VisualTextEmphasisAbsorber() {}

    /**
     * A very thin visual object that tracks one composed text line is better
     * represented as text emphasis than as a floating PNG. This keeps the
     * emphasis attached to editable text when the user edits or reflows it.
     */
    public static boolean tryAbsorbTextEmphasisBackdrop(
            ResolvedBuildContext ctx,
            List<ASTSection> sections,
            RenderedGroup rg,
            double[] rbRaw) {
        if (ctx == null || ctx.resolvedData == null || sections == null || rg == null) return false;
        if (!isAbsorbableTextEmphasisBackdrop(ctx, rg, rbRaw)) {
            return false;
        }

        TextLineMatch match = findBestTextLineMatch(ctx, rg, rbRaw);
        if (match == null || match.tf == null || match.line == null) {
            return false;
        }
        ASTParagraph para = findAstParagraphForLine(ctx, sections, rg.pageIndex(), match.tf, match.line);
        if (para == null) {
            return false;
        }

        byte[] png = BackgroundInjector.loadPng(ctx, rg);
        String color = inferEmphasisColorFromPng(png);
        if (color == null) {
            return false;
        }

        return applyCharacterShadeToComposedLine(para, match.line, color);
    }

    private static boolean applyCharacterShadeToComposedLine(
            ASTParagraph para,
            ResolvedTextFrame.ComposedLine line,
            String color) {
        if (para == null || line == null || color == null || color.isEmpty()) return false;
        String lineText = line.text();
        if (lineText == null || lineText.isEmpty()) return false;

        TextOffsetMap paraMap = buildParagraphCompactTextMap(para);
        TextOffsetMap lineMap = buildCompactTextMap(lineText);
        if (paraMap.compact.isEmpty() || lineMap.compact.isEmpty()) return false;

        int compactStart = paraMap.compact.indexOf(lineMap.compact);
        if (compactStart < 0) {
            // Some composed-line strings include paragraph-leading inline anchors
            // or layout-only punctuation. Try a conservative suffix match before
            // giving up; this still maps to an actual paragraph character span.
            String needle = trimCompactNeedle(lineMap.compact);
            compactStart = needle.isEmpty() ? -1 : paraMap.compact.indexOf(needle);
            if (compactStart < 0) return false;
            lineMap = buildCompactTextMap(needle);
        }

        int compactEnd = compactStart + lineMap.compact.length();
        if (compactStart < 0 || compactEnd > paraMap.originalOffsetsAfterCompactChars.length) return false;
        int originalStart = paraMap.originalOffsetsAfterCompactChars[compactStart] - 1;
        int originalEnd = paraMap.originalOffsetsAfterCompactChars[compactEnd - 1];
        if (originalStart < 0 || originalEnd <= originalStart) return false;
        return shadeParagraphTextRange(para, originalStart, originalEnd, color);
    }

    private static String trimCompactNeedle(String compact) {
        if (compact == null) return "";
        String result = compact;
        while (result.length() > 2 && isDecorativeLinePrefix(result.charAt(0))) {
            result = result.substring(1);
        }
        return result;
    }

    private static boolean isDecorativeLinePrefix(char ch) {
        return ch == '\uFFFC' || ch == '•' || ch == '●' || ch == '·';
    }

    private static final class TextOffsetMap {
        final String compact;
        final int[] originalOffsetsAfterCompactChars;

        TextOffsetMap(String compact, int[] originalOffsetsAfterCompactChars) {
            this.compact = compact;
            this.originalOffsetsAfterCompactChars = originalOffsetsAfterCompactChars;
        }
    }

    private static TextOffsetMap buildParagraphCompactTextMap(ASTParagraph para) {
        StringBuilder original = new StringBuilder();
        if (para != null && para.items() != null) {
            for (ASTInlineItem item : para.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) original.append(text);
                }
            }
        }
        return buildCompactTextMap(original.toString());
    }

    private static TextOffsetMap buildCompactTextMap(String text) {
        if (text == null || text.isEmpty()) return new TextOffsetMap("", new int[0]);
        StringBuilder compact = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isLayoutControlForEmphasisMatch(ch) || Character.isWhitespace(ch)) continue;
            compact.append(ch);
            offsets.add(i + 1);
        }
        int[] resultOffsets = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) resultOffsets[i] = offsets.get(i);
        return new TextOffsetMap(compact.toString(), resultOffsets);
    }

    private static boolean isLayoutControlForEmphasisMatch(char ch) {
        return ch == '\uFFFC'
                || ch == '\u0003'
                || ch == '\u0007'
                || ch == '\u0008'
                || ch == '\u0016'
                || ch == '\u0018'
                || ch == '\r'
                || ch == '\n';
    }

    private static boolean shadeParagraphTextRange(
            ASTParagraph para,
            int originalStart,
            int originalEnd,
            String color) {
        if (para == null || para.items() == null || originalEnd <= originalStart) return false;
        List<ASTInlineItem> rebuilt = new ArrayList<>();
        int cursor = 0;
        boolean changed = false;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) {
                rebuilt.add(item);
                continue;
            }
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            int len = text != null ? text.length() : 0;
            int runStart = cursor;
            int runEnd = cursor + len;
            cursor = runEnd;
            if (len == 0 || runEnd <= originalStart || runStart >= originalEnd) {
                rebuilt.add(item);
                continue;
            }

            int localStart = Math.max(0, originalStart - runStart);
            int localEnd = Math.min(len, originalEnd - runStart);
            if (localStart > 0) {
                rebuilt.add(copyTextRun(run, text.substring(0, localStart)));
            }
            ASTTextRun shaded = copyTextRun(run, text.substring(localStart, localEnd));
            shaded.shadeColor(color);
            rebuilt.add(shaded);
            if (localEnd < len) {
                rebuilt.add(copyTextRun(run, text.substring(localEnd)));
            }
            changed = true;
        }
        if (changed) {
            para.items().clear();
            para.items().addAll(rebuilt);
        }
        return changed;
    }

    private static ASTTextRun copyTextRun(ASTTextRun source, String text) {
        ASTTextRun copy = new ASTTextRun();
        copy.characterStyleRef(source.characterStyleRef());
        copy.text(text);
        copy.fontFamily(source.fontFamily());
        copy.fontStyle(source.fontStyle());
        copy.fontSizeHwpunits(source.fontSizeHwpunits());
        copy.textColor(source.textColor());
        copy.shadeColor(source.shadeColor());
        copy.letterSpacing(source.letterSpacing());
        copy.subscript(source.subscript());
        copy.superscript(source.superscript());
        copy.grepMathFont(source.grepMathFont());
        copy.underline(source.underline());
        copy.underlineColor(source.underlineColor());
        copy.underlineShape(source.underlineShape());
        copy.strikeThrough(source.strikeThrough());
        copy.horizontalScale(source.horizontalScale());
        copy.verticalScale(source.verticalScale());
        copy.baselineShift(source.baselineShift());
        copy.grepStyleApplied(source.grepStyleApplied());
        return copy;
    }

    private static boolean isAbsorbableTextEmphasisBackdrop(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double[] rbRaw) {
        if (rbRaw == null || rbRaw.length < 4) return false;
        if (!BackgroundInjector.isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
        if (plan == null || plan.visualAction != VisualAction.ABSORB_TEXT_STYLE) return false;

        double w = rbRaw[3] - rbRaw[1];
        double h = rbRaw[2] - rbRaw[0];
        if (w < 25.0 || h < 2.5 || h > 18.0 || w / h < 3.0) return false;

        TextLineMatch match = findBestTextLineMatch(ctx, rg, rbRaw);
        if (match == null || match.line == null || match.line.bounds() == null) return false;
        double[] lb = match.line.bounds();
        double lineH = Math.max(0.1, lb[2] - lb[0]);
        double rawOverlap = BackgroundInjector.overlapArea(rbRaw, lb);
        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        double scaledOverlap = BackgroundInjector.overlapArea(scaledRb, lb);
        double comparableH = scaledOverlap > rawOverlap ? h * ctx.scaleFactor : h;
        if (comparableH > Math.max(6.5, lineH * 1.35)) return false;
        if (countMatchedLineOverlaps(ctx, rg, rbRaw, match.tf) > 1) return false;
        double overlapRatio = Math.max(rawOverlap, scaledOverlap)
                / Math.max(0.1, BackgroundInjector.area(lb));
        if (overlapRatio < 0.18) return false;

        // A true underline is usually much thinner than the text line. Keep it in
        // the underline path rather than converting it to character shading.
        return h >= Math.max(2.5, lineH * 0.25);
    }

    private static int countMatchedLineOverlaps(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double[] rbRaw,
            ResolvedTextFrame tf) {
        if (ctx == null || rg == null || rbRaw == null || tf == null) return 0;
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines == null || lines.isEmpty()) return 0;
        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        int count = 0;
        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            double[] lb = line.bounds();
            double lineArea = BackgroundInjector.area(lb);
            if (lineArea <= 0.0) continue;
            double overlap = Math.max(BackgroundInjector.overlapArea(rbRaw, lb),
                    BackgroundInjector.overlapArea(scaledRb, lb));
            if (overlap / lineArea >= 0.12) {
                count++;
            }
        }
        return count;
    }

    private static final class TextLineMatch {
        ResolvedTextFrame tf;
        ResolvedTextFrame.ComposedLine line;
        double score;
    }

    private static TextLineMatch findBestTextLineMatch(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double[] rbRaw) {
        if (ctx == null || ctx.resolvedData == null || rg == null || rbRaw == null) return null;
        TextLineMatch best = null;
        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (!BackgroundInjector.hasSemanticText(tf)) continue;
            List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
            if (lines == null || lines.isEmpty()) continue;
            for (ResolvedTextFrame.ComposedLine line : lines) {
                if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
                double[] lb = line.bounds();
                double lineArea = BackgroundInjector.area(lb);
                if (lineArea <= 0) continue;
                double rawOverlap = BackgroundInjector.overlapArea(rbRaw, lb);
                double scaledOverlap = BackgroundInjector.overlapArea(scaledRb, lb);
                double overlap = Math.max(rawOverlap, scaledOverlap);
                if (overlap <= 0) continue;
                double score = overlap / lineArea;
                if (best == null || score > best.score) {
                    best = new TextLineMatch();
                    best.tf = tf;
                    best.line = line;
                    best.score = score;
                }
            }
        }
        return best;
    }

    private static ASTParagraph findAstParagraphForLine(
            ResolvedBuildContext ctx,
            List<ASTSection> sections,
            int resolvedPageIndex,
            ResolvedTextFrame tf,
            ResolvedTextFrame.ComposedLine line) {
        int pageIdx = ctx.toSectionIndex.applyAsInt(resolvedPageIndex);
        if (pageIdx < 0 || pageIdx >= sections.size()) return null;
        ASTSection section = sections.get(pageIdx);
        if (section == null || section.blocks() == null) return null;

        String storyId = tf.storyId();
        String tfId = tf.id();
        int paraIndex = Math.max(0, line.paraIndex());
        ASTParagraph fallback = null;
        String lineCompact = buildCompactTextMap(line.text()).compact;

        // Best path: use the actual originating TextFrame block. Story IDs can
        // diverge between resolved fallback and IDML-loaded stories, but the
        // source frame id remains stable.
        if (tfId != null) {
            for (ASTBlock block : section.blocks()) {
                if (!(block instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfBlock = (ASTTextFrameBlock) block;
                String blockDomId = ParagraphTextHelpers.domIdFromSourceId(tfBlock.sourceId());
                if (!tfId.equals(blockDomId)) continue;
                List<ASTParagraph> paras = tfBlock.paragraphs();
                if (paras == null || paras.isEmpty()) continue;
                if (paraIndex < paras.size()
                        && paragraphContainsCompactLine(paras.get(paraIndex), lineCompact)) {
                    return paras.get(paraIndex);
                }
                for (ASTParagraph para : paras) {
                    if (paragraphContainsCompactLine(para, lineCompact)) {
                        return para;
                    }
                }
                if (lineCompact.isEmpty()) {
                    return paras.get(Math.min(paras.size() - 1, 0));
                }
            }
        }

        for (ASTBlock block : section.blocks()) {
            if (!(block instanceof ASTTextFrameBlock)) continue;
            ASTTextFrameBlock tfBlock = (ASTTextFrameBlock) block;
            if (storyId != null && !storyId.equals(tfBlock.storyId())) continue;
            List<ASTParagraph> paras = tfBlock.paragraphs();
            if (paras == null || paras.isEmpty()) continue;
            if (paraIndex < paras.size()
                    && paragraphContainsCompactLine(paras.get(paraIndex), lineCompact)) {
                return paras.get(paraIndex);
            }
            for (ASTParagraph para : paras) {
                if (paragraphContainsCompactLine(para, lineCompact)) {
                    return para;
                }
            }
            if (fallback == null) fallback = paras.get(Math.min(paras.size() - 1, 0));
        }
        if (fallback != null && lineCompact.isEmpty()) return fallback;

        // Last fallback: match by composed line text. This is intentionally
        // scoped to the same page/section so it cannot steal unrelated stories.
        if (lineCompact.isEmpty()) return null;
        for (ASTBlock block : section.blocks()) {
            if (!(block instanceof ASTTextFrameBlock)) continue;
            ASTTextFrameBlock tfBlock = (ASTTextFrameBlock) block;
            List<ASTParagraph> paras = tfBlock.paragraphs();
            if (paras == null || paras.isEmpty()) continue;
            for (ASTParagraph para : paras) {
                if (para == null) continue;
                String paraCompact = buildParagraphCompactTextMap(para).compact;
                if (compactParagraphContainsLine(paraCompact, lineCompact)) {
                    return para;
                }
            }
        }
        return null;
    }

    private static boolean paragraphContainsCompactLine(ASTParagraph para, String lineCompact) {
        if (para == null || lineCompact == null || lineCompact.isEmpty()) return false;
        String paraCompact = buildParagraphCompactTextMap(para).compact;
        return compactParagraphContainsLine(paraCompact, lineCompact);
    }

    private static boolean compactParagraphContainsLine(String paraCompact, String lineCompact) {
        if (paraCompact == null || lineCompact == null || paraCompact.isEmpty() || lineCompact.isEmpty()) {
            return false;
        }
        if (paraCompact.contains(lineCompact)) return true;
        String needle = trimCompactNeedle(lineCompact);
        return !needle.isEmpty() && paraCompact.contains(needle);
    }

    private static String inferEmphasisColorFromPng(byte[] png) {
        if (png == null || png.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img == null) return null;
            long r = 0, g = 0, b = 0, count = 0;
            int stepX = Math.max(1, img.getWidth() / 160);
            int stepY = Math.max(1, img.getHeight() / 80);
            for (int y = 0; y < img.getHeight(); y += stepY) {
                for (int x = 0; x < img.getWidth(); x += stepX) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xff;
                    if (a < 12) continue;
                    int rr = (argb >>> 16) & 0xff;
                    int gg = (argb >>> 8) & 0xff;
                    int bb = argb & 0xff;
                    // Approximate transparent PNG compositing on white paper.
                    rr = (rr * a + 255 * (255 - a)) / 255;
                    gg = (gg * a + 255 * (255 - a)) / 255;
                    bb = (bb * a + 255 * (255 - a)) / 255;
                    if (rr > 248 && gg > 248 && bb > 248) continue;
                    r += rr;
                    g += gg;
                    b += bb;
                    count++;
                }
            }
            img.flush();
            if (count == 0) return null;
            return String.format("#%02X%02X%02X",
                    (int) Math.round((double) r / count),
                    (int) Math.round((double) g / count),
                    (int) Math.round((double) b / count));
        } catch (Exception ignored) {
            return null;
        }
    }
}
