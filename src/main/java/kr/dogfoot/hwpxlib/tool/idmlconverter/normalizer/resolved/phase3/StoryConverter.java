package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.MatchConfidence;
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
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.RunPropertyResolver;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

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

    private StoryConverter() {}

    /** ParagraphStyle에서 미리 구한 스타일 속성 (런에서 없을 때 폴백용) */
    static class StyleContext {
        final String fillColor;
        final Double tracking;
        final String fontFamily;
        final Double fontSize;
        boolean hasTabStops;

        StyleContext(String fillColor, Double tracking, String fontFamily, Double fontSize) {
            this.fillColor = fillColor;
            this.tracking = tracking;
            this.fontFamily = fontFamily;
            this.fontSize = fontSize;
        }
    }

    // ═══════════════════════════════════════════════════
    // Phase 3: Story→단락→런 변환
    // ═══════════════════════════════════════════════════

    public static void convertStories(ResolvedBuildContext ctx, List<ASTSection> sections) {
        // TextFrameBlock에 Story 텍스트 연결
        // storyId → TextFrameBlock 매핑
        Map<String, List<ASTTextFrameBlock>> storyToBlocks = new HashMap<>();
        for (ASTSection sec : sections) {
            for (ASTBlock blk : sec.blocks()) {
                if (blk instanceof ASTTextFrameBlock) {
                    ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                    // composedLines 경로에서 이미 단락이 생성된 블록은 건너뜀
                    if (tfb.paragraphs() != null && !tfb.paragraphs().isEmpty()) continue;
                    String sourceId = tfb.sourceId();
                    if (sourceId == null) continue;
                    // sourceId → DOM decimal → textFrame → storyId
                    // SPEC-025: master instance ("_pi" 접미사) 도 처리
                    String domId = ParagraphTextHelpers.domIdFromSourceId(sourceId);
                    if (domId == null) continue;
                    ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId);
                    if (rtf != null && rtf.storyId() != null) {
                        storyToBlocks.computeIfAbsent(rtf.storyId(), k -> new ArrayList<>()).add(tfb);
                    }
                }
            }
        }

        System.err.println("[ResolvedToASTBuilder] Phase 3: " + storyToBlocks.size() + " stories matched to TextFrameBlocks");

        // 각 Story → 단락 변환 후 TextFrameBlock에 분배
        // IDML Story XML 우선, 없으면 resolved fallback
        int totalParas = 0;
        int idmlCount = 0;
        int resolvedCount = 0;
        for (Map.Entry<String, List<ASTTextFrameBlock>> entry : storyToBlocks.entrySet()) {
            String storyId = entry.getKey();
            List<ASTTextFrameBlock> blocks = entry.getValue();

            // 1차: IDML Story XML에서 단락 파싱 (정확한 단락 구조)
            List<ASTParagraph> paragraphs = StoryLoader.convertStoryFromIDML(ctx, storyId);
            boolean useIdml = paragraphs != null && !paragraphs.isEmpty();

            // IDML-SHORT/PARA-MISMATCH 감지: resolved fallback 전환 조건
            // 1) IDML 텍스트가 resolved의 30% 미만 (불릿 전용 Story 등)
            // 2) IDML 단락 수가 resolved의 50% 미만 (강제 줄바꿈이 단락으로 처리되지 않는 경우)
            // 단, EH/BT/NP 수식 폰트가 포함된 story는 fallback하지 않음 (IDML 수식 변환이 우수)
            if (useIdml) {
                boolean hasEHMathFont = false;
                for (ASTParagraph p : paragraphs) {
                    for (Object item : p.items()) {
                        if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) {
                            hasEHMathFont = true;
                            break;
                        }
                        if (item instanceof ASTTextRun) {
                            String ff = ((ASTTextRun) item).fontFamily();
                            if (ff != null && (EHFontGlyphMap.isEHFontFamily(ff)
                                    || ff.contains("BT수식") || ff.contains("NP"))) {
                                hasEHMathFont = true;
                                break;
                            }
                        }
                    }
                    if (hasEHMathFont) break;
                }

                if (!hasEHMathFont) {
                    ResolvedStory rs = ctx.resolvedData.getStory(storyId);
                    if (rs != null) {
                        int idmlLen = 0;
                        for (ASTParagraph p : paragraphs)
                            for (Object item : p.items())
                                if (item instanceof ASTTextRun) idmlLen += ((ASTTextRun) item).text() != null ? ((ASTTextRun) item).text().length() : 0;
                        int resolvedLen = 0;
                        for (ResolvedParagraph rp : rs.paragraphs())
                            if (rp.runs() != null)
                                for (ResolvedRun r : rp.runs())
                                    resolvedLen += r.text() != null ? r.text().length() : 0;
                        if (resolvedLen > 10 && idmlLen < resolvedLen * 0.3) {
                            useIdml = false; // 텍스트 길이 부족 → resolved fallback
                        }
                        // 단락 수 불일치: IDML 1~2개 단락인데 resolved 5개 이상이면 강제 줄바꿈 누락
                        int resolvedParaCount = rs.paragraphs().size();
                        if (paragraphs.size() <= 2 && resolvedParaCount >= 5) {
                            useIdml = false; // 단락 구조 불일치 → resolved fallback
                        }
                    }
                }
            }

            if (useIdml) {
                idmlCount++;
            } else {
                // 2차: resolved.json fallback
                ResolvedStory story = ctx.resolvedData.getStory(storyId);
                if (story == null) continue;
                paragraphs = convertStoryParagraphs(ctx, story);
                resolvedCount++;
            }
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
            ParagraphDistributor.distributeParagraphs(ctx, paragraphs, blocks, storyId);
        }
        System.err.println("[ResolvedToASTBuilder] Phase 3: " + totalParas + " paragraphs converted (IDML=" + idmlCount + " resolved=" + resolvedCount + ")");
    }


    private static List<ASTParagraph> convertStoryParagraphs(ResolvedBuildContext ctx, ResolvedStory story) {
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
            if (rp.leftIndent() != null && rp.leftIndent() != 0) {
                para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
            }
            if (rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
                para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
            }

            // 런 변환 (ResolvedParagraph → runs 직접)
            boolean stopAfterThisRun = false;
            if (rp.runs() != null) {
                for (ResolvedRun run : rp.runs()) {
                    if (stopAfterThisRun) break;
                    // inline_anchor: 인라인 그래픽 → ASTInlineObject로 변환
                    if (run.isInlineAnchor()) {
                        Integer anchoredId = run.anchoredObjectId();
                        if (anchoredId != null) {
                            // 커스텀 위치 앵커 객체 건너뛰기
                            if (InlineFrameHandler.isAnchoredOutsideParent(ctx, anchoredId, story.id())) {
                                continue;
                            }
                            // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환 우선
                            ASTTextRun textRun = InlineFrameHandler.tryInlineTextFrameAsRun(ctx, anchoredId);
                            if (textRun != null) {
                                para.addItem(textRun);
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

                    ASTTextRun textRun = new ASTTextRun();
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
                    if (runText != null) {
                        runText = runText.replace("\t\u0008", "");
                        runText = runText.replace("\u0008", "");
                        runText = runText.replace("\n", "");
                        runText = runText.replace("\r", "");
                        // overline marker: Ó(0xD3) → \uE000{letters}\uE001 마커로 치환
                        if (runText.indexOf('\u00D3') >= 0) {
                            runText = RunPostProcessor.markOverlineSegments(runText);
                        }
                        if (!para.hasTabStops()) {
                            runText = runText.replace("\t", " ");
                        }
                    }
                    textRun.text(runText);
                    textRun.fontFamily(run.fontFamily());
                    if (run.fontSize() != null && run.fontSize() > 0) {
                        textRun.fontSizeHwpunits((int) Math.round(run.fontSize() * 100));
                    }
                    textRun.fontStyle(run.fontStyle());
                    textRun.textColor(RunBuilder.resolveColorToHex(ctx, run.fillColor()));
                    if (run.tracking() != null && run.tracking() != 0) {
                        textRun.letterSpacing((short) Math.round(run.tracking() / 10.0));
                    }
                    if (run.horizontalScale() != null && run.horizontalScale() != 0 && run.horizontalScale() != 100) {
                        Double vs = run.verticalScale();
                        if (vs != null && Math.abs(run.horizontalScale() - vs) < 1.0) {
                            // 비례 확대: fontSize에 반영
                            if (textRun.fontSizeHwpunits() != null) {
                                textRun.fontSizeHwpunits((int) Math.round(textRun.fontSizeHwpunits() * run.horizontalScale() / 100.0));
                            }
                        } else {
                            textRun.horizontalScale((short) run.horizontalScale().doubleValue());
                        }
                    }
                    if (run.baselineShift() != null && run.baselineShift() != 0) {
                        textRun.baselineShift((short) run.baselineShift().doubleValue());
                    }
                    para.addItem(textRun);
                }
            }

            // 인라인 객체 boundsX 기반 재정렬
            ASTTableConverter.reorderInlineObjectsByBoundsX(para);

            paragraphs.add(para);
        }

        // 수식 폰트 런 → ASTEquation 변환 (단락별 후처리)
        for (ASTParagraph para : paragraphs) {
            MathProcessor.convertMathRunsInParagraph(ctx, para);
            RunPostProcessor.splitOverlineRuns(para);
        }

        return paragraphs;
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


}
