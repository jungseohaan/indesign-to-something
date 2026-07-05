package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.MatchConfidence;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.DoviraSubunitMarkerPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.*;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHGrepFractionConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTMathGrouper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTRunConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedTextFlowAstConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextFlowTabPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.RunPropertyResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextStyleApplicator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TableFrameOwnershipPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowAstMaterializer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;

/**
 * SPEC-013 Stage 9: Phase 3 — Story → Paragraph → Run 변환.
 *
 * <p>{@code ResolvedToASTBuilder}에서 {@code convertStories} + 37개 헬퍼 + {@code StyleContext} /
 * {@code Segment} inner class를 stateless static helper로 발췌. 동작은 1:1 동일하며
 * 모든 인스턴스 상태는 {@link ResolvedBuildContext}를 통해 접근한다.</p>
 */
public final class StoryConverter {

    /** SPEC-016 Phase 2: LOW 매칭 진단용 텍스트 마커. */
    static final String SPEC016_DEBUG_TEXT = System.getProperty("spec016.debug.text");

    static final String BULLET_CHARS = "●•◆◇▶▷■□";

    // IDML/resolved fallback 전환 임계값
    /** resolved 텍스트 길이가 이 값 초과일 때만 IDML 짧음 판정을 적용 */
    private static final int    FALLBACK_RESOLVED_MIN_LEN   = 10;
    /** IDML 텍스트가 resolved의 이 비율 미만이면 IDML 짧음으로 판단 → resolved fallback */
    private static final double FALLBACK_IDML_SHORT_RATIO   = 0.3;
    /** IDML 단락 수가 이 값 이하이면서 resolved가 FALLBACK_RESOLVED_PARA_MIN 이상이면 단락 구조 불일치 */
    private static final int    FALLBACK_IDML_PARA_MAX      = 2;
    /** resolved 단락 수가 이 값 이상이면 단락 구조 불일치로 판단 → resolved fallback */
    private static final int    FALLBACK_RESOLVED_PARA_MIN  = 5;
    /** composed line ink가 선언 font보다 작은 장식/삽화 내부 TF는 ink bounds를 기준으로 run font를 제한한다. */
    private static final double COMPOSED_INK_FONT_CAP_RATIO = 1.15;
    private static final double COMPOSED_INK_MIN_PT = 4.0;

    // scanTextPathStorySubstitutions 전용 — 메서드 호출마다 재컴파일 방지
    private static final Pattern TEXT_FRAME_PATTERN =
            Pattern.compile("<TextFrame\\s+Self=\"u([0-9a-f]+)\"");
    private static final Pattern TEXT_PATH_PATTERN =
            Pattern.compile("<TextPath\\s+Self=\"[^\"]+\"\\s+ParentStory=\"u([0-9a-f]+)\"");

    private StoryConverter() {}

    private static double millis(long nanos) {
        return Math.round(nanos / 10000.0) / 100.0;
    }

    /** ParagraphStyle에서 미리 구한 스타일 속성 (런에서 없을 때 폴백용) */
    static class StyleContext {
        final String fillColor;
        final Double tracking;
        final String fontFamily;
        final Double fontSize;
        final Double horizontalScale;
        final String underlineColor;
        boolean hasTabStops;

        StyleContext(String fillColor, Double tracking, String fontFamily, Double fontSize,
                     Double horizontalScale, String underlineColor) {
            this.fillColor = fillColor;
            this.tracking = tracking;
            this.fontFamily = fontFamily;
            this.fontSize = fontSize;
            this.horizontalScale = horizontalScale;
            this.underlineColor = underlineColor;
        }
    }

    // ═══════════════════════════════════════════════════
    // Phase 3: Story→단락→런 변환
    // ═══════════════════════════════════════════════════

    public static void convertStories(ResolvedBuildContext ctx, List<ASTSection> sections) {
        long aboveLineScanNanos;
        long textPathScanNanos;
        long storyBlockMapNanos;
        long storyLoopNanos;
        long idmlConvertNanos = 0L;
        long fallbackCheckNanos = 0L;
        long resolvedConvertNanos = 0L;
        long distributeNanos = 0L;
        long restoreInlineNanos = 0L;
        long postprocessNanos = 0L;
        long t0 = System.nanoTime();
        collectAboveLineAnchoredIds(ctx);
        aboveLineScanNanos = System.nanoTime() - t0;

        // PRE: Spread XML 에서 TextPath 매핑 (TF id → TextPath storyId) 미리 추출.
        // curved text 가 부모 TF 의 빈 콘텐츠를 채우도록 한다 (직선으로 그대로 표시).
        t0 = System.nanoTime();
        Map<String, String> textPathStorySub = scanTextPathStorySubstitutions(ctx);
        textPathScanNanos = System.nanoTime() - t0;

        // TextFrameBlock에 Story 텍스트 연결
        // storyId → TextFrameBlock 매핑
        t0 = System.nanoTime();
        Map<String, List<ASTTextFrameBlock>> storyToBlocks = new HashMap<>();
        for (ASTSection sec : sections) {
            for (ASTBlock blk : sec.blocks()) {
                if (blk instanceof ASTTextFrameBlock) {
                    ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                    // composedLines 분할처럼 Stage 2가 의도적으로 만든 블록은 유지한다.
                    // 그 외 prebuilt 단락은 frameVisibleText 기반 placeholder일 수 있으므로,
                    // resolved story가 더 풍부한 paragraph/run 구조를 제공하면 Stage 3가 다시 소유한다.
                    if (tfb.paragraphs() != null && !tfb.paragraphs().isEmpty()) {
                        if (shouldRebuildPrebuiltParagraphsFromResolved(ctx, tfb, textPathStorySub)) {
                            tfb.paragraphs().clear();
                        } else {
                            continue;
                        }
                    }
                    String sourceId = tfb.sourceId();
                    if (sourceId == null) {
                        // domId=None TF: sourceId 없지만 Phase 2가 storyId를 직접 설정했으면 사용
                        String directStoryId = tfb.storyId();
                        if (directStoryId != null) {
                            if (isStoryFullyOwnedByIndesignPng(ctx, directStoryId)) continue;
                            storyToBlocks.computeIfAbsent(directStoryId, k -> new ArrayList<>()).add(tfb);
                        }
                        continue;
                    }
                    // sourceId → DOM decimal → textFrame → storyId
                    // source ownership policy: master instance ("_pi" 접미사) 도 처리
                    String domId = ParagraphTextHelpers.domIdFromSourceId(sourceId);
                    if (domId == null) continue;
                    if (ctx.resolvedData.isTextOwnedByIndesignPng(domId)) continue;
                    ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId);
                    if (rtf != null && rtf.storyId() != null) {
                        String storyId = rtf.storyId();
                        if (isStoryFullyOwnedByIndesignPng(ctx, storyId)) continue;
                        // IDML story가 table-only이면 Phase 4가 ASTTable로 처리 → Phase 3 skip
                        // (동일 TF에 대해 1×1 래퍼 TextBox 중복 생성 방지).
                        // 단, Story 루트에 본문 텍스트와 Table이 함께 있으면 본문 TF까지
                        // 통째로 누락되므로 텍스트는 Phase 3에서 유지한다.
                        if (ctx.loadIDMLStory != null) {
                            IDMLStory _chk =
                                    ctx.loadIDMLStory.apply(storyId);
                            if (_chk != null && _chk.hasTables()
                                    && !hasStandaloneStoryText(_chk)
                                    && !hasAnchoredTablePlanForTextFrame(ctx, domId)) continue;
                        }
                        // 빈 본문 스토리이고 TextPath 매핑이 있으면 TextPath 스토리로 대체.
                        // frameVisibleTextLength 도 TextPath 스토리 길이로 보정하여 단락 분배 필터를 통과시킨다.
                        String subStoryId = textPathStorySub.get(domId);
                        if (subStoryId != null && isStoryEmpty(ctx, storyId)) {
                            storyId = subStoryId;
                            int subLen = storyTextLength(ctx, subStoryId);
                            if (subLen > 0) tfb.frameVisibleTextLength(subLen);
                        }
                        storyToBlocks.computeIfAbsent(storyId, k -> new ArrayList<>()).add(tfb);
                    }
                }
            }
        }
        storyBlockMapNanos = System.nanoTime() - t0;

        System.err.println("[ResolvedToASTBuilder] Phase 3: " + storyToBlocks.size() + " stories matched to TextFrameBlocks");

        // 각 Story → 단락 변환 후 TextFrameBlock에 분배
        // IDML Story XML 우선, 없으면 resolved fallback
        int totalParas = 0;
        int idmlCount = 0;
        int resolvedCount = 0;
        t0 = System.nanoTime();
        for (Map.Entry<String, List<ASTTextFrameBlock>> entry : storyToBlocks.entrySet()) {
            String storyId = entry.getKey();
            List<ASTTextFrameBlock> blocks = entry.getValue();
            if (isStoryFullyOwnedByIndesignPng(ctx, storyId)) continue;
            // 1차: IDML Story XML에서 단락 파싱 (정확한 단락 구조)
            long stepStart = System.nanoTime();
            List<ASTParagraph> paragraphs = StoryLoader.convertStoryFromIDML(ctx, storyId);
            idmlConvertNanos += System.nanoTime() - stepStart;
            boolean useIdml = paragraphs != null && !paragraphs.isEmpty();

            // IDML-SHORT/PARA-MISMATCH 감지: resolved fallback 전환 조건
            // 1) IDML 텍스트가 resolved의 30% 미만 (불릿 전용 Story 등)
            // 2) IDML 단락 수가 resolved의 50% 미만 (강제 줄바꿈이 단락으로 처리되지 않는 경우)
            // 단, EH/BT/NP 수식 폰트가 포함된 story는 fallback하지 않음 (IDML 수식 변환이 우수)
            stepStart = System.nanoTime();
            if (useIdml) {
                if (!hasEquationFont(paragraphs)) {
                    ResolvedStory rs = ctx.resolvedData.getStory(storyId);
                    if (rs != null) {
                        if (hasAnchoredObjectIdWithoutInlineFlag(rs)) {
                            useIdml = false;
                        }
                        int idmlLen = 0;
                        for (ASTParagraph p : paragraphs)
                            for (Object item : p.items())
                                if (item instanceof ASTTextRun) idmlLen += ((ASTTextRun) item).text() != null ? ((ASTTextRun) item).text().length() : 0;
                        int resolvedLen = resolvedStoryTextLength(rs);
                        if (resolvedLen > FALLBACK_RESOLVED_MIN_LEN && idmlLen < resolvedLen * FALLBACK_IDML_SHORT_RATIO) {
                            useIdml = false; // 텍스트 길이 부족 → resolved fallback
                        }
                        // 단락 수 불일치: IDML 단락 수 적고 resolved 단락 수 많으면 강제 줄바꿈 누락
                        int resolvedParaCount = rs.paragraphs().size();
                        if (paragraphs.size() <= FALLBACK_IDML_PARA_MAX && resolvedParaCount >= FALLBACK_RESOLVED_PARA_MIN) {
                            useIdml = false; // 단락 구조 불일치 → resolved fallback
                        }
                    }
                }
            }
            fallbackCheckNanos += System.nanoTime() - stepStart;

            if (useIdml) {
                idmlCount++;
            } else {
                // 2차: resolved.json fallback
                stepStart = System.nanoTime();
                paragraphs = convertTextOnlyStoryParagraphsFromTextFlowIfSafe(ctx, storyId);
                if (paragraphs == null) {
                    ResolvedStory story = ctx.resolvedData.getStory(storyId);
                    if (story == null) {
                        continue;
                    }
                    paragraphs = convertStoryParagraphs(ctx, story);
                } else {
                    ConversionTiming.addCounter("stage2.textBuilder.storyConverter.textFlowSafeStories", 1);
                }
                resolvedConvertNanos += System.nanoTime() - stepStart;
                resolvedCount++;
            }
            resolveAutoPageNumberMarkers(ctx, storyId, blocks, paragraphs);
            totalParas += paragraphs.size();

            // Story 전체 텍스트 길이 저장 (overflow 감지용)
            int storyTextLen = 0;
            for (ASTParagraph p : paragraphs) {
                String pt = ParagraphTextHelpers.getParaPlainText(p);
                if (pt != null) storyTextLen += pt.length();
            }
            for (ASTTextFrameBlock b : blocks) {
                b.storyTotalTextLength(storyTextLen);
            }

            // 오버플로우 감지: 모든 블록의 frameVisibleText가 거의 비어있고 Story가 길면 할당 안 함
            if (storyTextLen > 20) {
                boolean allBlocksEmpty = true;
                for (ASTTextFrameBlock b : blocks) {
                    if (b.frameVisibleTextLength() > 1) { allBlocksEmpty = false; break; }
                }
                if (allBlocksEmpty) {
                    continue;
                }
            }

            // 단락 분배: paragraphStart/End에 따라 각 TextFrameBlock에 할당
            stepStart = System.nanoTime();
            ParagraphDistributor.distributeParagraphs(ctx, paragraphs, blocks, storyId);
            normalizeInitialSpaceBeforeForTextFrames(blocks);
            if (System.getProperty("idml.debug.story") != null
                    && System.getProperty("idml.debug.story").equals(storyId)) {
                System.err.println("[StoryConverter.DEBUG] story=" + storyId
                        + " paragraphs=" + paragraphs.size()
                        + " blocks=" + blocks.size());
                for (ASTTextFrameBlock b : blocks) {
                    System.err.println("[StoryConverter.DEBUG] block source=" + b.sourceId()
                            + " inlineToFloating=" + b.inlineToFloating()
                            + " frameVisibleTextLength=" + b.frameVisibleTextLength()
                            + " assignedParas=" + b.paragraphs().size());
                    for (ASTParagraph p : b.paragraphs()) {
                        System.err.println("[StoryConverter.DEBUG] paraText="
                                + ParagraphTextHelpers.getParaPlainText(p));
                    }
                }
            }
            insertAnchoredTables(ctx, blocks);
            annotateParagraphPageBounds(ctx, blocks);
            applyComposedInkFontCaps(ctx, blocks);
            distributeNanos += System.nanoTime() - stepStart;
            stepStart = System.nanoTime();
            restoreTfInlineVisuals(ctx, blocks);
            restoreInlineNanos += System.nanoTime() - stepStart;
            stepStart = System.nanoTime();
            preserveComposedLineBreaksForTrailingAnswerVisuals(ctx, blocks);
            replaceDottedInlineImagesWithTabLeaders(ctx, blocks);
            coalesceDotLeaderAnswerVisualBreaks(blocks);
            normalizeDotLeaderPageNumberTabs(blocks);
            expandBlocksForDotLeaderTabs(blocks);
            forceSingleLineJustifiedFramesLeft(ctx, blocks);
            postprocessNanos += System.nanoTime() - stepStart;
            // 다중 블록 스토리는 ParagraphDistributor가 각 블록에 단락을 직접 배분했으므로
            // HWP 연결 글상자 링크(overflow) 불필요 → distributed=true로 linkListIDRef=0 보장
            if (blocks.size() > 1) {
                for (ASTTextFrameBlock b : blocks) {
                    b.distributed(true);
                }
            }
        }
        storyLoopNanos = System.nanoTime() - t0;
        List<ASTTextFrameBlock> allTextFrameBlocks = textFrameBlocks(sections);
        insertAnchoredTables(ctx, allTextFrameBlocks);
        applyAnchoredTableStylePlans(ctx, allTextFrameBlocks);
        applyComposedInkFontCaps(ctx, allTextFrameBlocks);
        System.err.println("[ResolvedToASTBuilder] Phase 3: " + totalParas + " paragraphs converted (IDML=" + idmlCount + " resolved=" + resolvedCount + ")");

