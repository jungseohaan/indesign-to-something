package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTMathGrouper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTRunConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTStoryConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedTextFlowAstConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextFlowTabPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.DoviraSubunitMarkerPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTabStop;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/**
 * Phase 3 IDML Story 로딩 & 분기 (W3 Step C, 재시도).
 * IDML Story XML에서 단락을 파싱하여 ASTParagraph 리스트로 변환.
 * StoryConverter에서 분리됨 — RunBuilder/InlineFrameHandler 추출 후 의존성 줄어 추출 가능.
 */
public class StoryLoader {
    private StoryLoader() {}


    /**
     * IDML Story XML에서 단락을 파싱하여 ASTParagraph 리스트로 변환.
     * IDML의 단락 구조는 정확 (중복 없음, <Br/> 기반 분리).
     * 단락 속성(leading, indent)은 resolved에서 보강.
     */
    static List<ASTParagraph> convertStoryFromIDML(ResolvedBuildContext ctx, String storyId) {
        if (ctx.idmlDir == null) return null;
        // storyId(DOM decimal) → IDML hex → Story_u{hex}.xml
        String sourceStoryId = sourceStoryId(storyId);
        String hexId;
        try {
            if (sourceStoryId.startsWith("u") || sourceStoryId.startsWith("U")) {
                hexId = "u" + sourceStoryId.substring(1).toLowerCase();
            } else {
                hexId = "u" + Integer.toHexString(Integer.parseInt(sourceStoryId));
            }
        } catch (NumberFormatException e) {
            return null;
        }

        // 캐시 확인
        IDMLStory idmlStory = null;
        if (ctx.idmlStoryCache != null) {
            idmlStory = ctx.idmlStoryCache.get(storyId);
            if (idmlStory == null) idmlStory = ctx.idmlStoryCache.get(sourceStoryId);
            if (idmlStory == null) idmlStory = ctx.idmlStoryCache.get(hexId);
        }
        if (idmlStory != null) {
            ConversionTiming.addCounter("phase3.storyLoader.cacheHits", 1);
        }
        if (idmlStory == null && ctx.loadIDMLStory != null) {
            idmlStory = ctx.loadIDMLStory.apply(storyId);
            if (idmlStory != null) {
                ConversionTiming.addCounter("phase3.storyLoader.sharedLoaderHits", 1);
            }
        }
        if (idmlStory == null) {
            File storyFile = new File(new File(ctx.idmlDir, "Stories"), "Story_" + hexId + ".xml");
            if (!storyFile.exists()) return null;
            try {
                long parseStart = System.nanoTime();
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                org.w3c.dom.Document xmlDoc = db.parse(storyFile);
                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idmlDocument =
                        ctx.idmlDocumentSupplier != null ? ctx.idmlDocumentSupplier.get() : null;
                idmlStory = IDMLStoryParser.parseStory(xmlDoc, hexId, idmlDocument);
                ConversionTiming.addCounter("phase3.storyLoader.xmlParseNanos", System.nanoTime() - parseStart);
                ConversionTiming.addCounter("phase3.storyLoader.xmlParses", 1);
                if (ctx.idmlStoryCache != null) {
                    ctx.idmlStoryCache.put(storyId, idmlStory);
                    ctx.idmlStoryCache.put(sourceStoryId, idmlStory);
                    ctx.idmlStoryCache.put(hexId, idmlStory);
                }
            } catch (Exception e) {
                return null;
            }
        } else if (ctx.idmlStoryCache != null) {
            ctx.idmlStoryCache.putIfAbsent(storyId, idmlStory);
            ctx.idmlStoryCache.putIfAbsent(sourceStoryId, idmlStory);
            ctx.idmlStoryCache.putIfAbsent(hexId, idmlStory);
        }

        if (idmlStory == null || idmlStory.paragraphs() == null) return null;

        // resolved에서 단락 속성 보강용
        ResolvedStory resolvedStory = ctx.resolvedData.getStory(storyId);

        // cleanStyleName → alignment 캐시: 같은 스타일명이 수백 단락에 반복 적용될 때 중복 resolve 방지
        Map<String, String> styleAlignCache = new java.util.HashMap<>();

        List<ASTParagraph> paragraphs = new ArrayList<>();
        List<IDMLParagraph> idmlParas = idmlStory.paragraphs();
        for (int i = 0; i < idmlParas.size(); i++) {
            IDMLParagraph ip = idmlParas.get(i);
            ASTParagraph para = new ASTParagraph();
            ResolvedParagraph resolvedParagraph = (resolvedStory != null && i < resolvedStory.paragraphs().size())
                    ? resolvedStory.paragraphs().get(i)
                    : null;
            long paragraphSetupStart = System.nanoTime();

            // 칼럼 브레이크 (ACE 8)
            if (ip.columnBreakAfter()) {
                para.columnBreakAfter(true);
            }

            // 단락 스타일 (IDML)
            if (ip.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(ip.appliedParagraphStyle());
            }

            // 단락 속성(정렬/줄간격/간격/들여쓰기/탭): 셀 안/밖 공용 루틴 사용
            ParagraphPropertyResolver.apply(para, ip, resolvedParagraph, ctx, styleAlignCache);
            applyComposedLinePitchFallback(para, ctx, resolvedStory, i);

            // resolved 런 (스타일 상속 보강용)
            List<ResolvedRun> resolvedRuns = null;
            if (resolvedStory != null && i < resolvedStory.paragraphs().size()) {
                resolvedRuns = resolvedStory.paragraphs().get(i).runs();
            }

            // source ownership policy: ACE 7 (IndentToHere) 감지 — 첫 inline anchor 다음에 \u0007 (BEL) 또는
            // \u0008 (BS, IDML 변환값) 이 나오면 첫 anchor TF 폭만큼 paragraph leftMargin 적용.
            // (예: "1  가 같은 사건을..." 패턴에서 "가" 부터의 줄은 "1" 폭만큼 들여쓰기)
            if (resolvedRuns != null && resolvedRuns.size() >= 2 && para.leftMargin() == null) {
                ResolvedRun rA = resolvedRuns.get(0);
                ResolvedRun rB = resolvedRuns.get(1);
                if (rA.isInlineAnchor() && rA.anchoredObjectId() != null
                        && rB.text() != null
                        && (rB.text().indexOf('\u0007') >= 0 || rB.text().indexOf('\u0008') >= 0)) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame anchorTf
                            = ctx.resolvedData.getTextFrame(String.valueOf(rA.anchoredObjectId()));
                    if (anchorTf != null) {
                        double[] gbA = anchorTf.geometricBounds();
                        if (gbA != null && gbA.length >= 4) {
                            double anchorW = Math.abs(gbA[3] - gbA[1]);
                            if (anchorW > 0) {
                                para.leftMargin(CoordinateConverter.pointsToHwpunits(anchorW));
                            }
                        }
                    }
                }
            }
            ConversionTiming.addCounter("phase3.storyLoader.paragraphSetupNanos",
                    System.nanoTime() - paragraphSetupStart);

            // ParagraphStyle에서 FillColor/Tracking/FontFamily 미리 구해둠 (런에서 없을 때 사용)
            long styleContextStart = System.nanoTime();
            StoryConverter.StyleContext sc = styleContextFor(ctx, ip.appliedParagraphStyle());
            // tabStop이 있으면 \t 문자를 보존 (HwpxParagraphBuilder가 <hp:tab>으로 변환)
            sc.hasTabStops = para.hasTabStops();
            ConversionTiming.addCounter("phase3.storyLoader.styleContextNanos",
                    System.nanoTime() - styleContextStart);

            appendGeneratedParagraphPrefix(ctx, resolvedParagraph, para);
            buildParagraphContent(ctx, ip, resolvedParagraph, resolvedRuns, storyId, i, sc, para);

            paragraphs.add(para);
            ConversionTiming.addCounter("phase3.storyLoader.paragraphs", 1);
        }

