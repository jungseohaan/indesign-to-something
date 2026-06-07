package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTMathGrouper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTRunConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.DoviraSubunitMarkerPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTabStop;
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
class StoryLoader {
    private StoryLoader() {}


    /**
     * IDML Story XML에서 단락을 파싱하여 ASTParagraph 리스트로 변환.
     * IDML의 단락 구조는 정확 (중복 없음, <Br/> 기반 분리).
     * 단락 속성(leading, indent)은 resolved에서 보강.
     */
    static List<ASTParagraph> convertStoryFromIDML(ResolvedBuildContext ctx, String storyId, boolean suppressLeftIndent) {
        if (ctx.idmlDir == null) return null;
        // storyId(DOM decimal) → IDML hex → Story_u{hex}.xml
        String sourceStoryId = sourceStoryId(storyId);
        String hexId;
        try {
            hexId = "u" + Integer.toHexString(Integer.parseInt(sourceStoryId));
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

        // cleanStyleName → alignment 캐시: 같은 스타일명이 수백 단락에 반복 적용될 때 중복 resolve 방지
        Map<String, String> styleAlignCache = new java.util.HashMap<>();

        List<ASTParagraph> paragraphs = new ArrayList<>();
        List<IDMLParagraph> idmlParas = idmlStory.paragraphs();
        for (int i = 0; i < idmlParas.size(); i++) {
            IDMLParagraph ip = idmlParas.get(i);
            ASTParagraph para = new ASTParagraph();
            boolean hasIdmlInlineAnchors = false; // FFFC 앵커 순서로 인라인 삽입된 경우 true
            ResolvedParagraph resolvedParagraph = (resolvedStory != null && i < resolvedStory.paragraphs().size())
                    ? resolvedStory.paragraphs().get(i)
                    : null;

            // 칼럼 브레이크 (ACE 8)
            if (ip.columnBreakAfter()) {
                para.columnBreakAfter(true);
            }

            // 단락 스타일 (IDML)
            if (ip.appliedParagraphStyle() != null) {
                para.paragraphStyleRef(ip.appliedParagraphStyle());
            }

            // 단락 속성: resolved에서 가져옴 (정확한 pt 값)
            // 정렬 우선순위: IDML 단락(스토리) → IDML 스타일 → resolved 단락 → resolved top-level paragraphStyles
            // IDML ParagraphStyleRange의 Justification이 스타일 정의와 다를 경우 로컬 오버라이드이므로 최우선 적용
            {
                String idmlStyleName = ip.appliedParagraphStyle();
                String cleanStyleName = (idmlStyleName != null && idmlStyleName.contains("/"))
                        ? idmlStyleName.substring(idmlStyleName.lastIndexOf('/') + 1) : idmlStyleName;
                if (ip.justification() != null) {
                    para.alignment(ip.justification());
                } else {
                    String idmlStyleJust = cleanStyleName == null ? null
                            : styleAlignCache.computeIfAbsent(cleanStyleName,
                                k -> StoryConverter.resolveStyleAlignment(k, ctx.astDocument));
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
                }
                // alignment가 null이면 HwpxParagraphBuilder에서 baseStyle 또는 기본 JUSTIFY 적용
            }
            if (resolvedParagraph != null) {
                ResolvedParagraph rp = resolvedParagraph;
                // leading: resolved 우선 (실제 렌더링 값), IDML 스타일 fallback
                // 단, auto leading(>50pt = percentage 값)은 무시
                Double fixedLeading = rp.fixedLeading(); // resolved (실제 렌더링 값)
                if (fixedLeading == null || fixedLeading <= 0) {
                    fixedLeading = RunBuilder.getStyleLeading(ctx, ip.appliedParagraphStyle()); // IDML 스타일
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
                boolean preserveHangingTab = shouldPreserveNeutralHangingIndentForTab(rp);
                boolean neutralHangingIndent = isNeutralHangingIndent(rp.leftIndent(), rp.firstLineIndent());
                if (neutralHangingIndent) {
                    para.leftMargin(0L);
                    para.firstLineIndent(0L);
                } else if (suppressLeftIndent) {
                    para.leftMargin(0L); // 스타일 폴백 차단: baseStyle.leftMargin() 우선순위를 명시적 0으로 덮어씀
                } else if (rp.leftIndent() != null && rp.leftIndent() != 0) {
                    para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
                }
                if (!neutralHangingIndent && rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
                    para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
                }
                // 탭 스톱
                if (rp.hasTabStops()) {
                    // normalizeToPoints() 후 tabStop position과 leftIndent는 이미 pt 단위
                    // (applyScale에서 scaleFactor가 적용됨). 다시 scaleFactor를 곱하면 이중 적용.
                    double leftPt = (rp.leftIndent() != null ? rp.leftIndent() : 0);
                    for (ResolvedTabStop rts : rp.tabStops()) {
                        if (rts.position() != null && rts.position() > 0) {
                            double posPt = preserveHangingTab ? rts.position() : rts.position() - leftPt;
                            if (posPt < 0) posPt = 0;
                            String align = "left";
                            if (rts.alignment() != null) {
                                String a = rts.alignment().toLowerCase();
                                if (a.contains("center")) align = "center";
                                else if (a.contains("right")) align = "right";
                                else if (a.contains("decimal")) align = "decimal";
                            }
                            para.addTabStop(new ASTTabStop(
                                    CoordinateConverter.pointsToHwpunits(posPt), align, rts.leader()));
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
                    String styleJust = StoryConverter.resolveStyleAlignment(styleName, ctx.astDocument);
                    if (styleJust != null) para.alignment(styleJust);
                }
            }

            // resolved 런 (스타일 상속 보강용)
            List<ResolvedRun> resolvedRuns = null;
            if (resolvedStory != null && i < resolvedStory.paragraphs().size()) {
                resolvedRuns = resolvedStory.paragraphs().get(i).runs();
            }

            // SPEC-025: ACE 7 (IndentToHere) 감지 — 첫 inline anchor 다음에 \u0007 (BEL) 또는
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

            // ParagraphStyle에서 FillColor/Tracking/FontFamily 미리 구해둠 (런에서 없을 때 사용)
            StoryConverter.StyleContext sc = new StoryConverter.StyleContext(
                    RunBuilder.getStyleFillColor(ctx, ip.appliedParagraphStyle()),
                    RunBuilder.getStyleTracking(ctx, ip.appliedParagraphStyle()),
                    RunBuilder.getStyleFontFamily(ctx, ip.appliedParagraphStyle()),
                    RunBuilder.getStyleFontSize(ctx, ip.appliedParagraphStyle()),
                    RunBuilder.getStyleUnderlineColor(ctx, ip.appliedParagraphStyle()));
            // tabStop이 있으면 \t 문자를 보존 (HwpxParagraphBuilder가 <hp:tab>으로 변환)
            sc.hasTabStops = para.hasTabStops();

            // 런 변환: IDML CharacterRun → ASTTextRun + 수식 그룹화
            // resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 (불릿/특수문자 런 회피)
            ResolvedRun defaultRR = RunBuilder.findDefaultResolvedRun(ctx, resolvedRuns);
            int resolvedRunIdx = 0;

            // 전처리: 한국어+수식마커 혼합 런 분리 + 원문자 변환
            List<IDMLCharacterRun> runs = ASTMathGrouper.splitMathKoreanMixedRuns(ip.characterRuns());
            ASTRunConverter.convertCircledNumberRuns(runs);
            addUnderlineBlankTabStop(ctx, storyId, i, para, runs);
            sc.hasTabStops = para.hasTabStops();

            // 수식 그룹화 상태
            List<IDMLCharacterRun> mathGroup = new ArrayList<>();
            List<IDMLCharacterRun> npMathGroup = new ArrayList<>();
            List<IDMLCharacterRun> ehMathGroup = new ArrayList<>();
            boolean firstTextRunAfterLeadingAnchor = false;

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
                            || (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content()));
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
                            if (!partText.isEmpty()) {
                                // resolved 런 스타일 차이가 있으면 분할 시도
                                boolean partSplit = false;
                                if (resolvedRuns != null && resolvedRuns.size() > 1 && RunBuilder.hasStyleVariation(ctx, resolvedRuns)) {
                                    partSplit = RunBuilder.splitIdmlRunByResolvedRuns(ctx, run, partText, resolvedRuns, resolvedRunIdx,
                                            para, sc);
                                }
                                if (!partSplit) {
                                    ResolvedRun matchedRR = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, partText);
                                    if (matchedRR != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                                    ASTTextRun tr = RunBuilder.createRunFromIDML(ctx, run, partText, matchedRR != null ? matchedRR : defaultRR, sc);
                                    if (!RunBuilder.splitBulletRun(ctx, tr, para)) {
                                        RunBuilder.splitLatinVarsInMixedText(ctx, tr, para);
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
                                    // AnchoredPosition="Anchored" + TextWrapMode="None" Group:
                                    // 사전 스캔에서 등록됨 → 인라인 삽입 건너뛰고 Phase 3 후처리가 floating ASTFigure 로 배치.
                                    if (ctx.deferredAnchoredFloatingIds.contains(domId)) {
                                        anchorIdx++;
                                        continue;
                                    }
                                    // 커스텀 위치 앵커가 부모 범위 밖이면 인라인 흐름에는 넣지 않는다.
                                    // 단, inline_object PNG가 있으면 시각 장식이므로 절대 좌표 floating으로 보존한다.
                                    if (InlineFrameHandler.isAnchoredOutsideParentByTextFrame(ctx, domId, storyId)) {
                                        if (hasInlineObjectPng(ctx, domId)) {
                                            ctx.deferredAnchoredFloatingIds.add(domId);
                                        }
                                        anchorIdx++;
                                        continue;
                                    }
                                    String nextPartText = (pi + 1 < parts.length) ? parts[pi + 1] : null;
                                    if (!InlineFrameHandler.isInlineVocabularyMarker(ctx, domId, partText, nextPartText)) {
                                        maybeInsertDecorativeLeaderTab(ctx, ip, run, inlineHexId, partText, para);
                                    }
                                    // SPEC-025: Group 앵커가 다수의 박스(예: 자모 배지 ㅍㅎㅂㅅ) 면 각 자식 TF 를
                                    // 박스 스타일 inline TextFrame 으로 개별 분해 → 검색 가능 + 시각 박스 보존.
                                    java.util.List<ASTInlineObject> boxList =
                                            InlineFrameHandler.tryInlineGroupAsBoxList(ctx, domId);
                                    if (boxList != null && !boxList.isEmpty()) {
                                        for (ASTInlineObject box : boxList) para.addItem(box);
                                    } else {
                                        // 분수 구조 인라인 TextFrame(2단락) → 수식으로 변환
                                        ASTEquation fracEq = InlineFrameHandler.tryInlineFractionAsEquation(ctx, domId);
                                        if (fracEq != null) {
                                            para.addItem(fracEq);
                                        } else {
                                            // 짧은 텍스트 인라인 TextFrame → 텍스트 런으로 변환
                                            ASTTextRun textRun = InlineFrameHandler.tryInlineTextFrameAsRun(ctx, domId,
                                                    partText, nextPartText);
                                            if (textRun != null) {
                                                para.addItem(textRun);
                                            } else {
                                                // SPEC-025: 빈 inline TextFrame 이지만 fillColor 가 있으면 데코 박스 (예: 본문 빈칸 강조 박스)
                                                ASTInlineObject emptyBox = InlineFrameHandler.tryInlineEmptyFilledBoxAsFrame(ctx, domId);
                                                if (emptyBox != null) {
                                                    para.addItem(emptyBox);
                                                } else {
                                                    // 배경 도형 + 단일 짧은 텍스트프레임 (예: 페이지 39 "가" / "나" 캡슐 배지)
                                                    // → INLINE_TEXT_FRAME (한 몸 + 검색 가능)
                                                    ASTInlineObject singleBadge = InlineFrameHandler.tryInlineGroupAsSingleBadge(ctx, domId);
                                                    if (singleBadge != null) {
                                                        para.addItem(singleBadge);
                                                    } else {
                                                        ASTInlineObject inlineObj = InlineFrameHandler.loadInlineObject(ctx, domId);
                                                        if (inlineObj != null) {
                                                            para.addItem(inlineObj);
                                                        }
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
                    } else {
                        if (firstTextRunAfterLeadingAnchor) {
                            text = StoryConverter.stripLeadingAnchorLayoutSpaces(text);
                            firstTextRunAfterLeadingAnchor = false;
                        }
                        // GREP 스타일 분할: IDML 단일 런이 resolved에서 여러 런(다른 색상/폰트)으로 분할된 경우
                        // resolved 런 경계에서 IDML 런을 분할하여 각각의 색상을 적용
                        boolean splitByResolved = false;
                        if (resolvedRuns != null && resolvedRuns.size() > 1 && RunBuilder.hasStyleVariation(ctx, resolvedRuns)) {
                            splitByResolved = RunBuilder.splitIdmlRunByResolvedRuns(ctx, run, text, resolvedRuns, resolvedRunIdx,
                                    para, sc);
                        }
                        if (!splitByResolved) {
                            ResolvedRun matchedRR2 = RunBuilder.findResolvedRun(ctx, resolvedRuns, resolvedRunIdx, text);
                            if (matchedRR2 != null) resolvedRunIdx = ctx.lastMatchResult[0] + 1;
                            ASTTextRun tr = RunBuilder.createRunFromIDML(ctx, run, text, matchedRR2 != null ? matchedRR2 : defaultRR, sc);
                            // ;...; 분수 GREP 패턴이 포함된 텍스트 → 분수 수식으로 분리
                            if (!RunBuilder.splitBulletRun(ctx, tr, para)) {
                                if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                                    MathProcessor.splitFractionPatternInText(ctx, text, tr, para);
                                } else {
                                    RunBuilder.splitLatinVarsInMixedText(ctx, tr, para);
                                }
                            }
                        }
                    }
                }
            }
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

            paragraphs.add(para);
        }

        StoryConverter.removeDuplicateDoviraLeadingMarkers(ctx, storyId, paragraphs);
        return paragraphs;
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
                String text = ((ASTTextRun) item).text();
                if (text == null || text.trim().isEmpty()) continue;
                if (isPageNumberText(text)) {
                    if (i > 0 && items.get(i - 1) instanceof ASTTextRun) {
                        String prev = ((ASTTextRun) items.get(i - 1)).text();
                        if ("\t".equals(prev)) return;
                    }
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
            if (i > 0 && items.get(i - 1) instanceof ASTTextRun) {
                String prev = ((ASTTextRun) items.get(i - 1)).text();
                if ("\t".equals(prev)) return;
            }
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
        rightmost.leader(".");
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
        para.addTabStop(new ASTTabStop(pos, "left", null));
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

    private static boolean hasInlineObjectPng(ResolvedBuildContext ctx, int objectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return false;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != objectId) continue;
            if (!"inline_object".equals(rg.itemType())) continue;
            String file = rg.file();
            if (file == null || file.isEmpty()) continue;
            return ctx.basePath == null || new File(ctx.basePath, file).exists();
        }
        return false;
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
}