        ConversionTiming.metric("stage2.textBuilder.storyConverter.aboveLineAnchorScanMs", millis(aboveLineScanNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.textPathScanMs", millis(textPathScanNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.storyBlockMapMs", millis(storyBlockMapNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.storyLoopMs", millis(storyLoopNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.idmlConvertMs", millis(idmlConvertNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.fallbackCheckMs", millis(fallbackCheckNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.resolvedConvertMs", millis(resolvedConvertNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.distributeMs", millis(distributeNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.restoreInlineVisualsMs", millis(restoreInlineNanos));
        ConversionTiming.metric("stage2.textBuilder.storyConverter.postprocessMs", millis(postprocessNanos));
    }

    private static void normalizeInitialSpaceBeforeForTextFrames(List<ASTTextFrameBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        for (ASTTextFrameBlock block : blocks) {
            ASTParagraph first = firstMaterializedParagraph(block);
            if (first != null) first.spaceBefore(0L);
        }
    }

    private static ASTParagraph firstMaterializedParagraph(ASTTextFrameBlock block) {
        if (block == null || block.paragraphs() == null) return null;
        for (ASTParagraph paragraph : block.paragraphs()) {
            if (paragraph == null) continue;
            if (paragraph.inlineTable() != null) return paragraph;
            String text = ParagraphTextHelpers.getParaPlainText(paragraph);
            if (text != null && !text.trim().isEmpty()) return paragraph;
            if (paragraph.items() != null && !paragraph.items().isEmpty()) return paragraph;
        }
        return null;
    }

    private static void applyComposedInkFontCaps(ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (ctx == null || ctx.resolvedData == null || blocks == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null || block.paragraphs().isEmpty()) continue;
            ResolvedTextFrame tf = textFrameForBlock(ctx, block);
            double maxFontSizePt = maxFontSizePt(ctx, tf);
            double capPt = composedInkFontCapPt(tf, maxFontSizePt, ctx.scaleFactor);
            if (capPt <= 0) continue;
            int capHwpunits = (int) Math.round(CoordinateConverter.pointsToHwpunits(capPt));
            clampParagraphFonts(block.paragraphs(), capHwpunits);
        }
    }

    private static ResolvedTextFrame textFrameForBlock(ResolvedBuildContext ctx, ASTTextFrameBlock block) {
        if (ctx == null || ctx.resolvedData == null || block == null) return null;
        String domId = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
        ResolvedTextFrame direct = domId != null ? ctx.resolvedData.getTextFrame(domId) : null;
        if (direct != null) return direct;

        String storyId = block.storyId();
        if (storyId == null || ctx.resolvedData.textFrames() == null) return null;
        ResolvedTextFrame firstStoryFrame = null;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || !storyId.equals(tf.storyId())) continue;
            if (firstStoryFrame == null) firstStoryFrame = tf;
            double maxFontSizePt = maxFontSizePt(ctx, tf);
            if (composedInkFontCapPt(tf, maxFontSizePt, ctx.scaleFactor) > 0) return tf;
        }
        return firstStoryFrame;
    }

