package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
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
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.MatchConfidence;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedTextFlowAstConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextFlowTabPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.DoviraSubunitMarkerPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.SourceTextStyleResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.Set;

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
    public static List<ASTParagraph> convertStoryFromIDML(ResolvedBuildContext ctx, String storyId) {
        if (ctx.idmlDir == null) return null;
        if (isResolvedSyntheticStoryClone(ctx, storyId)) {
            return null;
        }
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

        IDMLStory idmlStory = null;
        if (ctx.loadIDMLStory != null) {
            idmlStory = ctx.loadIDMLStory.apply(storyId);
            if (idmlStory != null) {
                ConversionTiming.addCounter("phase3.storyLoader.sharedLoaderHits", 1);
            }
        }
        if (idmlStory == null && ctx.idmlStoryCache != null) {
            idmlStory = ctx.idmlStoryCache.get(storyId);
            if (idmlStory == null) idmlStory = ctx.idmlStoryCache.get(sourceStoryId);
            if (idmlStory == null) idmlStory = ctx.idmlStoryCache.get(hexId);
            if (idmlStory != null) {
                ConversionTiming.addCounter("phase3.storyLoader.cacheHits", 1);
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
            String sourceParagraphAlignment = para.alignment();

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

            appendGeneratedParagraphPrefix(ctx, resolvedParagraph, ip, para);
            buildParagraphContent(ctx, ip, resolvedParagraph, resolvedRuns, storyId, i, sc, para);
            MathProcessor.convertMathRunsInParagraph(ctx, para);

            paragraphs.add(para);
            paragraphs.addAll(splitTerminalCustomAnchoredCarriers(ctx, para, sourceParagraphAlignment));
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
            runs = ASTMathGrouper.splitChemicalFormulaMixedRuns(runs);
            ASTRunConverter.convertCircledNumberRuns(runs);
            enrichMathFontsFromResolvedRuns(ctx, runs, resolvedRuns);
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
            // resolved font authority를 반영한 CharacterRun에서 수식 기호 유무 확인
            for (IDMLCharacterRun r : runs) {
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

                // SPEC-080 Phase 2: `상부자(이탤릭)` 숫자(제곱하여 4가 되는 수 → 4·25)는
                // GREP 가 수식 서체로 명시한 요소다. EH/BT 그룹 파이프라인은 방어 로직이
                // 여러 겹이라 순수 숫자를 텍스트로 폴백한다 → 여기서 열린 그룹을 flush 하고
                // ASTEquation 으로 직접 방출해 모든 게이트를 우회한다(x 변수의 LATIN_VAR
                // 경로와 동형). 과학 화학식은 `상부자(이탤릭)` 미사용(전수 0건)이라 무영향.
                if (isGrepSangbujaItalicNumber(run) && !hasIdmlInlineAnchor(run)
                        && isBodyNumberContext(runs, idx)
                        && mathGroup.isEmpty() && ehMathGroup.isEmpty() && npMathGroup.isEmpty()) {
                    MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, ehMathGroup, para);
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                            new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation();
                    eq.hwpScript(run.content().trim());
                    eq.sourceType("GREP_NUMBER");
                    if (run.fillColor() != null) {
                        String hex = RunBuilder.resolveColorToHex(ctx, run.fillColor());
                        if (hex != null && !hex.isEmpty()) eq.textColor(hex);
                    }
                    para.addItem(eq);
                    continue;
                }

                // 근호 마커 직후(radicand 텍스트 없이) 순수 빈 답란 박스만 온 경우, 박스
                // 1개를 HWP 수식 box{~} 로 근호 안 radicand 에 넣어 근호가 답란을 덮게 한다
                // (실측: 1단원 p32 "√□"). 그룹 마지막이 sqrt 마커일 때만 — radicand 텍스트가
                // 섞인 √(2×□) 복합 케이스는 별도 처리. 같은 런의 나머지 박스는 근호 밖으로
                // 남겨(FFFC 하나 제거 후 재처리) 정상 배치한다.
                if (run.content() != null
                        && !ehMathGroup.isEmpty()
                        && EHFontGlyphMap.isFractionNumeratorFont(
                                ehMathGroup.get(ehMathGroup.size() - 1).fontFamily())
                        && run.content().replace("￼", "").isEmpty()
                        && (run.inlineFrames() == null || run.inlineFrames().isEmpty())
                        && allInlineGraphicsAreEmptyAnswerBox(run)) {
                    IDMLCharacterRun boxRun = run.shallowCopyWithoutInlines();
                    // ␜ 를 붙이지 않는다 — lexer 가 ␜ 를 radicand 종료 SEP 로 렉싱하게
                    // 되면서, 여기 붙인 인위적 센티넬이 √(□²)·√(□×9/2) 의 뒤 지수·곱셈을
                    // 근호 밖으로 밀어냈다. 여긴 실제 스페이서가 아니므로 간격도 불필요.
                    boxRun.content("box{~}");
                    MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, null, para);
                    ehMathGroup.add(boxRun);
                    if (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()) {
                        run.inlineGraphics().remove(0);
                    }
                    if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) {
                        run.inlineAnchors().remove(0);
                    }
                    String rest = run.content().replaceFirst("￼", "");
                    if ((run.inlineGraphics() == null || run.inlineGraphics().isEmpty())
                            && rest.replace("￼", "").isEmpty()) {
                        continue; // 남은 박스 없음 → 런 소진
                    }
                    run.content(rest);
                    idx--; // 남은 박스 재처리
                    continue;
                }

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
                    // 내용이 한국어뿐인 EH 폰트 런도 리셋 — InDesign DOM(resolved)이 √
                    // 글리프 뒤 한국어 문장까지 EH상부자로 보고해 수식 그룹에 빨려들면
                    // lexSubSup 이 미매핑 0x80+ 로 한국어를 통째로 버린다(실측: p20 표 셀
                    // "a가 √a 보다 항상 더 큰지 말해 보자." → sqrt{a}. 로 잘림).
                    boolean koreanOnly = EHTextClassifier.containsKorean(ct)
                            && EHTextClassifier.isKoreanOnly(ct);
                    if ((!paraHasMathSymbols && !isSingleLatinVar) || nearLongWord || koreanOnly) {
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
                boolean formulaClusterRun = ASTMathGrouper.isFormulaEquationClusterRun(run, runs, idx);

                if (_orcOnly && formulaClusterRun
                        && MathProcessor.isFormulaAnswerPlaceholderRun(ctx, run)) {
                    MathProcessor.flushMathGroups(ctx, null, npMathGroup, ehMathGroup, para);
                    mathGroup.add(ASTMathGrouper.formulaAnswerBoxRun(run));
                    continue;
                }

                if (isStandaloneBtArrowGlyphRun(run) && !formulaClusterRun) {
                    // 화살표에서 수식 그룹을 끊는다(화살표는 수식의 일부가 아니다).
                    MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, ehMathGroup, para);
                    // 화살표 런을 새로 만들지 않는다 — IDMLStoryParser 가 파싱 직후 이미
                    // "→" 로 정규화해 두었다. 여기서 또 추가하면 중복("→→")된다.
                    //
                    // 화살표 글리프가 "@" + "C" 처럼 여러 IDML 런으로 쪼개져 들어오는
                    // 경우가 있어, 조각마다 화살표가 붙던 것이 중복의 원인이었다.
                    // 정규화된 화살표 런은 그대로 일반 텍스트 경로로 흘려보낸다.
                    if (lastItemIsArrow(para)) {
                        // 같은 화살표 글리프의 나머지 조각 — 버린다.
                        continue;
                    }
                }

                // EH 수식 그룹 진입
                // "수식" 그룹에 속하지만 실제로는 본문 영문/숫자를 조판하는 스타일은
                // 수식 그룹을 새로 열지 않는다. 과학 교과서의 "00_수식모음:00_영문bold"
                // 가 그것으로, 한글 문장 속 원소기호가 이탤릭 수식이 되던 원인이다
                // — "수소 원자(H)" 의 H, "산소 원자(O)" 의 O (표 셀에서 관측).
                //
                // 이미 열린 그룹은 건드리지 않는다(ehMathGroup.isEmpty() 조건).
                // 진행 중인 수식 흐름을 끊으면 ":3:" 같은 비율 표기가 ":" 로 분해된다.
                boolean bodyTextGlyphRun = ehMathGroup.isEmpty()
                        && BTFontGlyphMap.isBodyTextGlyphStyle(run.appliedCharacterStyle())
                        && !hasResolvedStructuralMathFont(run.fontFamily());

                boolean enterEH = !_orcOnly && !bodyTextGlyphRun
                        && (run.isEHFont()
                        || EHFontGlyphMap.containsEHEncodedChars(run.content())
                        || EHFontGlyphMap.containsEHFractionPattern(run.content())
                        || (ehMathGroup.isEmpty() && ASTMathGrouper.isPreEHMathRun(run, runs, idx))
                        || (!ehMathGroup.isEmpty() && ASTMathGrouper.isEHMathBridgeRun(run, runs, idx))
                        || (!ehMathGroup.isEmpty() && MathProcessor.isEHSqrtContent(run, ehMathGroup)));

                // EH 폰트 공백-only run이 그룹을 "시작"하면 그 선행 공백이 수식에
                // 흡수돼 유실된다(실측: 3단원 ⑶ 뒤 EH상부자 공백 → (3)과 수식이 붙음).
                // 그룹이 비어있을 때의 공백 run은 일반 텍스트로 내보낸다.
                if (enterEH && ehMathGroup.isEmpty()
                        && run.content() != null && run.content().trim().isEmpty()) {
                    enterEH = false;
                }
                // "변수`단위"(예: y`cmÛ`) 구조 run은 EH 그룹에 넣지 않는다. 넣으면
                // 변수+단위가 통째로 수식이 된다(ycm²). 일반 텍스트로 남겨 convertItalic
                // 경로의 splitVariableBacktickLatin 이 변수(수식)/단위(텍스트)로 나누게
                // 한다. x`km(제곱 없음)과 동일 경로로 통일(실측: 3단원 x km, y cm²).
                if (enterEH && ehMathGroup.isEmpty()
                        && RunPostProcessor.isVariableBacktickLatinRun(run.content())) {
                    enterEH = false;
                }
                // 실제 인라인 프레임/그래픽 앵커를 가진 run은 EH 그룹에 넣지 않는다.
                // 넣으면 앵커(U+FFFC)가 수식 스크립트/□ 로 뭉개져 프레임이 유실된다
                // (실측: 3단원 y=-x²/5 의 x²/5 분수 앵커 프레임). 정상 인라인 배치로 보낸다.
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
                    boolean btBodyTextWithoutFormulaStructure = run.isBTFont()
                            && BTFontGlyphMap.isBTBodyTextFont(run.fontFamily())
                            && !formulaClusterRun
                            && !ASTMathGrouper.isChemicalFormulaTextRun(run)
                            && !ASTMathGrouper.looksLikeMathRun(run.content());
                    boolean formulaBoundaryOnly = ASTMathGrouper.isChemicalFormulaBoundaryRun(run);
                    enterBT = !formulaBoundaryOnly
                            && ((run.isBTFont()
                                    && !btBodyTextWithoutFormulaStructure
                                    && !ASTMathGrouper.isBTRunWithOnlyKorean(run.content()))
                            || run.grepMathFont()
                            || (!mathGroup.isEmpty() && ASTMathGrouper.isMathBridgeRun(run, runs, idx))
                            || (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content()))
                            || formulaClusterRun);
                }

                // 실제 인라인 프레임/그래픽 앵커를 가진 run은 어떤 수식 그룹(EH/NP/BT)에도
                // 넣지 않는다. 넣으면 앵커(U+FFFC)가 수식 스크립트/□ 로 뭉개져 프레임이
                // 유실된다(실측: 3단원 y=-x²/5 의 x²/5 분수 앵커). 정상 인라인 배치로 보낸다.
                //
                // 예외: EH 근호 그룹이 열려 있고 앵커가 색 없는 투명 스페이서
                // Rectangle 뿐이면, radicand 를 근호에 넣어야 한다(실측: 1단원 p22
                // √15, √0.81, √(9/144) — √ 갈고리는 EH분수대문자 텍스트, radicand 는
                // 텍스트, 항 사이 간격은 baseline 근처의 색 없는 고정폭(28.3pt)
                // Rectangle 로 조판). 이 Rectangle 은 이미지·텍스트가 없어 인라인
                // 배치 대상이 아니다. FFFC 위치는 그 근호의 radicand 종료 경계이자 항
                // 간격 자리이므로, 지우지 않고 센티넬(U+241C)로 바꿔 EH 수식 변환기가
                // (1) 인접한 다음 근호와 radicand 를 섞지 않고 (2) 그 자리에 항 간격을
                // 넣게 한다.
                // 근호 그룹이 열려 있고, radicand 텍스트 뒤에 인라인 그래픽(FFFC)이
                // 붙은 런은 FFFC 앞 radicand 만 근호에 넣고, 앵커는 근호 뒤에 그대로
                // 배치한다(실측: 1단원 p26 문제6 "√7 □ 0" — √ 갈고리 뒤 "7 <FFFC> 0" 런,
                // p19 "√3 ●<" — radicand 3 뒤 정답 원 그래픽. 답란 박스만 허용하면
                // 원 그래픽 런이 그룹에서 통째로 밀려나 √ 와 3 이 분리된다).
                // FFFC 앞 텍스트를 근호 종료 센티넬(U+241C)까지 EH 그룹에 넣고, 런 자신은
                // FFFC 부터로 잘라 다시 처리(idx--) → 남은 부분이 일반 인라인 앵커 경로로
                // 그래픽을 배치한다. 단 vinculum 스페이서 전용 런은 제외 — 아래 센티넬
                // 치환 경로가 런 전체를 그룹에 넣어야 한다.
                if (enterEH && !ehMathGroup.isEmpty()
                        && EHFontGlyphMap.isFractionNumeratorFont(
                                ehMathGroup.get(ehMathGroup.size() - 1).fontFamily())
                        && (run.inlineFrames() == null || run.inlineFrames().isEmpty())
                        && !allInlineGraphicsAreVinculum(run)
                        && run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()
                        && run.content() != null) {
                    int fffcPos = run.content().indexOf('￼');
                    if (fffcPos > 0) {
                        String radicandText = run.content().substring(0, fffcPos) + '␜';
                        IDMLCharacterRun radicandRun = run.shallowCopyWithoutInlines();
                        radicandRun.content(radicandText);
                        if (radicandRun.fontSize() == null && resolvedRuns != null) {
                            ResolvedRun rrSize = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, radicandText);
                            if (rrSize != null && rrSize.fontSize() != null && rrSize.fontSize() > 0) {
                                radicandRun.fontSize(rrSize.fontSize());
                            }
                        }
                        MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, null, para);
                        ehMathGroup.add(radicandRun);
                        // 현재 런은 FFFC 부터로 잘라 재처리 → 박스 앵커 정상 배치
                        run.content(run.content().substring(fffcPos));
                        idx--;
                        continue;
                    }
                }

                boolean anchorIsVinculumOnly = enterEH
                        && !ehMathGroup.isEmpty()
                        && EHFontGlyphMap.isFractionNumeratorFont(
                                ehMathGroup.get(ehMathGroup.size() - 1).fontFamily())
                        && (run.inlineFrames() == null || run.inlineFrames().isEmpty())
                        && allInlineGraphicsAreVinculum(run);
                if (anchorIsVinculumOnly) {
                    if (run.content() != null) {
                        run.content(run.content().replace('\uFFFC', '\u241C'));
                    }
                    run.inlineGraphics().clear();
                    if (run.inlineAnchors() != null) run.inlineAnchors().clear();
                } else if ((run.inlineFrames() != null && !run.inlineFrames().isEmpty())
                        || (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty())) {
                    enterEH = false;
                    enterNP = false;
                    enterBT = false;
                }

                // SPEC-042: 수식 그룹 런은 RunBuilder(resolved 권위) 경로를 우회하므로,
                // 매칭 resolved run 의 fillColor 를 여기서 주입한다. resolved 는 InDesign
                // 이 실제 렌더한 계산값이라 IDML 로컬 속성보다 권위 있다 (실측: p17
                // 2H₂ 의 H 가 IDML 로컬 파랑·resolved 빨강 — 인쇄는 빨강).
                if ((enterEH || enterNP || enterBT) && resolvedRuns != null && run.content() != null) {
                    // findResolvedRun 은 ctx.lastMatchResult 공유 커서를 갱신하므로,
                    // 부수 조회가 본류 매칭을 밀지 않게 저장/복원한다.
                    int savedMatchCursor = ctx.lastMatchResult[0];
                    ResolvedRun rrColor = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, run.content());
                    ctx.lastMatchResult[0] = savedMatchCursor;
                    if (rrColor != null && rrColor.fillColor() != null) {
                        // 무채색(검정·회색)은 주입하지 않는다 — 기본 텍스트의 기존
                        // 상속 경로(CharPr 크기 계승 포함)를 바꾸지 않기 위함.
                        // 목적은 화학식 강조색(유채색) 보존이다.
                        String hex = RunBuilder.resolveColorToHex(ctx, rrColor.fillColor());
                        if (hex != null && hex.length() == 7 && !isGrayscaleHex(hex)) {
                            run.fillColor(rrColor.fillColor());
                        }
                    }
                }

                if (enterEH) {
                    MathProcessor.flushMathGroups(ctx, mathGroup, npMathGroup, null, para);
                    // IDML run 은 명시적 fontSize 가 없어(스타일 상속만) EH 수식의
                    // 원본 크기 힌트가 유실된다(실측: "이차함수 y=ax² 의 그래프" 24pt 가
                    // 기본 11pt 로). 매칭 resolved run 의 크기를 IDML run 에 채워둔다.
                    if (run.fontSize() == null && resolvedRuns != null) {
                        ResolvedRun rrSize = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, run.content());
                        if (rrSize != null && rrSize.fontSize() != null && rrSize.fontSize() > 0) {
                            run.fontSize(rrSize.fontSize());
                        }
                    }
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
                    if (text == null || text.isEmpty()) {
                        if (appendAnchorOnlyRunItems(ctx, runs, idx, run, resolvedParagraph,
                                ip, storyId, para)) {
                            hasIdmlInlineAnchors = true;
                            if (!hasVisibleText(para)) {
                                firstTextRunAfterLeadingAnchor = true;
                            }
                        }
                        continue;
                    }

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
                                // ;...; 분수 GREP 패턴이 있으면 분수 수식으로 먼저 분리한다
                                // (실측: 1단원 p26 "⑶ ;2%; □ √8"의 5/2 분수). resolved 스타일
                                // 분할(splitIdmlRunByResolvedRuns)이 세미콜론을 런 경계로 잘라
                                // ";2%;"→"2%" 로 만들면 분수 패턴이 깨지므로, 그 전에 처리한다.
                                // 텍스트 조각만 처리하고, 뒤따르는 인라인 앵커(빈 답란 □)는
                                // 아래 공통 블록이 그대로 이어서 배치한다.
                                boolean handledAsFraction = false;
                                if (EHFontGlyphMap.containsEHFractionPattern(partText)) {
                                    ResolvedRun fracRR = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, partText);
                                    if (fracRR != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                                    ASTTextRun fracTemplate = RunBuilder.createRunFromIDML(ctx, run, partText,
                                            fracRR != null ? fracRR : defaultRR, sc, MatchConfidence.LOW);
                                    MathProcessor.splitFractionPatternInText(ctx, partText, fracTemplate, para);
                                    handledAsFraction = true;
                                }
                                // resolved 런 스타일 차이가 있으면 분할 시도
                                boolean partSplit = false;
                                if (!handledAsFraction && resolvedStyleVaries) {
                                    partSplit = RunBuilder.splitIdmlRunByResolvedRuns(ctx, run, partText, resolvedRuns, resolvedRunIdx,
                                            para, sc);
                                }
                                if (!handledAsFraction && !partSplit) {
                                    ResolvedRun matchedRR = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, partText);
                                    if (matchedRR != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                                    long createStart = System.nanoTime();
                                    MatchConfidence confidence = matchedRR != null
                                            ? RunBuilder.confidenceForResolvedRunMatch(matchedRR, partText)
                                            : MatchConfidence.LOW;
                                    ASTTextRun tr = RunBuilder.createRunFromIDML(ctx, run, partText,
                                            matchedRR != null ? matchedRR : defaultRR, sc, confidence);
                                    ConversionTiming.addCounter(perfPrefix + ".createRunNanos",
                                            System.nanoTime() - createStart);
                                    if (!RunBuilder.splitBulletRun(ctx, tr, para)) {
                                        long splitLatinStart = System.nanoTime();
                                        RunBuilder.splitChemicalFormulasAndLatinVarsInMixedText(ctx, tr, para);
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
                                        applyPositionedInlineTextFrameAdvance(ctx, storyId, para, domId);
                                        InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, domId, para);
                                        for (ASTInlineItem item : plannedItems) para.addItem(item);
                                        anchorIdx++;
                                        if (isTableCellInlineComplexFormCompletePngAnchor(ctx, domId)) {
                                            anchorIdx = inlineIds.size();
                                            break;
                                        }
                                        continue;
                                    }
                                    if (InlineFrameHandler.hasOwnershipPlanForAnchorBundle(ctx, domId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    warnUnplannedInlineAnchorSkipped(ctx, storyId, domId);
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
                            MatchConfidence confidence = matchedRR2 != null
                                    ? RunBuilder.confidenceForResolvedRunMatch(matchedRR2, text)
                                    : MatchConfidence.LOW;
                            ASTTextRun tr = RunBuilder.createRunFromIDML(ctx, run, text,
                                    matchedRR2 != null ? matchedRR2 : defaultRR, sc, confidence);
                            ConversionTiming.addCounter(perfPrefix + ".createRunNanos",
                                    System.nanoTime() - createStart);
                            // ;...; 분수 GREP 패턴이 포함된 텍스트 → 분수 수식으로 분리
                            if (!RunBuilder.splitBulletRun(ctx, tr, para)) {
                                if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                                    MathProcessor.splitFractionPatternInText(ctx, text, tr, para);
                                } else {
                                    long splitLatinStart = System.nanoTime();
                                    RunBuilder.splitChemicalFormulasAndLatinVarsInMixedText(ctx, tr, para);
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

            // SPEC-055(text-attribute): resolved 가 문단 전체를 단일 유채색으로
            // 보고하면, 매칭 실패(LOW)로 색을 잃은 런에도 그 색을 채운다
            // (p26 실측: 수식이 낀 줄의 조각들이 검정으로 빵꾸).
            // 불릿 리셋보다 먼저 — 불릿 문단의 의도적 검정 리셋이 이긴다.
            RunBuilder.applyUniformResolvedParagraphColor(ctx, resolvedRuns, para);

            // 불릿 단락이면 불릿 이후 런 색상을 검정으로 리셋
            RunBuilder.resetBulletParagraphColors(ctx, para);

            // Legacy-only: Stage 1 ObjectPlan이 있으면 source/story order가 실행 계약이다.
            if (!hasIdmlInlineAnchors && !hasStage1ObjectPlans(ctx)) {
                ASTTableConverter.reorderInlineObjectsByBoundsX(para);
            }

            // 화학식은 MathProcessor.convertMathRunsInParagraph 에서 ASTEquation으로 닫는다.
    }

    private static boolean appendAnchorOnlyRunItems(
            ResolvedBuildContext ctx,
            List<IDMLCharacterRun> runs,
            int runIndex,
            IDMLCharacterRun run,
            ResolvedParagraph resolvedParagraph,
            IDMLParagraph idmlParagraph,
            String storyId,
            ASTParagraph para) {
        List<String> inlineIds = inlineIdsInRunOrder(run);
        if (inlineIds.isEmpty()) return false;
        boolean handled = false;
        String previousText = nearestIdmlText(runs, runIndex - 1, -1);
        String nextText = nearestIdmlText(runs, runIndex + 1, 1);
        for (String inlineId : inlineIds) {
            int domId = parseInlineDomId(inlineId);
            if (domId < 0) continue;
            if (isDoviraSubunitMarker(resolvedParagraph, idmlParagraph, domId)
                    && DoviraSubunitMarkerPolicy.isDuplicateMarkerStory(
                    ctx != null ? ctx.resolvedData : null, storyId)) {
                handled = true;
                continue;
            }
            List<ASTInlineItem> plannedItems =
                    InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, domId, previousText, nextText);
            if (plannedItems != null) {
                applyPositionedInlineTextFrameAdvance(ctx, storyId, para, domId);
                InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, domId, para);
                for (ASTInlineItem item : plannedItems) {
                    if (item instanceof ASTInlineObject) {
                        ((ASTInlineObject) item).keepInline(true);
                    }
                    para.addItem(item);
                }
                handled = true;
                continue;
            }
            if (InlineFrameHandler.hasOwnershipPlanForAnchorBundle(ctx, domId)) {
                handled = true;
                continue;
            }
            warnUnplannedInlineAnchorSkipped(ctx, storyId, domId);
        }
        return handled;
    }

    private static boolean isTableCellInlineComplexFormCompletePngAnchor(
            ResolvedBuildContext ctx,
            int domId) {
        if (ctx == null || domId < 0) return false;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectId(domId)) {
            if (plan == null) continue;
            if (plan.textAction == TextAction.OWNED_BY_PNG
                    && plan.visualAction == VisualAction.PLACE_INLINE_PNG
                    && plan.materialization == Materialization.COMPLETE_PNG
                    && plan.placement == Placement.INLINE
                    && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                    && "table_cell_inline_complex_form_complete_png".equals(plan.slotRole)
                    && plan.ownedTextFrameIds != null
                    && plan.ownedTextFrameIds.length > 1) {
                return true;
            }
        }
        return false;
    }

    private static List<String> inlineIdsInRunOrder(IDMLCharacterRun run) {
        List<String> ids = new ArrayList<>();
        if (run == null) return ids;
        if (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty()) {
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                if (anchor == null) continue;
                if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                        && run.inlineFrames() != null
                        && anchor.index() >= 0
                        && anchor.index() < run.inlineFrames().size()) {
                    ids.add(run.inlineFrames().get(anchor.index()).selfId());
                } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                        && run.inlineGraphics() != null
                        && anchor.index() >= 0
                        && anchor.index() < run.inlineGraphics().size()) {
                    ids.add(run.inlineGraphics().get(anchor.index()).selfId());
                }
            }
            return ids;
        }
        if (run.inlineFrames() != null) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame frame : run.inlineFrames()) {
                if (frame != null && frame.selfId() != null) ids.add(frame.selfId());
            }
        }
        if (run.inlineGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic graphic : run.inlineGraphics()) {
                if (graphic != null && graphic.selfId() != null) ids.add(graphic.selfId());
            }
        }
        return ids;
    }

    private static int parseInlineDomId(String inlineId) {
        if (inlineId == null || inlineId.isEmpty()) return -1;
        String s = inlineId;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        if (s.startsWith("u") || s.startsWith("U")) s = s.substring(1);
        int end = 0;
        while (end < s.length()) {
            char c = s.charAt(end);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) break;
            end++;
        }
        if (end == 0) return -1;
        try {
            return Integer.parseInt(s.substring(0, end), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String nearestIdmlText(List<IDMLCharacterRun> runs, int start, int step) {
        if (runs == null || step == 0) return null;
        for (int i = start; i >= 0 && i < runs.size(); i += step) {
            IDMLCharacterRun run = runs.get(i);
            String text = run != null ? run.content() : null;
            if (text == null) continue;
            String normalized = text.replace("\uFFFC", "").trim();
            if (!normalized.isEmpty()) return normalized;
        }
        return null;
    }

    private static boolean hasStage1ObjectPlans(ResolvedBuildContext ctx) {
        return ctx != null && ctx.ownershipPlans != null && !ctx.ownershipPlans.isEmpty();
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

    private static void applyPositionedInlineTextFrameAdvance(
            ResolvedBuildContext ctx,
            String carrierStoryId,
            ASTParagraph paragraph,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || paragraph == null || anchoredObjectId <= 0) return;
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)) return;
        if (!paragraphPrefixIsLayoutOnly(paragraph)) return;

        ResolvedTextFrame anchor = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
        if (anchor == null || !anchor.isInline()) return;

        ResolvedTextFrame owner = ownerTextFrameForInlineAnchor(ctx, carrierStoryId, anchor);
        if (owner == null) return;

        double[] anchorBounds = preferredBounds(anchor);
        double[] ownerBounds = preferredBounds(owner);
        if (!validBounds(anchorBounds) || !validBounds(ownerBounds)) return;

        double ownerLeft = ownerBounds[1];
        double ownerRight = ownerBounds[3];
        double anchorLeft = anchorBounds[1];
        double anchorCenterY = (anchorBounds[0] + anchorBounds[2]) * 0.5;
        if (anchorLeft < ownerLeft - 0.5 || anchorLeft > ownerRight + 0.5) return;
        if (anchorCenterY < ownerBounds[0] - 1.0 || anchorCenterY > ownerBounds[2] + 1.0) return;

        double sourceAdvancePt = anchorLeft - ownerLeft - estimatedVisiblePrefixWidthPt(paragraph);
        double ownerWidth = Math.max(0.0, ownerRight - ownerLeft);
        if (sourceAdvancePt < 2.0 || sourceAdvancePt > ownerWidth - 1.0) return;

        long sourceAdvance = CoordinateConverter.pointsToHwpunits(sourceAdvancePt);
        Long existing = paragraph.leftMargin();
        if (existing == null || sourceAdvance > existing + CoordinateConverter.pointsToHwpunits(1.0)) {
            paragraph.leftMargin(sourceAdvance);
        }
    }

    private static ResolvedTextFrame ownerTextFrameForInlineAnchor(
            ResolvedBuildContext ctx,
            String carrierStoryId,
            ResolvedTextFrame anchor) {
        if (ctx == null || ctx.resolvedData == null || anchor == null || carrierStoryId == null) {
            return null;
        }
        double[] anchorBounds = preferredBounds(anchor);
        double anchorCenterX = validBounds(anchorBounds) ? (anchorBounds[1] + anchorBounds[3]) * 0.5 : Double.NaN;
        double anchorCenterY = validBounds(anchorBounds) ? (anchorBounds[0] + anchorBounds[2]) * 0.5 : Double.NaN;

        ResolvedTextFrame best = null;
        double bestArea = Double.POSITIVE_INFINITY;
        for (ResolvedTextFrame candidate : ctx.resolvedData.getTextFramesForStory(carrierStoryId)) {
            if (candidate == null || candidate.isInline()) continue;
            if (anchor.pageIndex() >= 0 && candidate.pageIndex() >= 0
                    && anchor.pageIndex() != candidate.pageIndex()) {
                continue;
            }
            double[] b = preferredBounds(candidate);
            if (!validBounds(b)) continue;
            if (Double.isFinite(anchorCenterX) && Double.isFinite(anchorCenterY)
                    && (anchorCenterX < b[1] - 0.5 || anchorCenterX > b[3] + 0.5
                    || anchorCenterY < b[0] - 1.0 || anchorCenterY > b[2] + 1.0)) {
                continue;
            }
            double area = Math.max(0.0, b[2] - b[0]) * Math.max(0.0, b[3] - b[1]);
            if (area < bestArea) {
                best = candidate;
                bestArea = area;
            }
        }
        return best;
    }

    private static double[] preferredBounds(ResolvedTextFrame tf) {
        if (tf == null) return null;
        double[] pageBounds = tf.pageRelativeBounds();
        if (validBounds(pageBounds)) return pageBounds;
        return tf.geometricBounds();
    }

    private static boolean validBounds(double[] bounds) {
        return bounds != null && bounds.length >= 4
                && Double.isFinite(bounds[0]) && Double.isFinite(bounds[1])
                && Double.isFinite(bounds[2]) && Double.isFinite(bounds[3])
                && bounds[2] >= bounds[0] && bounds[3] >= bounds[1];
    }

    private static boolean paragraphPrefixIsLayoutOnly(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return true;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) return false;
            if (hasSubstantiveText(((ASTTextRun) item).text())) return false;
        }
        return true;
    }

    private static boolean hasSubstantiveText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008'
                    || ch == '\t' || ch == '\r' || ch == '\n'
                    || Character.isWhitespace(ch)) {
                continue;
            }
            if (Character.isLetterOrDigit(ch)) return true;
        }
        return false;
    }

    private static double estimatedVisiblePrefixWidthPt(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return 0.0;
        double width = 0.0;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text == null || text.isEmpty()) continue;
            double fontPt = run.fontSizeHwpunits() != null && run.fontSizeHwpunits() > 0
                    ? run.fontSizeHwpunits() / 100.0
                    : 10.0;
            double scale = run.horizontalScale() != null && run.horizontalScale() > 0
                    ? run.horizontalScale() / 100.0
                    : 1.0;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008'
                        || ch == '\t' || ch == '\r' || ch == '\n') {
                    continue;
                }
                width += Character.isWhitespace(ch) ? fontPt * 0.25 * scale : fontPt * 0.5 * scale;
            }
        }
        return width;
    }

    private static void warnUnplannedInlineAnchorSkipped(
            ResolvedBuildContext ctx,
            String storyId,
            int anchoredId) {
        if (ctx == null) return;
        ctx.ownershipWarningLines.add("{\"code\":\"STAGE2_UNPLANNED_INLINE_ANCHOR_SKIPPED\""
                + ",\"storyId\":\"" + ObjectPlan.escape(storyId) + "\""
                + ",\"anchoredObjectId\":" + anchoredId
                + ",\"detail\":\"StoryLoader did not synthesize inline material without a Stage 1 ObjectPlan\"}");
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
                && !hasCellOwnedNestedStoryRef(ctx, idmlCell)
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
                removeDuplicateInlineObjectsFromCellFlow(ctx, resolvedCellParagraphs);
                return resolvedCellParagraphs;
            }
        }
        if (resolvedCell != null
                && resolvedCell.hasTextRuns()) {
            List<ASTParagraph> resolvedCellParagraphs =
                    astParagraphsFromResolvedCell(ctx, idmlTable, idmlCell);
            if (resolvedCellParagraphs != null && !resolvedCellParagraphs.isEmpty()) {
                removeDuplicateInlineObjectsFromCellFlow(ctx, resolvedCellParagraphs);
                return resolvedCellParagraphs;
            }
        }
        ResolvedStory cellResolvedStory = findResolvedStoryForCell(ctx, idmlCell, cellStoryId);
        String effectiveCellStoryId = cellStoryId != null ? cellStoryId : firstCellStoryId(idmlCell);
        int paraIndex = 0;
        for (IDMLParagraph ip : idmlCell.paragraphs()) {
            if (ip == null) { paraIndex++; continue; }
            ASTParagraph para = new ASTParagraph();
            ResolvedParagraph resolvedParagraph = (cellResolvedStory != null
                    && cellResolvedStory.paragraphs() != null
                    && paraIndex < cellResolvedStory.paragraphs().size())
                    ? cellResolvedStory.paragraphs().get(paraIndex)
                    : null;
            if (resolvedParagraph == null
                    && resolvedCell != null
                    && resolvedCell.paragraphs() != null
                    && paraIndex < resolvedCell.paragraphs().size()) {
                resolvedParagraph = resolvedCell.paragraphs().get(paraIndex);
            }
            if (ip.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(ip.appliedParagraphStyle());
            }
            ParagraphPropertyResolver.apply(para, ip, resolvedParagraph, ctx, null);
            StoryConverter.StyleContext sc = styleContextFor(ctx, ip.appliedParagraphStyle());
            sc.hasTabStops = para.hasTabStops();
            List<ResolvedRun> resolvedRuns = resolvedParagraph != null ? resolvedParagraph.runs() : null;
            resolvedRuns = splitCellMarkerBodyRunFromSiblingStyle(resolvedCell, paraIndex, resolvedRuns);
            buildParagraphContent(ctx, ip, resolvedParagraph, resolvedRuns,
                    effectiveCellStoryId, paraIndex, sc, para);
            MathProcessor.convertMathRunsInParagraph(ctx, para);
            result.add(para);
            ConversionTiming.addCounter("phase3.storyLoader.cell.paragraphs", 1);
            paraIndex++;
        }
        applyResolvedCellPlannedInlineAnchors(ctx, idmlCell, resolvedCell, result);
        removeDuplicateInlineObjectsFromCellFlow(ctx, result);
        for (ASTParagraph para : result) {
            MathProcessor.convertMathRunsInParagraph(ctx, para);
            recordCellInlineEmbeddedIds(ctx, para);
        }
        if (!hasMeaningfulCellParagraphContent(result)
                && idmlCell.textFrameStoryRefs() != null
                && !idmlCell.textFrameStoryRefs().isEmpty()) {
            return new ArrayList<>();
        }
        return result;
    }

    private static List<ResolvedRun> splitCellMarkerBodyRunFromSiblingStyle(
            ResolvedTable.Cell resolvedCell,
            int paraIndex,
            List<ResolvedRun> runs) {
        if (resolvedCell == null || runs == null || runs.size() != 1) return runs;
        ResolvedRun run = runs.get(0);
        String text = run != null ? run.text() : null;
        int splitIndex = markerBodySplitIndex(text);
        if (splitIndex <= 0 || splitIndex >= text.length()) return runs;

        String markerText = text.substring(0, splitIndex);
        String bodyText = text.substring(splitIndex);
        if (!isCompactCellMarker(markerText) || bodyText.trim().isEmpty()) return runs;

        ResolvedRun bodyStyle = findSiblingBodyRunStyle(resolvedCell, paraIndex, run);
        if (bodyStyle == null || sameCoreTextStyle(run, bodyStyle)) return runs;

        List<ResolvedRun> split = new ArrayList<>();
        ResolvedRun marker = copyResolvedRun(run);
        marker.text(markerText);
        split.add(marker);

        ResolvedRun body = copyResolvedRun(bodyStyle);
        body.text(bodyText);
        split.add(body);
        return split;
    }

    private static int markerBodySplitIndex(String text) {
        if (text == null) return -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\u2007' || ch == '\u2002' || ch == '\u2003') {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean isCompactCellMarker(String text) {
        if (text == null) return false;
        String t = text.replace("\u2007", "")
                .replace("\u2002", "")
                .replace("\u2003", "")
                .trim();
        if (t.isEmpty() || t.length() > 8) return false;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) return false;
        }
        return true;
    }

    private static ResolvedRun findSiblingBodyRunStyle(
            ResolvedTable.Cell resolvedCell,
            int paraIndex,
            ResolvedRun markerRun) {
        if (resolvedCell.paragraphs() == null) return null;
        for (int i = paraIndex - 1; i >= 0; i--) {
            ResolvedRun found = firstBodyStyleCandidate(resolvedCell.paragraphs().get(i), markerRun);
            if (found != null) return found;
        }
        for (int i = paraIndex + 1; i < resolvedCell.paragraphs().size(); i++) {
            ResolvedRun found = firstBodyStyleCandidate(resolvedCell.paragraphs().get(i), markerRun);
            if (found != null) return found;
        }
        return null;
    }

    private static ResolvedRun firstBodyStyleCandidate(ResolvedParagraph paragraph, ResolvedRun markerRun) {
        if (paragraph == null || paragraph.runs() == null) return null;
        for (ResolvedRun candidate : paragraph.runs()) {
            String text = candidate != null ? candidate.text() : null;
            if (text == null || text.trim().length() < 6) continue;
            if (isCompactCellMarker(text)) continue;
            if (sameCoreTextStyle(candidate, markerRun)) continue;
            return candidate;
        }
        return null;
    }

    private static boolean sameCoreTextStyle(ResolvedRun a, ResolvedRun b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return eq(a.fontFamily(), b.fontFamily())
                && eq(a.fontStyle(), b.fontStyle())
                && eq(a.fillColor(), b.fillColor())
                && eq(a.fontSize(), b.fontSize())
                && eq(a.tracking(), b.tracking())
                && eq(a.horizontalScale(), b.horizontalScale());
    }

    private static boolean eq(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static ResolvedRun copyResolvedRun(ResolvedRun source) {
        ResolvedRun copy = new ResolvedRun();
        if (source == null) return copy;
        copy.text(source.text());
        copy.fontFamily(source.fontFamily());
        copy.fontSize(source.fontSize());
        copy.fontStyle(source.fontStyle());
        copy.fillColor(source.fillColor());
        copy.charStyle(source.charStyle());
        copy.tracking(source.tracking());
        copy.horizontalScale(source.horizontalScale());
        copy.verticalScale(source.verticalScale());
        copy.baselineShift(source.baselineShift());
        copy.position(source.position());
        copy.underline(source.underline());
        copy.strikeThru(source.strikeThru());
        copy.type(source.type());
        copy.anchoredObjectId(source.anchoredObjectId());
        copy.storyAnchorPlacement(source.storyAnchorPlacement());
        return copy;
    }

    private static int removeDuplicateInlineObjectsFromCellFlow(
            ResolvedBuildContext ctx,
            List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return 0;
        Set<String> seenSourceIds = new HashSet<>();
        int removed = 0;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null || paragraph.items().isEmpty()) continue;
            Iterator<ASTInlineItem> it = paragraph.items().iterator();
            while (it.hasNext()) {
                ASTInlineItem item = it.next();
                if (!(item instanceof ASTInlineObject)) continue;
                String key = inlineObjectSourceKey((ASTInlineObject) item);
                if (key == null) continue;
                if (!seenSourceIds.add(key)) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            ConversionTiming.addCounter("phase3.storyLoader.cell.duplicateInlineObjectsRemoved", removed);
            if (ctx != null && ctx.debugAst) {
                System.err.println("[StoryLoader] removed duplicate inline objects from cell flow: " + removed);
            }
        }
        return removed;
    }

    private static String inlineObjectSourceKey(ASTInlineObject obj) {
        if (obj == null || obj.sourceId() == null || obj.sourceId().isEmpty()) return null;
        return obj.sourceId();
    }

    private static ResolvedStory findResolvedStoryForCell(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell,
            String explicitStoryId) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null) return null;
        if (explicitStoryId != null) {
            ResolvedStory explicit = ctx.resolvedData.getStory(explicitStoryId);
            if (explicit != null) return explicit;
        }
        if (idmlCell.textFrameStoryRefs() == null || idmlCell.textFrameStoryRefs().isEmpty()) {
            return null;
        }
        String cellText = normalizedCellText(idmlCell);
        ResolvedStory first = null;
        for (String storyRef : idmlCell.textFrameStoryRefs()) {
            String storyId = toDecimalStoryId(storyRef);
            ResolvedStory story = storyId != null ? ctx.resolvedData.getStory(storyId) : null;
            if (story == null && storyRef != null) {
                story = ctx.resolvedData.getStory(storyRef);
            }
            if (story == null) continue;
            if (first == null) first = story;
            String storyText = normalizedResolvedStoryText(story);
            if (!cellText.isEmpty() && !storyText.isEmpty()
                    && (cellText.equals(storyText)
                    || storyText.startsWith(cellText)
                    || cellText.startsWith(storyText))) {
                return story;
            }
        }
        return first;
    }

    private static String firstCellStoryId(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (idmlCell == null || idmlCell.textFrameStoryRefs() == null
                || idmlCell.textFrameStoryRefs().isEmpty()) {
            return null;
        }
        return toDecimalStoryId(idmlCell.textFrameStoryRefs().get(0));
    }

    private static boolean hasMeaningfulCellParagraphContent(List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return false;
        for (ASTParagraph para : paragraphs) {
            if (paragraphHasMeaningfulCellContent(para)) {
                return true;
            }
        }
        return false;
    }

    private static boolean paragraphHasMeaningfulCellContent(ASTParagraph para) {
        if (para == null) return false;
        if (para.inlineTable() != null) return true;
        if (para.items() == null || para.items().isEmpty()) return false;
        for (ASTInlineItem item : para.items()) {
            if (item == null) continue;
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !normalizeCellOwnershipText(text).isEmpty()) {
                    return true;
                }
                continue;
            }
            return true;
        }
        return false;
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
                || resolvedCell.paragraphs().isEmpty()) {
            return result;
        }
        boolean includeResolvedText = resolvedCell.hasTextRuns();

        int paraIndex = 0;
        for (ResolvedParagraph resolvedParagraph : resolvedCell.paragraphs()) {
            if (resolvedParagraph == null) { paraIndex++; continue; }
            IDMLParagraph idmlParagraph = (idmlCell.paragraphs() != null
                    && paraIndex < idmlCell.paragraphs().size())
                    ? idmlCell.paragraphs().get(paraIndex)
                    : null;
            List<ResolvedRun> resolvedRuns = splitCellMarkerBodyRunFromSiblingStyle(
                    resolvedCell, paraIndex, resolvedParagraph.runs());
            for (ASTParagraph para : buildResolvedCellParagraphs(
                    ctx, idmlParagraph, resolvedParagraph, resolvedRuns, includeResolvedText)) {
                MathProcessor.convertMathRunsInParagraph(ctx, para);
                RunPostProcessor.splitOverlineRuns(para);
                RunPostProcessor.convertItalicRunsToEquations(para);
                RunBuilder.resetBulletParagraphColors(ctx, para);
                recordCellInlineEmbeddedIds(ctx, para);
                if (para.items() != null && !para.items().isEmpty()) {
                    result.add(para);
                }
            }
            paraIndex++;
        }
        return result;
    }

    /**
     * 표 셀의 resolved 런에 IDML 이 <em>직접 명시한</em> 글자속성을 채운다.
     *
     * <p>셀 런은 텍스트만 담는다 — SPEC-067 이 추출기의 DOM 글자속성을 차단한 뒤로 색·폰트의
     * 출처가 사라져 빨간 산세리프 제목이 검정 기본 폰트로 렌더된다. 속성은 IDML 에 있으므로
     * AST 런을 만들기 전에 옮겨 담는다.
     *
     * <p>단 <b>런 태그가 직접 명시한 값만</b> 옮긴다. 파서는 문단/문자 스타일에서 상속한
     * 값도 같은 필드에 채우는데, 그 상속값까지 런에 실으면 해당 런이 문단 CharPr 상속을
     * 잃고 자체 CharPr(기본 10pt)을 갖게 되어 크기가 무너진다. 실제로 상속 폰트를 함께
     * 옮겼을 때 20페이지에서 크기 112건이 어긋났다 (SPEC-072).</p>
     */
    private static List<ResolvedRun> withExplicitIdmlCharacterAttributes(
            ResolvedBuildContext ctx, IDMLParagraph idmlParagraph, List<ResolvedRun> runs) {
        if (idmlParagraph == null || idmlParagraph.characterRuns() == null || runs == null) {
            return runs;
        }
        List<IDMLCharacterRun> idmlRuns = idmlParagraph.characterRuns();
        if (idmlRuns.isEmpty()) return runs;

        Double paraFontSize = paragraphStyleFontSize(ctx, idmlParagraph);
        List<ResolvedRun> out = new ArrayList<>(runs.size());
        int offset = 0;
        for (ResolvedRun run : runs) {
            if (run == null || run.isInlineAnchor() || run.text() == null) {
                out.add(run);
                continue;
            }
            IDMLCharacterRun cr = idmlRunAtOffset(idmlRuns, offset, run.text());
            out.add(cr == null ? run : runWithExplicitAttributes(run, cr, paraFontSize));
            offset += run.text().length();
        }
        return out;
    }

    /**
     * 문단 텍스트에서 {@code offset} 위치를 덮는 IDML 문자 런. 오프셋이 어긋나면
     * (추출기와 IDML 의 제어문자 처리 차이) 텍스트로 맞춘다.
     */
    private static IDMLCharacterRun idmlRunAtOffset(
            List<IDMLCharacterRun> idmlRuns, int offset, String runText) {
        int cursor = 0;
        for (IDMLCharacterRun cr : idmlRuns) {
            if (cr == null || cr.content() == null) continue;
            int len = cr.content().length();
            if (len <= 0) continue;
            if (offset >= cursor && offset < cursor + len) return cr;
            cursor += len;
        }
        String needle = runText != null ? runText.trim() : "";
        if (needle.isEmpty()) return null;
        for (IDMLCharacterRun cr : idmlRuns) {
            if (cr == null || cr.content() == null) continue;
            if (cr.content().contains(needle)) return cr;
        }
        return null;
    }

    /**
     * 이 문단의 스타일이 정한 글자 크기. 런이 자체 CharPr 을 갖게 될 때 이 값을 함께
     * 실어야 크기가 하드코딩 기본값(10pt)으로 떨어지지 않는다.
     */
    private static Double paragraphStyleFontSize(ResolvedBuildContext ctx, IDMLParagraph p) {
        if (ctx == null || ctx.styleResolver == null || p == null) return null;
        String ref = p.appliedParagraphStyle();
        if (ref == null) return null;
        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef def =
                ctx.styleResolver.getResolvedParagraphStyle(ref);
        if (def == null && ref.startsWith("ParagraphStyle/")) {
            def = ctx.styleResolver.getResolvedParagraphStyle(
                    ref.substring("ParagraphStyle/".length()));
        }
        return def != null ? def.fontSize() : null;
    }

    /** resolved 런 사본에 IDML 명시 속성만 채운다. 이미 값이 있으면 그대로 둔다. */
    private static ResolvedRun runWithExplicitAttributes(
            ResolvedRun run, IDMLCharacterRun cr, Double paraFontSize) {
        boolean takeColor = cr.fillColorExplicit() && cr.fillColor() != null
                && run.fillColor() == null;
        boolean takeFont = cr.fontFamilyExplicit() && cr.fontFamily() != null
                && run.fontFamily() == null;
        boolean takeSize = cr.fontSizeExplicit() && cr.fontSize() != null && cr.fontSize() > 0
                && run.fontSize() == null;
        if (!takeColor && !takeFont && !takeSize) return run;

        ResolvedRun copy = new ResolvedRun();
        copy.text(run.text());
        copy.fontFamily(run.fontFamily());
        copy.fontSize(run.fontSize());
        copy.fontStyle(run.fontStyle());
        copy.fillColor(run.fillColor());
        copy.charStyle(run.charStyle());
        copy.tracking(run.tracking());
        copy.horizontalScale(run.horizontalScale());
        copy.verticalScale(run.verticalScale());
        copy.baselineShift(run.baselineShift());
        copy.position(run.position());
        copy.underline(run.underline());
        copy.strikeThru(run.strikeThru());
        copy.type(run.type());
        copy.anchoredObjectId(run.anchoredObjectId());
        copy.storyAnchorPlacement(run.storyAnchorPlacement());

        if (takeColor) copy.fillColor(cr.fillColor());
        if (takeFont) copy.fontFamily(cr.fontFamily());
        if (takeSize) copy.fontSize(cr.fontSize());
        // 속성을 실은 런은 자체 CharPr 을 갖게 되는데, 그 경로가 크기를 하드코딩
        // 기본값(10pt)으로 채운다. 런에도 IDML 에도 크기가 없으면 문단 스타일이 정한
        // 크기를 실어 원래 크기를 지킨다 (p10 제목 12pt).
        if (copy.fontSize() == null && paraFontSize != null && paraFontSize > 0) {
            copy.fontSize(paraFontSize);
        }
        return copy;
    }

    private static List<ASTParagraph> buildResolvedCellParagraphs(
            ResolvedBuildContext ctx,
            IDMLParagraph idmlParagraph,
            ResolvedParagraph resolvedParagraph,
            List<ResolvedRun> resolvedRuns,
            boolean includeTextRuns) {
        List<ASTParagraph> paragraphs = new ArrayList<>();
        if (ctx == null || resolvedParagraph == null) return paragraphs;

        ASTParagraph current = createResolvedCellParagraph(ctx, idmlParagraph, resolvedParagraph);
        appendLeadingAnchorOnlyRuns(ctx, idmlParagraph, resolvedParagraph, current);
        if (includeTextRuns) {
            appendGeneratedParagraphPrefix(ctx, resolvedParagraph, idmlParagraph, current);
        }

        ResolvedTextFlowAstConverter.Options options = ResolvedTextFlowAstConverter.options()
                .colorResolver(color -> ctx.resolvedData != null ? ctx.resolvedData.resolveColorHex(color) : color)
                .truncateAtParagraphBreak(false);
        List<ResolvedRun> runs = resolvedRuns != null ? resolvedRuns : resolvedParagraph.runs();
        if (runs == null || runs.isEmpty()) {
            addNonEmptyParagraph(paragraphs, current);
            return paragraphs;
        }
        runs = withExplicitIdmlCharacterAttributes(ctx, idmlParagraph, runs);

        for (int i = 0; i < runs.size(); i++) {
            ResolvedRun run = runs.get(i);
            if (run == null) continue;
            if (run.isInlineAnchor()) {
                appendResolvedInlineAnchorInOrder(ctx, idmlParagraph, runs, i, current);
                continue;
            }
            if (!includeTextRuns || run.text() == null) continue;

            String text = run.text();
            int start = 0;
            while (start <= text.length()) {
                int breakAt = text.indexOf('\r', start);
                String segment = breakAt >= 0 ? text.substring(start, breakAt) : text.substring(start);
                // 화살표 런: 텍스트는 ResolvedDataReader 가 파싱 직후 이미 "→" 로
                // 정규화했다. 여기서는 폰트만 벗긴다 — BT화살표 폰트를 그대로 두면
                // 한글이 글리프를 렌더링하지 못한다.
                boolean arrowRun = BTFontGlyphMap.isBTArrowFont(run.fontFamily());
                for (ASTTextRun textRun : ResolvedTextFlowAstConverter.convertRunText(segment, run, current, options)) {
                    if (arrowRun) {
                        textRun.fontFamily(null);
                        textRun.fontStyle(null);
                        textRun.grepMathFont(false);
                        textRun.subscript(false);
                        textRun.superscript(false);
                    }
                    current.addItem(textRun);
                }
                if (breakAt < 0) break;
                addNonEmptyParagraph(paragraphs, current);
                current = createResolvedCellParagraph(ctx, idmlParagraph, resolvedParagraph);
                start = breakAt + 1;
            }
        }

        addNonEmptyParagraph(paragraphs, current);

        // SPEC-067: 셀 문단 경로(buildResolvedCellParagraphs)는 RunBuilder 색 우선순위
        // (SPEC-065)를 안 거치고 resolved run 의 fillColor 를 그대로 쓴다. resolved DOM 이
        // 앞 문단 색(예: 대화 선지의 녹색)을 뒤 영문 런에 흘려 오보고하면, IDML 에 아무
        // 색 근거가 없는데도 그 유채색이 그대로 렌더된다 (영어 u3 p56 "A That happens…"
        // 전체가 녹색). IDML 문자 스타일·GREP·로컬 FillColor 어디에도 없는 유채색은
        // 문단 기본색(검정)으로 되돌린다.
        for (ASTParagraph p : paragraphs) {
            revertUnbackedResolvedCellColors(ctx, idmlParagraph, p);
        }
        return paragraphs;
    }

    /**
     * SPEC-067: 셀 문단에서 IDML 색 근거가 없는 resolved 유채색을 검정으로 되돌린다.
     *
     * <p>근거 집합 = 이 IDML 문단의 모든 문자 런이 실제로 지닌 색(로컬 FillColor +
     * GREP 적용 문자 스타일 색 + 적용 문자 스타일 색). 빌드된 ASTTextRun 색이 검정·흰색이
     * 아니면서 이 근거 집합에 없으면 resolved DOM 이 흘려보낸 오색이므로 검정으로 되돌린다.
     * 근거 있는 색자(과학 표의 @색자 등)는 근거 집합에 들어 있어 보존된다.</p>
     */
    private static void revertUnbackedResolvedCellColors(
            ResolvedBuildContext ctx, IDMLParagraph idmlParagraph, ASTParagraph para) {
        if (ctx == null || para == null || para.items() == null) return;
        java.util.Set<String> evidence = collectIdmlColorEvidence(ctx, idmlParagraph);
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun tr = (ASTTextRun) item;
            String c = tr.textColor();
            if (c == null) continue;
            String up = c.trim().toUpperCase(java.util.Locale.ROOT);
            if (up.isEmpty() || "#000000".equals(up) || "#FFFFFF".equals(up)) continue;
            // 채도 높은 유채색만 대상. #1A1A1A 같은 근흑·회색 본문색은 DOM 이
            // 보고한 것이라도 정상 렌더값이므로 건드리지 않는다 (K90 등). 오색 누출은
            // 선명한 색(녹색 #67B755 등)이라 채도로 구분된다.
            if (!isSaturatedChromatic(up)) continue;
            if (!evidence.contains(up)) {
                tr.textColor(null);   // 문단 기본색(검정)으로 복귀
            }
        }
    }

    /**
     * hex 색이 "선명한 유채색"인가 — HSV 채도·명도 기준. 근흑/회색(낮은 채도)과
     * 아주 어두운 색은 본문색일 가능성이 높아 제외한다. 누출되는 오색(선지 녹색 등)은
     * 채도가 높다.
     */
    static boolean isSaturatedChromatic(String hex) {
        if (hex == null) return false;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return false;
        int r, g, b;
        try {
            r = Integer.parseInt(h.substring(0, 2), 16);
            g = Integer.parseInt(h.substring(2, 4), 16);
            b = Integer.parseInt(h.substring(4, 6), 16);
        } catch (NumberFormatException e) { return false; }
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 64) return false;              // 너무 어두움(근흑) → 본문색
        double saturation = max == 0 ? 0 : (double) (max - min) / max;
        return saturation >= 0.25;               // 채도 25%↑ 만 유채색으로 본다
    }

    /** IDML 문단의 색 근거 집합(로컬 FillColor + GREP/적용 문자 스타일 색), 대문자 hex. */
    private static java.util.Set<String> collectIdmlColorEvidence(
            ResolvedBuildContext ctx, IDMLParagraph idmlParagraph) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (idmlParagraph == null || idmlParagraph.characterRuns() == null) return out;
        for (IDMLCharacterRun cr : idmlParagraph.characterRuns()) {
            if (cr == null) continue;
            addColorEvidence(ctx, out, cr.fillColor());
            addStyleColorEvidence(ctx, out, cr.grepAppliedCharStyle());
            String applied = cr.appliedCharacterStyle();
            if (applied != null && !applied.contains("[No character style]")) {
                addStyleColorEvidence(ctx, out, applied);
            }
        }
        return out;
    }

    private static void addStyleColorEvidence(
            ResolvedBuildContext ctx, java.util.Set<String> out, String styleRef) {
        if (styleRef == null || ctx.styleResolver == null) return;
        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef def =
                ctx.styleResolver.getResolvedCharacterStyle(styleRef);
        if (def == null && styleRef.startsWith("CharacterStyle/")) {
            def = ctx.styleResolver.getResolvedCharacterStyle(
                    styleRef.substring("CharacterStyle/".length()));
        }
        if (def != null) addColorEvidence(ctx, out, def.fillColor());
    }

    private static void addColorEvidence(
            ResolvedBuildContext ctx, java.util.Set<String> out, String color) {
        if (color == null || color.trim().isEmpty()) return;
        String hex = RunBuilder.resolveColorToHex(ctx, color);
        if (hex != null && !hex.trim().isEmpty()) {
            out.add(hex.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static ASTParagraph createResolvedCellParagraph(
            ResolvedBuildContext ctx,
            IDMLParagraph idmlParagraph,
            ResolvedParagraph resolvedParagraph) {
        ASTParagraph para = new ASTParagraph();
        if (idmlParagraph != null) {
            if (idmlParagraph.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(idmlParagraph.appliedParagraphStyle());
            }
            ParagraphPropertyResolver.apply(para, idmlParagraph, resolvedParagraph, ctx, null);
        } else {
            applyResolvedParagraphPropertiesOnly(para, resolvedParagraph);
        }
        return para;
    }

    private static void appendLeadingAnchorOnlyRuns(
            ResolvedBuildContext ctx,
            IDMLParagraph idmlParagraph,
            ResolvedParagraph resolvedParagraph,
            ASTParagraph para) {
        if (idmlParagraph == null || idmlParagraph.characterRuns() == null || para == null) return;
        List<IDMLCharacterRun> runs = idmlParagraph.characterRuns();
        for (int i = 0; i < runs.size(); i++) {
            IDMLCharacterRun run = runs.get(i);
            if (hasMeaningfulRunText(run)) return;
            appendAnchorOnlyRunItems(ctx, runs, i, run, resolvedParagraph, idmlParagraph, null, para);
        }
    }

    private static boolean hasMeaningfulRunText(IDMLCharacterRun run) {
        String text = run != null ? run.content() : null;
        if (text == null || text.isEmpty()) return false;
        return !text.replace("\uFFFC", "").trim().isEmpty();
    }

    private static boolean paragraphContainsInlineAnchor(IDMLParagraph paragraph, int anchorId) {
        if (paragraph == null || paragraph.characterRuns() == null || anchorId <= 0) return false;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null) continue;
            if (containsInlineAnchorDomId(run, anchorId)) return true;
            if (containsInlineGraphicDomId(run.inlineGraphics(), anchorId)) return true;
            if (containsInlineFrameDomId(run.inlineFrames(), anchorId)) return true;
        }
        return false;
    }

    private static void addNonEmptyParagraph(List<ASTParagraph> paragraphs, ASTParagraph para) {
        if (paragraphs == null || para == null || para.items() == null || para.items().isEmpty()) return;
        paragraphs.add(para);
    }

    private static void appendResolvedInlineAnchorInOrder(
            ResolvedBuildContext ctx,
            IDMLParagraph idmlParagraph,
            List<ResolvedRun> runs,
            int runIndex,
            ASTParagraph para) {
        if (ctx == null || runs == null || runIndex < 0 || runIndex >= runs.size() || para == null) {
            return;
        }
        ResolvedRun run = runs.get(runIndex);
        if (run == null || !run.isInlineAnchor()) return;
        Integer anchoredId = run.anchoredObjectId();
        if (anchoredId == null || anchoredId <= 0) return;
        if (!isResolvedCellPlannedInlineAnchor(ctx, anchoredId)) {
            return;
        }
        String previousText = nearestResolvedText(runs, runIndex - 1, -1);
        String nextText = nearestResolvedText(runs, runIndex + 1, 1);
        List<ASTInlineItem> plannedItems =
                InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, anchoredId, previousText, nextText);
        if (plannedItems == null) return;
        InlineFrameHandler.applyClosedInlineCarrierTextAlignment(ctx, anchoredId, para);
        appendInlineItemsKeepingObjectsInline(para, plannedItems);
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
        ResolvedTable.Cell cell = resolvedTable != null
                ? resolvedTable.cellAt(idmlCell.rowIndex(), idmlCell.columnIndex())
                : null;
        if (cell != null && resolvedCellTextCompatible(idmlCell, cell)) return cell;
        return findResolvedCellByText(ctx, idmlCell);
    }

    private static boolean resolvedCellTextCompatible(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell,
            ResolvedTable.Cell cell) {
        String idmlText = normalizedCellText(idmlCell);
        String resolvedText = normalizedResolvedCellText(cell);
        if (idmlText.isEmpty() || resolvedText.isEmpty()) return true;
        return idmlText.equals(resolvedText)
                || resolvedText.startsWith(idmlText)
                || idmlText.startsWith(resolvedText);
    }

    private static ResolvedTable.Cell findResolvedCellByText(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        String cellText = normalizedCellText(idmlCell);
        if (cellText.isEmpty() || ctx == null || ctx.resolvedData == null) return null;
        for (ResolvedTable table : ctx.resolvedData.tables()) {
            if (table == null || table.cells() == null) continue;
            for (ResolvedTable.Cell cell : table.cells()) {
                String resolvedText = normalizedResolvedCellText(cell);
                if (resolvedText.isEmpty()) continue;
                if (cellText.equals(resolvedText)
                        || resolvedText.startsWith(cellText)
                        || cellText.startsWith(resolvedText)) {
                    return cell;
                }
            }
        }
        return null;
    }

    private static String normalizedResolvedCellText(ResolvedTable.Cell cell) {
        if (cell == null || cell.paragraphs() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor()) continue;
                sb.append(normalizeCellOwnershipText(run.text()));
            }
        }
        return sb.toString();
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
            appendGeneratedParagraphPrefix(ctx, resolvedParagraph, null, para);
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
                if (!isResolvedCellPlannedInlineAnchor(ctx, anchoredId)) {
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
                continue;
            }

            if (!includeTextRuns) continue;
            String text = run.text();
            if (text == null) continue;

            // 화살표 런: 텍스트는 ResolvedDataReader 가 파싱 직후 이미 "→" 로
            // 정규화했다. 여기서는 폰트만 벗긴다 — BT화살표 폰트를 그대로 두면
            // 한글이 글리프를 렌더링하지 못한다.
            boolean arrowRun = BTFontGlyphMap.isBTArrowFont(run.fontFamily());
            for (ASTTextRun textRun : ResolvedTextFlowAstConverter.convertRunText(text, run, para, options)) {
                if (arrowRun) {
                    textRun.fontFamily(null);
                    textRun.fontStyle(null);
                    textRun.grepMathFont(false);
                    textRun.subscript(false);
                    textRun.superscript(false);
                }
                para.addItem(textRun);
            }
            if (text.indexOf('\r') >= 0) break;
        }
    }

    private static void appendGeneratedParagraphPrefix(
            ResolvedBuildContext ctx,
            ResolvedParagraph resolvedParagraph,
            IDMLParagraph idmlParagraph,
            ASTParagraph para) {
        if (ctx == null || resolvedParagraph == null || para == null) return;
        String prefix = ResolvedTextFlowAstConverter.generatedPrefixToInsert(resolvedParagraph);
        if (prefix == null || prefix.trim().isEmpty()) return;
        ResolvedRun styleRun = ResolvedTextFlowAstConverter.firstVisibleResolvedRun(resolvedParagraph);
        if (styleRun == null) return;
        ResolvedRun markerRun = copyResolvedRun(styleRun);
        String paraStyleRef = idmlParagraph != null && idmlParagraph.appliedParagraphStyle() != null
                ? idmlParagraph.appliedParagraphStyle()
                : resolvedParagraph.styleName();
        SourceTextStyleResolver.applyGeneratedBulletStyle(ctx, markerRun, paraStyleRef);
        ResolvedTextFlowAstConverter.Options options = ResolvedTextFlowAstConverter.options()
                .colorResolver(color -> ctx.resolvedData != null ? ctx.resolvedData.resolveColorHex(color) : color)
                .truncateAtParagraphBreak(false);
        if (!prefix.isEmpty() && markerRun.charStyle() != null) {
            int markerEnd = prefix.offsetByCodePoints(0, 1);
            for (ASTTextRun run : ResolvedTextFlowAstConverter.convertRunText(
                    prefix.substring(0, markerEnd), markerRun, para, options)) {
                para.addItem(run);
            }
            if (markerEnd < prefix.length()) {
                for (ASTTextRun run : ResolvedTextFlowAstConverter.convertRunText(
                        prefix.substring(markerEnd), styleRun, para, options)) {
                    para.addItem(run);
                }
            }
        } else {
            for (ASTTextRun run : ResolvedTextFlowAstConverter.convertRunText(prefix, styleRun, para, options)) {
                para.addItem(run);
            }
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
        if (!normalizedStyle.contains("화살표")) return false;
        // 화살표 폰트/스타일이면 글리프 코드가 무엇이든 화살표다.
        //
        // 문서마다 코드가 다르다(관측: "@C", "?C", 그리고 접두문자 없는 "C").
        // 예전에는 "@C"/"?C" 만 인정해서, 접두문자 없는 "C" 를 쓰는 문단은
        // 화살표 자리에 글자 C 가 그대로 박혔다("CaO+H₂O C Ca(OH)₂").
        String cleaned = text.trim();
        return !cleaned.isEmpty();
    }

    /**
     * 문단의 마지막 항목이 이미 화살표 텍스트 런인가.
     *
     * <p>BT화살표 글리프가 여러 IDML 런으로 쪼개져 들어오는 경우가 있어
     * (실측: "@C" 하나가 아니라 "@" + "C" 로 분리), 각 조각마다 화살표를 넣으면
     * "→→" 가 된다. 중복 삽입을 막는 데 쓴다.
     */
    private static boolean lastItemIsArrow(ASTParagraph para) {
        if (para == null || para.items() == null || para.items().isEmpty()) return false;
        ASTInlineItem last = para.items().get(para.items().size() - 1);
        if (!(last instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) last).text();
        return text != null && "→".equals(text.trim());
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

    private static boolean isResolvedCellPlannedInlineAnchor(
            ResolvedBuildContext ctx,
            int anchoredId) {
        if (ctx != null && ctx.ownershipPlanPlacesInlineHwpxText(anchoredId)) return true;
        if (isResolvedCellPlannedLayoutOnlyInlineAnchor(ctx, anchoredId)) return true;
        return isResolvedCellPlannedInlineVisualAnchor(ctx, anchoredId);
    }

    private static boolean isResolvedCellPlannedLayoutOnlyInlineAnchor(
            ResolvedBuildContext ctx,
            int anchoredId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredId <= 0) return false;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.domId != anchoredId) continue;
            if (plan.placement != Placement.INLINE) return false;
            if (plan.visualAction != VisualAction.DROP_VISUAL) return false;
            if (plan.textAction != TextAction.DROP_TEXT) return false;
            if (plan.materialization != Materialization.HWPX_TEXT) return false;
            return plan.sourceObjectIds != null && plan.sourceObjectIds.length > 0
                    && (plan.kind != null && plan.kind.startsWith("layout_only_inline_slot:")
                    || "layout_only_inline_slot".equals(plan.slotRole)
                    || "inline_rule_below_whitespace_placeholder".equals(plan.reason));
        }
        return false;
    }

    private static boolean isResolvedCellPlannedInlineVisualAnchor(
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

    private static void applyResolvedCellPlannedInlineAnchors(
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
                if (!isResolvedCellPlannedInlineAnchor(ctx, anchoredId)) {
                    continue;
                }
                int targetIndex = paraIndex;
                if (targetIndex < 0 || targetIndex >= paragraphs.size()) continue;

                ASTParagraph target = paragraphs.get(targetIndex);
                if (paragraphsAlreadyContainInlineObject(paragraphs, anchoredId)) continue;
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

    private static boolean paragraphsAlreadyContainInlineObject(List<ASTParagraph> paragraphs, int domId) {
        if (paragraphs == null || paragraphs.isEmpty() || domId <= 0) return false;
        for (ASTParagraph para : paragraphs) {
            if (paragraphAlreadyContainsInlineObject(para, domId)) return true;
        }
        return false;
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
                    RunBuilder.getStyleFontStyle(ctx, styleRef),
                    RunBuilder.getStyleFontSize(ctx, styleRef),
                    RunBuilder.getStyleHorizontalScale(ctx, styleRef),
                    RunBuilder.getStyleUnderlineColor(ctx, styleRef));
            ctx.paragraphStyleContextCache.put(key, cached);
        }
        return new StoryConverter.StyleContext(
                cached.fillColor,
                cached.tracking,
                cached.fontFamily,
                cached.fontStyle,
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

    private static boolean isResolvedSyntheticStoryClone(ResolvedBuildContext ctx, String storyId) {
        if (ctx == null || ctx.resolvedData == null || storyId == null) return false;
        if (!storyId.contains("_pi") && !storyId.contains("_oc")) return false;
        return ctx.resolvedData.getStory(storyId) != null;
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

    private static List<ASTParagraph> splitTerminalCustomAnchoredCarriers(
            ResolvedBuildContext ctx,
            ASTParagraph para,
            String sourceParagraphAlignment) {
        List<ASTParagraph> out = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || para == null || para.items() == null) return out;
        if (!hasVisibleText(para)) return out;

        List<ASTInlineItem> items = para.items();
        int lastContentIndex = lastNonBlankItemIndex(items);
        if (lastContentIndex < 0 || !(items.get(lastContentIndex) instanceof ASTInlineObject)) return out;
        ASTInlineObject obj = (ASTInlineObject) items.get(lastContentIndex);
        if (!isTerminalCustomAnchoredEditableShell(ctx, obj)) return out;
        if (!hasVisibleTextBefore(items, lastContentIndex)) return out;

        ASTParagraph carrier = newInlineAnchorCarrierParagraph(para);
        carrier.addItem(obj);
        applyInlineCarrierLineHeight(carrier);

        for (int i = items.size() - 1; i >= lastContentIndex; i--) {
            items.remove(i);
        }
        para.alignment(sourceParagraphAlignment);
        out.add(carrier);
        return out;
    }

    private static int lastNonBlankItemIndex(List<ASTInlineItem> items) {
        if (items == null) return -1;
        for (int i = items.size() - 1; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (item == null) continue;
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text == null || text.trim().isEmpty()) continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean hasVisibleTextBefore(List<ASTInlineItem> items, int endExclusive) {
        if (items == null) return false;
        for (int i = 0; i < Math.min(endExclusive, items.size()); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && !text.trim().isEmpty()) return true;
        }
        return false;
    }

    private static boolean isTerminalCustomAnchoredEditableShell(ResolvedBuildContext ctx, ASTInlineObject obj) {
        if (obj == null || obj.kind() != ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) return false;
        if ((obj.paragraphs() == null || obj.paragraphs().isEmpty())
                && (obj.inlineTables() == null || obj.inlineTables().isEmpty())) {
            return false;
        }
        String domId = ParagraphTextHelpers.domIdFromSourceId(obj.sourceId());
        if (domId == null) return false;
        ResolvedPageItem source = ctx.resolvedData.getPageItem(domId);
        if (source == null) return false;
        return "FLOATING_ANCHORED".equalsIgnoreCase(nullToEmpty(source.storyAnchorPlacement()))
                && "ANCHORED".equalsIgnoreCase(nullToEmpty(source.anchoredPosition()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ASTParagraph newInlineAnchorCarrierParagraph(ASTParagraph sourcePara) {
        ASTParagraph carrier = new ASTParagraph();
        carrier.paragraphStyleRef(sourcePara.paragraphStyleRef());
        carrier.alignment(sourcePara.alignment());
        carrier.leftMargin(sourcePara.leftMargin());
        carrier.rightMargin(sourcePara.rightMargin());
        carrier.firstLineIndent(0L);
        carrier.spaceBefore(0L);
        carrier.spaceAfter(0L);
        carrier.keepWithNext(sourcePara.keepWithNext());
        carrier.keepLinesTogether(sourcePara.keepLinesTogether());
        return carrier;
    }

    private static void applyInlineCarrierLineHeight(ASTParagraph carrier) {
        if (carrier == null || carrier.items() == null) return;
        long maxHeight = 0;
        for (ASTInlineItem item : carrier.items()) {
            if (!(item instanceof ASTInlineObject)) continue;
            ASTInlineObject obj = (ASTInlineObject) item;
            maxHeight = Math.max(maxHeight, obj.height());
            maxHeight = Math.max(maxHeight, obj.resolvedHeight());
        }
        if (maxHeight <= 0) return;
        carrier.lineSpacing((int) Math.min(Integer.MAX_VALUE, maxHeight));
        carrier.lineSpacingType("fixed");
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
        // 원본 leader 존중: 원본에 리더가 있으면 유지, 없으면 점선을 강제로 만들지
        // 않는다. endsWithPageNumber 오판("\d{1,4}")으로 "문제 N" 같은 일반 문단에
        // 없던 점선(------)이 생기던 문제(실측: 수학교과서 "문제 N" 전부).
        if (rightmost.leader() == null || rightmost.leader().isEmpty()) {
            return false;
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

    /**
     * run \uC758 \uC778\uB77C\uC778 \uADF8\uB798\uD53D\uC774 \uBAA8\uB450 \uADFC\uD638 \uC9C0\uBD95(vinculum) \uC7A5\uC2DD\uC6A9 Rectangle \uC778\uC9C0 \uD655\uC778.
     *
     * <p>vinculum \uC740 radicand \uC704\uC5D0 \uADF8\uC5B4\uC9C0\uB294 \uAC00\uB85C \uB9C9\uB300\uB85C, \uC774\uBBF8\uC9C0\u00B7\uD14D\uC2A4\uD2B8 \uC5C6\uB294 \uC587\uC740
     * \uAC00\uB85C Rectangle \uC774\uB2E4(\uC2E4\uCE21: 1\uB2E8\uC6D0 p22 \u221A \uC9C0\uBD95, 28.3\u00D75.7pt). HWP sqrt \uAC00 \uC9C0\uBD95\uC744
     * \uC790\uCCB4 \uB80C\uB354\uD558\uBBC0\uB85C \uC774 Rectangle \uC740 \uBC84\uB9AC\uACE0 radicand \uB97C \uADFC\uD638\uC5D0 \uB123\uC5B4\uC57C \uD55C\uB2E4. \uC2E4\uC81C
     * \uC778\uB77C\uC778 \uD504\uB808\uC784/\uC774\uBBF8\uC9C0/\uD14D\uC2A4\uD2B8\uAC00 \uD558\uB098\uB77C\uB3C4 \uC788\uC73C\uBA74 false(\uC815\uC0C1 \uC778\uB77C\uC778 \uBC30\uCE58 \uB300\uC0C1).
     */
    /** #RRGGBB 가 무채색(회색조)인지 — r=g=b 근사(±8). */
    private static boolean isGrayscaleHex(String hex) {
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return Math.abs(r - g) <= 8 && Math.abs(g - b) <= 8 && Math.abs(r - b) <= 8;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean allInlineGraphicsAreVinculum(IDMLCharacterRun run) {
        if (run == null) return false;
        java.util.List<IDMLCharacterRun.InlineGraphic> gfx = run.inlineGraphics();
        if (gfx == null || gfx.isEmpty()) return false;
        for (IDMLCharacterRun.InlineGraphic g : gfx) {
            if (g == null) return false;
            if (!"rectangle".equalsIgnoreCase(g.type())) return false;
            if (g.linkResourceURI() != null) return false;      // \uC774\uBBF8\uC9C0 \uB2F4\uC740 \uD504\uB808\uC784
            if (g.embeddedText() != null && !g.embeddedText().isEmpty()) return false;
            if (g.childGraphics() != null && !g.childGraphics().isEmpty()) return false;
            if (g.childTextFrames() != null && !g.childTextFrames().isEmpty()) return false;
            // \uAC00\uB85C \uB9C9\uB300(vinculum): \uD3ED\uC774 \uB192\uC774\uBCF4\uB2E4 \uB69C\uB837\uD558\uAC8C \uCEE4\uC57C \uD55C\uB2E4(\uC885\uD6A1\uBE44 \u2265 2).
            // \uADFC\uD638 \uC9C0\uBD95 \uC2A4\uD398\uC774\uC11C\uB294 28.3\u00D75.7(\u22485:1). \uC815\uC0AC\uAC01\uD615\uC5D0 \uAC00\uAE4C\uC6B4 \uB3C4\uD615\uC740 \uC81C\uC678 \u2014
            // \uBD80\uB4F1\uD638 \uBE48 \uB2F5\uB780 \uBC15\uC2A4(14.17\u00D714.17, \uBD80\uB3D9\uC18C\uC218 \uC624\uCC28\uB85C \uD3ED>\uB192\uC774\uAC00 \uB418\uB358 \uCF00\uC774\uC2A4)\uAC00
            // vinculum \uC73C\uB85C \uC624\uD310\uB3FC \uC0AD\uC81C\uB418\uB358 \uBB38\uC81C \uBC29\uC9C0(\uC2E4\uCE21: 1\uB2E8\uC6D0 p26 \uBB38\uC81C6 \uBE48\uCE78).
            if (!(g.widthPoints() >= g.heightPoints() * 2.0)) return false;
        }
        return true;
    }

    /**
     * run 의 인라인 그래픽이 모두 빈 답란 박스(정사각형에 가까운 Rectangle)인지 확인.
     *
     * <p>부등호·빈칸 채우기 문제의 답란 네모칸(실측: 1단원 p26 문제6, 14.17×14.17pt).
     * 이미지·텍스트·자식 없는 Rectangle 이고 종횡비가 대략 정사각(폭 < 높이×2)이다.
     * vinculum 스페이서(가로 막대, 폭 ≥ 높이×2)의 반대 케이스. 이 박스는 삭제하지
     * 않고 근호 뒤에 인라인 배치해야 한다.
     */
    private static boolean allInlineGraphicsAreEmptyAnswerBox(IDMLCharacterRun run) {
        if (run == null) return false;
        java.util.List<IDMLCharacterRun.InlineGraphic> gfx = run.inlineGraphics();
        if (gfx == null || gfx.isEmpty()) return false;
        for (IDMLCharacterRun.InlineGraphic g : gfx) {
            if (g == null) return false;
            if (!"rectangle".equalsIgnoreCase(g.type())) return false;
            if (g.linkResourceURI() != null) return false;
            if (g.embeddedText() != null && !g.embeddedText().isEmpty()) return false;
            if (g.childGraphics() != null && !g.childGraphics().isEmpty()) return false;
            if (g.childTextFrames() != null && !g.childTextFrames().isEmpty()) return false;
            if (g.widthPoints() <= 0 || g.heightPoints() <= 0) return false;
            // 정사각형에 가까움: 폭 < 높이×2 (가로 막대 vinculum 제외)
            if (g.widthPoints() >= g.heightPoints() * 2.0) return false;
        }
        return true;
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
                List<ASTInlineItem> plannedItems =
                        InlineFrameHandler.loadPlannedInlineAnchorItems(ctx, anchoredId, null, null);
                if (plannedItems != null && !plannedItems.isEmpty()) {
                    appendInlineItemsKeepingObjectsInline(para, plannedItems);
                    inserted = true;
                }
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
        if (hasStage1ObjectPlans(ctx)) {
            if (hasSourceAutoPageNumberMarker(ctx, tf)) return true;
            if (legacyLooksLikeInlinePageNumberFrame(ctx, tf)) {
                warnPageNumberRoleHeuristicSuppressed(ctx, domId, "idml_story");
            }
            return false;
        }
        return legacyLooksLikeInlinePageNumberFrame(ctx, tf);
    }

    private static boolean legacyLooksLikeInlinePageNumberFrame(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf) {
        if (ctx == null || tf == null) return false;
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

    private static boolean hasSourceAutoPageNumberMarker(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (containsAutoPageNumberMarker(tf.frameVisibleText())) return true;
        if (ctx == null || ctx.resolvedData == null || tf.storyId() == null) return false;
        ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph rp : story.paragraphs()) {
            if (rp == null || rp.runs() == null) continue;
            for (ResolvedRun run : rp.runs()) {
                if (run != null && containsAutoPageNumberMarker(run.text())) return true;
            }
        }
        return false;
    }

    private static boolean containsAutoPageNumberMarker(String text) {
        return text != null && text.indexOf('\u0018') >= 0;
    }

    private static void warnPageNumberRoleHeuristicSuppressed(
            ResolvedBuildContext ctx,
            int anchoredId,
            String surface) {
        if (ctx == null) return;
        ctx.ownershipWarningLines.add("{\"code\":\"STAGE2_PAGE_NUMBER_ROLE_HEURISTIC_SUPPRESSED\""
                + ",\"anchoredObjectId\":" + anchoredId
                + ",\"surface\":\"" + ObjectPlan.escape(surface) + "\""
                + ",\"detail\":\"Stage 1 ObjectPlans are present; page-number role must come from source metadata, not digit text or frame bounds\"}");
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
            if (hasCellOwnedNestedStoryRef(ctx, idmlCell, storyRef)) continue;
            if (isStoryOwnedByPlacedTextFrame(ctx, storyRef)) return true;
        }
        return false;
    }

    private static boolean hasCellOwnedNestedStoryRef(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell) {
        if (idmlCell == null || idmlCell.textFrameStoryRefs() == null) return false;
        for (String storyRef : idmlCell.textFrameStoryRefs()) {
            if (hasCellOwnedNestedStoryRef(ctx, idmlCell, storyRef)) return true;
        }
        return false;
    }

    private static boolean hasCellOwnedNestedStoryRef(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell,
            String storyRef) {
        return StoryFlowAssembler.shouldCellConsumeNestedStoryRef(ctx, idmlCell, storyRef);
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

    static void enrichMathFontsFromResolvedRuns(ResolvedBuildContext ctx,
            List<IDMLCharacterRun> runs, List<ResolvedRun> resolvedRuns) {
        if (runs == null || runs.isEmpty() || resolvedRuns == null || resolvedRuns.isEmpty()) {
            return;
        }
        int resolvedRunIdx = 0;
        int savedMatchCursor = ctx != null && ctx.lastMatchResult != null
                ? ctx.lastMatchResult[0] : -1;
        for (IDMLCharacterRun run : runs) {
            if (run == null || run.content() == null || run.content().isEmpty()) {
                continue;
            }
            ResolvedRun matched = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, run.content());
            if (matched == null) {
                continue;
            }
            MatchConfidence confidence = RunBuilder.confidenceForResolvedRunMatch(matched, run.content());
            if (confidence == MatchConfidence.HIGH) {
                applyResolvedMathFontForClassification(run, matched);
                if (ctx != null && ctx.lastMatchResult != null) {
                    resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                }
            }
        }
        if (ctx != null && ctx.lastMatchResult != null) {
            ctx.lastMatchResult[0] = savedMatchCursor;
        }
    }

    static void applyResolvedMathFontForClassification(IDMLCharacterRun run, ResolvedRun resolvedRun) {
        if (run == null || resolvedRun == null) {
            return;
        }
        String fontFamily = resolvedRun.fontFamily();
        boolean runMathFont = isMathFontFamily(run.fontFamily());
        boolean resolvedMathFont = isMathFontFamily(fontFamily);
        if (!runMathFont && !resolvedMathFont) {
            return;
        }
        if (run.fontFamily() == null && resolvedMathFont) {
            run.fontFamily(fontFamily);
        }
        if (run.fontStyle() == null && resolvedRun.fontStyle() != null) {
            run.fontStyle(resolvedRun.fontStyle());
        }
        if (run.fontSize() == null && resolvedRun.fontSize() != null && resolvedRun.fontSize() > 0) {
            run.fontSize(resolvedRun.fontSize());
        }
        if (resolvedRun.fillColor() != null) {
            run.fillColor(resolvedRun.fillColor());
        }
    }

    private static boolean isMathFontFamily(String fontFamily) {
        return EHFontGlyphMap.isEHFontFamily(fontFamily)
                || BTFontGlyphMap.isBTFontFamily(fontFamily)
                || NPFontGlyphMap.isNPFont(fontFamily);
    }

    /**
     * SPEC-080 Phase 2: `상부자(이탤릭)` charStyle 을 받은 수식 숫자 토큰인가. GREP 가
     * 수식 서체로 명시한 본문 숫자(제곱하여 4가 되는 수 → 4·25)를 ASTEquation 으로 직접
     * 방출하는 스코프. 과학 화학식은 `상부자(이탤릭)` 미사용(전수 0건)이라 안 걸린다.
     */
    private static boolean isGrepSangbujaItalicNumber(IDMLCharacterRun run) {
        if (run == null) return false;
        // GREP 로 적용된 charStyle 은 grepAppliedCharStyle 에 있다(appliedCharacterStyle 은
        // IDML 원본=[No character style]). 둘 다 확인한다.
        String cs = run.grepAppliedCharStyle() != null
                ? run.grepAppliedCharStyle() : run.appliedCharacterStyle();
        if (cs == null) return false;
        String norm = cs.toLowerCase(java.util.Locale.ROOT).replace("%3a", ":").replace("%25", "%");
        if (norm.contains("정체") || norm.contains("정자")) return false;
        if (!(norm.contains("상부자") && norm.contains("이탤릭"))) return false;
        String ct = run.content();
        if (ct == null) return false;
        String t = ct.trim();
        if (t.isEmpty()) return false;
        boolean hasDigit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') { hasDigit = true; continue; }
            if (c == '-' || c == '.' || c == ',') continue;
            return false;
        }
        return hasDigit;
    }

    /** 런에 IDML 인라인 앵커(프레임·그래픽·￼)가 있는가 — 직접 수식 방출 제외용. */
    private static boolean hasIdmlInlineAnchor(IDMLCharacterRun run) {
        if (run == null) return false;
        return (run.inlineFrames() != null && !run.inlineFrames().isEmpty())
                || (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty())
                || (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty())
                || (run.content() != null && run.content().indexOf('￼') >= 0);
    }

    /**
     * SPEC-080 Phase 2: idx 위치의 숫자가 <b>본문 문맥</b>(한글 문장 속)에 있는가.
     *
     * <p>순수 본문 숫자(제곱하여 4가 되는 수 → 4)는 앞뒤가 한글·문장부호·공백이거나
     * 문단 경계다. 수식 조각 숫자(72m²·3&lt;5·(sqrt{5})²)는 앞/뒤에 라틴·수식폰트·연산자가
     * 붙는다. 양쪽 이웃이 모두 본문 경계일 때만 직접 수식 방출해 수식 파괴를 막는다.</p>
     */
    private static boolean isBodyNumberContext(List<IDMLCharacterRun> runs, int idx) {
        return neighborIsBodyBoundary(runs, idx - 1, true)
                && neighborIsBodyBoundary(runs, idx + 1, false);
    }

    private static boolean neighborIsBodyBoundary(List<IDMLCharacterRun> runs, int n, boolean prev) {
        if (n < 0 || n >= runs.size()) return true; // 문단 경계 = 본문
        IDMLCharacterRun r = runs.get(n);
        if (r == null) return true;
        // 수식 폰트 이웃 = 수식 조각
        String ff = r.fontFamily();
        if ((ff != null && (EHFontGlyphMap.isEHFontFamily(ff) || BTFontGlyphMap.isBTFontFamily(ff)
                || NPFontGlyphMap.isNPFont(ff)))
                || r.isEHFont() || r.isBTFont() || r.isNPFont() || r.grepMathFont()) {
            return false;
        }
        String c = r.content();
        if (c == null || c.trim().isEmpty()) return true; // 공백 런 = 본문 경계
        // 인접 모서리 문자: 한글이면 본문, 라틴·숫자·연산자면 수식 조각
        String t = c.trim();
        char edge = prev ? t.charAt(t.length() - 1) : t.charAt(0);
        if ((edge >= 0xAC00 && edge <= 0xD7AF) || (edge >= 0x3131 && edge <= 0x318E)) return true;
        // 문장부호(쉼표·마침표·따옴표 등)도 본문 경계로 인정
        return ",.·:;)）'\"”’]".indexOf(edge) >= 0;
    }

    static boolean hasResolvedStructuralMathFont(String fontFamily) {
        return EHFontGlyphMap.isEHFontFamily(fontFamily)
                || NPFontGlyphMap.isNPFont(fontFamily)
                || (BTFontGlyphMap.isBTFontFamily(fontFamily)
                        && !BTFontGlyphMap.isBTBodyTextFont(fontFamily));
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
