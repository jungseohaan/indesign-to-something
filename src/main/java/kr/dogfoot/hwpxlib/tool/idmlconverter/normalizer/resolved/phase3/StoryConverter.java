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
    private static final String SPEC016_DEBUG_TEXT = System.getProperty("spec016.debug.text");

    private static final String BULLET_CHARS = "●•◆◇▶▷■□";

    private StoryConverter() {}

    /** ParagraphStyle에서 미리 구한 스타일 속성 (런에서 없을 때 폴백용) */
    private static class StyleContext {
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
                    String hexPart = sourceId.startsWith("u") ? sourceId.substring(1) : sourceId;
                    if (hexPart.contains("_")) hexPart = hexPart.substring(0, hexPart.indexOf('_'));
                    String domId;
                    try {
                        domId = String.valueOf(Integer.parseInt(hexPart, 16));
                    } catch (NumberFormatException e) {
                        continue;
                    }
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
            List<ASTParagraph> paragraphs = convertStoryFromIDML(ctx, storyId);
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
                if (allBlocksEmpty) continue;
            }

            // 단락 분배: paragraphStart/End에 따라 각 TextFrameBlock에 할당
            distributeParagraphs(ctx, paragraphs, blocks, storyId);
        }
        System.err.println("[ResolvedToASTBuilder] Phase 3: " + totalParas + " paragraphs converted (IDML=" + idmlCount + " resolved=" + resolvedCount + ")");
    }

    /**
     * IDML Story XML에서 단락을 파싱하여 ASTParagraph 리스트로 변환.
     * IDML의 단락 구조는 정확 (중복 없음, <Br/> 기반 분리).
     * 단락 속성(leading, indent)은 resolved에서 보강.
     */
    private static List<ASTParagraph> convertStoryFromIDML(ResolvedBuildContext ctx, String storyId) {
        if (ctx.idmlDir == null) return null;

        // storyId(DOM decimal) → IDML hex → Story_u{hex}.xml
        String hexId;
        try {
            hexId = "u" + Integer.toHexString(Integer.parseInt(storyId));
        } catch (NumberFormatException e) {
            return null;
        }

        // 캐시 확인
        IDMLStory idmlStory = ctx.idmlStoryCache.get(hexId);
        if (idmlStory == null) {
            File storyFile = new File(new File(ctx.idmlDir, "Stories"), "Story_" + hexId + ".xml");
            if (!storyFile.exists()) return null;
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                org.w3c.dom.Document xmlDoc = db.parse(storyFile);
                idmlStory = IDMLStoryParser.parseStory(xmlDoc, hexId);
                ctx.idmlStoryCache.put(hexId, idmlStory);
            } catch (Exception e) {
                return null;
            }
        }

        if (idmlStory == null || idmlStory.paragraphs() == null) return null;

        // resolved에서 단락 속성 보강용
        ResolvedStory resolvedStory = ctx.resolvedData.getStory(storyId);

        List<ASTParagraph> paragraphs = new ArrayList<>();
        List<IDMLParagraph> idmlParas = idmlStory.paragraphs();
        for (int i = 0; i < idmlParas.size(); i++) {
            IDMLParagraph ip = idmlParas.get(i);
            ASTParagraph para = new ASTParagraph();
            boolean hasIdmlInlineAnchors = false; // FFFC 앵커 순서로 인라인 삽입된 경우 true

            // 칼럼 브레이크 (ACE 8)
            if (ip.columnBreakAfter()) {
                para.columnBreakAfter(true);
            }

            // 단락 스타일 (IDML)
            if (ip.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(ip.appliedParagraphStyle());
            }

            // 단락 속성: resolved에서 가져옴 (정확한 pt 값)
            // 정렬: IDML 스타일 → resolved 단락 → resolved top-level paragraphStyles → JUSTIFY
            {
                String idmlStyleName = ip.appliedParagraphStyle();
                String cleanStyleName = (idmlStyleName != null && idmlStyleName.contains("/"))
                        ? idmlStyleName.substring(idmlStyleName.lastIndexOf('/') + 1) : idmlStyleName;
                String idmlStyleJust = resolveStyleAlignment(cleanStyleName, ctx.astDocument);
                if (idmlStyleJust != null) {
                    para.alignment(idmlStyleJust);
                } else if (resolvedStory != null && i < resolvedStory.paragraphs().size()
                        && resolvedStory.paragraphs().get(i).justification() != null) {
                    para.alignment(resolvedStory.paragraphs().get(i).justification());
                } else if (cleanStyleName != null && ctx.resolvedData != null
                        && ctx.resolvedData.getParagraphStyleJustification(cleanStyleName) != null) {
                    // resolved.json top-level paragraphStyles fallback
                    para.alignment(ctx.resolvedData.getParagraphStyleJustification(cleanStyleName));
                }
                // alignment가 null이면 HwpxParagraphBuilder에서 baseStyle 또는 기본 JUSTIFY 적용
            }
            if (resolvedStory != null && i < resolvedStory.paragraphs().size()) {
                ResolvedParagraph rp = resolvedStory.paragraphs().get(i);
                // leading: resolved 우선 (실제 렌더링 값), IDML 스타일 fallback
                // 단, auto leading(>50pt = percentage 값)은 무시
                Double fixedLeading = rp.fixedLeading(); // resolved (실제 렌더링 값)
                if (fixedLeading == null || fixedLeading <= 0) {
                    fixedLeading = getStyleLeading(ctx, ip.appliedParagraphStyle()); // IDML 스타일
                    if (fixedLeading != null && fixedLeading > 50) fixedLeading = null;
                }
                if (fixedLeading == null || fixedLeading <= 0) {
                    fixedLeading = ip.leading(); // IDML CharacterRun leading
                    if (fixedLeading != null && fixedLeading > 50) fixedLeading = null;
                }
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
                // 탭 스톱
                if (rp.hasTabStops()) {
                    // normalizeToPoints() 후 tabStop position과 leftIndent는 이미 pt 단위
                    // (applyScale에서 scaleFactor가 적용됨). 다시 scaleFactor를 곱하면 이중 적용.
                    double leftPt = (rp.leftIndent() != null ? rp.leftIndent() : 0);
                    for (ResolvedTabStop rts : rp.tabStops()) {
                        if (rts.position() != null && rts.position() > 0) {
                            double posPt = rts.position() - leftPt;
                            if (posPt < 0) posPt = 0;
                            String align = "left";
                            if (rts.alignment() != null) {
                                String a = rts.alignment().toLowerCase();
                                if (a.contains("center")) align = "center";
                                else if (a.contains("right")) align = "right";
                                else if (a.contains("decimal")) align = "decimal";
                            }
                            para.addTabStop(new ASTTabStop(
                                    CoordinateConverter.pointsToHwpunits(posPt), align, null));
                        }
                    }
                }
            } else {
                // resolvedStory 매칭 실패 → IDML 단락 스타일에서 정렬 상속
                String idmlStyle = ip.appliedParagraphStyle();
                if (idmlStyle != null) {
                    // "ParagraphStyle/스타일명" → "스타일명"
                    String styleName = idmlStyle.contains("/")
                            ? idmlStyle.substring(idmlStyle.lastIndexOf('/') + 1) : idmlStyle;
                    String styleJust = resolveStyleAlignment(styleName, ctx.astDocument);
                    if (styleJust != null) para.alignment(styleJust);
                }
            }

            // resolved 런 (스타일 상속 보강용)
            List<ResolvedRun> resolvedRuns = null;
            if (resolvedStory != null && i < resolvedStory.paragraphs().size()) {
                resolvedRuns = resolvedStory.paragraphs().get(i).runs();
            }

            // ParagraphStyle에서 FillColor/Tracking/FontFamily 미리 구해둠 (런에서 없을 때 사용)
            StyleContext sc = new StyleContext(
                    getStyleFillColor(ctx, ip.appliedParagraphStyle()),
                    getStyleTracking(ctx, ip.appliedParagraphStyle()),
                    getStyleFontFamily(ctx, ip.appliedParagraphStyle()),
                    getStyleFontSize(ctx, ip.appliedParagraphStyle()));
            // tabStop이 있으면 \t 문자를 보존 (HwpxParagraphBuilder가 <hp:tab>으로 변환)
            sc.hasTabStops = para.hasTabStops();

            // 런 변환: IDML CharacterRun → ASTTextRun + 수식 그룹화
            // resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 (불릿/특수문자 런 회피)
            ResolvedRun defaultRR = findDefaultResolvedRun(ctx, resolvedRuns);
            int resolvedRunIdx = 0;

            // 전처리: 한국어+수식마커 혼합 런 분리 + 원문자 변환
            List<IDMLCharacterRun> runs = ASTMathGrouper.splitMathKoreanMixedRuns(ip.characterRuns());
            ASTRunConverter.convertCircledNumberRuns(runs);

            // 수식 그룹화 상태
            List<IDMLCharacterRun> mathGroup = new ArrayList<>();
            List<IDMLCharacterRun> npMathGroup = new ArrayList<>();
            List<IDMLCharacterRun> ehMathGroup = new ArrayList<>();

            boolean paraHasBTRuns = false;
            boolean paraHasNPStructuralRuns = false;
            boolean paraHasMathSymbols = false;
            // IDML 원본 CharacterRun에서 수식 기호 유무 확인 (GREP 분리 전 기준)
            for (IDMLCharacterRun r : ip.characterRuns()) {
                if (r.isBTFont() || r.isNPFont() || r.isEHFont()) { paraHasMathSymbols = true; break; }
                String rt = r.content();
                if (rt != null) {
                    for (int ci = 0; ci < rt.length(); ci++) {
                        char cc = rt.charAt(ci);
                        if ("+=<>≤≥±×÷√²³^_π∑∫∞".indexOf(cc) >= 0
                                || (cc >= 0xC0 && cc <= 0xFF)) { // EH encoded chars (확장 라틴 전체)
                            paraHasMathSymbols = true;
                            break;
                        }
                    }
                }
                if (paraHasMathSymbols) break;
            }
            for (IDMLCharacterRun r : runs) {
                if (r.isBTFont()) paraHasBTRuns = true;
                if (r.isNPFont()) {
                    NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(r.npFontName());
                    if (cat == NPFontGlyphMap.FontCategory.SUBSCRIPT_INDEX
                            || cat == NPFontGlyphMap.FontCategory.SUPERSCRIPT_INDEX
                            || cat == NPFontGlyphMap.FontCategory.ROOT
                            || cat == NPFontGlyphMap.FontCategory.FRACTION_BAR
                            || cat == NPFontGlyphMap.FontCategory.INTEGRAL
                            || cat == NPFontGlyphMap.FontCategory.SUMMATION
                            || cat == NPFontGlyphMap.FontCategory.LIMIT
                            || cat == NPFontGlyphMap.FontCategory.SPECIAL_SYMBOL) {
                        paraHasNPStructuralRuns = true;
                    }
                }
            }

            for (int idx = 0; idx < runs.size(); idx++) {
                IDMLCharacterRun run = runs.get(idx);

                // GREP 수식 리셋: 단일 라틴 문자(수식 변수 x, a, n)는 유지, 나머지 제거
                if (run.grepMathFont()) {
                    String ct = run.content();
                    boolean isSingleLatinVar = ct != null && ct.trim().length() == 1
                            && Character.isLetter(ct.trim().charAt(0));
                    if (!isSingleLatinVar) {
                        run.grepMathFont(false);
                        String ff = run.fontFamily();
                        if (ff != null && ff.contains("BT수식")) {
                            run.fontFamily(null);
                        }
                        run.fontStyle(null);
                    }
                }
                // EH 수식 폰트 리셋
                if (run.isEHFont()) {
                    String ct = run.content();
                    boolean isSingleLatinVar = ct != null && ct.trim().length() == 1
                            && Character.isLetter(ct.trim().charAt(0));
                    // 이 런 또는 근처 런(±5)에 3자+ 영단어가 있으면 이름/약어 그룹 → 리셋
                    boolean nearLongWord = containsLongLatinWord(ct, 3);
                    if (!nearLongWord) {
                        for (int d = 1; d <= 5 && !nearLongWord; d++) {
                            if (idx - d >= 0) nearLongWord = containsLongLatinWord(runs.get(idx - d).content(), 3);
                            if (idx + d < runs.size()) nearLongWord = nearLongWord || containsLongLatinWord(runs.get(idx + d).content(), 3);
                        }
                    }
                    if ((!paraHasMathSymbols && !isSingleLatinVar) || nearLongWord) {
                        run.fontFamily(null);
                        run.fontStyle(null);
                        run.appliedCharacterStyle(null);
                    }
                }

                // EH 수식: fontFamily가 null이면 CharacterStyle 이름에서 추출
                if (run.isEHFont() && run.fontFamily() == null) {
                    String ehFont = EHFontGlyphMap.extractFontFromStyle(run.appliedCharacterStyle());
                    if (ehFont != null) run.fontFamily(ehFont);
                }

                // EH 수식 그룹 진입
                boolean enterEH = run.isEHFont()
                        || EHFontGlyphMap.containsEHEncodedChars(run.content())
                        || EHFontGlyphMap.containsEHFractionPattern(run.content())
                        || (!ehMathGroup.isEmpty() && ASTMathGrouper.isEHMathBridgeRun(run, runs, idx))
                        || (!ehMathGroup.isEmpty() && isEHSqrtContent(run, ehMathGroup));

                // NP 수식 그룹 진입
                boolean enterNP = false;
                if (!enterEH) {
                    enterNP = run.isNPFont()
                            || (!npMathGroup.isEmpty() && ASTMathGrouper.isNPMathBridgeRun(run, runs, idx))
                            || (npMathGroup.isEmpty() && ASTMathGrouper.isPreNPMathRun(run, runs, idx))
                            || (paraHasNPStructuralRuns && !run.isNPFont() && !run.isBTFont()
                                && !run.isEHFont()
                                && ASTMathGrouper.isStandaloneMathRun(run));
                }

                // BT 수식 그룹 진입
                boolean enterBT = false;
                if (!enterEH && !enterNP) {
                    enterBT = (run.isBTFont()
                                && !ASTMathGrouper.isBTRunWithOnlyKorean(run.content()))
                            || (!mathGroup.isEmpty() && ASTMathGrouper.isMathBridgeRun(run, runs, idx))
                            || (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content()));
                }

                if (enterEH) {
                    flushMathGroups(ctx, mathGroup, npMathGroup, null, para);
                    ehMathGroup.add(run);
                } else if (enterNP) {
                    flushMathGroups(ctx, mathGroup, null, ehMathGroup, para);
                    npMathGroup.add(run);
                } else if (enterBT) {
                    flushMathGroups(ctx, null, npMathGroup, ehMathGroup, para);
                    mathGroup.add(run);
                } else {
                    // 비수식 런: 열린 그룹 모두 flush
                    flushMathGroups(ctx, mathGroup, npMathGroup, ehMathGroup, para);

                    // 일반 런 변환 (U+FFFC 인라인 객체 포함)
                    String text = run.content();
                    if (text == null || text.isEmpty()) continue;

                    if (text.contains("\uFFFC")) {
                        hasIdmlInlineAnchors = true;
                        String[] parts = text.split("\uFFFC", -1);
                        // inlineAnchors 순서로 인라인 ID 목록 생성 (FFFC 출현 순서 보장)
                        List<String> inlineIds = new ArrayList<>();
                        if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) {
                            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                                if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                                        && run.inlineFrames() != null && anchor.index() < run.inlineFrames().size()) {
                                    inlineIds.add(run.inlineFrames().get(anchor.index()).selfId());
                                } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                                        && run.inlineGraphics() != null && anchor.index() < run.inlineGraphics().size()) {
                                    inlineIds.add(run.inlineGraphics().get(anchor.index()).selfId());
                                }
                            }
                        } else {
                            // fallback: inlineFrames + inlineGraphics 순서
                            if (run.inlineFrames() != null) {
                                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame itf : run.inlineFrames()) {
                                    inlineIds.add(itf.selfId());
                                }
                            }
                            if (run.inlineGraphics() != null) {
                                for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                                    inlineIds.add(ig.selfId());
                                }
                            }
                        }
                        int anchorIdx = 0;
                        for (int pi = 0; pi < parts.length; pi++) {
                            // 인라인 앵커 직전 텍스트의 후행 공백 제거 (위치 조정용 공백)
                            String partText = parts[pi];
                            if (pi < parts.length - 1 && partText.endsWith("  ")) {
                                partText = partText.replaceAll("\\s+$", " "); // 후행 다중 공백 → 단일 공백
                            }
                            if (!partText.isEmpty()) {
                                // resolved 런 스타일 차이가 있으면 분할 시도
                                boolean partSplit = false;
                                if (resolvedRuns != null && resolvedRuns.size() > 1 && hasStyleVariation(ctx, resolvedRuns)) {
                                    partSplit = splitIdmlRunByResolvedRuns(ctx, run, partText, resolvedRuns, resolvedRunIdx,
                                            para, sc);
                                }
                                if (!partSplit) {
                                    ResolvedRun matchedRR = findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, partText);
                                    if (matchedRR != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                                    ASTTextRun tr = createRunFromIDML(ctx, run, partText, matchedRR != null ? matchedRR : defaultRR, sc);
                                    if (!splitBulletRun(ctx, tr, para)) {
                                        splitLatinVarsInMixedText(ctx, tr, para);
                                    }
                                }
                            }
                            if (pi < parts.length - 1 && anchorIdx < inlineIds.size()) {
                                String inlineHexId = inlineIds.get(anchorIdx);
                                try {
                                    int domId = Integer.parseInt(inlineHexId.substring(1), 16);
                                    // 커스텀 위치 앵커 객체 건너뛰기: resolved TextFrame의 중심X가 부모 범위 밖이면 인라인 삽입 안 함
                                    if (isAnchoredOutsideParentByTextFrame(ctx, domId, storyId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    // 분수 구조 인라인 TextFrame(2단락) → 수식으로 변환
                                    ASTEquation fracEq = tryInlineFractionAsEquation(ctx, domId);
                                    if (fracEq != null) {
                                        para.addItem(fracEq);
                                    } else {
                                        // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환
                                        ASTTextRun textRun = tryInlineTextFrameAsRun(ctx, domId);
                                        if (textRun != null) {
                                            para.addItem(textRun);
                                        } else {
                                            ASTInlineObject inlineObj = loadInlineObject(ctx, domId);
                                            if (inlineObj != null) {
                                                para.addItem(inlineObj);
                                            }
                                        }
                                    }
                                } catch (Exception e) { /* skip */ }
                                anchorIdx++;
                            }
                        }
                    } else {
                        // GREP 스타일 분할: IDML 단일 런이 resolved에서 여러 런(다른 색상/폰트)으로 분할된 경우
                        // resolved 런 경계에서 IDML 런을 분할하여 각각의 색상을 적용
                        boolean splitByResolved = false;
                        if (resolvedRuns != null && resolvedRuns.size() > 1 && hasStyleVariation(ctx, resolvedRuns)) {
                            splitByResolved = splitIdmlRunByResolvedRuns(ctx, run, text, resolvedRuns, resolvedRunIdx,
                                    para, sc);
                        }
                        if (!splitByResolved) {
                            ResolvedRun matchedRR2 = findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, text);
                            if (matchedRR2 != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                            ASTTextRun tr = createRunFromIDML(ctx, run, text, matchedRR2 != null ? matchedRR2 : defaultRR, sc);
                            // ;...; 분수 GREP 패턴이 포함된 텍스트 → 분수 수식으로 분리
                            if (!splitBulletRun(ctx, tr, para)) {
                                if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                                    splitFractionPatternInText(ctx, text, tr, para);
                                } else {
                                    splitLatinVarsInMixedText(ctx, tr, para);
                                }
                            }
                        }
                    }
                }
            }
            // 단락 끝 잔여 수식 그룹 flush
            flushMathGroups(ctx, mathGroup, npMathGroup, ehMathGroup, para);

            // 패턴 감지: 행잉 인덴트 + 인라인 아이콘 + 탭
            // InDesign에서 인라인 아이콘이 마진 밖(-indent 영역)에 배치되지만
            // HWPX에서는 마진 안에 배치되므로, 행잉 인덴트를 리셋하여 자연 들여쓰기 사용
            if (para.firstLineIndent() != null && para.firstLineIndent() < 0
                    && !para.items().isEmpty()
                    && para.items().get(0) instanceof ASTInlineObject) {
                para.leftMargin(null);
                para.firstLineIndent(null);
            }

            // overline 마커(\uE000...\uE001)가 포함된 TextRun을 ASTEquation으로 분리
            splitOverlineRuns(para);

            // 이탤릭 EH상부자 런 → 인라인 수식(ASTEquation)으로 변환
            // 한컴 수식 에디터가 변수=이탤릭, 괄호/연산자=정체를 자동 처리
            convertItalicRunsToEquations(para);

            // 불릿 단락이면 불릿 이후 런 색상을 검정으로 리셋
            resetBulletParagraphColors(ctx, para);

            // 인라인 객체 boundsX 기반 재정렬: FFFC 앵커 순서가 없는 경우에만
            // (IDML 경로에서 FFFC 분할 후 앵커 순서로 삽입된 경우 재정렬하면 순서 깨짐)
            if (!hasIdmlInlineAnchors) {
                ASTTableConverter.reorderInlineObjectsByBoundsX(para);
            }

            paragraphs.add(para);
        }

        return paragraphs;
    }

    /** 기본 매칭 신뢰도(LOW)로 createRunFromIDML 호출 — 호환용 래퍼. */
    private static ASTTextRun createRunFromIDML(ResolvedBuildContext ctx, IDMLCharacterRun cr, String text, ResolvedRun rr, StyleContext sc) {
        return createRunFromIDML(ctx, cr, text, rr, sc, MatchConfidence.LOW);
    }

    private static ASTTextRun createRunFromIDML(ResolvedBuildContext ctx, IDMLCharacterRun cr, String text, ResolvedRun rr, StyleContext sc, MatchConfidence confidence) {
        // SPEC-016 Phase 2: 신뢰도 카운터 (모든 IDML→AST 런 생성 경로 단일 집계 지점)
        switch (confidence) {
            case HIGH: ctx.spec016Counts[0]++; break;
            case MEDIUM: ctx.spec016Counts[1]++; break;
            case LOW: ctx.spec016Counts[2]++; break;
        }
        ASTTextRun tr = new ASTTextRun();
        // 특수 제어 문자 제거
        // \u0008 = Indent to Here (ACE 7) — HWPX에 대응 없음
        // \n = Frame Break (ACE 3) — 같은 글상자 안에서 의미 없음
        // \t + \u0008 패턴: 인라인 아이콘 앞 탭+IndentToHere → 둘 다 제거
        // 단독 \t: tabStop이 있으면 유지 (HwpxParagraphBuilder가 <hp:tab>으로 변환), 없으면 공백 치환
        if (text != null) {
            text = text.replace("\t\u0008", ""); // \t + IndentToHere 조합 제거
            text = text.replace("\u0008", "");   // 단독 IndentToHere 제거
            text = text.replace("\n", "");       // Frame Break 제거
            if (!sc.hasTabStops) {
                text = text.replace("\t", " ");  // 탭 → 공백 (탭스톱 없는 경우 간격 방지)
            }
            text = text.replace("\u2009", " ");   // Thin Space → 공백
            text = text.replace("\u2002", " ");  // En Space → 공백 (단어 구분자 보존)
            text = text.replace("\u2003", " ");  // Em Space → 공백 (단어 구분자 보존)
            text = text.replace("\u200A", "");   // Hair Space 제거 (타이포 조정용, 시각상 무의미)
            text = text.replace("\uFFE3", "~");  // Fullwidth Macron → 물결 (한글 호환)
            // EH상부자 overline marker: Ó(0xD3) → \uE000{letters}\uE001 마커로 치환
            // 단락 후처리(splitOverlineRuns)에서 ASTEquation overline{AB}로 변환
            if (text.indexOf('\u00D3') >= 0) {
                text = markOverlineSegments(text);
            }
        }

        // ── 속성 적용: SPEC-012 RunPropertyResolver 사용 ──
        // 우선순위: resolved → IDML CharacterRun → ParagraphStyle → default
        // resolved는 GREP/중첩 스타일이 모두 적용된 실제 렌더링 값이므로 가장 권위 있음.

        // GREP 적용 캐릭터 스타일이 있으면 색상/글리프 매핑에 활용
        String effectiveIdmlColor = cr.fillColor();
        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef grepCharStyle = null;
        if (cr.grepAppliedCharStyle() != null && ctx.idmlDocumentSupplier.get() != null) {
            ctx.ensureIdmlInfra.run();
            if (ctx.idmlDocumentSupplier.get() != null) {
                grepCharStyle = ctx.idmlDocumentSupplier.get().charStyles().get(cr.grepAppliedCharStyle());
                if (grepCharStyle == null) {
                    String shortRef = cr.grepAppliedCharStyle();
                    if (shortRef.startsWith("CharacterStyle/")) shortRef = shortRef.substring("CharacterStyle/".length());
                    grepCharStyle = ctx.idmlDocumentSupplier.get().charStyles().get(shortRef);
                }
                if (grepCharStyle != null && grepCharStyle.fillColor() != null) {
                    effectiveIdmlColor = grepCharStyle.fillColor();
                }
            }
        }

        // EH상부자/하부자 GREP 적용 시 ASCII 글리프 매핑 (예: '_' → '×')
        // GREP으로 분리된 단일/짧은 ASCII 서브런만 영향을 받는다.
        if (text != null && grepCharStyle != null && grepCharStyle.fontFamily() != null
                && EHFontGlyphMap.isEHFontFamily(grepCharStyle.fontFamily())) {
            text = EHFontGlyphMap.applyEHGrepAsciiGlyphMap(text);
        }
        tr.text(text);

        // fontFamily / fontSize / textColor: 헬퍼로 단일 우선순위 적용
        // SPEC-016: 매칭 신뢰도(confidence)에 따라 resolved 오버라이드 여부 결정
        String resolvedFontFamily = RunPropertyResolver.resolveFontFamilyWithConfidence(
                rr, cr, sc.fontFamily, text, confidence);
        if (resolvedFontFamily != null) {
            // EH/BT/NP 수식 전용 폰트가 일반 텍스트(수식 그룹 밖)에 적용된 경우
            // → 이탤릭이면 Times New Roman (수식 변수 스타일), 아니면 ParagraphStyle 기본 폰트
            if (EHFontGlyphMap.isEHFontFamily(resolvedFontFamily)
                    || resolvedFontFamily.contains("BT수식")
                    || resolvedFontFamily.startsWith("NP_")) {
                String rrStyle = (rr != null && rr.fontStyle() != null) ? rr.fontStyle().toLowerCase() : "";
                if (rrStyle.contains("italic")) {
                    // 수식 변수 이탤릭: ParagraphStyle 기본 폰트를 유지하되
                    // fontStyle=Italic으로 이탤릭 적용 (charPr에서 처리)
                    resolvedFontFamily = sc.fontFamily;
                } else {
                    resolvedFontFamily = sc.fontFamily;
                }
            }
            tr.fontFamily(resolvedFontFamily);
        }
        Integer resolvedFontSize = RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                rr, cr, sc.fontSize, confidence);
        if (resolvedFontSize != null) {
            tr.fontSizeHwpunits(resolvedFontSize);
        }
        String resolvedColor = RunPropertyResolver.resolveTextColorHexWithConfidence(
                rr, effectiveIdmlColor, sc.fillColor, (_c) -> resolveColorToHex(ctx, _c), confidence);
        if (resolvedColor != null) {
            tr.textColor(resolvedColor);
        }

        // fontStyle: IDML CR이 EH/BT 수식 폰트를 한국어에 잘못 적용한 경우 리셋
        if (cr.fontStyle() != null) {
            String crFf = cr.fontFamily();
            boolean crIsEHOrBT = crFf != null && (EHFontGlyphMap.isEHFontFamily(crFf) || crFf.contains("BT수식"));
            if (crIsEHOrBT && text != null && EHTextClassifier.isKoreanOnly(text)) {
                // 한국어 텍스트에 EH/BT 폰트의 fontStyle 적용 안 함
            } else {
                tr.fontStyle(cr.fontStyle());
            }
        }
        // InDesign Tracking → HWPX 자간
        // 한컴돋움/한컴바탕 fallback 폰트 매핑 시: tracking 값 그대로 (e.g., -15 → -15%)
        // 명시적 매핑 폰트: tracking / 10 (e.g., -30 → -3%)
        // 한국어 폰트(한글 포함 이름)는 대부분 한컴돋움 fallback → 그대로 사용
        {
            Double trackingVal = (cr.tracking() != null && cr.tracking() != 0)
                    ? cr.tracking() : sc.tracking;
            if (trackingVal != null && trackingVal != 0) {
                String fn = (tr.fontFamily() != null) ? tr.fontFamily()
                        : (sc.fontFamily != null ? sc.fontFamily
                        : (rr != null ? rr.fontFamily() : null));
                boolean isDefaultFallback = isKoreanFontName(fn);
                if (isDefaultFallback) {
                    // 한컴돋움 fallback: tracking 값의 50%
                    tr.letterSpacing((short) Math.round(trackingVal * 0.5));
                } else {
                    // 명시적 매핑: tracking / 10
                    tr.letterSpacing((short) Math.round(trackingVal / 10.0));
                }
            }
        }
        // baselineShift: InDesign에서 작은 글자 + 양수 baselineShift = 위첨자,
        // 작은 글자 + 음수 baselineShift = 아래첨자 패턴을 감지하여 sup/subscript로 변환
        // resolved 우선, IDML CR fallback
        Double bsVal = (rr != null && rr.baselineShift() != null && rr.baselineShift() != 0)
                ? rr.baselineShift()
                : (cr.baselineShift() != null && cr.baselineShift() != 0 ? cr.baselineShift() : null);
        if (bsVal != null) {
            double bsPt = bsVal;
            // 인접 런의 fontSize 비교: 현재 런이 주변보다 작으면 첨자로 판별
            double curFs = (rr != null && rr.fontSize() != null && rr.fontSize() > 0) ? rr.fontSize()
                    : (cr.fontSize() != null && cr.fontSize() > 0) ? cr.fontSize() : 10.0;
            double baseFs = (sc.fontSize != null && sc.fontSize > 0) ? sc.fontSize : 10.0;
            boolean isSmallerFont = curFs < baseFs * 0.75; // 75% 이하면 첨자
            if (isSmallerFont && bsPt > 0) {
                tr.superscript(true); // 위첨자
            } else if (isSmallerFont && bsPt < 0) {
                tr.subscript(true); // 아래첨자
            } else {
                // 일반 기선 이동
                short bsPct = (short) Math.round((bsPt / curFs) * 100);
                tr.baselineShift(bsPct);
            }
        }
        // resolved에서만 가져오는 보조 속성: fontStyle / horizontalScale / underline / strikeThrough
        // (fontFamily/fontSize/textColor는 위쪽에서 RunPropertyResolver로 이미 처리됨)
        if (rr != null) {
            if (rr.fontStyle() != null) {
                String rrStyle = rr.fontStyle().toLowerCase();
                boolean isItalic = rrStyle.contains("italic") || rrStyle.contains("oblique");
                boolean isEHorBT = rr.fontFamily() != null
                        && (EHFontGlyphMap.isEHFontFamily(rr.fontFamily()) || rr.fontFamily().contains("BT수식"));
                // EH/BT 수식 폰트라도 Italic이면 적용 (GREP 이탤릭 스타일)
                // 숫자 전용 fontStyle(예: "30")은 무시
                if (!isEHorBT || isItalic) {
                    tr.fontStyle(rr.fontStyle());
                }
            }
            // horizontalScale: IDML에 없으면 resolved에서 보강
            // hs == vs인 비례 확대라도 fontSize를 키우면 baseline이 어긋나 보이므로
            // ratio에만 반영한다. (예: `+` 글자만 115% 확대인 경우 위첨자처럼 보이는 현상 방지)
            if (tr.horizontalScale() == null && rr.horizontalScale() != null
                    && rr.horizontalScale() != 0 && rr.horizontalScale() != 100) {
                tr.horizontalScale((short) rr.horizontalScale().doubleValue());
            }
            // underline / strikeThrough
            if (rr.underline() != null && rr.underline()) {
                tr.underline(true);
            }
            if (rr.strikeThru() != null && rr.strikeThru()) {
                tr.strikeThrough(true);
            }
        }
        // 색상 default 폴백 (헬퍼에서 처리되지 않은 경우)
        if (tr.textColor() == null) {
            tr.textColor("#000000");
        }
        // IDML CharacterRun의 underline/strikeThrough (resolved보다 우선)
        if (cr.underline() != null && cr.underline()) {
            tr.underline(true);
        }
        if (cr.strikeThrough() != null && cr.strikeThrough()) {
            tr.strikeThrough(true);
        }
        // IDML UnderlineType → underlineShape 매핑 (Wavy → WAVE 등)
        if (cr.underlineType() != null) {
            String ulType = cr.underlineType().toLowerCase();
            if (ulType.contains("wavy") || ulType.contains("wave")) {
                tr.underline(true);
                tr.underlineShape("WAVE");
            } else if (ulType.contains("dashed") || ulType.contains("dash")) {
                tr.underline(true);
                tr.underlineShape("DASH");
            } else if (ulType.contains("dotted") || ulType.contains("dot")) {
                tr.underline(true);
                tr.underlineShape("DOT");
            }
        }
        // CharacterStyle 이름에서 밑줄/취소선 추론
        String charStyle = cr.appliedCharacterStyle();
        if (charStyle != null) {
            if (charStyle.contains("밑줄") || charStyle.toLowerCase().contains("underline")) {
                tr.underline(true);
            }
            if (charStyle.contains("취소선") || charStyle.toLowerCase().contains("strikethrough")) {
                tr.strikeThrough(true);
            }
            // CharacterStyle에서 물결 밑줄 추론
            if (charStyle.contains("물결") || charStyle.toLowerCase().contains("wavy")) {
                tr.underlineShape("WAVE");
            }
        }
        // 수식 폰트 감지는 convertMathRunsInParagraph에서 후처리
        return tr;
    }

    /** 한국어 폰트 이름 판별: 한글 문자가 포함되어 있으면 한국어 폰트 */
    private static boolean isKoreanFontName(String fontName) {
        if (fontName == null) return false;
        for (int i = 0; i < fontName.length(); i++) {
            char c = fontName.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) return true;
        }
        return false;
    }

    /**
     * 불릿 문자(●, •)로 시작하는 런을 불릿 런 + 본문 런으로 분리하여 단락에 추가.
     * InDesign에서 불릿과 본문이 같은 런에 포함되면 불릿 색상이 본문에도 적용되는 문제 해결.
     * 분리 시 불릿 런은 원래 색상 유지, 본문 런은 검정(#000000)으로 리셋.
     * @return true: 분리되어 단락에 추가됨, false: 분리 불필요 (호출자가 직접 추가)
     */
    private static boolean splitBulletRun(ResolvedBuildContext ctx, ASTTextRun tr, ASTParagraph para) {
        String text = tr.text();
        if (text == null || text.length() < 2) return false;

        char first = text.charAt(0);
        if (BULLET_CHARS.indexOf(first) < 0) return false;

        // 불릿 뒤에 공백/탭이 있어야 분리 (단독 불릿 문자는 무시)
        int splitIdx = 1;
        if (splitIdx < text.length() && (text.charAt(splitIdx) == ' ' || text.charAt(splitIdx) == '\t')) {
            splitIdx++; // 공백/탭 포함
        }
        if (splitIdx >= text.length()) return false; // 불릿만 있으면 분리 불필요

        // 단락에 불릿 플래그 설정 (이후 런의 색상 리셋용)
        para.bulletParagraph(true);

        // 불릿 런: 원래 색상, 약간 작은 크기
        ASTTextRun bulletRun = new ASTTextRun();
        bulletRun.text(String.valueOf(first));
        bulletRun.textColor(tr.textColor());
        bulletRun.fontFamily(tr.fontFamily());
        bulletRun.fontStyle(tr.fontStyle());
        if (tr.fontSizeHwpunits() != null) {
            // 불릿 크기: 본문의 50% (함초롬돋움의 ● 글리프가 크므로)
            bulletRun.fontSizeHwpunits((int) (tr.fontSizeHwpunits() * 0.5));
        }
        bulletRun.letterSpacing(tr.letterSpacing());
        para.addItem(bulletRun);

        // 구분자(탭/공백) 런
        if (splitIdx > 1) {
            ASTTextRun sepRun = new ASTTextRun();
            sepRun.text(text.substring(1, splitIdx));
            sepRun.fontFamily(tr.fontFamily());
            sepRun.fontStyle(tr.fontStyle());
            sepRun.fontSizeHwpunits(tr.fontSizeHwpunits());
            sepRun.textColor("#000000");
            para.addItem(sepRun);
        }

        // 본문 런: 검정색
        ASTTextRun bodyRun = new ASTTextRun();
        bodyRun.text(text.substring(splitIdx));
        bodyRun.fontFamily(tr.fontFamily());
        bodyRun.fontStyle(tr.fontStyle());
        bodyRun.fontSizeHwpunits(tr.fontSizeHwpunits());
        bodyRun.letterSpacing(tr.letterSpacing());
        bodyRun.textColor("#000000");
        bodyRun.baselineShift(tr.baselineShift());
        para.addItem(bodyRun);

        return true;
    }

    /**
     * 불릿 단락의 런 색상을 검정으로 리셋 (불릿 런 자체는 제외).
     * splitBulletRun이 단락 첫 런만 처리하므로, 이후 런의 색상도 리셋 필요.
     */
    private static void resetBulletParagraphColors(ResolvedBuildContext ctx, ASTParagraph para) {
        if (!para.bulletParagraph()) return;
        boolean firstItem = true;
        for (Object item : para.items()) {
            if (item instanceof ASTTextRun) {
                if (firstItem) {
                    firstItem = false;
                    continue; // 불릿 런 자체는 건너뜀
                }
                ASTTextRun run = (ASTTextRun) item;
                // 불릿 색상(비검정)이면 검정으로 리셋
                if (run.textColor() != null && !run.textColor().equals("#000000")) {
                    run.textColor("#000000");
                }
            } else {
                firstItem = false;
            }
        }
    }

    /**
     * ParagraphStyle의 Leading 값을 가져옴 (pt). StylePropertyResolver에 위임.
     */
    private static Double getStyleLeading(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.leading() : null;
    }

    /**
     * ParagraphStyle의 Tracking(자간) 값을 가져옴. StylePropertyResolver에 위임.
     */
    private static Double getStyleTracking(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.tracking() : null;
    }

    /**
     * ParagraphStyle의 FillColor를 hex로 변환하여 반환. StylePropertyResolver에 위임.
     */
    private static String getStyleFillColor(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        if (resolved != null && resolved.fillColor() != null) {
            return resolveColorToHex(ctx, resolved.fillColor());
        }
        return null;
    }

    private static String getStyleFontFamily(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontFamily() : null;
    }

    private static Double getStyleFontSize(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontSize() : null;
    }

    /**
     * 한국어 사이 단일 라틴 문자를 수식 변수(이탤릭)로 분리.
     * "길이를 x라고 할 때" → "길이를 " + [수식 x] + "라고 할 때"
     */
    private static void splitLatinVarsInMixedText(ResolvedBuildContext ctx, ASTTextRun originalRun, ASTParagraph para) {
        String text = originalRun.text();
        if (text == null || text.isEmpty()) { para.addItem(originalRun); return; }

        // 한국어가 포함된 텍스트에서만 분리 (순수 라틴 텍스트는 그대로)
        boolean hasKorean = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) { hasKorean = true; break; }
        }
        if (!hasKorean) { para.addItem(originalRun); return; }

        // 단일 라틴 문자(공백으로 둘러싸인)를 찾아 분리
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isLatinLetter = Character.isLetter(c) && c < 0x100 && !Character.isDigit(c);
            // 단일 라틴 문자: 앞뒤가 공백/한국어/숫자이고, 다음 문자가 라틴이 아님
            boolean isSingleVar = isLatinLetter
                    && (i == 0 || !Character.isLetter(text.charAt(i - 1)) || text.charAt(i - 1) >= 0x100)
                    && (i == text.length() - 1 || !Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) >= 0x100);
            if (isSingleVar) {
                // 앞 텍스트 flush
                if (buf.length() > 0) {
                    ASTTextRun before = cloneRunWithText(ctx, originalRun, buf.toString());
                    para.addItem(before);
                    buf.setLength(0);
                }
                // 수식 변수
                ASTEquation eq = new ASTEquation();
                eq.hwpScript(String.valueOf(c));
                eq.sourceType("LATIN_VAR");
                if (originalRun.textColor() != null) eq.textColor(originalRun.textColor());
                para.addItem(eq);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            ASTTextRun after = cloneRunWithText(ctx, originalRun, buf.toString());
            para.addItem(after);
        }
    }

    private static ASTTextRun cloneRunWithText(ResolvedBuildContext ctx, ASTTextRun src, String text) {
        ASTTextRun tr = new ASTTextRun();
        tr.text(text);
        tr.fontFamily(src.fontFamily());
        tr.fontStyle(src.fontStyle());
        tr.fontSizeHwpunits(src.fontSizeHwpunits());
        tr.textColor(src.textColor());
        tr.letterSpacing(src.letterSpacing());
        tr.grepMathFont(src.grepMathFont());
        tr.underline(src.underline());
        tr.underlineShape(src.underlineShape());
        tr.underlineColor(src.underlineColor());
        tr.strikeThrough(src.strikeThrough());
        tr.characterStyleRef(src.characterStyleRef());
        tr.horizontalScale(src.horizontalScale());
        tr.verticalScale(src.verticalScale());
        tr.baselineShift(src.baselineShift());
        return tr;
    }

    /** 텍스트에 minLen자 이상 연속 라틴 문자(영단어)가 포함되어 있는지 확인. */
    private static boolean containsLongLatinWord(String text, int minLen) {
        if (text == null) return false;
        int streak = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c < 0x100) {
                streak++;
                if (streak >= minLen) return true;
            } else {
                streak = 0;
            }
        }
        return false;
    }

    /**
     * 색상 이름/CMYK 문자열을 hex RGB로 변환.
     * IDML 스와치 이름("Color/홀수_1단원_MD") 또는 CMYK 문자열("C=0 M=0 Y=0 K=70") 지원.
     */
    private static String resolveColorToHex(ResolvedBuildContext ctx, String color) {
        if (color == null) return null;
        // 이미 hex (#RRGGBB 또는 #RGB 형식: # 뒤가 모두 hex 문자)
        if (color.startsWith("#") && color.length() >= 4 && color.substring(1).matches("[0-9a-fA-F]+")) {
            return color;
        }
        // # 접두사가 있지만 hex가 아닌 스와치 이름 (예: "#활동 번호 색") → 이름으로 조회
        if (color.startsWith("#")) {
            String hex = ctx.resolvedData.resolveColorHex(color);
            if (hex != null) return hex;
            // # 제거 후 재조회
            hex = ctx.resolvedData.resolveColorHex(color.substring(1));
            if (hex != null) return hex;
        }
        // IDML 스와치: "Color/Paper" → "Paper"
        String name = color.startsWith("Color/") ? color.substring(6) : color;
        // resolvedData에서 조회
        String hex = ctx.resolvedData.resolveColorHex(name);
        if (hex != null) return hex;
        // IDML color ID (예: "Color/u1fc", "u1fc") → colorResolver로 해석
        ctx.ensureIdmlInfra.run();
        if (ctx.colorResolverSupplier.get() != null) {
            String crHex = ctx.colorResolverSupplier.get().resolve(color);
            if (crHex != null) return crHex;
            crHex = ctx.colorResolverSupplier.get().resolve("Color/" + name);
            if (crHex != null) return crHex;
            crHex = ctx.colorResolverSupplier.get().resolve(name);
            if (crHex != null) return crHex;
        }
        // CMYK 문자열 파싱: "C=0 M=15 Y=80 K=0"
        if (name.contains("C=") && name.contains("M=")) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("C=(\\d+\\.?\\d*)\\s+M=(\\d+\\.?\\d*)\\s+Y=(\\d+\\.?\\d*)\\s+K=(\\d+\\.?\\d*)")
                        .matcher(name);
                if (m.find()) {
                    double c = Double.parseDouble(m.group(1)) / 100.0;
                    double mm = Double.parseDouble(m.group(2)) / 100.0;
                    double y = Double.parseDouble(m.group(3)) / 100.0;
                    double k = Double.parseDouble(m.group(4)) / 100.0;
                    int r = (int) (255 * (1 - c) * (1 - k));
                    int g = (int) (255 * (1 - mm) * (1 - k));
                    int b = (int) (255 * (1 - y) * (1 - k));
                    return String.format("#%02X%02X%02X", r, g, b);
                }
            } catch (Exception e) {}
        }
        return null;
    }

    /**
     * resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 선택.
     * 불릿(●, ▪ 등 1~2자)이나 특수문자 런이 아닌 본문 런을 우선 선택.
     */
    private static ResolvedRun findDefaultResolvedRun(ResolvedBuildContext ctx, List<ResolvedRun> runs) {
        if (runs == null || runs.isEmpty()) return null;
        ResolvedRun longest = null;
        int maxLen = 0;
        for (ResolvedRun r : runs) {
            String text = r.text();
            if (text == null) continue;
            String trimmed = text.replace("\uFFFC", "").trim();
            if (trimmed.length() > maxLen) {
                maxLen = trimmed.length();
                longest = r;
            }
        }
        return longest != null ? longest : runs.get(0);
    }

    /** SPEC-016: 분할 세그먼트 정보 (텍스트 + 매칭된 resolved 런 + 매칭 신뢰도). */
    private static class Segment {
        final String text;
        final int rrIdx;
        final MatchConfidence confidence;

        Segment(String text, int rrIdx, MatchConfidence confidence) {
            this.text = text;
            this.rrIdx = rrIdx;
            this.confidence = confidence;
        }
    }

    private static boolean splitIdmlRunByResolvedRuns(ResolvedBuildContext ctx, IDMLCharacterRun cr, String text,
            List<ResolvedRun> resolvedRuns, int startIdx,
            ASTParagraph para, StyleContext sc) {
        if (text == null || text.isEmpty() || resolvedRuns == null) return false;

        // resolved 런에서 이 텍스트와 겹치는 연속 런들을 찾기
        // 텍스트 시작부터 순차적으로 resolved 런 텍스트를 매칭
        String remaining = text;
        List<Segment> segments = new ArrayList<>();
        int rIdx = startIdx;
        boolean foundSplit = false;

        while (!remaining.isEmpty() && rIdx < resolvedRuns.size()) {
            ResolvedRun rr = resolvedRuns.get(rIdx);
            String rrText = rr.text();
            if (rrText == null || rrText.isEmpty()) { rIdx++; continue; }

            // resolved 런 텍스트가 remaining의 접두사인지 확인
            // 특수 공백(Figure Space \u2007 등)을 일반 공백으로 정규화하여 비교
            String normRemaining = normalizeSpaces(remaining);
            String normRRText = normalizeSpaces(rrText);
            if (normRemaining.startsWith(normRRText)) {
                // 정규화 후 정확 접두사 매칭 → HIGH
                int cutLen = findOriginalLength(remaining, normRRText.length());
                segments.add(new Segment(remaining.substring(0, cutLen), rIdx, MatchConfidence.HIGH));
                remaining = remaining.substring(cutLen);
                rIdx++;
            } else if (remaining.startsWith(rrText)) {
                // 원문 그대로 접두사 매칭 → HIGH
                segments.add(new Segment(rrText, rIdx, MatchConfidence.HIGH));
                remaining = remaining.substring(rrText.length());
                rIdx++;
            } else if (rrText.length() > 0 && remaining.startsWith(rrText.substring(0, Math.min(3, rrText.length())))) {
                // 부분 매칭: 앞 3자만 일치 → 다음 런 키워드로 분할
                // 성공하면 MEDIUM, 분할 실패 시 LOW
                if (rIdx + 1 < resolvedRuns.size()) {
                    ResolvedRun nextRR = resolvedRuns.get(rIdx + 1);
                    String nextText = nextRR.text();
                    if (nextText != null && nextText.length() >= 3) {
                        String nextKey = nextText.substring(0, Math.min(5, nextText.length()));
                        int splitPos = remaining.indexOf(nextKey);
                        if (splitPos > 0) {
                            segments.add(new Segment(remaining.substring(0, splitPos), rIdx, MatchConfidence.MEDIUM));
                            remaining = remaining.substring(splitPos);
                            rIdx++;
                            foundSplit = true;
                            continue;
                        }
                    }
                }
                // 분할 실패: 남은 텍스트 전체를 LOW로 처리
                segments.add(new Segment(remaining, rIdx, MatchConfidence.LOW));
                remaining = "";
                rIdx++;
            } else {
                rIdx++; // 매칭 실패 → 다음 런 시도
            }
        }
        if (!remaining.isEmpty()) {
            // 루프 탈출 후 남은 텍스트: 매칭 없음 → LOW
            segments.add(new Segment(remaining, Math.max(0, rIdx - 1), MatchConfidence.LOW));
        }

        // 분할이 없으면(세그먼트 1개) 기존 로직 사용
        if (segments.size() <= 1 && !foundSplit) return false;

        // 각 세그먼트별로 ASTTextRun 생성 (confidence 전달)
        for (Segment seg : segments) {
            ResolvedRun rr = (seg.rrIdx >= 0 && seg.rrIdx < resolvedRuns.size())
                    ? resolvedRuns.get(seg.rrIdx) : null;
            ResolvedRun effectiveRr = rr != null ? rr : findDefaultResolvedRun(ctx, resolvedRuns);
            // findDefaultResolvedRun 폴백은 신뢰도 강등
            MatchConfidence effConf = (rr != null) ? seg.confidence : MatchConfidence.LOW;

            // SPEC-016 Phase 2: LOW 진단 — 마커 텍스트가 포함되면 컨텍스트 덤프
            if (effConf == MatchConfidence.LOW && SPEC016_DEBUG_TEXT != null
                    && seg.text != null && seg.text.contains(SPEC016_DEBUG_TEXT)) {
                System.err.println("[SPEC-016 LOW] segment=\"" + seg.text + "\""
                        + " idmlCRtext=\"" + text + "\""
                        + " rrIdx=" + seg.rrIdx
                        + " resolvedRuns(" + resolvedRuns.size() + ")=");
                for (int i = 0; i < resolvedRuns.size(); i++) {
                    ResolvedRun dbg = resolvedRuns.get(i);
                    System.err.println("  rr[" + i + "] text=\""
                            + (dbg.text() == null ? "<null>" : dbg.text())
                            + "\" font=" + dbg.fontFamily()
                            + " size=" + dbg.fontSize()
                            + " color=" + dbg.fillColor());
                }
            }

            ASTTextRun tr = createRunFromIDML(ctx, cr, seg.text, effectiveRr, sc, effConf);
            if (!splitBulletRun(ctx, tr, para)) {
                splitLatinVarsInMixedText(ctx, tr, para);
            }
        }
        return true;
    }

    /** resolved 런 간 스타일(색상, 폰트, fontStyle) 차이가 있는지 확인 */
    private static boolean hasStyleVariation(ResolvedBuildContext ctx, List<ResolvedRun> runs) {
        if (runs == null || runs.size() <= 1) return false;
        String firstColor = null, firstFont = null, firstStyle = null;
        boolean initialized = false;
        for (ResolvedRun rr : runs) {
            if (rr.text() == null || rr.text().isEmpty()) continue;
            String color = rr.fillColor();
            String font = rr.fontFamily();
            String style = rr.fontStyle();
            if (!initialized) {
                firstColor = color; firstFont = font; firstStyle = style;
                initialized = true;
            } else {
                if (!eq(color, firstColor) || !eq(font, firstFont) || !eq(style, firstStyle)) return true;
            }
        }
        return false;
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /** 특수 공백(Figure Space, En/Em Space 등)을 일반 공백으로 정규화 */
    private static String normalizeSpaces(String s) {
        if (s == null) return "";
        return s.replace('\u2007', ' ').replace('\u2002', ' ').replace('\u2003', ' ')
                .replace('\u2009', ' ').replace('\u200A', ' ').replace('\u00A0', ' ');
    }

    /** 정규화된 길이에 대응하는 원본 문자열의 실제 길이 (1:1 매핑이므로 동일) */
    private static int findOriginalLength(String original, int normalizedLen) {
        return Math.min(normalizedLen, original.length());
    }

    private static ResolvedRun findResolvedRun(ResolvedBuildContext ctx, List<ResolvedRun> runs, int startIdx, String text) {
        if (runs == null || runs.isEmpty() || text == null || text.isEmpty()) return null;
        String key = text.length() > 5 ? text.substring(0, 5) : text;
        // startIdx부터 순차 검색
        for (int i = startIdx; i < runs.size(); i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) {
                ctx.lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        // 못 찾으면 처음부터
        for (int i = 0; i < Math.min(startIdx, runs.size()); i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) {
                ctx.lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        // 매칭 실패 시 null 반환 — 호출측에서 defaultRR 사용
        return null;
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
                            if (isAnchoredOutsideParent(ctx, anchoredId, story.id())) {
                                continue;
                            }
                            // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환 우선
                            ASTTextRun textRun = tryInlineTextFrameAsRun(ctx, anchoredId);
                            if (textRun != null) {
                                para.addItem(textRun);
                                continue;
                            }
                            ASTInlineObject inlineObj = loadInlineObject(ctx, anchoredId);
                            if (inlineObj != null) {
                                para.addItem(inlineObj);
                                continue;
                            }
                            // PNG도 텍스트도 없는 인라인 앵커 → 빈칸 공백으로 대체
                            ASTTextRun spaceRun = createSpaceRunForEmptyAnchor(ctx, anchoredId);
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
                            runText = markOverlineSegments(runText);
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
                    textRun.textColor(resolveColorToHex(ctx, run.fillColor()));
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
            convertMathRunsInParagraph(ctx, para);
            splitOverlineRuns(para);
        }

        return paragraphs;
    }

    /**
     * resolved-only 단락 내 수식 폰트 런(EH/BT/NP)을 ASTEquation으로 변환.
     * ASTTextRun의 fontFamily를 기반으로 IDMLCharacterRun 어댑터를 생성하여
     * ASTMathGrouper.flush* 메서드로 위임.
     */
    private static void convertMathRunsInParagraph(ResolvedBuildContext ctx, ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        // IDML 경로에서 이미 ASTEquation으로 변환된 단락은 건너뜀 (중복 변환 방지)
        boolean hasEquation = false;
        boolean hasEHRun = false;
        for (ASTInlineItem it : items) {
            if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) hasEquation = true;
            if (it instanceof ASTTextRun) {
                String ff = ((ASTTextRun) it).fontFamily();
                if (ff != null && EHFontGlyphMap.isEHFontFamily(ff)) hasEHRun = true;
            }
        }
        if (hasEquation) {
            if (hasEHRun) {
                // 수식과 EH TextRun이 공존: EH TextRun은 수식 변환 잔여물 → 제거
                items.removeIf(it -> it instanceof ASTTextRun
                        && ((ASTTextRun) it).fontFamily() != null
                        && EHFontGlyphMap.isEHFontFamily(((ASTTextRun) it).fontFamily()));
            }
            return;
        }

        List<ASTInlineItem> newItems = new ArrayList<>();
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        String mathType = null; // "EH", "BT", "NP"

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                mathGroup.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            ASTTextRun tr = (ASTTextRun) item;
            String ff = tr.fontFamily();
            String currentType = null;
            if (ff != null) {
                if (EHFontGlyphMap.isEHFontFamily(ff)) currentType = "EH";
                else if (BTFontGlyphMap.isBTFontFamily(ff)) currentType = "BT";
                else if (NPFontGlyphMap.isNPFont(ff)) currentType = "NP";
            }

            if (currentType != null) {
                if (mathType == null || mathType.equals(currentType)) {
                    mathType = currentType;
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(ff);
                    mathGroup.add(cr);
                } else {
                    flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                    mathGroup.clear();
                    mathType = currentType;
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(ff);
                    mathGroup.add(cr);
                }
            } else {
                // EH 그룹이 열려있으면 비EH 런의 bridge 가능성 확인
                // 짧은 특수 공백(thin/four-per-em space) 또는 연산자 1문자만 bridge 허용
                boolean bridge = false;
                if ("EH".equals(mathType) && !mathGroup.isEmpty()) {
                    String text = tr.text();
                    if (text != null && text.length() <= 2) {
                        boolean allBridgeable = true;
                        for (int ci = 0; ci < text.length(); ci++) {
                            char c = text.charAt(ci);
                            // 특수 공백, 연산 기호만 bridge
                            if (c != '\u2005' && c != '\u2009' && c != '\u2003' && c != ' '
                                    && c != '+' && c != '-' && c != '=' && c != '\u00D7'
                                    && c != '\u00F7') {
                                allBridgeable = false;
                                break;
                            }
                        }
                        if (allBridgeable) {
                            // 뒤에 EH 폰트 런이 있는지 확인
                            for (int ni = i + 1; ni < items.size(); ni++) {
                                ASTInlineItem next = items.get(ni);
                                if (next instanceof ASTTextRun) {
                                    String nff = ((ASTTextRun) next).fontFamily();
                                    if (nff != null && EHFontGlyphMap.isEHFontFamily(nff)) {
                                        bridge = true;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                if (bridge) {
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(tr.fontFamily() != null ? tr.fontFamily() : "");
                    mathGroup.add(cr);
                } else {
                    flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                    mathGroup.clear();
                    mathType = null;
                    newItems.add(item);
                }
            }
        }
        flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);

        if (newItems.size() != items.size() || !newItems.equals(items)) {
            items.clear();
            items.addAll(newItems);
        }
    }

    private static void flushResolvedMathGroup(ResolvedBuildContext ctx, List<IDMLCharacterRun> group, String type,
                                         List<ASTInlineItem> out, ASTParagraph ignoredPara) {
        if (group == null || group.isEmpty()) return;
        // flush 메서드는 para에 직접 추가하므로, 임시 para를 사용하여 결과를 꺼냄
        ASTParagraph tempPara = new ASTParagraph();
        if ("EH".equals(type)) {
            ASTMathGrouper.flushEHMathGroup(group, tempPara);
        } else if ("BT".equals(type)) {
            ASTMathGrouper.flushMathGroup(group, tempPara);
        } else if ("NP".equals(type)) {
            ASTMathGrouper.flushNPMathGroup(group, tempPara);
        }
        out.addAll(tempPara.items());
    }

    /**
     * 수식 그룹 flush 헬퍼: null이 아닌 그룹만 flush하고 clear.
     */
    private static void flushMathGroups(ResolvedBuildContext ctx, List<IDMLCharacterRun> btGroup,
                                  List<IDMLCharacterRun> npGroup,
                                  List<IDMLCharacterRun> ehGroup,
                                  ASTParagraph para) {
        if (btGroup != null && !btGroup.isEmpty()) {
            ASTMathGrouper.flushMathGroup(btGroup, para);
            btGroup.clear();
        }
        if (npGroup != null && !npGroup.isEmpty()) {
            ASTMathGrouper.flushNPMathGroup(npGroup, para);
            npGroup.clear();
        }
        if (ehGroup != null && !ehGroup.isEmpty()) {
            ASTMathGrouper.flushEHMathGroup(ehGroup, para);
            ehGroup.clear();
        }
    }

    /**
     * 텍스트 내 ;...; 분수 GREP 패턴을 인라인 수식(ASTEquation)으로 분리.
     * 예: "이므로 ;4!;의 제곱근은" → "이므로 " + ASTEquation({1} over {4}) + "의 제곱근은"
     */
    private static void splitFractionPatternInText(ResolvedBuildContext ctx, String text, ASTTextRun templateRun, ASTParagraph para) {
        for (EHGrepFractionConverter.Segment seg : EHGrepFractionConverter.splitAndConvert(text)) {
            if (seg.type() == EHGrepFractionConverter.Segment.Type.EQUATION) {
                para.addItem(new ASTEquation(seg.content(), "EH_FONT"));
            } else {
                ASTTextRun tr = new ASTTextRun();
                tr.text(seg.content());
                tr.fontFamily(templateRun.fontFamily());
                tr.fontStyle(templateRun.fontStyle());
                tr.fontSizeHwpunits(templateRun.fontSizeHwpunits());
                tr.textColor(templateRun.textColor());
                para.addItem(tr);
            }
        }
    }

    /**
     * EH 그룹이 열려있고 마지막이 EH분수대문자(√)일 때,
     * 바로 뒤의 짧은 비EH 런이 루트 내용(radicand)인지 판단.
     * GREP 스타일이 IDML에 반영되지 않아 fontFamily=null인 런도 포함.
     */
    private static boolean isEHSqrtContent(IDMLCharacterRun run,
                                            List<IDMLCharacterRun> ehGroup) {
        if (ehGroup.isEmpty()) return false;
        // 마지막 EH 런이 분수대문자(√)인지
        IDMLCharacterRun last = ehGroup.get(ehGroup.size() - 1);
        if (!EHFontGlyphMap.isFractionNumeratorFont(last.fontFamily())) return false;
        // 현재 런이 짧은 라틴/수학 텍스트인지 (한국어만으로 시작하면 제외)
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        // 첫 문자가 라틴 알파벳, 숫자, 수학 기호이면 루트 내용
        return Character.isLetterOrDigit(first)
                && !(first >= 0xAC00 && first <= 0xD7AF)
                && !(first >= 0x3131 && first <= 0x318E);
    }

    /**
     * composedLines 문자 범위 기반 단락 분배.
     * 각 블록의 composedCharStart~composedCharEnd 범위에 해당하는 단락을 할당.
     */
    private static void distributeByComposedCharRange(ResolvedBuildContext ctx, List<ASTParagraph> paragraphs,
                                                List<ASTTextFrameBlock> blocks) {
        // 전체 단락 텍스트를 연속 문자열로 합침
        StringBuilder sb = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>();
        for (ASTParagraph p : paragraphs) {
            int s = sb.length();
            String pt = ParagraphTextHelpers.getParaPlainText(p);
            sb.append(pt != null ? pt : "");
            paraRanges.add(new int[]{s, sb.length()});
        }

        // 범위 겹침 기반 분배 (단락이 블록 경계를 걸치면 분할)
        for (ASTTextFrameBlock block : blocks) {
            int blockStart = block.composedCharStart();
            int blockEnd = block.composedCharEnd();
            if (blockStart < 0) continue;

            // YGapSplit 블록: composedCharStart/End가 paraIndex 범위를 의미
            boolean isYGapBlock = block.sourceId() != null && block.sourceId().contains("_g");
            if (isYGapBlock) {
                for (int i = 0; i < paragraphs.size(); i++) {
                    if (i >= blockStart && i <= blockEnd) {
                        block.addParagraph(paragraphs.get(i));
                    }
                }
                continue;
            }

            for (int i = 0; i < paragraphs.size(); i++) {
                int paraStart = paraRanges.get(i)[0];
                int paraEnd = paraRanges.get(i)[1];

                if (paraEnd <= blockStart) continue; // 단락이 블록 이전
                if (paraStart >= blockEnd) break;    // 단락이 블록 이후

                if (paraStart >= blockStart && paraEnd <= blockEnd) {
                    // 단락이 블록 안에 완전히 포함
                    block.addParagraph(paragraphs.get(i));
                } else if (paraStart < blockEnd && paraEnd > blockEnd) {
                    // 단락이 블록 끝을 넘김 → 앞부분만
                    int cutLen = blockEnd - paraStart;
                    ASTParagraph trimmed = ParagraphTextHelpers.createSplitParagraph(paragraphs.get(i),
                            ParagraphTextHelpers.getParaPlainText(paragraphs.get(i)) != null
                                    ? ParagraphTextHelpers.getParaPlainText(paragraphs.get(i)).substring(0, Math.min(cutLen, ParagraphTextHelpers.getParaPlainText(paragraphs.get(i)).length()))
                                    : "");
                    if (trimmed != null) block.addParagraph(trimmed);
                } else if (paraStart < blockStart && paraEnd > blockStart) {
                    // 이전 블록에서 시작된 단락의 나머지
                    int skipLen = blockStart - paraStart;
                    String fullText = ParagraphTextHelpers.getParaPlainText(paragraphs.get(i));
                    String contText = (fullText != null && skipLen < fullText.length())
                            ? fullText.substring(skipLen) : "";
                    ASTParagraph cont = ParagraphTextHelpers.createContinuationParagraph(paragraphs.get(i), skipLen, contText);
                    if (cont != null) block.addParagraph(cont);
                }
            }
        }
    }

    private static void distributeParagraphs(ResolvedBuildContext ctx, List<ASTParagraph> paragraphs,
                                       List<ASTTextFrameBlock> blocks, String storyId) {
        // composedLines 분할 블록 감지: composedCharStart >= 0인 블록이 있으면
        boolean hasComposedBlocks = false;
        for (ASTTextFrameBlock b : blocks) {
            if (b.composedCharStart() >= 0) { hasComposedBlocks = true; break; }
        }
        if (hasComposedBlocks) {
            distributeByComposedCharRange(ctx, paragraphs, blocks);
            return;
        }

        // 단일 프레임: frameVisibleText와 Story 텍스트 길이 비교
        if (blocks.size() == 1) {
            ASTTextFrameBlock block = blocks.get(0);
            // Story 텍스트가 길고 frameVisibleText가 거의 비어있으면 할당하지 않음
            // (다른 페이지의 프레임에서 실제로 표시되는 텍스트가 이 프레임에 잘못 할당되는 것 방지)
            int storyLen = 0;
            for (ASTParagraph p : paragraphs) {
                String pt = ParagraphTextHelpers.getParaPlainText(p);
                if (pt != null) storyLen += pt.length();
            }
            int visLen = block.frameVisibleTextLength();
            if (storyLen > 20 && visLen <= 1) {
                // Story가 20자 이상인데 프레임에 보이는 텍스트가 0~1자 → 오버플로우/미표시 프레임
                return;
            }
            for (ASTParagraph p : paragraphs) {
                block.addParagraph(p);
            }
            return;
        }

        // 다중 프레임: frameVisibleText 기반 분배
        List<ASTTextFrameBlock> ordered = orderByThreadChain(ctx, blocks);

        // 전체 IDML 단락 텍스트를 하나의 연속 문자열로 합침
        StringBuilder storyTextBuilder = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>(); // [startCharIdx, endCharIdx]
        for (ASTParagraph p : paragraphs) {
            int s = storyTextBuilder.length();
            String pt = ParagraphTextHelpers.getParaPlainText(p);
            storyTextBuilder.append(pt != null ? pt : "");
            paraRanges.add(new int[]{s, storyTextBuilder.length()});
        }
        String storyText = storyTextBuilder.toString();

        // 각 프레임의 첫 frameParaText를 storyText에서 검색하여 정확한 범위 결정
        // 프레임별 (startOffset, endOffset) 계산
        int[][] frameRanges = new int[ordered.size()][2];
        int searchFrom = 0;
        for (int fi = 0; fi < ordered.size(); fi++) {
            ASTTextFrameBlock block = ordered.get(fi);
            String sid2 = block.sourceId();
            String hex2 = sid2 != null && sid2.startsWith("u") ? sid2.substring(1) : sid2;
            if (hex2 != null && hex2.contains("_")) hex2 = hex2.substring(0, hex2.indexOf('_'));
            String domId;
            try { domId = String.valueOf(Integer.parseInt(hex2, 16)); }
            catch (NumberFormatException e) { domId = sid2; }
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId);
            // wrap 분할 블록은 블록 자체의 frameVisibleText 우선
            String visibleText = block.frameVisibleText();
            if (visibleText == null) {
                visibleText = (rtf != null) ? rtf.frameVisibleText() : null;
            }
            if (visibleText != null) {
                visibleText = visibleText.replace("\uFFFC", "").replace("\n", "");
            }

            if (visibleText == null || visibleText.isEmpty()) {
                // frameVisibleText가 없으면 frameParaTexts 폴백
                java.util.List<String> frameTexts = (rtf != null) ? rtf.frameParaTexts() : null;
                if (frameTexts != null && !frameTexts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String ft : frameTexts) {
                        if (ft != null) sb.append(ft.replace("\uFFFC", ""));
                    }
                    visibleText = sb.toString();
                }
            }

            if (visibleText == null || visibleText.isEmpty()) {
                frameRanges[fi][0] = searchFrom;
                frameRanges[fi][1] = (fi == ordered.size() - 1) ? storyText.length() : searchFrom;
                continue;
            }

            // visibleText의 앞부분을 storyText에서 검색하여 시작 위치 결정
            String startKey = visibleText.length() > 20 ? visibleText.substring(0, 20) : visibleText;
            int foundStart = storyText.indexOf(startKey, searchFrom);
            if (foundStart < 0) foundStart = searchFrom;

            // visibleText의 끝부분을 storyText에서 검색하여 종료 위치 결정
            String endKey = visibleText.length() > 20 ? visibleText.substring(visibleText.length() - 20) : visibleText;
            int foundEnd = storyText.indexOf(endKey, foundStart);
            if (foundEnd >= 0) {
                foundEnd += endKey.length();
            } else {
                foundEnd = foundStart + visibleText.length();
            }

            frameRanges[fi][0] = foundStart;
            frameRanges[fi][1] = Math.min(foundEnd, storyText.length());
            searchFrom = frameRanges[fi][1];
        }

        // 프레임별 단락 할당
        for (int fi = 0; fi < ordered.size(); fi++) {
            ASTTextFrameBlock block = ordered.get(fi);
            int frameStart = frameRanges[fi][0];
            int frameEnd = frameRanges[fi][1];
            String sid3 = block.sourceId();
            String hex3 = sid3 != null && sid3.startsWith("u") ? sid3.substring(1) : sid3;
            if (hex3 != null && hex3.contains("_")) hex3 = hex3.substring(0, hex3.indexOf('_'));
            String domId3;
            try { domId3 = String.valueOf(Integer.parseInt(hex3, 16)); }
            catch (NumberFormatException e) { domId3 = sid3; }
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId3);
            java.util.List<String> frameTexts = (rtf != null) ? rtf.frameParaTexts() : null;

            if (frameTexts == null || frameTexts.isEmpty()) {
                int start = (rtf != null) ? Math.max(0, rtf.paragraphStart()) : 0;
                int end = (rtf != null && rtf.paragraphEnd() >= 0) ? rtf.paragraphEnd() : paragraphs.size() - 1;
                for (int i = start; i <= end && i < paragraphs.size(); i++) {
                    block.addParagraph(paragraphs.get(i));
                }
                continue;
            }

            for (int i = 0; i < paragraphs.size(); i++) {
                int paraStart = paraRanges.get(i)[0];
                int paraEnd = paraRanges.get(i)[1];

                if (paraEnd <= frameStart) continue;
                if (paraStart >= frameEnd) break;

                if (paraStart >= frameStart && paraEnd <= frameEnd) {
                    // 단락이 프레임 안에 완전히 포함
                    block.addParagraph(paragraphs.get(i));
                } else if (paraStart < frameEnd && paraEnd > frameEnd) {
                    // 단락이 프레임 경계에 걸침 → 앞부분만 (cutLen 글자)
                    int cutLen = frameEnd - paraStart;
                    String fullText = ParagraphTextHelpers.getParaPlainText(paragraphs.get(i));
                    String cutText = (fullText != null && cutLen < fullText.length()) ? fullText.substring(0, cutLen) : fullText;
                    ASTParagraph trimmed = ParagraphTextHelpers.createSplitParagraph(paragraphs.get(i), cutText);
                    if (trimmed != null) {
                        block.addParagraph(trimmed);
                    }
                } else if (paraStart < frameStart && paraEnd > frameStart) {
                    // 이전 프레임에서 시작된 단락의 나머지
                    int skipLen = frameStart - paraStart;
                    String fullText = ParagraphTextHelpers.getParaPlainText(paragraphs.get(i));
                    String contText = (fullText != null && skipLen < fullText.length()) ? fullText.substring(skipLen) : "";
                    ASTParagraph continuation = ParagraphTextHelpers.createContinuationParagraph(paragraphs.get(i), skipLen, contText);
                    if (continuation != null) {
                        block.addParagraph(continuation);
                    }
                }
            }
        }
    }

    /**
     * 스레드 체인 순서로 블록 정렬: previousFrameId=null인 첫 번째 프레임부터 순서대로.
     */
    private static List<ASTTextFrameBlock> orderByThreadChain(ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (blocks.size() <= 1) return blocks;

        // domId → block 매핑
        Map<String, ASTTextFrameBlock> byDomId = new java.util.LinkedHashMap<String, ASTTextFrameBlock>();
        for (ASTTextFrameBlock b : blocks) {
            String sid = b.sourceId();
            if (sid == null) continue;
            String hexPart = sid.startsWith("u") ? sid.substring(1) : sid;
            if (hexPart.contains("_")) hexPart = hexPart.substring(0, hexPart.indexOf('_'));
            String domId;
            try { domId = String.valueOf(Integer.parseInt(hexPart, 16)); }
            catch (NumberFormatException e) { domId = sid; }
            byDomId.put(domId, b);
        }

        // 첫 번째 프레임 찾기 (previousFrameId=null)
        String firstId = null;
        for (String domId : byDomId.keySet()) {
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId);
            if (rtf != null && rtf.previousFrameId() == null) {
                firstId = domId;
                break;
            }
        }

        if (firstId == null) return blocks; // 체인 시작을 못 찾으면 원래 순서

        // 체인 순서로 정렬
        List<ASTTextFrameBlock> ordered = new ArrayList<ASTTextFrameBlock>();
        String currentId = firstId;
        java.util.Set<String> visited = new java.util.HashSet<String>();
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            ASTTextFrameBlock b = byDomId.get(currentId);
            if (b != null) ordered.add(b);
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(currentId);
            currentId = (rtf != null) ? rtf.nextFrameId() : null;
        }

        // 체인에 포함되지 않은 블록 추가
        for (ASTTextFrameBlock b : blocks) {
            if (!ordered.contains(b)) ordered.add(b);
        }

        return ordered;
    }

    /**
     * 인라인 앵커 객체가 짧은 텍스트(1~5자)를 가진 TextFrame이면
     * PNG 이미지 대신 ASTTextRun으로 변환 (줄간격 영향 없음, 폰트 매핑 가능).
     * @return ASTTextRun (텍스트로 변환됨) 또는 null (PNG 변환 필요)
     */
    /**
     * 인라인 TextFrame이 분수 구조(2 paragraphs = 분자/분모)이면 ASTEquation으로 변환.
     * @return ASTEquation 또는 null (분수가 아님)
     */
    private static kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation tryInlineFractionAsEquation(
            ResolvedBuildContext ctx, int anchoredObjectId) {
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null || !tf.isInline()) return null;
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;

        // 2개 단락 = 분수 구조 (frameParaTexts[0]=분자, [1]=분모)
        ResolvedStory rs = (tf.storyId() != null) ? ctx.resolvedData.getStory(tf.storyId()) : null;
        if (rs == null || rs.paragraphs().size() != 2) return null;

        // 각 단락의 텍스트 수집 (EH 수식 폰트 포함)
        String numerator = collectParagraphEquationText(rs.paragraphs().get(0));
        String denominator = collectParagraphEquationText(rs.paragraphs().get(1));
        if (numerator == null || denominator == null) return null;
        numerator = numerator.trim();
        denominator = denominator.trim();
        if (numerator.isEmpty() || denominator.isEmpty()) return null;

        // EH 수식 런이 포함되어 있으면 EH 변환 파이프라인으로 처리
        String numScript = convertRunsToHwpScript(rs.paragraphs().get(0));
        String denomScript = convertRunsToHwpScript(rs.paragraphs().get(1));
        if (numScript == null) numScript = numerator;
        if (denomScript == null) denomScript = denominator;

        String hwpScript = "{" + numScript + "} over {" + denomScript + "}";
        return new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(hwpScript, "EH_FONT");
    }

    private static String collectParagraphEquationText(ResolvedParagraph rp) {
        if (rp.runs() == null || rp.runs().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (ResolvedRun r : rp.runs()) {
            if (r.text() != null) sb.append(r.text());
        }
        return sb.toString();
    }

    private static String convertRunsToHwpScript(ResolvedParagraph rp) {
        if (rp.runs() == null || rp.runs().isEmpty()) return null;
        boolean hasEH = false;
        for (ResolvedRun r : rp.runs()) {
            if (r.fontFamily() != null && EHFontGlyphMap.isEHFontFamily(r.fontFamily())) {
                hasEH = true;
                break;
            }
        }
        if (!hasEH) return null;

        // EH 런을 IDMLCharacterRun으로 변환하여 EHFontEquationConverter로 처리
        List<IDMLCharacterRun> ehRuns = new ArrayList<>();
        for (ResolvedRun r : rp.runs()) {
            IDMLCharacterRun cr = new IDMLCharacterRun();
            cr.content(r.text());
            cr.fontFamily(r.fontFamily());
            ehRuns.add(cr);
        }
        return EHFontEquationConverter.convert(ehRuns);
    }

    private static ASTTextRun tryInlineTextFrameAsRun(ResolvedBuildContext ctx, int anchoredObjectId) {
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null || !tf.isInline()) return null;

        // rendered된 TF(badge_group 등)는 PNG로 이미 배치됨 → 텍스트 런 변환 안 함
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;

        // frameVisibleText 또는 IDML Story에서 텍스트 가져오기
        String visText = tf.frameVisibleText();
        if (visText != null) {
            visText = visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").trim();
        }
        if (visText == null || visText.isEmpty()) {
            // IDML Story에서 폴백
            if (tf.storyId() != null) {
                IDMLStory idmlStory = ctx.loadIDMLStory.apply(tf.storyId());
                if (idmlStory != null) {
                    StringBuilder sb = new StringBuilder();
                    for (IDMLParagraph p : idmlStory.paragraphs()) {
                        for (IDMLCharacterRun r : p.characterRuns()) {
                            if (r.content() != null) sb.append(r.content());
                        }
                    }
                    visText = sb.toString().replace("\uFFFC", "").trim();
                }
            }
        }
        if (visText == null || visText.isEmpty()) return null;

        // 장식 번호 인라인(≤3자 + 큰 폰트 또는 정사각형 프레임) → 텍스트 런 변환하지 않음 (PNG 유지)
        if (visText.length() <= 3) {
            ResolvedStory rs = (tf.storyId() != null) ? ctx.resolvedData.getStory(tf.storyId()) : null;
            boolean isDecorativeNumber = false;
            // 큰 폰트(≥16pt) 체크
            if (rs != null && !rs.paragraphs().isEmpty()) {
                ResolvedParagraph rp0 = rs.paragraphs().get(0);
                if (rp0.runs() != null && !rp0.runs().isEmpty()) {
                    Double fs = rp0.runs().get(0).fontSize();
                    if (fs != null && fs >= 16) isDecorativeNumber = true;
                }
            }
            // 정사각형에 가까운 프레임 (가로/세로 비율 0.7~1.4)
            double[] gb = tf.geometricBounds();
            if (gb != null && gb.length >= 4) {
                double fw = gb[3] - gb[1];
                double fh = gb[2] - gb[0];
                if (fw > 0 && fh > 0) {
                    double ratio = fw / fh;
                    if (ratio >= 0.7 && ratio <= 1.4) isDecorativeNumber = true;
                }
            }
            if (isDecorativeNumber) return null; // PNG 폴백 (loadInlineObject로 처리)
        }

        // resolved story에서 런 스타일 가져오기
        ResolvedStory story = (tf.storyId() != null) ? ctx.resolvedData.getStory(tf.storyId()) : null;
        ASTTextRun run = new ASTTextRun();
        run.text(visText + " "); // 뒤에 공백 추가 (텍스트와의 간격)

        if (story != null && !story.paragraphs().isEmpty()) {
            ResolvedParagraph rp = story.paragraphs().get(0);
            if (rp.runs() != null && !rp.runs().isEmpty()) {
                ResolvedRun rr = rp.runs().get(0);
                if (rr.fontFamily() != null) run.fontFamily(rr.fontFamily());
                if (rr.fontStyle() != null) run.fontStyle(rr.fontStyle());
                if (rr.fontSize() != null && rr.fontSize() > 0) {
                    run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                }
                if (rr.fillColor() != null) run.textColor(resolveColorToHex(ctx, rr.fillColor()));
                if (rr.underline() != null && rr.underline()) run.underline(true);
                if (rr.strikeThru() != null && rr.strikeThru()) run.strikeThrough(true);
            }
        }
        // IDML CharacterStyle에서 밑줄 추론
        if (tf.storyId() != null) {
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(tf.storyId());
            if (idmlStory != null && !idmlStory.paragraphs().isEmpty()) {
                IDMLParagraph ip = idmlStory.paragraphs().get(0);
                if (!ip.characterRuns().isEmpty()) {
                    IDMLCharacterRun cr = ip.characterRuns().get(0);
                    if (cr.underline() != null && cr.underline()) run.underline(true);
                    String cs = cr.appliedCharacterStyle();
                    if (cs != null && (cs.contains("밑줄") || cs.toLowerCase().contains("underline"))) {
                        run.underline(true);
                    }
                }
            }
        }

        // 인라인 TextFrame의 ParagraphStyle에서 밑줄 추론
        // resolved story의 styleName에 "선", "답+선", "underline" 등이 포함되면 밑줄
        if (story != null && !story.paragraphs().isEmpty()) {
            String styleName = story.paragraphs().get(0).styleName();
            if (styleName != null && (styleName.contains("선") || styleName.toLowerCase().contains("underline"))) {
                run.underline(true);
            }
        }

        return run;
    }

    /** IDML 경로용: resolved TextFrame bounds만으로 판별 (renderedFloatingItems 사용 안 함) */
    private static boolean isAnchoredOutsideParentByTextFrame(ResolvedBuildContext ctx, int anchoredId, String parentStoryId) {
        ResolvedTextFrame anchoredTf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredId));
        if (anchoredTf == null) {
            // TextFrame이 아닌 인라인(Polygon/Rectangle 등): renderedFloatingItems에서 bounds 확인
            return isAnchoredOutsideParent(ctx, anchoredId, parentStoryId);
        }
        double[] aGb = anchoredTf.geometricBounds();
        if (aGb == null || aGb.length < 4) return false;
        // 다중 컬럼 스레드 스토리: 어느 한 프레임에라도 포함되면 outside가 아님
        boolean anyParentChecked = false;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (parentStoryId.equals(tf.storyId()) && !tf.isInline()) {
                double[] pGb = tf.geometricBounds();
                if (pGb == null || pGb.length < 4) continue;
                anyParentChecked = true;
                if (!isOutsideParentBounds(ctx, aGb, pGb)) return false;
            }
        }
        return anyParentChecked;
    }

    /** Resolved 경로용: resolved TextFrame + renderedFloatingItems bounds로 판별 */
    private static boolean isAnchoredOutsideParent(ResolvedBuildContext ctx, int anchoredId, String parentStoryId) {
        double[] aGb = null;
        // 1) resolved TextFrame에서 bounds
        ResolvedTextFrame anchoredTf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredId));
        if (anchoredTf != null) {
            aGb = anchoredTf.geometricBounds();
        }
        // 2) resolved에 없으면 renderedFloatingItems의 inline_object bounds
        //    renderedFloatingItems bounds は mm 単位 → pt に変환이 필요
        if (aGb == null || aGb.length < 4) {
            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == anchoredId && "inline_object".equals(rg.itemType())) {
                    double[] raw = rg.bounds();
                    if (raw != null && raw.length >= 4) {
                        aGb = new double[]{raw[0] * ctx.scaleFactor, raw[1] * ctx.scaleFactor,
                                raw[2] * ctx.scaleFactor, raw[3] * ctx.scaleFactor};
                    }
                    break;
                }
            }
        }
        if (aGb == null || aGb.length < 4) return false;

        // 부모 Story의 모든 비인라인 TextFrame을 검사. 어느 한 프레임에라도 포함되면 outside가 아님.
        // (스레드 체인된 다중 컬럼 스토리에서 한쪽 컬럼에만 포함되어도 정상)
        boolean anyParentChecked = false;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (parentStoryId.equals(tf.storyId()) && !tf.isInline()) {
                double[] pGb = tf.geometricBounds();
                if (pGb == null || pGb.length < 4) continue;
                anyParentChecked = true;
                if (!isOutsideParentBounds(ctx, aGb, pGb)) return false;
            }
        }
        return anyParentChecked;
    }

    /**
     * 인라인 객체 bounds가 부모 프레임 밖에 위치하는지 판단.
     * - 중심 X가 부모 밖 3pt 이상 → outside
     * - 오른쪽 끝이 부모 밖으로 돌출하고 폭의 50% 이상 밖 → outside (장식 그래픽)
     */
    private static boolean isOutsideParentBounds(ResolvedBuildContext ctx, double[] aGb, double[] pGb) {
        double aCenterX = (aGb[1] + aGb[3]) / 2.0;
        // 중심 X 기준 (기존 로직, 허용 오차 3pt)
        if (aCenterX > pGb[3] + 3.0 || aCenterX < pGb[1] - 3.0) return true;
        // 오른쪽 돌출 체크: 인라인 객체의 절반 이상이 부모 밖
        double aWidth = aGb[3] - aGb[1];
        if (aWidth > 0 && aGb[3] > pGb[3]) {
            double overshoot = aGb[3] - pGb[3];
            if (overshoot > aWidth * 0.5) return true;
        }
        // 왼쪽 돌출 체크
        if (aWidth > 0 && aGb[1] < pGb[1]) {
            double overshoot = pGb[1] - aGb[1];
            if (overshoot > aWidth * 0.5) return true;
        }
        return false;
    }

    /**
     * PNG/텍스트 없는 인라인 빈칸 앵커를 공백 텍스트 런으로 대체.
     * 교과서 빈칸 채우기 문제의 ( ) 안 공백 등.
     */
    private static ASTTextRun createSpaceRunForEmptyAnchor(ResolvedBuildContext ctx, int anchoredObjectId) {
        // SPEC-020: 빈칸박스 TextFrame(공백 내용)은 실제 bounds 폭에 맞춰 공백 수 계산
        // + 밑줄 적용 — 배경 PNG의 "빈칸 밑줄"과 위치/길이 동조.
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
        double widthPt = 20.0;
        if (tf != null && tf.geometricBounds() != null && tf.geometricBounds().length >= 4) {
            double[] gb = tf.geometricBounds();
            widthPt = Math.max(0, gb[3] - gb[1]);
        }
        // 공백 1칸 ≈ 3pt (10.5pt 폰트 기준). 최소 4칸.
        int spaces = Math.max(4, (int) Math.round(widthPt / 3.0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaces; i++) sb.append(' ');
        ASTTextRun run = new ASTTextRun();
        run.text(sb.toString());
        run.underline(true);
        return run;
    }

    private static boolean isNoneColor(String c) {
        return c == null || c.isEmpty() || "None".equals(c) || c.contains("[None]");
    }

    /**
     * SPEC-020: 빈 컨테이너 = fill/stroke 모두 None 인 inline TextFrame.
     * 이런 프레임은 PNG 안에 그려진 일러스트/외곽선의 텍스트 입력란이므로
     * inline_object PNG 로드 결정에서 "텍스트 중복" 폐기 사유로 보지 않는다.
     */
    private static boolean isEmptyContainer(ResolvedTextFrame tf) {
        return isNoneColor(tf.fillColor()) && isNoneColor(tf.strokeColor());
    }

    /**
     * renderedFloatingItems에서 인라인 객체 PNG를 로드하여 ASTInlineObject로 변환.
     */
    private static ASTInlineObject loadInlineObject(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx.basePath == null) return null;

        // 자식/자손 TextFrame이 플로팅 텍스트박스로 배치될 예정이면
        // inline_object PNG를 로드하지 않는다 (이미지 + 글상자 중복 방지).
        // Rectangle은 childIds가 비어있고 자식이 parentId로만 참조하므로 textFrames를 훑는다.
        String anchorIdStr = String.valueOf(anchoredObjectId);
        for (ResolvedTextFrame childTf : ctx.resolvedData.textFrames()) {
            // childTf의 조상 중에 anchorId가 있는지 확인
            boolean isDescendant = false;
            String curId = childTf.id();
            int depth = 0;
            while (curId != null && depth < 8) {
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(curId);
                if (pi == null) break;
                String pid = pi.parentId();
                if (pid == null) break;
                if (anchorIdStr.equals(pid)) { isDescendant = true; break; }
                curId = pid;
                depth++;
            }
            if (!isDescendant) continue;
            String vt = childTf.frameVisibleText();
            boolean hasText = vt != null && vt.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().length() > 1;
            if (!hasText) continue;
            // SPEC-020: 빈 컨테이너(fill=None, stroke=None)는 텍스트 입력란이며,
            // PNG는 그 입력란을 둘러싼 시각적 배경(일러스트/라운드 외곽선)만 담는다.
            // 텍스트는 별도 오버레이되므로 PNG를 폐기하면 안 됨.
            if (isEmptyContainer(childTf)) continue;
            if (ctx.resolvedData.isEditableTextFrame(childTf.id())
                    || childTf.isInline()) {
                return null;
            }
        }

        // 같은 ID가 badge_group으로도 등록되어 있으면 badge PNG를 우선 사용.
        // (inline_object PNG는 자식 텍스트를 누락하는 경우가 있음)
        RenderedGroup badgeGroup = null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == anchoredObjectId && "badge_group".equals(rg.itemType())) {
                badgeGroup = rg;
                break;
            }
        }

        // renderedFloatingItems에서 해당 ID의 inline_object 찾기 (badge_group이 있으면 그것으로 교체)
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == anchoredObjectId && "inline_object".equals(rg.itemType())) {
                if (badgeGroup != null) rg = badgeGroup;
                if (rg.file() == null) return null;
                File pngFile = new File(ctx.basePath, rg.file());
                if (!pngFile.exists()) return null;

                try {
                    byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                    BufferedImage img = ImageIO.read(pngFile);
                    if (img == null) return null;
                    // 2x2 이하 빈 이미지 무시
                    if (img.getWidth() <= 2 && img.getHeight() <= 2) return null;

                    ASTInlineObject obj = new ASTInlineObject();
                    obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                    obj.imageData(imageData);
                    obj.imageFormat("png");
                    obj.pixelWidth(img.getWidth());
                    obj.pixelHeight(img.getHeight());

                    // 크기: bounds [top, left, bottom, right]
                    double[] bounds = rg.bounds();
                    if (bounds != null && bounds.length >= 4) {
                        obj.boundsX(bounds[1]); // rendered X 좌표 (인라인 정렬용)
                        // SPEC-020: 페이지 절대 좌표 기록 — 같은 셀에 여러 인라인이 있을 때
                        // cellX/cellY fallback 으로 겹치는 문제를 막는다.
                        double pxPt = bounds[1] * ctx.scaleFactor;
                        double pyPt = bounds[0] * ctx.scaleFactor;
                        obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pxPt));
                        obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pyPt));
                        double bw = Math.abs(bounds[3] - bounds[1]) * ctx.scaleFactor; // right - left
                        double bh = Math.abs(bounds[2] - bounds[0]) * ctx.scaleFactor; // bottom - top
                        // PNG 비율로 보정 (bounds가 부정확한 경우)
                        double pngRatio = (double) img.getWidth() / img.getHeight();
                        double boundsRatio = bw / bh;
                        // bounds 비율과 PNG 비율이 다르면 PNG 비율 기준으로 보정
                        // bounds의 작은 쪽을 기준으로 맞춤 (원본 크기 초과 방지)
                        if (Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
                            if (pngRatio < 1.0) {
                                // 세로가 더 긴 PNG → 높이 유지, 폭 축소
                                bw = bh * pngRatio;
                            } else {
                                // 가로가 더 긴 PNG → 폭 유지, 높이 축소
                                bh = bw / pngRatio;
                            }
                        }
                        obj.width(CoordinateConverter.pointsToHwpunits(bw));
                        obj.height(CoordinateConverter.pointsToHwpunits(bh));
                    } else {
                        double pw = img.getWidth(), ph = img.getHeight();
                        obj.width(CoordinateConverter.pointsToHwpunits(Math.max(pw, ph) * 72.0 / ctx.pngExportDpi));
                        obj.height(CoordinateConverter.pointsToHwpunits(Math.min(pw, ph) * 72.0 / ctx.pngExportDpi));
                    }

                    obj.sourceId("u" + Integer.toHexString(anchoredObjectId));

                    // 장식 번호 인라인(≤3자 + 큰 폰트/정사각형): 높이를 본문 줄 높이로 제한
                    // 인라인 이미지 높이가 줄간격을 벌리는 것 방지
                    ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
                    if (rtf != null && obj.height() > 1500) { // 15pt 초과
                        // 프레임 가로/세로 비율이 정사각형에 가까우면 높이 제한
                        double[] rtfGb = rtf.geometricBounds();
                        if (rtfGb != null && rtfGb.length >= 4) {
                            double fw = rtfGb[3] - rtfGb[1];
                            double fh = rtfGb[2] - rtfGb[0];
                            if (fw > 0 && fh > 0 && fw / fh >= 0.7 && fw / fh <= 1.4) {
                                long maxH = 1200; // 12pt — 본문 줄 높이 이하
                                long scaledW = obj.width() * maxH / obj.height();
                                obj.height(maxH);
                                obj.width(scaledW);
                            }
                        }
                    }

                    return obj;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * paragraphStyles에서 스타일 이름으로 alignment를 조회한다.
     */
    private static String resolveStyleAlignment(String styleName, ASTDocument doc) {
        if (styleName == null || doc == null) return null;
        for (ASTStyleDef sd : doc.paragraphStyles()) {
            if (styleName.equals(sd.styleName())) {
                return sd.alignment();
            }
        }
        return null;
    }

    /**
     * Ó(U+00D3) → \uE000{대문자들}\uE001 마커로 치환.
     * 예: "ABÓ=APÓ" → "\uE000AB\uE001=\uE000AP\uE001"
     */
    private static String markOverlineSegments(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00D3') {
                // Ó: 앞으로 거슬러 올라가며 연속 대문자를 찾아 마커로 감싸기
                int endPos = sb.length();
                int startPos = endPos;
                while (startPos > 0 && sb.charAt(startPos - 1) >= 'A' && sb.charAt(startPos - 1) <= 'Z') {
                    startPos--;
                }
                if (startPos < endPos) {
                    String letters = sb.substring(startPos, endPos);
                    sb.replace(startPos, endPos, "\uE000" + letters + "\uE001");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * \uE000...\uE001 overline 마커가 포함된 TextRun을 분리하여 ASTEquation(overline{AB})으로 변환.
     */
    private static void splitOverlineRuns(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;
        boolean hasMarker = false;
        for (ASTInlineItem it : items) {
            if (it instanceof ASTTextRun) {
                String t = ((ASTTextRun) it).text();
                if (t != null && t.indexOf('\uE000') >= 0) { hasMarker = true; break; }
            }
        }
        if (!hasMarker) return;

        List<ASTInlineItem> newItems = new ArrayList<>();
        for (ASTInlineItem item : items) {
            if (!(item instanceof ASTTextRun)) {
                newItems.add(item);
                continue;
            }
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text == null || text.indexOf('\uE000') < 0) {
                newItems.add(item);
                continue;
            }
            // \uE000...\uE001 패턴으로 분할
            int pos = 0;
            while (pos < text.length()) {
                int markerStart = text.indexOf('\uE000', pos);
                if (markerStart < 0) {
                    // 나머지 텍스트
                    String rest = text.substring(pos);
                    if (!rest.isEmpty()) {
                        ASTTextRun tr = new ASTTextRun();
                        tr.text(rest);
                        tr.fontFamily(run.fontFamily());
                        tr.fontStyle(run.fontStyle());
                        tr.fontSizeHwpunits(run.fontSizeHwpunits());
                        tr.textColor(run.textColor());
                        newItems.add(tr);
                    }
                    break;
                }
                // 마커 앞 텍스트
                if (markerStart > pos) {
                    ASTTextRun tr = new ASTTextRun();
                    tr.text(text.substring(pos, markerStart));
                    tr.fontFamily(run.fontFamily());
                    tr.fontStyle(run.fontStyle());
                    tr.fontSizeHwpunits(run.fontSizeHwpunits());
                    tr.textColor(run.textColor());
                    newItems.add(tr);
                }
                int markerEnd = text.indexOf('\uE001', markerStart + 1);
                if (markerEnd < 0) markerEnd = text.length();
                String letters = text.substring(markerStart + 1, markerEnd);
                // ASTEquation: overline{AB}
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(
                                "overline{" + letters + "}", "EH_FONT");
                newItems.add(eq);
                pos = markerEnd + 1;
            }
        }
        items.clear();
        items.addAll(newItems);
    }

    /**
     * 이탤릭 TextRun을 인라인 수식(ASTEquation)으로 변환.
     * 한컴 수식 에디터가 변수=이탤릭, 괄호/연산자=정체를 자동 처리.
     * 연속된 이탤릭 수학 런은 하나의 수식으로 합쳐서 변환.
     */
    private static void convertItalicRunsToEquations(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        boolean hasTarget = false;
        for (ASTInlineItem it : items) {
            if (it instanceof ASTTextRun && isItalicMathRun((ASTTextRun) it)) {
                hasTarget = true;
                break;
            }
        }
        if (!hasTarget) return;

        List<ASTInlineItem> newItems = new ArrayList<>();
        StringBuilder mathBuf = new StringBuilder();
        List<IDMLCharacterRun> mathRuns = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTTextRun && isItalicMathRun((ASTTextRun) item)) {
                ASTTextRun tr = (ASTTextRun) item;
                mathBuf.append(tr.text());
                IDMLCharacterRun cr = new IDMLCharacterRun();
                cr.content(tr.text());
                cr.fontFamily(tr.fontFamily());
                mathRuns.add(cr);
            } else if (item instanceof ASTTextRun && mathBuf.length() > 0) {
                // 수식 버퍼가 열려있을 때 backtick/thin space 등 짧은 비수식 런은 흡수
                String t = ((ASTTextRun) item).text();
                if (t != null && t.length() <= 2 && !t.isEmpty()) {
                    boolean allSkippable = true;
                    for (int ci = 0; ci < t.length(); ci++) {
                        char c = t.charAt(ci);
                        if (c != '`' && c != ' ' && c != '\u2009' && c != '\u2005') {
                            allSkippable = false; break;
                        }
                    }
                    if (allSkippable) continue; // backtick/space 스킵
                }
                // 수학 버퍼 flush + 일반 텍스트 추가
                flushItalicMathBuf(mathBuf, mathRuns, newItems);
                newItems.add(item);
            } else if (item instanceof ASTEquation) {
                // 기존 수식도 연속 이탤릭 버퍼에 합침
                mathBuf.append(((ASTEquation) item).hwpScript());
            } else {
                // 수학 버퍼 flush
                flushItalicMathBuf(mathBuf, mathRuns, newItems);
                newItems.add(item);
            }
        }
        // 마지막 버퍼 flush
        flushItalicMathBuf(mathBuf, mathRuns, newItems);

        items.clear();
        items.addAll(newItems);
    }

    /**
     * 이탤릭 수학 버퍼를 ASTEquation으로 변환.
     * EH 폰트 런이 있으면 EHFontEquationConverter로 파이프라인 처리,
     * 없으면 raw 텍스트에서 backtick 제거 후 수식으로 사용.
     */
    private static void flushItalicMathBuf(StringBuilder mathBuf, List<IDMLCharacterRun> mathRuns,
                                            List<ASTInlineItem> out) {
        if (mathBuf.length() == 0) return;

        // EH 폰트 런이 있으면 EH 변환 파이프라인 사용
        boolean hasEH = false;
        for (IDMLCharacterRun cr : mathRuns) {
            if (cr.fontFamily() != null && EHFontGlyphMap.isEHFontFamily(cr.fontFamily())) {
                hasEH = true;
                break;
            }
        }
        String script;
        if (hasEH && mathRuns.size() > 0) {
            script = EHFontEquationConverter.convert(mathRuns);
        } else {
            script = null;
        }
        if (script == null || script.isEmpty()) {
            // EH 변환 실패 → raw 텍스트에서 backtick 제거
            script = mathBuf.toString().replace("`", "");
        }
        if (!script.isEmpty()) {
            out.add(new ASTEquation(script, "EH_FONT"));
        }
        mathBuf.setLength(0);
        mathRuns.clear();
    }

    /** 이탤릭 수학 런 판별: Italic 또는 EH수식 폰트 + 라틴 알파벳/숫자/수학 기호만 포함 */
    private static boolean isItalicMathRun(ASTTextRun tr) {
        String text = tr.text();
        if (text == null || text.isEmpty()) return false;
        // 한국어가 포함되면 수식이 아님
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
        }
        // Italic fontStyle
        String fs = tr.fontStyle();
        boolean isItalic = fs != null && fs.toLowerCase().contains("italic");
        // EH 수식 폰트 (이탤릭 여부와 무관하게 수식으로 변환)
        String ff = tr.fontFamily();
        boolean isEHFont = ff != null && EHFontGlyphMap.isEHFontFamily(ff);
        if (!isItalic && !isEHFont) return false;
        // EH 폰트 런은 길이와 무관하게 수식으로 간주 (backtick, 연산자 포함)
        if (isEHFont) return true;
        // 이탤릭 런: 2자 이상이면 수식으로 간주
        if (text.length() >= 2) return true;
        // 단일 문자: 알파벳이면 수식 변수
        char c0 = text.charAt(0);
        return (c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z');
    }

}