    private static double maxFontSizePt(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.storyId() == null) return 0.0;
        ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
        if (story == null || story.paragraphs() == null) return 0.0;
        double max = 0.0;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.fontSize() == null || run.fontSize() <= 0) continue;
                max = Math.max(max, run.fontSize());
            }
        }
        return max;
    }

    private static double composedInkFontCapPt(ResolvedTextFrame tf, double maxFontSizePt, double scaleFactor) {
        if (tf == null || maxFontSizePt <= 0) return 0.0;
        if (tf.composedLines() == null || tf.composedLines().isEmpty()) return 0.0;
        double[] frameBounds = tf.pageRelativeBounds();
        if (frameBounds == null || frameBounds.length < 4) frameBounds = tf.geometricBounds();
        if (frameBounds == null || frameBounds.length < 4) return 0.0;

        // ResolvedData normalizes page/text bounds to points before Stage 2/3.
        // Font sizes are also in points, so the composed-ink cap must compare
        // point bounds directly. Dividing by scaleFactor turns the frame back
        // into mm and can shrink compact labels such as numeric badges.
        double frameW = Math.abs(frameBounds[3] - frameBounds[1]);
        double frameH = Math.abs(frameBounds[2] - frameBounds[0]);
        double frameMaxAxis = Math.max(frameW, frameH);
        if (frameMaxAxis <= 0 || maxFontSizePt <= frameMaxAxis * 1.20) return 0.0;

        double inkMaxAxis = 0.0;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            if (!hasVisibleTextExcludingObjectControls(line.text())) continue;
            double[] b = line.bounds();
            double lineW = Math.abs(b[3] - b[1]);
            double lineH = Math.abs(b[2] - b[0]);
            if (lineW <= 0 || lineH <= 0) continue;
            inkMaxAxis = Math.max(inkMaxAxis, Math.max(lineW, lineH));
        }
        if (inkMaxAxis <= 0 || inkMaxAxis >= maxFontSizePt * 0.90) return 0.0;
        return Math.max(COMPOSED_INK_MIN_PT, inkMaxAxis * COMPOSED_INK_FONT_CAP_RATIO);
    }

    private static boolean hasVisibleTextExcludingObjectControls(String text) {
        if (text == null) return false;
        String cleaned = text
                .replace("\uFFFC", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\b", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        return !cleaned.isEmpty();
    }

    private static void clampParagraphFonts(List<ASTParagraph> paragraphs, int capHwpunits) {
        if (paragraphs == null || capHwpunits <= 0) return;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null) continue;
            clampInlineItems(paragraph.items(), capHwpunits);
            if (paragraph.inlineTable() != null) {
                clampTableFonts(paragraph.inlineTable(), capHwpunits);
            }
        }
    }

    private static void clampInlineItems(List<ASTInlineItem> items, int capHwpunits) {
        if (items == null) return;
        for (ASTInlineItem item : items) {
            if (item instanceof ASTTextRun) {
                ASTTextRun run = (ASTTextRun) item;
                if (run.fontSizeHwpunits() != null && run.fontSizeHwpunits() > capHwpunits) {
                    run.fontSizeHwpunits(capHwpunits);
                }
            } else if (item instanceof ASTInlineObject) {
                ASTInlineObject obj = (ASTInlineObject) item;
                clampParagraphFonts(obj.paragraphs(), capHwpunits);
                if (obj.inlineTables() != null) {
                    for (ASTTable table : obj.inlineTables()) {
                        clampTableFonts(table, capHwpunits);
                    }
                }
                if (obj.overlayFrames() != null) {
                    for (ASTInlineObject overlay : obj.overlayFrames()) {
                        clampParagraphFonts(overlay.paragraphs(), capHwpunits);
                    }
                }
            }
        }
    }

    private static void clampTableFonts(ASTTable table, int capHwpunits) {
        if (table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                clampParagraphFonts(cell.paragraphs(), capHwpunits);
            }
        }
    }

    private static List<ASTTextFrameBlock> textFrameBlocks(List<ASTSection> sections) {
        List<ASTTextFrameBlock> blocks = new ArrayList<>();
        if (sections == null) return blocks;
        for (ASTSection section : sections) {
            if (section == null || section.blocks() == null) continue;
            for (ASTBlock block : section.blocks()) {
                if (block instanceof ASTTextFrameBlock) {
                    blocks.add((ASTTextFrameBlock) block);
                }
            }
        }
        return blocks;
    }

    private static boolean shouldRebuildPrebuiltParagraphsFromResolved(
            ResolvedBuildContext ctx, ASTTextFrameBlock block, Map<String, String> textPathStorySub) {
        if (ctx == null || ctx.resolvedData == null || block == null) return false;
        if (containsStructuralInlineContent(block)) return false;

        String storyId = resolvedStoryIdForBlock(ctx, block, textPathStorySub);
        if (storyId == null || isStoryFullyOwnedByIndesignPng(ctx, storyId)) return false;
        ResolvedStory story = ctx.resolvedData.getStory(storyId);
        if (!hasAuthoritativeResolvedStructure(story)) return false;

        String existing = normalizedComparableText(plainText(block.paragraphs()));
        String resolved = normalizedComparableText(resolvedStoryPlainText(story));
        if (resolved.isEmpty()) return false;
        if (existing.isEmpty()) return true;

        // The prebuilt path is allowed only when it represents deliberate structure.
        // If it is just the same story flattened into fewer paragraphs/runs, resolved
        // owns the source text structure.
        return resolved.equals(existing)
                || resolved.startsWith(existing)
                || existing.startsWith(resolved);
    }

    private static String resolvedStoryIdForBlock(
            ResolvedBuildContext ctx, ASTTextFrameBlock block, Map<String, String> textPathStorySub) {
        String direct = block.storyId();
        String domId = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
        if (domId == null) domId = block.sourceId();
        if (domId != null) {
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
            if (tf != null && tf.storyId() != null) {
                direct = tf.storyId();
                String subStoryId = textPathStorySub != null ? textPathStorySub.get(domId) : null;
                if (subStoryId != null && isStoryEmpty(ctx, direct)) {
                    direct = subStoryId;
                }
            }
        }
        return direct;
    }

    private static boolean hasAuthoritativeResolvedStructure(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        int nonEmptyParagraphs = 0;
        for (ResolvedParagraph para : story.paragraphs()) {
            if (para == null) continue;
            int visibleRuns = 0;
            for (ResolvedRun run : para.runs()) {
                if (run == null || isInlineAnchorRun(run)) continue;
                String text = normalizedComparableText(run.text());
                if (!text.isEmpty()) visibleRuns++;
            }
            if (visibleRuns > 0) nonEmptyParagraphs++;
            if (visibleRuns > 1) return true;
        }
        return nonEmptyParagraphs > 1;
    }

    private static boolean hasAnchoredObjectIdWithoutInlineFlag(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph para : story.paragraphs()) {
            if (para == null || para.runs() == null) continue;
            for (ResolvedRun run : para.runs()) {
                if (run == null) continue;
                if (run.anchoredObjectId() != null && !run.isInlineAnchor()) return true;
            }
        }
        return false;
    }

    private static boolean containsStructuralInlineContent(ASTTextFrameBlock block) {
        if (block == null || block.paragraphs() == null) return false;
        for (ASTParagraph para : block.paragraphs()) {
            if (para == null) continue;
            if (para.inlineTable() != null) return true;
            if (para.items() == null) continue;
            for (ASTInlineItem item : para.items()) {
                if (item instanceof ASTInlineObject || item instanceof ASTEquation) return true;
            }
        }
        return false;
    }

    private static String plainText(List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph para : paragraphs) {
            if (para == null) continue;
            String text = ParagraphTextHelpers.getParaPlainText(para);
            if (text != null) sb.append(text);
        }
        return sb.toString();
    }

    private static String resolvedStoryPlainText(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedParagraph para : story.paragraphs()) {
            if (para == null || para.runs() == null) continue;
            for (ResolvedRun run : para.runs()) {
                if (run == null || isInlineAnchorRun(run)) continue;
                String text = run.text();
                if (text != null) sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String normalizedComparableText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008') continue;
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static boolean isStoryFullyOwnedByIndesignPng(ResolvedBuildContext ctx, String storyId) {
        if (ctx == null || ctx.resolvedData == null || storyId == null) return false;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames == null || frames.isEmpty()) return false;
        boolean hasOwnedFrame = false;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.id() == null) continue;
            if (!ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) return false;
            hasOwnedFrame = true;
        }
        return hasOwnedFrame;
    }

    private static void resolveAutoPageNumberMarkers(
            ResolvedBuildContext ctx,
            String storyId,
            List<ASTTextFrameBlock> blocks,
            List<ASTParagraph> paragraphs) {
        if (ctx == null || ctx.resolvedData == null || paragraphs == null || paragraphs.isEmpty()) return;
        if (!paragraphsContainAutoPageNumberMarker(paragraphs)) return;

        String replacement = autoPageNumberReplacement(ctx, storyId, blocks);
        if (replacement == null || replacement.isEmpty()) return;

        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (!(item instanceof ASTTextRun)) continue;
                ASTTextRun run = (ASTTextRun) item;
                String text = run.text();
                if (text == null || text.indexOf('\uFFFE') < 0) continue;
                run.text(text.replace("\uFFFE", replacement));
            }
        }
    }

    private static boolean paragraphsContainAutoPageNumberMarker(List<ASTParagraph> paragraphs) {
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (!(item instanceof ASTTextRun)) continue;
                String text = ((ASTTextRun) item).text();
                if (text != null && text.indexOf('\uFFFE') >= 0) return true;
            }
        }
        return false;
    }

    private static String autoPageNumberReplacement(
            ResolvedBuildContext ctx,
            String storyId,
            List<ASTTextFrameBlock> blocks) {
        if (blocks != null) {
            for (ASTTextFrameBlock block : blocks) {
                ResolvedTextFrame tf = textFrameForBlock(ctx, block);
                String text = visibleTextForAutoPageNumber(tf);
                if (text != null) return text;
            }
        }
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames != null) {
            for (ResolvedTextFrame tf : frames) {
                String text = visibleTextForAutoPageNumber(tf);
                if (text != null) return text;
            }
        }
        return null;
    }

    private static String visibleTextForAutoPageNumber(ResolvedTextFrame tf) {
        if (tf == null) return null;
        String text = tf.frameVisibleText();
        if (text == null) return null;
        String cleaned = text
                .replace("\uFEFF", "")
                .replace("\uFFFC", "")
                .replace("\uFFFE", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static void applyAnchoredTableStylePlans(
            ResolvedBuildContext ctx,
            List<ASTTextFrameBlock> blocks) {
        if (ctx == null || ctx.resolvedData == null || blocks == null || blocks.isEmpty()) return;
        List<AnchoredTablePlan> plans = ctx.anchoredTablePlans();
        if (plans == null || plans.isEmpty()) return;
        for (AnchoredTablePlan plan : plans) {
            if (plan == null) continue;
            ResolvedTextFrame owner = ctx.resolvedData.getTextFrame(String.valueOf(plan.anchoredTextFrameDomId));
            if (owner == null) continue;
            Set<String> targetTableIds = anchoredTableStyleCarrierIds(plan);
            if (targetTableIds.isEmpty()) continue;
            for (ASTTextFrameBlock block : blocks) {
                if (block == null || block.paragraphs() == null) continue;
                for (ASTParagraph paragraph : block.paragraphs()) {
                    absorbAnchoredNestedTableOutline(ctx, owner, targetTableIds, paragraph);
                }
            }
        }
    }

    private static boolean hasAnchoredTablePlanForTextFrame(ResolvedBuildContext ctx, String domId) {
        int id = parseInt(domId, -1);
        return ctx != null
                && id >= 0
                && ctx.anchoredTablePlansForOwnerTextFrame(id) != null
                && !ctx.anchoredTablePlansForOwnerTextFrame(id).isEmpty();
    }

    private static void insertAnchoredTables(ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (ctx == null || blocks == null || ctx.loadIDMLStory == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null) continue;
            String domIdText = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
            int domId = parseInt(domIdText, -1);
            if (domId < 0) continue;
            List<AnchoredTablePlan> plans = ctx.anchoredTablePlansForOwnerTextFrame(domId);
            if (plans == null || plans.isEmpty()) continue;
            plans = new ArrayList<>(plans);
            plans.sort(Comparator.comparingInt((AnchoredTablePlan p) -> p.afterParagraphIndex).reversed());
            for (AnchoredTablePlan plan : plans) {
                IDMLTable table = loadPlannedTable(ctx, plan);
                if (table == null) continue;
                boolean wrapperFlowTable = isWrapperFlowTable(table, plan);
                if (!wrapperFlowTable && hasInlineTable(block.paragraphs(), plan.nestedTableId)) continue;
                ASTTable astTable = TableBuilder.buildPreparedAstTable(ctx, table, 0, 0, 0);
                if (astTable == null) continue;
                applyAnchoredTableBounds(ctx, plan, astTable);
                int insertAt = Math.max(0, Math.min(block.paragraphs().size(), plan.afterParagraphIndex + 1));
                consumeMarkerOnlyParagraphAt(block.paragraphs(), insertAt);
                // wrapper flow(실제 nested table을 감싼 표)만 셀 단위로 평탄화한다.
                // 데이터/레이아웃 표는 인라인 표(else 분기)로 통째 삽입해 셀 구조를 보존한다.
                List<ASTParagraph> wrapperFlow = wrapperFlowTable
                        ? wrapperFlowParagraphs(ctx, plan, astTable, table)
                        : java.util.Collections.emptyList();
                if (!wrapperFlow.isEmpty()) {
                    if (containsEquivalentFlow(block.paragraphs(), wrapperFlow)) {
                        continue;
                    }
                    removeInlineTables(block.paragraphs(), plan.wrapperTableId, plan.nestedTableId);
                    insertAt = Math.max(0, Math.min(block.paragraphs().size(), plan.afterParagraphIndex + 1));
                    consumeMarkerOnlyParagraphAt(block.paragraphs(), insertAt);
                    block.paragraphs().addAll(insertAt, wrapperFlow);
                } else {
                    ASTParagraph paragraph = new ASTParagraph();
                    paragraph.inlineTable(astTable);
                    block.paragraphs().add(insertAt, paragraph);
                }
            }
        }
    }

    private static List<ASTParagraph> wrapperFlowParagraphs(
            ResolvedBuildContext ctx,
            AnchoredTablePlan plan,
            ASTTable astTable,
            IDMLTable idmlWrapperTable) {
        List<ASTParagraph> result = new ArrayList<>();
        if (plan == null || astTable == null || astTable.rows() == null) return result;
        if (plan.wrapperTableId == null || !plan.wrapperTableId.equals(astTable.sourceId())) return result;
        List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow> idmlRows =
                idmlWrapperTable != null ? idmlWrapperTable.rows() : null;
        if (idmlRows == null || idmlRows.size() <= 1) return result;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow idmlRow : idmlRows) {
            if (idmlRow == null || idmlRow.cells() == null) continue;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell : idmlRow.cells()) {
                if (idmlCell == null) continue;
                for (ASTParagraph paragraph : astParagraphsFromIdmlCell(ctx, idmlCell)) {
                    if (isVisibleWrapperFlowParagraph(paragraph)) result.add(paragraph);
                }
                if (cellHasStoryRef(idmlCell, plan.nestedStoryId)) {
                    ASTParagraph nested = nestedTableParagraph(ctx, plan);
                    if (nested != null) result.add(nested);
                }
            }
        }
        return result;
    }

    private static Set<String> anchoredTableStyleCarrierIds(AnchoredTablePlan plan) {
        Set<String> ids = new HashSet<>();
        if (plan == null) return ids;
        if (plan.nestedTableId != null && !plan.nestedTableId.isEmpty()) ids.add(plan.nestedTableId);
        if (plan.wrapperTableId != null && !plan.wrapperTableId.isEmpty()) ids.add(plan.wrapperTableId);
        return ids;
    }

    private static void absorbAnchoredNestedTableOutline(
            ResolvedBuildContext ctx,
            ResolvedTextFrame owner,
            Set<String> targetTableIds,
            ASTParagraph paragraph) {
        if (paragraph == null) return;
        ASTTable inlineTable = paragraph.inlineTable();
        if (inlineTable != null) {
            absorbAnchoredNestedTableOutline(ctx, owner, targetTableIds, inlineTable);
        }
        if (paragraph.items() == null) return;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTInlineObject)) continue;
            ASTInlineObject obj = (ASTInlineObject) item;
            if (obj.inlineTables() != null) {
                for (ASTTable table : obj.inlineTables()) {
                    if (table != null) {
                        absorbAnchoredNestedTableOutline(ctx, owner, targetTableIds, table);
                    }
                }
            }
            if (obj.paragraphs() != null) {
                for (ASTParagraph child : obj.paragraphs()) {
                    absorbAnchoredNestedTableOutline(ctx, owner, targetTableIds, child);
                }
            }
            if (obj.overlayFrames() != null) {
                for (ASTInlineObject overlay : obj.overlayFrames()) {
                    if (overlay == null || overlay.paragraphs() == null) continue;
                    for (ASTParagraph child : overlay.paragraphs()) {
                        absorbAnchoredNestedTableOutline(ctx, owner, targetTableIds, child);
                    }
                }
            }
        }
    }

    private static void absorbAnchoredNestedTableOutline(
            ResolvedBuildContext ctx,
            ResolvedTextFrame owner,
            Set<String> targetTableIds,
            ASTTable table) {
        if (table == null) return;
        if (targetTableIds.contains(table.sourceId())) {
            TableBuilder.absorbTextFrameOutlineIntoTable(ctx, owner, table);
        }
        if (table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null) continue;
                for (ASTParagraph paragraph : cell.paragraphs()) {
                    absorbAnchoredNestedTableOutline(ctx, owner, targetTableIds, paragraph);
                }
            }
        }
    }

    private static boolean containsEquivalentFlow(
            List<ASTParagraph> existing,
            List<ASTParagraph> flow) {
        if (existing == null || existing.isEmpty() || flow == null || flow.isEmpty()) return false;
        for (ASTParagraph candidate : flow) {
            if (candidate == null) continue;
            ASTTable candidateTable = candidate.inlineTable();
            String candidateTableSource = candidateTable != null ? candidateTable.sourceId() : null;
            String candidateText = normalizedComparableText(ParagraphTextHelpers.getParaPlainText(candidate));
            if ((candidateTableSource == null || candidateTableSource.isEmpty()) && candidateText.isEmpty()) {
                continue;
            }
            for (ASTParagraph paragraph : existing) {
                if (paragraph == null) continue;
                ASTTable existingTable = paragraph.inlineTable();
                if (candidateTableSource != null && !candidateTableSource.isEmpty()
                        && existingTable != null
                        && candidateTableSource.equals(existingTable.sourceId())) {
                    return true;
                }
                if (!candidateText.isEmpty()) {
                    String existingText =
                            normalizedComparableText(ParagraphTextHelpers.getParaPlainText(paragraph));
                    if (candidateText.equals(existingText)) return true;
                }
            }
        }
        return false;
    }

    private static List<ASTParagraph> astParagraphsFromIdmlCell(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        List<ASTParagraph> result = new ArrayList<>();
        // 셀 밖과 동일한 공용 루틴으로 빌드 후, wrapperFlow 가시성 필터만 적용
        for (ASTParagraph para : StoryLoader.astParagraphsForCell(ctx, idmlCell)) {
            if (isVisibleWrapperFlowParagraph(para)) result.add(para);
        }
        return result;
    }

    private static void copyIdmlParagraphProperties(IDMLParagraph source, ASTParagraph target) {
        if (source == null || target == null) return;
        target.paragraphStyleRef(source.appliedParagraphStyle());
        target.alignment(source.justification());
        if (source.firstLineIndent() != null && source.firstLineIndent() != 0) {
            target.firstLineIndent(CoordinateConverter.pointsToHwpunits(source.firstLineIndent()));
        }
        if (source.leftIndent() != null && source.leftIndent() != 0) {
            target.leftMargin(CoordinateConverter.pointsToHwpunits(source.leftIndent()));
        }
        if (source.rightIndent() != null && source.rightIndent() != 0) {
            target.rightMargin(CoordinateConverter.pointsToHwpunits(source.rightIndent()));
        }
        if (source.spaceBefore() != null && source.spaceBefore() > 0) {
            target.spaceBefore(CoordinateConverter.pointsToHwpunits(source.spaceBefore()));
        }
        if (source.spaceAfter() != null && source.spaceAfter() > 0) {
            target.spaceAfter(CoordinateConverter.pointsToHwpunits(source.spaceAfter()));
        }
        if (source.leading() != null && source.leading() > 0 && source.leading() <= 50) {
            target.lineSpacing((int) CoordinateConverter.pointsToHwpunits(source.leading()));
            target.lineSpacingType("fixed");
        }
        if (source.columnBreakAfter()) target.columnBreakAfter(true);
        if (source.keepWithNext()) target.keepWithNext(true);
        if (source.keepLinesTogether()) target.keepLinesTogether(true);
        if (source.pageBreakBefore()) target.pageBreakBefore(true);
        if (source.tabStops() != null) {
            for (IDMLStyleDef.TabStop tabStop : source.tabStops()) {
                if (tabStop == null || tabStop.position() <= 0) continue;
                target.addTabStop(new ASTTabStop(
                        CoordinateConverter.pointsToHwpunits(tabStop.position()),
                        tabStop.alignment(),
                        tabStop.leader()));
            }
        }
    }

    private static boolean isWrapperFlowTable(IDMLTable table, AnchoredTablePlan plan) {
        return table != null
                && plan != null
                && plan.wrapperTableId != null
                && plan.wrapperTableId.equals(table.selfId())
                && hasWrapperFlowContent(table, plan);
    }

    private static void removeInlineTables(
            List<ASTParagraph> paragraphs,
            String... sourceIds) {
        if (paragraphs == null || paragraphs.isEmpty() || sourceIds == null || sourceIds.length == 0) return;
        Set<String> ids = new HashSet<>();
        for (String sourceId : sourceIds) {
            if (sourceId != null && !sourceId.isEmpty()) ids.add(sourceId);
        }
        if (ids.isEmpty()) return;
        paragraphs.removeIf(paragraph -> paragraph != null
                && paragraph.inlineTable() != null
                && ids.contains(paragraph.inlineTable().sourceId()));
    }

    private static kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell findIdmlCell(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow row,
            int columnIndex) {
        if (row == null || row.cells() == null) return null;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell : row.cells()) {
            if (cell != null && cell.columnIndex() == columnIndex) return cell;
        }
        return null;
    }

    private static boolean cellHasInlineTable(ASTTableCell cell, String sourceId) {
        if (cell == null || cell.paragraphs() == null || sourceId == null) return false;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph != null && paragraph.inlineTable() != null
                    && sourceId.equals(paragraph.inlineTable().sourceId())) {
                return true;
            }
        }
        return false;
    }

    private static ASTParagraph nestedTableParagraph(ResolvedBuildContext ctx, AnchoredTablePlan plan) {
        if (ctx == null || plan == null) return null;
        IDMLTable nestedTable = findTable(loadStoryByAnyId(ctx, plan.nestedStoryId), plan.nestedTableId);
        if (nestedTable == null) return null;
        ASTTable nestedAst = TableBuilder.buildPreparedAstTable(ctx, nestedTable, 0, 0, 0);
        if (nestedAst == null) return null;
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.inlineTable(nestedAst);
        return paragraph;
    }

    private static boolean isVisibleWrapperFlowParagraph(ASTParagraph paragraph) {
        if (paragraph == null) return false;
        if (paragraph.inlineTable() != null) return true;
        if (paragraph.items() == null || paragraph.items().isEmpty()) return false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item == null) continue;
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !isMarkerOnlyText(text)) return true;
                continue;
            }
            if (item instanceof ASTInlineObject) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (obj.imageData() != null || obj.imagePath() != null) return true;
                if (obj.paragraphs() != null && !obj.paragraphs().isEmpty()) return true;
                if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return true;
                continue;
            }
            return true;
        }
        return false;
    }

    private static void consumeMarkerOnlyParagraphAt(List<ASTParagraph> paragraphs, int index) {
        if (paragraphs == null || index < 0 || index >= paragraphs.size()) return;
        ASTParagraph candidate = paragraphs.get(index);
        if (!isMarkerOnlyParagraph(candidate)) return;
        paragraphs.remove(index);
    }

    private static boolean isMarkerOnlyParagraph(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.inlineTable() != null) return false;
        if (paragraph.items() == null || paragraph.items().isEmpty()) return true;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) return false;
            String text = ((ASTTextRun) item).text();
            if (!isMarkerOnlyText(text)) return false;
        }
        return true;
    }

    private static boolean isMarkerOnlyText(String text) {
        if (text == null || text.isEmpty()) return true;
        String normalized = text
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        return normalized.isEmpty();
    }

    private static void applyAnchoredTableBounds(
            ResolvedBuildContext ctx,
            AnchoredTablePlan plan,
            ASTTable astTable) {
        if (ctx == null || ctx.resolvedData == null || plan == null || astTable == null) return;
        if (plan.wrapperTableId != null && plan.wrapperTableId.equals(astTable.sourceId())) {
            return;
        }
        if (plan.anchoredTextFrameDomId < 0) return;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(plan.anchoredTextFrameDomId));
        if (tf == null || tf.pageRelativeBounds() == null || tf.pageRelativeBounds().length < 4) return;
        double[] b = tf.pageRelativeBounds();
        double scale = ctx.resolvedData.scaleFactor();
        long y = CoordinateConverter.pointsToHwpunits(b[0] * scale);
        long x = CoordinateConverter.pointsToHwpunits(b[1] * scale);
        long h = CoordinateConverter.pointsToHwpunits(Math.max(0, b[2] - b[0]) * scale);
        long w = CoordinateConverter.pointsToHwpunits(Math.max(0, b[3] - b[1]) * scale);
        astTable.x(x);
        astTable.y(y);
        if (w > 0) astTable.width(w);
        if (h > 0) astTable.height(h);
        astTable.zOrder(tf.zOrder());
    }

    private static void annotateParagraphPageBounds(
            ResolvedBuildContext ctx,
            List<ASTTextFrameBlock> blocks) {
        if (ctx == null || ctx.resolvedData == null || blocks == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null || block.paragraphs().isEmpty()) continue;
            String domIdText = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domIdText);
            if (tf == null || tf.composedLines() == null || tf.composedLines().isEmpty()) continue;
            Map<Integer, List<ResolvedTextFrame.ComposedLine>> byParagraph =
                    composedLinesByParagraph(tf);
            if (byParagraph.isEmpty()) continue;
            Set<ASTParagraph> processed = new HashSet<>();
            for (List<ResolvedTextFrame.ComposedLine> lines : byParagraph.values()) {
                ASTParagraph para = findParagraphForComposedLines(block.paragraphs(), lines, processed);
                if (para == null) continue;
                long[] bounds = pageRelativeLineBounds(tf, lines, ctx.resolvedData.scaleFactor());
                if (bounds == null) continue;
                para.pageX(bounds[0]);
                para.pageY(bounds[1]);
                para.pageWidth(Math.max(1, bounds[2] - bounds[0]));
                para.pageHeight(Math.max(1, bounds[3] - bounds[1]));
                processed.add(para);
            }
        }
    }

    private static long[] pageRelativeLineBounds(
            ResolvedTextFrame tf,
            List<ResolvedTextFrame.ComposedLine> lines,
            double outputScale) {
        if (tf == null || lines == null || lines.isEmpty()
                || tf.geometricBounds() == null || tf.pageRelativeBounds() == null) return null;
        double[] gb = tf.geometricBounds();
        double[] pb = tf.pageRelativeBounds();
        if (gb.length < 4 || pb.length < 4) return null;
        double scaleY = coordinateScale(gb[2] - gb[0], pb[2] - pb[0]);
        double scaleX = coordinateScale(gb[3] - gb[1], pb[3] - pb[1]);
        double top = Double.POSITIVE_INFINITY;
        double left = Double.POSITIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            double[] b = line.bounds();
            top = Math.min(top, pb[0] + (b[0] - gb[0]) / scaleY);
            left = Math.min(left, pb[1] + (b[1] - gb[1]) / scaleX);
            bottom = Math.max(bottom, pb[0] + (b[2] - gb[0]) / scaleY);
            right = Math.max(right, pb[1] + (b[3] - gb[1]) / scaleX);
        }
        if (!Double.isFinite(top) || !Double.isFinite(left)
                || !Double.isFinite(bottom) || !Double.isFinite(right)
                || bottom <= top || right <= left) {
            return null;
        }
        return new long[] {
                CoordinateConverter.pointsToHwpunits(left * outputScale),
                CoordinateConverter.pointsToHwpunits(top * outputScale),
                CoordinateConverter.pointsToHwpunits(right * outputScale),
                CoordinateConverter.pointsToHwpunits(bottom * outputScale)
        };
    }

    private static double coordinateScale(double sourceSize, double targetSize) {
        if (!Double.isFinite(sourceSize) || !Double.isFinite(targetSize)
                || sourceSize <= 0 || targetSize <= 0) return 1.0;
        return sourceSize / targetSize;
    }

    private static IDMLTable loadPlannedTable(ResolvedBuildContext ctx, AnchoredTablePlan plan) {
        if (ctx == null || plan == null || ctx.loadIDMLStory == null) return null;
        IDMLTable wrapper = findTable(loadStoryByAnyId(ctx, plan.ownerStoryId), plan.wrapperTableId);
        if (hasWrapperFlowContent(wrapper, plan)) return wrapper;
        IDMLTable nested = findTable(loadStoryByAnyId(ctx, plan.nestedStoryId), plan.nestedTableId);
        if (nested != null) return nested;
        if (wrapper != null) return wrapper;
        return findTable(loadStoryByAnyId(ctx, plan.ownerStoryId), plan.nestedTableId);
    }

    private static boolean hasWrapperFlowContent(IDMLTable wrapper, AnchoredTablePlan plan) {
        if (wrapper == null || wrapper.rows() == null) return false;
        // 실제 nested table(별도 story)을 감싼 wrapper만 평탄화 대상. nestedStoryId가 없으면
        // (nestedTableId가 wrapper 자기 자신으로 채워진 경우) 셀에 직접 텍스트를 담은 데이터/레이아웃
        // 표이므로 평탄화하면 셀이 흩어진다 → wrapper flow 아님.
        if (wrapper.rows().size() > 1 && plan != null && plan.nestedStoryId != null) {
            return true;
        }
        int meaningfulCells = 0;
        int nestedAnchorCells = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow row : wrapper.rows()) {
            if (row == null || row.cells() == null) continue;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell : row.cells()) {
                if (cell == null) continue;
                boolean hasNestedAnchor = cellHasStoryRef(cell, plan.nestedStoryId);
                if (hasNestedAnchor) nestedAnchorCells++;
                if (hasNestedAnchor) {
                    meaningfulCells++;
                    continue;
                }
                if (cellHasVisibleText(cell) || cellHasInlineGraphic(cell) || cellHasNonNestedStoryRef(cell, plan.nestedStoryId)) {
                    meaningfulCells++;
                }
            }
        }
        return nestedAnchorCells > 0 && meaningfulCells > nestedAnchorCells;
    }

    private static boolean cellHasStoryRef(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell,
            String storyId) {
        if (cell == null || storyId == null || cell.textFrameStoryRefs() == null) return false;
        for (String ref : cell.textFrameStoryRefs()) {
            if (sameStoryId(ref, storyId)) return true;
        }
        return false;
    }

    private static boolean cellHasNonNestedStoryRef(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell,
            String nestedStoryId) {
        if (cell == null || cell.textFrameStoryRefs() == null) return false;
        for (String ref : cell.textFrameStoryRefs()) {
            if (ref != null && !sameStoryId(ref, nestedStoryId)) return true;
        }
        return false;
    }

    private static boolean sameStoryId(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String da = ParagraphTextHelpers.domIdFromSourceId(a);
        String db = ParagraphTextHelpers.domIdFromSourceId(b);
        return da != null && da.equals(db);
    }

    private static boolean cellHasVisibleText(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null) continue;
            String text = paragraph.getPlainText();
            if (text == null) continue;
            String normalized = text
                    .replace("\uFFFC", "")
                    .replace("\u0016", "")
                    .replace("\u0018", "")
                    .replace("\u0003", "")
                    .replace("\u0007", "")
                    .replace("\u0008", "")
                    .trim();
            if (!normalized.isEmpty()) return true;
        }
        return false;
    }

    private static boolean cellHasInlineGraphic(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) continue;
                return true;
            }
        }
        return false;
    }

    private static IDMLStory loadStoryByAnyId(ResolvedBuildContext ctx, String storyId) {
        if (ctx == null || ctx.loadIDMLStory == null || storyId == null || storyId.isEmpty()) return null;
        IDMLStory story = ctx.loadIDMLStory.apply(storyId);
        if (story != null) return story;
        String sourceId = ParagraphTextHelpers.domIdToSourceId(storyId);
        if (sourceId != null && !sourceId.equals(storyId)) {
            story = ctx.loadIDMLStory.apply(sourceId);
            if (story != null) return story;
        }
        if (storyId.startsWith("u")) {
            String decimal = ParagraphTextHelpers.domIdFromSourceId(storyId);
            if (decimal != null && !decimal.equals(storyId)) {
                story = ctx.loadIDMLStory.apply(decimal);
            }
        }
        return story;
    }

    private static IDMLTable findTable(IDMLStory story, String tableId) {
        if (story == null || tableId == null || story.tables() == null) return null;
        for (IDMLTable table : story.tables()) {
            if (table != null && tableId.equals(table.selfId())) return table;
        }
        return null;
    }

    private static boolean hasInlineTable(List<ASTParagraph> paragraphs, String sourceId) {
        if (paragraphs == null || sourceId == null) return false;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph != null && paragraph.inlineTable() != null
                    && sourceId.equals(paragraph.inlineTable().sourceId())) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean hasStandaloneStoryText(IDMLStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (IDMLParagraph paragraph : story.paragraphs()) {
            if (paragraph == null) continue;
            String text = paragraph.getPlainText();
            if (text == null) continue;
            String normalized = text
                    .replace("\uFFFC", "")
                    .replace("\u0016", "")
                    .replace("\u0018", "")
                    .replace("\u0003", "")
                    .replace("\u0007", "")
                    .replace("\u0008", "")
                    .trim();
            if (!normalized.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Preserve InDesign's line composition for paragraphs whose final line owns
     * trailing answer visuals such as dotted blanks or answer circles.
     *
     * <p>HWP can otherwise rewrap the text before the answer blank and then
     * justify the short line, producing wide word gaps and detached visuals.</p>
     */
    private static void preserveComposedLineBreaksForTrailingAnswerVisuals(
            ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (ctx == null || ctx.resolvedData == null || blocks == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null || block.paragraphs().isEmpty()) continue;
            String domId = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
            if (tf == null || tf.composedLines() == null || tf.composedLines().size() < 2) continue;

            Map<Integer, List<ResolvedTextFrame.ComposedLine>> linesByPara =
                    composedLinesByParagraph(tf);
            if (linesByPara.isEmpty()) continue;

            boolean changedBlock = false;
            Set<ASTParagraph> processed = new HashSet<>();
            for (Map.Entry<Integer, List<ResolvedTextFrame.ComposedLine>> entry : linesByPara.entrySet()) {
                int paraIndex = entry.getKey();
                List<ResolvedTextFrame.ComposedLine> lines = entry.getValue();
                if (!shouldPreserveAnswerVisualLines(ctx, tf, lines)) continue;
                ASTParagraph para = findParagraphForComposedLines(block.paragraphs(), lines, processed);
                if (para == null) continue;
                if (insertComposedLineBreaks(para, lines)) {
                    processed.add(para);
                    para.alignment("left");
                    changedBlock = true;
                }
            }
            // Explicit break edits preserve the source line structure.
            // Do not turn the whole text frame into HWPX SQUEEZE: that flag is
            // reserved for source single-line labels and would force body text
            // into one visual line.
        }
    }

    private static Map<Integer, List<ResolvedTextFrame.ComposedLine>> composedLinesByParagraph(
            ResolvedTextFrame tf) {
        Map<Integer, List<ResolvedTextFrame.ComposedLine>> result = new LinkedHashMap<>();
        if (tf == null || tf.composedLines() == null) return result;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || line.paraIndex() < 0) continue;
            result.computeIfAbsent(line.paraIndex(), k -> new ArrayList<>()).add(line);
        }
        return result;
    }

    private static ASTParagraph findParagraphForComposedLines(
            List<ASTParagraph> paragraphs,
            List<ResolvedTextFrame.ComposedLine> lines,
            Set<ASTParagraph> processed) {
        if (paragraphs == null || lines == null || lines.isEmpty()) return null;
        String expected = normalizeForParagraphMatch(combinedComposedLineText(lines));
        if (expected.isEmpty()) return null;
        for (ASTParagraph para : paragraphs) {
            if (para == null || (processed != null && processed.contains(para))) continue;
            String actual = normalizeForParagraphMatch(ParagraphTextHelpers.getParaPlainText(para));
            if (actual.isEmpty()) continue;
            if (actual.equals(expected)
                    || actual.contains(expected)
                    || (actual.length() >= 6 && expected.contains(actual))) {
                return para;
            }
        }
        return null;
    }

    private static String combinedComposedLineText(List<ResolvedTextFrame.ComposedLine> lines) {
        StringBuilder sb = new StringBuilder();
        if (lines != null) {
            for (ResolvedTextFrame.ComposedLine line : lines) {
                sb.append(normalizeComposedLineText(line != null ? line.text() : null));
            }
        }
        return sb.toString();
    }

    private static String normalizeForParagraphMatch(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .replace("\r", "")
                .replace("\n", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private static boolean shouldPreserveAnswerVisualLines(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            List<ResolvedTextFrame.ComposedLine> lines) {
        if (ctx == null || ctx.resolvedData == null || tf == null || lines == null || lines.size() < 2) {
            return false;
        }
        ResolvedTextFrame.ComposedLine last = lines.get(lines.size() - 1);
        if (last == null || last.bounds() == null || last.bounds().length < 4) return false;
        if (!hasTrailingAnswerLineMarker(last.text())) return false;
        double scale = ctx.resolvedData.scaleFactor();
        if (scale <= 0.0) scale = 1.0;
        if (last.wrapIndentRight() / scale < 8.0) return false;

        double[] line = last.bounds();
        double pageLeft = pageLeft(ctx, tf.pageIndex());
        double pageTop = pageTop(ctx, tf.pageIndex());
        double lineTop = (line[0] - pageTop) / scale;
        double lineBottom = (line[2] - pageTop) / scale;
        double lineRight = (line[3] - pageLeft) / scale;

        double[] tfb = tf.pageRelativeBounds();
        if (tfb == null || tfb.length < 4) {
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) return false;
            tfb = new double[] {
                    (gb[0] - pageTop) / scale,
                    (gb[1] - pageLeft) / scale,
                    (gb[2] - pageTop) / scale,
                    (gb[3] - pageLeft) / scale
            };
        }
        double frameLeft = tfb[1];
        double frameRight = tfb[3];

        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.pageIndex() != tf.pageIndex()) continue;
            if (rg.bounds() == null || rg.bounds().length < 4) continue;
            if (!isAnswerTrailingVisualCandidate(ctx, rg)) continue;
            double[] b = rg.bounds();
            double vOverlap = Math.min(lineBottom, b[2]) - Math.max(lineTop, b[0]);
            if (vOverlap <= 0) continue;
            double lineH = Math.max(0.1, lineBottom - lineTop);
            if (vOverlap < lineH * 0.35) continue;
            if (b[3] < lineRight + 4.0) continue;
            if (b[1] > frameRight + 6.0 || b[3] < frameLeft - 6.0) continue;
            return true;
        }
        return false;
    }

    private static boolean hasTrailingAnswerLineMarker(String text) {
        if (text == null) return false;
        if (text.indexOf('\uFFFC') >= 0
                || text.indexOf('\u0007') >= 0
                || text.indexOf('\b') >= 0
                || text.indexOf('\t') >= 0) {
            return true;
        }
        return text.contains("···")
                || text.contains("...")
                || text.contains("…");
    }

    private static boolean isAnswerTrailingVisualCandidate(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg == null) return false;
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        if (plan == null || !plan.hasVisibleVisual()) return false;
        return plan.textAction != TextAction.OWNED_BY_HWPX_TEXT;
    }

    private static double pageLeft(ResolvedBuildContext ctx, int pageIndex) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null) return 0.0;
        for (ResolvedPage page : ctx.resolvedData.pages()) {
            if (page == null || page.index() != pageIndex) continue;
            double[] b = page.bounds();
            return b != null && b.length >= 4 ? b[1] : 0.0;
        }
        return 0.0;
    }

    private static double pageTop(ResolvedBuildContext ctx, int pageIndex) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null) return 0.0;
        for (ResolvedPage page : ctx.resolvedData.pages()) {
            if (page == null || page.index() != pageIndex) continue;
            double[] b = page.bounds();
            return b != null && b.length >= 4 ? b[0] : 0.0;
        }
        return 0.0;
    }

    private static boolean insertComposedLineBreaks(
            ASTParagraph para, List<ResolvedTextFrame.ComposedLine> lines) {
        if (para == null || para.items() == null || lines == null || lines.size() < 2) return false;
        NormalizedTextMap paraMap = normalizedTextMap(para);
        if (paraMap.normalized.isEmpty()) return false;
        List<Integer> offsets = new ArrayList<>();
        int searchFrom = 0;
        for (int i = 0; i < lines.size() - 1; i++) {
            String lineText = normalizeForParagraphMatch(lines.get(i).text());
            if (lineText.isEmpty()) return false;
            int found = paraMap.normalized.indexOf(lineText, searchFrom);
            if (found < 0) return false;
            int breakNormOffset = found + lineText.length();
            if (breakNormOffset <= 0 || breakNormOffset >= paraMap.textOffsetsAfterNormalizedChars.length) {
                return false;
            }
            int textOffset = paraMap.textOffsetsAfterNormalizedChars[breakNormOffset];
            if (textOffset <= 0 || textOffset >= paraMap.textLength) return false;
            offsets.add((trailingObjectReplacementCount(lines.get(i).text()) << 24) | textOffset);
            searchFrom = breakNormOffset;
        }
        for (int i = offsets.size() - 1; i >= 0; i--) {
            int packed = offsets.get(i);
            int trailingObjects = packed >>> 24;
            int textOffset = packed & 0x00ffffff;
            insertBreakAtTextOffset(para, textOffset, trailingObjects);
        }
        return !offsets.isEmpty();
    }

    private static NormalizedTextMap normalizedTextMap(ASTParagraph para) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        int textOffset = 0;
        if (para != null && para.items() != null) {
            for (ASTInlineItem item : para.items()) {
                if (!(item instanceof ASTTextRun)) continue;
                String text = ((ASTTextRun) item).text();
                if (text == null || text.isEmpty()) continue;
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    textOffset++;
                    if (isIgnoredForComposedLineMatch(ch)) continue;
                    normalized.append(ch);
                    offsets.add(textOffset);
                }
            }
        }
        int[] map = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) map[i] = offsets.get(i);
        return new NormalizedTextMap(normalized.toString(), map, textOffset);
    }

    private static boolean isIgnoredForComposedLineMatch(char ch) {
        return ch == '\uFFFC'
                || ch == '\u0003'
                || ch == '\u0007'
                || ch == '\u0008'
                || ch == '\r'
                || ch == '\n'
                || Character.isWhitespace(ch);
    }

    private static String normalizeComposedLineText(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private static int trailingObjectReplacementCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC') {
                count++;
                continue;
            }
            if (ch == '\u0003' || ch == '\u0007' || ch == '\u0008'
                    || ch == '\r' || ch == '\n' || ch == '\u2028'
                    || Character.isWhitespace(ch)) {
                continue;
            }
            break;
        }
        return count;
    }

    /**
     * Reinsert visual-only inline objects that belong to an editable TF.
     *
     * <p>The extractor records these objects on the rendered visual shell as
     * tfInlineVisualIds. They should remain editable-flow inline images in HWPX
     * when the shell says textOwner=hwpx_tf; otherwise the checkbox/badge is
     * baked into a floating PNG and can no longer travel with the text.</p>
     */
    private static void restoreTfInlineVisuals(
            ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (ctx == null || ctx.resolvedData == null || blocks == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null || block.paragraphs().isEmpty()) continue;
            String visibleText = block.frameVisibleText();
            if (visibleText == null || visibleText.indexOf('\uFFFC') < 0) continue;
            String domId = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
            RenderedGroup owner = findTfInlineVisualOwner(ctx, domId);
            if (owner == null || owner.tfInlineVisualIds() == null || owner.tfInlineVisualIds().length == 0) continue;

            List<Integer> inlineVisualIds = tfInlineVisualIdsForStory(ctx, owner, block.storyId());
            if (inlineVisualIds.isEmpty()) continue;

            int idIdx = 0;
            int searchPara = 0;
            int searchOffset = 0;
            for (int i = 0; i < visibleText.length() && idIdx < inlineVisualIds.size(); i++) {
                if (visibleText.charAt(i) != '\uFFFC') continue;
                int inlineId = inlineVisualIds.get(idIdx++);
                if (containsInlineSource(block, inlineId)) continue;
                ASTInlineObject inline = loadTfInlineVisual(ctx, inlineId);
                if (inline == null) continue;

                String lookup = nextTextTokenAfterObject(visibleText, i + 1);
                InsertPoint point = findInlineInsertPoint(
                        block.paragraphs(), lookup, searchPara, searchOffset);
                if (point == null) {
                    int prefixLen = textLengthBeforeObject(visibleText, i);
                    point = findInsertPointByGlobalOffset(block.paragraphs(), prefixLen);
                }
                if (point == null) continue;
                insertInlineAtTextOffset(point.para, point.offset, inline);
                searchPara = point.paraIndex;
                searchOffset = point.offset + (lookup != null ? lookup.length() : 0);
            }
        }
    }

    private static List<Integer> tfInlineVisualIdsForStory(
            ResolvedBuildContext ctx, RenderedGroup owner, String storyId) {
        List<Integer> ids = new ArrayList<>();
        if (ctx == null || owner == null || owner.tfInlineVisualIds() == null) return ids;
        for (int inlineId : owner.tfInlineVisualIds()) {
            // nativeFillChildIds(대형 배경)는 FramePlacer가 네이티브 셀 fill로 흡수 →
            // 인라인 이미지로 렌더하면 본문 위에 통이미지가 떠 가린다. 인라인에서 제외.
            if (ownerContainsId(owner.nativeFillChildIds(), inlineId)) {
                continue;
            }
            RenderedGroup inlineRg = findRenderedGroup(ctx, inlineId);
            if (inlineRg == null
                    || !ctx.hasOwnershipPlan(inlineRg)
                    || !isPlannedInlineVisual(inlineRg, ctx)) {
                if (inlineRg != null) {
                    ctx.recordRenderedDecision(inlineRg, "Phase3.restoreTfInlineVisuals",
                            "SKIP_OBJECT_PLAN_NOT_INLINE_VISUAL",
                            "OwnershipPlanner did not assign this TF inline visual to an inline visual action");
                }
                continue;
            }
            String parentStoryId = inlineRg != null ? inlineRg.parentStoryId() : null;
            if (parentStoryId != null && storyId != null && !storyId.equals(parentStoryId)) {
                continue;
            }
            ids.add(inlineId);
        }
        return ids;
    }

    private static boolean isPlannedInlineVisual(RenderedGroup rg, ResolvedBuildContext ctx) {
        if (rg == null || ctx == null) return false;
        if (ctx.placementByOwnershipPlan(rg) != Placement.INLINE) return false;
        VisualAction action = ctx.visualActionByOwnershipPlan(rg);
        return action == VisualAction.PLACE_INLINE_PNG
                || ctx.shellRoleByOwnershipPlan(rg) != ShellRole.NONE;
    }

    private static RenderedGroup findTfInlineVisualOwner(ResolvedBuildContext ctx, String domId) {
        return ctx != null ? ctx.tfInlineVisualOwnerForTextFrame(domId) : null;
    }

    private static boolean ownerContainsId(int[] ids, int value) {
        if (ids == null) return false;
        for (int id : ids) {
            if (id == value) return true;
        }
        return false;
    }

    private static ASTInlineObject loadTfInlineVisual(ResolvedBuildContext ctx, int inlineId) {
        RenderedGroup rg = findRenderedGroup(ctx, inlineId);
        if (rg == null || rg.file() == null || ctx.basePath == null) return null;
        if (ctx.shellRoleByOwnershipPlan(rg) != ShellRole.NONE
                && ctx.placementByOwnershipPlan(rg) == Placement.INLINE) {
            return InlineFrameHandler.loadInlineObject(ctx, inlineId);
        }
        File pngFile = new File(ctx.basePath, rg.file());
        if (!pngFile.exists()) return null;
        try {
            byte[] data = loadRenderedPng(ctx, rg, "phase3.tfInlinePng");
            int[] dims = readPngDimensions(data);
            if (dims == null) {
                BufferedImage img = decodePngBytes(data, "phase3.tfInlinePng");
                if (img != null) dims = new int[] { img.getWidth(), img.getHeight() };
            } else {
                ConversionTiming.addCounter("phase3.tfInlinePng.headerDimensionReads", 1);
            }
            if (dims == null || dims[0] <= 1 || dims[1] <= 1) return null;
            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.sourceId("u" + Integer.toHexString(inlineId));
            obj.imageData(data);
            obj.imageFormat("png");
            obj.pixelWidth(dims[0]);
            obj.pixelHeight(dims[1]);
            obj.keepInline(true);
            double[] b = rg.bounds();
            if (b != null && b.length >= 4) {
                obj.boundsX(b[1]);
                double bw = Math.abs(b[3] - b[1]) * ctx.scaleFactor;
                double bh = Math.abs(b[2] - b[0]) * ctx.scaleFactor;
                if (bw > 0 && bh > 0) {
                    obj.width(CoordinateConverter.pointsToHwpunits(bw));
                    obj.height(CoordinateConverter.pointsToHwpunits(bh));
                }
            }
            if (obj.width() <= 0 || obj.height() <= 0) {
                double dpi = ctx.pngExportDpi > 0 ? ctx.pngExportDpi : 220.0;
                obj.width(CoordinateConverter.pointsToHwpunits(dims[0] * 72.0 / dpi));
                obj.height(CoordinateConverter.pointsToHwpunits(dims[1] * 72.0 / dpi));
            }
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] loadRenderedPng(ResolvedBuildContext ctx, RenderedGroup rg, String metricPrefix) {
        if (ctx == null || rg == null || rg.file() == null || ctx.basePath == null) return null;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists()) return null;
            String key = pngFile.getAbsolutePath();
            byte[] cached = ctx.renderedPngByteCache.get(key);
            if (cached != null) {
                ConversionTiming.addCounter(metricPrefix + ".cacheHits", 1);
                return cached;
            }
            byte[] data = Files.readAllBytes(pngFile.toPath());
            ctx.renderedPngByteCache.put(key, data);
            ConversionTiming.addCounter(metricPrefix + ".diskReads", 1);
            ConversionTiming.addCounter(metricPrefix + ".readBytes", data.length);
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage decodePngBytes(byte[] data, String metricPrefix) {
        if (data == null || data.length == 0) return null;
        try {
            ConversionTiming.addCounter(metricPrefix + ".imageDecodes", 1);
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] readPngDimensions(byte[] pngData) {
        if (pngData == null || pngData.length < 24) return null;
        if ((pngData[0] & 0xFF) != 0x89
                || pngData[1] != 0x50
                || pngData[2] != 0x4E
                || pngData[3] != 0x47
                || pngData[4] != 0x0D
                || pngData[5] != 0x0A
                || pngData[6] != 0x1A
                || pngData[7] != 0x0A) {
            return null;
        }
        if (pngData[12] != 0x49
                || pngData[13] != 0x48
                || pngData[14] != 0x44
                || pngData[15] != 0x52) {
            return null;
        }
        int w = readBigEndianInt(pngData, 16);
        int h = readBigEndianInt(pngData, 20);
        return w > 0 && h > 0 ? new int[] { w, h } : null;
    }

    private static int readBigEndianInt(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 3 >= data.length) return -1;
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static RenderedGroup findRenderedGroup(ResolvedBuildContext ctx, int id) {
        return ctx != null ? ctx.renderedFloatingById(id) : null;
    }

    private static boolean containsInlineSource(ASTTextFrameBlock block, int inlineId) {
        if (block == null || block.paragraphs() == null) return false;
        String sourceId = "u" + Integer.toHexString(inlineId);
        for (ASTParagraph para : block.paragraphs()) {
            if (para == null || para.items() == null) continue;
            for (ASTInlineItem item : para.items()) {
                if (item instanceof ASTInlineObject) {
                    ASTInlineObject obj = (ASTInlineObject) item;
                    if (sourceId.equals(obj.sourceId())) return true;
                }
            }
        }
        return false;
    }

    private static String nextTextTokenAfterObject(String text, int start) {
        if (text == null) return null;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\uFFFC' || c == '\r' || c == '\n' || Character.isWhitespace(c)
                    || isLookupPunctuation(c)) {
                i++;
                continue;
            }
            break;
        }
        int s = i;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && !isHangulChar(c)) break;
            i++;
        }
        return i > s ? text.substring(s, i) : null;
    }

    private static boolean isLookupPunctuation(char c) {
        return "()[]{}<>/,:;.!?\"'“”‘’·".indexOf(c) >= 0;
    }

    private static boolean isHangulChar(char c) {
        return (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3130 && c <= 0x318F);
    }

    private static int textLengthBeforeObject(String text, int objectIndex) {
        int len = 0;
        for (int i = 0; i < objectIndex && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFC' || c == '\r' || c == '\n') continue;
            len++;
        }
        return len;
    }

    private static InsertPoint findInlineInsertPoint(
            List<ASTParagraph> paragraphs, String lookup, int startPara, int startOffset) {
        if (paragraphs == null || paragraphs.isEmpty() || lookup == null || lookup.isEmpty()) return null;
        for (int pi = Math.max(0, startPara); pi < paragraphs.size(); pi++) {
            ASTParagraph para = paragraphs.get(pi);
            String text = ParagraphTextHelpers.getParaPlainText(para);
            if (text == null) continue;
            int from = (pi == startPara) ? Math.min(Math.max(0, startOffset), text.length()) : 0;
            int found = text.indexOf(lookup, from);
            if (found >= 0) return new InsertPoint(pi, para, found);
        }
        return null;
    }

    private static InsertPoint findInsertPointByGlobalOffset(
            List<ASTParagraph> paragraphs, int globalOffset) {
        if (paragraphs == null || paragraphs.isEmpty()) return null;
        int remaining = Math.max(0, globalOffset);
        for (int pi = 0; pi < paragraphs.size(); pi++) {
            ASTParagraph para = paragraphs.get(pi);
            String text = ParagraphTextHelpers.getParaPlainText(para);
            int len = text != null ? text.length() : 0;
            if (remaining <= len) return new InsertPoint(pi, para, remaining);
            remaining -= len;
        }
        ASTParagraph last = paragraphs.get(paragraphs.size() - 1);
        String text = ParagraphTextHelpers.getParaPlainText(last);
        return new InsertPoint(paragraphs.size() - 1, last, text != null ? text.length() : 0);
    }

    private static void insertInlineAtTextOffset(ASTParagraph para, int offset, ASTInlineObject inline) {
        if (para == null || inline == null || para.items() == null) return;
        int remaining = Math.max(0, offset);
        List<ASTInlineItem> items = para.items();
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                if (remaining == 0) {
                    items.add(i, inline);
                    return;
                }
                continue;
            }
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            int len = text != null ? text.length() : 0;
            if (remaining > len) {
                remaining -= len;
                continue;
            }
            if (remaining == 0) {
                items.add(i, inline);
                return;
            }
            if (remaining == len) {
                items.add(i + 1, inline);
                return;
            }
            ASTTextRun before = copyTextRun(run, text.substring(0, remaining));
            ASTTextRun after = copyTextRun(run, text.substring(remaining));
            items.set(i, before);
            items.add(i + 1, inline);
            items.add(i + 2, after);
            return;
        }
        items.add(inline);
    }

    private static void insertBreakAtTextOffset(ASTParagraph para, int offset) {
        insertBreakAtTextOffset(para, offset, 0);
    }

    private static void insertBreakAtTextOffset(ASTParagraph para, int offset, int trailingInlineObjects) {
        if (para == null || para.items() == null) return;
        int remaining = Math.max(0, offset);
        List<ASTInlineItem> items = para.items();
        ASTBreak lineBreak = new ASTBreak(ASTBreak.BreakType.LINE);
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                if (remaining == 0) {
                    items.add(i, lineBreak);
                    return;
                }
                continue;
            }
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            int len = text != null ? text.length() : 0;
            if (remaining > len) {
                remaining -= len;
                continue;
            }
            if (remaining == 0) {
                items.add(advancePastTrailingInlineObjects(items, i, trailingInlineObjects), lineBreak);
                return;
            }
            if (remaining == len) {
                items.add(advancePastTrailingInlineObjects(items, i + 1, trailingInlineObjects), lineBreak);
                return;
            }
            ASTTextRun before = copyTextRun(run, text.substring(0, remaining));
            ASTTextRun after = copyTextRun(run, text.substring(remaining));
            items.set(i, before);
            items.add(i + 1, after);
            items.add(advancePastTrailingInlineObjects(items, i + 1, trailingInlineObjects), lineBreak);
            return;
        }
        items.add(advancePastTrailingInlineObjects(items, items.size(), trailingInlineObjects), lineBreak);
    }

    private static int advancePastTrailingInlineObjects(
            List<ASTInlineItem> items, int index, int trailingInlineObjects) {
        if (items == null || trailingInlineObjects <= 0) return index;
        int pos = Math.max(0, Math.min(index, items.size()));
        int remainingObjects = trailingInlineObjects;
        while (pos < items.size() && remainingObjects > 0) {
            ASTInlineItem item = items.get(pos);
            if (item instanceof ASTInlineObject) {
                remainingObjects--;
                pos++;
                continue;
            }
            if (isWhitespaceOnlyTextRunForLineBoundary(item)) {
                pos++;
                continue;
            }
            break;
        }
        while (pos < items.size() && isWhitespaceOnlyTextRunForLineBoundary(items.get(pos))) {
            pos++;
        }
        return pos;
    }

    private static boolean isWhitespaceOnlyTextRunForLineBoundary(ASTInlineItem item) {
        if (!(item instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) item).text();
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\u2028') return false;
            if (!Character.isWhitespace(ch)) return false;
        }
        return true;
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

    private static final class InsertPoint {
        final int paraIndex;
        final ASTParagraph para;
        final int offset;

        InsertPoint(int paraIndex, ASTParagraph para, int offset) {
            this.paraIndex = paraIndex;
            this.para = para;
            this.offset = offset;
        }
    }

    private static final class NormalizedTextMap {
        final String normalized;
        final int[] textOffsetsAfterNormalizedChars;
        final int textLength;

        NormalizedTextMap(String normalized, int[] textOffsetsAfterNormalizedChars, int textLength) {
            this.normalized = normalized != null ? normalized : "";
            this.textOffsetsAfterNormalizedChars = textOffsetsAfterNormalizedChars != null
                    ? textOffsetsAfterNormalizedChars : new int[] { 0 };
            this.textLength = textLength;
        }
    }

    /**
     * InDesign 의 LEFT_JUSTIFIED 는 마지막 줄을 왼쪽으로 두지만, HWPX JUSTIFY 는
     * 단일행 글상자에서도 공백을 벌려 원본보다 오른쪽으로 밀려 보일 수 있다.
     * 원본에서 한 줄로 조판된 1문단 TF는 HWPX 에서는 left alignment 로 둔다.
     */
    private static void forceSingleLineJustifiedFramesLeft(
            ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (ctx == null || ctx.resolvedData == null || blocks == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null || block.paragraphs().size() != 1) continue;
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(
                    ParagraphTextHelpers.domIdFromSourceId(block.sourceId()));
            if (!isResolvedSingleLine(tf)) continue;
            ParagraphPropertyResolver.normalizeSingleLineJustify(block.paragraphs().get(0));
        }
    }

    private static void expandBlocksForDotLeaderTabs(List<ASTTextFrameBlock> blocks) {
        if (blocks == null) return;
        final long SAFETY_PAD = 500L; // 5pt: HWP tab leader/right edge clipping guard.
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null) continue;
            long requiredInnerWidth = 0;
            for (ASTParagraph para : block.paragraphs()) {
                if (para == null || para.tabStops() == null) continue;
                if (!hasActualTabRun(para)) continue;
                boolean blankUnderline = hasUnderlinedTabRun(para);
                long paraRight = para.rightMargin() != null ? Math.max(0L, para.rightMargin()) : 0L;
                for (ASTTabStop stop : para.tabStops()) {
                    if (stop == null || stop.position() <= 0) continue;
                    String ld = stop.leader();
                    // 리더 있는 탭(점선 페이지번호 등) 또는 밑줄 빈칸 탭 run → 프레임 폭 확보
                    if ((ld == null || ld.isEmpty()) && !blankUnderline) continue;
                    requiredInnerWidth = Math.max(requiredInnerWidth, stop.position() + paraRight);
                }
            }
            if (requiredInnerWidth <= 0) continue;
            long requiredOuterWidth = block.insetLeft() + requiredInnerWidth + block.insetRight() + SAFETY_PAD;
            if (requiredOuterWidth > block.width()) {
                block.width(requiredOuterWidth);
            }
        }
    }

    private static boolean hasActualTabRun(ASTParagraph para) {
        if (para == null || para.items() == null) return false;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && text.indexOf('\t') >= 0) return true;
        }
        return false;
    }

    /** 밑줄 속성이 걸린 탭 run(빈칸 채움선)이 있는지 — 프레임 폭 확보 판정용. */
    static boolean hasUnderlinedTabRun(ASTParagraph para) {
        if (para == null || para.items() == null) return false;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text != null && text.indexOf('\t') >= 0 && run.underline()) return true;
        }
        return false;
    }

    private static void replaceDottedInlineImagesWithTabLeaders(
            ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (ctx == null || blocks == null) return;
        double scale = ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null) continue;
            long contentLeft = block.x() + block.insetLeft();
            long contentRight = block.x() + Math.max(0L, block.width() - block.insetRight());
            for (ASTParagraph para : block.paragraphs()) {
                if (para == null || para.items() == null) continue;
                List<ASTInlineItem> items = para.items();
                for (int i = 0; i < items.size(); i++) {
                    ASTInlineItem item = items.get(i);
                    if (!(item instanceof ASTInlineObject)) continue;
                    ASTInlineObject obj = (ASTInlineObject) item;
                    if (!isDottedLeaderInlineImage(obj)) continue;

                    long tabPos = dottedLeaderTabStopPosition(obj, contentLeft, contentRight, scale);
                    if (tabPos <= 0) continue;
                    ensureDotLeaderTabStop(para, tabPos);
                    items.set(i, tabTextRunLike(items, i));
                }
            }
        }
    }

    private static void coalesceDotLeaderAnswerVisualBreaks(List<ASTTextFrameBlock> blocks) {
        if (blocks == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null) continue;
            boolean changedBlock = false;
            for (ASTParagraph para : block.paragraphs()) {
                if (para == null || para.items() == null) continue;
                List<ASTInlineItem> items = para.items();
                for (int i = 0; i < items.size(); i++) {
                    if (!isDotLeaderTabRun(items.get(i))) continue;

                    int afterTab = skipNonTabWhitespaceTextRuns(items, i + 1);
                    int cursor = afterTab;
                    List<Integer> breakIndexes = new ArrayList<>();
                    while (cursor < items.size()) {
                        ASTInlineItem item = items.get(cursor);
                        if (isLineBreak(item)) {
                            breakIndexes.add(cursor);
                            cursor++;
                            cursor = skipNonTabWhitespaceTextRuns(items, cursor);
                            continue;
                        }
                        break;
                    }
                    if (breakIndexes.isEmpty()) continue;

                    int tail = skipNonTabWhitespaceTextRuns(items, cursor);
                    int duplicateTab = -1;
                    if (tail < items.size() && isDotLeaderTabRun(items.get(tail))) {
                        duplicateTab = tail;
                        tail = skipNonTabWhitespaceTextRuns(items, tail + 1);
                    }
                    if (!hasAnswerVisualSoon(items, tail)) continue;

                    if (duplicateTab >= 0) {
                        items.remove(duplicateTab);
                    }
                    for (int b = breakIndexes.size() - 1; b >= 0; b--) {
                        int index = breakIndexes.get(b);
                        if (index >= 0 && index < items.size() && isLineBreak(items.get(index))) {
                            items.remove(index);
                        }
                    }
                    para.alignment("left");
                    changedBlock = true;
                }
                if (removeAdjacentDuplicateDotLeaderTabsBeforeAnswerVisuals(para)) {
                    para.alignment("left");
                    changedBlock = true;
                }
            }
            // Keep the paragraph edits local. Setting noAutoLineWrap here would
            // change the container to SQUEEZE and break ordinary multi-line
            // body text that merely happens to have trailing answer visuals.
        }
    }

    private static boolean removeAdjacentDuplicateDotLeaderTabsBeforeAnswerVisuals(ASTParagraph para) {
        if (para == null || para.items() == null) return false;
        boolean changed = false;
        List<ASTInlineItem> items = para.items();
        for (int i = 0; i < items.size(); i++) {
            if (!isDotLeaderTabRun(items.get(i))) continue;
            int next = skipNonTabWhitespaceTextRuns(items, i + 1);
            if (next >= items.size() || !isDotLeaderTabRun(items.get(next))) continue;
            int afterNext = skipNonTabWhitespaceTextRuns(items, next + 1);
            if (!hasAnswerVisualSoon(items, afterNext)) continue;
            items.remove(next);
            changed = true;
            i--;
        }
        return changed;
    }

    private static boolean isDotLeaderTabRun(ASTInlineItem item) {
        if (!(item instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) item).text();
        if (text == null || text.isEmpty() || text.indexOf('\t') < 0) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\t' && !Character.isWhitespace(ch)) return false;
        }
        return true;
    }

    private static int skipWhitespaceTextRuns(List<ASTInlineItem> items, int index) {
        if (items == null) return index;
        int pos = Math.max(0, index);
        while (pos < items.size() && isWhitespaceOnlyTextRunForLineBoundary(items.get(pos))) {
            pos++;
        }
        return pos;
    }

    private static int skipNonTabWhitespaceTextRuns(List<ASTInlineItem> items, int index) {
        if (items == null) return index;
        int pos = Math.max(0, index);
        while (pos < items.size() && isNonTabWhitespaceOnlyTextRun(items.get(pos))) {
            pos++;
        }
        return pos;
    }

    private static boolean isNonTabWhitespaceOnlyTextRun(ASTInlineItem item) {
        if (!(item instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) item).text();
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\t' || ch == '\r' || ch == '\n' || ch == '\u2028') return false;
            if (!Character.isWhitespace(ch)) return false;
        }
        return true;
    }

    private static boolean isLineBreak(ASTInlineItem item) {
        if (item instanceof ASTBreak) {
            return ((ASTBreak) item).breakType() == ASTBreak.BreakType.LINE;
        }
        if (!(item instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) item).text();
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\r' && ch != '\n' && ch != '\u2028') return false;
        }
        return true;
    }

    private static boolean hasAnswerVisualSoon(List<ASTInlineItem> items, int index) {
        if (items == null) return false;
        int seen = 0;
        for (int i = Math.max(0, index); i < items.size() && seen < 6; i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTInlineObject) {
                ASTInlineObject obj = (ASTInlineObject) item;
                long minVisualSize = CoordinateConverter.pointsToHwpunits(3.0);
                return obj.width() >= minVisualSize && obj.height() >= minVisualSize;
            }
            if (isWhitespaceOnlyTextRunForLineBoundary(item) || isDotLeaderTabRun(item)) {
                seen++;
                continue;
            }
            if (item instanceof ASTBreak) return false;
            seen++;
        }
        return false;
    }

    private static boolean isDottedLeaderInlineImage(ASTInlineObject obj) {
        if (obj == null) return false;
        ASTInlineObject.ObjectKind kind = obj.kind();
        if (kind != ASTInlineObject.ObjectKind.IMAGE
                && kind != ASTInlineObject.ObjectKind.RENDERED_GROUP) {
            return false;
        }
        if (obj.width() < CoordinateConverter.pointsToHwpunits(20.0)) return false;
        if (obj.height() > CoordinateConverter.pointsToHwpunits(6.0)) return false;
        return looksLikeDottedHorizontalBitmap(obj.imageData());
    }

    private static boolean looksLikeDottedHorizontalBitmap(byte[] data) {
        if (data == null || data.length == 0) return false;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null || img.getWidth() < 16 || img.getHeight() < 1) return false;
            if (img.getHeight() > 12) return false;
            int bestRuns = 0;
            double bestCoverage = 0.0;
            for (int y = 0; y < img.getHeight(); y++) {
                int runs = 0;
                int ink = 0;
                boolean inInk = false;
                for (int x = 0; x < img.getWidth(); x++) {
                    boolean on = isInkPixel(img.getRGB(x, y));
                    if (on) {
                        ink++;
                        if (!inInk) {
                            runs++;
                            inInk = true;
                        }
                    } else {
                        inInk = false;
                    }
                }
                double coverage = ink / (double) img.getWidth();
                if (runs > bestRuns || (runs == bestRuns && coverage > bestCoverage)) {
                    bestRuns = runs;
                    bestCoverage = coverage;
                }
            }
            return bestRuns >= 4 && bestCoverage > 0.03 && bestCoverage < 0.75;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isInkPixel(int argb) {
        int alpha = (argb >>> 24) & 0xff;
        if (alpha < 32) return false;
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>> 8) & 0xff;
        int b = argb & 0xff;
        return Math.min(r, Math.min(g, b)) < 245;
    }

    private static long dottedLeaderTabStopPosition(
            ASTInlineObject obj, long contentLeft, long contentRight, double scale) {
        long fallback = Math.max(0L, contentRight - contentLeft);
        if (obj == null || obj.boundsX() < 0 || obj.width() <= 0) return fallback;
        long absoluteRight = CoordinateConverter.pointsToHwpunits(obj.boundsX() * scale) + obj.width();
        long pos = absoluteRight - contentLeft;
        if (pos <= 0) return fallback;
        return Math.max(pos, CoordinateConverter.pointsToHwpunits(8.0));
    }

    // 빈칸 채움선은 "탭 leader"가 아니라 "탭 run의 밑줄(UnderlineType.BOTTOM)"로 그린다.
    // 한글의 탭 leader(DOT/SOLID)는 줄 가운데에 그려져 strike-out처럼 보이기 때문(밑줄=베이스라인 필요).
    // 따라서 탭 정지점 leader는 NONE으로 두고, tabTextRunLike가 탭 run에 underline을 건다.
    private static void ensureDotLeaderTabStop(ASTParagraph para, long position) {
        if (para == null || position <= 0) return;
        long tol = CoordinateConverter.pointsToHwpunits(1.0);
        if (para.tabStops() != null) {
            for (ASTTabStop stop : para.tabStops()) {
                if (stop == null) continue;
                if (Math.abs(stop.position() - position) <= tol) {
                    stop.alignment("left");
                    stop.leader(null);
                    return;
                }
            }
        }
        para.addTabStop(new ASTTabStop(position, "left", null));
    }

    private static ASTTextRun tabTextRunLike(List<ASTInlineItem> items, int index) {
        ASTTextRun sample = nearestTextRun(items, index);
        ASTTextRun run = sample != null ? copyTextRun(sample, "\t") : new ASTTextRun();
        if (sample == null) run.text("\t");
        // 빈칸 채움선 = 탭 폭만큼의 베이스라인 밑줄(실선). 인접 텍스트의 밑줄 여부와 무관하게 강제.
        run.underline(true);
        run.underlineShape(null); // SOLID
        run.underlineColor("#000000");
        return run;
    }

    private static ASTTextRun nearestTextRun(List<ASTInlineItem> items, int index) {
        if (items == null || items.isEmpty()) return null;
        for (int i = index - 1; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTTextRun) return (ASTTextRun) item;
        }
        for (int i = index + 1; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTTextRun) return (ASTTextRun) item;
        }
        return null;
    }

    private static void normalizeDotLeaderPageNumberTabs(List<ASTTextFrameBlock> blocks) {
        if (blocks == null) return;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null) continue;
            for (ASTParagraph para : block.paragraphs()) {
                if (para == null || !para.hasTabStops()) continue;
                if (!hasTabRun(para) || !endsWithPageNumber(para)) continue;
                ASTTabStop rightmost = rightmostPositiveTabStop(para);
                if (rightmost != null) {
                    rightmost.leader(".");
                }
            }
        }
    }

    private static boolean hasTabRun(ASTParagraph para) {
        if (para == null || para.items() == null) return false;
        for (ASTInlineItem item : para.items()) {
            if (item == null || item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && text.indexOf('\t') >= 0) return true;
        }
        return false;
    }

    private static boolean endsWithPageNumber(ASTParagraph para) {
        if (para == null || para.items() == null) return false;
        for (int i = para.items().size() - 1; i >= 0; i--) {
            ASTInlineItem item = para.items().get(i);
            if (item == null || item.itemType() == ASTInlineItem.ItemType.BREAK) continue;
            if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) return false;
            String text = ((ASTTextRun) item).text();
            if (text == null || text.trim().isEmpty()) continue;
            return isPageNumberText(text);
        }
        return false;
    }

    private static ASTTabStop rightmostPositiveTabStop(ASTParagraph para) {
        if (para == null || para.tabStops() == null) return null;
        ASTTabStop rightmost = null;
        for (ASTTabStop stop : para.tabStops()) {
            if (stop == null || stop.position() <= 0) continue;
            if (rightmost == null || stop.position() > rightmost.position()) {
                rightmost = stop;
            }
        }
        return rightmost;
    }

    private static boolean isResolvedSingleLine(ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (tf.composedLines() != null && !tf.composedLines().isEmpty()) {
            return tf.composedLines().size() == 1;
        }
        return tf.lineCount() == 1;
    }

    /**
     * Spread XML 들을 스캔해 TextPath 가 있는 TextFrame 에 대해 TF id → TextPath story id 매핑을 만든다.
     * 부모 TF 의 본문이 비어있으면 TextPath 스토리로 대체하여 curved text 가 빠지지 않도록 한다.
     * 반환 map: key=TF decimal id (string), value=TextPath story decimal id (string).
     */
    private static Map<String, String> scanTextPathStorySubstitutions(ResolvedBuildContext ctx) {
        Map<String, String> map = new HashMap<>();
        if (ctx.idmlDir == null) return map;
        File spreadsDir = new File(ctx.idmlDir, "Spreads");
        if (!spreadsDir.isDirectory()) return map;
        File[] spreadFiles = spreadsDir.listFiles();
        if (spreadFiles == null) return map;
        for (File f : spreadFiles) {
            if (!f.getName().endsWith(".xml")) continue;
            try {
                String txt = new String(Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                // 각 <TextFrame Self="..."> 와 그 뒤 가장 가까운 <TextPath ParentStory="..."> 매칭.
                // depth 추적 없이 위치 기반 단순 매칭 — 실제 IDML 에서 TextPath 는 TF 의 직속 자식이므로 안전.
                Matcher tfM = TEXT_FRAME_PATTERN.matcher(txt);
                while (tfM.find()) {
                    String tfHex = tfM.group(1);
                    int tfEnd = tfM.end();
                    // 다음 TextFrame 까지의 범위 안에서 첫 TextPath 검색
                    int nextTfStart = txt.length();
                    Matcher nextTfM = TEXT_FRAME_PATTERN.matcher(txt);
                    if (nextTfM.find(tfEnd)) nextTfStart = nextTfM.start();
                    Matcher tpM = TEXT_PATH_PATTERN.matcher(txt.substring(tfEnd, nextTfStart));
                    if (tpM.find()) {
                        String tpHex = tpM.group(1);
                        try {
                            int tfDec = Integer.parseInt(tfHex, 16);
                            int tpDec = Integer.parseInt(tpHex, 16);
                            map.put(String.valueOf(tfDec), String.valueOf(tpDec));
                        } catch (NumberFormatException e) { /* skip */ }
                    }
                }
            } catch (Exception e) { /* skip file */ }
        }
        if (!map.isEmpty()) {
            System.err.println("[ResolvedToASTBuilder] Phase 3 사전 스캔: "
                    + map.size() + "개 TextPath story 매핑 (curved text → 부모 TF 본문)");
        }
        return map;
    }

    private static int resolvedStoryTextLength(ResolvedStory rs) {
        if (rs == null || rs.paragraphs() == null) return 0;
        int total = 0;
        for (ResolvedParagraph rp : rs.paragraphs()) {
            if (rp.runs() == null) continue;
            for (ResolvedRun r : rp.runs()) {
                if (r.text() != null) total += r.text().length();
            }
        }
        return total;
    }

    /** Story 의 총 문자 길이 (resolved 우선, 없으면 IDML XML 에서 fallback). */
    private static int storyTextLength(ResolvedBuildContext ctx, String storyId) {
        if (storyId == null) return 0;
        ResolvedStory rs = ctx.resolvedData.getStory(storyId);
        int total = resolvedStoryTextLength(rs);
        if (total > 0) return total;
        // Fallback: IDML 스토리 (TextPath 스토리는 resolved 에 없음)
        if (ctx.loadIDMLStory == null) return 0;
        try {
            IDMLStory ids = ctx.loadIDMLStory.apply(storyId);
            if (ids == null || ids.paragraphs() == null) return 0;
            int idmlTotal = 0;
            for (IDMLParagraph ip : ids.paragraphs()) {
                if (ip.characterRuns() == null) continue;
                for (IDMLCharacterRun run : ip.characterRuns()) {
                    if (run.content() != null) idmlTotal += run.content().length();
                }
            }
            return idmlTotal;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Story 가 비어있는지 (paragraphs 가 없거나 모든 텍스트가 공백) 확인. */
    private static boolean isStoryEmpty(ResolvedBuildContext ctx, String storyId) {
        if (storyId == null) return true;
        ResolvedStory rs = ctx.resolvedData.getStory(storyId);
        if (rs == null || rs.paragraphs() == null || rs.paragraphs().isEmpty()) return true;
        for (ResolvedParagraph rp : rs.paragraphs()) {
            if (rp.runs() == null) continue;
            for (ResolvedRun r : rp.runs()) {
                String t = r.text();
                if (t != null && !t.trim().isEmpty()) return false;
            }
        }
        return true;
    }

    private static void collectAboveLineAnchoredIds(ResolvedBuildContext ctx) {
        if (ctx.resolvedData == null || ctx.loadIDMLStory == null) return;
        for (ResolvedStory rs : ctx.resolvedData.stories()) {
            if (rs == null || rs.id() == null) continue;
            IDMLStory ids;
            try {
                ids = ctx.loadIDMLStory.apply(rs.id());
            } catch (Exception e) { continue; }
            if (ids == null) continue;
            // Story 본문 단락 + 테이블 셀 단락 모두 검사
            List<IDMLParagraph> allParas =
                    new ArrayList<>();
            if (ids.paragraphs() != null) allParas.addAll(ids.paragraphs());
            if (ids.tables() != null) {
                for (IDMLTable tbl : ids.tables()) {
                    if (tbl.rows() == null) continue;
                    for (IDMLTableRow row : tbl.rows()) {
                        if (row.cells() == null) continue;
                        for (IDMLTableCell cell : row.cells()) {
                            if (cell.paragraphs() != null) allParas.addAll(cell.paragraphs());
                        }
                    }
                }
            }
            for (IDMLParagraph ip : allParas) {
                if (ip.characterRuns() == null) continue;
                for (IDMLCharacterRun run : ip.characterRuns()) {
                    if (run.inlineGraphics() == null) continue;
                    for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                        String anchorPos = ig.anchoredPosition();
                        if ("AboveLine".equals(anchorPos)) {
                            String selfId = ig.selfId();
                            if (selfId != null && selfId.length() >= 2) {
                                try {
                                    ctx.aboveLineAnchoredIds.add(Integer.parseInt(selfId.substring(1), 16));
                                } catch (NumberFormatException e) { /* skip */ }
                            }
                            continue;
                        }
                    }
                }
            }
        }
        if (!ctx.aboveLineAnchoredIds.isEmpty()) {
            System.err.println("[ResolvedToASTBuilder] Phase 3 사전 스캔: "
                    + ctx.aboveLineAnchoredIds.size() + "개 AboveLine 앵커 수집 (배지 오분류 정정)");
        }
    }

    public static List<ASTParagraph> convertStoryParagraphs(ResolvedBuildContext ctx, ResolvedStory story) {
        List<ASTParagraph> paragraphs = new ArrayList<>();

        if (story.paragraphs() == null) return paragraphs;

        // 중복 단락 제거: InDesign의 overset/threaded frame에서
        // story.paragraphs가 중복 텍스트를 가진 단락을 반환하는 경우 감지
        String prevParaText = "";
        for (ResolvedParagraph rp : story.paragraphs()) {
            // 현재 단락 텍스트 추출
            // ExtendScript에서 paragraph.textStyleRanges[k].contents가 단락 경계를
            // 넘어 다음 단락 내용까지 포함한 경우를 대비: \r 이후 내용은 다음 단락 소속이므로 잘라냄
            StringBuilder sb = new StringBuilder();
            boolean truncated = false;
            if (rp.runs() != null) {
                for (ResolvedRun r : rp.runs()) {
                    if (r.text() != null) {
                        String t = r.text();
                        int crIdx = t.indexOf('\r');
                        if (crIdx >= 0) {
                            sb.append(t, 0, crIdx);
                            truncated = true;
                            break;
                        }
                        sb.append(t);
                    }
                }
            }
            String curText = sb.toString().trim();

            // 이전 단락 끝부분과 현재 단락이 겹치면 건너뜀
            if (prevParaText.length() > 10 && curText.length() > 10) {
                String prevTail = prevParaText.substring(Math.max(0, prevParaText.length() - 20));
                if (curText.startsWith(prevTail.substring(Math.min(prevTail.length(), 5)))) {
                    continue; // 중복 단락 건너뜀
                }
            }
            prevParaText = curText;
            // truncated=true이면 이후 run 처리에서도 \r에서 잘라서 현재 단락 경계에 맞춤
            boolean truncateAtCR = truncated;
            ASTParagraph para = new ASTParagraph();

            // 단락 스타일
            if (rp.styleName() != null) {
                para.paragraphStyleRef(rp.styleName());
            }

            // 단락 속성 (leading, spacing, indent는 InDesign에서 항상 pt 단위)
            if (rp.justification() != null) {
                para.alignment(rp.justification());
            } else if (rp.styleName() != null) {
                // 개별 단락에 justification 없으면 스타일에서 상속
                String styleJust = resolveStyleAlignment(rp.styleName(), ctx.astDocument);
                if (styleJust != null) para.alignment(styleJust);
            }
            Double fixedLeading = rp.fixedLeading();
            if (fixedLeading != null && fixedLeading > 0) {
                // InDesign Leading(pt) → HWPX 고정 줄간격(HWPUNIT)
                para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
                para.lineSpacingType("fixed");
            }
            if (rp.spaceBefore() != null && rp.spaceBefore() > 0) {
                para.spaceBefore(CoordinateConverter.pointsToHwpunits(rp.spaceBefore()));
            }
            if (rp.spaceAfter() != null && rp.spaceAfter() > 0) {
                para.spaceAfter(CoordinateConverter.pointsToHwpunits(rp.spaceAfter()));
            }
            boolean preserveHangingTab = StoryLoader.shouldPreserveNeutralHangingIndentForTab(rp);
            boolean neutralHangingIndent = StoryLoader.isNeutralHangingIndent(rp.leftIndent(), rp.firstLineIndent());
            if (neutralHangingIndent) {
                para.leftMargin(0L);
                para.firstLineIndent(0L);
            } else if (rp.leftIndent() != null && rp.leftIndent() != 0) {
                para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
            }
            if (!neutralHangingIndent && rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
                para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
            }
            if (rp.hasTabStops()) {
                double leftPt = (rp.leftIndent() != null ? rp.leftIndent() : 0);
                for (ResolvedTabStop rts : rp.tabStops()) {
                    if (rts.position() == null || rts.position() <= 0) continue;
                    double posPt = preserveHangingTab ? rts.position() : rts.position() - leftPt;
                    if (posPt < 0) posPt = 0;
                    para.addTabStop(new ASTTabStop(
                            CoordinateConverter.pointsToHwpunits(posPt),
                            mapResolvedTabAlignment(rts.alignment()),
                            rts.leader()));
                }
            }

            // 런 변환 (ResolvedParagraph → runs 직접)
            boolean stopAfterThisRun = false;
            boolean firstTextRunAfterLeadingAnchor = false;
            // source ownership policy: ACE 7 (IndentToHere) 감지 — 첫 inline anchor 의 너비를 paragraph leftMargin 으로
            // 적용해 InDesign 의 "1 ←여기부터 내려쓰기" 효과 재현 (예: 페이지 32 "가 같은 사건을..." 발문)
            ResolvedTextFrame firstAnchorTf = null;
            List<ResolvedRun> runs = rp.runs();
            if (runs != null) {
                int runIndex = -1;
                for (ResolvedRun run : runs) {
                    runIndex++;
                    if (isInlineAnchorRun(run)) {
                        Integer aid = run.anchoredObjectId();
                        if (aid != null) firstAnchorTf = ctx.resolvedData.getTextFrame(String.valueOf(aid));
                        break;
                    }
                    String t = run.text();
                    if (t != null && !t.isEmpty()) break; // 텍스트 먼저 나오면 anchor 패턴 아님
                }
            }
            if (runs != null) {
                int runIndex = -1;
                for (ResolvedRun run : runs) {
                    runIndex++;
                    if (stopAfterThisRun) break;
                    // inline_anchor: 인라인 그래픽 → ASTInlineObject로 변환
                    if (isInlineAnchorRun(run)) {
                        Integer anchoredId = run.anchoredObjectId();
                        if (anchoredId != null) {
                            if (isDoviraSubunitMarker(rp, runs, runIndex)
                                    && DoviraSubunitMarkerPolicy.isDuplicateMarkerStory(ctx.resolvedData, story.id())) {
                                continue;
                            }
                            if (!hasVisibleText(para)) {
                                firstTextRunAfterLeadingAnchor = true;
                            }
                            String prevRunText = adjacentRunText(runs, runIndex, -1);
                            String nextRunText = adjacentRunText(runs, runIndex, 1);
                            List<ASTInlineItem> plannedItems =
                                    InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, anchoredId,
                                            prevRunText, nextRunText);
                            if (plannedItems != null) {
                                for (ASTInlineItem item : plannedItems) para.addItem(item);
                                continue;
                            }
                            if (InlineFrameHandler.hasOwnershipPlanForAnchorBundle(ctx, anchoredId)) {
                                continue;
                            }
                            ASTInlineObject earlyGroupShell =
                                    InlineFrameHandler.tryInlineGroupShellWithEditableChild(ctx, anchoredId);
                            if (earlyGroupShell != null) {
                                para.addItem(earlyGroupShell);
                                continue;
                            }
                            if (InlineFrameHandler.hasTextBlockPlacedDescendant(ctx, anchoredId)) {
                                continue;
                            }
                            // 커스텀 위치 앵커가 부모 범위 밖이면 인라인 흐름에는 넣지 않는다.
                            if (!InlineFrameHandler.shouldKeepAnchoredInlineByOwnershipPlan(ctx, anchoredId)
                                    && InlineFrameHandler.isAnchoredOutsideParent(ctx, anchoredId, story.id())) {
                                continue;
                            }
                            // 다수 박스(예: ㅍ ㅎ ㅂ ㅅ 자모 배지) → 각 TF 를 박스 스타일 INLINE_TEXT_FRAME 으로 분해
                            List<ASTInlineObject> boxList =
                                    InlineFrameHandler.tryInlineGroupAsBoxList(ctx, anchoredId);
                            if (boxList != null && !boxList.isEmpty()) {
                                for (ASTInlineObject box : boxList) para.addItem(box);
                                continue;
                            }
                            ASTInlineObject shapeShell =
                                    InlineFrameHandler.tryInlineShapeWithEditableChildAsShell(ctx, anchoredId);
                            if (shapeShell != null) {
                                para.addItem(shapeShell);
                                continue;
                            }
                            if (InlineFrameHandler.isSimpleButtonLabelAnchor(ctx, anchoredId)) {
                                ASTInlineObject inlineObj =
                                        SimpleButtonLabelInlineFactory.create(ctx, anchoredId);
                                if (inlineObj != null) {
                                    para.addItem(inlineObj);
                                    continue;
                                }
                            }
                            // 배경 도형 + 단일 짧은 텍스트프레임 (예: "가" / "나" 캡슐 배지)
                            // → INLINE_TEXT_FRAME (한 몸 + 검색 가능)
                            ASTInlineObject singleBadge = InlineFrameHandler.tryInlineGroupAsSingleBadge(ctx, anchoredId);
                            if (singleBadge != null) {
                                para.addItem(singleBadge);
                                continue;
                            }
                            // 하위 인라인 TF 안에 다시 ORC 앵커가 있는 경우
                            // 텍스트 런으로 평탄화하지 말고 배지/박스 + 텍스트 순서를 보존한다.
                            List<ASTInlineItem> nestedItems =
                                    InlineFrameHandler.tryInlineTextFrameAsItems(ctx, anchoredId,
                                            prevRunText, nextRunText);
                            if (nestedItems != null && !nestedItems.isEmpty()) {
                                for (ASTInlineItem item : nestedItems) para.addItem(item);
                                continue;
                            }
                            // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환.
                            // 배경+텍스트 배지는 위에서 먼저 처리해 ORC 텍스트가 일반 런으로 중복 삽입되지 않게 한다.
                            ASTTextRun textRun = InlineFrameHandler.tryInlineTextFrameAsRun(ctx, anchoredId,
                                    prevRunText, nextRunText);
                            if (textRun != null) {
                                if (!InlineFrameHandler.isInlineVocabularyMarker(ctx, anchoredId, prevRunText, nextRunText)) {
                                    maybeInsertDecorativeLeaderTab(ctx, rp, anchoredId, para);
                                }
                                para.addItem(textRun);
                                continue;
                            }
                            if (InlineFrameHandler.isInlineTextShellCompanionForEditableText(ctx, anchoredId)) {
                                continue;
                            }
                            ASTInlineObject inlineObj = InlineFrameHandler.loadInlineObject(ctx, anchoredId);
                            if (inlineObj != null) {
                                para.addItem(inlineObj);
                                continue;
                            }
                            // PNG도 텍스트도 없는 인라인 앵커 → 빈칸 공백으로 대체
                            ASTTextRun spaceRun = InlineFrameHandler.createSpaceRunForEmptyAnchor(ctx, anchoredId);
                            if (spaceRun != null) {
                                para.addItem(spaceRun);
                                continue;
                            }
                        }
                        continue; // 로드 실패 시 건너뜀
                    }

                    String runText = run.text();
                    // 단락 경계 넘김 방지: \r 이후는 다음 단락 내용 → 잘라내고 루프 종료
                    if (truncateAtCR && runText != null) {
                        int crIdx = runText.indexOf('\r');
                        if (crIdx >= 0) {
                            runText = runText.substring(0, crIdx);
                            stopAfterThisRun = true;
                        }
                    }
                    // 특수 제어 문자 제거 (IDML 경로와 동일)
                    boolean preserveUnderlineBlank = RunBuilder.hasUnderlineIntent(null, run);
                    if (runText != null) {
                        if (firstTextRunAfterLeadingAnchor && !preserveUnderlineBlank) {
                            runText = stripLeadingAnchorLayoutSpaces(runText);
                        }
                        // source ownership policy: ACE 7 (IndentToHere, \u0007 or \u0008) 감지 — 첫 inline anchor 이후
                        // 처음 등장한 위치에서 후속 줄 좌측 들여쓰기를 위해 paragraph leftMargin 적용
                        if (firstAnchorTf != null && para.leftMargin() == null) {
                            int aceIdx = runText.indexOf('\u0007');
                            if (aceIdx < 0) aceIdx = runText.indexOf('\u0008');
                            if (aceIdx >= 0) {
                                double[] gbA = firstAnchorTf.geometricBounds();
                                if (gbA != null && gbA.length >= 4) {
                                    double anchorW = Math.abs(gbA[3] - gbA[1]);
                                    if (anchorW > 0) {
                                        para.leftMargin(CoordinateConverter.pointsToHwpunits(anchorW));
                                    }
                                }
                            }
                        }
                    }
                    firstTextRunAfterLeadingAnchor = false;
                    TextStyleApplicator.ResolvedStyleOptions styleOptions =
                            new TextStyleApplicator.ResolvedStyleOptions();
                    styleOptions.proportionalScaleAsFontSize = true;
                    styleOptions.applyVerticalScale = false;
                    List<ASTTextRun> textRuns = ResolvedTextFlowAstConverter.convertRunText(
                            runText,
                            run,
                            para,
                            ResolvedTextFlowAstConverter.options()
                                    .colorResolver(color -> RunBuilder.resolveColorToHex(ctx, color))
                                    .preserveUnderlineBlank(preserveUnderlineBlank)
                                    .styleOptions(styleOptions)
                                    .truncateAtParagraphBreak(false));
                    for (ASTTextRun textRun : textRuns) {
                        if (preserveUnderlineBlank || Boolean.TRUE.equals(run.underline())) {
                            textRun.underline(true);
                        }
                        para.addItem(textRun);
                    }
                }
            }

            // 인라인 객체 boundsX 기반 재정렬
            ASTTableConverter.reorderInlineObjectsByBoundsX(para);

            applyTrailingPageNumberLeader(para, rp);
            paragraphs.add(para);
        }

        postprocessResolvedStoryParagraphs(ctx, story.id(), paragraphs);
        return paragraphs;
    }

    private static List<ASTParagraph> convertTextOnlyStoryParagraphsFromTextFlowIfSafe(
            ResolvedBuildContext ctx,
            String storyId) {
        if (ctx == null || ctx.textFlowDocument == null || storyId == null) return null;
        TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(storyId);
        if (!isSafeTextOnlyTextFlowUnit(unit)) return null;

        TextStyleApplicator.ResolvedStyleOptions styleOptions =
                new TextStyleApplicator.ResolvedStyleOptions();
        styleOptions.proportionalScaleAsFontSize = true;
        styleOptions.applyVerticalScale = false;
        List<ASTParagraph> paragraphs = TextFlowAstMaterializer.convertUnit(
                ctx,
                unit,
                null,
                null,
                ResolvedTextFlowAstConverter.options()
                        .colorResolver(color -> RunBuilder.resolveColorToHex(ctx, color))
                        .styleOptions(styleOptions)
                        .truncateAtParagraphBreak(false));
        if (paragraphs == null || paragraphs.isEmpty()) return null;
        postprocessResolvedStoryParagraphs(ctx, storyId, paragraphs);
        return paragraphs;
    }

    private static boolean isSafeTextOnlyTextFlowUnit(TextFlowDocument.TextFlowUnit unit) {
        if (unit == null || unit.paragraphs == null || unit.paragraphs.isEmpty()) return false;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null || paragraph.sourceParagraph == null || paragraph.atoms == null) {
                return false;
            }
            for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
                if (atom instanceof TextFlowDocument.InlineSlotAtom) {
                    return false;
                }
                if (!(atom instanceof TextFlowDocument.TextAtom)) {
                    continue;
                }
                TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
                String text = textAtom.text;
                if (text == null || text.isEmpty()) continue;
                if (text.indexOf('\r') >= 0 || text.indexOf('\uFFFC') >= 0) {
                    return false;
                }
                ResolvedRun run = textAtom.sourceRun;
                if (run == null || run.isInlineAnchor()) {
                    return false;
                }
                if (Boolean.TRUE.equals(run.underline())) {
                    return false;
                }
                String charStyle = run.charStyle();
                if (charStyle != null) {
                    String lower = charStyle.toLowerCase(Locale.ROOT);
                    if (charStyle.contains("밑줄") || lower.contains("underline")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void postprocessResolvedStoryParagraphs(
            ResolvedBuildContext ctx,
            String storyId,
            List<ASTParagraph> paragraphs) {
        if (paragraphs == null) return;
        for (ASTParagraph para : paragraphs) {
            MathProcessor.convertMathRunsInParagraph(ctx, para);
            RunPostProcessor.splitOverlineRuns(para);
        }
        removeDuplicateDoviraLeadingMarkers(ctx, storyId, paragraphs);
    }

    static void removeDuplicateDoviraLeadingMarkers(ResolvedBuildContext ctx,
                                                    String storyId,
                                                    List<ASTParagraph> paragraphs) {
        if (ctx == null || ctx.resolvedData == null || paragraphs == null || paragraphs.isEmpty()) return;
        if (!DoviraSubunitMarkerPolicy.isDuplicateMarkerStory(ctx.resolvedData, storyId)) return;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null || paragraph.items().isEmpty()) continue;
            paragraph.dropLeadingSmallInlineObjects(true);
            for (int i = 0; i < paragraph.items().size(); ) {
                ASTInlineItem item = paragraph.items().get(i);
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text == null || text.replace("\uFFFC", "").trim().isEmpty()) {
                        i++;
                        continue;
                    }
                    break;
                }
                if (isSmallDoviraMarkerObject(item)) {
                    paragraph.items().remove(i);
                    continue;
                }
                break;
            }
        }
    }

    private static boolean isSmallDoviraMarkerObject(ASTInlineItem item) {
        if (!(item instanceof ASTInlineObject)) return false;
        ASTInlineObject obj = (ASTInlineObject) item;
        if (obj.kind() != ASTInlineObject.ObjectKind.IMAGE
                && obj.kind() != ASTInlineObject.ObjectKind.RENDERED_GROUP) {
            return false;
        }
        return obj.width() > 0
                && obj.height() > 0
                && obj.width() <= CoordinateConverter.pointsToHwpunits(35)
                && obj.height() <= CoordinateConverter.pointsToHwpunits(25);
    }

    /**
     * paragraphStyles에서 스타일 이름으로 alignment를 조회한다.
     */
    static String resolveStyleAlignment(String styleName, ASTDocument doc) {
        if (styleName == null || doc == null) return null;
        for (ASTStyleDef sd : doc.paragraphStyles()) {
            if (styleName.equals(sd.styleName())) {
                return sd.alignment();
            }
        }
        return null;
    }

    private static void maybeInsertDecorativeLeaderTab(ResolvedBuildContext ctx,
                                                       ResolvedParagraph rp,
                                                       int anchoredId,
                                                       ASTParagraph para) {
        if (ctx == null || rp == null || para == null) return;
        if (!hasVisibleText(para)) return;
        if (!isResolvedPageNumberFrame(ctx, anchoredId)) return;
        if (!hasDecorativeResolvedParagraphRule(ctx, rp)) return;
        if (TextFlowTabPolicy.paragraphEndsWithTab(para)) return;
        if (!enableRightmostDotLeader(para, rp)) return;

        ASTTextRun tabRun = new ASTTextRun();
        tabRun.text("\t");
        tabRun.textColor("#000000");
        para.addItem(tabRun);
    }

    private static void applyTrailingPageNumberLeader(ASTParagraph para, ResolvedParagraph rp) {
        if (para == null || para.items() == null || para.items().size() < 2) return;
        if (!hasVisibleText(para)) return;

        List<ASTInlineItem> items = para.items();
        for (int i = items.size() - 1; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (item == null || item.itemType() == ASTInlineItem.ItemType.BREAK) continue;
            if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) return;

            String text = ((ASTTextRun) item).text();
            if (text == null || text.trim().isEmpty()) continue;
            if (!isPageNumberText(text)) return;
            if (TextFlowTabPolicy.hasTabImmediatelyBefore(items, i)) return;
            if (!enableRightmostDotLeader(para, rp)) return;

            ASTTextRun tabRun = new ASTTextRun();
            tabRun.text("\t");
            tabRun.textColor("#000000");
            items.add(i, tabRun);
            return;
        }
    }

    static String stripLeadingAnchorLayoutSpaces(String text) {
        if (text == null || text.isEmpty()) return text;

        int i = 0;
        boolean sawAnchorLayoutControl = false;
        boolean sawLeadingSpace = false;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\u00A0') {
                sawLeadingSpace = true;
                i++;
            } else if (isAnchorLayoutControl(ch)) {
                sawAnchorLayoutControl = true;
                i++;
            } else {
                break;
            }
        }
        if (!sawAnchorLayoutControl) return text;
        if (i >= text.length()) return "";
        return sawLeadingSpace ? " " + text.substring(i) : text.substring(i);
    }

    private static boolean isAnchorLayoutControl(char ch) {
        return ch == '\u0003' || ch == '\u0007' || ch == '\u0008' || ch == '\n';
    }

    private static boolean isPageNumberText(String text) {
        if (text == null) return false;
        String cleaned = text.replace("\r", "").replace("\n", "").trim();
        return cleaned.matches("\\d{1,4}");
    }

    private static boolean hasVisibleText(ASTParagraph para) {
        if (para.items() == null) return false;
        for (ASTInlineItem item : para.items()) {
            if (item == null || item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && !text.trim().isEmpty()) return true;
        }
        return false;
    }

    private static boolean isInlineAnchorRun(ResolvedRun run) {
        return run != null && run.isInlineAnchor();
    }

    private static String adjacentRunText(List<ResolvedRun> runs, int index, int direction) {
        if (runs == null || direction == 0) return null;
        for (int i = index + direction; i >= 0 && i < runs.size(); i += direction) {
            ResolvedRun r = runs.get(i);
            if (r == null || isInlineAnchorRun(r)) continue;
            String text = r.text();
            if (text == null || text.isEmpty()) continue;
            return text;
        }
        return null;
    }

    private static boolean isResolvedPageNumberFrame(ResolvedBuildContext ctx, int anchoredId) {
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredId));
        if (tf == null) return false;
        String text = tf.frameVisibleText();
        if ((text == null || text.trim().isEmpty()) && tf.storyId() != null) {
            ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
            if (story != null) {
                StringBuilder sb = new StringBuilder();
                for (ResolvedParagraph rp : story.paragraphs()) {
                    if (rp.runs() == null) continue;
                    for (ResolvedRun rr : rp.runs()) {
                        if (rr.text() != null) sb.append(rr.text());
                    }
                }
                text = sb.toString();
            }
        }
        if (text == null) return false;
        String cleaned = text.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
        if (!cleaned.matches("\\d{1,4}")) return false;
        double[] gb = tf.geometricBounds();
        if (gb == null || gb.length < 4) return true;
        double w = Math.abs(gb[3] - gb[1]);
        double h = Math.abs(gb[2] - gb[0]);
        return w <= 40.0 && h <= 25.0;
    }

    private static boolean hasDecorativeResolvedParagraphRule(ResolvedBuildContext ctx, ResolvedParagraph rp) {
        if (ctx.styleResolver == null || rp.styleName() == null) return rp.hasTabStops();
        IDMLStyleDef style = ctx.styleResolver.getResolvedParagraphStyle(rp.styleName());
        if (style == null) style = ctx.styleResolver.getResolvedParagraphStyle("ParagraphStyle/" + rp.styleName());
        if (style == null) return rp.hasTabStops();
        if (positive(style.ruleAboveLineWeight()) || positive(style.ruleBelowLineWeight())) return true;
        return positive(style.underlineWeight()) && positive(style.underlineOffset());
    }

    static boolean isDoviraSubunitMarker(ResolvedParagraph rp,
                                         List<ResolvedRun> runs,
                                         int runIndex) {
        if (rp == null || runs == null || runIndex < 0 || runIndex >= runs.size()) return false;
        if (!DoviraSubunitMarkerPolicy.isDoviraSubunitParagraph(rp)) return false;
        ResolvedRun current = runs.get(runIndex);
        return current != null && isInlineAnchorRun(current);
    }

    static boolean isStandaloneDoviraSubunitMarker(ResolvedParagraph rp) {
        if (!DoviraSubunitMarkerPolicy.isDoviraSubunitParagraph(rp)) return false;
        List<ResolvedRun> runs = rp.runs();
        if (runs == null || runs.isEmpty()) return false;
        boolean hasAnchor = false;
        for (ResolvedRun run : runs) {
            if (run == null) continue;
            if (isInlineAnchorRun(run)) {
                hasAnchor = true;
                continue;
            }
            String text = run.text();
            if (text == null) continue;
            String cleaned = text.replace("\uFFFC", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim();
            if (!cleaned.isEmpty()) return false;
        }
        return hasAnchor;
    }

    private static boolean positive(Double value) {
        return value != null && value > 0.0;
    }

    private static boolean enableRightmostDotLeader(ASTParagraph para, ResolvedParagraph rp) {
        if (!para.hasTabStops() && rp.hasTabStops()) {
            double leftPt = (rp.leftIndent() != null ? rp.leftIndent() : 0);
            for (ResolvedTabStop rts : rp.tabStops()) {
                if (rts.position() == null || rts.position() <= 0) continue;
                double posPt = rts.position() - leftPt;
                if (posPt < 0) posPt = 0;
                para.addTabStop(new ASTTabStop(
                        CoordinateConverter.pointsToHwpunits(posPt),
                        mapResolvedTabAlignment(rts.alignment()),
                        rts.leader()));
            }
        }
        if (!para.hasTabStops() || para.tabStops().size() < 2) return false;
        ASTTabStop rightmost = null;
        for (ASTTabStop stop : para.tabStops()) {
            if (stop == null || stop.position() <= 0) continue;
            if (rightmost == null || stop.position() > rightmost.position()) {
                rightmost = stop;
            }
        }
        if (rightmost == null) return false;
        rightmost.leader(".");
        return true;
    }

    private static String mapResolvedTabAlignment(String alignment) {
        if (alignment == null) return "left";
        String a = alignment.toLowerCase();
        if (a.contains("center")) return "center";
        if (a.contains("right")) return "right";
        if (a.contains("decimal")) return "decimal";
        return "left";
    }

    private static boolean hasEquationFont(List<ASTParagraph> paragraphs) {
        for (ASTParagraph p : paragraphs) {
            for (Object item : p.items()) {
                if (item instanceof ASTEquation) return true;
                if (item instanceof ASTTextRun) {
                    String ff = ((ASTTextRun) item).fontFamily();
                    if (ff != null && (EHFontGlyphMap.isEHFontFamily(ff)
                            || ff.contains("BT수식") || ff.contains("NP"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


}