        StoryConverter.removeDuplicateDoviraLeadingMarkers(ctx, storyId, paragraphs);
        return paragraphs;
    }

    /**
     * 테이블 셀 IDML 단락들을 셀 밖과 동일한 공용 루틴으로 AST 단락 빌드.
     * 단락 속성은 {@link ParagraphPropertyResolver}, 런은 {@link RunBuilder#createRunFromIDML}.
     * (셀은 보통 resolvedStory 매칭이 없어 ResolvedRun=null로 빌드하지만, 그래도
     * SPEC-012 IDML→스타일 우선순위와 수식 폰트 처리를 얻어 간소 경로보다 정확하다.)
     */

    /**
     * 단락 콘텐츠(런/인라인 객체/수식/GREP) 빌드 공용 루틴 — 셀 안/밖 동일 로직.
     * 표준(convertStoryFromIDML)과 셀(astParagraphsForCell)이 같은 인라인 앵커 처리를 쓰도록 추출.
     * resolvedParagraph/resolvedRuns는 셀에서 null, storyId는 인라인 앵커 검사용(셀: 소유 스토리 or null).
     */
    static void buildParagraphContent(ResolvedBuildContext ctx, IDMLParagraph ip,
            ResolvedParagraph resolvedParagraph, List<ResolvedRun> resolvedRuns,
            String storyId, int paraIndex, StoryConverter.StyleContext sc, ASTParagraph para) {
        boolean cellPath = storyId == null && resolvedParagraph == null && resolvedRuns == null;
        String perfPrefix = cellPath ? "phase3.storyLoader.cell" : "phase3.storyLoader.story";
        boolean hasIdmlInlineAnchors = false;
            // 런 변환: IDML CharacterRun → ASTTextRun + 수식 그룹화
            // resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 (불릿/특수문자 런 회피)
            ResolvedRun defaultRR = RunBuilder.findDefaultResolvedRun(ctx, resolvedRuns);
            int resolvedRunIdx = 0;
            boolean resolvedStyleVaries = resolvedRuns != null
                    && resolvedRuns.size() > 1
                    && RunBuilder.hasStyleVariation(ctx, resolvedRuns);

            // 전처리: 한국어+수식마커 혼합 런 분리 + 원문자 변환
            long runPrepStart = System.nanoTime();
            List<IDMLCharacterRun> runs = ASTMathGrouper.splitMathKoreanMixedRuns(ip.characterRuns());
            ASTRunConverter.convertCircledNumberRuns(runs);
            addUnderlineBlankTabStop(ctx, storyId, paraIndex, para, runs);
            sc.hasTabStops = para.hasTabStops();
            ConversionTiming.addCounter("phase3.storyLoader.runPrepNanos",
                    System.nanoTime() - runPrepStart);

            // 수식 그룹화 상태
            List<IDMLCharacterRun> mathGroup = new ArrayList<>();
            List<IDMLCharacterRun> npMathGroup = new ArrayList<>();
            List<IDMLCharacterRun> ehMathGroup = new ArrayList<>();
            boolean firstTextRunAfterLeadingAnchor = false;
            if (!hasIdmlInlineAnchorRuns(runs)
                    && insertResolvedLeadingInlineAnchors(ctx, resolvedParagraph, resolvedRuns, para)) {
                firstTextRunAfterLeadingAnchor = !hasVisibleText(para);
            }

            boolean paraHasBTRuns = false;
            boolean paraHasNPStructuralRuns = false;
            boolean paraHasMathSymbols = false;
            long runLoopStart = System.nanoTime();
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
                applyCharacterStylePosition(ctx, run);

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
                    boolean nearLongWord = RunBuilder.containsLongLatinWord(ct, 3);
                    if (!nearLongWord) {
                        for (int d = 1; d <= 5 && !nearLongWord; d++) {
                            if (idx - d >= 0) nearLongWord = RunBuilder.containsLongLatinWord(runs.get(idx - d).content(), 3);
                            if (idx + d < runs.size()) nearLongWord = nearLongWord || RunBuilder.containsLongLatinWord(runs.get(idx + d).content(), 3);
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

                // ORC-only run: 인라인 프레임/그래픽 플레이스홀더(￼)만 있는 런은
                // 수식(EH/NP/BT) 분류 대상에서 제외 — 실제 텍스트 내용이 없으므로 수식일 수 없음.
                // (예: "약물" 이 포함된 배지 CharacterStyle 때문에 isEHFontStyle이 true를 반환하는 오분류 방지)
                String _runTxt = run.content();
                boolean _orcOnly = _runTxt != null && !_runTxt.isEmpty()
                        && _runTxt.replace("￼", "").isEmpty();
                boolean formulaClusterRun =
                        ASTMathGrouper.isFormulaEquationClusterRun(run, runs, idx);

                if (_orcOnly && formulaClusterRun
                        && MathProcessor.isFormulaAnswerPlaceholderRun(ctx, run)) {
                    MathProcessor.flushMathGroups(ctx, null, npMathGroup, ehMathGroup, para);
                    mathGroup.add(ASTMathGrouper.formulaAnswerBoxRun(run));
                    continue;
                }

                if (isStandaloneBtArrowGlyphRun(run) && !formulaClusterRun) {
                    MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, ehMathGroup, para);
                    para.addItem(createStandaloneArrowRun(ctx, run, defaultRR, sc));
                    continue;
                }

                // EH 수식 그룹 진입
                boolean enterEH = !_orcOnly && (run.isEHFont()
                        || EHFontGlyphMap.containsEHEncodedChars(run.content())
                        || EHFontGlyphMap.containsEHFractionPattern(run.content())
                        || (!ehMathGroup.isEmpty() && ASTMathGrouper.isEHMathBridgeRun(run, runs, idx))
                        || (!ehMathGroup.isEmpty() && MathProcessor.isEHSqrtContent(run, ehMathGroup)));

                // NP 수식 그룹 진입
                boolean enterNP = false;
                if (!enterEH && !_orcOnly) {
                    enterNP = run.isNPFont()
                            || (!npMathGroup.isEmpty() && ASTMathGrouper.isNPMathBridgeRun(run, runs, idx))
                            || (npMathGroup.isEmpty() && ASTMathGrouper.isPreNPMathRun(run, runs, idx))
                            || (paraHasNPStructuralRuns && !run.isNPFont() && !run.isBTFont()
                                && !run.isEHFont()
                                && ASTMathGrouper.isStandaloneMathRun(run));
                }

                // BT 수식 그룹 진입
                boolean enterBT = false;
                if (!enterEH && !enterNP && !_orcOnly) {
                    enterBT = (run.isBTFont()
                                && !ASTMathGrouper.isBTRunWithOnlyKorean(run.content()))
                            || (!mathGroup.isEmpty() && ASTMathGrouper.isMathBridgeRun(run, runs, idx))
                            || (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content()))
                            || formulaClusterRun;
                }

                if (enterEH) {
                    MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, null, para);
                    ehMathGroup.add(run);
                } else if (enterNP) {
                    MathProcessor.flushMathGroups(ctx, mathGroup, null, ehMathGroup, para);
                    npMathGroup.add(run);
                } else if (enterBT) {
                    MathProcessor.flushMathGroups(ctx, null, npMathGroup, ehMathGroup, para);
                    mathGroup.add(run);
                } else {
                    // 비수식 런: 열린 그룹 모두 flush
                    MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, ehMathGroup, para);

                    // 일반 런 변환 (U+FFFC 인라인 객체 포함)
                    String text = run.content();
                    if (text == null || text.isEmpty()) continue;

                    if (text.contains("\uFFFC")) {
                        hasIdmlInlineAnchors = true;
                        long inlineBranchStart = System.nanoTime();
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
                            if (firstTextRunAfterLeadingAnchor && !partText.isEmpty()) {
                                partText = StoryConverter.stripLeadingAnchorLayoutSpaces(partText);
                                firstTextRunAfterLeadingAnchor = false;
                            }
                            if (pi < parts.length - 1 && partText.endsWith("  ")) {
                                partText = partText.replaceAll("\\s+$", " "); // 후행 다중 공백 → 단일 공백
                            }
                            partText = normalizeLeadingTabAfterLeadingInlineObjects(para, partText);
                            if (!partText.isEmpty()) {
                                // resolved 런 스타일 차이가 있으면 분할 시도
                                boolean partSplit = false;
                                if (resolvedStyleVaries) {
                                    partSplit = RunBuilder.splitIdmlRunByResolvedRuns(ctx, run, partText, resolvedRuns, resolvedRunIdx,
                                            para, sc);
                                }
                                if (!partSplit) {
                                    ResolvedRun matchedRR = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, partText);
                                    if (matchedRR != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                                    long createStart = System.nanoTime();
                                    ASTTextRun tr = RunBuilder.createRunFromIDML(ctx, run, partText, matchedRR != null ? matchedRR : defaultRR, sc);
                                    ConversionTiming.addCounter(perfPrefix + ".createRunNanos",
                                            System.nanoTime() - createStart);
                                    if (!RunBuilder.splitBulletRun(ctx, tr, para)) {
                                        long splitLatinStart = System.nanoTime();
                                        RunBuilder.splitLatinVarsInMixedText(ctx, tr, para);
                                        ConversionTiming.addCounter(perfPrefix + ".splitLatinVarsNanos",
                                                System.nanoTime() - splitLatinStart);
                                    }
                                }
                            }
                            if (pi < parts.length - 1 && anchorIdx < inlineIds.size()) {
                                String inlineHexId = inlineIds.get(anchorIdx);
                                try {
                                    int domId = Integer.parseInt(inlineHexId.substring(1), 16);
                                    if (isDoviraSubunitMarker(resolvedParagraph, ip, domId)
                                            && DoviraSubunitMarkerPolicy.isDuplicateMarkerStory(ctx.resolvedData, storyId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    String nextPartText = (pi + 1 < parts.length) ? parts[pi + 1] : null;
                                    List<ASTInlineItem> plannedItems =
                                            InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, domId,
                                                    partText, nextPartText);
                                    if (plannedItems != null) {
                                        InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, domId, para);
                                        for (ASTInlineItem item : plannedItems) para.addItem(item);
                                        anchorIdx++;
                                        continue;
                                    }
                                    if (InlineFrameHandler.hasOwnershipPlanForAnchorBundle(ctx, domId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    List<ASTInlineObject> plannedAnchorTextShellFragments =
                                            InlineFrameHandler.loadPlannedInlineTextShellFragmentsForAnchor(ctx, domId);
                                    if (plannedAnchorTextShellFragments != null && !plannedAnchorTextShellFragments.isEmpty()) {
                                        for (ASTInlineObject fragment : plannedAnchorTextShellFragments) para.addItem(fragment);
                                        anchorIdx++;
                                        continue;
                                    }
                                    ASTInlineObject plannedAnchorTextShell =
                                            InlineFrameHandler.loadPlannedInlineTextShellForAnchor(ctx, domId);
                                    if (plannedAnchorTextShell != null) {
                                        para.addItem(plannedAnchorTextShell);
                                        anchorIdx++;
                                        continue;
                                    }
                                    ASTInlineObject groupShell =
                                            InlineFrameHandler.tryInlineGroupShellWithEditableChild(ctx, domId);
                                    if (groupShell != null) {
                                        para.addItem(groupShell);
                                        anchorIdx++;
                                        continue;
                                    }
                                    if (InlineFrameHandler.hasTextBlockPlacedDescendant(ctx, domId)
                                            || InlineFrameHandler.hasPlannedFloatingHwpxTextDescendant(ctx, domId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    // 커스텀 위치 앵커가 부모 범위 밖이면 인라인 흐름에는 넣지 않는다.
                                    if (!InlineFrameHandler.shouldKeepAnchoredInlineByOwnershipPlan(ctx, domId)
                                            && storyId != null
                                            && InlineFrameHandler.isAnchoredOutsideParentByTextFrame(ctx, domId, storyId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    // source ownership policy: Group 앵커가 다수의 박스(예: 자모 배지 ㅍㅎㅂㅅ) 면 각 자식 TF 를
                                    // 박스 스타일 inline TextFrame 으로 개별 분해 → 검색 가능 + 시각 박스 보존.
                                    java.util.List<ASTInlineObject> boxList =
                                            InlineFrameHandler.tryInlineGroupAsBoxList(ctx, domId);
                                    if (boxList != null && !boxList.isEmpty()) {
                                        for (ASTInlineObject box : boxList) para.addItem(box);
                                    } else {
                                        ASTInlineObject shapeShell =
                                                InlineFrameHandler.tryInlineShapeWithEditableChildAsShell(ctx, domId);
                                        if (shapeShell != null) {
                                            para.addItem(shapeShell);
                                            anchorIdx++;
                                            continue;
                                        }
                                        List<ASTInlineObject> plannedAnchorTextShellFragments2 =
                                                InlineFrameHandler.loadPlannedInlineTextShellFragmentsForAnchor(ctx, domId);
                                        if (plannedAnchorTextShellFragments2 != null && !plannedAnchorTextShellFragments2.isEmpty()) {
                                            for (ASTInlineObject fragment : plannedAnchorTextShellFragments2) para.addItem(fragment);
                                            anchorIdx++;
                                            continue;
                                        }
                                        ASTInlineObject plannedAnchorTextShell2 =
                                                InlineFrameHandler.loadPlannedInlineTextShellForAnchor(ctx, domId);
                                        if (plannedAnchorTextShell2 != null) {
                                            para.addItem(plannedAnchorTextShell2);
                                            anchorIdx++;
                                            continue;
                                        }
                                        if (InlineFrameHandler.hasDirectDropOnlyInlinePlanForAnchor(ctx, domId)) {
                                            anchorIdx++;
                                            continue;
                                        }
                                        // 분수 구조 인라인 TextFrame(2단락) → 수식으로 변환
                                        ASTEquation fracEq = InlineFrameHandler.tryInlineFractionAsEquation(ctx, domId);
                                        if (fracEq != null) {
                                            para.addItem(fracEq);
                                        } else {
                                            if (InlineFrameHandler.isSimpleButtonLabelAnchor(ctx, domId)) {
                                                ASTInlineObject inlineObj =
                                                        SimpleButtonLabelInlineFactory.create(ctx, domId);
                                                if (inlineObj != null) {
                                                    para.addItem(inlineObj);
                                                    anchorIdx++;
                                                    continue;
                                                }
                                            }
                                            // 배경 도형 + 단일 짧은 텍스트프레임 (예: 페이지 39 "가" / "나" 캡슐 배지)
                                            // → INLINE_TEXT_FRAME (한 몸 + 검색 가능)
                                            ASTInlineObject singleBadge = InlineFrameHandler.tryInlineGroupAsSingleBadge(ctx, domId);
                                            if (singleBadge != null) {
                                                para.addItem(singleBadge);
                                                anchorIdx++;
                                                continue;
                                            }
                                            ASTInlineObject plannedTextShell =
                                                    InlineFrameHandler.loadPlannedInlineTextShellForTextFrame(ctx, domId);
                                            if (plannedTextShell != null) {
                                                para.addItem(plannedTextShell);
                                                anchorIdx++;
                                                continue;
                                            }
                                            // 하위 인라인 TF 안에 다시 ORC 앵커가 있는 경우
                                            // 텍스트 런으로 평탄화하지 말고 배지/박스 + 텍스트 순서를 보존한다.
                                            ASTInlineObject inlineTableFrame =
                                                    InlineFrameHandler.tryInlineTextFrameWithTables(ctx, domId);
                                            if (inlineTableFrame != null) {
                                                para.addItem(inlineTableFrame);
                                                anchorIdx++;
                                                continue;
                                            }

                                            List<ASTInlineItem> nestedItems =
                                                    InlineFrameHandler.tryInlineTextFrameAsItems(ctx, domId,
                                                            partText, nextPartText);
                                            if (nestedItems != null && !nestedItems.isEmpty()) {
                                                for (ASTInlineItem item : nestedItems) para.addItem(item);
                                                anchorIdx++;
                                                continue;
                                            }
                                            // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환
                                            ASTTextRun textRun = InlineFrameHandler.tryInlineTextFrameAsRun(ctx, domId,
                                                    partText, nextPartText);
                                            if (textRun != null) {
                                                if (!InlineFrameHandler.isInlineVocabularyMarker(ctx, domId, partText, nextPartText)) {
                                                    maybeInsertDecorativeLeaderTab(ctx, ip, run, inlineHexId, partText, para);
                                                }
                                                para.addItem(textRun);
                                            } else {
                                                // source ownership policy: 빈 inline TextFrame 이지만 fillColor 가 있으면 데코 박스 (예: 본문 빈칸 강조 박스)
                                                ASTInlineObject emptyBox = InlineFrameHandler.tryInlineEmptyFilledBoxAsFrame(ctx, domId);
                                                if (emptyBox != null) {
                                                    para.addItem(emptyBox);
                                                } else {
                                                    if (InlineFrameHandler.isInlineTextShellCompanionForEditableText(ctx, domId)) {
                                                        anchorIdx++;
                                                        continue;
                                                    }
                                                    ASTInlineObject inlineObj = InlineFrameHandler.loadInlineObject(ctx, domId);
                                                    if (inlineObj != null) {
                                                        para.addItem(inlineObj);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) { /* skip */ }
                                if (!hasVisibleText(para)) {
                                    firstTextRunAfterLeadingAnchor = true;
                                }
                                anchorIdx++;
                            }
                        }
                        ConversionTiming.addCounter(perfPrefix + ".inlineAnchorBranchNanos",
                                System.nanoTime() - inlineBranchStart);
                    } else {
                        if (firstTextRunAfterLeadingAnchor) {
                            text = StoryConverter.stripLeadingAnchorLayoutSpaces(text);
                            firstTextRunAfterLeadingAnchor = false;
                        }
                        text = normalizeLeadingTabAfterLeadingInlineObjects(para, text);
                        // GREP 스타일 분할: IDML 단일 런이 resolved에서 여러 런(다른 색상/폰트)으로 분할된 경우
                        // resolved 런 경계에서 IDML 런을 분할하여 각각의 색상을 적용
                        boolean splitByResolved = false;
                        if (resolvedStyleVaries) {
                            splitByResolved = RunBuilder.splitIdmlRunByResolvedRuns(ctx, run, text, resolvedRuns, resolvedRunIdx,
                                    para, sc);
                        }
                        if (!splitByResolved) {
                            ResolvedRun matchedRR2 = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, text);
                            if (matchedRR2 != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                            long createStart = System.nanoTime();
                            ASTTextRun tr = RunBuilder.createRunFromIDML(ctx, run, text, matchedRR2 != null ? matchedRR2 : defaultRR, sc);
                            ConversionTiming.addCounter(perfPrefix + ".createRunNanos",
                                    System.nanoTime() - createStart);
                            // ;...; 분수 GREP 패턴이 포함된 텍스트 → 분수 수식으로 분리
                            if (!RunBuilder.splitBulletRun(ctx, tr, para)) {
                                if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                                    MathProcessor.splitFractionPatternInText(ctx, text, tr, para);
                                } else {
                                    long splitLatinStart = System.nanoTime();
                                    RunBuilder.splitLatinVarsInMixedText(ctx, tr, para);
                                    ConversionTiming.addCounter(perfPrefix + ".splitLatinVarsNanos",
                                            System.nanoTime() - splitLatinStart);
                                }
                            }
                        }
                    }
                }
            }
            ConversionTiming.addCounter("phase3.storyLoader.runLoopNanos",
                    System.nanoTime() - runLoopStart);
            ConversionTiming.addCounter(perfPrefix + ".runLoopNanos",
                    System.nanoTime() - runLoopStart);
            // 단락 끝 잔여 수식 그룹 flush
            MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, ehMathGroup, para);
            applyTrailingPageNumberLeader(ctx, ip, para);

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
            RunPostProcessor.splitOverlineRuns(para);

            // 이탤릭 EH상부자 런 → 인라인 수식(ASTEquation)으로 변환
            // 한컴 수식 에디터가 변수=이탤릭, 괄호/연산자=정체를 자동 처리
            RunPostProcessor.convertItalicRunsToEquations(para);

            // 불릿 단락이면 불릿 이후 런 색상을 검정으로 리셋
            RunBuilder.resetBulletParagraphColors(ctx, para);

            // 인라인 객체 boundsX 기반 재정렬: FFFC 앵커 순서가 없는 경우에만
            // (IDML 경로에서 FFFC 분할 후 앵커 순서로 삽입된 경우 재정렬하면 순서 깨짐)
            if (!hasIdmlInlineAnchors) {
                ASTTableConverter.reorderInlineObjectsByBoundsX(para);
            }
    }

    private static String normalizeLeadingTabAfterLeadingInlineObjects(ASTParagraph para, String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '\t') return text;
        if (!paragraphContainsOnlyInlineObjectsSoFar(para)) return text;
        int offset = 0;
        while (offset < text.length() && text.charAt(offset) == '\t') {
            offset++;
        }
        return " " + text.substring(offset);
    }

    private static boolean paragraphContainsOnlyInlineObjectsSoFar(ASTParagraph para) {
        if (para == null || para.items() == null || para.items().isEmpty()) return false;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTInlineObject)) return false;
        }
        return true;
    }

    public static List<ASTParagraph> astParagraphsForCell(ResolvedBuildContext ctx,
                                                          kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        return astParagraphsForCell(ctx, null, idmlCell, null);
    }

    /**
     * 셀 단락을 셀 밖과 동일한 공용 루틴({@link #buildParagraphContent})으로 빌드.
     * resolvedParagraph/resolvedRuns는 null(셀은 보통 resolvedStory 매칭이 없음)이며,
     * cellStoryId가 null이면 story 단위 검사(중복 마커/부모밖 앵커)는 건너뛴다(셀 내부에는 무의미).
     * 이로써 셀 단락의 FFFC 인라인 앵커가 표준과 동일하게 ASTInlineObject(배지/라벨/inline drawText)로 임베드된다.
     */
    public static List<ASTParagraph> astParagraphsForCell(ResolvedBuildContext ctx,
                                                          kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell,
                                                          String cellStoryId) {
        return astParagraphsForCell(ctx, null, idmlCell, cellStoryId);
    }

    public static List<ASTParagraph> astParagraphsForCell(ResolvedBuildContext ctx,
                                                          IDMLTable idmlTable,
                                                          kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell,
                                                          String cellStoryId) {
        List<ASTParagraph> result = new ArrayList<>();
        if (ctx == null || idmlCell == null || idmlCell.paragraphs() == null) return result;
        if (isFlattenedOwnedTextShellStoryCell(ctx, idmlCell)
                && !hasPlannedInlineAtomicCellContent(ctx, idmlCell)) {
            return result;
        }
        if (hasTextFrameStoryOwnedByPlacedTextFrame(ctx, idmlCell)
                && !hasPlannedInlineAtomicCellContent(ctx, idmlCell)
                && !hasDirectVisibleCellText(idmlCell)) {
            return result;
        }
        ResolvedTable.Cell resolvedCell = findResolvedCell(ctx, idmlTable, idmlCell);
        if ((idmlCell.paragraphs() == null || idmlCell.paragraphs().isEmpty())
                && resolvedCell != null
                && hasDirectCellTextOrInlineObject(idmlCell)) {
            List<ASTParagraph> resolvedCellParagraphs =
                    astParagraphsFromResolvedCell(ctx, idmlTable, idmlCell);
            if (resolvedCellParagraphs != null && !resolvedCellParagraphs.isEmpty()) {
                return resolvedCellParagraphs;
            }
        }
        int paraIndex = 0;
        for (IDMLParagraph ip : idmlCell.paragraphs()) {
            if (ip == null) { paraIndex++; continue; }
            ASTParagraph para = new ASTParagraph();
            if (ip.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(ip.appliedParagraphStyle());
            }
            ParagraphPropertyResolver.apply(para, ip, null, ctx, null);
            StoryConverter.StyleContext sc = styleContextFor(ctx, ip.appliedParagraphStyle());
            sc.hasTabStops = para.hasTabStops();
            buildParagraphContent(ctx, ip, null, null, cellStoryId, paraIndex, sc, para);
            MathProcessor.convertMathRunsInParagraph(ctx, para);
            result.add(para);
            ConversionTiming.addCounter("phase3.storyLoader.cell.paragraphs", 1);
            paraIndex++;
        }
        applyResolvedCellInlineGraphicRescueAnchors(ctx, idmlCell, resolvedCell, result);
        for (ASTParagraph para : result) {
            MathProcessor.convertMathRunsInParagraph(ctx, para);
            recordCellInlineEmbeddedIds(ctx, para);
        }
        return result;
    }

    /**
     * resolved.json의 table cell paragraphs는 IDML Story XML에서 빠지는 inline_anchor를
     * 보존한다. 셀 안 inline 여부도 Stage 1 ObjectPlan이 이미 결정했으므로, 여기서는
     * resolved run 순서를 AST로 실행만 한다.
     */
    private static List<ASTParagraph> astParagraphsFromResolvedCell(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        List<ASTParagraph> result = new ArrayList<>();
        ResolvedTable.Cell resolvedCell = findResolvedCell(ctx, idmlTable, idmlCell);
        if (resolvedCell == null
                || resolvedCell.paragraphs() == null
                || resolvedCell.paragraphs().isEmpty()
                || !resolvedCellHasInlineAnchors(resolvedCell)) {
            return result;
        }
        boolean includeResolvedText = hasDirectVisibleCellText(idmlCell);

        int paraIndex = 0;
        for (ResolvedParagraph resolvedParagraph : resolvedCell.paragraphs()) {
            if (resolvedParagraph == null) { paraIndex++; continue; }
            IDMLParagraph idmlParagraph = (idmlCell.paragraphs() != null
                    && paraIndex < idmlCell.paragraphs().size())
                    ? idmlCell.paragraphs().get(paraIndex)
                    : null;
            ASTParagraph para = new ASTParagraph();
            if (idmlParagraph != null) {
                if (idmlParagraph.appliedParagraphStyle() != null) {
                    para.paragraphStyleRef(idmlParagraph.appliedParagraphStyle());
                }
                ParagraphPropertyResolver.apply(para, idmlParagraph, resolvedParagraph, ctx, null);
            } else {
                applyResolvedParagraphPropertiesOnly(para, resolvedParagraph);
            }
            appendResolvedRunsInOrder(ctx, resolvedParagraph, para, includeResolvedText);
            MathProcessor.convertMathRunsInParagraph(ctx, para);
            RunPostProcessor.splitOverlineRuns(para);
            RunPostProcessor.convertItalicRunsToEquations(para);
            RunBuilder.resetBulletParagraphColors(ctx, para);
            recordCellInlineEmbeddedIds(ctx, para);
            if (para.items() != null && !para.items().isEmpty()) {
                result.add(para);
            }
            paraIndex++;
        }
        return result;
    }

    static void applyComposedLinePitchFallback(ASTParagraph para,
                                               ResolvedBuildContext ctx,
                                               ResolvedStory resolvedStory,
                                               int paragraphIndex) {
        if (para == null || para.lineSpacing() != null) return;
        if (ctx == null || ctx.resolvedData == null || resolvedStory == null || resolvedStory.id() == null) return;

        List<Double> tops = new ArrayList<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.getTextFramesForStory(resolvedStory.id())) {
            if (tf == null || tf.composedLines() == null) continue;
            for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
                if (line == null || line.paraIndex() != paragraphIndex) continue;
                double[] b = line.bounds();
                if (b == null || b.length < 4) continue;
                tops.add(b[0]);
            }
        }
        if (tops.size() < 2) return;
        tops.sort(Double::compareTo);

        List<Double> deltas = new ArrayList<>();
        Double prev = null;
        for (Double top : tops) {
            if (top == null) continue;
            if (prev != null) {
                double delta = top - prev;
                if (delta >= 4.0 && delta <= 50.0) {
                    deltas.add(delta);
                }
            }
            prev = top;
        }
        if (deltas.isEmpty()) return;
        deltas.sort(Double::compareTo);
        double pitch = deltas.get(deltas.size() / 2);
        para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(pitch));
        para.lineSpacingType("fixed");
    }

    private static ResolvedTable.Cell findResolvedCell(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null) return null;
        ResolvedTable resolvedTable = idmlTable != null
                ? ctx.resolvedData.getTableByIdOrSourceId(idmlTable.selfId())
                : null;
        if (resolvedTable == null && idmlCell.selfId() != null) {
            resolvedTable = ctx.resolvedData.getTableByIdOrSourceId(idmlCell.selfId());
        }
        if (resolvedTable == null) return null;
        return resolvedTable.cellAt(idmlCell.rowIndex(), idmlCell.columnIndex());
    }

    private static boolean resolvedCellHasInlineAnchors(ResolvedTable.Cell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run != null && run.isInlineAnchor()) return true;
            }
        }
        return false;
    }

    private static void appendResolvedRunsInOrder(
            ResolvedBuildContext ctx,
            ResolvedParagraph resolvedParagraph,
            ASTParagraph para,
            boolean includeTextRuns) {
        if (ctx == null || resolvedParagraph == null || resolvedParagraph.runs() == null || para == null) return;
        if (includeTextRuns) {
            appendGeneratedParagraphPrefix(ctx, resolvedParagraph, para);
        }
        ResolvedTextFlowAstConverter.Options options = ResolvedTextFlowAstConverter.options()
                .colorResolver(color -> ctx.resolvedData != null ? ctx.resolvedData.resolveColorHex(color) : color)
                .truncateAtParagraphBreak(true);
        List<ResolvedRun> runs = resolvedParagraph.runs();
        for (int i = 0; i < runs.size(); i++) {
            ResolvedRun run = runs.get(i);
            if (run == null) continue;
            if (run.isInlineAnchor()) {
                Integer anchoredId = run.anchoredObjectId();
                if (anchoredId == null || anchoredId <= 0) continue;
                if (!isResolvedCellInlineGraphicRescueAnchor(ctx, anchoredId)) {
                    continue;
                }
                String previousText = nearestResolvedText(runs, i - 1, -1);
                String nextText = nearestResolvedText(runs, i + 1, 1);
                List<ASTInlineItem> plannedItems =
                        InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, anchoredId, previousText, nextText);
                if (plannedItems != null) {
                    InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, anchoredId, para);
                    appendInlineItemsKeepingObjectsInline(para, plannedItems);
                    continue;
                }
                ASTInlineObject inline = InlineFrameHandler.loadPlannedInlineTextShellForAnchor(ctx, anchoredId);
                if (inline == null) inline = InlineFrameHandler.loadInlineObject(ctx, anchoredId);
                if (inline == null) continue;
                inline.keepInline(true);
                para.addItem(inline);
                continue;
            }

            if (!includeTextRuns) continue;
            String text = run.text();
            if (text == null) continue;
            for (ASTTextRun textRun : ResolvedTextFlowAstConverter.convertRunText(text, run, para, options)) {
                para.addItem(textRun);
            }
            if (text.indexOf('\r') >= 0) break;
        }
    }

    private static void appendGeneratedParagraphPrefix(
            ResolvedBuildContext ctx,
            ResolvedParagraph resolvedParagraph,
            ASTParagraph para) {
        if (ctx == null || resolvedParagraph == null || para == null) return;
        String prefix = ResolvedTextFlowAstConverter.generatedPrefixToInsert(resolvedParagraph);
        if (prefix == null || prefix.trim().isEmpty()) return;
        ResolvedRun styleRun = ResolvedTextFlowAstConverter.firstVisibleResolvedRun(resolvedParagraph);
        if (styleRun == null) return;
        ResolvedTextFlowAstConverter.Options options = ResolvedTextFlowAstConverter.options()
                .colorResolver(color -> ctx.resolvedData != null ? ctx.resolvedData.resolveColorHex(color) : color)
                .truncateAtParagraphBreak(false);
        for (ASTTextRun run : ResolvedTextFlowAstConverter.convertRunText(prefix, styleRun, para, options)) {
            para.addItem(run);
        }
    }

    private static boolean isStandaloneBtArrowGlyphRun(IDMLCharacterRun run) {
        if (run == null) return false;
        String style = run.appliedCharacterStyle();
        String text = run.content();
        if (style == null || text == null) return false;
        String normalizedStyle = style.toLowerCase(java.util.Locale.ROOT)
                .replace("%3a", ":")
                .replace("%ed%99%94%ec%82%b4%ed%91%9c", "화살표");
        String cleaned = text.trim();
        return normalizedStyle.contains("화살표")
                && ("@C".equals(cleaned) || "@c".equals(cleaned)
                    || "?C".equals(cleaned) || "?c".equals(cleaned));
    }

    private static ASTTextRun createStandaloneArrowRun(
            ResolvedBuildContext ctx,
            IDMLCharacterRun source,
            ResolvedRun resolvedRun,
            StoryConverter.StyleContext sc) {
        ASTTextRun arrow = RunBuilder.createRunFromIDML(ctx, source, "\u2192", resolvedRun, sc);
        arrow.fontFamily(null);
        arrow.fontStyle(null);
        arrow.characterStyleRef(null);
        arrow.grepMathFont(false);
        arrow.subscript(false);
        arrow.superscript(false);
        return arrow;
    }

    private static boolean isResolvedCellInlineGraphicRescueAnchor(
            ResolvedBuildContext ctx,
            int anchoredId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredId <= 0) return false;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.domId != anchoredId) continue;
            if (plan.placement != Placement.INLINE) return false;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                    && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
                return false;
            }
            return plan.sourceObjectIds != null && plan.sourceObjectIds.length > 0;
        }
        return false;
    }

    private static void applyResolvedCellInlineGraphicRescueAnchors(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell,
            ResolvedTable.Cell resolvedCell,
            List<ASTParagraph> paragraphs) {
        if (ctx == null || resolvedCell == null || resolvedCell.paragraphs() == null
                || paragraphs == null || paragraphs.isEmpty()) {
            return;
        }

        List<ResolvedParagraph> resolvedParagraphs = resolvedCell.paragraphs();
        for (int paraIndex = 0; paraIndex < resolvedParagraphs.size(); paraIndex++) {
            ResolvedParagraph resolvedParagraph = resolvedParagraphs.get(paraIndex);
            if (resolvedParagraph == null || resolvedParagraph.runs() == null) continue;
            List<ResolvedRun> runs = resolvedParagraph.runs();
            for (int i = 0; i < runs.size(); i++) {
                ResolvedRun run = runs.get(i);
                if (run == null || !run.isInlineAnchor()) continue;
                Integer anchoredId = run.anchoredObjectId();
                if (anchoredId == null || anchoredId <= 0) continue;
                if (!cellContainsInlineAnchor(idmlCell, anchoredId)) continue;
                if (!isResolvedCellInlineGraphicRescueAnchor(ctx, anchoredId)
                        && !isResolvedCellInlineGraphicPrefixMarkerAnchor(ctx, anchoredId)) {
                    continue;
                }
                int targetIndex = isResolvedCellInlineGraphicPrefixMarkerAnchor(ctx, anchoredId)
                        ? nearestParagraphIndexByInlineVisualY(ctx, resolvedCell, anchoredId, paraIndex, paragraphs.size())
                        : paraIndex;
                if (targetIndex < 0 || targetIndex >= paragraphs.size()) continue;

                ASTParagraph target = paragraphs.get(targetIndex);
                if (paragraphAlreadyContainsInlineObject(target, anchoredId)) continue;
                String previousText = nearestResolvedText(runs, i - 1, -1);
                String nextText = nearestResolvedText(runs, i + 1, 1);
                List<ASTInlineItem> plannedItems =
                        InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, anchoredId, previousText, nextText);
                if (plannedItems == null || plannedItems.isEmpty()) continue;
                InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, anchoredId, target);
                for (ASTInlineItem item : plannedItems) {
                    if (item instanceof ASTInlineObject) {
                        ((ASTInlineObject) item).keepInline(true);
                    }
                }
                int insertAt = leadingWhitespaceItemCount(target);
                target.items().addAll(insertAt, plannedItems);
            }
        }
    }

    private static boolean cellContainsInlineAnchor(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell,
            int anchorId) {
        if (idmlCell == null || idmlCell.paragraphs() == null || anchorId <= 0) return false;
        for (IDMLParagraph paragraph : idmlCell.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null) continue;
                if (containsInlineAnchorDomId(run, anchorId)) return true;
                if (containsInlineGraphicDomId(run.inlineGraphics(), anchorId)) return true;
                if (containsInlineFrameDomId(run.inlineFrames(), anchorId)) return true;
            }
        }
        return false;
    }

    private static boolean containsInlineAnchorDomId(IDMLCharacterRun run, int anchorId) {
        if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) return false;
        for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
            if (anchor == null) continue;
            if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
                List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame> frames = run.inlineFrames();
                int index = anchor.index();
                if (frames != null && index >= 0 && index < frames.size()
                        && idMatches(frames.get(index).selfId(), anchorId)) {
                    return true;
                }
            } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
                List<IDMLCharacterRun.InlineGraphic> graphics = run.inlineGraphics();
                int index = anchor.index();
                if (graphics != null && index >= 0 && index < graphics.size()
                        && inlineGraphicContainsDomId(graphics.get(index), anchorId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsInlineGraphicDomId(
            List<IDMLCharacterRun.InlineGraphic> graphics,
            int anchorId) {
        if (graphics == null || graphics.isEmpty()) return false;
        for (IDMLCharacterRun.InlineGraphic graphic : graphics) {
            if (inlineGraphicContainsDomId(graphic, anchorId)) return true;
        }
        return false;
    }

    private static boolean inlineGraphicContainsDomId(
            IDMLCharacterRun.InlineGraphic graphic,
            int anchorId) {
        if (graphic == null) return false;
        if (idMatches(graphic.selfId(), anchorId)) return true;
        if (containsInlineGraphicDomId(graphic.childGraphics(), anchorId)) return true;
        return containsInlineFrameDomId(graphic.childTextFrames(), anchorId);
    }

    private static boolean containsInlineFrameDomId(
            List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame> frames,
            int anchorId) {
        if (frames == null || frames.isEmpty()) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame frame : frames) {
            if (frame == null) continue;
            if (idMatches(frame.selfId(), anchorId)) return true;
        }
        return false;
    }

    private static boolean idMatches(String sourceId, int domId) {
        if (sourceId == null || sourceId.isEmpty() || domId <= 0) return false;
        try {
            if (sourceId.charAt(0) == 'u' || sourceId.charAt(0) == 'U') {
                return Integer.parseInt(sourceId.substring(1), 16) == domId;
            }
            return Integer.parseInt(sourceId) == domId;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isResolvedCellInlineGraphicPrefixMarkerAnchor(
            ResolvedBuildContext ctx,
            int anchoredId) {
        ObjectPlan plan = inlineGraphicPlanFor(ctx, anchoredId);
        if (plan == null) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        String reason = plan.reason != null ? plan.reason : "";
        return "inline_graphic_only".equals(reason)
                || "paper_inline_anchor_uses_page_material_slot".equals(reason);
    }

    private static ObjectPlan inlineGraphicPlanFor(ResolvedBuildContext ctx, int anchoredId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredId <= 0) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.domId != anchoredId) continue;
            if (plan.placement != Placement.INLINE) return null;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                    && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return null;
            return plan;
        }
        return null;
    }

    private static int nearestParagraphIndexByInlineVisualY(
            ResolvedBuildContext ctx,
            ResolvedTable.Cell resolvedCell,
            int anchoredId,
            int fallbackIndex,
            int paragraphCount) {
        ObjectPlan targetPlan = inlineGraphicPlanFor(ctx, anchoredId);
        if (targetPlan == null || targetPlan.bounds == null || targetPlan.bounds.length < 4) {
            return clampParagraphIndex(fallbackIndex, paragraphCount);
        }
        double targetCenterY = (targetPlan.bounds[0] + targetPlan.bounds[2]) / 2.0;
        double bestDistance = Double.MAX_VALUE;
        int bestIndex = -1;
        List<ResolvedParagraph> resolvedParagraphs = resolvedCell != null ? resolvedCell.paragraphs() : null;
        if (resolvedParagraphs != null) {
            for (int p = 0; p < resolvedParagraphs.size() && p < paragraphCount; p++) {
                Double paraY = representativeInlineVisualCenterY(ctx, resolvedParagraphs.get(p), anchoredId);
                if (paraY == null) continue;
                double distance = Math.abs(paraY - targetCenterY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = p;
                }
            }
        }
        if (bestIndex >= 0) return bestIndex;
        return clampParagraphIndex(fallbackIndex, paragraphCount);
    }

    private static int clampParagraphIndex(int index, int paragraphCount) {
        if (paragraphCount <= 0) return -1;
        if (index < 0) return 0;
        if (index >= paragraphCount) return paragraphCount - 1;
        return index;
    }

    private static Double representativeInlineVisualCenterY(
            ResolvedBuildContext ctx,
            ResolvedParagraph paragraph,
            int excludeAnchoredId) {
        if (paragraph == null || paragraph.runs() == null) return null;
        double sum = 0.0;
        int count = 0;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || !run.isInlineAnchor() || run.anchoredObjectId() == null) continue;
            int anchoredId = run.anchoredObjectId();
            if (anchoredId == excludeAnchoredId) continue;
            ObjectPlan plan = inlinePlanWithBoundsFor(ctx, anchoredId);
            if (plan == null) continue;
            sum += (plan.bounds[0] + plan.bounds[2]) / 2.0;
            count++;
        }
        return count > 0 ? sum / count : null;
    }

    private static ObjectPlan inlinePlanWithBoundsFor(ResolvedBuildContext ctx, int anchoredId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredId <= 0) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.domId != anchoredId) continue;
            if (plan.placement != Placement.INLINE) return null;
            if (plan.bounds == null || plan.bounds.length < 4) return null;
            return plan;
        }
        return null;
    }

    private static boolean paragraphAlreadyContainsInlineObject(ASTParagraph para, int domId) {
        if (para == null || para.items() == null || domId <= 0) return false;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTInlineObject)) continue;
            Integer existing = sourceIdToDomId(((ASTInlineObject) item).sourceId());
            if (existing != null && existing == domId) return true;
        }
        return false;
    }

    private static int leadingWhitespaceItemCount(ASTParagraph para) {
        if (para == null || para.items() == null || para.items().isEmpty()) return 0;
        int count = 0;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) break;
            String text = ((ASTTextRun) item).text();
            if (text == null || !text.trim().isEmpty()) break;
            count++;
        }
        return count;
    }

    private static String nearestResolvedText(List<ResolvedRun> runs, int start, int step) {
        if (runs == null || step == 0) return null;
        for (int i = start; i >= 0 && i < runs.size(); i += step) {
            ResolvedRun run = runs.get(i);
            if (run == null || run.isInlineAnchor()) continue;
            String text = run.text();
            if (text == null || text.isEmpty()) continue;
            int crIdx = text.indexOf('\r');
            if (crIdx >= 0) text = text.substring(0, crIdx);
            if (!text.isEmpty()) return text;
        }
        return null;
    }

    private static void appendInlineItemsKeepingObjectsInline(
            ASTParagraph paragraph,
            List<ASTInlineItem> items) {
        if (paragraph == null || items == null) return;
        for (ASTInlineItem item : items) {
            if (item == null) continue;
            if (item instanceof ASTInlineObject) {
                ((ASTInlineObject) item).keepInline(true);
            }
            paragraph.addItem(item);
        }
    }

    private static void applyResolvedParagraphPropertiesOnly(
            ASTParagraph para,
            ResolvedParagraph resolvedParagraph) {
        if (para == null || resolvedParagraph == null) return;
        if (resolvedParagraph.styleName() != null) {
            para.paragraphStyleRef(resolvedParagraph.styleName());
        }
        if (resolvedParagraph.justification() != null) {
            para.alignment(resolvedParagraph.justification());
        }
        if (resolvedParagraph.autoLeading() != null && resolvedParagraph.autoLeading() > 0) {
            para.autoLeadingPercent((int) Math.round(resolvedParagraph.autoLeading()));
        }
        Double fixedLeading = resolvedParagraph.fixedLeading();
        if (fixedLeading != null && fixedLeading > 0) {
            para.lineSpacingType("fixed");
            para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
        }
        if (resolvedParagraph.spaceBefore() != null && resolvedParagraph.spaceBefore() > 0) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(resolvedParagraph.spaceBefore()));
        }
        if (resolvedParagraph.spaceAfter() != null && resolvedParagraph.spaceAfter() > 0) {
            para.spaceAfter(CoordinateConverter.pointsToHwpunits(resolvedParagraph.spaceAfter()));
        }
        if (resolvedParagraph.leftIndent() != null && resolvedParagraph.leftIndent() != 0) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(resolvedParagraph.leftIndent()));
        }
        if (resolvedParagraph.rightIndent() != null && resolvedParagraph.rightIndent() != 0) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(resolvedParagraph.rightIndent()));
        }
        if (resolvedParagraph.firstLineIndent() != null && resolvedParagraph.firstLineIndent() != 0) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(resolvedParagraph.firstLineIndent()));
        }
        if (resolvedParagraph.hasTabStops()) {
            double leftPt = resolvedParagraph.leftIndent() != null ? resolvedParagraph.leftIndent() : 0;
            for (ResolvedTabStop tabStop : resolvedParagraph.tabStops()) {
                if (tabStop == null || tabStop.position() == null || tabStop.position() <= 0) continue;
                double posPt = tabStop.position() - leftPt;
                if (posPt < 0) posPt = 0;
                para.addTabStop(new ASTTabStop(
                        CoordinateConverter.pointsToHwpunits(posPt),
                        ASTStoryConverter.mapTabAlignment(tabStop.alignment()),
                        tabStop.leader()));
            }
        }
    }

    /**
     * 셀 단락에 임베드된 inline 객체의 sourceId(=DOM id)를 ctx.cellInlineEmbeddedDomIds에 기록한다.
     * 같은 source bundle의 inline/floating visible 중복을 검증/실행 단계에서 차단하기 위한 실행 기록이다.
     */
    private static void recordCellInlineEmbeddedIds(ResolvedBuildContext ctx, ASTParagraph para) {
        if (ctx == null || para == null || para.items() == null) return;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTInlineObject)) continue;
            Integer domId = sourceIdToDomId(((ASTInlineObject) item).sourceId());
            if (domId != null) ctx.cellInlineEmbeddedDomIds.add(domId);
        }
    }

    /** "u439eb" / "child_u439eb" / "u439eb_sb" 등 sourceId에서 선행 hex DOM id를 파싱. */
    private static Integer sourceIdToDomId(String sourceId) {
        if (sourceId == null) return null;
        String s = sourceId;
        if (s.startsWith("child_")) s = s.substring(6);
        if (!s.startsWith("u")) return null;
        s = s.substring(1);
        int end = 0;
        while (end < s.length()) {
            char c = s.charAt(end);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) break;
            end++;
        }
        if (end == 0) return null;
        try {
            return Integer.parseInt(s.substring(0, end), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static StoryConverter.StyleContext styleContextFor(ResolvedBuildContext ctx, String styleRef) {
        String key = styleRef != null ? styleRef : "";
        ResolvedBuildContext.ParagraphStyleContext cached = ctx.paragraphStyleContextCache.get(key);
        if (cached == null) {
            cached = new ResolvedBuildContext.ParagraphStyleContext(
                    RunBuilder.getStyleFillColor(ctx, styleRef),
                    RunBuilder.getStyleTracking(ctx, styleRef),
                    RunBuilder.getStyleFontFamily(ctx, styleRef),
                    RunBuilder.getStyleFontSize(ctx, styleRef),
                    RunBuilder.getStyleHorizontalScale(ctx, styleRef),
                    RunBuilder.getStyleUnderlineColor(ctx, styleRef));
            ctx.paragraphStyleContextCache.put(key, cached);
        }
        return new StoryConverter.StyleContext(
                cached.fillColor,
                cached.tracking,
                cached.fontFamily,
                cached.fontSize,
                cached.horizontalScale,
                cached.underlineColor);
    }

    private static String sourceStoryId(String storyId) {
        if (storyId == null) return "";
        int cut = -1;
        int pi = storyId.indexOf("_pi");
        int oc = storyId.indexOf("_oc");
        if (pi >= 0) cut = pi;
        if (oc >= 0) cut = cut < 0 ? oc : Math.min(cut, oc);
        return cut >= 0 ? storyId.substring(0, cut) : storyId;
    }

    private static void applyTrailingPageNumberLeader(ResolvedBuildContext ctx,
                                                      IDMLParagraph paragraph,
                                                      ASTParagraph para) {
        if (para == null || para.items() == null || para.items().size() < 2) return;
        if (!hasVisibleText(para)) return;

        List<ASTInlineItem> items = para.items();
        for (int i = items.size() - 1; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (item == null || item.itemType() == ASTInlineItem.ItemType.BREAK) continue;
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                ASTTextRun textRun = (ASTTextRun) item;
                String text = textRun.text();
                if (text == null || text.trim().isEmpty()) continue;
                if (isPageNumberTextRun(textRun)) {
                    if (TextFlowTabPolicy.hasTabImmediatelyBefore(items, i)) return;
                    if (!enableRightmostDotLeader(ctx, paragraph, para)) return;

                    ASTTextRun tabRun = new ASTTextRun();
                    tabRun.text("\t");
                    tabRun.textColor("#000000");
                    items.add(i, tabRun);
                    return;
                }
                return;
            }
            if (item.itemType() != ASTInlineItem.ItemType.INLINE_OBJECT) return;

            ASTInlineObject obj = (ASTInlineObject) item;
            if (obj.kind() != ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) return;
            if (!isPageNumberInlineObject(obj)) return;
            if (TextFlowTabPolicy.hasTabImmediatelyBefore(items, i)) return;
            if (!enableRightmostDotLeader(ctx, paragraph, para)) return;

            ASTTextRun tabRun = new ASTTextRun();
            tabRun.text("\t");
            tabRun.textColor("#000000");
            items.add(i, tabRun);
            return;
        }
    }

    private static boolean isPageNumberText(String text) {
        if (text == null) return false;
        String cleaned = text.replace("\r", "").replace("\n", "").trim();
        return cleaned.matches("\\d{1,4}");
    }

    private static boolean isPageNumberTextRun(ASTTextRun run) {
        if (run == null || !isPageNumberText(run.text())) return false;
        if (run.subscript() || run.superscript()) return false;
        String style = run.characterStyleRef();
        if (style != null) {
            String lower = style.toLowerCase(java.util.Locale.ROOT)
                    .replace("%3a", ":")
                    .replace("%25", "%");
            if (lower.contains("subscript") || lower.contains("superscript")
                    || lower.contains("하부자") || lower.contains("상부자")
                    || lower.contains("아래첨자") || lower.contains("위첨자")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPageNumberInlineObject(ASTInlineObject obj) {
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph p : obj.paragraphs()) {
            if (p.items() == null) continue;
            for (ASTInlineItem item : p.items()) {
                if (item != null && item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) sb.append(text);
                }
            }
        }
        return isPageNumberText(sb.toString());
    }

    private static void maybeInsertDecorativeLeaderTab(ResolvedBuildContext ctx,
                                                       IDMLParagraph paragraph,
                                                       IDMLCharacterRun run,
                                                       String inlineHexId,
                                                       String precedingText,
                                                       ASTParagraph astPara) {
        if (ctx == null || paragraph == null || run == null || astPara == null) return;
        if ((precedingText == null || precedingText.trim().isEmpty()) && !hasVisibleText(astPara)) return;
        if (!hasDecorativeParagraphRule(ctx, paragraph)) return;
        if (!isInlinePageNumberFrame(ctx, run, inlineHexId)) return;
        if (TextFlowTabPolicy.paragraphEndsWithTab(astPara)) return;
        if (!enableRightmostDotLeader(ctx, paragraph, astPara)) return;

        ASTTextRun tabRun = new ASTTextRun();
        tabRun.text("\t");
        tabRun.textColor("#000000");
        astPara.addItem(tabRun);
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

    private static boolean hasDecorativeParagraphRule(ResolvedBuildContext ctx, IDMLParagraph paragraph) {
        if (paragraph.ruleBelowOn()) return true;
        if (ctx.styleResolver == null) return false;
        IDMLStyleDef style = ctx.styleResolver.getResolvedParagraphStyle(paragraph.appliedParagraphStyle());
        if (style == null) return false;
        if (positive(style.ruleAboveLineWeight()) || positive(style.ruleBelowLineWeight())) return true;
        return positive(style.underlineWeight()) && positive(style.underlineOffset());
    }

    private static boolean positive(Double value) {
        return value != null && value > 0.0;
    }

    private static boolean enableRightmostDotLeader(ResolvedBuildContext ctx, IDMLParagraph paragraph, ASTParagraph para) {
        if ((para.tabStops() == null || para.tabStops().size() < 2)
                && ctx.styleResolver != null && paragraph.appliedParagraphStyle() != null) {
            IDMLStyleDef style = ctx.styleResolver.getResolvedParagraphStyle(paragraph.appliedParagraphStyle());
            if (style != null && style.tabStops() != null) {
                for (IDMLStyleDef.TabStop ts : style.tabStops()) {
                    if (ts == null || ts.position() <= 0) continue;
                    para.addTabStop(new ASTTabStop(
                            CoordinateConverter.pointsToHwpunits(ts.position()),
                            mapTabAlignment(ts.alignment()),
                            ts.leader()));
                }
            }
        }
        if ((para.tabStops() == null || para.tabStops().size() < 2)
                && ctx.astDocument != null && ctx.astDocument.paragraphStyles() != null
                && paragraph.appliedParagraphStyle() != null) {
            String styleRef = paragraph.appliedParagraphStyle();
            String cleanStyleRef = styleRef.contains("/")
                    ? styleRef.substring(styleRef.lastIndexOf('/') + 1) : styleRef;
            for (ASTStyleDef style : ctx.astDocument.paragraphStyles()) {
                if (style == null || style.tabStops() == null) continue;
                if (!styleRef.equals(style.styleId()) && !cleanStyleRef.equals(style.styleName())) continue;
                for (ASTTabStop ts : style.tabStops()) {
                    if (ts == null || ts.position() <= 0) continue;
                    para.addTabStop(new ASTTabStop(ts.position(), ts.alignment(), ts.leader()));
                }
                break;
            }
        }
        if (para.tabStops() == null || para.tabStops().size() < 2) return false;
        ASTTabStop rightmost = null;
        for (ASTTabStop stop : para.tabStops()) {
            if (stop == null || stop.position() <= 0) continue;
            if (rightmost == null || stop.position() > rightmost.position()) {
                rightmost = stop;
            }
        }
        if (rightmost == null) return false;
        // 이미 leader가 지정된 탭(예: 밑줄 빈칸의 "_" SOLID)은 점선으로 덮어쓰지 않는다.
        if (rightmost.leader() == null || rightmost.leader().isEmpty()) {
            rightmost.leader(".");
        }
        return true;
    }

    private static String mapTabAlignment(String alignment) {
        if (alignment == null) return "left";
        String a = alignment.toLowerCase();
        if (a.contains("center")) return "center";
        if (a.contains("right")) return "right";
        if (a.contains("decimal")) return "decimal";
        return "left";
    }

    private static void addUnderlineBlankTabStop(ResolvedBuildContext ctx, String storyId, int paraIndex,
                                                 ASTParagraph para, List<IDMLCharacterRun> runs) {
        if (ctx == null || ctx.resolvedData == null || storyId == null || para == null) return;
        if (!hasUnderlineBlankAnchor(runs)) return;

        double posPt = -1;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames == null) return;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null) continue;
            int localPara = paraIndex - tf.paragraphStart();
            if (localPara < 0) continue;
            double[] frameBounds = tf.geometricBounds();
            if (frameBounds == null || frameBounds.length < 4) continue;

            double insetLeft = 0;
            double insetRight = 0;
            if (tf.insetSpacing() != null && tf.insetSpacing().length >= 4) {
                insetLeft = tf.insetSpacing()[1];
                insetRight = tf.insetSpacing()[3];
            }
            if (tf.composedLines() != null) {
                for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
                    if (line == null || line.paraIndex() != localPara) continue;
                    String lineText = line.text();
                    if (lineText == null || lineText.indexOf('\u0008') < 0) continue;
                    double[] lineBounds = line.bounds();
                    if (lineBounds != null && lineBounds.length >= 4) {
                        posPt = Math.max(posPt, lineBounds[3] - frameBounds[1] - insetLeft);
                    }
                }
            }
            if (posPt <= 0 && tf.frameParaTexts() != null && localPara < tf.frameParaTexts().size()) {
                String frameText = tf.frameParaTexts().get(localPara);
                if (frameText != null && frameText.indexOf('\u0008') >= 0) {
                    posPt = Math.max(posPt, (frameBounds[3] - frameBounds[1]) - insetLeft - insetRight);
                }
            }
        }
        if (posPt <= 0) return;
        long pos = CoordinateConverter.pointsToHwpunits(posPt);
        if (pos <= 0) return;
        if (para.tabStops() != null) {
            long tol = CoordinateConverter.pointsToHwpunits(1.0);
            for (ASTTabStop tab : para.tabStops()) {
                if (tab != null && Math.abs(tab.position() - pos) <= tol) {
                    return;
                }
            }
        }
        // 밑줄 빈칸은 실선("_"→SOLID)으로 채운다. 점선("."→DOT)은 가운데줄처럼 보여 부적합.
        para.addTabStop(new ASTTabStop(pos, "left", "_"));
    }

    private static boolean hasUnderlineBlankAnchor(List<IDMLCharacterRun> runs) {
        if (runs == null) return false;
        for (IDMLCharacterRun run : runs) {
            if (run == null || run.content() == null) continue;
            if (run.content().indexOf('\u0008') >= 0 && RunBuilder.hasUnderlineIntent(run, null)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIdmlInlineAnchorRuns(List<IDMLCharacterRun> runs) {
        if (runs == null) return false;
        for (IDMLCharacterRun run : runs) {
            if (run == null) continue;
            String text = run.content();
            if (text != null && text.indexOf('\uFFFC') >= 0) return true;
            if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) return true;
        }
        return false;
    }

    private static boolean insertResolvedLeadingInlineAnchors(
            ResolvedBuildContext ctx,
            ResolvedParagraph resolvedParagraph,
            List<ResolvedRun> resolvedRuns,
            ASTParagraph para) {
        if (ctx == null || resolvedRuns == null || resolvedRuns.isEmpty() || para == null) {
            return false;
        }
        boolean inserted = false;
        for (ResolvedRun run : resolvedRuns) {
            if (run == null) continue;
            if (run.isInlineAnchor()) {
                Integer anchoredId = run.anchoredObjectId();
                if (anchoredId == null || anchoredId <= 0) continue;
                List<ASTInlineObject> fragments =
                        InlineFrameHandler.loadPlannedInlineTextShellFragmentsForAnchor(ctx, anchoredId);
                if (fragments != null && !fragments.isEmpty()) {
                    for (ASTInlineObject fragment : fragments) {
                        if (fragment == null) continue;
                        fragment.keepInline(true);
                        para.addItem(fragment);
                    }
                    inserted = true;
                    continue;
                }
                ASTInlineObject inline =
                        InlineFrameHandler.loadPlannedInlineTextShellForAnchor(ctx, anchoredId);
                if (inline == null) {
                    inline = InlineFrameHandler.loadInlineObject(ctx, anchoredId);
                }
                if (inline == null) continue;
                inline.keepInline(true);
                para.addItem(inline);
                inserted = true;
                continue;
            }
            String text = run.text();
            if (text != null && !text.isEmpty()) break;
        }
        return inserted;
    }

    private static boolean isDoviraSubunitMarker(ResolvedParagraph resolvedParagraph,
                                                 IDMLParagraph idmlParagraph,
                                                 int domId) {
        if (domId <= 0) return false;
        if (!isDoviraSubunitParagraph(resolvedParagraph, idmlParagraph)) return false;
        if (resolvedParagraph == null || resolvedParagraph.runs() == null) {
            return true;
        }
        for (ResolvedRun run : resolvedParagraph.runs()) {
            if (run == null || !run.isInlineAnchor()) continue;
            Integer anchoredId = run.anchoredObjectId();
            if (anchoredId != null && anchoredId == domId) {
                return true;
            }
        }
        return true;
    }

    private static boolean isDoviraSubunitParagraph(ResolvedParagraph resolvedParagraph,
                                                    IDMLParagraph idmlParagraph) {
        if (DoviraSubunitMarkerPolicy.isDoviraSubunitParagraph(resolvedParagraph)) {
            return true;
        }
        String style = idmlParagraph != null ? idmlParagraph.appliedParagraphStyle() : null;
        if (style == null || style.isEmpty()) return false;
        if (DoviraSubunitMarkerPolicy.isDoviraSubunitStyleName(style)) return true;
        int slash = style.lastIndexOf('/');
        String leaf = slash >= 0 ? style.substring(slash + 1) : style;
        return DoviraSubunitMarkerPolicy.isDoviraSubunitStyleName(leaf);
    }

    private static boolean isInlinePageNumberFrame(ResolvedBuildContext ctx, IDMLCharacterRun run, String inlineHexId) {
        IDMLTextFrame inlineFrame = findInlineFrame(run, inlineHexId);
        if (inlineFrame != null && inlineFrame.appliedObjectStyle() != null
                && inlineFrame.appliedObjectStyle().contains("쪽수")) {
            return true;
        }

        int domId;
        try {
            domId = Integer.parseInt(inlineHexId.substring(1), 16);
        } catch (Exception e) {
            return false;
        }
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(domId));
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

    private static IDMLTextFrame findInlineFrame(IDMLCharacterRun run, String inlineHexId) {
        if (run.inlineFrames() == null || inlineHexId == null) return null;
        for (IDMLTextFrame frame : run.inlineFrames()) {
            if (frame != null && inlineHexId.equals(frame.selfId())) return frame;
        }
        return null;
    }

    static boolean isNeutralHangingIndent(Double leftIndent, Double firstLineIndent) {
        if (leftIndent == null || firstLineIndent == null) return false;
        return leftIndent > 0 && firstLineIndent < 0
                && Math.abs(leftIndent + firstLineIndent) < 0.01;
    }

    static boolean shouldPreserveNeutralHangingIndentForTab(ResolvedParagraph rp) {
        if (rp == null || !isNeutralHangingIndent(rp.leftIndent(), rp.firstLineIndent())
                || !rp.hasTabStops()) {
            return false;
        }
        double left = rp.leftIndent();
        for (ResolvedTabStop tab : rp.tabStops()) {
            if (tab == null || tab.position() == null) continue;
            if (Math.abs(tab.position() - left) < 0.01) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTextFrameStoryOwnedByPlacedTextFrame(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null
                || idmlCell.textFrameStoryRefs() == null || idmlCell.textFrameStoryRefs().isEmpty()) {
            return false;
        }
        for (String storyRef : idmlCell.textFrameStoryRefs()) {
            if (isStoryOwnedByPlacedTextFrame(ctx, storyRef)) return true;
        }
        return false;
    }

    private static boolean hasDirectVisibleCellText(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (idmlCell == null || idmlCell.paragraphs() == null) return false;
        for (IDMLParagraph paragraph : idmlCell.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null || run.content() == null) continue;
                String normalized = normalizeCellOwnershipText(run.content());
                if (!normalized.isEmpty()) return true;
            }
        }
        return false;
    }

    private static boolean hasDirectCellTextOrInlineObject(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (hasDirectVisibleCellText(idmlCell)) return true;
        if (idmlCell == null || idmlCell.paragraphs() == null) return false;
        for (IDMLParagraph paragraph : idmlCell.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null) continue;
                if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) return true;
                if (run.inlineFrames() != null && !run.inlineFrames().isEmpty()) return true;
                if (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()) return true;
            }
        }
        return false;
    }

    private static boolean hasPlannedInlineAtomicCellContent(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (ctx == null || ctx.ownershipPlans == null || idmlCell == null
                || idmlCell.paragraphs() == null) {
            return false;
        }
        for (IDMLParagraph paragraph : idmlCell.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) continue;
                for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                    Integer domId = inlineAnchorDomId(run, anchor);
                    if (domId != null && hasInlineTextShellPlan(ctx, domId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isFlattenedOwnedTextShellStoryCell(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null
                || idmlCell.textFrameStoryRefs() == null || idmlCell.textFrameStoryRefs().isEmpty()) {
            return false;
        }
        String cellText = normalizedCellText(idmlCell);
        if (cellText.isEmpty()) return false;
        for (String storyRef : idmlCell.textFrameStoryRefs()) {
            String storyId = toDecimalStoryId(storyRef);
            if (storyId == null || !isStoryOwnedByPlacedTextFrame(ctx, storyRef)) continue;
            ResolvedStory story = ctx.resolvedData.getStory(storyId);
            String storyText = normalizedResolvedStoryText(story);
            if (!storyText.isEmpty() && cellText.equals(storyText)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedCellText(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (idmlCell == null || idmlCell.paragraphs() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (IDMLParagraph paragraph : idmlCell.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null) continue;
                sb.append(normalizeCellOwnershipText(run.content()));
            }
        }
        return sb.toString();
    }

    private static String normalizedResolvedStoryText(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor()) continue;
                sb.append(normalizeCellOwnershipText(run.text()));
            }
        }
        return sb.toString();
    }

    private static Integer inlineAnchorDomId(IDMLCharacterRun run, IDMLCharacterRun.InlineAnchor anchor) {
        if (run == null || anchor == null) return null;
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (run.inlineFrames() == null || anchor.index() < 0 || anchor.index() >= run.inlineFrames().size()) {
                return null;
            }
            IDMLTextFrame frame = run.inlineFrames().get(anchor.index());
            return parseDomId(frame != null ? frame.selfId() : null);
        }
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
            if (run.inlineGraphics() == null || anchor.index() < 0 || anchor.index() >= run.inlineGraphics().size()) {
                return null;
            }
            IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
            return parseDomId(graphic != null ? graphic.selfId() : null);
        }
        return null;
    }

    private static Integer parseDomId(String id) {
        if (id == null || id.isEmpty()) return null;
        String value = id;
        if (value.startsWith("u") || value.startsWith("U")) {
            value = value.substring(1);
            try {
                return Integer.parseInt(value, 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean hasInlineTextShellPlan(ResolvedBuildContext ctx, int domId) {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (!ShellRole.isTextShell(plan) || plan.placement != Placement.INLINE) {
                continue;
            }
            if (plan.domId == domId || containsDomId(plan.sourceObjectIds, domId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDomId(int[] ids, int domId) {
        if (ids == null) return false;
        for (int id : ids) {
            if (id == domId) return true;
        }
        return false;
    }

    private static String normalizeCellOwnershipText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008') continue;
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            out.append(ch);
        }
        return out.toString();
    }

    private static boolean isStoryOwnedByPlacedTextFrame(ResolvedBuildContext ctx, String storyRef) {
        if (storyRef == null) return false;
        String storyId = toDecimalStoryId(storyRef);
        if (storyId == null) return false;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames == null || frames.isEmpty()) return false;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.id() == null) continue;
            try {
                int domId = Integer.parseInt(tf.id());
                if (ctx.isTextDisposed(domId, FrameDisposition.TEXT_BLOCK_PLACED)) return true;
                if (ctx.isTextFrameOwnedByTextShellPlan(domId)) return true;
            } catch (NumberFormatException ignored) {
                // Non-DOM ids cannot be matched against text-frame ownership disposition.
            }
        }
        return false;
    }

    private static void applyCharacterStylePosition(ResolvedBuildContext ctx, IDMLCharacterRun run) {
        if (ctx == null || ctx.styleResolver == null || run == null) return;
        String styleRef = run.appliedCharacterStyle();
        if (styleRef == null || styleRef.isEmpty()) return;
        IDMLStyleDef style = ctx.styleResolver.getResolvedCharacterStyle(styleRef);
        if (style == null) return;
        if (isNormalPosition(run.position()) && style.position() != null) {
            run.position(style.position());
        }
        if (run.baselineShift() == null && style.baselineShift() != null) {
            run.baselineShift(style.baselineShift());
        }
    }

    private static boolean isNormalPosition(String position) {
        if (position == null || position.trim().isEmpty()) return true;
        return position.toLowerCase(java.util.Locale.ROOT).contains("normal");
    }

    private static String toDecimalStoryId(String storyRef) {
        if (storyRef == null || storyRef.isEmpty()) return null;
        String s = storyRef;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        if (s.startsWith("Story_")) s = s.substring("Story_".length());
        if (s.startsWith("u") && s.length() > 1) {
            try {
                return String.valueOf(Integer.parseInt(s.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return storyRef;
            }
        }
        return storyRef;
    }
}
