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
        // PRE: IDML 의 AnchoredPosition="Anchored" + TextWrapMode="None" InlineGraphic 들을
        // 미리 스캔해서 deferredAnchoredFloatingIds 에 등록. 두 경로(IDML/resolved) 모두
        // 인라인 배치 시 이 ID 들을 건너뛰고, 후처리가 floating ASTFigure 로 배치한다.
        prepopulateAnchoredFloatingIds(ctx);

        // PRE: Spread XML 에서 TextPath 매핑 (TF id → TextPath storyId) 미리 추출.
        // curved text 가 부모 TF 의 빈 콘텐츠를 채우도록 한다 (직선으로 그대로 표시).
        java.util.Map<String, String> textPathStorySub = scanTextPathStorySubstitutions(ctx);

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
                        String storyId = rtf.storyId();
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

        // Phase 3 후처리: AnchoredPosition="Anchored" + TextWrapMode="None" Group 들을
        // BEHIND_TEXT 위치-절대 ASTFigure 로 배치 (텍스트 겹침, 밀지 않음).
        placeDeferredAnchoredFloating(ctx, sections);
    }

    /**
     * Spread XML 들을 스캔해 TextPath 가 있는 TextFrame 에 대해 TF id → TextPath story id 매핑을 만든다.
     * 부모 TF 의 본문이 비어있으면 TextPath 스토리로 대체하여 curved text 가 빠지지 않도록 한다.
     * 반환 map: key=TF decimal id (string), value=TextPath story decimal id (string).
     */
    private static java.util.Map<String, String> scanTextPathStorySubstitutions(ResolvedBuildContext ctx) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (ctx.idmlDir == null) return map;
        java.io.File spreadsDir = new java.io.File(ctx.idmlDir, "Spreads");
        if (!spreadsDir.isDirectory()) return map;
        java.util.regex.Pattern tfPattern = java.util.regex.Pattern.compile(
                "<TextFrame\\s+Self=\"u([0-9a-f]+)\"");
        java.util.regex.Pattern tpPattern = java.util.regex.Pattern.compile(
                "<TextPath\\s+Self=\"[^\"]+\"\\s+ParentStory=\"u([0-9a-f]+)\"");
        for (java.io.File f : spreadsDir.listFiles()) {
            if (!f.getName().endsWith(".xml")) continue;
            try {
                String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                // 각 <TextFrame Self="..."> 와 그 뒤 가장 가까운 <TextPath ParentStory="..."> 매칭.
                // depth 추적 없이 위치 기반 단순 매칭 — 실제 IDML 에서 TextPath 는 TF 의 직속 자식이므로 안전.
                java.util.regex.Matcher tfM = tfPattern.matcher(txt);
                while (tfM.find()) {
                    String tfHex = tfM.group(1);
                    int tfEnd = tfM.end();
                    // 다음 TextFrame 까지의 범위 안에서 첫 TextPath 검색
                    int nextTfStart = txt.length();
                    java.util.regex.Matcher nextTfM = tfPattern.matcher(txt);
                    if (nextTfM.find(tfEnd)) nextTfStart = nextTfM.start();
                    java.util.regex.Matcher tpM = tpPattern.matcher(txt.substring(tfEnd, nextTfStart));
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

    /** Story 의 총 문자 길이 (resolved 우선, 없으면 IDML XML 에서 fallback). */
    private static int storyTextLength(ResolvedBuildContext ctx, String storyId) {
        if (storyId == null) return 0;
        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory rs = ctx.resolvedData.getStory(storyId);
        if (rs != null && rs.paragraphs() != null) {
            int total = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph rp : rs.paragraphs()) {
                if (rp.runs() == null) continue;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun r : rp.runs()) {
                    if (r.text() != null) total += r.text().length();
                }
            }
            if (total > 0) return total;
        }
        // Fallback: IDML 스토리 (TextPath 스토리는 resolved 에 없음)
        if (ctx.loadIDMLStory == null) return 0;
        try {
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory ids = ctx.loadIDMLStory.apply(storyId);
            if (ids == null || ids.paragraphs() == null) return 0;
            int total = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph ip : ids.paragraphs()) {
                if (ip.characterRuns() == null) continue;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : ip.characterRuns()) {
                    if (run.content() != null) total += run.content().length();
                }
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Story 가 비어있는지 (paragraphs 가 없거나 모든 텍스트가 공백) 확인. */
    private static boolean isStoryEmpty(ResolvedBuildContext ctx, String storyId) {
        if (storyId == null) return true;
        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory rs = ctx.resolvedData.getStory(storyId);
        if (rs == null || rs.paragraphs() == null || rs.paragraphs().isEmpty()) return true;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph rp : rs.paragraphs()) {
            if (rp.runs() == null) continue;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun r : rp.runs()) {
                String t = r.text();
                if (t != null && !t.trim().isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * 모든 IDML Story 를 스캔해 AnchoredPosition="Anchored" + TextWrapMode="None" InlineGraphic 의
     * Group ID 를 ctx.deferredAnchoredFloatingIds 에 미리 등록한다. 이 ID 들은 인라인 배치를
     * 건너뛰고 후처리가 BEHIND_TEXT 로 절대 위치에 배치된다.
     */
    private static void prepopulateAnchoredFloatingIds(ResolvedBuildContext ctx) {
        if (ctx.resolvedData == null || ctx.loadIDMLStory == null) return;
        int found = 0;
        int storiesScanned = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory rs : ctx.resolvedData.stories()) {
            if (rs == null || rs.id() == null) continue;
            storiesScanned++;
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory ids;
            try {
                ids = ctx.loadIDMLStory.apply(rs.id());
            } catch (Exception e) { continue; }
            if (ids == null) continue;
            // Story 본문 단락 + 테이블 셀 단락 모두 검사
            java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph> allParas =
                    new java.util.ArrayList<>();
            if (ids.paragraphs() != null) allParas.addAll(ids.paragraphs());
            if (ids.tables() != null) {
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable tbl : ids.tables()) {
                    if (tbl.rows() == null) continue;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow row : tbl.rows()) {
                        if (row.cells() == null) continue;
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell : row.cells()) {
                            if (cell.paragraphs() != null) allParas.addAll(cell.paragraphs());
                        }
                    }
                }
            }
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph ip : allParas) {
                if (ip.characterRuns() == null) continue;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : ip.characterRuns()) {
                    if (run.inlineGraphics() == null) continue;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                        if (!"Anchored".equals(ig.anchoredPosition())) continue;
                        if (!"None".equals(ig.textWrapMode())) continue;
                        String selfId = ig.selfId();
                        if (selfId == null || selfId.length() < 2) continue;
                        try {
                            int domId = Integer.parseInt(selfId.substring(1), 16);
                            if (ctx.deferredAnchoredFloatingIds.add(domId)) found++;
                        } catch (NumberFormatException e) { /* skip */ }
                    }
                }
            }
        }
        if (found > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 3 사전 스캔: "
                    + found + "개 anchored+none Group 등록 (stories=" + storiesScanned + ")");
        }
    }

    /**
     * StoryLoader 가 등록한 deferredAnchoredFloatingIds 를 처리하여 각 페이지 섹션에
     * BEHIND_TEXT 강조 직사각형(ASTFigure)을 추가한다. 인라인 PNG(inline_NNN.png)를
     * 그대로 사용해 정확한 시각을 보존.
     */
    private static void placeDeferredAnchoredFloating(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.deferredAnchoredFloatingIds == null || ctx.deferredAnchoredFloatingIds.isEmpty()) return;
        if (ctx.basePath == null) return;
        int placed = 0;
        for (Integer domId : ctx.deferredAnchoredFloatingIds) {
            // pageItem 조회 (페이지 인덱스 + bounds)
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem pi =
                    ctx.resolvedData.getPageItem(String.valueOf(domId));
            if (pi == null) continue;
            int pageIdx = pi.pageIndex();
            int secIdx = ctx.toSectionIndex.applyAsInt(pageIdx);
            if (secIdx < 0 || secIdx >= sections.size()) continue;
            double[] gb = pi.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            // 페이지 상대 좌표 (페이지 top-left = 0,0)
            double pageTop = 0, pageLeft = 0;
            if (ctx.resolvedData.pages() != null
                    && pageIdx >= 0 && pageIdx < ctx.resolvedData.pages().size()) {
                double[] pgB = ctx.resolvedData.pages().get(pageIdx).bounds();
                if (pgB != null && pgB.length >= 4) {
                    pageTop = pgB[0]; pageLeft = pgB[1];
                }
            }
            double x = gb[1] - pageLeft;
            double y = gb[0] - pageTop;
            double w = Math.abs(gb[3] - gb[1]);
            double h = Math.abs(gb[2] - gb[0]);
            if (w <= 0 || h <= 0) continue;

            // inline_NNN.png 가 있으면 이미지로, 없으면 일반 fill 도형으로 처리
            java.io.File pngFile = null;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg
                    : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == domId.intValue() && "inline_object".equals(rg.itemType())) {
                    if (rg.file() != null) pngFile = new java.io.File(ctx.basePath, rg.file());
                    break;
                }
            }
            if (pngFile == null || !pngFile.exists()) continue;
            try {
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                fig.kind(kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure.FigureKind.IMAGE);
                fig.imageData(imageData);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                fig.x(kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter.pointsToHwpunits(x));
                fig.y(kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter.pointsToHwpunits(y));
                fig.width(kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter.pointsToHwpunits(w));
                fig.height(kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter.pointsToHwpunits(h));
                // BEHIND_TEXT: 텍스트 뒤에 겹쳐서 표시, 텍스트 흐름에 영향 없음
                fig.textWrapMode("None");
                // zOrder 작게 (텍스트 뒤로) — pageItem zOrder 기반
                int z = pi.zOrder();
                if (z > 0) fig.zOrder(Math.max(2, 10000 - z));
                else fig.zOrder(2);
                sections.get(secIdx).addBlock(fig);
                placed++;
            } catch (Exception e) {
                // skip
            }
        }
        if (placed > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 3 후처리: " + placed
                    + "개 anchored floating 강조 도형 배치");
        }
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
            // SPEC-025: ACE 7 (IndentToHere) 감지 — 첫 inline anchor 의 너비를 paragraph leftMargin 으로
            // 적용해 InDesign 의 "1 ←여기부터 내려쓰기" 효과 재현 (예: 페이지 32 "가 같은 사건을..." 발문)
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame firstAnchorTf = null;
            if (rp.runs() != null) {
                for (ResolvedRun run : rp.runs()) {
                    if (run.isInlineAnchor()) {
                        Integer aid = run.anchoredObjectId();
                        if (aid != null) firstAnchorTf = ctx.resolvedData.getTextFrame(String.valueOf(aid));
                        break;
                    }
                    String t = run.text();
                    if (t != null && !t.isEmpty()) break; // 텍스트 먼저 나오면 anchor 패턴 아님
                }
            }
            if (rp.runs() != null) {
                for (ResolvedRun run : rp.runs()) {
                    if (stopAfterThisRun) break;
                    // inline_anchor: 인라인 그래픽 → ASTInlineObject로 변환
                    if (run.isInlineAnchor()) {
                        Integer anchoredId = run.anchoredObjectId();
                        if (anchoredId != null) {
                            // AnchoredPosition="Anchored" + TextWrapMode="None" Group:
                            // 인라인 배치 건너뛰고 후처리가 BEHIND_TEXT floating 으로 배치.
                            if (ctx.deferredAnchoredFloatingIds.contains(anchoredId)) {
                                continue;
                            }
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
                            // 다수 박스(예: ㅍ ㅎ ㅂ ㅅ 자모 배지) → 각 TF 를 박스 스타일 INLINE_TEXT_FRAME 으로 분해
                            java.util.List<ASTInlineObject> boxList =
                                    InlineFrameHandler.tryInlineGroupAsBoxList(ctx, anchoredId);
                            if (boxList != null && !boxList.isEmpty()) {
                                for (ASTInlineObject box : boxList) para.addItem(box);
                                continue;
                            }
                            // 배경 도형 + 단일 짧은 텍스트프레임 (예: "가" / "나" 캡슐 배지)
                            // → INLINE_TEXT_FRAME (한 몸 + 검색 가능)
                            ASTInlineObject singleBadge = InlineFrameHandler.tryInlineGroupAsSingleBadge(ctx, anchoredId);
                            if (singleBadge != null) {
                                para.addItem(singleBadge);
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
                        // SPEC-025: ACE 7 (IndentToHere, \u0007 or \u0008) 감지 — 첫 inline anchor 이후
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
                        runText = runText.replace("\u0007", "");
                        runText = runText.replace("\t\u0008", "");
                        runText = runText.replace("\u0008", "");
                        runText = runText.replace("\n", "");
                        runText = runText.replace("\r", "");
                        // Yoon 폰트 PUA 글리프 → 안전한 유니코드 치환 (□ 빈 정답 칸)
                        runText = runText.replace('\uE285', '\u25A1').replace('\uE287', '\u25A1').replace('\uE288', '\u25A1');
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
